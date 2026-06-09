/**
 * ConduitVirtualCamera — CMIO DAL plug-in
 *
 * Registers "Conduit Virtual Camera" with macOS so Zoom, FaceTime, etc. see it
 * as a real camera device. Frames come from the Conduit Mac app via a tiny IPC
 * file: ~/Library/Application Support/Conduit/camera-source.json
 * { "host": "192.168.x.x", "port": 5337 }
 *
 * The plugin connects directly to the phone's CameraServer TCP stream (same
 * Annex-B wire format as ScreenServer), decodes H.264 via VideoToolbox, and
 * pushes CVPixelBuffers into a CMSimpleQueue that camera clients drain.
 *
 * Install:  cp -R ConduitVirtualCamera.plugin ~/Library/CoreMediaIO/Plug-Ins/DAL/
 * Rebuild:  make (in virtual-camera/)
 *
 * Note: CMIOObjectsPublishedAndDied is deprecated since macOS 12.3 in favour of
 * Camera Extensions, but DAL plug-ins continue to work as a compatibility path.
 */

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"

#import <CoreMediaIO/CMIOHardwarePlugin.h>
#import <CoreMediaIO/CMIOHardwareObject.h>
#import <CoreMediaIO/CMIOHardwareDevice.h>
#import <CoreMediaIO/CMIOHardwareStream.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>
#import <Foundation/Foundation.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/tcp.h>
#include <unistd.h>
#include <pthread.h>
#include <stdatomic.h>

// ─── Object IDs ────────────────────────────────────────────────────────────
#define kDeviceID   ((CMIOObjectID)1001)
#define kStreamID   ((CMIOObjectID)1002)

// ─── Transport type value (same FourCC as CoreAudio's kAudioDeviceTransportTypeVirtual)
#define kTransportVirtual ((UInt32)'virt')

// ─── Source file written by the Conduit Mac app ────────────────────────────
static NSString *SourceFilePath(void) {
    return [@"~/Library/Application Support/Conduit/camera-source.json"
               stringByExpandingTildeInPath];
}

// ─── Default advertised stream format: 1280×720 NV12 ──────────────────────
static CMVideoFormatDescriptionRef DefaultFormat(void) {
    static CMVideoFormatDescriptionRef fmt = NULL;
    static dispatch_once_t tok;
    dispatch_once(&tok, ^{
        CMVideoFormatDescriptionCreate(kCFAllocatorDefault,
            kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            1280, 720, NULL, &fmt);
    });
    return fmt;
}

// ─── Plugin instance ───────────────────────────────────────────────────────
// COM-style: vtable pointer MUST be the first field so that
// (CMIOHardwarePlugInRef)&inst resolves correctly.
typedef struct {
    CMIOHardwarePlugInInterface *vtablePtr;  // ← first field = COM vtable pointer
    UInt32                       refCount;
    CMIOObjectID                 plugInObjectID;

    // Stream buffer queue (returned to camera clients via StreamCopyBufferQueue)
    CMSimpleQueueRef                    queue;
    CMIODeviceStreamQueueAlteredProc    queueAlteredProc;
    void                               *queueAlteredRefCon;

    // Reader thread
    pthread_t   readerThread;
    atomic_bool streaming;
    atomic_int  sockfd;   // -1 when not connected

    // H.264 decoder
    VTDecompressionSessionRef   decompSession;
    CMVideoFormatDescriptionRef streamFormat; // updated when SPS/PPS arrives
} PluginInstance;

static PluginInstance *gInst = NULL;   // single instance per load

// ─── Helpers ───────────────────────────────────────────────────────────────

static BOOL ReadFully(int fd, void *buf, size_t len, atomic_bool *running) {
    uint8_t *p = (uint8_t *)buf;
    size_t got = 0;
    while (got < len) {
        if (!atomic_load(running)) return NO;
        ssize_t n = recv(fd, p + got, len - got, 0);
        if (n <= 0) return NO;
        got += n;
    }
    return YES;
}

