/*
 * ConduitMicrophone.c — macOS Audio Server Plugin (HAL plugin)
 *
 * Exposes "Conduit Microphone" as a virtual audio input device at 48 kHz mono Float32.
 * Reads PCM samples from a POSIX shared memory ring buffer (/conduit_mic_v1) that the
 * Conduit app populates in real time from the phone's microphone stream.
 *
 * Build and install:
 *   cd mac-app/macos/virtual-mic
 *   make install          # copies to ~/Library/Audio/Plug-Ins/HAL/
 *   sudo killall coreaudiod
 *
 * Then select "Conduit Microphone" as the microphone in Zoom / FaceTime / System Settings.
 *
 * Shared memory layout (must match ConduitMic in AppDelegate.mm):
 *   /conduit_mic_v1 → { volatile uint32_t writeIdx; uint8_t _pad[60]; float samples[4096]; }
 */

#include <CoreAudio/AudioServerPlugIn.h>
#include <CoreAudio/AudioHardware.h>
#include <CoreFoundation/CoreFoundation.h>
#include <mach/mach_time.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>

/* ── Shared memory (must match AppDelegate.mm) ─────────────────────────────── */

#define MIC_SHM_NAME  "/conduit_mic_v1"
#define MIC_RING_SIZE 4096u

typedef struct {
    volatile uint32_t writeIdx;
    uint8_t  _pad[60];
    float    samples[MIC_RING_SIZE];
} ConduitMicRing;

/* ── Object IDs ─────────────────────────────────────────────────────────────── */

#define kObjPlugin  ((AudioObjectID)1)
#define kObjDevice  ((AudioObjectID)2)
#define kObjStream  ((AudioObjectID)3)

/* ── Constants ──────────────────────────────────────────────────────────────── */

#define kSampleRate      48000.0
#define kBufFrameSize    512u

/* ── State ──────────────────────────────────────────────────────────────────── */

static AudioServerPlugInHostRef  gHost       = NULL;
static ConduitMicRing           *gRing       = NULL;
static pthread_mutex_t           gMutex      = PTHREAD_MUTEX_INITIALIZER;
static uint32_t                  gIOCount    = 0;
static uint64_t                  gAnchorHost = 0;
static double                    gAnchorSmpl = 0.0;
static mach_timebase_info_data_t gTB         = {0, 0};

/* ── Shared memory ──────────────────────────────────────────────────────────── */

static void shmOpen(void) {
    if (gRing) return;
    int fd = shm_open(MIC_SHM_NAME, O_RDWR | O_CREAT, 0666);
    if (fd < 0) return;
    size_t sz = sizeof(ConduitMicRing);
    ftruncate(fd, (off_t)sz);
    void *p = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p != MAP_FAILED) gRing = (ConduitMicRing *)p;
}

/* ── Helper ─────────────────────────────────────────────────────────────────── */

static AudioStreamBasicDescription streamFmt(void) {
    AudioStreamBasicDescription f;
    memset(&f, 0, sizeof(f));
    f.mSampleRate       = kSampleRate;
    f.mFormatID         = kAudioFormatLinearPCM;
    f.mFormatFlags      = kAudioFormatFlagsNativeFloatPacked | kAudioFormatFlagIsNonInterleaved;
    f.mBytesPerPacket   = 4;
    f.mFramesPerPacket  = 1;
    f.mBytesPerFrame    = 4;
    f.mChannelsPerFrame = 1;
    f.mBitsPerChannel   = 32;
    return f;
}

/* ── COM boilerplate ────────────────────────────────────────────────────────── */

static HRESULT QueryInterface(void *self, REFIID iid, LPVOID *out) {
    CFUUIDRef uuid = CFUUIDCreateFromUUIDBytes(NULL, iid);
    HRESULT r = E_NOINTERFACE;
    if (CFEqual(uuid, IUnknownUUID) || CFEqual(uuid, kAudioServerPlugInDriverInterfaceUUID)) {
        *out = self; r = S_OK;
    }
    CFRelease(uuid);
    return r;
}
static ULONG AddRef(void *self)  { (void)self; return 1; }
static ULONG Release(void *self) { (void)self; return 1; }

/* ── Initialize ─────────────────────────────────────────────────────────────── */

