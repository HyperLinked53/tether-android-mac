#import "AppDelegate.h"

#import <React/RCTBundleURLProvider.h>
#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTViewManager.h>
#import <AppKit/AppKit.h>
// Plain #import (not @import): C++ modules are disabled for this .mm. Frameworks are linked
// explicitly via OTHER_LDFLAGS (-framework UserNotifications / UniformTypeIdentifiers), set in the
// Podfile post_install.
#import <UserNotifications/UserNotifications.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <unistd.h>

@implementation AppDelegate

- (void)applicationDidFinishLaunching:(NSNotification *)notification
{
  self.moduleName = @"tether-mac";
  // You can add your custom initial props in the dictionary below.
  // They will be passed down to the ViewController used by React Native.
  self.initialProps = @{};

  return [super applicationDidFinishLaunching:notification];
}

- (NSURL *)sourceURLForBridge:(RCTBridge *)bridge
{
  return [self bundleURL];
}

- (NSURL *)bundleURL
{
#if DEBUG
  return [[RCTBundleURLProvider sharedSettings] jsBundleURLForBundleRoot:@"index"];
#else
  return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
#endif
}

/// This method controls whether the `concurrentRoot`feature of React18 is turned on or off.
///
/// @see: https://reactjs.org/blog/2022/03/29/react-v18.html
/// @note: This requires to be rendering on Fabric (i.e. on the New Architecture).
/// @return: `true` if the `concurrentRoot` feature is enabled. Otherwise, it returns `false`.
- (BOOL)concurrentRootEnabled
{
#ifdef RN_FABRIC_ENABLED
  return true;
#else
  return false;
#endif
}

@end

#pragma mark - TetherNotifier (native macOS notifications)

/**
 * Bridges mirrored Android notifications to real macOS banners via UserNotifications:
 *  - shows the source app's icon as the notification image (attachment),
 *  - for replyable (messaging) notifications, registers smart-reply suggestion buttons plus a
 *    free-text reply field on the banner,
 *  - emits `tetherNotificationReply` to JS when the user replies, which is sent back to the phone.
 *
 * Defined here (not a separate file) so it compiles into the existing app target without editing
 * the Xcode project. React Native auto-discovers it through RCT_EXPORT_MODULE.
 */
@interface TetherNotifier : RCTEventEmitter <UNUserNotificationCenterDelegate>
@end

@implementation TetherNotifier {
  BOOL _hasListeners;
  NSMutableDictionary<NSString *, UNNotificationCategory *> *_categories;
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup { return YES; }

- (instancetype)init {
  if (self = [super init]) {
    _categories = [NSMutableDictionary dictionary];
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    center.delegate = self; // so banners appear even while Tether is frontmost, and we get replies
    [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                          completionHandler:^(BOOL granted, NSError *_Nullable error) {}];
  }
  return self;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[@"tetherNotificationReply"];
}
- (void)startObserving { _hasListeners = YES; }
- (void)stopObserving { _hasListeners = NO; }

/// opts: { key, title, body, iconPng (base64), canReply (bool), suggestions ([string]) }
/// Resolves with a short diagnostic string describing what happened to the icon attachment.
RCT_EXPORT_METHOD(notify:(NSDictionary *)opts
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  dispatch_async(dispatch_get_main_queue(), ^{
    NSString *key = opts[@"key"] ?: @"";
    NSArray<NSString *> *suggestions = opts[@"suggestions"] ?: @[];
    BOOL canReply = [opts[@"canReply"] boolValue];

    UNMutableNotificationContent *content = [UNMutableNotificationContent new];
    content.title = [opts[@"title"] length] ? opts[@"title"] : @"Notification";
    content.body = opts[@"body"] ?: @"";
    content.sound = UNNotificationSound.defaultSound;
    content.userInfo = @{@"key": key};

    if (canReply || suggestions.count > 0) {
      content.categoryIdentifier = [self registerCategoryForKey:key
                                                    suggestions:suggestions
                                                       canReply:canReply];
    }

    NSMutableArray<NSString *> *stagingDirs = [NSMutableArray array];
    
    // 1. NSTemporaryDirectory
    NSString *nstemp = NSTemporaryDirectory();
    if (nstemp) [stagingDirs addObject:nstemp];
    
    // 2. /tmp
    [stagingDirs addObject:@"/tmp"];
    
    // 3. /var/tmp
    [stagingDirs addObject:@"/var/tmp"];
    
    // 4. group.com.apple.usernoted in Group Containers
    NSString *libraryDir = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, YES).firstObject;
    if (libraryDir) {
        NSString *groupDir = [libraryDir stringByAppendingPathComponent:@"Group Containers/group.com.apple.usernoted"];
        [stagingDirs addObject:groupDir];
    }
    
    // 5. User Caches
    NSString *cachesDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
    if (cachesDir) [stagingDirs addObject:cachesDir];
    
    // 6. Application Support
    NSString *appSupportDir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject;
    if (appSupportDir) {
        NSString *tetherSupport = [appSupportDir stringByAppendingPathComponent:@"Tether"];
        [stagingDirs addObject:tetherSupport];
    }

    [self postNotificationWithContent:content
                              iconPng:opts[@"iconPng"]
                          stagingDirs:stagingDirs
                             dirIndex:0
                             resolver:resolve];
  });
}

- (void)postNotificationWithContent:(UNMutableNotificationContent *)content
                            iconPng:(NSString *)iconPng
                        stagingDirs:(NSArray<NSString *> *)stagingDirs
                           dirIndex:(NSUInteger)dirIndex
                           resolver:(RCTPromiseResolveBlock)resolve {
  if (dirIndex >= stagingDirs.count) {
    // If we ran out of staging directories, clear the attachments and schedule WITHOUT the attachment
    content.attachments = @[];
    UNNotificationRequest *request =
        [UNNotificationRequest requestWithIdentifier:NSUUID.UUID.UUIDString content:content trigger:nil];
    [[UNUserNotificationCenter currentNotificationCenter]
        addNotificationRequest:request
         withCompletionHandler:^(NSError *_Nullable error) {
           if (error) {
             resolve([NSString stringWithFormat:@"add-error-no-att: %@ | domain=%@ code=%ld",
                                                error.localizedDescription, error.domain, (long)error.code]);
           } else {
             resolve(@"success-no-attachment");
           }
         }];
    return;
  }

  NSString *stagingDir = stagingDirs[dirIndex];
  NSString *iconStatus = [self attachIcon:iconPng toContent:content stagingDir:stagingDir];

  if ([iconStatus isEqualToString:@"no-icon"] || [iconStatus isEqualToString:@"decode-failed"]) {
    // No icon or decode failed - no point in trying other directories, just schedule without attachment
    content.attachments = @[];
    UNNotificationRequest *request =
        [UNNotificationRequest requestWithIdentifier:NSUUID.UUID.UUIDString content:content trigger:nil];
    [[UNUserNotificationCenter currentNotificationCenter]
        addNotificationRequest:request
         withCompletionHandler:^(NSError *_Nullable error) {
           if (error) {
             resolve([NSString stringWithFormat:@"add-error-no-att: %@ | domain=%@ code=%ld",
                                                error.localizedDescription, error.domain, (long)error.code]);
           } else {
             resolve(iconStatus);
           }
         }];
    return;
  }

  // Attempt to schedule with this attachment
  UNNotificationRequest *request =
      [UNNotificationRequest requestWithIdentifier:NSUUID.UUID.UUIDString content:content trigger:nil];
  [[UNUserNotificationCenter currentNotificationCenter]
      addNotificationRequest:request
       withCompletionHandler:^(NSError *_Nullable error) {
         if (error) {
           // It failed! Log it and try the next staging directory.
           NSLog(@"[Tether] Notification with attachment from staging dir %@ failed: %@", stagingDir, error.localizedDescription);
           // Clean up the temporary attachment file/directory
           if (content.attachments.count > 0) {
             NSURL *url = content.attachments.firstObject.URL;
             if (url) {
               NSURL *parentDir = [url URLByDeletingLastPathComponent];
               // [[NSFileManager defaultManager] removeItemAtURL:parentDir error:nil];
             }
           }
           // Recursively try the next directory on the main thread
           dispatch_async(dispatch_get_main_queue(), ^{
             [self postNotificationWithContent:content
                                       iconPng:iconPng
                                   stagingDirs:stagingDirs
                                      dirIndex:(dirIndex + 1)
                                      resolver:resolve];
           });
         } else {
           // Success!
           resolve([NSString stringWithFormat:@"success-with-att-via-%@", stagingDir]);
         }
       }];
}