// Parse Annex-B blob → SPS/PPS → CMVideoFormatDescriptionRef
static CMVideoFormatDescriptionRef FormatFromAnnexB(const uint8_t *b, size_t n) {
    NSData *sps = nil, *pps = nil;
    size_t i = 0;
    while (i + 3 <= n) {
        if (b[i]==0 && b[i+1]==0 && b[i+2]==1) {
            size_t s = i + 3;
            size_t e = n;
            for (size_t j = s; j + 2 < n; j++) {
                if (b[j]==0 && b[j+1]==0 && b[j+2]==1) { e = j; break; }
            }
            while (e > s && b[e-1]==0) e--;
            if (e > s) {
                uint8_t t = b[s] & 0x1F;
                if (t == 7) sps = [NSData dataWithBytes:b+s length:e-s];
                else if (t == 8) pps = [NSData dataWithBytes:b+s length:e-s];
            }
            i = (e > i+3) ? e : i+1;
        } else { i++; }
    }
    if (!sps || !pps) return NULL;
    const uint8_t *params[2] = { (const uint8_t *)sps.bytes, (const uint8_t *)pps.bytes };
    size_t sizes[2] = { sps.length, pps.length };
    CMVideoFormatDescriptionRef fmt = NULL;
    CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, 2, params, sizes, 4, &fmt);
    return fmt;
}

// ─── VTDecompression callback ──────────────────────────────────────────────

static void VTCallback(void *refCon, void *sourceFrameRefCon,
                        OSStatus status, VTDecodeInfoFlags flags,
                        CVImageBufferRef imageBuffer,
                        CMTime pts, CMTime duration) {
    if (status != noErr || !imageBuffer) return;
    PluginInstance *inst = (PluginInstance *)refCon;
    if (!atomic_load(&inst->streaming) || !inst->queue) return;

    CMVideoFormatDescriptionRef fmt = NULL;
    CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, imageBuffer, &fmt);
    if (!fmt) return;

    CMSampleTimingInfo timing = {
        .duration = CMTimeMake(1, 30),
        .presentationTimeStamp = CMClockGetTime(CMClockGetHostTimeClock()),
        .decodeTimeStamp = kCMTimeInvalid,
    };
    CMSampleBufferRef sb = NULL;
    CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, imageBuffer, fmt, &timing, &sb);
    CFRelease(fmt);
    if (!sb) return;

    CFArrayRef atts = CMSampleBufferGetSampleAttachmentsArray(sb, YES);
    if (atts && CFArrayGetCount(atts) > 0) {
        CFMutableDictionaryRef d = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(atts, 0);
        CFDictionarySetValue(d, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
    }

    // Discard if queue is full rather than blocking
    if (CMSimpleQueueGetCount(inst->queue) < CMSimpleQueueGetCapacity(inst->queue)) {
        CMSimpleQueueEnqueue(inst->queue, sb); // queue holds the +1 from create; caller releases
        if (inst->queueAlteredProc)
            inst->queueAlteredProc(kStreamID, inst->queue, inst->queueAlteredRefCon);
    } else {
        CFRelease(sb);
    }
}

// ─── Decode one Annex-B access unit ───────────────────────────────────────

static void DecodeAnnexB(PluginInstance *inst, const uint8_t *b, size_t n) {
    if (!inst->decompSession || !inst->streamFormat) return;
    NSMutableData *avcc = [NSMutableData data];
    size_t i = 0;
    while (i + 3 <= n) {
        if (b[i]==0 && b[i+1]==0 && b[i+2]==1) {
            size_t s = i+3, e = n;
            for (size_t j = s; j+2 < n; j++) {
                if (b[j]==0 && b[j+1]==0 && b[j+2]==1) { e = j; break; }
            }
            while (e > s && b[e-1]==0) e--;
            if (e > s) {
                size_t len = e - s;
                uint8_t hdr[4] = {(uint8_t)(len>>24),(uint8_t)(len>>16),
                                   (uint8_t)(len>>8),(uint8_t)len};
                [avcc appendBytes:hdr length:4];
                [avcc appendBytes:b+s length:len];
            }
            i = (e > i+3) ? e : i+1;
        } else { i++; }
    }
    if (!avcc.length) return;

    void *block = malloc(avcc.length);
    if (!block) return;
    memcpy(block, avcc.bytes, avcc.length);
    CMBlockBufferRef bb = NULL;
    if (CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, block, avcc.length,
            kCFAllocatorMalloc, NULL, 0, avcc.length, 0, &bb) != noErr || !bb) {
        free(block); return;
    }
    CMSampleBufferRef sb = NULL;
    const size_t sz = avcc.length;
    CMSampleBufferCreateReady(kCFAllocatorDefault, bb, inst->streamFormat,
                               1, 0, NULL, 1, &sz, &sb);
    CFRelease(bb);
    if (!sb) return;
    VTDecompressionSessionDecodeFrame(inst->decompSession, sb, 0, NULL, NULL);
    CFRelease(sb);
}