static OSStatus Initialize(AudioServerPlugInDriverRef d, AudioServerPlugInHostRef host) {
    (void)d;
    gHost = host;
    mach_timebase_info(&gTB);
    shmOpen();
    return noErr;
}

/* ── Device lifecycle no-ops ────────────────────────────────────────────────── */

static OSStatus CreateDevice(AudioServerPlugInDriverRef d, CFDictionaryRef desc,
    const AudioServerPlugInClientInfo *c, AudioObjectID *out)
    { (void)d; (void)desc; (void)c; (void)out; return kAudioHardwareUnsupportedOperationError; }

static OSStatus DestroyDevice(AudioServerPlugInDriverRef d, AudioObjectID id)
    { (void)d; (void)id; return kAudioHardwareUnsupportedOperationError; }

static OSStatus AddDeviceClient(AudioServerPlugInDriverRef d, AudioObjectID id,
    const AudioServerPlugInClientInfo *c)
    { (void)d; (void)id; (void)c; return noErr; }

static OSStatus RemoveDeviceClient(AudioServerPlugInDriverRef d, AudioObjectID id,
    const AudioServerPlugInClientInfo *c)
    { (void)d; (void)id; (void)c; return noErr; }

static OSStatus PerformDeviceConfigChange(AudioServerPlugInDriverRef d, AudioObjectID id,
    UInt64 act, void *info)
    { (void)d; (void)id; (void)act; (void)info; return noErr; }

static OSStatus AbortDeviceConfigChange(AudioServerPlugInDriverRef d, AudioObjectID id,
    UInt64 act, void *info)
    { (void)d; (void)id; (void)act; (void)info; return noErr; }

/* ── HasProperty ────────────────────────────────────────────────────────────── */

static Boolean HasProperty(AudioServerPlugInDriverRef d, AudioObjectID obj,
    pid_t pid, const AudioObjectPropertyAddress *addr) {
    (void)d; (void)pid;
    AudioObjectPropertySelector s = addr->mSelector;
    if (obj == kObjPlugin) {
        return s == kAudioObjectPropertyBaseClass || s == kAudioObjectPropertyClass ||
               s == kAudioObjectPropertyOwner    || s == kAudioObjectPropertyName ||
               s == kAudioObjectPropertyManufacturer ||
               s == kAudioPlugInPropertyDeviceList ||
               s == kAudioPlugInPropertyTranslateUIDToDevice ||
               s == kAudioObjectPropertyCustomPropertyInfoList;
    }
    if (obj == kObjDevice) {
        return s == kAudioObjectPropertyBaseClass || s == kAudioObjectPropertyClass ||
               s == kAudioObjectPropertyOwner    || s == kAudioObjectPropertyName ||
               s == kAudioObjectPropertyManufacturer ||
               s == kAudioDevicePropertyDeviceUID || s == kAudioDevicePropertyModelUID ||
               s == kAudioDevicePropertyTransportType || s == kAudioDevicePropertyRelatedDevices ||
               s == kAudioDevicePropertyClockDomain  || s == kAudioDevicePropertyDeviceIsAlive ||
               s == kAudioDevicePropertyDeviceIsRunning ||
               s == kAudioDevicePropertyDeviceCanBeDefaultDevice ||
               s == kAudioDevicePropertyDeviceCanBeDefaultSystemDevice ||
               s == kAudioDevicePropertyLatency || s == kAudioDevicePropertySafetyOffset ||
               s == kAudioDevicePropertyStreams ||
               s == kAudioDevicePropertyNominalSampleRate ||
               s == kAudioDevicePropertyAvailableNominalSampleRates ||
               s == kAudioDevicePropertyBufferFrameSize ||
               s == kAudioDevicePropertyBufferFrameSizeRange ||
               s == kAudioDevicePropertyIsHidden ||
               s == kAudioDevicePropertyPreferredChannelsForStereo ||
               s == kAudioDevicePropertyPreferredChannelLayout;
    }
    if (obj == kObjStream) {
        return s == kAudioObjectPropertyBaseClass || s == kAudioObjectPropertyClass ||
               s == kAudioObjectPropertyOwner    || s == kAudioObjectPropertyName ||
               s == kAudioStreamPropertyIsActive || s == kAudioStreamPropertyDirection ||
               s == kAudioStreamPropertyTerminalType || s == kAudioStreamPropertyStartingChannel ||
               s == kAudioStreamPropertyLatency  || s == kAudioStreamPropertyVirtualFormat ||
               s == kAudioStreamPropertyPhysicalFormat ||
               s == kAudioStreamPropertyAvailableVirtualFormats ||
               s == kAudioStreamPropertyAvailablePhysicalFormats;
    }
    return false;
}

