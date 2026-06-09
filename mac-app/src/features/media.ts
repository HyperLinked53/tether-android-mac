/**
 * Mac-side media feature. Receives media.info pushes from Android and lets the UI send
 * media.control commands back. The phone broadcasts info whenever track/playback changes,
 * and pushes the current state immediately when a Mac connects.
 */
import {connection} from '../net/ConnectionManager';
import {MediaInfoPayload} from '../protocol/types';

type Listener = (info: MediaInfoPayload | null) => void;

class MediaFeature {
  private info: MediaInfoPayload | null = null;
  private listeners = new Set<Listener>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => {
      if (env.type === 'media.info') {
        const p = env.payload as MediaInfoPayload;
        // Treat empty title + not-playing as "nothing playing".
        this.info = (p.title || p.artist || p.isPlaying) ? p : null;
        this.notify();
      }
    });
    connection.on('state', s => {
      if (s === 'reconnecting' || s === 'idle') {
        this.info = null;
        this.notify();
      }
    });
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.info);
    return () => this.listeners.delete(listener);
  }

  getInfo(): MediaInfoPayload | null { return this.info; }

  play_pause() { connection.send('media.control', {action: 'play_pause'}); }
  next()       { connection.send('media.control', {action: 'next'}); }
  previous()   { connection.send('media.control', {action: 'previous'}); }

  private notify() { this.listeners.forEach(l => l(this.info)); }
}

export const media = new MediaFeature();
