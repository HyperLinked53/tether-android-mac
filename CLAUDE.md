# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Conduit links an Android phone and a Mac (à la Link My Mac / Phone Link / KDE Connect). Two apps
discover each other over LAN, pair, and hold one persistent WebSocket that carries every feature.

- `protocol/` — the wire contract (spec + canonical TS types). The source of truth.
- `android-app/` — native Kotlin + Jetpack Compose. **Hosts the WebSocket server.**
- `mac-app/` — React Native macOS. **Connects as the WebSocket client.**

Feature status — all built and working: connectivity core, **file transfer** (browse the phone's
whole file tree + multi-file selection with shift/cmd-click + two-way drag-and-drop), a **Photos**
tab (phone gallery, drag photos in/out), battery/status, **find-my-phone** (`phone.ring` rings the
phone for 10 s at alarm volume), **notifications** (source-app icon, on-device smart replies, reply
round-trip), **screen mirroring** (live H.264 in its own Mac window + optional phone→Mac audio +
mouse/keyboard control), **clipboard sync** (two-way, with an on/off toggle), **SMS/MMS
messaging** (read SMS + received MMS with images, by contact name; send SMS; live inbound), and
**phone-as-webcam** (Camera2 H.264 stream to its own Mac window + optional phone mic streamed
through BlackHole virtual audio). The link is **encrypted** (v2, see Security note).

Pairing uses a **6-digit code** shown on the phone (not a long secret) so it can be typed on the
Mac without transcription errors; the proof math is unchanged.

## The one cross-cutting invariant: the protocol is duplicated in 3 places

`protocol/README.md` (spec) ⇄ `protocol/types.ts` (canonical TS) ⇄
`android-app/.../connection/Protocol.kt` (Kotlin mirror). `mac-app/src/protocol/types.ts` is a
**copy** of `protocol/types.ts`. When you change a message type, payload shape, or the binary
frame layout, update **all of these** or the apps silently stop understanding each other.

Key protocol facts (full detail in `protocol/README.md`):
- Control frames = JSON envelope `{v, id, type:"namespace.action", replyTo?, payload}`. Unknown
  `type`s must be ignored (forward-compat).
- Binary WS frames (21-byte header, codecs `binary.ts` / `BinaryFrame`) exist but are **unused** —
  all bulk data (file bytes, the H.264 video stream, PCM audio) moves over dedicated raw TCP sockets
  the Android app hosts, not the WebSocket. `channel 0x02` stays reserved. **Don't route bulk data
  over the WebSocket; it's the slow path (JS + base64).** The pattern everywhere: WebSocket
  *coordinates*, Android *hosts a socket*, Mac *connects natively* and never touches the bytes in JS.
- Lifecycle: client `hello` (with stored token) → if unknown, server sends `pair.required` →
  client proves the secret with `pair.request` → server returns `pair.ok {token}`, persisted both
  sides → heartbeat ping/pong. Pairing proof = `base64(HMAC-SHA256(secret, salt+clientDeviceId))`
  and **must match** between `mac-app/src/net/pairing.ts` and Android `PairingManager.computeProof`.
- **Already-paired reconnects authenticate silently**: the server does *not* send `pair.ok` again —
  it just replies `hello` and starts serving. So the Mac flips to `connected` when it receives the
  server's `hello` reply *and it sent a token* (`sentToken`). Without this it would sit "connecting"
  until the first heartbeat (~15s). Don't remove that.

## Architecture per side

**Android** (`com.conduit.android`): `ConnectionService` (foreground service) owns a
`ConduitServer` (Java-WebSocket) + `DeviceAdvertiser` (NSD, type `_conduit._tcp`) + `PairingManager`.
Each connected peer is a `PeerSession`; **every message is funnelled through a per-session coroutine
`Channel` so it's handled in strict arrival order** — don't bypass it. Features implement
`FeatureHandler` (matched by `type` prefix) and are registered in `ConnectionService.onCreate`:
`FileTransferHandler`, `FileBrowseHandler`, `PhotosHandler`, `ScreenFeatureHandler`, `InputHandler`,
`StatusHandler`, `ClipboardHandler`, `SmsHandler`, `NotificationReplyHandler`, `RingHandler`,
`CameraHandler`; plus the manifest-registered `SmsReceiver` for live inbound SMS. The service also
owns the raw-socket servers the bulk features stream over (`FileHttpServer`, `ScreenServer`,
`ScreenAudioServer`, `MicServer`) and the `ScreenCaptureManager` plus `CameraCapture` / `MicCapture`. UI ↔ service communicate only through the `ConduitState` singleton
(StateFlows) and `ConnectionService`'s static helpers (`broadcast`, `beginPairing`, `sendFile`,
`startScreenCapture`, `stopScreenMirroring`).

