# Tether — Android ⇄ Mac

Tether links your Android phone and your Mac so they work as one. Inspired by
**Microsoft Phone Link** and **KDE Connect**.

- **Mac app** — React Native (macOS)
- **Android app** — native Kotlin + Jetpack Compose

The two apps discover each other over local Wi‑Fi, pair with a 6-digit code, and
hold a single persistent encrypted WebSocket that carries every feature.

```
┌────────────────┐        mDNS discovery          ┌────────────────┐
│   Mac app      │ ───────────────────────────►   │  Android app   │
│ (React Native) │                                 │  (Kotlin)      │
│                │  ◄═══ encrypted WebSocket ════► │  WS server +   │
│  WS client     │      AES-256-GCM (v2)           │  foreground svc│
└────────────────┘                                 └────────────────┘
```

The Android app hosts a WebSocket server inside a foreground service and advertises
itself via mDNS (service type `_tether._tcp`). The Mac discovers it with Bonjour and
connects as the client. All features share a single
[wire protocol](protocol/README.md).

## Install

Download the latest **Tether.dmg** (Mac) and **Tether.apk** (Android) from the
[Releases](../../releases/latest) page.

### Mac

1. Open **Tether.dmg** and drag **Tether.app** to your Applications folder
2. Because Tether isn't from the App Store, macOS will block it on first launch.
   Open Terminal and run:
   ```
   xattr -rd com.apple.quarantine /Applications/Tether.app
   ```
3. Launch Tether from Applications as normal

> The `xattr` command removes the quarantine flag macOS adds to files downloaded
> from the internet. It's a one-time step and is safe to run on your own app.

### Android

1. Transfer **Tether.apk** to your phone — Google Drive, email, or a USB cable all work
2. On your phone, open the APK file from your Downloads folder or Files app
3. Tap **Install**
   - If Android asks to enable *Install unknown apps*, tap **Settings**, enable it
     for the current app, then go back and tap **Install** again
   - If Google Play Protect warns about the app, tap **Install anyway** — it flags
     any APK not distributed through the Play Store, not because anything is wrong
4. Open **Tether** from your app drawer

## Features

| Feature | Notes |
| --- | --- |
| **Device discovery** | mDNS/Bonjour — finds the phone automatically on the same Wi-Fi |
| **Secure pairing** | 6-digit code shown on phone; HMAC-SHA256 proof; AES-256-GCM session encryption |
| **File transfer** | Browse the phone's full file tree; multi-select with Shift/⌘-click; drag files in and out |
| **Photos** | Phone gallery tab; drag photos to and from the Mac |
| **Notifications** | Phone notifications mirrored to Mac with source app icon and smart replies |
| **SMS / MMS** | Read threads by contact name; send SMS; live inbound; MMS images |
| **Screen mirroring** | Live H.264 stream in its own Mac window; mouse and keyboard control |
| **Clipboard sync** | Two-way, with on/off toggle |
| **Find my phone** | Rings the phone at alarm volume for 10 s, bypasses silent/DND |
| **Phone as webcam** | Camera2 H.264 stream to a Mac window; optional phone mic via BlackHole |
| **Dark mode** | System / Light / Dark toggle on both Mac and Android |

## Pairing

1. Open Tether on your Android phone
2. Open Tether on your Mac — it will discover the phone automatically
3. Tap **Pair** on the Mac and enter the 6-digit code shown on the phone

After pairing the apps reconnect instantly whenever they're on the same Wi-Fi.

## Repository layout

| Path | What it is |
| --- | --- |
| `protocol/` | Wire protocol spec + canonical TypeScript types |
| `android-app/` | Kotlin Android app (WebSocket server, all feature handlers, Compose UI) |
| `mac-app/` | React Native macOS app (discovery, connection, feature UI, native modules) |

## Build from source

### Mac app
Requires Node 20, Xcode, CocoaPods.

```bash
cd mac-app
export NVM_DIR="$HOME/.nvm" && source "$NVM_DIR/nvm.sh" && nvm use 20
npm install
npm run pods          # cd macos && pod install
node bundle-release.js   # build the JS bundle
# then build in Xcode or via xcodebuild
```

### Android app
Requires Android Studio, JDK 17, Android SDK 35.

```bash
cd android-app
./gradlew assembleDebug   # outputs app/build/outputs/apk/debug/app-debug.apk
```

Or open `android-app/` in Android Studio and use **Build → Build APK**.

## Security

Transport is `ws://` but every WebSocket frame is encrypted with **AES-256-GCM**
(protocol v2). The session key is derived with `HKDF-SHA256` from the pairing token
and per-session nonces exchanged during the handshake. The token is never sent in
the clear — already-paired clients authenticate implicitly; first-pairing ships the
token encrypted under a key derived from the shared secret. Bulk data (file bytes,
video, audio) travels over dedicated TCP sockets gated by unguessable capability
tokens delivered only over the encrypted WebSocket.