/* ── IsPropertySettable ─────────────────────────────────────────────────────── */

static OSStatus IsPropertySettable(AudioServerPlugInDriverRef d, AudioObjectID obj,
    pid_t pid, const AudioObjectPropertyAddress *addr, Boolean *outIsSettable) {
    (void)d; (void)obj; (void)pid; (void)addr;
    *outIsSettable = false;
    return noErr;
}

/* ── GetPropertyDataSize ────────────────────────────────────────────────────── */

static OSStatus GetPropertyDataSize(AudioServerPlugInDriverRef d, AudioObjectID obj,
    pid_t pid, const AudioObjectPropertyAddress *addr,
    UInt32 qSz, const void *qData, UInt32 *outSz) {
    (void)d; (void)pid; (void)qSz; (void)qData;
    AudioObjectPropertySelector s = addr->mSelector;

    if (obj == kObjPlugin) {
        if (s == kAudioObjectPropertyBaseClass || s == kAudioObjectPropertyClass ||
            s == kAudioObjectPropertyOwner)
            { *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyName || s == kAudioObjectPropertyManufacturer)
            { *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioPlugInPropertyDeviceList || s == kAudioPlugInPropertyTranslateUIDToDevice)
            { *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioObjectPropertyCustomPropertyInfoList)
            { *outSz = 0; return noErr; }
    }
    if (obj == kObjDevice) {
        if (s == kAudioObjectPropertyBaseClass || s == kAudioObjectPropertyClass ||
            s == kAudioObjectPropertyOwner)
            { *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyName || s == kAudioObjectPropertyManufacturer ||
            s == kAudioDevicePropertyDeviceUID || s == kAudioDevicePropertyModelUID)
            { *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioDevicePropertyTransportType || s == kAudioDevicePropertyClockDomain ||
            s == kAudioDevicePropertyDeviceIsAlive || s == kAudioDevicePropertyDeviceIsRunning ||
            s == kAudioDevicePropertyDeviceCanBeDefaultDevice ||
            s == kAudioDevicePropertyDeviceCanBeDefaultSystemDevice ||
            s == kAudioDevicePropertyLatency || s == kAudioDevicePropertySafetyOffset ||
            s == kAudioDevicePropertyIsHidden || s == kAudioDevicePropertyBufferFrameSize)
            { *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyNominalSampleRate)
            { *outSz = sizeof(Float64); return noErr; }
        if (s == kAudioDevicePropertyAvailableNominalSampleRates ||
            s == kAudioDevicePropertyBufferFrameSizeRange)
            { *outSz = sizeof(AudioValueRange); return noErr; }
        if (s == kAudioDevicePropertyRelatedDevices)
            { *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioDevicePropertyStreams)
            { *outSz = (addr->mScope == kAudioObjectPropertyScopeInput) ? sizeof(AudioObjectID) : 0; return noErr; }
        if (s == kAudioDevicePropertyPreferredChannelsForStereo)
            { *outSz = 2 * sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyPreferredChannelLayout)
            { *outSz = (UInt32)offsetof(AudioChannelLayout, mChannelDescriptions); return noErr; }
    }
    if (obj == kObjStream) {
        if (s == kAudioObjectPropertyBaseClass || s == kAudioObjectPropertyClass ||
            s == kAudioObjectPropertyOwner || s == kAudioStreamPropertyIsActive ||
            s == kAudioStreamPropertyDirection || s == kAudioStreamPropertyTerminalType ||
            s == kAudioStreamPropertyStartingChannel || s == kAudioStreamPropertyLatency)
            { *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioObjectPropertyName)
            { *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioStreamPropertyVirtualFormat || s == kAudioStreamPropertyPhysicalFormat)
            { *outSz = sizeof(AudioStreamBasicDescription); return noErr; }
        if (s == kAudioStreamPropertyAvailableVirtualFormats ||
            s == kAudioStreamPropertyAvailablePhysicalFormats)
            { *outSz = sizeof(AudioStreamRangedDescription); return noErr; }
    }
    return kAudioHardwareUnknownPropertyError;
}