Two features run in **separate, system-bound services** with their own lifecycles (not the
foreground service), reached via a static `instance`:
- `ConduitNotificationListener` (`NotificationListenerService`) pushes notifications to peers via
  `ConnectionService.broadcast`, enriching each with the source app icon/label (needs
  `QUERY_ALL_PACKAGES` on API 30+) and smart replies (`TextClassifier.suggestConversationActions`,
  API 29+, off the main thread). Its static `reply(key, text)` is used by `NotificationReplyHandler`
  (the `notif.reply` handler), which injects text into the notification's `RemoteInput` and fires its
  `PendingIntent`.
- `ConduitInputService` (`AccessibilityService`) performs remote control: `dispatchGesture`
  (tap/swipe/scroll), `performGlobalAction` (Back/Home/Recents), and focused-field text editing. The
  `input.*` `InputHandler` calls `ConduitInputService.instance`. **Reinstalling the APK disables an
  AccessibilityService (and notification access) — the user must re-enable it in Settings after every
  full install** (Android Studio "Apply Changes" preserves it; a full Run does not).

**Mac** (`mac-app/src`): `ConnectionManager` (singleton `connection`) wraps RN's built-in
`WebSocket` — handshake, pairing, heartbeat, auto-reconnect — and emits typed events
(`state`/`peer`/`message`/`pairingRequired`/`pairingFailed`). `Discovery` wraps
`react-native-zeroconf`. Feature singletons in `features/` (`fileTransfer`, `remoteBrowse`, `photos`,
`notifications`, `screenMirror`, `clipboardSync`, `messaging`, `autoConnect`) subscribe to
`connection` events and are `wire()`d once at app launch in `App.tsx` (so they work regardless of
which tab is open — don't move their subscriptions into screens). `hooks.ts` bridges the singletons
into React; screens in `ui/` are thin. Crypto is hand-rolled (`util/sha256.ts`, `util/encoding.ts`)
to avoid a native crypto dep — verified against Node's crypto.

`features/autoConnect.ts` makes the link **persistent**: at launch it connects straight to each
paired phone's *last known IP* (cached in `util/store.ts`, keyed by device id) for a sub-second
reconnect without waiting for mDNS, then lets `Discovery` correct the address if the phone moved.
`ConnectionManager` has a 2.5s connect timeout so a stale cached IP fails over fast. **Forget device**
(`autoConnect.forget()`) drops the token + cached address so it stops auto-connecting until re-paired
— that's the only intended off-switch (there's no plain "disconnect").

**File transfer is HTTP, not WebSocket** (this is the whole reason it's fast). The WebSocket only
*coordinates* (`file.offer`/`accept`/`progress`/`complete`/`error`); the bytes stream over a tiny
raw-socket HTTP server the **Android app hosts** (`connection/FileHttpServer.kt`, port `5334`).
Android is always the HTTP server; the Mac is always the client. Endpoints are keyed by the
`transferId` (an unguessable UUID minted over the authed WebSocket = capability token):
`GET /download/<id>` (Android→Mac) and `POST /upload/<id>` (Mac→Android, raw body). The Mac streams
**natively** via RNFS `downloadFile`/`uploadFiles` (`binaryStreamOnly`) — NSURLSession, no JS, no
base64. Don't reintroduce per-chunk JS/base64; that was the old slow path. Gotchas baked in:
`FileHttpServer` omits `Content-Length` when the source size is unknown (relies on `Connection:
close`) so SAF providers that report size 0 don't truncate; `FileTransferHandler` falls back to the
fd's `statSize`. Received files land in **Downloads via MediaStore** on Android (API 29+; visible to
the user) and in **`~/Documents/ConduitInbox/`** on the Mac. `NSAllowsArbitraryLoads` (already set)
lets the Mac hit `http://` on the LAN.

