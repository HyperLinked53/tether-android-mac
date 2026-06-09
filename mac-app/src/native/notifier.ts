/**
 * Wrapper over the `TetherNotifier` native module (defined in macos AppDelegate.mm). Posts rich
 * macOS notifications (app icon image + smart-reply actions) and surfaces reply events.
 */
import {NativeEventEmitter, NativeModules} from 'react-native';

export interface NativeNotifyOptions {
  key: string;
  title: string;
  body: string;
  iconPng?: string; // base64 PNG
  canReply: boolean;
  suggestions: string[];
}

interface TetherNotifierModule {
  notify(opts: NativeNotifyOptions): Promise<string>;
  addListener(event: string): void;
  removeListeners(count: number): void;
}

const native = (NativeModules as {TetherNotifier?: TetherNotifierModule}).TetherNotifier;
const emitter = native ? new NativeEventEmitter(native as never) : null;

export const nativeNotificationsAvailable = !!native;

export function postNativeNotification(opts: NativeNotifyOptions): void {
  const p = native?.notify(opts);
  if (p && typeof p.then === 'function') {
    p.then(status => console.log('[notif-native]', status)).catch(e => console.log('[notif-native] error', e));
  }
}

/** Fires when the user replies (suggestion tap or typed text) on a macOS notification banner. */
export function onNativeNotificationReply(
  cb: (e: {key: string; text: string}) => void,
): () => void {
  const sub = emitter?.addListener('tetherNotificationReply', cb);
  return () => sub?.remove();
}
