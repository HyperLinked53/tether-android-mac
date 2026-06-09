# Conduit wire protocol (v1)

One persistent WebSocket connection carries everything. Two frame kinds travel over it:

1. **Control frames** — UTF‑8 **text** frames containing a JSON envelope.
2. **Data frames** — **binary** frames used for bulk payloads (file chunks today,
   screen-mirror video later).

The Android app is the **server** (it hosts the socket); the Mac app is the **client**.
This is an implementation detail — the protocol itself is symmetric, and either peer may
send any message unless noted.

## 1. Control envelope

```jsonc
{
  "v": 1,                 // protocol version (integer)
  "id": "9f1c…",          // uuid v4, unique per message
  "type": "file.offer",   // "namespace.action"
  "replyTo": "3a0b…",     // optional: id of the message this responds to
  "payload": { }          // type-specific object (see below)
}
```

Rules:

- Unknown `type` values **must** be ignored (forward compatibility), except a peer may
  reply with `error` referencing the unknown message `id`.
- A message with `replyTo` set is a response; senders correlate requests/responses by id.
- `v` mismatch on handshake aborts the connection with `error` code `version_unsupported`.

## 2. Connection lifecycle

```
Mac (client)                         Android (server)
     │  ── TCP/WS connect ──────────────►  │
     │  ── hello ───────────────────────►  │   identifies client, offers token
     │  ◄── hello ─────────────────────    │   identifies server
     │                                      │
     │   if token unknown/absent:           │
     │  ◄── pair.required ──────────────    │
     │  ── pair.request {secretProof} ──►   │   user-entered code proves the secret
     │  ◄── pair.ok {token} | error ───     │   token persisted both sides
     │                                      │
     │  ══ authenticated session ═══════════│
     │  ── ping ⇄ pong (heartbeat 15s) ──   │
```

### `hello`

```jsonc
// payload
{
  "device": {
    "id": "stable-uuid",        // persistent per install
    "name": "Neil's Pixel 8",
    "platform": "android",      // "android" | "macos"
    "appVersion": "0.1.0"
  },
  "token": "…"                  // client only; omit if never paired with this peer
}
```

### Pairing

`pair.required` (server → client) — sent when the client presented no/invalid token.

```jsonc
{ "salt": "base64", "iterations": 100000 }   // KDF params for the proof
```

The phone displays a **pairing secret** as a short human-typable code (currently a 6-digit
number, chosen for typo-free manual entry) and the same value inside a QR payload:

```
conduit://pair?deviceId=<id>&secret=<code>&v=1
```

The secret's format is not part of the wire contract — the `proof` is opaque to it. `host`/`port`
may also be included for out-of-band connect, but are normally redundant since the Mac already has
them from mDNS discovery — only `secret` (and `deviceId`) are required to pair.

The Mac obtains `secret` (typed code or scanned QR), then computes a proof and sends:

`pair.request` (client → server)

```jsonc
{ "proof": "base64(HMAC-SHA256(secret, salt || deviceId))" }
```

`pair.ok` (server → client) on success:

```jsonc
{ "token": "base64-32-bytes" }   // long-term auth token, stored by both peers keyed by deviceId
```

On failure the server sends `error` with code `pair_failed` and closes.

### Heartbeat

Either side may send `ping`; peer replies `pong` with `replyTo`. Missing 2 consecutive
expected pongs ⇒ treat connection as dead and reconnect.

## 3. Binary data frames

Bulk transfers don't go through JSON (no base64 overhead). Every binary WS frame begins
with a fixed 21‑byte header:

```
offset  size  field
0       1     version (0x01)
1       1     channel  (0x01 = file chunk; 0x02 = reserved: screen video)
2       16    streamId (raw UUID bytes — e.g. the file transferId)
18      3     reserved (0x000000)
21      …     payload bytes
```

Control of a binary stream (start, accept, progress, completion, errors) always happens via
JSON control messages; the binary frames carry only the bytes.

## 4. Feature messages