**Browsing + drag-and-drop** ride on the same transfer. `FileBrowseHandler` answers `fs.list`
(directory listing; needs **All files access** / `MANAGE_EXTERNAL_STORAGE` on the phone — replies
`error:"permission"` if not granted) and `fs.pull` (arms a `GET /download/<id>` for a browsed file
and replies `fs.pullReady`; the Mac then streams it like any other download, releasing it with
`file.complete`). `mac-app/src/features/remoteBrowse.ts` drives the browser UI and exposes
`pull(path)` and `pullMultiple(paths[])`. Drag-*in* (Mac→phone) is built into RN macOS —
`<View draggedTypes={['fileUrl']} onDrop>` gives dropped file paths, no native code. Drag-*out*
(phone→Mac) needs the native `ConduitDragSource` (NSFilePromiseProvider): on drag start it `fs.pull`s
and the native side downloads to the drop location on release.

**Multi-file drag-out** (phone→Mac): the user selects files in the browser with click / Shift-click /
⌘-click (handled by `onItemClick` on `ConduitDragSource`). When dragging a file that is part of a
selection with >1 entries, the JS passes `filenames` (an array — **known synchronously at render
time from the current selection**) and `fileUrls` (filled asynchronously by `pullMultiple` via the
condition-variable wait in native). **Critical invariant**: `filenames` must be set *before* the drag
begins (native reads it to create one `NSFilePromiseProvider` per file); `fileUrls` can arrive later
(native waits up to 8 s per file). Setting `filenames` async (after the pull) was the bug that caused
only one file to transfer — the drag had already started with a single promise.