- (NSString *)attachIcon:(NSString *)base64 toContent:(UNMutableNotificationContent *)content stagingDir:(NSString *)stagingDir {
  if (![base64 isKindOfClass:[NSString class]] || base64.length == 0) return @"no-icon";
  NSData *data = [[NSData alloc] initWithBase64EncodedString:base64
                                                     options:NSDataBase64DecodingIgnoreUnknownCharacters];
  if (!data) return @"decode-failed";

  NSString *dir = [stagingDir stringByAppendingPathComponent:[@"TetherIcons" stringByAppendingPathComponent:NSUUID.UUID.UUIDString]];
  NSError *createDirErr = nil;
  if (![[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:&createDirErr]) {
    return [NSString stringWithFormat:@"write-failed-create-dir: %@", createDirErr.localizedDescription];
  }

  NSString *path = [dir stringByAppendingPathComponent:@"icon.png"];
  if (![data writeToFile:path atomically:YES]) {
    return @"write-failed-write-file";
  }

  NSError *err = nil;
  UNNotificationAttachment *att =
      [UNNotificationAttachment attachmentWithIdentifier:@"icon"
                                                     URL:[NSURL fileURLWithPath:path]
                                                  options:@{UNNotificationAttachmentOptionsTypeHintKey: @"public.png"}
                                                    error:&err];
  if (att) {
    content.attachments = @[att];
    return [NSString stringWithFormat:@"attached-%lu-bytes", (unsigned long)data.length];
  }
  return [@"attach-failed: " stringByAppendingString:err.localizedDescription ?: @"unknown"];
}

// Categories are a global set; we register one per replyable notification (keyed by its id) and
// re-publish the whole set. Cap growth so it doesn't accumulate forever.
- (NSString *)registerCategoryForKey:(NSString *)key
                         suggestions:(NSArray<NSString *> *)suggestions
                            canReply:(BOOL)canReply {
  NSMutableArray<UNNotificationAction *> *actions = [NSMutableArray array];
  for (NSString *s in suggestions) {
    if (![s isKindOfClass:[NSString class]] || s.length == 0) continue;
    [actions addObject:[UNNotificationAction actionWithIdentifier:[@"suggest:" stringByAppendingString:s]
                                                            title:s
                                                          options:UNNotificationActionOptionNone]];
  }
  if (canReply) {
    [actions addObject:[UNTextInputNotificationAction actionWithIdentifier:@"reply"
                                                                     title:@"Reply"
                                                                   options:UNNotificationActionOptionNone
                                                      textInputButtonTitle:@"Send"
                                                      textInputPlaceholder:@"Message"]];
  }
  NSString *categoryId = [@"tether." stringByAppendingString:key.length ? key : NSUUID.UUID.UUIDString];
  UNNotificationCategory *category = [UNNotificationCategory categoryWithIdentifier:categoryId
                                                                           actions:actions
                                                                 intentIdentifiers:@[]
                                                                           options:UNNotificationCategoryOptionNone];
  if (_categories.count > 60) [_categories removeAllObjects];
  _categories[categoryId] = category;
  [[UNUserNotificationCenter currentNotificationCenter]
      setNotificationCategories:[NSSet setWithArray:_categories.allValues]];
  return categoryId;
}

#pragma mark UNUserNotificationCenterDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
  completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionSound);
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
             withCompletionHandler:(void (^)(void))completionHandler {
  NSString *key = response.notification.request.content.userInfo[@"key"];
  NSString *text = nil;
  if ([response isKindOfClass:[UNTextInputNotificationResponse class]]) {
    text = [(UNTextInputNotificationResponse *)response userText];
  } else if ([response.actionIdentifier hasPrefix:@"suggest:"]) {
    text = [response.actionIdentifier substringFromIndex:[@"suggest:" length]];
  }
  if (_hasListeners && key.length && text.length) {
    [self sendEventWithName:@"tetherNotificationReply" body:@{@"key": key, @"text": text}];
  }
  completionHandler();
}

@end

#pragma mark - TetherFilePicker (native macOS open panel)

/**
 * Opens a native NSOpenPanel so the user can pick a file to send to the phone, returning its path,
 * name, and size. react-native-document-picker is iOS-only, so we provide this directly.
 */
@interface TetherFilePicker : NSObject <RCTBridgeModule>
@end

@implementation TetherFilePicker

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup { return NO; }

RCT_EXPORT_METHOD(pick:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
  dispatch_async(dispatch_get_main_queue(), ^{
    NSOpenPanel *panel = [NSOpenPanel openPanel];
    panel.canChooseFiles = YES;
    panel.canChooseDirectories = NO;
    panel.allowsMultipleSelection = NO;
    if ([panel runModal] != NSModalResponseOK || panel.URLs.count == 0) {
      resolve([NSNull null]); // user cancelled
      return;
    }
    NSURL *url = panel.URLs.firstObject;
    NSNumber *size = nil;
    [url getResourceValue:&size forKey:NSURLFileSizeKey error:nil];
    resolve(@{@"path": url.path ?: @"", @"name": url.lastPathComponent ?: @"file", @"size": size ?: @0});
  });
}

/// Opens an NSSavePanel pre-filled with `suggestedName`; resolves the chosen destination path, or
/// NSNull if the user cancelled. Used by the "Save to…" button for files pulled from the phone.
RCT_EXPORT_METHOD(save:(NSString *)suggestedName
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  dispatch_async(dispatch_get_main_queue(), ^{
    NSSavePanel *panel = [NSSavePanel savePanel];
    panel.nameFieldStringValue = suggestedName.length ? suggestedName : @"file";
    panel.canCreateDirectories = YES;
    if ([panel runModal] != NSModalResponseOK || !panel.URL) {
      resolve([NSNull null]);
      return;
    }
    resolve(panel.URL.path ?: [NSNull null]);
  });
}

@end

#pragma mark - TetherDragSource (native macOS drag-out for phone files)

/**
 * A draggable view: dragging it out of the app writes the phone file to wherever it's dropped in
 * Finder. RN macOS supports drag *in* (via `draggedTypes`) but not drag *out*, so this provides a
 * native NSFilePromiseProvider drag source.
 *
 * Flow: on the first drag movement it fires `onDragArm` to JS (which sends `fs.pull` to the phone
 * and, on `fs.pullReady`, pushes the real download URL back as the `fileUrl` prop). When the user
 * drops, NSFilePromiseProvider asks us to materialize the file — we download it from `fileUrl`
 * straight to the drop destination via NSURLSession (waiting briefly for the arm round-trip to set
 * `fileUrl` if it hasn't landed yet). Defined here (not a new file) so it builds without editing
 * the Xcode project; UniformTypeIdentifiers.framework is linked via the Podfile post_install.
 */
@interface TetherDragSourceView : NSView <NSDraggingSource, NSFilePromiseProviderDelegate>
@property (nonatomic, copy) NSString *fileUrl;
@property (nonatomic, copy) NSString *filename;
// Multi-file drag: when filenames has >1 entry these override fileUrl/filename for the session.
@property (nonatomic, copy) NSArray<NSString *> *fileUrls;
@property (nonatomic, copy) NSArray<NSString *> *filenames;
@property (nonatomic, copy) RCTDirectEventBlock onDragArm;
// Fires on a click that wasn't a drag, carrying the Shift/Cmd modifier state so JS can do
// range / toggle selection (file-manager style). Named onItemClick (not onPress) to avoid
// colliding with RCTView's built-in bubbling `press` event.
@property (nonatomic, copy) RCTDirectEventBlock onItemClick;
@end

@implementation TetherDragSourceView {
  NSCondition *_urlCondition;
  NSString *_armedUrl;            // single-file path: set by setFileUrl:
  NSArray<NSString *> *_armedUrls; // multi-file path: set by setFileUrls:
  NSOperationQueue *_promiseQueue;
  BOOL _dragging;
  BOOL _dragStarted; // a drag began in the current mouse-down→up gesture (so it's not a click)
}

- (instancetype)initWithFrame:(NSRect)frame {
  if (self = [super initWithFrame:frame]) {
    _urlCondition = [NSCondition new];
    _promiseQueue = [NSOperationQueue new];
  }
  return self;
}

- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }

// Capture mouse events anywhere over our content so the drag gesture starts reliably regardless of
// RN child views. (The Download/Save buttons are siblings outside this view, so they still work.)
- (NSView *)hitTest:(NSPoint)point {
  if (NSPointInRect([self.superview convertPoint:point toView:self], self.bounds)) return self;
  return [super hitTest:point];
}

- (void)mouseDown:(NSEvent *)event {
  // Swallow so mouseDragged is delivered; remember this gesture hasn't dragged yet.
  _dragStarted = NO;
}

// A mouse-up with no intervening drag is a click → tell JS, with the modifier keys, so it can
// do plain / Shift-range / Cmd-toggle selection.
- (void)mouseUp:(NSEvent *)event {
  if (_dragStarted || !self.onItemClick) return;
  BOOL shift = (event.modifierFlags & NSEventModifierFlagShift) != 0;
  BOOL meta = (event.modifierFlags & NSEventModifierFlagCommand) != 0;
  self.onItemClick(@{@"shiftKey": @(shift), @"metaKey": @(meta)});
}

// Build one dragging item (a file promise) for a file with the given name. The index is stashed in
// the provider's userInfo so the promise callbacks can look up the matching name/URL for multi-drag.
- (NSDraggingItem *)draggingItemForName:(NSString *)name index:(NSUInteger)index {
  NSString *fileType = @"public.data";
  NSString *ext = name.pathExtension;
  if (@available(macOS 11.0, *)) {
    UTType *t = ext.length ? [UTType typeWithFilenameExtension:ext] : nil;
    if (t) fileType = t.identifier;
  }
  NSFilePromiseProvider *provider =
      [[NSFilePromiseProvider alloc] initWithFileType:fileType delegate:self];
  provider.userInfo = @(index);
  NSDraggingItem *item = [[NSDraggingItem alloc] initWithPasteboardWriter:provider];
  NSImage *icon = [[NSWorkspace sharedWorkspace] iconForFileType:ext ?: @""];
  [item setDraggingFrame:self.bounds contents:icon];
  return item;
}

- (void)mouseDragged:(NSEvent *)event {
  if (_dragging) return;
  NSArray<NSString *> *names = self.filenames.count > 0 ? self.filenames : nil;
  if (!names && self.filename.length == 0) return;
  _dragging = YES;
  _dragStarted = YES; // this gesture is a drag, not a click

  // Clear any URLs from a previous drag and ask JS to arm fresh download(s) for this drag.
  [_urlCondition lock]; _armedUrl = nil; _armedUrls = nil; [_urlCondition unlock];
  if (self.onDragArm) self.onDragArm(@{});

  NSMutableArray<NSDraggingItem *> *items = [NSMutableArray array];
  if (names.count > 0) {
    // Multi-file drag (also handles the single-selection case): one promise per file.
    [names enumerateObjectsUsingBlock:^(NSString *name, NSUInteger i, BOOL *stop) {
      [items addObject:[self draggingItemForName:name index:i]];
    }];
  } else {
    [items addObject:[self draggingItemForName:self.filename index:0]];
  }
  [self beginDraggingSessionWithItems:items event:event source:self];
}

