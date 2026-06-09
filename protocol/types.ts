/**
 * Conduit wire protocol — canonical TypeScript types (v1).
 *
 * This file is the single source of truth for the control-frame shapes described in
 * ./README.md. The Mac app imports it directly; the Android app mirrors it in
 * `connection/Protocol.kt`. Keep all three in sync.
 */

export const PROTOCOL_VERSION = 2 as const; // v2 adds LAN session encryption (§6)

/** mDNS / Bonjour service type both peers use for discovery. */
export const SERVICE_TYPE = '_conduit._tcp' as const;

/** Binary data-frame channels (see README §3). */
export enum BinaryChannel {
  FileChunk = 0x01,
  ScreenVideo = 0x02, // reserved (roadmap)
}

export type Platform = 'android' | 'macos';

export interface DeviceInfo {
  id: string;
  name: string;
  platform: Platform;
  appVersion: string;
}

/** Every control frame is one of these, discriminated by `type`. */
export interface Envelope<T extends MessageType = MessageType> {
  v: typeof PROTOCOL_VERSION;
  id: string;
  type: T;
  replyTo?: string;
  payload: PayloadFor<T>;
}

export type MessageType =
  // lifecycle
  | 'hello'
  | 'pair.required'
  | 'pair.request'
  | 'pair.ok'
  | 'ping'
  | 'pong'
  | 'error'
  // file transfer
  | 'file.offer'
  | 'file.accept'
  | 'file.reject'
  | 'file.progress'
  | 'file.complete'
  | 'file.error'
  // remote file browsing (Mac browses the phone's storage)
  | 'fs.list'
  | 'fs.listing'
  | 'fs.pull'
  | 'fs.pullReady'
  | 'fs.error'
  // photo gallery (Mac browses the phone's photos)
  | 'photos.list'
  | 'photos.listing'
  | 'photos.pull'
  | 'photos.pullReady'
  // device status
  | 'status.query'
  | 'status.report'
  // clipboard (scaffold)
  | 'clip.push'
  // notifications (scaffold)
  | 'notif.posted'
  | 'notif.removed'
  | 'notif.reply'
  // sms / mms messaging
  | 'sms.threads'
  | 'sms.threadList'
  | 'sms.messages'
  | 'sms.messageList'
  | 'sms.send'
  | 'sms.sent'
  | 'sms.incoming'
  | 'sms.contacts'
  | 'sms.contactList'
  // screen mirroring
  | 'screen.start'
  | 'screen.ready'
  | 'screen.stop'
  | 'screen.error'
  | 'screen.audio'
  // phone as webcam — streams the phone's camera over a raw TCP socket (same pattern as screen)
  | 'camera.start'
  | 'camera.ready'
  | 'camera.stop'
  | 'camera.error'
  | 'camera.switch'
  // remote control (Mac drives the phone while mirroring)
  | 'input.tap'
  | 'input.longpress'
  | 'input.swipe'
  | 'input.scroll'
  | 'input.key'
  | 'input.button'
  // find-my-phone + send-to-phone
  | 'phone.ring'
  | 'phone.openUrl'
  // media controls
  | 'media.info'
  | 'media.control'
  // phone-as-trackpad (Android → Mac)
  | 'trackpad.move'
  | 'trackpad.scroll'
  | 'trackpad.tap'
  | 'trackpad.rightTap'
  | 'trackpad.doubleTap'
  | 'trackpad.key';

// ---------------------------------------------------------------------------
// Payloads
// ---------------------------------------------------------------------------

export interface HelloPayload {
  device: DeviceInfo;
  /** base64 random nonce for session-key derivation (client sends its own; server replies with its
   *  own only on the already-paired path). The long-term token is NEVER sent in the clear. */
  nonce?: string;
}

export interface PairRequiredPayload {
  salt: string; // base64
  iterations: number;
}

export interface PairRequestPayload {
  /** base64( HMAC-SHA256(secret, salt || deviceId) ) */
  proof: string;
}

export interface PairOkPayload {
  /** The long-term token, AES-GCM-encrypted under a key derived from the pairing secret (base64 of
   *  nonce ‖ ciphertext ‖ tag), so it isn't sent in the clear. */
  tokenEnc: string;
  /** Server's nonce for session-key derivation (with the client nonce from hello). */
  nonce: string;
}

export interface ErrorPayload {
  code:
    | 'version_unsupported'
    | 'unauthenticated'
    | 'pair_failed'
    | 'bad_request'
    | 'transfer_failed'
    | 'internal';
  message: string;
  fatal?: boolean;
}

export interface FileOfferPayload {
  transferId: string; // uuid; also the capability token for the HTTP transfer URL
  name: string;
  size: number; // bytes
  mime: string;
  httpPort?: number; // Android→Mac: port to GET the bytes from (Android's file HTTP server)
}
export interface FileAcceptPayload {
  transferId: string;
  httpPort?: number; // Mac→Android: port the Mac should POST the bytes to
}
export interface FileRejectPayload { transferId: string; reason: string }
export interface FileProgressPayload { transferId: string; received: number }
export interface FileCompletePayload { transferId: string; sha256?: string }
export interface FileErrorPayload { transferId: string; message: string }

