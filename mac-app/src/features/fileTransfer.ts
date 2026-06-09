/**
 * File transfer, Mac side (protocol/README §4.1). The WebSocket only coordinates; bytes stream
 * natively (NSURLSession via RNFS) to/from the Android phone's LAN HTTP server — no base64, no JS
 * bridge per chunk, so it's fast. The Mac is always the HTTP client.
 *
 *  - Outgoing (Mac→Android): offer → await accept(httpPort) → RNFS.uploadFiles POST /upload/<id>.
 *  - Incoming (Android→Mac): offer(httpPort) → RNFS.downloadFile GET /download/<id> → complete.
 */
import RNFS from 'react-native-fs';
import {connection} from '../net/ConnectionManager';
import {Envelope, FileAcceptPayload, FileOfferPayload} from '../protocol/types';
import {uuidv4} from '../util/encoding';
import {PickedFile} from '../native/filePicker';

export const INBOX = `${RNFS.DocumentDirectoryPath}/TetherInbox`;
const PROGRESS_NOTIFY_BYTES = 2_000_000; // how often the receiver tells the sender its progress
const log = (...a: unknown[]) => console.log('[ft]', ...a);

export type Direction = 'incoming' | 'outgoing';
export type Status = 'active' | 'completed' | 'failed';

export interface Transfer {
  id: string;
  name: string;
  size: number;
  transferred: number;
  direction: Direction;
  status: Status;
  path?: string;
  error?: string;
}

type Listener = (transfers: Transfer[]) => void;

