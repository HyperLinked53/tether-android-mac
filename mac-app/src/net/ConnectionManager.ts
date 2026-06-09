/**
 * Owns the single WebSocket link to a paired phone: connect → hello → pair/auth → heartbeat,
 * with auto-reconnect. Features subscribe to decoded control messages and binary frames.
 */
import {
  Envelope,
  HelloPayload,
  PairRequiredPayload,
  PairOkPayload,
  ErrorPayload,
  DeviceInfo,
  MessageType,
  PayloadFor,
  PROTOCOL_VERSION,
} from '../protocol/types';
import {DiscoveredDevice} from './Discovery';
import {computeProof} from './pairing';
import {getToken, setToken, selfDeviceId} from '../util/store';
import {uuidv4, base64Encode, base64Decode, utf8Bytes, utf8ToString} from '../util/encoding';
import {hkdf, aesGcmEncrypt, aesGcmDecrypt, randomBytes} from '../util/crypto';

export type ConnectionState =
  | 'idle'
  | 'connecting'
  | 'pairing'
  | 'connected'
  | 'reconnecting'
  | 'error';

interface Events {
  state: (s: ConnectionState, detail?: string) => void;
  peer: (device: DeviceInfo) => void;
  pairingRequired: () => void;
  pairingFailed: (message: string) => void;
  message: (env: Envelope) => void;
}

const HEARTBEAT_MS = 15_000;
const RECONNECT_MS = 1_200;
const CONNECT_TIMEOUT_MS = 2_500; // give up on a socket that won't open (e.g. stale cached IP)

export class ConnectionManager {
  private ws: WebSocket | null = null;
  private device: DiscoveredDevice | null = null;
  private state: ConnectionState = 'idle';
  private selfId = '';
  private selfName = 'My Mac';

  private pairSalt: string | null = null;
  private pendingSecret: string | null = null; // pairing secret, kept to decrypt the pair.ok token
  private storedToken: string | null = null; // token for this device, if previously paired
  private clientNonce: Uint8Array | null = null; // this connection's nonce (for session key)
  private sessionKey: Uint8Array | null = null;
  private encrypting = false; // once true, frames are AES-GCM (binary); handshake stays plaintext
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private connectTimer: ReturnType<typeof setTimeout> | null = null;
  private userClosed = false;

  private listeners: {[K in keyof Events]: Set<Events[K]>} = {
    state: new Set(), peer: new Set(), pairingRequired: new Set(), pairingFailed: new Set(),
    message: new Set(),
  };

  async init(name?: string): Promise<void> {
    this.selfId = await selfDeviceId();
    if (name) this.selfName = name;
  }

  on<K extends keyof Events>(event: K, cb: Events[K]): () => void {
    this.listeners[event].add(cb);
    return () => this.listeners[event].delete(cb);
  }

  getState(): ConnectionState {
    return this.state;
  }

  currentDeviceId(): string | null {
    return this.device?.id ?? null;
  }

  currentHost(): string | null {
    return this.device?.host ?? null;
  }

  currentDevice(): DiscoveredDevice | null {
    return this.device;
  }

  connect(device: DiscoveredDevice): void {
    this.userClosed = false;
    this.device = device;
    this.openSocket();
  }

  disconnect(): void {
    this.userClosed = true;
    this.clearTimers();
    this.ws?.close();
    this.ws = null;
    this.setState('idle');
  }

  /** Send a typed control message. Encrypted (binary) once the session key is established. */
  send<T extends MessageType>(type: T, payload: PayloadFor<T>, replyTo?: string): string {
    const env: Envelope<T> = {v: PROTOCOL_VERSION, id: uuidv4(), type, replyTo, payload};
    const json = JSON.stringify(env);
    if (this.encrypting && this.sessionKey) {
      this.ws?.send(aesGcmEncrypt(this.sessionKey, utf8Bytes(json)).buffer as ArrayBuffer);
    } else {
      this.ws?.send(json);
    }
    return env.id;
  }

  /** Called by the UI once the user supplies the pairing code/QR from the phone. */
  submitPairingSecret(secret: string): void {
    if (!this.pairSalt) return;
    this.pendingSecret = secret; // needed to decrypt the token in pair.ok
    const proof = computeProof(secret, this.pairSalt, this.selfId);
    this.send('pair.request', {proof});
  }

  // ---- internals ---------------------------------------------------------

  private openSocket(): void {
    if (!this.device) return;
    this.clearTimers();
    this.teardownSocket(); // drop any prior socket so its stale callbacks can't trigger reconnects
    // Fresh crypto state per connection (a new session key is derived each handshake).
    this.sessionKey = null;
    this.encrypting = false;
    this.clientNonce = null;
    this.storedToken = null;
    this.setState(this.state === 'reconnecting' ? 'reconnecting' : 'connecting');

    const ws = new WebSocket(`ws://${this.device.host}:${this.device.port}`);
    // RN's WebSocket supports binaryType at runtime but omits it from its type defs.
    (ws as unknown as {binaryType: string}).binaryType = 'arraybuffer';
    this.ws = ws;

    ws.onopen = () => this.onOpen();
    ws.onmessage = e => this.onWsMessage(e.data);
    ws.onerror = () => this.setState('error', 'socket error');
    ws.onclose = () => this.onClose();

    // If the socket doesn't open promptly (stale/unreachable address), stop waiting and let the
    // reconnect loop / discovery pick a fresh address — avoids a long OS-level connect hang.
    this.connectTimer = setTimeout(() => {
      if (this.ws === ws && this.state !== 'connected' && this.state !== 'pairing') {
        this.onClose();
      }
    }, CONNECT_TIMEOUT_MS);
  }

