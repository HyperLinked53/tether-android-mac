/**
 * Screen mirroring, Mac side (protocol/README §4.6). The WebSocket only coordinates: we ask the
 * phone to start (`screen.start`), it grants capture on-device and replies `screen.ready` with the
 * video socket port + dimensions; the native `TetherMirror` view then connects to `host:port` and
 * decodes the H.264 stream. Bytes never touch JS.
 */
import {connection} from '../net/ConnectionManager';
import {AudioTarget, Envelope, ScreenReadyPayload, ScreenErrorPayload} from '../protocol/types';
import {
  openMirror, closeMirror, onMirrorClosed, onMirrorInput, MirrorInputEvent,
  startMirrorAudio, stopMirrorAudio,
} from '../native/mirrorWindow';
import {InputButtonPayload} from '../protocol/types';

export type MirrorStatus = 'idle' | 'requesting' | 'live' | 'error';

export interface MirrorState {
  status: MirrorStatus;
  host?: string;
  port?: number;
  width?: number;
  height?: number;
  audio: AudioTarget; // where the phone's audio plays: 'phone' (default) or 'mac'
  audioPort?: number;
  error?: string;
}

type Listener = (state: MirrorState) => void;

class ScreenMirrorService {
  private state: MirrorState = {status: 'idle', audio: 'phone'};
  private listeners = new Set<Listener>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => this.onMessage(env));
    connection.on('state', s => {
      // Tear down the window if the link drops.
      if (s !== 'connected' && this.state.status !== 'idle') {
        stopMirrorAudio();
        closeMirror();
        this.set({status: 'idle'});
      }
    });
    // If the user closes the mirror window from its title bar, stop capture on the phone too.
    onMirrorClosed(() => this.stop());
    // Mouse/keyboard captured on the mirror window → drive the phone over the WebSocket.
    onMirrorInput(e => this.forwardInput(e));
  }

  /** Tap a navigation button (Back/Home/Recents) — also usable from the UI. */
  sendButton(name: InputButtonPayload['name']): void {
    if (this.state.status !== 'live') return;
    connection.send('input.button', {name});
  }

  private forwardInput(e: MirrorInputEvent): void {
    if (this.state.status !== 'live') return;
    switch (e.type) {
      case 'tap': connection.send('input.tap', {x: e.x!, y: e.y!}); break;
      case 'longpress': connection.send('input.longpress', {x: e.x!, y: e.y!}); break;
      case 'swipe':
        connection.send('input.swipe', {x1: e.x1!, y1: e.y1!, x2: e.x2!, y2: e.y2!, ms: e.ms ?? 200});
        break;
      case 'scroll': connection.send('input.scroll', {x: e.x!, y: e.y!, dx: e.dx ?? 0, dy: e.dy ?? 0}); break;
      case 'key': connection.send('input.key', {text: e.text, code: e.code as never}); break;
      case 'button': connection.send('input.button', {name: e.name as InputButtonPayload['name']}); break;
    }
  }

  /** Choose where the phone's audio plays. Takes effect live if a session is running. */
  setAudioTarget(target: AudioTarget): void {
    if (this.state.audio === target) return;
    this.set({audio: target});
    if (this.state.status === 'live') {
      connection.send('screen.audio', {target});
      this.applyAudio();
    }
  }

  private applyAudio(): void {
    const {audio, host, audioPort} = this.state;
    if (audio === 'mac' && host && audioPort) startMirrorAudio(host, audioPort);
    else stopMirrorAudio();
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  getState(): MirrorState {
    return this.state;
  }

  /** Ask the phone to start mirroring (it prompts for capture consent on-device). */
  requestStart(): void {
    if (!connection.currentHost()) return;
    this.set({status: 'requesting'});
    connection.send('screen.start', {audio: this.state.audio});
  }

  stop(): void {
    if (this.state.status === 'idle') return;
    stopMirrorAudio();
    closeMirror();
    connection.send('screen.stop', {});
    this.set({status: 'idle'});
  }

  private onMessage(env: Envelope): void {
    switch (env.type) {
      case 'screen.ready': {
        const p = env.payload as ScreenReadyPayload;
        const host = connection.currentHost() ?? undefined;
        this.set({status: 'live', host, port: p.port, width: p.width, height: p.height, audioPort: p.audioPort});
        if (host) {
          openMirror(host, p.port, p.width, p.height); // pop up the separate window
          this.applyAudio(); // start audio playback if the user chose 'mac'
        }
        break;
      }
      case 'screen.error': {
        const p = env.payload as ScreenErrorPayload;
        stopMirrorAudio();
        closeMirror();
        this.set({status: 'error', error: p.message});
        break;
      }
      case 'screen.stop':
        if (this.state.status !== 'idle') {
          stopMirrorAudio();
          closeMirror();
          this.set({status: 'idle'});
        }
        break;
    }
  }

  private set(patch: Partial<MirrorState>): void {
    this.state = {...this.state, ...patch};
    this.listeners.forEach(l => l(this.state));
  }
}

export const screenMirror = new ScreenMirrorService();
