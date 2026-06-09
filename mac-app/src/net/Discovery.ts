/**
 * LAN discovery of Tether-capable phones via Bonjour/mDNS (react-native-zeroconf wraps
 * NSNetServiceBrowser on Apple platforms). Service type matches the Android advertiser.
 */
import Zeroconf from 'react-native-zeroconf';

export interface DiscoveredDevice {
  id: string;
  name: string;
  host: string;
  port: number;
  platform: 'android';
}

type Listener = (devices: DiscoveredDevice[]) => void;

export class Discovery {
  private zeroconf = new Zeroconf();
  private devices = new Map<string, DiscoveredDevice>();
  private listeners = new Set<Listener>();

  constructor() {
    this.zeroconf.on('resolved', (service: any) => {
      // Prefer the numeric IPv4 address over the `.local` hostname: connecting to a hostname
      // forces a second (slow, multicast) mDNS lookup, so using the resolved IP makes Connect
      // near-instant. Fall back to any address, then the hostname.
      const addresses: string[] = service.addresses ?? [];
      const ipv4 = addresses.find(a => /^\d{1,3}(\.\d{1,3}){3}$/.test(a));
      const host: string | undefined = ipv4 || addresses[0] || service.host;
      const txt = service.txt ?? {};
      if (!host || !service.port) return;
      const id: string = txt.id || service.name;
      this.devices.set(id, {
        id,
        name: txt.name || service.name || host,
        host,
        port: service.port,
        platform: 'android',
      });
      this.emit();
    });
    this.zeroconf.on('remove', (name: string) => {
      for (const [id, d] of this.devices) if (d.name === name || id === name) this.devices.delete(id);
      this.emit();
    });
    this.zeroconf.on('error', (err: unknown) => console.warn('[zeroconf]', err));
  }

  private scanning = false;

  /** Begin scanning. Idempotent — safe to call from multiple places (app launch + screens). */
  start(): void {
    if (this.scanning) return;
    this.scanning = true;
    // type without underscores, protocol, domain — resolves `_tether._tcp.local.`
    this.zeroconf.scan('tether', 'tcp', 'local.');
  }

  stop(): void {
    this.scanning = false;
    this.zeroconf.stop();
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.list());
    return () => this.listeners.delete(listener);
  }

  list(): DiscoveredDevice[] {
    return [...this.devices.values()];
  }

  private emit(): void {
    const snapshot = this.list();
    this.listeners.forEach(l => l(snapshot));
  }
}

/** Shared singleton — one scanner for the whole app (auto-connect + the Devices screen share it). */
export const discovery = new Discovery();