- (void)setFileUrl:(NSString *)fileUrl {
  [_urlCondition lock];
  _fileUrl = [fileUrl copy];
  _armedUrl = [fileUrl copy];
  [_urlCondition signal];
  [_urlCondition unlock];
}

// JS sets this with every armed download URL once a multi-file drag is armed.
- (void)setFileUrls:(NSArray<NSString *> *)urls {
  [_urlCondition lock];
  _fileUrls = [urls copy];
  _armedUrls = [urls copy];
  [_urlCondition broadcast]; // wake every per-file promise callback waiting on its index
  [_urlCondition unlock];
}

- (NSString *)waitForArmedUrl {
  [_urlCondition lock];
  NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:8.0];
  while (_armedUrl.length == 0 && [deadline timeIntervalSinceNow] > 0) {
    [_urlCondition waitUntilDate:deadline];
  }
  NSString *u = _armedUrl;
  [_urlCondition unlock];
  return u;
}

- (NSString *)waitForArmedUrlAtIndex:(NSUInteger)idx {
  [_urlCondition lock];
  NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:8.0];
  while (_armedUrls.count <= idx && [deadline timeIntervalSinceNow] > 0) {
    [_urlCondition waitUntilDate:deadline];
  }
  NSString *u = idx < _armedUrls.count ? _armedUrls[idx] : nil;
  [_urlCondition unlock];
  return u;
}

#pragma mark NSDraggingSource

- (NSDragOperation)draggingSession:(NSDraggingSession *)session
    sourceOperationMaskForDraggingContext:(NSDraggingContext)context {
  return NSDragOperationCopy;
}

- (void)draggingSession:(NSDraggingSession *)session
           endedAtPoint:(NSPoint)screenPoint
              operation:(NSDragOperation)operation {
  _dragging = NO;
}

#pragma mark NSFilePromiseProviderDelegate

- (NSString *)filePromiseProvider:(NSFilePromiseProvider *)provider
                   fileNameForType:(NSString *)fileType {
  if (self.filenames.count > 0 && [provider.userInfo isKindOfClass:[NSNumber class]]) {
    NSUInteger idx = [provider.userInfo unsignedIntegerValue];
    if (idx < self.filenames.count) return self.filenames[idx];
  }
  return self.filename.length ? self.filename : @"file";
}

- (NSOperationQueue *)operationQueueForFilePromiseProvider:(NSFilePromiseProvider *)provider {
  return _promiseQueue;
}

- (void)filePromiseProvider:(NSFilePromiseProvider *)provider
          writePromiseToURL:(NSURL *)url
          completionHandler:(void (^)(NSError *_Nullable))completionHandler {
  NSString *urlStr;
  if (self.filenames.count > 0 && [provider.userInfo isKindOfClass:[NSNumber class]]) {
    urlStr = [self waitForArmedUrlAtIndex:[provider.userInfo unsignedIntegerValue]];
  } else {
    urlStr = [self waitForArmedUrl];
  }
  NSURL *src = urlStr.length ? [NSURL URLWithString:urlStr] : nil;
  if (!src) {
    completionHandler([NSError errorWithDomain:@"Tether" code:1
                                      userInfo:@{NSLocalizedDescriptionKey: @"download not armed"}]);
    return;
  }
  NSURLSessionDownloadTask *task =
      [[NSURLSession sharedSession] downloadTaskWithURL:src
                                      completionHandler:^(NSURL *tmp, NSURLResponse *resp, NSError *err) {
        if (err || !tmp) { completionHandler(err); return; }
        NSFileManager *fm = [NSFileManager defaultManager];
        [fm removeItemAtURL:url error:nil];
        NSError *mvErr = nil;
        [fm moveItemAtURL:tmp toURL:url error:&mvErr];
        completionHandler(mvErr);
      }];
  [task resume];
}

@end

@interface TetherDragSourceManager : RCTViewManager
@end

@implementation TetherDragSourceManager

RCT_EXPORT_MODULE()

- (NSView *)view { return [TetherDragSourceView new]; }

RCT_EXPORT_VIEW_PROPERTY(fileUrl, NSString)
RCT_EXPORT_VIEW_PROPERTY(filename, NSString)
RCT_EXPORT_VIEW_PROPERTY(fileUrls, NSStringArray)
RCT_EXPORT_VIEW_PROPERTY(filenames, NSStringArray)
RCT_EXPORT_VIEW_PROPERTY(onDragArm, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onItemClick, RCTDirectEventBlock)

@end

#pragma mark - TetherMirror (live phone screen via H.264 → AVSampleBufferDisplayLayer)

/**
 * Displays the phone's screen. Declarative props: set `host`/`port` and flip `active` to connect to
 * the Android screen socket (see ScreenServer.kt). The H.264 bytes are read on a background thread
 * and fed straight into an AVSampleBufferDisplayLayer (which decodes + renders) — they never touch
 * the JS bridge. Defined here (not a new file) so it builds without editing the Xcode project;
 * AVFoundation/CoreMedia/CoreVideo are linked via the Podfile post_install.
 *
 * Wire format (matches ScreenServer): [4-byte BE length][1-byte flags: bit0=config, bit1=keyframe]
 * [Annex-B payload]. The config frame carries SPS/PPS; data frames carry one access unit each.
 */
@interface TetherMirrorView : NSView
@property (nonatomic, copy) NSString *host;
@property (nonatomic, assign) NSInteger port;
@property (nonatomic, assign) BOOL active;
@property (nonatomic, copy) void (^onInput)(NSDictionary *); // mouse/keyboard → JS → input.* over WS
@end

@implementation TetherMirrorView {
  AVSampleBufferDisplayLayer *_displayLayer;
  int _sockfd;
  BOOL _running;
  BOOL _connected; // reflects current desired (active && host && port) state
  NSPoint _downNorm; // mouse-down point (normalized) for tap/swipe discrimination
  NSTimeInterval _downTime;
}

- (instancetype)initWithFrame:(NSRect)frame {
  if (self = [super initWithFrame:frame]) {
    self.wantsLayer = YES;
    _sockfd = -1;
    _displayLayer = [AVSampleBufferDisplayLayer layer];
    _displayLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    _displayLayer.backgroundColor = [[NSColor blackColor] CGColor];
    _displayLayer.frame = self.bounds;
    [self.layer addSublayer:_displayLayer];
  }
  return self;
}

- (void)layout {
  [super layout];
  _displayLayer.frame = self.bounds;
}

- (void)setFrameSize:(NSSize)newSize {
  [super setFrameSize:newSize];
  _displayLayer.frame = self.bounds; // keep the video filling the (resizable) window
}

- (void)setHost:(NSString *)host { _host = [host copy]; [self reconcile]; }
- (void)setPort:(NSInteger)port { _port = port; [self reconcile]; }
- (void)setActive:(BOOL)active { _active = active; [self reconcile]; }

// Start/stop the stream to match the declarative props.
- (void)reconcile {
  BOOL want = _active && _host.length > 0 && _port > 0;
  if (want && !_connected) {
    _connected = YES;
    [self startStream];
  } else if (!want && _connected) {
    _connected = NO;
    [self stopStream];
  }
}

- (void)startStream {
  _running = YES;
  NSString *host = _host;
  int port = (int)_port;
  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
    [self runReaderToHost:host port:port];
  });
}

- (void)stopStream {
  // Just signal the reader thread to exit and unblock its recv; the reader owns and frees its own
  // format description, so nothing is released out from under it (avoids a crash on stop).
  _running = NO;
  if (_sockfd >= 0) { close(_sockfd); _sockfd = -1; }
  AVSampleBufferDisplayLayer *layer = _displayLayer; // capture the layer, not self (safe in dealloc)
  dispatch_async(dispatch_get_main_queue(), ^{ [layer flushAndRemoveImage]; });
}

- (void)dealloc {
  [self stopStream];
}

#pragma mark input capture (mouse/keyboard → input.* events)

- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }

// Mouse location → normalized [0,1], origin top-left to match the phone screen.
- (NSPoint)normalizedPoint:(NSEvent *)event {
  NSPoint p = [self convertPoint:event.locationInWindow fromView:nil];
  CGFloat nx = p.x / MAX(1.0, self.bounds.size.width);
  CGFloat ny = 1.0 - (p.y / MAX(1.0, self.bounds.size.height)); // AppKit origin is bottom-left
  return NSMakePoint(fmin(1.0, fmax(0.0, nx)), fmin(1.0, fmax(0.0, ny)));
}

- (void)mouseDown:(NSEvent *)event {
  _downNorm = [self normalizedPoint:event];
  _downTime = event.timestamp;
}

- (void)mouseUp:(NSEvent *)event {
  if (!self.onInput) return;
  NSPoint up = [self normalizedPoint:event];
  NSTimeInterval dt = event.timestamp - _downTime;
  CGFloat dist = hypot(up.x - _downNorm.x, up.y - _downNorm.y);
  if (dist < 0.02) { // negligible movement → tap or long-press
    NSString *type = dt >= 0.5 ? @"longpress" : @"tap";
    self.onInput(@{@"type": type, @"x": @(_downNorm.x), @"y": @(_downNorm.y)});
  } else { // movement → swipe/drag over the drag duration
    int ms = (int)fmax(40, fmin(1500, dt * 1000));
    self.onInput(@{@"type": @"swipe", @"x1": @(_downNorm.x), @"y1": @(_downNorm.y),
                   @"x2": @(up.x), @"y2": @(up.y), @"ms": @(ms)});
  }
}

- (void)scrollWheel:(NSEvent *)event {
  if (!self.onInput) return;
  NSPoint n = [self normalizedPoint:event];
  self.onInput(@{@"type": @"scroll", @"x": @(n.x), @"y": @(n.y),
                 @"dx": @(event.scrollingDeltaX), @"dy": @(event.scrollingDeltaY)});
}

