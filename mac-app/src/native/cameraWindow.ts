import {NativeModules, NativeEventEmitter} from 'react-native';

const {TetherCamera} = NativeModules;

export const cameraWindowAvailable = !!TetherCamera;

const emitter = TetherCamera ? new NativeEventEmitter(TetherCamera) : null;

export function openCamera(host: string, port: number, width: number, height: number): void {
  TetherCamera?.open(host, port, width, height);
}

export function closeCamera(): void {
  TetherCamera?.close();
}

/** Returns a cleanup function. Listener fires when the user closes the camera window manually. */
export function onCameraWindowClosed(cb: () => void): () => void {
  if (!emitter) return () => {};
  const sub = emitter.addListener('tetherCameraWindowClosed', cb);
  return () => sub.remove();
}