// ─── Reader thread ─────────────────────────────────────────────────────────

static void *ReaderThread(void *arg) {
    @autoreleasepool {
    PluginInstance *inst = (PluginInstance *)arg;

    while (atomic_load(&inst->streaming)) {
        // Wait for camera-source.json to appear
        NSData *d = [NSData dataWithContentsOfFile:SourceFilePath()];
        if (!d) { sleep(2); continue; }
        NSDictionary *src = [NSJSONSerialization JSONObjectWithData:d options:0 error:nil];
        NSString *host = src[@"host"];
        int port = [src[@"port"] intValue];
        if (!host.length || port <= 0) { sleep(2); continue; }

        // Connect to phone's CameraServer
        struct addrinfo hints; memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
        char portStr[16]; snprintf(portStr, sizeof(portStr), "%d", port);
        struct addrinfo *res = NULL;
        if (getaddrinfo(host.UTF8String, portStr, &hints, &res) != 0 || !res) {
            sleep(2); continue;
        }
        int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
        if (fd < 0) { freeaddrinfo(res); sleep(2); continue; }
        if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
            close(fd); freeaddrinfo(res); sleep(2); continue;
        }
        freeaddrinfo(res);
        int one = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        atomic_store(&inst->sockfd, fd);

        // Drain the H.264 stream (5-byte header: 4-byte BE length + 1-byte flags)
        uint8_t hdr[5];
        while (atomic_load(&inst->streaming)) {
            if (!ReadFully(fd, hdr, 5, &inst->streaming)) break;
            uint32_t len = ((uint32_t)hdr[0]<<24)|((uint32_t)hdr[1]<<16)|
                           ((uint32_t)hdr[2]<<8)|(uint32_t)hdr[3];
            uint8_t flags = hdr[4];
            if (len == 0 || len > 16u*1024*1024) break;

            uint8_t *payload = (uint8_t *)malloc(len);
            if (!payload) break;
            if (!ReadFully(fd, payload, len, &inst->streaming)) { free(payload); break; }

            if (flags & 0x01) {
                // Config frame (SPS/PPS)
                CMVideoFormatDescriptionRef fmt = FormatFromAnnexB(payload, len);
                if (fmt) {
                    BOOL changed = (!inst->streamFormat ||
                                   !CMFormatDescriptionEqual(fmt, inst->streamFormat));
                    if (inst->streamFormat) CFRelease(inst->streamFormat);
                    inst->streamFormat = fmt;
                    if (changed) {
                        if (inst->decompSession) {
                            VTDecompressionSessionWaitForAsynchronousFrames(inst->decompSession);
                            VTDecompressionSessionInvalidate(inst->decompSession);
                            CFRelease(inst->decompSession);
                            inst->decompSession = NULL;
                        }
                        NSDictionary *destAttrs = @{
                            (id)kCVPixelBufferPixelFormatTypeKey:
                                @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
                            (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
                        };
                        VTDecompressionOutputCallbackRecord cb = {
                            VTCallback, inst
                        };
                        VTDecompressionSessionCreate(kCFAllocatorDefault, fmt, NULL,
                            (__bridge CFDictionaryRef)destAttrs, &cb, &inst->decompSession);
                    }
                }
            } else if (inst->decompSession) {
                DecodeAnnexB(inst, payload, len);
            }
            free(payload);
        }

        // Disconnected — clean up and retry
        close(fd);
        atomic_store(&inst->sockfd, -1);
        if (inst->decompSession) {
            VTDecompressionSessionWaitForAsynchronousFrames(inst->decompSession);
        }
        if (atomic_load(&inst->streaming)) sleep(2);
    }

    // Final cleanup
    if (inst->decompSession) {
        VTDecompressionSessionInvalidate(inst->decompSession);
        CFRelease(inst->decompSession);
        inst->decompSession = NULL;
    }
    if (inst->streamFormat) {
        CFRelease(inst->streamFormat);
        inst->streamFormat = NULL;
    }
    } // @autoreleasepool
    return NULL;
}

// ─── CMIO property helpers ─────────────────────────────────────────────────

#define BAIL(err) do { return (err); } while(0)

