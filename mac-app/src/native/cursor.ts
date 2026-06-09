/**
 * Wrapper over the native `TetherCursor` module (defined in macos AppDelegate.mm).
 * Translates trackpad.* WebSocket messages into CoreGraphics HID events on the Mac.
 * Requires the app to be trusted in System Preferences → Privacy & Security → Accessibility.
 */
import {NativeModules} from 'react-native';

interface CursorModule {
  move(dx: number, dy: number): void;
  scroll(dx: number, dy: number): void;
  click(): void;
  rightClick(): void;
  doubleClick(): void;
  typeText(text: string): void;
  pressKey(keyCode: number): void;
}

const {TetherCursor} = NativeModules;

export const cursor: CursorModule | null = TetherCursor ?? null;
