/**
 * Wrapper over the native `TetherMirror` module (defined in macos AppDelegate.mm), which shows the
 * live phone screen in its own movable/resizable macOS window. The H.264 bytes are read and decoded
 * natively — they never cross the JS bridge. `open`/`close` manage the window; `tetherMirrorClosed`
 * fires when the user closes the window from its title bar.
 */
import {NativeModules, NativeEventEmitter} from 'react-native';

interface TetherMirrorModule {
  open(host: string, port: number, width: number, height: number): void;
  close(): void;
  startAudio(host: string, port: number): void;
  stopAudio(): void;
}

const native = (NativeModules as {TetherMirror?: TetherMirrorModule}).TetherMirror;

export const mirrorWindowAvailable = !!native;

export function openMirror(host: string, port: number, width: number, height: number): void {
  native?.open(host, port, width, height);
}

export function closeMirror(): void {
  native?.close();
}

/** Start playing the phone's audio on the Mac (connects to the phone's audio socket). */
export function startMirrorAudio(host: string, port: number): void {
  native?.startAudio(host, port);
}

export function stopMirrorAudio(): void {
  native?.stopAudio();
}

/** Subscribe to the user closing the mirror window; returns an unsubscribe fn. */
export function onMirrorClosed(cb: () => void): () => void {
  if (!native) return () => {};
  const emitter = new NativeEventEmitter(native as never);
  const sub = emitter.addListener('tetherMirrorClosed', cb);
  return () => sub.remove();
}

/** A mouse/keyboard event captured on the mirror window. */
export interface MirrorInputEvent {
  type: 'tap' | 'longpress' | 'swipe' | 'scroll' | 'key' | 'button';
  x?: number; y?: number;
  x1?: number; y1?: number; x2?: number; y2?: number; ms?: number;
  dx?: number; dy?: number;
  text?: string; code?: string; name?: string;
}

/** Subscribe to mouse/keyboard events on the mirror window; returns an unsubscribe fn. */
export function onMirrorInput(cb: (e: MirrorInputEvent) => void): () => void {
  if (!native) return () => {};
  const emitter = new NativeEventEmitter(native as never);
  const sub = emitter.addListener('tetherMirrorInput', cb);
  return () => sub.remove();
}