static BOOL IsPluginProp(CMIOObjectID oid, CMIOObjectPropertySelector sel) {
    if (oid != gInst->plugInObjectID) return NO;
    return sel == kCMIOObjectPropertyOwnedObjects || sel == kCMIOObjectPropertyName;
}
static BOOL IsDeviceProp(CMIOObjectID oid, CMIOObjectPropertySelector sel) {
    if (oid != kDeviceID) return NO;
    switch (sel) {
        case kCMIOObjectPropertyOwnedObjects:
        case kCMIOObjectPropertyName:
        case kCMIODevicePropertyDeviceUID:
        case kCMIODevicePropertyDeviceIsAlive:
        case kCMIODevicePropertyDeviceIsRunning:
        case kCMIODevicePropertyDeviceIsRunningSomewhere:
        case kCMIODevicePropertyDeviceCanBeDefaultDevice:
        case kCMIODevicePropertyStreams:
        case kCMIODevicePropertyTransportType:
        case kCMIODevicePropertyLinkedCoreAudioDeviceUID:
            return YES;
        default: return NO;
    }
}
static BOOL IsStreamProp(CMIOObjectID oid, CMIOObjectPropertySelector sel) {
    if (oid != kStreamID) return NO;
    switch (sel) {
        case kCMIOObjectPropertyOwnedObjects:
        case kCMIOStreamPropertyDirection:
        case kCMIOStreamPropertyFormatDescription:
        case kCMIOStreamPropertyFormatDescriptions:
        case kCMIOStreamPropertyFrameRate:
        case kCMIOStreamPropertyMinimumFrameRate:
        case kCMIOStreamPropertyFrameRates:
        case kCMIOStreamPropertyEndOfData:
            return YES;
        default: return NO;
    }
}

// ─── Vtable implementations ────────────────────────────────────────────────

static HRESULT QueryInterface(void *self, REFIID uuid, LPVOID *outIface) {
    // REFIID is CFUUIDBytes in the CFPlugin COM layer
    CFUUIDRef requested = CFUUIDCreateFromUUIDBytes(kCFAllocatorDefault, uuid);
    BOOL match = CFEqual(requested, kCMIOHardwarePlugInInterfaceID);
    CFRelease(requested);
    if (match) {
        *outIface = self;
        ((PluginInstance *)self)->refCount++;
        return S_OK;
    }
    *outIface = NULL;
    return (HRESULT)E_NOINTERFACE;
}
static ULONG AddRef(void *self) {
    return ++((PluginInstance *)self)->refCount;
}
static ULONG Release(void *self) {
    UInt32 c = --((PluginInstance *)self)->refCount;
    return c;
}

static OSStatus Initialize(CMIOHardwarePlugInRef self) { return noErr; }

static OSStatus InitializeWithObjectID(CMIOHardwarePlugInRef self, CMIOObjectID objectID) {
    PluginInstance *inst = (PluginInstance *)self;
    inst->plugInObjectID = objectID;

    // Announce device to the system, then stream to the device
    CMIOObjectID devID = kDeviceID, stID = kStreamID;
    OSStatus err = CMIOObjectsPublishedAndDied(self, kCMIOObjectSystemObject,
                                               1, &devID, 0, NULL);
    if (err != noErr) return err;
    return CMIOObjectsPublishedAndDied(self, kDeviceID, 1, &stID, 0, NULL);
}

static OSStatus Teardown(CMIOHardwarePlugInRef self) { return noErr; }

static void ObjectShow(CMIOHardwarePlugInRef self, CMIOObjectID oid) {}

static Boolean ObjectHasProperty(CMIOHardwarePlugInRef self,
                                  CMIOObjectID oid,
                                  const CMIOObjectPropertyAddress *addr) {
    return IsPluginProp(oid, addr->mSelector) ||
           IsDeviceProp(oid, addr->mSelector) ||
           IsStreamProp(oid, addr->mSelector);
}

static OSStatus ObjectIsPropertySettable(CMIOHardwarePlugInRef self,
                                          CMIOObjectID oid,
                                          const CMIOObjectPropertyAddress *addr,
                                          Boolean *outSettable) {
    if (!ObjectHasProperty(self, oid, addr)) BAIL(kCMIOHardwareUnknownPropertyError);
    *outSettable = NO;
    return noErr;
}

