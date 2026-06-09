/**
 * Keeps the Mac linked to phones it has already paired with, with no manual "Connect":
 *  - on launch it connects straight to each known phone's LAST KNOWN address (cached on disk), so
 *    it doesn't have to wait for mDNS discovery — this is the fast path,
 *  - discovery runs in parallel and corrects the address if the phone moved to a new IP,
 *  - the ConnectionManager's own reconnect loop handles transient drops while running,
 *  - `forget()` clears the token + cached address so that device stops auto-connecting until paired.
 *
 * "Known" = we hold a pairing token for that device id (persisted across app launches).
 */
import {connection} from '../net/ConnectionManager';
import {discovery, DiscoveredDevice} from '../net/Discovery';
import {forgetLastAddress, forgetToken, getLastAddress, listPairedDeviceIds, setLastAddress} from '../util/store';

class AutoConnect {
  private known = new Set<string>();
  private wired = false;

  async wire(): Promise<void> {
    if (this.wired) return;
    this.wired = true;

    connection.on('state', s => {
      if (s === 'connected') {
        const d = connection.currentDevice();
        if (d) {
          this.known.add(d.id);
          void setLastAddress(d.id, {host: d.host, port: d.port, name: d.name}); // remember for next launch
        }
      }
      this.maybeConnect();
    });
    discovery.onChange(() => this.maybeConnect());

    (await listPairedDeviceIds()).forEach(id => this.known.add(id));

    // Fast path: connect to the last known address immediately, before mDNS has resolved anything.
    for (const id of this.known) {
      const addr = await getLastAddress(id);
      if (addr) {
        connection.connect({id, host: addr.host, port: addr.port, name: addr.name, platform: 'android'});
        break;
      }
    }
    this.maybeConnect();
  }

  isKnown(id: string): boolean {
    return this.known.has(id);
  }

  /** Forget the connected device: drop its token + cached address, disconnect, stop auto-connecting. */
  async forget(): Promise<void> {
    const id = connection.currentDeviceId();
    if (id) {
      this.known.delete(id);
      await Promise.all([forgetToken(id), forgetLastAddress(id)]);
    }
    connection.disconnect();
  }

  private maybeConnect(): void {
    if (this.known.size === 0) return;
    const state = connection.getState();
    if (state === 'connected' || state === 'pairing') return; // done, or mid-handshake — leave alone

    const target = discovery.list().find(d => this.known.has(d.id));
    if (!target) return;

    // If we're already aiming at the right host, let the in-flight attempt / reconnect loop run.
    // If it's a different host (stale cached IP vs. the freshly discovered one), preempt it.
    if ((state === 'connecting' || state === 'reconnecting') && connection.currentHost() === target.host) {
      return;
    }
    connection.connect(target);
  }
}

export const autoConnect = new AutoConnect();
