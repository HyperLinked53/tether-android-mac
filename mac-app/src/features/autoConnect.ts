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
import {discovery} from '../net/Discovery';
import {
  AutoDisconnectSetting,
  forgetLastAddress, forgetToken,
  getAutoDisconnect, getLastAddress, listPairedDeviceIds,
  setAutoDisconnect, setLastAddress,
} from '../util/store';

const MS: Record<string, number> = {'1h': 3_600_000, '5h': 18_000_000, '12h': 43_200_000};

function msForSetting(s: AutoDisconnectSetting): number | null {
  if (s.option === 'never') return null;
  if (s.option === 'custom') return Math.max(1, s.customHours) * 3_600_000;
  return MS[s.option] ?? null;
}

class AutoConnect {
  private known = new Set<string>();
  private wired = false;
  private disconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private paused = false; // true after an auto-disconnect fires; resets on next manual connect

  async wire(): Promise<void> {
    if (this.wired) return;
    this.wired = true;

    connection.on('state', s => {
      if (s === 'connected') {
        this.paused = false;
        const d = connection.currentDevice();
        if (d) {
          this.known.add(d.id);
          void setLastAddress(d.id, {host: d.host, port: d.port, name: d.name});
        }
        void this.rescheduleTimer();
      } else {
        this.clearTimer();
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
    this.clearTimer();
    const id = connection.currentDeviceId();
    if (id) {
      this.known.delete(id);
      await Promise.all([forgetToken(id), forgetLastAddress(id)]);
    }
    connection.disconnect();
  }

  /** Read the current setting and save it, then reschedule the timer if connected. */
  async updateSetting(setting: AutoDisconnectSetting): Promise<void> {
    await setAutoDisconnect(setting);
    if (connection.getState() === 'connected') {
      await this.rescheduleTimer();
    }
  }

  getAutoDisconnectSetting(): Promise<AutoDisconnectSetting> {
    return getAutoDisconnect();
  }

  private async rescheduleTimer(): Promise<void> {
    this.clearTimer();
    const setting = await getAutoDisconnect();
    const ms = msForSetting(setting);
    if (ms === null) return;
    this.disconnectTimer = setTimeout(() => {
      this.paused = true;
      this.clearTimer();
      connection.disconnect();
    }, ms);
  }

  private clearTimer(): void {
    if (this.disconnectTimer !== null) {
      clearTimeout(this.disconnectTimer);
      this.disconnectTimer = null;
    }
  }

  private maybeConnect(): void {
    if (this.known.size === 0) return;
    if (this.paused) return; // auto-disconnected this session; wait for user to manually reconnect
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