- (void)keyDown:(NSEvent *)event {
  if (!self.onInput) return;
  switch (event.keyCode) {
    case 51: self.onInput(@{@"type": @"key", @"code": @"backspace"}); return; // delete
    case 36: case 76: { // return / enter
      BOOL shift = (event.modifierFlags & NSEventModifierFlagShift) != 0;
      // Shift+Enter inserts a newline; plain Enter submits/sends.
      self.onInput(shift ? @{@"type": @"key", @"text": @"\n"} : @{@"type": @"key", @"code": @"enter"});
      return;
    }
    case 49: self.onInput(@{@"type": @"key", @"code": @"space"}); return; // space
    case 53: self.onInput(@{@"type": @"button", @"name": @"back"}); return; // escape → Back
    default: break;
  }
  NSString *chars = event.characters;
  if (chars.length > 0) {
    unichar c = [chars characterAtIndex:0];
    if (c >= 32 && c != 127) self.onInput(@{@"type": @"key", @"text": chars}); // printable
  }
}

#pragma mark socket reader

- (void)runReaderToHost:(NSString *)host port:(int)port {
  struct addrinfo hints; memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *res = NULL;
  char portStr[16]; snprintf(portStr, sizeof(portStr), "%d", port);
  if (getaddrinfo(host.UTF8String, portStr, &hints, &res) != 0 || !res) return;

  int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (fd < 0) { freeaddrinfo(res); return; }
  if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) { close(fd); freeaddrinfo(res); return; }
  freeaddrinfo(res);
  int one = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
  _sockfd = fd;

  CMVideoFormatDescriptionRef fmt = NULL; // owned by this reader thread only
  uint8_t header[5];
  while (_running) {
    if (![self readFully:fd buffer:header length:5]) break;
    uint32_t len = ((uint32_t)header[0] << 24) | ((uint32_t)header[1] << 16) |
                   ((uint32_t)header[2] << 8) | (uint32_t)header[3];
    uint8_t flags = header[4];
    if (len == 0 || len > 16 * 1024 * 1024) break; // sanity
    NSMutableData *payload = [NSMutableData dataWithLength:len];
    if (![self readFully:fd buffer:payload.mutableBytes length:len]) break;

    if (flags & 0x01) {
      CMVideoFormatDescriptionRef f = [self formatDescriptionFromAnnexB:payload];
      if (f) { if (fmt) CFRelease(fmt); fmt = f; }
    } else if (fmt) {
      [self enqueueAccessUnitAnnexB:payload format:fmt];
    }
  }
  if (fmt) CFRelease(fmt);
  if (fd == _sockfd) { close(fd); _sockfd = -1; }
}

- (BOOL)readFully:(int)fd buffer:(void *)buffer length:(size_t)length {
  uint8_t *p = (uint8_t *)buffer;
  size_t got = 0;
  while (got < length && _running) {
    ssize_t n = recv(fd, p + got, length - got, 0);
    if (n <= 0) return NO;
    got += n;
  }
  return got == length;
}

#pragma mark H.264 helpers

// Iterate Annex-B NAL units (3- or 4-byte start codes), calling block with each NAL body (no start
// code, trailing zero of the next start code trimmed).
- (void)forEachNALInAnnexB:(NSData *)data block:(void (^)(const uint8_t *nal, size_t len))block {
  const uint8_t *b = (const uint8_t *)data.bytes;
  size_t n = data.length;
  // Collect the byte offset just past each start code (i.e. where a NAL body begins).
  size_t bodyStarts[2048];
  size_t count = 0;
  size_t i = 0;
  while (i + 3 <= n && count < 2048) {
    if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
      bodyStarts[count++] = i + 3;
      i += 3;
    } else {
      i++;
    }
  }
  for (size_t k = 0; k < count; k++) {
    size_t bodyStart = bodyStarts[k];
    size_t bodyEnd = (k + 1 < count) ? bodyStarts[k + 1] - 3 : n;
    // A 4-byte start code (00 00 00 01) leaves a trailing 0 before the next 00 00 01.
    if (bodyEnd > bodyStart && b[bodyEnd - 1] == 0) bodyEnd--;
    if (bodyEnd > bodyStart) block(b + bodyStart, bodyEnd - bodyStart);
  }
}

// Returns a retained format description from SPS/PPS (caller owns it), or NULL.
- (CMVideoFormatDescriptionRef)formatDescriptionFromAnnexB:(NSData *)data {
  __block NSData *sps = nil, *pps = nil;
  [self forEachNALInAnnexB:data block:^(const uint8_t *nal, size_t len) {
    if (len == 0) return;
    uint8_t type = nal[0] & 0x1F;
    if (type == 7) sps = [NSData dataWithBytes:nal length:len];
    else if (type == 8) pps = [NSData dataWithBytes:nal length:len];
  }];
  if (!sps || !pps) return NULL;
  const uint8_t *params[2] = {(const uint8_t *)sps.bytes, (const uint8_t *)pps.bytes};
  size_t sizes[2] = {sps.length, pps.length};
  CMVideoFormatDescriptionRef fmt = NULL;
  OSStatus st = CMVideoFormatDescriptionCreateFromH264ParameterSets(
      kCFAllocatorDefault, 2, params, sizes, 4, &fmt);
  return (st == noErr) ? fmt : NULL;
}

- (void)enqueueAccessUnitAnnexB:(NSData *)data format:(CMVideoFormatDescriptionRef)formatDesc {
  if (!_running) return;
  // Convert Annex-B (start codes) → AVCC (4-byte length prefixes) into one contiguous buffer.
  NSMutableData *avcc = [NSMutableData data];
  [self forEachNALInAnnexB:data block:^(const uint8_t *nal, size_t len) {
    if (len == 0) return;
    uint8_t hdr[4] = {(uint8_t)(len >> 24), (uint8_t)(len >> 16), (uint8_t)(len >> 8), (uint8_t)len};
    [avcc appendBytes:hdr length:4];
    [avcc appendBytes:nal length:len];
  }];
  if (avcc.length == 0) return;

  void *block = malloc(avcc.length);
  if (!block) return;
  memcpy(block, avcc.bytes, avcc.length);
  CMBlockBufferRef bb = NULL;
  OSStatus st = CMBlockBufferCreateWithMemoryBlock(
      kCFAllocatorDefault, block, avcc.length, kCFAllocatorMalloc, NULL, 0, avcc.length, 0, &bb);
  if (st != noErr || !bb) { free(block); return; }

  CMSampleBufferRef sb = NULL;
  const size_t sampleSize = avcc.length;
  st = CMSampleBufferCreateReady(kCFAllocatorDefault, bb, formatDesc, 1, 0, NULL, 1, &sampleSize, &sb);
  CFRelease(bb);
  if (st != noErr || !sb) return;

  // Render as soon as it arrives (live, low-latency).
  CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sb, YES);
  if (attachments && CFArrayGetCount(attachments) > 0) {
    CFMutableDictionaryRef dict = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
    CFDictionarySetValue(dict, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
  }

  AVSampleBufferDisplayLayer *layer = _displayLayer;
  if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) {
    dispatch_async(dispatch_get_main_queue(), ^{ [layer flush]; });
  }
  [layer enqueueSampleBuffer:sb];
  CFRelease(sb);
}

@end

#pragma mark - TetherAudioPlayer (raw PCM from the phone → speakers via AudioQueue)

/**
 * Plays the phone's audio when the user routes audio to the Mac. Connects to the phone's audio
 * socket (ScreenAudioServer) and plays the raw PCM stream (48 kHz, stereo, 16-bit LE) through an
 * AudioQueue. A reader thread fills a small ring buffer; the queue callback drains it (padding with
 * silence on underrun so playback never stalls).
 */
static const int kAudioBufBytes = 8192;
static const int kAudioNumBuffers = 3;
static const NSUInteger kAudioMaxRing = 48000 * 4 / 4; // ~250ms cap to bound latency

@interface TetherAudioPlayer : NSObject
- (void)startWithHost:(NSString *)host port:(int)port;
- (void)stop;
@end

static void TetherAudioCallback(void *userData, AudioQueueRef q, AudioQueueBufferRef buf);

@implementation TetherAudioPlayer {
  AudioQueueRef _queue;
  AudioQueueBufferRef _buffers[kAudioNumBuffers];
  NSMutableData *_ring;
  NSLock *_lock;
  int _sockfd;
  BOOL _running;
}

- (void)startWithHost:(NSString *)host port:(int)port {
  if (_running) return;
  _lock = [NSLock new];
  _ring = [NSMutableData data];
  _sockfd = -1;
  _running = YES;

  AudioStreamBasicDescription asbd = {0};
  asbd.mSampleRate = 48000;
  asbd.mFormatID = kAudioFormatLinearPCM;
  asbd.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
  asbd.mChannelsPerFrame = 2;
  asbd.mBitsPerChannel = 16;
  asbd.mBytesPerFrame = 4;
  asbd.mFramesPerPacket = 1;
  asbd.mBytesPerPacket = 4;

  if (AudioQueueNewOutput(&asbd, TetherAudioCallback, (__bridge void *)self, NULL, NULL, 0, &_queue) != noErr) {
    _running = NO; return;
  }
  for (int i = 0; i < kAudioNumBuffers; i++) {
    AudioQueueAllocateBuffer(_queue, kAudioBufBytes, &_buffers[i]);
    _buffers[i]->mAudioDataByteSize = kAudioBufBytes;
    memset(_buffers[i]->mAudioData, 0, kAudioBufBytes);
    AudioQueueEnqueueBuffer(_queue, _buffers[i], 0, NULL);
  }
  AudioQueueStart(_queue, NULL);

  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
    [self runReaderToHost:host port:port];
  });
}

