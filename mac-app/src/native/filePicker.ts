/**
 * Wrapper over the native `TetherFilePicker` module (defined in macos AppDelegate.mm), which opens
 * an NSOpenPanel and returns the chosen file. Replaces react-native-document-picker (iOS-only).
 */
import {NativeModules} from 'react-native';

export interface PickedFile {
  path: string;
  name: string;
  size: number;
}

interface TetherFilePickerModule {
  pick(): Promise<PickedFile | null>;
  save(suggestedName: string): Promise<string | null>;
}

const native = (NativeModules as {TetherFilePicker?: TetherFilePickerModule}).TetherFilePicker;

export const filePickerAvailable = !!native;

/** Open the native picker; resolves to the chosen file, or null if cancelled/unavailable. */
export async function pickFile(): Promise<PickedFile | null> {
  if (!native) return null;
  return native.pick();
}

/** Open a native Save panel (pre-filled with `suggestedName`); resolves the chosen path or null. */
export async function savePanel(suggestedName: string): Promise<string | null> {
  if (!native?.save) return null;
  return native.save(suggestedName);
}