static OSStatus ObjectGetPropertyDataSize(CMIOHardwarePlugInRef self,
                                           CMIOObjectID oid,
                                           const CMIOObjectPropertyAddress *addr,
                                           UInt32 qualSize, const void *qualData,
                                           UInt32 *outSize) {
    CMIOObjectPropertySelector sel = addr->mSelector;
    if (IsPluginProp(oid, sel)) {
        switch (sel) {
            case kCMIOObjectPropertyOwnedObjects: *outSize = sizeof(CMIOObjectID); return noErr;
            case kCMIOObjectPropertyName:         *outSize = sizeof(CFStringRef);  return noErr;
        }
    }
    if (IsDeviceProp(oid, sel)) {
        switch (sel) {
            case kCMIOObjectPropertyOwnedObjects:          *outSize = sizeof(CMIOObjectID);    return noErr;
            case kCMIOObjectPropertyName:                  *outSize = sizeof(CFStringRef);     return noErr;
            case kCMIODevicePropertyDeviceUID:             *outSize = sizeof(CFStringRef);     return noErr;
            case kCMIODevicePropertyDeviceIsAlive:         *outSize = sizeof(UInt32);          return noErr;
            case kCMIODevicePropertyDeviceIsRunning:       *outSize = sizeof(UInt32);          return noErr;
            case kCMIODevicePropertyDeviceIsRunningSomewhere: *outSize = sizeof(UInt32);       return noErr;
            case kCMIODevicePropertyDeviceCanBeDefaultDevice: *outSize = sizeof(UInt32);       return noErr;
            case kCMIODevicePropertyStreams:               *outSize = sizeof(CMIOObjectID);    return noErr;
            case kCMIODevicePropertyTransportType:         *outSize = sizeof(UInt32);          return noErr;
            case kCMIODevicePropertyLinkedCoreAudioDeviceUID: *outSize = sizeof(CFStringRef);  return noErr;
        }
    }
    if (IsStreamProp(oid, sel)) {
        switch (sel) {
            case kCMIOObjectPropertyOwnedObjects:          *outSize = 0;                       return noErr;
            case kCMIOStreamPropertyDirection:             *outSize = sizeof(UInt32);          return noErr;
            case kCMIOStreamPropertyFormatDescription:     *outSize = sizeof(CMFormatDescriptionRef); return noErr;
            case kCMIOStreamPropertyFormatDescriptions:    *outSize = sizeof(CFArrayRef);      return noErr;
            case kCMIOStreamPropertyFrameRate:             *outSize = sizeof(Float64);         return noErr;
            case kCMIOStreamPropertyMinimumFrameRate:      *outSize = sizeof(Float64);         return noErr;
            case kCMIOStreamPropertyFrameRates:            *outSize = sizeof(Float64);         return noErr;
            case kCMIOStreamPropertyEndOfData:             *outSize = sizeof(UInt32);          return noErr;
        }
    }
    return kCMIOHardwareUnknownPropertyError;
}

