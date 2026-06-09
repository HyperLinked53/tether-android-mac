# Conduit — Android app (native Kotlin)

The phone side of Conduit. It hosts a WebSocket server inside a foreground service, advertises
itself on the LAN with NSD, handles secure pairing, and implements the feature handlers.

## Requirements

- Android Studio (Ladybug or newer) with **JDK 17** and the Android SDK (compileSdk 35).
- A physical Android device (API 26+) on the **same Wi‑Fi** as your Mac. NSD/mDNS and the
  local socket don't traverse most "AP isolation" guest networks.

> This repo ships source + Gradle config but **not** the `gradle-wrapper.jar` binary. Opening the
> project in Android Studio generates it automatically. From a CLI with Gradle installed you can
> instead run `gradle wrapper --gradle-version 8.9` once in `android-app/`.

## Build & run

```bash
# In Android Studio: File ▸ Open ▸ select android-app/, let it sync, then Run ▸ app.
# Or, headless once the wrapper exists and a device is attached:
cd android-app
./gradlew installDebug
adb shell am start -n com.conduit.android/.ui.MainActivity
```

On first launch:

1. Grant the prompted permissions (notifications, and SMS if you want the messaging scaffold).
2. Tap **Start** — the service comes up and begins advertising.
3. Tap **Pair a device** and scan the QR (or copy the code) from the Mac app.

## Architecture

```
ui/MainActivity ──reads──► state/ConduitState (StateFlows) ◄──writes── connection/* , features/*
                                                                              ▲
connection/ConnectionService (foreground)                                     │
   ├─ ConduitServer (Java-WebSocket)  ── routes by type ──► features/FileTransferHandler  ✅
   │                                                        features/StatusHandler        ✅
   │                                                        features/ClipboardHandler     🟡
   │                                                        features/SmsHandler           🟡
   ├─ DeviceAdvertiser (NSD)
   └─ PairingManager (HMAC proof + token store)
features/ConduitNotificationListener (separate system-bound service)          🟡
```

- **Ordering:** each peer's messages flow through a per-session `Channel`, so text and binary
  frames are handled strictly in arrival order (critical for in-order file chunks).
- **Files land in:** `Android/data/com.conduit.android/files/Inbox/` (app-specific external
  storage — no extra permission needed). Switch to `MediaStore`/Downloads for a shipping build.

## Known simplifications (foundation, not production)

- Transport is `ws://` with token auth. Harden to `wss://` + cert pinning (see top-level README).
- Incoming files are auto-accepted; add a user prompt for untrusted peers.
- Outgoing file sends don't yet apply socket backpressure — fine for typical files, add
  windowing for very large ones.
- SMS / notifications / clipboard-from-phone are scaffolds; see each handler's KDoc TODOs.
- Screen mirroring is reserved in the protocol only (binary channel `0x02`).