  private async onOpen(): Promise<void> {
    if (this.connectTimer) clearTimeout(this.connectTimer);
    // Guard against connecting before init() resolved, so hello carries a real device id.
    if (!this.selfId) this.selfId = await selfDeviceId();
    this.storedToken = (this.device ? await getToken(this.device.id) : undefined) ?? null;
    this.clientNonce = randomBytes(16);
    // hello carries a nonce (for the session key), never the token.
    const hello: HelloPayload = {
      device: {id: this.selfId, name: this.selfName, platform: 'macos', appVersion: '0.1.0'},
      nonce: base64Encode(this.clientNonce),
    };
    this.send('hello', hello);
    this.startHeartbeat();
  }

  private onWsMessage(data: string | ArrayBuffer): void {
    let env: Envelope;
    if (typeof data !== 'string') {
      // Binary = an AES-GCM encrypted envelope (post-handshake). Ignore until we have the key.
      if (!this.sessionKey) return;
      try {
        env = JSON.parse(utf8ToString(aesGcmDecrypt(this.sessionKey, new Uint8Array(data))));
      } catch {
        return;
      }
    } else {
      try {
        env = JSON.parse(data); // plaintext handshake frame (hello / pair.* / error)
      } catch {
        return;
      }
    }
    this.handleControl(env);
    this.emit('message', env);
  }

  /** Derive the AES-GCM session key from the token + both nonces and switch to encrypted frames. */
  private deriveSession(token: string, serverNonceB64: string): void {
    const serverNonce = base64Decode(serverNonceB64);
    const salt = new Uint8Array((this.clientNonce?.length ?? 0) + serverNonce.length);
    if (this.clientNonce) salt.set(this.clientNonce);
    salt.set(serverNonce, this.clientNonce?.length ?? 0);
    this.sessionKey = hkdf(base64Decode(token), salt, 'conduit-session-v1');
    this.encrypting = true;
  }

  private handleControl(env: Envelope): void {
    switch (env.type) {
      case 'hello': {
        const h = env.payload as HelloPayload;
        this.emit('peer', h.device);
        // Already-paired: the server's hello reply carries its nonce. With our stored token we derive
        // the session key and go live immediately (no pair.ok round trip). If the token was rejected,
        // a pair.required follows instead.
        if (this.storedToken && h.nonce) {
          this.deriveSession(this.storedToken, h.nonce);
          this.setState('connected');
        }
        break;
      }
      case 'pair.required':
        this.pairSalt = (env.payload as PairRequiredPayload).salt;
        this.setState('pairing');
        this.emit('pairingRequired');
        break;
      case 'pair.ok': {
        const {tokenEnc, nonce} = env.payload as PairOkPayload;
        // Decrypt the token with a key derived from the pairing secret, persist it, then derive the
        // session key from it.
        if (!this.pendingSecret || !this.pairSalt) break;
        const pairKey = hkdf(utf8Bytes(this.pendingSecret), base64Decode(this.pairSalt), 'conduit-pair-v1');
        let token: string;
        try {
          token = utf8ToString(aesGcmDecrypt(pairKey, base64Decode(tokenEnc)));
        } catch {
          this.emit('pairingFailed', 'could not establish a secure session');
          this.setState('error', 'pairing decryption failed');
          break;
        }
        this.storedToken = token;
        if (this.device) void setToken(this.device.id, token);
        this.deriveSession(token, nonce);
        this.pairSalt = null;
        this.pendingSecret = null;
        this.setState('connected');
        break;
      }
      case 'ping':
        this.send('pong', {} as PayloadFor<'pong'>, env.id);
        break;
      case 'error': {
        const err = env.payload as ErrorPayload;
        if (err.code === 'pair_failed') this.emit('pairingFailed', err.message);
        this.setState('error', err.message);
        break;
      }
      default:
        // If we receive any feature message, the session is authenticated and live.
        if (this.state !== 'connected' && this.state !== 'pairing') this.setState('connected');
    }
  }

  private onClose(): void {
    this.clearTimers();
    this.ws = null;
    if (this.userClosed) return;
    this.setState('reconnecting');
    this.reconnectTimer = setTimeout(() => this.openSocket(), RECONNECT_MS);
  }

  private startHeartbeat(): void {
    this.heartbeat = setInterval(() => this.send('ping', {} as PayloadFor<'ping'>), HEARTBEAT_MS);
  }

  private clearTimers(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.connectTimer) clearTimeout(this.connectTimer);
    this.heartbeat = null;
    this.reconnectTimer = null;
    this.connectTimer = null;
  }

  private teardownSocket(): void {
    const old = this.ws;
    if (!old) return;
    this.ws = null;
    old.onopen = null;
    old.onmessage = null;
    old.onerror = null;
    old.onclose = null;
    try {
      old.close();
    } catch {
      /* already closed */
    }
  }

  private setState(s: ConnectionState, detail?: string): void {
    this.state = s;
    this.emit('state', s, detail);
  }

  private emit<K extends keyof Events>(event: K, ...args: Parameters<Events[K]>): void {
    this.listeners[event].forEach(cb => (cb as (...a: Parameters<Events[K]>) => void)(...args));
  }
}

/** Singleton shared across screens. */
export const connection = new ConnectionManager();