static OSStatus ObjectGetPropertyData(CMIOHardwarePlugInRef self,
                                       CMIOObjectID oid,
                                       const CMIOObjectPropertyAddress *addr,
                                       UInt32 qualSize, const void *qualData,
                                       UInt32 dataSize, UInt32 *outUsed, void *outData) {
    PluginInstance *inst = (PluginInstance *)self;
    CMIOObjectPropertySelector sel = addr->mSelector;

    // ── Plugin ─────────────────────────────────────────────────────────────
    if (IsPluginProp(oid, sel)) {
        switch (sel) {
            case kCMIOObjectPropertyOwnedObjects:
                *outUsed = sizeof(CMIOObjectID);
                *(CMIOObjectID *)outData = kDeviceID;
                return noErr;
            case kCMIOObjectPropertyName:
                *outUsed = sizeof(CFStringRef);
                *(CFStringRef *)outData = CFSTR("Conduit Virtual Camera Plugin");
                CFRetain(*(CFStringRef *)outData);
                return noErr;
        }
    }

    // ── Device ─────────────────────────────────────────────────────────────
    if (IsDeviceProp(oid, sel)) {
        switch (sel) {
            case kCMIOObjectPropertyOwnedObjects:
                *outUsed = sizeof(CMIOObjectID);
                *(CMIOObjectID *)outData = kStreamID;
                return noErr;
            case kCMIOObjectPropertyName:
                *outUsed = sizeof(CFStringRef);
                *(CFStringRef *)outData = CFSTR("Conduit Virtual Camera");
                CFRetain(*(CFStringRef *)outData);
                return noErr;
            case kCMIODevicePropertyDeviceUID:
                *outUsed = sizeof(CFStringRef);
                *(CFStringRef *)outData = CFSTR("com.conduit.virtualcamera.0");
                CFRetain(*(CFStringRef *)outData);
                return noErr;
            case kCMIODevicePropertyDeviceIsAlive:
                *outUsed = sizeof(UInt32); *(UInt32 *)outData = 1; return noErr;
            case kCMIODevicePropertyDeviceIsRunning:
                *outUsed = sizeof(UInt32);
                *(UInt32 *)outData = atomic_load(&inst->streaming) ? 1 : 0;
                return noErr;
            case kCMIODevicePropertyDeviceIsRunningSomewhere:
                *outUsed = sizeof(UInt32);
                *(UInt32 *)outData = atomic_load(&inst->streaming) ? 1 : 0;
                return noErr;
            case kCMIODevicePropertyDeviceCanBeDefaultDevice:
                *outUsed = sizeof(UInt32); *(UInt32 *)outData = 1; return noErr;
            case kCMIODevicePropertyStreams:
                *outUsed = sizeof(CMIOObjectID);
                *(CMIOObjectID *)outData = kStreamID;
                return noErr;
            case kCMIODevicePropertyTransportType:
                *outUsed = sizeof(UInt32); *(UInt32 *)outData = kTransportVirtual; return noErr;
            case kCMIODevicePropertyLinkedCoreAudioDeviceUID:
                *outUsed = sizeof(CFStringRef);
                *(CFStringRef *)outData = CFSTR("");
                CFRetain(*(CFStringRef *)outData);
                return noErr;
        }
    }

    // ── Stream ─────────────────────────────────────────────────────────────
    if (IsStreamProp(oid, sel)) {
        switch (sel) {
            case kCMIOObjectPropertyOwnedObjects:
                *outUsed = 0; return noErr;
            case kCMIOStreamPropertyDirection:
                *outUsed = sizeof(UInt32); *(UInt32 *)outData = 0; return noErr; // 0 = source
            case kCMIOStreamPropertyFormatDescription: {
                CMVideoFormatDescriptionRef fmt =
                    inst->streamFormat ? inst->streamFormat : DefaultFormat();
                *outUsed = sizeof(CMFormatDescriptionRef);
                *(CMFormatDescriptionRef *)outData = fmt;
                if (fmt) CFRetain(fmt);
                return noErr;
            }
            case kCMIOStreamPropertyFormatDescriptions: {
                CMVideoFormatDescriptionRef fmt =
                    inst->streamFormat ? inst->streamFormat : DefaultFormat();
                CFArrayRef arr = CFArrayCreate(kCFAllocatorDefault,
                    (const void **)&fmt, fmt ? 1 : 0, &kCFTypeArrayCallBacks);
                *outUsed = sizeof(CFArrayRef);
                *(CFArrayRef *)outData = arr; // caller releases
                return noErr;
            }
            case kCMIOStreamPropertyFrameRate:
                *outUsed = sizeof(Float64); *(Float64 *)outData = 30.0; return noErr;
            case kCMIOStreamPropertyMinimumFrameRate:
                *outUsed = sizeof(Float64); *(Float64 *)outData = 0.0; return noErr;
            case kCMIOStreamPropertyFrameRates:
                *outUsed = sizeof(Float64); *(Float64 *)outData = 30.0; return noErr;
            case kCMIOStreamPropertyEndOfData:
                *outUsed = sizeof(UInt32); *(UInt32 *)outData = 0; return noErr;
        }
    }
    return kCMIOHardwareUnknownPropertyError;
}

static OSStatus ObjectSetPropertyData(CMIOHardwarePlugInRef self,
                                       CMIOObjectID oid,
                                       const CMIOObjectPropertyAddress *addr,
                                       UInt32 qualSize, const void *qualData,
                                       UInt32 dataSize, const void *data) {
    return kCMIOHardwareUnsupportedOperationError;
}

static OSStatus DeviceSuspend(CMIOHardwarePlugInRef self, CMIODeviceID dev) { return noErr; }
static OSStatus DeviceResume(CMIOHardwarePlugInRef self, CMIODeviceID dev)  { return noErr; }

