import {NativeModules} from 'react-native';

const {TetherMic} = NativeModules;

/** Connect to the phone's mic TCP stream and feed it into the shared memory ring for the HAL plugin. */
export function openMic(host: string, port: number): void {
  TetherMic?.open(host, port);
}

/** Disconnect from the mic stream. */
export function closeMic(): void {
  TetherMic?.close();
}

export const micDeviceAvailable = Boolean(TetherMic);