- (void)runReaderToHost:(NSString *)host port:(int)port {
  struct addrinfo hints; memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *res = NULL;
  char portStr[16]; snprintf(portStr, sizeof(portStr), "%d", port);
  if (getaddrinfo(host.UTF8String, portStr, &hints, &res) != 0 || !res) return;
  int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (fd < 0) { freeaddrinfo(res); return; }
  if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) { close(fd); freeaddrinfo(res); return; }
  freeaddrinfo(res);
  _sockfd = fd;

  uint8_t tmp[8192];
  while (_running) {
    ssize_t n = recv(fd, tmp, sizeof(tmp), 0);
    if (n <= 0) break;
    [_lock lock];
    [_ring appendBytes:tmp length:(NSUInteger)n];
    if (_ring.length > kAudioMaxRing) {
      [_ring replaceBytesInRange:NSMakeRange(0, _ring.length - kAudioMaxRing) withBytes:NULL length:0];
    }
    [_lock unlock];
  }
  if (fd == _sockfd) { close(fd); _sockfd = -1; }
}

- (void)fillBuffer:(AudioQueueBufferRef)buf {
  UInt32 cap = buf->mAudioDataBytesCapacity;
  [_lock lock];
  NSUInteger take = MIN((NSUInteger)cap, _ring.length);
  if (take > 0) {
    memcpy(buf->mAudioData, _ring.bytes, take);
    [_ring replaceBytesInRange:NSMakeRange(0, take) withBytes:NULL length:0];
  }
  [_lock unlock];
  if (take < cap) memset((uint8_t *)buf->mAudioData + take, 0, cap - take); // pad with silence
  buf->mAudioDataByteSize = cap;
  if (_running) AudioQueueEnqueueBuffer(_queue, buf, 0, NULL);
}

- (void)stop {
  if (!_running && !_queue) return;
  _running = NO;
  if (_sockfd >= 0) { close(_sockfd); _sockfd = -1; }
  if (_queue) {
    AudioQueueStop(_queue, true);
    AudioQueueDispose(_queue, true);
    _queue = NULL;
  }
}

- (void)dealloc { [self stop]; }

@end

static void TetherAudioCallback(void *userData, AudioQueueRef q, AudioQueueBufferRef buf) {
  [(__bridge TetherAudioPlayer *)userData fillBuffer:buf];
}

/**
 * Hosts the mirror view in its own movable/resizable macOS window (rather than embedded in the RN
 * view tree). `open` creates the window + a TetherMirrorView and starts the stream; `close` tears
 * it down. Closing the window from its title bar emits `tetherMirrorClosed` so JS can tell the
 * phone to stop capturing. `startAudio`/`stopAudio` drive the optional audio-to-Mac playback.
 */
@interface TetherMirror : RCTEventEmitter <NSWindowDelegate>
@end

@implementation TetherMirror {
  NSWindow *_window;
  TetherMirrorView *_mirrorView;
  TetherAudioPlayer *_audio;
  BOOL _hasListeners;
  BOOL _suppressCloseEvent; // set while closing programmatically, to avoid a feedback loop
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup { return NO; }

- (NSArray<NSString *> *)supportedEvents { return @[@"tetherMirrorClosed", @"tetherMirrorInput"]; }
- (void)startObserving { _hasListeners = YES; }
- (void)stopObserving { _hasListeners = NO; }

RCT_EXPORT_METHOD(open:(NSString *)host
                  port:(nonnull NSNumber *)port
                  width:(nonnull NSNumber *)width
                  height:(nonnull NSNumber *)height) {
  dispatch_async(dispatch_get_main_queue(), ^{
    [self openWindowWithHost:host port:port.intValue width:width.intValue height:height.intValue];
  });
}

RCT_EXPORT_METHOD(close) {
  dispatch_async(dispatch_get_main_queue(), ^{ [self closeWindowSuppressingEvent:YES]; });
}

RCT_EXPORT_METHOD(startAudio:(NSString *)host port:(nonnull NSNumber *)port) {
  dispatch_async(dispatch_get_main_queue(), ^{
    if (!self->_audio) self->_audio = [TetherAudioPlayer new];
    [self->_audio stop];
    [self->_audio startWithHost:host port:port.intValue];
  });
}

RCT_EXPORT_METHOD(stopAudio) {
  dispatch_async(dispatch_get_main_queue(), ^{ [self->_audio stop]; });
}

- (void)openWindowWithHost:(NSString *)host port:(int)port width:(int)width height:(int)height {
  if (_window) [self closeWindowSuppressingEvent:YES];

  // Fit the phone's frame into ~80% of the screen height, preserving aspect.
  CGFloat w = width > 0 ? width : 720;
  CGFloat h = height > 0 ? height : 1280;
  CGFloat maxH = (NSScreen.mainScreen.visibleFrame.size.height ?: 900) * 0.8;
  if (h > maxH) { CGFloat k = maxH / h; w *= k; h *= k; }

  // Window = video on top + a solid nav strip beneath it (not overlaying the screen).
  CGFloat barH = 44;
  NSRect contentRect = NSMakeRect(0, 0, round(w), round(h) + barH);
  NSWindow *win = [[NSWindow alloc]
      initWithContentRect:contentRect
                styleMask:(NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                           NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable)
                  backing:NSBackingStoreBuffered
                    defer:NO];
  win.title = @"Phone — Tether";
  win.releasedWhenClosed = NO;
  win.delegate = self;
  win.minSize = NSMakeSize(220, 220 * h / w + barH);

  // Video occupies the area above the strip; grows with the window, bottom fixed above the bar.
  TetherMirrorView *view = [[TetherMirrorView alloc] initWithFrame:NSMakeRect(0, barH, round(w), round(h))];
  view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  __weak TetherMirror *weakSelf = self;
  view.onInput = ^(NSDictionary *e) {
    TetherMirror *s = weakSelf;
    if (s && s->_hasListeners) [s sendEventWithName:@"tetherMirrorInput" body:e];
  };

  NSView *container = [[NSView alloc] initWithFrame:contentRect];
  [container addSubview:view];
  [container addSubview:[self makeNavBarForWidth:contentRect.size.width height:barH]];

  win.contentView = container;
  view.host = host;
  view.port = port;
  view.active = YES; // begins the stream

  _mirrorView = view;
  _window = win;
  [win center];
  [win makeKeyAndOrderFront:nil];
  [win makeFirstResponder:view]; // so the view receives key events
}

// A solid, bottom-pinned strip beneath the video with Back / Home / Recents — sends input.button via JS.
- (NSView *)makeNavBarForWidth:(CGFloat)width height:(CGFloat)barH {
  CGFloat bw = 92, gap = 8, btnH = 24;
  NSView *bar = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, width, barH)];
  bar.autoresizingMask = NSViewWidthSizable | NSViewMaxYMargin; // fixed-height strip pinned to the bottom
  bar.wantsLayer = YES;
  bar.layer.backgroundColor = [[NSColor colorWithCalibratedWhite:0.13 alpha:1.0] CGColor];

  NSArray<NSString *> *titles = @[@"‹ Back", @"● Home", @"▢ Recents"];
  CGFloat total = bw * titles.count + gap * (titles.count - 1);
  NSView *group = [[NSView alloc] initWithFrame:NSMakeRect((width - total) / 2.0, (barH - btnH) / 2.0, total, btnH)];
  group.autoresizingMask = NSViewMinXMargin | NSViewMaxXMargin; // keep the button group centered
  NSColor *accent = [NSColor colorWithSRGBRed:0x58 / 255.0 green:0x65 / 255.0 blue:0xf2 / 255.0 alpha:1.0];
  for (NSUInteger i = 0; i < titles.count; i++) {
    NSButton *b = [NSButton buttonWithTitle:titles[i] target:self action:@selector(navButtonPressed:)];
    b.tag = (NSInteger)i;
    b.bezelStyle = NSBezelStyleRounded;
    b.bezelColor = accent; // bright accent fill so it's visible on the dark strip
    b.contentTintColor = [NSColor whiteColor];
    b.attributedTitle = [[NSAttributedString alloc]
        initWithString:titles[i]
            attributes:@{NSForegroundColorAttributeName: [NSColor whiteColor]}];
    b.frame = NSMakeRect(i * (bw + gap), 0, bw, btnH);
    [group addSubview:b];
  }
  [bar addSubview:group];
  return bar;
}

- (void)navButtonPressed:(NSButton *)sender {
  NSArray<NSString *> *names = @[@"back", @"home", @"recents"];
  if (sender.tag >= 0 && (NSUInteger)sender.tag < names.count && _hasListeners) {
    [self sendEventWithName:@"tetherMirrorInput"
                       body:@{@"type": @"button", @"name": names[(NSUInteger)sender.tag]}];
  }
}

- (void)closeWindowSuppressingEvent:(BOOL)suppress {
  [_audio stop];
  if (!_window) return;
  _suppressCloseEvent = suppress;
  _mirrorView.active = NO; // stop the stream
  [_window close];
  _window = nil;
  _mirrorView = nil;
  _suppressCloseEvent = NO;
}

#pragma mark NSWindowDelegate

- (void)windowWillClose:(NSNotification *)notification {
  // User closed the window from the title bar → stop the stream and tell JS.
  if (notification.object == _window) {
    [_audio stop];
    _mirrorView.active = NO;
    _window = nil;
    _mirrorView = nil;
    if (!_suppressCloseEvent && _hasListeners) {
      [self sendEventWithName:@"tetherMirrorClosed" body:@{}];
    }
  }
}

@end

#pragma mark - TetherMic (phone microphone → BlackHole virtual audio → Zoom / FaceTime)