static OSStatus DeviceStartStream(CMIOHardwarePlugInRef self,
                                   CMIODeviceID dev, CMIOStreamID stream) {
    PluginInstance *inst = (PluginInstance *)self;
    if (atomic_load(&inst->streaming)) return noErr; // idempotent
    atomic_store(&inst->streaming, YES);
    atomic_store(&inst->sockfd, -1);
    pthread_create(&inst->readerThread, NULL, ReaderThread, inst);
    return noErr;
}

static OSStatus DeviceStopStream(CMIOHardwarePlugInRef self,
                                  CMIODeviceID dev, CMIOStreamID stream) {
    PluginInstance *inst = (PluginInstance *)self;
    if (!atomic_load(&inst->streaming)) return noErr;
    atomic_store(&inst->streaming, NO);
    int fd = atomic_exchange(&inst->sockfd, -1);
    if (fd >= 0) { shutdown(fd, SHUT_RDWR); close(fd); }
    pthread_join(inst->readerThread, NULL);
    return noErr;
}

static OSStatus DeviceProcessAVCCommand(CMIOHardwarePlugInRef self,
                                         CMIODeviceID dev,
                                         CMIODeviceAVCCommand *cmd) {
    return kCMIOHardwareUnsupportedOperationError;
}
static OSStatus DeviceProcessRS422Command(CMIOHardwarePlugInRef self,
                                           CMIODeviceID dev,
                                           CMIODeviceRS422Command *cmd) {
    return kCMIOHardwareUnsupportedOperationError;
}

static OSStatus StreamCopyBufferQueue(CMIOHardwarePlugInRef self,
                                       CMIOStreamID stream,
                                       CMIODeviceStreamQueueAlteredProc proc,
                                       void *refCon,
                                       CMSimpleQueueRef *outQueue) {
    PluginInstance *inst = (PluginInstance *)self;
    if (!inst->queue) {
        CMSimpleQueueCreate(kCFAllocatorDefault, 60, &inst->queue);
    }
    inst->queueAlteredProc    = proc;
    inst->queueAlteredRefCon  = refCon;
    *outQueue = inst->queue;
    if (inst->queue) CFRetain(inst->queue); // caller owns +1
    return noErr;
}

static OSStatus StreamDeckPlay(CMIOHardwarePlugInRef s, CMIOStreamID st) {
    return kCMIOHardwareUnsupportedOperationError; }
static OSStatus StreamDeckStop(CMIOHardwarePlugInRef s, CMIOStreamID st) {
    return kCMIOHardwareUnsupportedOperationError; }
static OSStatus StreamDeckJog(CMIOHardwarePlugInRef s, CMIOStreamID st, SInt32 sp) {
    return kCMIOHardwareUnsupportedOperationError; }
static OSStatus StreamDeckCueTo(CMIOHardwarePlugInRef s, CMIOStreamID st,
                                 Float64 tc, Boolean poc) {
    return kCMIOHardwareUnsupportedOperationError; }

// ─── Static vtable ─────────────────────────────────────────────────────────

static CMIOHardwarePlugInInterface gVtable = {
    NULL,                       // _reserved
    QueryInterface,
    AddRef,
    Release,
    Initialize,
    InitializeWithObjectID,
    Teardown,
    ObjectShow,
    ObjectHasProperty,
    ObjectIsPropertySettable,
    ObjectGetPropertyDataSize,
    ObjectGetPropertyData,
    ObjectSetPropertyData,
    DeviceSuspend,
    DeviceResume,
    DeviceStartStream,
    DeviceStopStream,
    DeviceProcessAVCCommand,
    DeviceProcessRS422Command,
    StreamCopyBufferQueue,
    StreamDeckPlay,
    StreamDeckStop,
    StreamDeckJog,
    StreamDeckCueTo,
};

// ─── CFPlugin factory ───────────────────────────────────────────────────────

extern "C" void *ConduitVirtualCameraFactory(CFAllocatorRef allocator, CFUUIDRef typeUUID) {
    if (!CFEqual(typeUUID, kCMIOHardwarePlugInTypeID)) return NULL;
    if (!gInst) {
        gInst = (PluginInstance *)calloc(1, sizeof(PluginInstance));
        gInst->vtablePtr = &gVtable;
        gInst->refCount  = 1;
        atomic_init(&gInst->streaming, NO);
        atomic_init(&gInst->sockfd, -1);
    } else {
        gInst->refCount++;
    }
    return (void *)gInst; // CMIOHardwarePlugInRef = CMIOHardwarePlugInInterface** = &vtablePtr
}

#pragma clang diagnostic pop