// Remote file browsing: the Mac lists and pulls files from the phone's storage. Bytes still flow
// over the existing HTTP file path; `fs.pull` just arms a download (mints a transferId) for a file
// the Mac located by browsing, rather than one the phone's user picked.
export interface FsEntry {
  name: string;
  path: string; // absolute path on the phone; opaque to the Mac (echoed back in fs.list/fs.pull)
  isDir: boolean;
  size: number; // bytes (0 for directories)
  modified: number; // epoch millis
}
export interface FsListPayload { path: string } // "" = storage root
export interface FsListingPayload {
  path: string;
  entries: FsEntry[];
  /** Set when the listing couldn't be produced, e.g. "permission" (All-files-access not granted). */
  error?: string;
}
// Photo gallery. Thumbnails + full images are served over the file HTTP server (Android hosts), like
// MMS images; `thumbUrl` carries a `%HOST%` placeholder the Mac rewrites to the connected host.
export interface PhotoItem {
  id: number;
  name: string;
  date: number; // epoch millis
  width: number;
  height: number;
  thumbUrl: string;
}
export interface PhotosListPayload { limit?: number }
export interface PhotosListingPayload { photos: PhotoItem[] }
export interface PhotosPullPayload { id: number }
export interface PhotosPullReadyPayload {
  id: number;
  transferId: string;
  httpPort: number;
  name: string;
  size: number;
  mime: string;
}

export interface FsPullPayload { path: string }
export interface FsPullReadyPayload {
  transferId: string; // capability token for GET /download/<transferId>
  httpPort: number;
  name: string;
  size: number;
  mime: string;
}
export interface FsErrorPayload { path?: string; message: string }

export interface BatteryInfo { level: number; charging: boolean }
export interface StatusReportPayload {
  battery: BatteryInfo;
  name: string;
  platform: Platform;
}

export interface ClipPushPayload { text: string }

export interface NotifPostedPayload {
  key: string;
  app: string; // source package name, e.g. "com.whatsapp"
  appName: string; // human label, e.g. "WhatsApp"
  title: string;
  text: string;
  postedAt: number;
  iconPng?: string; // base64 PNG of the source app's icon
  canReply: boolean; // notification carries an inline RemoteInput reply action
  suggestions: string[]; // smart-reply suggestions (only for replyable/messaging notifications)
}
export interface NotifRemovedPayload { key: string }
export interface NotifReplyPayload { key: string; text: string }

export interface SmsThread { id: string; address: string; name?: string; snippet: string; date: number }
export interface SmsThreadListPayload { threads: SmsThread[] }
export interface SmsMessagesPayload { threadId: string }
export interface SmsMessage {
  id: string;
  body: string;
  date: number;
  mine: boolean;
  mms?: boolean;
  attachmentUrl?: string; // MMS image, fetched from the phone's file HTTP server (Android hosts)
  attachmentMime?: string;
}
export interface SmsMessageListPayload { threadId: string; messages: SmsMessage[] }
export interface SmsSendPayload { address: string; body: string }
export interface SmsSentPayload { address: string; ok: boolean; error?: string }
/** Live inbound SMS pushed from the phone. */
export interface SmsIncomingPayload { threadId: string; address: string; name?: string; message: SmsMessage }
export interface SmsContact { name: string; number: string }
export interface SmsContactsPayload { query?: string }
export interface SmsContactListPayload { contacts: SmsContact[] }

export interface MediaInfoPayload {
  title: string;
  artist: string;
  album: string;
  /** Human label of the app currently playing (e.g. "Spotify", "YouTube"). */
  appName: string;
  isPlaying: boolean;
  duration: number; // ms; 0 = unknown (live streams)
  position: number; // ms as of lastUpdateTime
  /** elapsedRealtime() ms on the phone when position was recorded — lets the Mac extrapolate. */
  lastUpdateTime: number;
}

export type MediaControlAction = 'play_pause' | 'next' | 'previous';
export interface MediaControlPayload { action: MediaControlAction }

/** Where the phone's audio should play while mirroring. 'phone' = stays on the phone (not streamed). */
export type AudioTarget = 'phone' | 'mac';
export interface ScreenStartPayload { maxWidth?: number; maxHeight?: number; bitrate?: number; audio?: AudioTarget }
/** Capture is live; connect a raw TCP video socket to the phone at `host:port`. */
export interface ScreenReadyPayload { port: number; width: number; height: number; audioPort: number }
export interface ScreenErrorPayload { message: string }
/** Switch audio routing live during a mirror session. */
export interface ScreenAudioPayload { target: AudioTarget }

// Phone-as-webcam. Same TCP streaming pattern as screen mirroring (H.264 Annex-B, port 5337).
export type CameraFacing = 'front' | 'back';
export interface CameraStartPayload { facing?: CameraFacing; maxWidth?: number; bitrate?: number }
/** Camera is live; connect a raw TCP video socket to `host:port`; optionally connect mic to `host:micPort`. */
export interface CameraReadyPayload { port: number; width: number; height: number; micPort?: number }
export interface CameraErrorPayload { message: string }
/** Switch the active camera live during a streaming session. Also used to change resolution mid-stream. */
export interface CameraSwitchPayload { facing: CameraFacing; maxWidth?: number; bitrate?: number }