/**
 * Connects to the phone's MicServer (TCP, port 5338), reads raw 16-bit mono PCM at 48 kHz, and
 * plays it through an AudioQueue directed at the "BlackHole" virtual audio device (installed via
 * `brew install --cask blackhole-2ch`). BlackHole acts as a loopback: audio played to its output
 * becomes available as an input device.
 *
 * On open, BlackHole is also set as the system default input device so every app that uses the
 * system microphone (browsers, Discord, Slack, Teams…) automatically receives the phone audio
 * without any per-app configuration. The previous default input is saved and restored on close.
 *
 * macOS 26 requires a paid Developer ID signature to load unsigned HAL plug-ins; the previous
 * shared-memory + custom HAL driver approach is not viable without one. BlackHole ships with a
 * proper Developer ID and works on macOS 26.
 */

static const int kMicBufBytes    = 4096;
static const int kMicNumBuffers  = 3;
static const NSUInteger kMicMaxRing = 48000 * 2 / 4; // ~125 ms cap

static void TetherMicCallback(void *u, AudioQueueRef q, AudioQueueBufferRef buf);

@interface TetherMic : NSObject <RCTBridgeModule>
@end

@implementation TetherMic {
  AudioQueueRef _queue;
  AudioQueueBufferRef _buffers[kMicNumBuffers];
  NSMutableData *_ring;
  NSLock *_lock;
  int  _sockFd;
  BOOL _running;
  id<NSObject> _activity;       // prevents App Nap while streaming
  AudioDeviceID _prevInputDev;  // system default input restored on close
  BOOL _savedInput;
}

RCT_EXPORT_MODULE()
+ (BOOL)requiresMainQueueSetup { return NO; }

- (instancetype)init {
  if (self = [super init]) { _sockFd = -1; _running = NO; }
  return self;
}

static AudioDeviceID findBlackHole(void) {
  AudioObjectPropertyAddress addr = {
    kAudioHardwarePropertyDevices,
    kAudioObjectPropertyScopeGlobal,
    kAudioObjectPropertyElementMain
  };
  UInt32 sz = 0;
  if (AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &addr, 0, NULL, &sz) != noErr) return kAudioDeviceUnknown;
  AudioDeviceID *devs = (AudioDeviceID *)malloc(sz);
  if (!devs || AudioObjectGetPropertyData(kAudioObjectSystemObject, &addr, 0, NULL, &sz, devs) != noErr) { free(devs); return kAudioDeviceUnknown; }
  int count = (int)(sz / sizeof(AudioDeviceID));
  AudioObjectPropertyAddress nameAddr = { kAudioObjectPropertyName, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
  AudioDeviceID found = kAudioDeviceUnknown;
  for (int i = 0; i < count; i++) {
    CFStringRef name = NULL; UInt32 nsz = sizeof(name);
    if (AudioObjectGetPropertyData(devs[i], &nameAddr, 0, NULL, &nsz, &name) == noErr && name) {
      if (CFStringFind(name, CFSTR("BlackHole"), 0).location != kCFNotFound) { found = devs[i]; CFRelease(name); break; }
      CFRelease(name);
    }
  }
  free(devs);
  return found;
}

static AudioDeviceID getDefaultInputDevice(void) {
  AudioObjectPropertyAddress addr = { kAudioHardwarePropertyDefaultInputDevice,
      kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
  AudioDeviceID dev = kAudioDeviceUnknown; UInt32 sz = sizeof(dev);
  AudioObjectGetPropertyData(kAudioObjectSystemObject, &addr, 0, NULL, &sz, &dev);
  return dev;
}

static void setDefaultInputDevice(AudioDeviceID dev) {
  AudioObjectPropertyAddress addr = { kAudioHardwarePropertyDefaultInputDevice,
      kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
  AudioObjectSetPropertyData(kAudioObjectSystemObject, &addr, 0, NULL, sizeof(dev), &dev);
}

RCT_EXPORT_METHOD(open:(NSString *)host port:(nonnull NSNumber *)portNum) {
  [self _stop];
  _lock = [NSLock new];
  _ring = [NSMutableData data];
  _running = YES;

  AudioStreamBasicDescription asbd = {0};
  asbd.mSampleRate       = 48000;
  asbd.mFormatID         = kAudioFormatLinearPCM;
  asbd.mFormatFlags      = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
  asbd.mChannelsPerFrame = 1;
  asbd.mBitsPerChannel   = 16;
  asbd.mBytesPerFrame    = 2;
  asbd.mFramesPerPacket  = 1;
  asbd.mBytesPerPacket   = 2;

  if (AudioQueueNewOutput(&asbd, TetherMicCallback, (__bridge void *)self, NULL, NULL, 0, &_queue) != noErr) {
    _running = NO; return;
  }

  AudioDeviceID bh = findBlackHole();
  if (bh != kAudioDeviceUnknown) {
    AudioQueueSetProperty(_queue, kAudioQueueProperty_CurrentDevice, &bh, sizeof(bh));
    // Set BlackHole as the system default input so every app gets the phone mic
    // automatically, without per-app configuration. Restore on close.
    _prevInputDev = getDefaultInputDevice();
    _savedInput = YES;
    setDefaultInputDevice(bh);
    NSLog(@"[TetherMic] routing to BlackHole %u, set as system default input", (unsigned)bh);
  } else {
    NSLog(@"[TetherMic] BlackHole not found — falling back to default output");
  }

  for (int i = 0; i < kMicNumBuffers; i++) {
    AudioQueueAllocateBuffer(_queue, kMicBufBytes, &_buffers[i]);
    _buffers[i]->mAudioDataByteSize = kMicBufBytes;
    memset(_buffers[i]->mAudioData, 0, kMicBufBytes);
    AudioQueueEnqueueBuffer(_queue, _buffers[i], 0, NULL);
  }
  AudioQueueStart(_queue, NULL);

  // Prevent App Nap: keeps timers (WebSocket heartbeat) and I/O on schedule while backgrounded.
  _activity = [[NSProcessInfo processInfo]
      beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep | NSActivityLatencyCritical
                        reason:@"Streaming phone microphone"];

  NSString *h = [host copy];
  int port = portNum.intValue;
  dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
    [self _readerLoop:h port:port];
  });
}

RCT_EXPORT_METHOD(close) { [self _stop]; }

- (void)_fillBuffer:(AudioQueueBufferRef)buf {
  UInt32 cap = buf->mAudioDataBytesCapacity;
  [_lock lock];
  NSUInteger take = MIN((NSUInteger)cap, _ring.length);
  if (take > 0) {
    memcpy(buf->mAudioData, _ring.bytes, take);
    [_ring replaceBytesInRange:NSMakeRange(0, take) withBytes:NULL length:0];
  }
  [_lock unlock];
  if (take < cap) memset((uint8_t *)buf->mAudioData + take, 0, cap - take);
  buf->mAudioDataByteSize = cap;
  if (_running) AudioQueueEnqueueBuffer(_queue, buf, 0, NULL);
}

- (void)_stop {
  _running = NO;
  if (_sockFd >= 0) { close(_sockFd); _sockFd = -1; }
  if (_queue) {
    AudioQueueStop(_queue, true);
    AudioQueueDispose(_queue, true);
    _queue = NULL;
  }
  if (_savedInput) {
    setDefaultInputDevice(_prevInputDev);
    _savedInput = NO;
  }
  if (_activity) {
    [[NSProcessInfo processInfo] endActivity:_activity];
    _activity = nil;
  }
}

- (void)_readerLoop:(NSString *)host port:(int)port {
  while (_running) {
    struct addrinfo hints; memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
    char portStr[16]; snprintf(portStr, sizeof(portStr), "%d", port);
    struct addrinfo *res = NULL;
    if (getaddrinfo(host.UTF8String, portStr, &hints, &res) != 0 || !res) { sleep(2); continue; }
    int fd = socket(res->ai_family, SOCK_STREAM, 0);
    int ok = (fd >= 0) ? connect(fd, res->ai_addr, res->ai_addrlen) : -1;
    freeaddrinfo(res);
    if (ok != 0) { if (fd >= 0) close(fd); sleep(2); continue; }
    _sockFd = fd;
    NSLog(@"[TetherMic] connected %@:%d", host, port);

    uint8_t tmp[4096];
    while (_running) {
      ssize_t n = recv(fd, tmp, sizeof(tmp), 0);
      if (n <= 0) break;
      [_lock lock];
      [_ring appendBytes:tmp length:(NSUInteger)n];
      if (_ring.length > kMicMaxRing) {
        [_ring replaceBytesInRange:NSMakeRange(0, _ring.length - kMicMaxRing) withBytes:NULL length:0];
      }
      [_lock unlock];
    }
    _sockFd = -1; close(fd);
    if (_running) { NSLog(@"[TetherMic] disconnected, retrying"); sleep(2); }
  }
}

- (void)dealloc { [self _stop]; }

@end

static void TetherMicCallback(void *u, AudioQueueRef q, AudioQueueBufferRef buf) {
  [(__bridge TetherMic *)u _fillBuffer:buf];
}

#pragma mark - TetherCamera (phone camera as Mac webcam preview via H.264 → AVSampleBufferDisplayLayer)

/**
 * Displays the phone's front/back camera in a floating Mac window. Same wire format and H.264
 * decode path as TetherMirrorView; just a simpler window (no nav bar, no input forwarding).
 * The native decode path means frames never pass through JS. Works over both Wi-Fi and USB
 * tethering because the underlying transport is a plain TCP socket to the phone's IP.
 */
@interface TetherCameraView : NSView
@property (nonatomic, copy) NSString *host;
@property (nonatomic, assign) NSInteger port;
@property (nonatomic, assign) BOOL active;
@end

@implementation TetherCameraView {
  AVSampleBufferDisplayLayer *_displayLayer;
  int   _sockfd;
  BOOL  _running;
  BOOL  _connected;
  NSData *_cachedIDR;                     // last IDR frame (Annex-B) for replay after flush
  CMVideoFormatDescriptionRef _cachedFmt; // format for _cachedIDR
}