class FileTransferService {
  private transfers = new Map<string, Transfer>();
  private listeners = new Set<Listener>();
  private pendingAccept = new Map<string, (httpPort: number) => void>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => void this.onMessage(env));
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.list());
    return () => this.listeners.delete(listener);
  }

  list(): Transfer[] {
    return [...this.transfers.values()].sort((a, b) => b.id.localeCompare(a.id));
  }

  /** Mac → Android: offer the file, then stream it to the phone's /upload endpoint. */
  async sendFile(file: PickedFile): Promise<void> {
    const host = connection.currentHost();
    log('sendFile', file.name, file.size, 'host=', host, 'path=', file.path);
    if (!host) {
      log('sendFile aborted: no host (not connected)');
      return;
    }
    const transferId = uuidv4();
    this.upsert({id: transferId, name: file.name, size: file.size, transferred: 0, direction: 'outgoing', status: 'active'});

    const httpPort = await new Promise<number>(resolve => {
      this.pendingAccept.set(transferId, resolve);
      connection.send('file.offer', {transferId, name: file.name, size: file.size, mime: 'application/octet-stream'});
    });
    log('got accept, httpPort=', httpPort, '→ uploading');

    try {
      const {promise} = RNFS.uploadFiles({
        toUrl: `http://${host}:${httpPort}/upload/${transferId}`,
        method: 'POST',
        binaryStreamOnly: true, // send the raw file as the body (no multipart wrapping)
        files: [{name: 'file', filename: file.name, filepath: file.path, filetype: 'application/octet-stream'}],
        progress: p => this.patch(transferId, {transferred: p.totalBytesSent}),
      });
      const res = await promise;
      log('upload result statusCode=', res.statusCode, 'body=', res.body);
      if (res.statusCode >= 400) throw new Error(`HTTP ${res.statusCode}`);
      // Android confirms with file.complete, but mark done here too in case it races.
      this.patch(transferId, {transferred: file.size, status: 'completed'});
    } catch (e) {
      log('upload error', String(e));
      connection.send('file.error', {transferId, message: String(e)});
      this.patch(transferId, {status: 'failed', error: String(e)});
    }
  }

  private async onMessage(env: Envelope): Promise<void> {
    if (env.type.startsWith('file.')) log('recv', env.type, JSON.stringify(env.payload).slice(0, 200));
    switch (env.type) {
      case 'file.offer':
        await this.receive(env.payload as FileOfferPayload);
        break;
      case 'file.accept': {
        const p = env.payload as FileAcceptPayload;
        const resolve = this.pendingAccept.get(p.transferId);
        if (resolve) {
          this.pendingAccept.delete(p.transferId);
          resolve(p.httpPort ?? 5334);
        }
        break;
      }
      case 'file.complete': {
        const {transferId} = env.payload as {transferId: string};
        const t = this.transfers.get(transferId);
        if (t) this.patch(transferId, {transferred: t.size, status: 'completed'});
        break;
      }
      case 'file.error': {
        const {transferId, message} = env.payload as {transferId: string; message: string};
        this.patch(transferId, {status: 'failed', error: message});
        break;
      }
    }
  }

  /**
   * Download a file the user located by browsing the phone (see remoteBrowse). The transfer is
   * already armed on the phone (fs.pull → fs.pullReady gave us `url`); we just stream it to `dest`
   * and report it in the same transfers list. `file.complete` releases the phone-side registration.
   */
  async downloadUrl(opts: {transferId: string; url: string; name: string; size: number; dest: string}): Promise<void> {
    const {transferId, url, name, size, dest} = opts;
    log('downloadUrl', name, size, '→', dest, 'from', url);
    this.upsert({id: transferId, name, size, transferred: 0, direction: 'incoming', status: 'active', path: dest});
    try {
      const {promise} = RNFS.downloadFile({
        fromUrl: url,
        toFile: dest,
        progressInterval: 200,
        progress: p => this.patch(transferId, {transferred: p.bytesWritten}),
      });
      const res = await promise;
      log('downloadUrl result statusCode=', res.statusCode, 'bytes=', res.bytesWritten);
      if (res.statusCode >= 400) throw new Error(`HTTP ${res.statusCode}`);
      this.patch(transferId, {transferred: size, status: 'completed'});
      connection.send('file.complete', {transferId});
    } catch (e) {
      log('downloadUrl error', String(e));
      connection.send('file.error', {transferId, message: String(e)});
      this.patch(transferId, {status: 'failed', error: String(e)});
      throw e;
    }
  }

  /** Android → Mac: download the offered file from the phone's /download endpoint into the Inbox. */
  private async receive(offer: FileOfferPayload): Promise<void> {
    const host = connection.currentHost();
    if (!host) {
      log('receive aborted: no host');
      return;
    }
    const httpPort = offer.httpPort ?? 5334;
    await RNFS.mkdir(INBOX).catch(() => {});
    const dest = await this.uniqueDest(offer.name);
    log('downloading', offer.name, 'from', `http://${host}:${httpPort}/download/${offer.transferId}`, '→', dest);
    this.upsert({id: offer.transferId, name: offer.name, size: offer.size, transferred: 0, direction: 'incoming', status: 'active', path: dest});
    connection.send('file.accept', {transferId: offer.transferId});

    let lastNotified = 0;
    try {
      const {promise} = RNFS.downloadFile({
        fromUrl: `http://${host}:${httpPort}/download/${offer.transferId}`,
        toFile: dest,
        progressInterval: 200,
        progress: p => {
          this.patch(offer.transferId, {transferred: p.bytesWritten});
          if (p.bytesWritten - lastNotified >= PROGRESS_NOTIFY_BYTES) {
            lastNotified = p.bytesWritten;
            connection.send('file.progress', {transferId: offer.transferId, received: p.bytesWritten});
          }
        },
      });
      const res = await promise;
      log('download result statusCode=', res.statusCode, 'bytes=', res.bytesWritten);
      if (res.statusCode >= 400) throw new Error(`HTTP ${res.statusCode}`);
      this.patch(offer.transferId, {transferred: offer.size, status: 'completed'});
      connection.send('file.complete', {transferId: offer.transferId});
    } catch (e) {
      log('download error', String(e));
      connection.send('file.error', {transferId: offer.transferId, message: String(e)});
      this.patch(offer.transferId, {status: 'failed', error: String(e)});
    }
  }

  private async uniqueDest(name: string): Promise<string> {
    if (!(await RNFS.exists(`${INBOX}/${name}`))) return `${INBOX}/${name}`;
    const dot = name.lastIndexOf('.');
    const base = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : '';
    let i = 1;
    while (await RNFS.exists(`${INBOX}/${base} (${i})${ext}`)) i++;
    return `${INBOX}/${base} (${i})${ext}`;
  }

  private upsert(t: Transfer): void {
    this.transfers.set(t.id, t);
    this.notify();
  }

  private patch(id: string, partial: Partial<Transfer>): void {
    const cur = this.transfers.get(id);
    if (!cur) return;
    this.transfers.set(id, {...cur, ...partial});
    this.notify();
  }

  private notify(): void {
    const snapshot = this.list();
    this.listeners.forEach(l => l(snapshot));
  }
}

export const fileTransfer = new FileTransferService();
