/**
 * Phone-as-webcam, Mac side. The WebSocket only coordinates: we ask the phone to start camera
 * capture (`camera.start`), it opens the camera and replies `camera.ready {port, width, height}`;
 * the native `TetherCamera` window then connects to `host:port` and decodes the H.264 stream.
 * Bytes never touch JS. Works over both Wi-Fi and USB tethering (both are just IP connections
 * from the Mac's perspective).
 */
import RNFS from 'react-native-fs';
import {connection} from '../net/ConnectionManager';
import {
  CameraFacing,
  CameraReadyPayload,
  CameraErrorPayload,
  Envelope,
} from '../protocol/types';
import {openCamera, closeCamera, onCameraWindowClosed} from '../native/cameraWindow';
import {openMic, closeMic} from '../native/micDevice';

// IPC file for the virtual camera CMIO plugin.
// RNFS.LibraryDirectoryPath = ~/Library on macOS (sandbox disabled).
const TETHER_SUPPORT = `${RNFS.LibraryDirectoryPath}/Application Support/Tether`;
const SOURCE_FILE = `${TETHER_SUPPORT}/camera-source.json`;

async function writeSource(host: string, port: number): Promise<void> {
  try {
    await RNFS.mkdir(TETHER_SUPPORT);
    await RNFS.writeFile(SOURCE_FILE, JSON.stringify({host, port}), 'utf8');
  } catch {}
}

async function clearSource(): Promise<void> {
  try { await RNFS.unlink(SOURCE_FILE); } catch {}
}

export type CameraStatus = 'idle' | 'requesting' | 'live' | 'error';

export interface CameraState {
  status: CameraStatus;
  facing: CameraFacing;
  maxWidth: number;
  host?: string;
  port?: number;
  width?: number;
  height?: number;
  micPort?: number;
  micEnabled: boolean;
  error?: string;
}

type Listener = (state: CameraState) => void;

/** Maps a maxWidth tier to a sensible bitrate. Must match bitrateForWidth() in CameraHandler.kt. */
function bitrateForWidth(maxWidth: number): number {
  if (maxWidth >= 1920) return 8_000_000;
  if (maxWidth >= 1280) return 4_000_000;
  return 2_000_000;
}

class CameraWebcamService {
  private state: CameraState = {status: 'idle', facing: 'front', maxWidth: 1280, micEnabled: false};
  private listeners = new Set<Listener>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => this.onMessage(env));
    connection.on('state', s => {
      if (s !== 'connected' && this.state.status !== 'idle') {
        closeCamera();
        closeMic();
        void clearSource();
        this.set({status: 'idle'});
      }
    });
    onCameraWindowClosed(() => {
      if (this.state.status === 'live') {
        connection.send('camera.stop', {});
        closeMic();
        void clearSource();
        this.set({status: 'idle'});
      }
    });
  }

  /** Start streaming the phone's camera. */
  requestStart(facing: CameraFacing = this.state.facing): void {
    if (!connection.currentHost()) return;
    const {maxWidth} = this.state;
    this.set({status: 'requesting', facing});
    connection.send('camera.start', {facing, maxWidth, bitrate: bitrateForWidth(maxWidth)});
  }

  /** Change resolution while idle or live. If live, restarts the camera stream immediately. */
  setResolution(maxWidth: number): void {
    if (this.state.maxWidth === maxWidth) return;
    this.set({maxWidth});
    if (this.state.status === 'live') {
      // camera.switch restarts capture with the new resolution on Android side
      connection.send('camera.switch', {
        facing: this.state.facing,
        maxWidth,
        bitrate: bitrateForWidth(maxWidth),
      });
    }
  }

  stop(): void {
    if (this.state.status === 'idle') return;
    closeCamera();
    closeMic();
    void clearSource();
    connection.send('camera.stop', {});
    this.set({status: 'idle'});
  }

  /** Toggle phone microphone on/off. Takes effect immediately if camera is live. */
  toggleMic(): void {
    const enabled = !this.state.micEnabled;
    this.set({micEnabled: enabled});
    const {status, host, micPort} = this.state;
    if (status === 'live' && host && micPort) {
      if (enabled) openMic(host, micPort);
      else closeMic();
    }
  }

  /** Switch front/back camera while streaming. */
  switchCamera(facing: CameraFacing): void {
    if (this.state.facing === facing) return;
    this.set({facing});
    if (this.state.status === 'live') {
      const {maxWidth} = this.state;
      connection.send('camera.switch', {facing, maxWidth, bitrate: bitrateForWidth(maxWidth)});
    } else {
      this.requestStart(facing);
    }
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  getState(): CameraState { return this.state; }

  private onMessage(env: Envelope): void {
    switch (env.type) {
      case 'camera.ready': {
        const p = env.payload as CameraReadyPayload;
        const host = connection.currentHost() ?? undefined;
        this.set({status: 'live', host, port: p.port, width: p.width, height: p.height, micPort: p.micPort});
        if (host) {
          openCamera(host, p.port, p.width, p.height);
          void writeSource(host, p.port);
          if (p.micPort && this.state.micEnabled) openMic(host, p.micPort);
        }
        break;
      }
      case 'camera.error': {
        const p = env.payload as CameraErrorPayload;
        closeCamera();
        closeMic();
        void clearSource();
        this.set({status: 'error', error: p.message});
        break;
      }
      case 'camera.stop':
        if (this.state.status !== 'idle') {
          closeCamera(); closeMic(); void clearSource(); this.set({status: 'idle'});
        }
        break;
    }
  }

  private set(patch: Partial<CameraState>): void {
    this.state = {...this.state, ...patch};
    this.listeners.forEach(l => l(this.state));
  }
}

export const cameraWebcam = new CameraWebcamService();