/* ── GetPropertyData ────────────────────────────────────────────────────────── */

static OSStatus GetPropertyData(AudioServerPlugInDriverRef d, AudioObjectID obj,
    pid_t pid, const AudioObjectPropertyAddress *addr,
    UInt32 qSz, const void *qData, UInt32 inSz, UInt32 *outSz, void *out) {
    (void)d; (void)pid; (void)qSz; (void)inSz;
    AudioObjectPropertySelector s = addr->mSelector;

    if (obj == kObjPlugin) {
        if (s == kAudioObjectPropertyBaseClass)
            { *(AudioClassID *)out = kAudioObjectClassID; *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyClass)
            { *(AudioClassID *)out = kAudioPlugInClassID; *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyOwner)
            { *(AudioObjectID *)out = kAudioObjectSystemObject; *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioObjectPropertyName)
            { *(CFStringRef *)out = CFSTR("Conduit Microphone Plugin"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioObjectPropertyManufacturer)
            { *(CFStringRef *)out = CFSTR("Conduit"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioPlugInPropertyDeviceList)
            { *(AudioObjectID *)out = kObjDevice; *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioPlugInPropertyTranslateUIDToDevice) {
            CFStringRef uid = *(CFStringRef *)qData;
            *(AudioObjectID *)out = CFEqual(uid, CFSTR("com.conduit.microphone")) ?
                kObjDevice : kAudioObjectUnknown;
            *outSz = sizeof(AudioObjectID); return noErr;
        }
        if (s == kAudioObjectPropertyCustomPropertyInfoList)
            { *outSz = 0; return noErr; }
    }

    if (obj == kObjDevice) {
        if (s == kAudioObjectPropertyBaseClass)
            { *(AudioClassID *)out = kAudioObjectClassID; *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyClass)
            { *(AudioClassID *)out = kAudioDeviceClassID; *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyOwner)
            { *(AudioObjectID *)out = kObjPlugin; *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioObjectPropertyName)
            { *(CFStringRef *)out = CFSTR("Conduit Microphone"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioObjectPropertyManufacturer)
            { *(CFStringRef *)out = CFSTR("Conduit"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioDevicePropertyDeviceUID)
            { *(CFStringRef *)out = CFSTR("com.conduit.microphone"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioDevicePropertyModelUID)
            { *(CFStringRef *)out = CFSTR("com.conduit.microphone.model"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioDevicePropertyTransportType)
            { *(UInt32 *)out = kAudioDeviceTransportTypeVirtual; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyRelatedDevices)
            { *(AudioObjectID *)out = kObjDevice; *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioDevicePropertyClockDomain)
            { *(UInt32 *)out = 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyDeviceIsAlive)
            { *(UInt32 *)out = 1; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyDeviceIsRunning) {
            pthread_mutex_lock(&gMutex);
            *(UInt32 *)out = gIOCount > 0 ? 1 : 0;
            pthread_mutex_unlock(&gMutex);
            *outSz = sizeof(UInt32); return noErr;
        }
        if (s == kAudioDevicePropertyDeviceCanBeDefaultDevice)
            { *(UInt32 *)out = (addr->mScope == kAudioObjectPropertyScopeInput) ? 1 : 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyDeviceCanBeDefaultSystemDevice)
            { *(UInt32 *)out = 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyLatency)
            { *(UInt32 *)out = 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertySafetyOffset)
            { *(UInt32 *)out = 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyStreams) {
            if (addr->mScope == kAudioObjectPropertyScopeInput)
                { *(AudioObjectID *)out = kObjStream; *outSz = sizeof(AudioObjectID); }
            else { *outSz = 0; }
            return noErr;
        }
        if (s == kAudioDevicePropertyNominalSampleRate)
            { *(Float64 *)out = kSampleRate; *outSz = sizeof(Float64); return noErr; }
        if (s == kAudioDevicePropertyAvailableNominalSampleRates) {
            AudioValueRange r = {kSampleRate, kSampleRate};
            *(AudioValueRange *)out = r; *outSz = sizeof(AudioValueRange); return noErr;
        }
        if (s == kAudioDevicePropertyBufferFrameSize)
            { *(UInt32 *)out = kBufFrameSize; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyBufferFrameSizeRange) {
            AudioValueRange r = {kBufFrameSize, kBufFrameSize};
            *(AudioValueRange *)out = r; *outSz = sizeof(AudioValueRange); return noErr;
        }
        if (s == kAudioDevicePropertyIsHidden)
            { *(UInt32 *)out = 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioDevicePropertyPreferredChannelsForStereo) {
            UInt32 *ch = (UInt32 *)out; ch[0] = 1; ch[1] = 1;
            *outSz = 2 * sizeof(UInt32); return noErr;
        }
        if (s == kAudioDevicePropertyPreferredChannelLayout) {
            AudioChannelLayout *l = (AudioChannelLayout *)out;
            l->mChannelLayoutTag = kAudioChannelLayoutTag_Mono;
            l->mChannelBitmap = 0;
            l->mNumberChannelDescriptions = 0;
            *outSz = (UInt32)offsetof(AudioChannelLayout, mChannelDescriptions); return noErr;
        }
    }

    if (obj == kObjStream) {
        if (s == kAudioObjectPropertyBaseClass)
            { *(AudioClassID *)out = kAudioObjectClassID; *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyClass)
            { *(AudioClassID *)out = kAudioStreamClassID; *outSz = sizeof(AudioClassID); return noErr; }
        if (s == kAudioObjectPropertyOwner)
            { *(AudioObjectID *)out = kObjDevice; *outSz = sizeof(AudioObjectID); return noErr; }
        if (s == kAudioObjectPropertyName)
            { *(CFStringRef *)out = CFSTR("Conduit Microphone Input"); *outSz = sizeof(CFStringRef); return noErr; }
        if (s == kAudioStreamPropertyIsActive)
            { *(UInt32 *)out = 1; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioStreamPropertyDirection)
            { *(UInt32 *)out = 1; *outSz = sizeof(UInt32); return noErr; }  /* 1 = input */
        if (s == kAudioStreamPropertyTerminalType)
            { *(UInt32 *)out = kAudioStreamTerminalTypeMicrophone; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioStreamPropertyStartingChannel)
            { *(UInt32 *)out = 1; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioStreamPropertyLatency)
            { *(UInt32 *)out = 0; *outSz = sizeof(UInt32); return noErr; }
        if (s == kAudioStreamPropertyVirtualFormat || s == kAudioStreamPropertyPhysicalFormat) {
            AudioStreamBasicDescription f = streamFmt();
            *(AudioStreamBasicDescription *)out = f;
            *outSz = sizeof(AudioStreamBasicDescription); return noErr;
        }
        if (s == kAudioStreamPropertyAvailableVirtualFormats ||
            s == kAudioStreamPropertyAvailablePhysicalFormats) {
            AudioStreamRangedDescription rd;
            rd.mFormat = streamFmt();
            rd.mSampleRateRange.mMinimum = kSampleRate;
            rd.mSampleRateRange.mMaximum = kSampleRate;
            *(AudioStreamRangedDescription *)out = rd;
            *outSz = sizeof(AudioStreamRangedDescription); return noErr;
        }
    }
    return kAudioHardwareUnknownPropertyError;
}

static OSStatus SetPropertyData(AudioServerPlugInDriverRef d, AudioObjectID obj,
    pid_t pid, const AudioObjectPropertyAddress *addr,
    UInt32 qSz, const void *qData, UInt32 inSz, const void *in)
    { (void)d; (void)obj; (void)pid; (void)addr; (void)qSz; (void)qData; (void)inSz; (void)in;
      return kAudioHardwareUnsupportedOperationError; }

/* ── IO ─────────────────────────────────────────────────────────────────────── */

static OSStatus StartIO(AudioServerPlugInDriverRef d, AudioObjectID obj, UInt32 cid) {
    (void)d; (void)obj; (void)cid;
    pthread_mutex_lock(&gMutex);
    if (gIOCount++ == 0) {
        gAnchorHost = mach_absolute_time();
        gAnchorSmpl = 0.0;
        if (!gRing) shmOpen();
    }
    pthread_mutex_unlock(&gMutex);
    return noErr;
}

static OSStatus StopIO(AudioServerPlugInDriverRef d, AudioObjectID obj, UInt32 cid) {
    (void)d; (void)obj; (void)cid;
    pthread_mutex_lock(&gMutex);
    if (gIOCount > 0) gIOCount--;
    pthread_mutex_unlock(&gMutex);
    return noErr;
}

static OSStatus GetZeroTimeStamp(AudioServerPlugInDriverRef d, AudioObjectID obj,
    UInt32 cid, Float64 *outSmpl, UInt64 *outHost, UInt64 *outSeed) {
    (void)d; (void)obj; (void)cid;
    uint64_t now = mach_absolute_time();
    double nanos = (double)(now - gAnchorHost) * gTB.numer / (double)gTB.denom;
    *outSmpl = gAnchorSmpl + nanos * kSampleRate / 1.0e9;
    *outHost = now;
    *outSeed = 1;
    return noErr;
}

static OSStatus WillDoIOOperation(AudioServerPlugInDriverRef d, AudioObjectID obj,
    UInt32 cid, UInt32 opID, Boolean *willDo, Boolean *inPlace) {
    (void)d; (void)obj; (void)cid;
    *willDo  = (opID == kAudioServerPlugInIOOperationReadInput);
    *inPlace = false;
    return noErr;
}

static OSStatus BeginIOOperation(AudioServerPlugInDriverRef d, AudioObjectID obj,
    UInt32 cid, UInt32 opID, UInt32 nFrames,
    const AudioServerPlugInIOCycleInfo *cycleInfo) {
    (void)d; (void)obj; (void)cid; (void)opID; (void)nFrames; (void)cycleInfo;
    return noErr;
}

static OSStatus DoIOOperation(AudioServerPlugInDriverRef d, AudioObjectID obj,
    AudioObjectID stream, UInt32 cid, UInt32 opID,
    UInt32 nFrames, const AudioServerPlugInIOCycleInfo *cycleInfo,
    void *ioMainBuffer, void *ioSecondaryBuffer) {
    (void)d; (void)obj; (void)stream; (void)cid; (void)cycleInfo; (void)ioSecondaryBuffer;
    if (opID != kAudioServerPlugInIOOperationReadInput) return noErr;

    /* Fill ioMainBuffer with the most recent nFrames samples from the ring.
       If the phone isn't streaming yet, output silence. */
    float *dst = (float *)ioMainBuffer;
    ConduitMicRing *ring = gRing;
    if (ring && dst) {
        uint32_t wIdx = (uint32_t)__atomic_load_n(&ring->writeIdx, __ATOMIC_ACQUIRE);
        for (uint32_t i = 0; i < nFrames; i++) {
            uint32_t ridx = (wIdx - nFrames + i) & (MIC_RING_SIZE - 1u);
            dst[i] = ring->samples[ridx];
        }
    } else if (dst) {
        memset(dst, 0, nFrames * sizeof(float));
    }
    return noErr;
}

static OSStatus EndIOOperation(AudioServerPlugInDriverRef d, AudioObjectID obj,
    UInt32 cid, UInt32 opID, UInt32 nFrames,
    const AudioServerPlugInIOCycleInfo *cycleInfo) {
    (void)d; (void)obj; (void)cid; (void)opID; (void)nFrames; (void)cycleInfo;
    return noErr;
}

/* ── Driver vtable ──────────────────────────────────────────────────────────── */

static AudioServerPlugInDriverInterface gItf = {
    NULL,
    QueryInterface, AddRef, Release,
    Initialize,
    CreateDevice, DestroyDevice,
    AddDeviceClient, RemoveDeviceClient,
    PerformDeviceConfigChange, AbortDeviceConfigChange,
    HasProperty, IsPropertySettable,
    GetPropertyDataSize, GetPropertyData, SetPropertyData,
    StartIO, StopIO,
    GetZeroTimeStamp,
    WillDoIOOperation, BeginIOOperation, DoIOOperation, EndIOOperation,
};
static AudioServerPlugInDriverInterface *gItfPtr = &gItf;
static AudioServerPlugInDriverRef        gDrv    = &gItfPtr;

/* ── Entry point ────────────────────────────────────────────────────────────── */

__attribute__((visibility("default")))
void *ConduitMicEntryPoint(CFAllocatorRef alloc, CFUUIDRef typeUUID) {
    (void)alloc;
    if (!CFEqual(typeUUID, kAudioServerPlugInTypeUUID)) return NULL;
    return gDrv;
}