- (instancetype)initWithFrame:(NSRect)frame {
  if (self = [super initWithFrame:frame]) {
    self.wantsLayer = YES;
    _sockfd = -1;
    _displayLayer = [AVSampleBufferDisplayLayer layer];
    _displayLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    _displayLayer.backgroundColor = [[NSColor blackColor] CGColor];
    _displayLayer.frame = self.bounds;
    [self.layer addSublayer:_displayLayer];
  }
  return self;
}

- (void)layout { [super layout]; _displayLayer.frame = self.bounds; }
- (void)setFrameSize:(NSSize)s { [super setFrameSize:s]; _displayLayer.frame = self.bounds; }
- (void)setHost:(NSString *)host { _host = [host copy]; [self reconcile]; }
- (void)setPort:(NSInteger)port { _port = port; [self reconcile]; }
- (void)setActive:(BOOL)active { _active = active; [self reconcile]; }

- (void)reconcile {
  BOOL want = _active && _host.length > 0 && _port > 0;
  if (want && !_connected) { _connected = YES; [self startStream]; }
  else if (!want && _connected) { _connected = NO; [self stopStream]; }
}

- (void)startStream {
  _running = YES;
  NSString *host = _host; int port = (int)_port;
  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
    [self runReaderToHost:host port:port];
  });
}

- (void)stopStream {
  _running = NO;
  if (_sockfd >= 0) { close(_sockfd); _sockfd = -1; }
  AVSampleBufferDisplayLayer *layer = _displayLayer;
  dispatch_async(dispatch_get_main_queue(), ^{ [layer flushAndRemoveImage]; });
}

- (void)dealloc {
  [self stopStream];
  if (_cachedFmt) { CFRelease(_cachedFmt); _cachedFmt = NULL; }
}

#pragma mark socket reader (identical to TetherMirrorView — same wire format)

- (void)runReaderToHost:(NSString *)host port:(int)port {
  struct addrinfo hints; memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *res = NULL;
  char portStr[16]; snprintf(portStr, sizeof(portStr), "%d", port);
  if (getaddrinfo(host.UTF8String, portStr, &hints, &res) != 0 || !res) return;

  int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (fd < 0) { freeaddrinfo(res); return; }
  if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) { close(fd); freeaddrinfo(res); return; }
  freeaddrinfo(res);
  int one = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
  // Larger receive buffer smooths bursty delivery of large keyframes over TCP.
  int rcvbuf = 256 * 1024;
  setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
  _sockfd = fd;

  CMVideoFormatDescriptionRef fmt = NULL;
  uint8_t header[5];
  while (_running) {
    if (![self camReadFully:fd buffer:header length:5]) break;
    uint32_t len = ((uint32_t)header[0] << 24) | ((uint32_t)header[1] << 16) |
                   ((uint32_t)header[2] << 8) | (uint32_t)header[3];
    uint8_t flags = header[4];
    if (len == 0 || len > 16 * 1024 * 1024) break;
    NSMutableData *payload = [NSMutableData dataWithLength:len];
    if (![self camReadFully:fd buffer:payload.mutableBytes length:len]) break;

    if (flags & 0x01) {
      CMVideoFormatDescriptionRef f = [self camFormatDescFromAnnexB:payload];
      if (f) {
        if (fmt) CFRelease(fmt);
        fmt = f;
        // Flush on every SPS/PPS update so the decoder resets with the new
        // parameters rather than failing on the next frame.
        AVSampleBufferDisplayLayer *layer = _displayLayer;
        dispatch_async(dispatch_get_main_queue(), ^{ [layer flush]; });
      }
    } else if (fmt) {
      [self camDeliverFrame:payload format:fmt];
    }
  }
  if (fmt) CFRelease(fmt);
  if (fd == _sockfd) { close(fd); _sockfd = -1; }
  // Unexpected exit (not a user stop): retry so the preview recovers automatically
  // when the phone screen comes back on and the camera reconnects.
  if (_running) {
    NSString *h = host; int p = port;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC),
                   dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
      if (_running) [self runReaderToHost:h port:p];
    });
  }
}

- (BOOL)camReadFully:(int)fd buffer:(void *)buffer length:(size_t)length {
  uint8_t *p = (uint8_t *)buffer; size_t got = 0;
  while (got < length && _running) {
    ssize_t n = recv(fd, p + got, length - got, 0);
    if (n <= 0) return NO;
    got += n;
  }
  return got == length;
}

- (CMVideoFormatDescriptionRef)camFormatDescFromAnnexB:(NSData *)data {
  __block NSData *sps = nil, *pps = nil;
  const uint8_t *b = (const uint8_t *)data.bytes; size_t n = data.length;
  size_t starts[512]; size_t count = 0; size_t i = 0;
  while (i + 3 <= n && count < 512) {
    if (b[i]==0 && b[i+1]==0 && b[i+2]==1) { starts[count++] = i+3; i+=3; } else i++;
  }
  for (size_t k = 0; k < count; k++) {
    size_t s = starts[k], e = (k+1<count) ? starts[k+1]-3 : n;
    if (e>s && b[e-1]==0) e--;
    if (e>s) {
      uint8_t type = b[s] & 0x1F;
      if (type==7) sps = [NSData dataWithBytes:b+s length:e-s];
      else if (type==8) pps = [NSData dataWithBytes:b+s length:e-s];
    }
  }
  if (!sps || !pps) return NULL;
  const uint8_t *params[2] = {(const uint8_t *)sps.bytes, (const uint8_t *)pps.bytes};
  size_t sizes[2] = {sps.length, pps.length};
  CMVideoFormatDescriptionRef fmt = NULL;
  OSStatus st = CMVideoFormatDescriptionCreateFromH264ParameterSets(
      kCFAllocatorDefault, 2, params, sizes, 4, &fmt);
  return (st==noErr) ? fmt : NULL;
}

// Build a CMSampleBuffer from raw Annex-B H.264 data + a format description.
// Returns a +1 retained object the caller must CFRelease. Returns NULL on failure.
// Safe to call from any thread.
- (CMSampleBufferRef)camBuildSampleBuffer:(NSData *)data format:(CMVideoFormatDescriptionRef)fmt {
  const uint8_t *b = (const uint8_t *)data.bytes; size_t n = data.length;
  size_t starts[512]; size_t count = 0; size_t i = 0;
  while (i+3<=n && count<512) {
    if (b[i]==0&&b[i+1]==0&&b[i+2]==1) { starts[count++]=i+3; i+=3; } else i++;
  }
  NSMutableData *avcc = [NSMutableData data];
  for (size_t k=0; k<count; k++) {
    size_t s=starts[k], e=(k+1<count)?starts[k+1]-3:n;
    if (e>s&&b[e-1]==0) e--;
    if (e<=s) continue;
    size_t len=e-s;
    uint8_t hdr[4]={(uint8_t)(len>>24),(uint8_t)(len>>16),(uint8_t)(len>>8),(uint8_t)len};
    [avcc appendBytes:hdr length:4]; [avcc appendBytes:b+s length:len];
  }
  if (!avcc.length) return NULL;
  void *block = malloc(avcc.length); if (!block) return NULL;
  memcpy(block, avcc.bytes, avcc.length);
  CMBlockBufferRef bb = NULL;
  if (CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault,block,avcc.length,
      kCFAllocatorMalloc,NULL,0,avcc.length,0,&bb)!=noErr||!bb) { free(block); return NULL; }
  CMSampleBufferRef sb = NULL;
  const size_t sz = avcc.length;
  CMSampleBufferCreateReady(kCFAllocatorDefault,bb,fmt,1,0,NULL,1,&sz,&sb);
  CFRelease(bb);
  if (!sb) return NULL;
  // DisplayImmediately: bypass presentation-timestamp scheduling so the frame
  // appears as soon as it's enqueued (we have no clock sync with the phone).
  CFArrayRef atts = CMSampleBufferGetSampleAttachmentsArray(sb, YES);
  if (atts && CFArrayGetCount(atts) > 0) {
    CFMutableDictionaryRef d = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(atts, 0);
    CFDictionarySetValue(d, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
  }
  return sb;
}

// Called by the reader thread for every non-config frame. Detects IDR frames,
// caches them, and dispatches the layer enqueue to the main queue so the flush
// check and enqueue are atomic — fixing the race where we enqueued into a
// still-failed layer and lost the frame.
- (void)camDeliverFrame:(NSData *)data format:(CMVideoFormatDescriptionRef)fmt {
  if (!_running) return;

  // Scan for IDR NAL (type 5) to decide whether to update the replay cache.
  // We stop at the first slice NAL (type 1 or 5) — no need to scan the whole frame.
  BOOL isIDR = NO;
  const uint8_t *b = (const uint8_t *)data.bytes; size_t n = data.length;
  for (size_t i = 0; i + 3 < n && !isIDR; i++) {
    if (b[i]==0 && b[i+1]==0 && b[i+2]==1) {
      uint8_t t = b[i+3] & 0x1F;
      if (t == 5) { isIDR = YES; break; }
      if (t == 1) break; // non-IDR slice — done
    }
  }
  if (isIDR) {
    _cachedIDR = data; // ARC retains
    if (_cachedFmt) CFRelease(_cachedFmt);
    _cachedFmt = fmt; CFRetain(fmt);
  }

  CMSampleBufferRef sb = [self camBuildSampleBuffer:data format:fmt];
  if (!sb) return;

  // Snapshot replay state before the dispatch. The reader thread may update
  // _cachedIDR/_cachedFmt between now and when the main-queue block runs.
  NSData *replayData = _cachedIDR;
  CMVideoFormatDescriptionRef replayFmt = _cachedFmt;
  if (replayFmt) CFRetain(replayFmt);

  AVSampleBufferDisplayLayer *layer = _displayLayer;
  dispatch_async(dispatch_get_main_queue(), ^{
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) {
      [layer flush];
      // Replay the last IDR immediately so the decoder has a reference frame
      // and P-frames can resume decoding right away — otherwise we'd freeze
      // until the phone sends its next scheduled keyframe (~1 s later).
      if (replayData && replayFmt) {
        CMSampleBufferRef idrsb = [self camBuildSampleBuffer:replayData format:replayFmt];
        if (idrsb) { [layer enqueueSampleBuffer:idrsb]; CFRelease(idrsb); }
      }
    }
    if (replayFmt) CFRelease(replayFmt);
    [layer enqueueSampleBuffer:sb];
    CFRelease(sb);
  });
}

