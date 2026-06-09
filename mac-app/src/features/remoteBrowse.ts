/**
 * Browse the phone's file tree from the Mac (protocol/README §4.1, `fs.*`). Listing is metadata
 * over the WebSocket; the actual bytes reuse the HTTP file path: `pull()` arms a download on the
 * phone (fs.pull → fs.pullReady) and hands back a ready-to-GET URL that the drag source or the
 * Download/Save buttons stream via `fileTransfer.downloadUrl`.
 */
import {connection} from '../net/ConnectionManager';
import {
  Envelope,
  FsEntry,
  FsListingPayload,
  FsPullReadyPayload,
  FsErrorPayload,
} from '../protocol/types';

export interface BrowseState {
  path: string; // absolute path on the phone of the folder being shown
  entries: FsEntry[];
  loading: boolean;
  error?: string; // e.g. 'permission' when All-files-access isn't granted on the phone
}

export interface PulledFile {
  transferId: string;
  url: string; // http://host:port/download/<transferId>
  name: string;
  size: number;
}

type Listener = (state: BrowseState) => void;

const log = (...a: unknown[]) => console.log('[fs]', ...a);

class RemoteBrowseService {
  private state: BrowseState = {path: '', entries: [], loading: false};
  private stack: string[] = ['']; // navigation history of folder paths ('' = root)
  private listeners = new Set<Listener>();
  // fs.pullReady carries no path, but the WebSocket is ordered, so correlate pulls FIFO.
  private pendingPulls: Array<{resolve: (f: PulledFile) => void; reject: (e: Error) => void}> = [];
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => this.onMessage(env));
    // Refresh the root whenever a phone connects (and clear stale state on disconnect).
    connection.on('state', s => {
      if (s === 'connected') this.start();
      else if (s === 'reconnecting' || s === 'idle') this.setState({path: '', entries: [], loading: false});
    });
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  /** Reset to the storage root and list it. */
  start(): void {
    this.stack = [''];
    this.requestList('');
  }

  /** Enter a directory entry. */
  open(entry: FsEntry): void {
    if (!entry.isDir) return;
    this.stack.push(entry.path);
    this.requestList(entry.path);
  }

  /** Go up one level (no-op at the root). */
  up(): void {
    if (this.stack.length <= 1) return;
    this.stack.pop();
    this.requestList(this.stack[this.stack.length - 1]);
  }

  canGoUp(): boolean {
    return this.stack.length > 1;
  }

  refresh(): void {
    this.requestList(this.stack[this.stack.length - 1]);
  }

  /** Arm a download for a browsed file; resolves with a GET-able URL once the phone confirms. */
  pull(path: string): Promise<PulledFile> {
    const host = connection.currentHost();
    if (!host) return Promise.reject(new Error('not connected'));
    return new Promise<PulledFile>((resolve, reject) => {
      this.pendingPulls.push({resolve, reject});
      connection.send('fs.pull', {path});
      // Don't leave a drag/button hanging forever if the phone never replies.
      setTimeout(() => {
        const i = this.pendingPulls.findIndex(p => p.reject === reject);
        if (i >= 0) {
          this.pendingPulls.splice(i, 1);
          reject(new Error('pull timed out'));
        }
      }, 10_000);
    });
  }

  /**
   * Arm downloads for several browsed files at once. Sends one `fs.pull` per path
   * in order; the `fs.pullReady` replies are correlated FIFO by the same queue
   * `pull()` uses — safe because the WebSocket is ordered and Android handles
   * messages in strict arrival order. Resolves once every file is armed.
   */
  pullMultiple(paths: string[]): Promise<PulledFile[]> {
    if (!connection.currentHost()) return Promise.reject(new Error('not connected'));
    return Promise.all(paths.map(path => this.pull(path)));
  }

  private requestList(path: string): void {
    if (!connection.currentHost()) {
      this.setState({path, entries: [], loading: false, error: 'not_connected'});
      return;
    }
    log('list', path || '(root)');
    this.setState({...this.state, path, loading: true, error: undefined});
    connection.send('fs.list', {path});
  }

  private onMessage(env: Envelope): void {
    switch (env.type) {
      case 'fs.listing': {
        const p = env.payload as FsListingPayload;
        log('listing', p.path, p.entries.length, p.error ?? '');
        this.setState({path: p.path, entries: p.entries, loading: false, error: p.error});
        break;
      }
      case 'fs.pullReady': {
        const p = env.payload as FsPullReadyPayload;
        const host = connection.currentHost();
        const waiter = this.pendingPulls.shift();
        if (waiter && host) {
          waiter.resolve({
            transferId: p.transferId,
            url: `http://${host}:${p.httpPort}/download/${p.transferId}`,
            name: p.name,
            size: p.size,
          });
        }
        break;
      }
      case 'fs.error': {
        const p = env.payload as FsErrorPayload;
        log('error', p.message);
        this.pendingPulls.shift()?.reject(new Error(p.message));
        break;
      }
    }
  }

  private setState(s: BrowseState): void {
    this.state = s;
    this.listeners.forEach(l => l(s));
  }
}

export const remoteBrowse = new RemoteBrowseService();
