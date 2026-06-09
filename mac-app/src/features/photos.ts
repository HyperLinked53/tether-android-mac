/**
 * Phone photo gallery, Mac side (protocol `photos.*`). Lists the phone's photos (thumbnails served
 * over the file HTTP server, loaded natively by <Image>); `pull(id)` arms the full image for
 * download / drag-out, reusing `fileTransfer.downloadUrl`. Thumbnail URLs arrive with a `%HOST%`
 * placeholder rewritten to the connected host.
 */
import {connection} from '../net/ConnectionManager';
import {Envelope, PhotoItem, PhotosListingPayload, PhotosPullReadyPayload} from '../protocol/types';

export interface PulledPhoto {
  transferId: string;
  url: string;
  name: string;
  size: number;
}

type Listener = (photos: PhotoItem[]) => void;

class PhotosService {
  private photos: PhotoItem[] = [];
  private listeners = new Set<Listener>();
  private pending = new Map<number, (p: PulledPhoto) => void>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => this.onMessage(env));
    connection.on('state', s => {
      if (s === 'connected') this.load();
      else if (this.photos.length) this.set([]);
    });
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.photos);
    return () => this.listeners.delete(listener);
  }

  load(limit = 300): void {
    if (connection.currentHost()) connection.send('photos.list', {limit});
  }

  /** Arm the full image for transfer; resolves with a GET-able URL. */
  pull(id: number): Promise<PulledPhoto> {
    const host = connection.currentHost();
    if (!host) return Promise.reject(new Error('not connected'));
    return new Promise<PulledPhoto>((resolve, reject) => {
      this.pending.set(id, resolve);
      connection.send('photos.pull', {id});
      setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error('photo pull timed out'));
      }, 10_000);
    });
  }

  private onMessage(env: Envelope): void {
    if (env.type === 'photos.listing') {
      const host = connection.currentHost();
      const photos = (env.payload as PhotosListingPayload).photos.map(p =>
        host ? {...p, thumbUrl: p.thumbUrl.replace('%HOST%', host)} : p,
      );
      this.set(photos);
    } else if (env.type === 'photos.pullReady') {
      const p = env.payload as PhotosPullReadyPayload;
      const host = connection.currentHost();
      const resolve = this.pending.get(p.id);
      if (resolve && host) {
        this.pending.delete(p.id);
        resolve({transferId: p.transferId, url: `http://${host}:${p.httpPort}/download/${p.transferId}`, name: p.name, size: p.size});
      }
    }
  }

  private set(photos: PhotoItem[]): void {
    this.photos = photos;
    this.listeners.forEach(l => l(photos));
  }
}

export const photos = new PhotosService();
