/**
 * Two-way clipboard sync (protocol `clip.push`). The Mac has no clipboard-change notification API, so
 * we poll the pasteboard; when it changes we push the text to the phone, and an inbound `clip.push`
 * writes the Mac clipboard. A `lastValue` guard (covering both sent and received text) prevents the
 * set→change→resend echo loop with the phone. Text only.
 */
import Clipboard from '@react-native-clipboard/clipboard';
import {connection} from '../net/ConnectionManager';
import {ClipPushPayload, Envelope} from '../protocol/types';
import {getItem, setItem} from '../util/store';

const POLL_MS = 800;
const ENABLED_KEY = 'clipboardSyncEnabled';

type Listener = (enabled: boolean) => void;

class ClipboardSyncService {
  private lastValue: string | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private wired = false;
  private enabled = true; // gates both directions; disabling it turns the whole feature off
  private listeners = new Set<Listener>();

  wire(): void {
    if (this.wired) return;
    this.wired = true;

    void getItem<boolean>(ENABLED_KEY).then(v => {
      if (v !== undefined) {
        this.enabled = v;
        this.notify();
      }
    });

    connection.on('message', env => this.onMessage(env));

    // Seed lastValue with the current clipboard so we don't push a stale value on first poll.
    void Clipboard.getString().then(v => {
      if (this.lastValue === null) this.lastValue = v ?? '';
    });

    this.timer = setInterval(() => void this.poll(), POLL_MS);
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  setEnabled(enabled: boolean): void {
    console.log('[clip] setEnabled', enabled);
    this.enabled = enabled;
    void setItem(ENABLED_KEY, enabled);
    this.notify();
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.enabled);
    return () => this.listeners.delete(listener);
  }

  private notify(): void {
    this.listeners.forEach(l => l(this.enabled));
  }

  private onMessage(env: Envelope): void {
    if (env.type !== 'clip.push') return;
    if (!this.enabled) { console.log('[clip] recv ignored (disabled)'); return; }
    const {text} = env.payload as ClipPushPayload;
    if (text === this.lastValue) return;
    this.lastValue = text; // remember before writing so our own poll doesn't echo it back
    console.log('[clip] recv apply', JSON.stringify(text).slice(0, 30));
    Clipboard.setString(text);
  }

  private async poll(): Promise<void> {
    if (!this.enabled || connection.getState() !== 'connected') return;
    const cur = await Clipboard.getString();
    if (!cur || cur === this.lastValue) return;
    this.lastValue = cur;
    console.log('[clip] push', JSON.stringify(cur).slice(0, 30));
    connection.send('clip.push', {text: cur});
  }
}

export const clipboardSync = new ClipboardSyncService();