// Remote control. All coordinates are normalized [0,1] relative to the mirrored screen, so they map
// to the phone's real pixels regardless of the encoded resolution. Driven by the phone's
// AccessibilityService (gestures + global actions + focused-field text).
export interface InputTapPayload { x: number; y: number }
export interface InputLongPressPayload { x: number; y: number }
export interface InputSwipePayload { x1: number; y1: number; x2: number; y2: number; ms: number }
export interface InputScrollPayload { x: number; y: number; dx: number; dy: number }
/** `text` types a string into the focused field; `code` is a special key (backspace/enter/space). */
export interface InputKeyPayload { text?: string; code?: 'backspace' | 'enter' | 'space' }
export interface InputButtonPayload { name: 'back' | 'home' | 'recents' }

/** Maps a MessageType to its payload shape. */
export type PayloadFor<T extends MessageType> =
  T extends 'hello' ? HelloPayload :
  T extends 'pair.required' ? PairRequiredPayload :
  T extends 'pair.request' ? PairRequestPayload :
  T extends 'pair.ok' ? PairOkPayload :
  T extends 'ping' | 'pong' ? Record<string, never> :
  T extends 'error' ? ErrorPayload :
  T extends 'file.offer' ? FileOfferPayload :
  T extends 'file.accept' ? FileAcceptPayload :
  T extends 'file.reject' ? FileRejectPayload :
  T extends 'file.progress' ? FileProgressPayload :
  T extends 'file.complete' ? FileCompletePayload :
  T extends 'file.error' ? FileErrorPayload :
  T extends 'fs.list' ? FsListPayload :
  T extends 'fs.listing' ? FsListingPayload :
  T extends 'fs.pull' ? FsPullPayload :
  T extends 'fs.pullReady' ? FsPullReadyPayload :
  T extends 'fs.error' ? FsErrorPayload :
  T extends 'photos.list' ? PhotosListPayload :
  T extends 'photos.listing' ? PhotosListingPayload :
  T extends 'photos.pull' ? PhotosPullPayload :
  T extends 'photos.pullReady' ? PhotosPullReadyPayload :
  T extends 'status.query' ? Record<string, never> :
  T extends 'status.report' ? StatusReportPayload :
  T extends 'clip.push' ? ClipPushPayload :
  T extends 'notif.posted' ? NotifPostedPayload :
  T extends 'notif.removed' ? NotifRemovedPayload :
  T extends 'notif.reply' ? NotifReplyPayload :
  T extends 'sms.threads' ? Record<string, never> :
  T extends 'sms.threadList' ? SmsThreadListPayload :
  T extends 'sms.messages' ? SmsMessagesPayload :
  T extends 'sms.messageList' ? SmsMessageListPayload :
  T extends 'sms.send' ? SmsSendPayload :
  T extends 'sms.sent' ? SmsSentPayload :
  T extends 'sms.incoming' ? SmsIncomingPayload :
  T extends 'sms.contacts' ? SmsContactsPayload :
  T extends 'sms.contactList' ? SmsContactListPayload :
  T extends 'screen.start' ? ScreenStartPayload :
  T extends 'screen.ready' ? ScreenReadyPayload :
  T extends 'screen.stop' ? Record<string, never> :
  T extends 'screen.error' ? ScreenErrorPayload :
  T extends 'screen.audio' ? ScreenAudioPayload :
  T extends 'camera.start' ? CameraStartPayload :
  T extends 'camera.ready' ? CameraReadyPayload :
  T extends 'camera.stop' ? Record<string, never> :
  T extends 'camera.error' ? CameraErrorPayload :
  T extends 'camera.switch' ? CameraSwitchPayload :
  T extends 'input.tap' ? InputTapPayload :
  T extends 'input.longpress' ? InputLongPressPayload :
  T extends 'input.swipe' ? InputSwipePayload :
  T extends 'input.scroll' ? InputScrollPayload :
  T extends 'input.key' ? InputKeyPayload :
  T extends 'input.button' ? InputButtonPayload :
  T extends 'phone.ring' ? Record<string, never> :
  T extends 'phone.openUrl' ? {url: string} :
  T extends 'media.info' ? MediaInfoPayload :
  T extends 'media.control' ? MediaControlPayload :
  T extends 'trackpad.move' ? {dx: number; dy: number} :
  T extends 'trackpad.scroll' ? {dx: number; dy: number} :
  T extends 'trackpad.tap' | 'trackpad.rightTap' | 'trackpad.doubleTap' ? Record<string, never> :
  T extends 'trackpad.key' ? {text?: string; code?: 'backspace' | 'enter'} :
  never;

/** Fixed binary data-frame header size in bytes (see README §3). */
export const BINARY_HEADER_SIZE = 21 as const;
export const BINARY_VERSION = 0x01 as const;