@end

/**
 * RCTEventEmitter that owns the camera preview NSWindow. `open` creates the window and starts
 * streaming; `close` tears it down. Closing the window from the title bar emits
 * `tetherCameraWindowClosed` so JS can tell the phone to stop capture.
 */
@interface TetherCamera : RCTEventEmitter <NSWindowDelegate>
@end

@implementation TetherCamera {
  NSWindow *_window;
  TetherCameraView *_cameraView;
  BOOL _hasListeners;
  BOOL _suppressCloseEvent;
  id<NSObject> _activity; // prevents App Nap while streaming
}

RCT_EXPORT_MODULE();
+ (BOOL)requiresMainQueueSetup { return NO; }
- (NSArray<NSString *> *)supportedEvents { return @[@"tetherCameraWindowClosed"]; }
- (void)startObserving { _hasListeners = YES; }
- (void)stopObserving { _hasListeners = NO; }

RCT_EXPORT_METHOD(open:(NSString *)host
                  port:(nonnull NSNumber *)port
                  width:(nonnull NSNumber *)width
                  height:(nonnull NSNumber *)height) {
  dispatch_async(dispatch_get_main_queue(), ^{
    [self openWindowWithHost:host port:port.intValue width:width.intValue height:height.intValue];
  });
}

RCT_EXPORT_METHOD(close) {
  dispatch_async(dispatch_get_main_queue(), ^{ [self closeWindowSuppressingEvent:YES]; });
}

- (void)openWindowWithHost:(NSString *)host port:(int)port width:(int)width height:(int)height {
  if (_window) [self closeWindowSuppressingEvent:YES];

  // Fit into ~60% of screen height preserving aspect (camera is landscape 16:9 typically).
  CGFloat w = width > 0 ? width : 1280;
  CGFloat h = height > 0 ? height : 720;
  CGFloat maxH = (NSScreen.mainScreen.visibleFrame.size.height ?: 900) * 0.6;
  if (h > maxH) { CGFloat k = maxH / h; w *= k; h *= k; }

  NSWindow *win = [[NSWindow alloc]
      initWithContentRect:NSMakeRect(0, 0, round(w), round(h))
                styleMask:(NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                           NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable)
                  backing:NSBackingStoreBuffered
                    defer:NO];
  win.title = @"Camera — Tether";
  win.releasedWhenClosed = NO;
  win.delegate = self;
  win.minSize = NSMakeSize(320, 180);
  // Allow ScreenCaptureKit (used by OBS Window Capture) to see this window.
  win.sharingType = NSWindowSharingReadOnly;

  TetherCameraView *view = [[TetherCameraView alloc]
      initWithFrame:NSMakeRect(0, 0, round(w), round(h))];
  view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  win.contentView = view;
  view.host = host;
  view.port = port;
  view.active = YES;

  _cameraView = view;
  _window = win;

  // Prevent App Nap while the camera is streaming so the WebSocket heartbeat and
  // socket reader stay on schedule even when the user switches to another app.
  _activity = [[NSProcessInfo processInfo]
      beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep | NSActivityLatencyCritical
                        reason:@"Streaming phone camera"];

  [win center];
  [win makeKeyAndOrderFront:nil];
}

- (void)closeWindowSuppressingEvent:(BOOL)suppress {
  if (!_window) return;
  _suppressCloseEvent = suppress;
  _cameraView.active = NO;
  [_window close];
  _window = nil;
  _cameraView = nil;
  _suppressCloseEvent = NO;
  if (_activity) { [[NSProcessInfo processInfo] endActivity:_activity]; _activity = nil; }
}

- (void)windowWillClose:(NSNotification *)notification {
  if (notification.object == _window) {
    _cameraView.active = NO;
    _window = nil;
    _cameraView = nil;
    if (_activity) { [[NSProcessInfo processInfo] endActivity:_activity]; _activity = nil; }
    if (!_suppressCloseEvent && _hasListeners)
      [self sendEventWithName:@"tetherCameraWindowClosed" body:@{}];
  }
}

@end

#pragma mark - TetherCursor (phone-as-trackpad: moves the Mac cursor + posts clicks/scroll)

/**
 * Receives trackpad.* events forwarded from the phone (via the JS touchpad feature) and turns
 * them into CoreGraphics HID events so the phone can control the Mac cursor.
 *
 * CGEventPost requires the app to be trusted for Accessibility in System Preferences →
 * Privacy & Security → Accessibility. Without that the events post silently but are ignored.
 * App Sandbox is already disabled so this compiles and runs without entitlement changes.
 */
@interface TetherCursor : NSObject <RCTBridgeModule>
@end

@implementation TetherCursor {
  dispatch_queue_t _q; // serial queue for all CG work; never blocks the bridge
}

RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup { return NO; }

- (instancetype)init {
  if (self = [super init]) {
    _q = dispatch_queue_create("tether.cursor", DISPATCH_QUEUE_SERIAL);
  }
  return self;
}

// All methods dispatch_async onto _q so they return to the bridge immediately — if CGEvent
// functions need window-server access the bridge init does not deadlock waiting for them.

RCT_EXPORT_METHOD(move:(double)dx dy:(double)dy) {
  dispatch_async(_q, ^{
    CGEventRef probe = CGEventCreate(NULL);
    CGPoint pos = CGEventGetLocation(probe);
    CFRelease(probe);
    CGRect bounds = CGDisplayBounds(CGMainDisplayID());
    pos.x = fmax(bounds.origin.x, fmin(pos.x + dx, bounds.origin.x + bounds.size.width  - 1));
    pos.y = fmax(bounds.origin.y, fmin(pos.y + dy, bounds.origin.y + bounds.size.height - 1));
    CGEventRef ev = CGEventCreateMouseEvent(NULL, kCGEventMouseMoved, pos, kCGMouseButtonLeft);
    CGEventPost(kCGHIDEventTap, ev);
    CFRelease(ev);
  });
}

RCT_EXPORT_METHOD(scroll:(double)dx dy:(double)dy) {
  dispatch_async(_q, ^{
    CGEventRef ev = CGEventCreateScrollWheelEvent(NULL, kCGScrollEventUnitLine, 2,
                                                  (int32_t)dy, (int32_t)dx);
    CGEventPost(kCGHIDEventTap, ev);
    CFRelease(ev);
  });
}

RCT_EXPORT_METHOD(click) {
  dispatch_async(_q, ^{
    CGEventRef probe = CGEventCreate(NULL);
    CGPoint pos = CGEventGetLocation(probe);
    CFRelease(probe);
    CGEventRef down = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDown,  pos, kCGMouseButtonLeft);
    CGEventRef up   = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseUp,    pos, kCGMouseButtonLeft);
    CGEventPost(kCGHIDEventTap, down); CGEventPost(kCGHIDEventTap, up);
    CFRelease(down); CFRelease(up);
  });
}

RCT_EXPORT_METHOD(rightClick) {
  dispatch_async(_q, ^{
    CGEventRef probe = CGEventCreate(NULL);
    CGPoint pos = CGEventGetLocation(probe);
    CFRelease(probe);
    CGEventRef down = CGEventCreateMouseEvent(NULL, kCGEventRightMouseDown, pos, kCGMouseButtonRight);
    CGEventRef up   = CGEventCreateMouseEvent(NULL, kCGEventRightMouseUp,   pos, kCGMouseButtonRight);
    CGEventPost(kCGHIDEventTap, down); CGEventPost(kCGHIDEventTap, up);
    CFRelease(down); CFRelease(up);
  });
}

RCT_EXPORT_METHOD(doubleClick) {
  dispatch_async(_q, ^{
    CGEventRef probe = CGEventCreate(NULL);
    CGPoint pos = CGEventGetLocation(probe);
    CFRelease(probe);
    for (int i = 1; i <= 2; i++) {
      CGEventRef down = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDown, pos, kCGMouseButtonLeft);
      CGEventRef up   = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseUp,   pos, kCGMouseButtonLeft);
      CGEventSetIntegerValueField(down, kCGMouseEventClickState, i);
      CGEventSetIntegerValueField(up,   kCGMouseEventClickState, i);
      CGEventPost(kCGHIDEventTap, down); CGEventPost(kCGHIDEventTap, up);
      CFRelease(down); CFRelease(up);
    }
  });
}

RCT_EXPORT_METHOD(typeText:(NSString *)text) {
  NSString *copy = [text copy];
  dispatch_async(_q, ^{
    for (NSUInteger i = 0; i < copy.length; i++) {
      unichar c = [copy characterAtIndex:i];
      CGEventRef down = CGEventCreateKeyboardEvent(NULL, 0, true);
      CGEventRef up   = CGEventCreateKeyboardEvent(NULL, 0, false);
      CGEventKeyboardSetUnicodeString(down, 1, &c);
      CGEventKeyboardSetUnicodeString(up,   1, &c);
      CGEventPost(kCGHIDEventTap, down);
      CGEventPost(kCGHIDEventTap, up);
      CFRelease(down); CFRelease(up);
    }
  });
}

RCT_EXPORT_METHOD(pressKey:(int)keyCode) {
  dispatch_async(_q, ^{
    CGEventRef down = CGEventCreateKeyboardEvent(NULL, (CGKeyCode)keyCode, true);
    CGEventRef up   = CGEventCreateKeyboardEvent(NULL, (CGKeyCode)keyCode, false);
    CGEventPost(kCGHIDEventTap, down);
    CGEventPost(kCGHIDEventTap, up);
    CFRelease(down); CFRelease(up);
  });
}

@end
