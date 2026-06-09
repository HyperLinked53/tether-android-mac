/**
 * Mirrored Android notifications. Subscribes once to the connection (app-wide, not per-screen) so
 * incoming notifications are (a) posted as rich macOS banners — with the source app's icon and,
 * for messaging apps, smart-reply actions — and (b) buffered for the Notifications tab. Replies
 * (from a banner action or the in-app UI) are sent back to the phone as `notif.reply`.
 */
import {connection} from '../net/ConnectionManager';
import {NotifPostedPayload} from '../protocol/types';
import {onNativeNotificationReply, postNativeNotification} from '../native/notifier';

type Listener = (items: NotifPostedPayload[]) => void;

class NotificationService {
  private items: NotifPostedPayload[] = [];
  private listeners = new Set<Listener>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;

    connection.on('message', env => {
      if (env.type === 'notif.posted') {
        const n = env.payload as NotifPostedPayload;
        console.log(
          `[notif] from ${n.appName || n.app} · icon=${n.iconPng ? n.iconPng.length + 'B' : 'NONE'} · canReply=${n.canReply} · suggestions=${n.suggestions?.length ?? 0}`,
        );
        this.items = [n, ...this.items.filter(p => p.key !== n.key)].slice(0, 100);
        postNativeNotification({
          key: n.key,
          title: n.title || n.appName,
          body: n.text,
          iconPng: n.iconPng,
          canReply: n.canReply,
          suggestions: n.suggestions ?? [],
        });
        this.emit();
      } else if (env.type === 'notif.removed') {
        const {key} = env.payload as {key: string};
        this.items = this.items.filter(p => p.key !== key);
        this.emit();
      }
    });

    // Replies typed/tapped on a native macOS banner → send back to the phone.
    onNativeNotificationReply(({key, text}) => this.sendReply(key, text));
  }

  /** Send a reply to a mirrored notification (used by banner actions and the in-app UI). */
  sendReply(key: string, text: string): void {
    if (!text.trim()) return;
    connection.send('notif.reply', {key, text});
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.items);
    return () => this.listeners.delete(listener);
  }

  private emit(): void {
    const snapshot = [...this.items];
    this.listeners.forEach(l => l(snapshot));
  }
}

export const notifications = new NotificationService();