**Screen mirroring** (`screen.*` / `input.*`): Android captures via `MediaProjection` →
`MediaCodec` H.264 and streams it over `ScreenServer` (raw TCP, port `5335`, length-prefixed Annex-B
frames; replays last config+keyframe to a new viewer). The Mac decodes natively and renders into an
`AVSampleBufferDisplayLayer` **in its own movable `NSWindow`** (`ConduitMirror`), not embedded in the
RN tree. Consent is granted **on the phone** (MediaProjection dialog from `MainActivity`); a Mac
`screen.start` posts a phone notification to prompt it; `screen.ready {port,width,height,audioPort}`
tells the Mac where to connect. Optional **audio→Mac** (`ScreenAudioCapture` via
`AudioPlaybackCapture` → `ScreenAudioServer` raw PCM, port `5336` → AudioToolbox `AudioQueue` in
`ConduitAudioPlayer`); toggled live with `screen.audio`. **Control** is mouse/keyboard captured on
the mirror `NSView`, sent as `input.*` (normalized [0,1] coords), applied by `ConduitInputService`.
Keyboard model (hard-won — don't undo it): a field can report its **placeholder as its `text` with
no hint flag and no `viewIdResourceName`** (e.g. WhatsApp's box reports `text=[Message]`,
`hint=null`, `showHint=false`, `id=null`), indistinguishable from real content. So `ConduitInputService`
**never reads the field's existing text**; it keeps its own `typedBuffer` of what the user typed,
writes it via `ACTION_SET_TEXT`, and **resets only on a real focus change** — detected from
`TYPE_VIEW_FOCUSED` events, *not* node identity (a fresh node is returned each `findFocus()`, so
node-/hash-based keys reset every keystroke and you get the "only last char survives" bug). Net
behavior: first keystroke after focusing a field replaces what's shown, then keystrokes accumulate.
Enter: taps a visible "send"-labelled button if present (chat send — English-only match), else fires
the single-line IME action, else inserts a newline (multiline) through the buffer; **Shift+Enter**
always inserts a newline (sent as `text:"\n"`). Needs the phone's `RECORD_AUDIO` + a
`mediaProjection|microphone` FGS type.

**Phone-as-webcam** (`camera.*` / `mic.*`): Android streams Camera2 H.264 over `CameraServer` (raw
TCP, port `5337`, same length-prefixed Annex-B frame format as `ScreenServer`) — `CameraCapture` /
`CameraHandler`. Optional mic: `MicCapture` captures PCM via `AudioRecord` and streams it over
`MicServer` (raw TCP, separate port); the Mac's `ConduitMic` feeds it into an `AudioQueue` routed to
BlackHole. **The phone screen must stay on** (Android requires it for Camera2 while the app is
backgrounded — enforce a wake lock in `CameraCapture`). The Mac's `ConduitCamera` holds an
`NSProcessInfo` activity assertion so App Nap doesn't throttle the WebSocket heartbeat when Conduit
is backgrounded while the camera window is visible. For using the stream in Zoom/FaceTime, the
Camera tab currently instructs users to capture the floating window via OBS Virtual Camera (because
macOS 26 removed the CMIO DAL plug-in format and Camera Extensions require a paid Developer account).

**Photos / clipboard / messaging** — the other tabs, with platform gotchas worth knowing:
- **Photos** (`photos.*`, `PhotosHandler`): MediaStore image gallery; thumbnails are generated
  *lazily* (only when the Mac's `<Image>` requests the URL) and full images stream over
  `FileHttpServer` — same "Android hosts, Mac fetches natively" pattern. Drag-out reuses
  `ConduitDragSource`; drag-in reuses `fileTransfer.sendFile`. `thumbUrl` carries a `%HOST%`
  placeholder the Mac rewrites to the connected host (the phone can't know its own LAN IP) — MMS
  images use the same trick.
- **Clipboard** (`clip.push`, `ClipboardHandler` + `clipboardSync.ts`): two-way, echo-guarded by a
  shared `lastValue`; toggle persisted on the Mac. **Android can only read the clipboard while
  foregrounded** (10+), so phone→Mac capture happens on Conduit gaining window focus
  (`onWindowFocusChanged`, *not* `onResume` — focus comes later); Mac→phone is automatic via polling.
- **Messaging** (`sms.*`, `SmsHandler`): reads SMS **and** MMS (images served over `FileHttpServer`),
  resolves contact names via `PhoneLookup`, sends SMS only (sending MMS needs default-SMS-app role).
  RCS is not accessible (no API; lives in Google Messages' private store). `MessagesScreen` polls the
  open thread every 2s as a liveness fallback alongside the `sms.incoming` push.
- **Find my phone** (`phone.ring`, `RingHandler`): Mac sends `phone.ring {}` → Android plays the
  default ringtone for 10 s using `USAGE_ALARM` audio attributes (works through silent/DND, no
  `ACCESS_NOTIFICATION_POLICY` needed). **Never call `setStreamVolume` on `STREAM_RING`** without
  checking DND state — it throws `SecurityException` and crashes the handler. Vibrates in parallel
  (requires `VIBRATE` permission in manifest, already declared).

**Native macOS modules** all live *inside* `macos/conduit-mac-macOS/AppDelegate.mm` — defined there
rather than in new files so they compile into the app target **without editing the `.pbxproj`**
(`objectVersion 46`, explicit file refs, no synchronized groups). They are:
- `ConduitNotifier` — `RCTEventEmitter` driving `UserNotifications`.
- `ConduitFilePicker` — `NSOpenPanel` (`pick`) + `NSSavePanel` (`save`); react-native-document-picker
  was iOS-only and removed.
- `ConduitDragSource` — `RCTViewManager`/`NSView` drag-*source* (NSFilePromiseProvider) for dragging
  phone files out to Finder. Supports multi-file drag via `filenames[]`/`fileUrls[]` props (each entry
  gets one `NSFilePromiseProvider`; index stored in `provider.userInfo`). Also emits `onItemClick`
  (not `onPress` — see naming trap below) carrying `{shiftKey, metaKey}` for selection logic.
- `ConduitMirror` — `RCTEventEmitter` that owns the mirror `NSWindow` + an `AVSampleBufferDisplayLayer`
  `NSView` (H.264 decode), plus `ConduitAudioPlayer` (AudioQueue). It reads the video/audio sockets
  on background threads. **Threading lesson (caused a stop crash):** the H.264 `CMVideoFormatDescription`
  is owned by the reader thread and released only there — never `CFRelease` it from the main/stop path
  while the reader may still use it; `stop` just signals + closes the socket.
- `ConduitCamera` — `RCTEventEmitter` owning a separate `NSWindow` + `AVSampleBufferDisplayLayer` for
  the webcam stream (Camera2 H.264, port `5337`). Same Annex-B decoding path as `ConduitMirror`.
  **IDR caching to prevent 1-second pauses**: the layer enters `AVQueuedSampleBufferRenderingStatusFailed`
  periodically; when that's detected, flush the layer then immediately replay the last cached IDR
  (NAL type 5) frame before enqueuing the new frame — all three steps in one `dispatch_async(main_queue)`
  block to avoid the race where you flush async and then enqueue into a still-failed layer. Without the
  cached IDR replay, P-frames can't decode until the next natural keyframe (~1 s). `SO_RCVBUF=256KB`
  on the TCP socket smooths bursty keyframe delivery. Acquires an `NSProcessInfo` activity assertion
  (`NSActivityLatencyCritical | NSActivityUserInitiatedAllowingIdleSystemSleep`) while the window is
  open so macOS doesn't App-Nap the heartbeat/socket when Conduit is in the background.
- `ConduitMic` — `RCTEventEmitter` that opens a TCP connection to Android's `MicServer` (port `5337`
  reused for mic, or a dedicated port — see Android side), feeds raw PCM into an `AudioQueue`, and
  routes that queue to **BlackHole** (`kAudioQueueProperty_CurrentDevice`). On start it saves the
  current system default input device (`kAudioHardwarePropertyDefaultInputDevice`), then sets
  BlackHole as the system default so every app (Zoom, FaceTime, Chrome, etc.) receives the phone audio
  automatically. On stop it restores the previous input device. Also holds an `NSProcessInfo` activity
  assertion. **Requires the user to have BlackHole installed** (`brew install --cask blackhole-2ch`);
  `findBlackHole()` scans CoreAudio devices for "BlackHole" in the name and returns
  `kAudioObjectUnknown` if not found. **macOS 26 HAL plugin note**: custom ad-hoc–signed HAL plugins
  are rejected by `DriverHelper.xpc` on macOS 26; BlackHole is Developer ID–signed and is accepted
  without issues — don't attempt to ship a custom CMIO plugin.

Two consequences when adding native code: (1) this `.mm` has C++ modules disabled, so use
`#import <Framework/...>` not `@import`, and link the framework explicitly; (2) including heavier RN
headers (e.g. `RCTEventEmitter.h`) transitively needs Folly. Both are handled in the `macos/Podfile`
`post_install` hook, which targets the `conduit-mac-macOS` native target by name (the project also
has an unused `conduit-mac-iOS` target — don't patch that one).

**`RCTViewManager` direct-event naming trap**: the event prop name must not shadow any name already
registered by `RCTView`. In particular, **`onPress` is already registered by `RCTView` as a bubbling
event** — registering it as a direct event on a custom view throws
`"Component 'RCTView' re-registered direct event 'topPress' as a bubbling event"` at startup and
makes the app unusable. Use a unique name like `onItemClick`, `onDragArm`, etc.

## Commands

### Mac app (`cd mac-app`)
```bash
npm install
npm run tsc        # type-check (run this to validate changes; strict mode)
npm start          # Metro bundler on :8081 — see gotcha below before running
npm run pods       # cd macos && pod install (re-run after changing native deps/Podfile)
```

**"No bundle URL present" / app crashes on launch** means Metro isn't running. Fix:
```bash
cd mac-app
export NVM_DIR="$HOME/.nvm" && source "$NVM_DIR/nvm.sh" && nvm use 20
npm start   # runs node start-metro.js
```
Then relaunch the app. Metro must stay running in the background whenever the app is open in
development. If you close the terminal or the process is killed, the app will show this error
on next launch until Metro is restarted.

**Building the native Mac app** (required after any change to `AppDelegate.mm`):
```bash
cd macos
xcodebuild -workspace conduit-mac.xcworkspace \
           -scheme conduit-mac-macOS \
           -configuration Debug \
           -derivedDataPath ~/Library/Developer/Xcode/DerivedData/conduit-mac-dvittawkxzomqdbjmcwsbjqealnp \
           build CODE_SIGNING_ALLOWED=NO
```
JS-only changes (anything under `mac-app/src/`) need only a Metro hot-reload (⌘R in the app) — no
rebuild. Native changes (`AppDelegate.mm`, Podfile, entitlements) require a full Xcode build.

There is no test runner configured. To validate logic without the full RN build, type-check the
dependency-free core in isolation (see how the connectivity core was verified: copy
`util/*`, `net/binary.ts|pairing.ts`, `protocol/types.ts` and run `tsc`/`node` against Node crypto).

### Android app (`cd android-app`)
Open in Android Studio (it generates the missing `gradle-wrapper.jar` and handles SDK/JDK/licenses).
Headless once a wrapper + SDK exist: `./gradlew assembleDebug` / `./gradlew installDebug`.
Requires JDK 17, Android SDK 35. **Not buildable from the CLI here without that toolchain.**

## Environment / gotchas discovered while building

- **Toolchain reality:** this machine has Node + Xcode + CocoaPods but **no Android JDK/SDK/adb** —
  the Android app can't be compiled here; it needs Android Studio. The Mac app builds and runs.

- **Node version: must be 20.** Node 26 (Homebrew default) breaks React Native's bundled `semver`.
  Use nvm: `export NVM_DIR="$HOME/.nvm" && source "$NVM_DIR/nvm.sh" && nvm use 20`. Always prefix
  Metro/npm commands with this.

- **`npm start` hangs on this machine — use `node start-metro.js` instead.** The project folder
  lives on a slow external APFS volume (`/Volumes/EXT-Home`, mounted `noowners`). Three compounding
  problems:
  1. `@react-native-community/cli`'s `loadConfigAsync` blocks the event loop (external-volume I/O
     during its synchronous startup), so `react-native start` never produces output.
  2. Metro's default file crawler shells out to `find` and only drains stdout, not stderr — the
     stderr pipe fills on the external volume and deadlocks.
  3. watchman's default socket lives under `$HOME` (the external volume); `noowners` makes unix
     sockets there unreliable.
  `mac-app/start-metro.js` bypasses the CLI, drives Metro's JS API directly, and starts a watchman
  daemon with its socket on `/tmp`. **watchman must be installed**: `brew install watchman`.
  `package.json` `"start"` script already points to `node start-metro.js`.
  If watchman doesn't start within 15 s, `ensureWatchman()` returns `false`, deletes
  `WATCHMAN_SOCK` (otherwise Metro finds the default system socket on the external volume and hangs),
  and Metro falls back to the JS node file watcher with `resolver.useWatchman: false`. That key must
  be inside the `resolver:` block — **not** top-level and not under `watcher:` — because
  `createFileMap.js` reads `config.resolver.useWatchman`. `mac-app/.watchmanconfig` tells watchman to
  ignore heavy directories (`node_modules`, `macos/Pods`, etc.) for faster startup.

- **`npm start` / Metro also needs a `packager-status:running` endpoint.** The plain
  `Metro.runServer` call doesn't serve `/status`, causing the RN app to return a nil bundle URL and
  crash with "No bundle URL present". `start-metro.js` wires in `@react-native-community/cli-server-api`
  + `@react-native/dev-middleware` (same as the CLI does) to serve that endpoint.

- **Testing needs physical devices on the same Wi-Fi.** mDNS + the local socket don't reach the
  Android emulator (NAT-isolated) and are blocked by guest/AP-isolation networks.

- **RN version pinning:** `react-native` and `react-native-macos` must match (currently 0.76.9 /
  0.76.12); a mismatch makes `react-native-macos-init` / `pod install` fail with ERESOLVE.
  `@react-native-community/cli*` is pinned to **v15** because the generated Podfile requires
  `cli-platform-ios/native_modules` (a path removed in v20).

- **Space in the project folder name** (`android-mac-connector_app 2`) breaks RN's ReactCodegen
  build script — the generated `Script-46EB2E00019740.sh` invokes `with-environment.sh` unquoted.
  Two fixes are already applied and must survive a `pod install` regen:
  1. `macos/Pods/Pods.xcodeproj/project.pbxproj` line with `/bin/sh -c` uses `\"$WITH_ENVIRONMENT\"`
     (quoted).
  2. `node_modules/react-native-macos/scripts/xcode/with-environment.sh` uses `"$1"` (quoted). This
     is in `node_modules` so a fresh `npm install` reverts it — re-apply or move it to a Podfile
     `post_install` patch if the build breaks again with
     `/bin/sh: /Volumes/EXT-Home/neil/Desktop/android-mac-connector_app: is a directory`.

- **`macos/Podfile` `post_install` does four load-bearing patches** — re-add them if the Podfile
  is regenerated:
  1. **fmt + Xcode 16+/clang:** RN 0.76 pins `fmt` 11.0.2, which new clang rejects (`consteval …
     not a constant expression`). `fmt`'s `base.h` unconditionally `#define`s `FMT_USE_CONSTEVAL`
     (no `#ifndef`), so a `-D` flag can't override it — the hook rewrites that header.
  2. **Links the frameworks** the `AppDelegate.mm` modules use, on the app target:
     `UserNotifications` (notifier), `UniformTypeIdentifiers` (drag-source UTI), `AVFoundation` +
     `CoreMedia` + `CoreVideo` (mirror/camera decode), `AudioToolbox` (audio player), `VideoToolbox`
     (H.264 decode), `CoreAudio` (ConduitMic device enumeration + system default input routing). Add
     to that `%w[...]` list when a new module needs a framework.
  3. **Adds `$(PODS_ROOT)/RCT-Folly` + `FOLLY_*` defines** to the app target so `AppDelegate.mm`
     can resolve `folly/dynamic.h` (pulled in transitively by `RCTEventEmitter.h`).
  4. **Copies missing folly subdirectory headers** — CocoaPods renames directories like
     `folly/json/` → `"folly/json 2/"` when a same-stem file (`folly/json.h`) exists alongside the
     directory. This leaves `folly/json/dynamic.h`, `folly/detail/RangeCommon.h`, etc. absent from
     the Public headers tree, causing fatal include errors. The hook copies any missing headers from
     `Pods/RCT-Folly/folly/` into `Pods/Headers/Public/RCT-Folly/folly/`.

- **AppRegistry name:** the JS component must register under the native module name. `app.json`
  `name` is `conduit-mac` (matches the generated macOS target), `displayName` is `Conduit`. A
  mismatch throws `Invariant Violation: "…" has not been registered`.

- **Mac-side file picking** is the native `ConduitFilePicker` (`NSOpenPanel`/`NSSavePanel`) —
  `react-native-document-picker` was iOS-only and is gone. RNFS *is* macOS-capable and does the
  streaming IO.

- **Reinstalling the Android app disables its AccessibilityService** (and notification access) — re-
  enable in Settings after every full install; this kills *all* remote control (mouse + keyboard) at
  once, so "control stopped working after a rebuild" is almost always this, not a code bug.

- **App Sandbox is disabled** (`conduit-mac.entitlements`, `app-sandbox` = false). It's a local app,
  and the sandbox blocked notification image attachments. The macOS notification's *left* icon is
  always Conduit's and can't be changed; the source-app icon is sent as a `UNNotificationAttachment`
  (right-side thumbnail) — the in-app Notifications tab also renders it directly.

- **Notification attachments fail on external-volume homes:** on the build machine `$HOME` is on
  `/Volumes/EXT-Home`, and `addNotificationRequest` rejects every image attachment with
  `UNErrorDomain code=104` (`attachmentMoveIntoDataStoreFailed`) regardless of staging dir/sandbox.
  This is an OS/`usernoted` limitation with the home on an external volume — the attachment code is
  correct and works when `$HOME` is on the internal boot volume. Don't keep "fixing" the app side.

## Security note

Transport is `ws://` but the **session is encrypted** (protocol v2, `protocol/README.md` §6):
after the plaintext handshake, every WebSocket frame is **AES-256-GCM** (sent as a binary frame;
handshake frames are text — the frame type is the plaintext/ciphertext discriminator). The key is
`HKDF-SHA256(token, clientNonce‖serverNonce)`; the token is never sent in the clear (already-paired
clients prove possession implicitly; first-pairing `pair.ok` ships the token encrypted under
`HKDF(secret,salt)`). Crypto is `mac-app/src/util/crypto.ts` (`@noble/ciphers` AES + hand-rolled HKDF
on the existing HMAC) ⇄ `android-app/.../connection/ConduitCrypto.kt` (`javax.crypto`); keep them in
sync. **Bulk sockets (file/screen/audio/MMS-image HTTP) stay plaintext** but are gated by unguessable
`transferId` capability tokens delivered only over the encrypted WS. `PROTOCOL_VERSION` is `2`; a v1
peer is rejected (`version_unsupported`), so both apps must be rebuilt together.