### 4.1 File transfer (`file.*`)  — implemented

File **bytes do not travel over the WebSocket**. The WebSocket only *coordinates*; the bytes stream
over a separate plain-HTTP channel that the **Android app hosts on the LAN** (a tiny raw streaming
server, default port 5334). This keeps the byte path native on both ends (Android disk↔socket;
Mac via `NSURLSession`/RNFS), so it's fast — no base64, no JS bridge per chunk. The Android phone is
always the HTTP *server*; the Mac is always the HTTP *client* (it can't host). Everything stays on
the local network; no external server is involved. The `transferId` is an unguessable UUID minted
over the authenticated WebSocket and acts as a capability token for the transfer URL.

| type            | dir            | payload                                          |
| --------------- | -------------- | ------------------------------------------------ |
| `file.offer`    | sender→receiver| `{ transferId, name, size, mime, httpPort? }`    |
| `file.accept`   | receiver→sender| `{ transferId, httpPort? }`                      |
| `file.reject`   | either         | `{ transferId, reason }`                         |
| `file.progress` | receiver→sender| `{ transferId, received }`  (periodic)           |
| `file.complete` | receiver→sender| `{ transferId, sha256? }`  (receiver has it all) |
| `file.error`    | either         | `{ transferId, message }`                        |

HTTP endpoints on the Android server (`httpPort`), keyed by `transferId`:
- `GET /download/<transferId>` → streams the file (used for **Android→Mac**).
- `POST /upload/<transferId>`  → raw body (Content-Length-delimited) written to disk (**Mac→Android**).

**Android→Mac:** Android registers the file for download and sends `file.offer` (with its `httpPort`);
the Mac `GET`s `/download/<id>` (RNFS `downloadFile`), then sends `file.complete`.
**Mac→Android:** the Mac sends `file.offer`; Android registers an upload slot and replies
`file.accept` (with its `httpPort`); the Mac `POST`s the raw file to `/upload/<id>`
(RNFS `uploadFiles`, `binaryStreamOnly`); Android writes it and sends `file.complete`.

> Binary channel `0x01` (see §3) is no longer used for file chunks — it remains reserved.

#### Remote browsing (`fs.*`) — implemented

The Mac browses the phone's storage and pulls files it finds. Listing is metadata-only over the
WebSocket; bytes reuse the HTTP path above. `fs.pull` just *arms* a download (mints a `transferId`
and registers it on the Android HTTP server) for a file the Mac located by browsing, rather than
one the phone's user picked. The Mac then `GET`s `/download/<transferId>` and sends `file.complete`
to release it — identical to the Android→Mac flow.

| type           | dir       | payload                                               |
| -------------- | --------- | ----------------------------------------------------- |
| `fs.list`      | Mac→phone | `{ path }`  (`""` = storage root)                     |
| `fs.listing`   | phone→Mac | `{ path, entries: FsEntry[], error? }`                |
| `fs.pull`      | Mac→phone | `{ path }`                                            |
| `fs.pullReady` | phone→Mac | `{ transferId, httpPort, name, size, mime }`          |
| `fs.error`     | phone→Mac | `{ path?, message }`                                  |

`FsEntry = { name, path, isDir, size, modified }`. `path` is an absolute phone path, opaque to the
Mac (it's echoed back in `fs.list`/`fs.pull`). `fs.listing.error` is set (e.g. `"permission"`) when
the phone lacks **All files access** (`MANAGE_EXTERNAL_STORAGE`), so the Mac can prompt instead of
showing an empty folder.

### 4.2 Device status (`status.*`) — implemented

| type            | dir     | payload                                              |
| --------------- | ------- | ---------------------------------------------------- |
| `status.query`  | either  | `{}`                                                 |
| `status.report` | either  | `{ battery: { level, charging }, name, platform }`   |

### 4.3 Clipboard (`clip.*`) — scaffold

| type        | dir    | payload               |
| ----------- | ------ | --------------------- |
| `clip.push` | either | `{ text }`            |

### 4.4 Notifications (`notif.*`)

| type            | dir            | payload                                                                          |
| --------------- | -------------- | -------------------------------------------------------------------------------- |
| `notif.posted`  | android → mac  | `{ key, app, appName, title, text, postedAt, iconPng?, canReply, suggestions[] }`|
| `notif.removed` | android → mac  | `{ key }`                                                                        |
| `notif.reply`   | mac → android  | `{ key, text }`                                                                  |

- `iconPng` is a base64 PNG of the source app's launcher icon (downscaled), shown as the macOS
  notification's image. `appName` is the human app label; `app` is the package name.
- `canReply` is true when the Android notification carries an inline `RemoteInput` reply action
  (the signal for "messaging app"). `suggestions` are on-device smart replies
  (`TextClassifier.suggestConversationActions`, API 29+), populated only when `canReply`.
- `notif.reply` fills the original notification's `RemoteInput` and fires its `PendingIntent`, so
  the reply is delivered by the source messaging app itself.

### 4.5 Messaging / SMS + MMS (`sms.*`) — implemented (read MMS, send SMS)

The Mac shows all conversations and history (SMS **and** received MMS, with images), by contact
name, and sends SMS. Sending MMS is out of scope (would require Conduit to be the phone's default SMS
app). Needs `READ_SMS` / `SEND_SMS` / `RECEIVE_SMS` / `READ_CONTACTS` on the phone.

| type              | dir            | payload                                                       |
| ----------------- | -------------- | ------------------------------------------------------------- |
| `sms.threads`     | mac → android  | `{}` → replied with `sms.threadList`                          |
| `sms.threadList`  | android → mac  | `{ threads: [{ id, address, name?, snippet, date }] }`        |
| `sms.messages`    | mac → android  | `{ threadId }` → replied with `sms.messageList`               |
| `sms.messageList` | android → mac  | `{ threadId, messages: [SmsMessage] }`                        |
| `sms.send`        | mac → android  | `{ address, body }`  (SMS only)                               |
| `sms.sent`        | android → mac  | `{ address, ok, error? }`                                     |
| `sms.incoming`    | android → mac  | `{ threadId, address, name?, message }`  (live inbound SMS)   |
| `sms.contacts`    | mac → android  | `{ query? }` → replied with `sms.contactList`                 |
| `sms.contactList` | android → mac  | `{ contacts: [{ name, number }] }`                            |

`SmsMessage = { id, body, date, mine, mms?, attachmentUrl?, attachmentMime? }`. MMS image parts are
served over the **file HTTP server** (Android hosts; `attachmentUrl` is a `…/download/<id>` the Mac's
`<Image>` loads natively) — bytes don't cross the WebSocket. `name` is resolved from Contacts
(`PhoneLookup`). Inbound SMS is pushed live via a `BroadcastReceiver`; received MMS appears on refresh.

### 4.6 Screen mirroring (`screen.*`) — view implemented; control roadmap

The Mac views the phone's live screen. The WebSocket only *coordinates*; H.264 bytes stream over a
**dedicated raw TCP socket the Android app hosts** (default port 5335) — the same "Android hosts,
Mac connects natively" pattern as file transfer, so the video never touches the JS bridge. Android
captures via `MediaProjection` → `MediaCodec` (H.264); the Mac decodes with VideoToolbox behind a
native module and renders into an `AVSampleBufferDisplayLayer`. (Binary `channel=0x02` over the
WebSocket is therefore *not* used — it stays reserved.)

| type           | dir       | payload                                          |
| -------------- | --------- | ------------------------------------------------ |
| `screen.start` | Mac→phone | `{ maxWidth?, maxHeight?, bitrate?, audio? }`    |
| `screen.ready` | phone→Mac | `{ port, width, height, audioPort }`             |
| `screen.stop`  | either    | `{}`                                             |
| `screen.error` | phone→Mac | `{ message }`                                    |
| `screen.audio` | Mac→phone | `{ target: 'phone' \| 'mac' }`                   |

**Audio** is optional and routable: `audio`/`screen.audio` `target` is `'phone'` (default — audio
stays on the phone, nothing streamed) or `'mac'`. For `'mac'` the phone captures playback audio
(`AudioPlaybackCapture`, API 29+) and streams **raw PCM** (48 kHz, stereo, 16-bit LE) over a second
raw TCP socket (`audioPort`, default 5336); the Mac plays it via AudioToolbox. `screen.audio` flips
the routing live without restarting the mirror.

`MediaProjection` consent is granted **on the phone** (an Activity dialog). Flow: the phone user (or
a Mac `screen.start`, which prompts on the phone) approves capture → Android starts encoding, hosts
the video socket, and sends `screen.ready` → the Mac connects to `host:port`. The video stream is
length-prefixed frames: `[4-byte BE length][1-byte flags: bit0=config(SPS/PPS), bit1=keyframe]
[Annex-B payload]`; the server replays the latest config + last keyframe so a new viewer starts
immediately. `screen.stop` (either side) or socket close tears down the `VirtualDisplay`/encoder.

**Control (mouse/keyboard) — implemented** via an `input.*` namespace (Mac→phone) driven by
Conduit's Android `AccessibilityService` (`dispatchGesture` + global actions + focused-field text),
since a non-system app can't inject input into other apps. The user enables the service once in
Settings.

| type             | payload                                   | effect on phone                          |
| ---------------- | ----------------------------------------- | ---------------------------------------- |
| `input.tap`      | `{ x, y }`                                | tap                                      |
| `input.longpress`| `{ x, y }`                                | long-press                               |
| `input.swipe`    | `{ x1, y1, x2, y2, ms }`                  | swipe/drag along the path                |
| `input.scroll`   | `{ x, y, dx, dy }`                        | scroll (mapped to a swipe)               |
| `input.key`      | `{ text? , code? }`                       | type into the focused field / backspace / enter |
| `input.button`   | `{ name: 'back'\|'home'\|'recents' }`     | global navigation action                 |

All coordinates are **normalized `[0,1]`** relative to the mirrored screen, so they map to the
phone's real pixels regardless of the encoded resolution. Gesture-based, so control is best-effort
(slightly laggy, not pixel-perfect) and the keyboard reaches focused text fields, not games/shortcuts.

## 5. Errors

`error` (either direction):

```jsonc
{ "code": "pair_failed", "message": "human readable", "fatal": true }
```

Common codes: `version_unsupported`, `unauthenticated`, `pair_failed`, `bad_request`,
`transfer_failed`, `internal`.

## 6. Session encryption (v2)

After the handshake, **all WebSocket frames are AES-256-GCM encrypted** so the link is private on the
LAN. The handshake itself (hello / pair.* / errors) is sent as plaintext **text** frames; every
encrypted message is sent as a **binary** frame (`nonce[12] ‖ ciphertext ‖ tag[16]`) — the frame type
is the discriminator.

- **Session key** = `HKDF-SHA256(ikm = token bytes, salt = clientNonce ‖ serverNonce,
  info = "conduit-session-v1")` → 32 bytes. The client sends `clientNonce` in `hello`; the server
  returns `serverNonce` in its `hello` reply (already-paired) or in `pair.ok` (first pairing).
- **The token is never sent in the clear.** Already-paired clients prove possession implicitly (only
  the real token derives a key that decrypts/authenticates frames). On first pairing, `pair.ok`
  carries the token **encrypted** under `HKDF-SHA256(secret, salt, "conduit-pair-v1")` (the 6-digit
  pairing secret), so an eavesdropper can't lift it.
- **Bulk sockets** (file/screen/audio/MMS-image HTTP) stay plaintext but are gated by unguessable
  `transferId` capability tokens that now travel only over the encrypted WebSocket, so an eavesdropper
  can't request them.
- `PROTOCOL_VERSION` is `2`; a v1 peer is rejected (`version_unsupported`) — no plaintext downgrade.
