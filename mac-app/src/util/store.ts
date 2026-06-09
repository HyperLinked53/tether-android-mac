/**
 * Tiny persistent JSON key-value store backed by a single file in the app's documents dir.
 * Avoids adding AsyncStorage as a separate native dependency since we already depend on RNFS.
 */
import RNFS from 'react-native-fs';
import {uuidv4} from './encoding';

const FILE = `${RNFS.DocumentDirectoryPath}/tether-store.json`;

let cache: Record<string, unknown> | null = null;

async function load(): Promise<Record<string, unknown>> {
  if (cache) return cache;
  try {
    const exists = await RNFS.exists(FILE);
    cache = exists ? JSON.parse(await RNFS.readFile(FILE, 'utf8')) : {};
  } catch {
    cache = {};
  }
  return cache!;
}

async function persist(): Promise<void> {
  await RNFS.writeFile(FILE, JSON.stringify(cache ?? {}), 'utf8');
}

export async function getItem<T>(key: string): Promise<T | undefined> {
  return (await load())[key] as T | undefined;
}

export async function setItem(key: string, value: unknown): Promise<void> {
  (await load())[key] = value;
  await persist();
}

export async function removeItem(key: string): Promise<void> {
  delete (await load())[key];
  await persist();
}

/** Stable per-install identity for this Mac. */
export async function selfDeviceId(): Promise<string> {
  let id = await getItem<string>('deviceId');
  if (!id) {
    id = uuidv4();
    await setItem('deviceId', id);
  }
  return id;
}

const tokenKey = (peerId: string) => `token:${peerId}`;
export const getToken = (peerId: string) => getItem<string>(tokenKey(peerId));
export const setToken = (peerId: string, token: string) => setItem(tokenKey(peerId), token);
export const forgetToken = (peerId: string) => removeItem(tokenKey(peerId));

/** Device ids we hold a pairing token for (i.e. previously paired). */
export async function listPairedDeviceIds(): Promise<string[]> {
  const all = await load();
  return Object.keys(all)
    .filter(k => k.startsWith('token:'))
    .map(k => k.slice('token:'.length));
}

/** Last address a device was reachable at — lets us reconnect instantly without waiting for mDNS. */
export interface LastAddress {
  host: string;
  port: number;
  name: string;
}
const addrKey = (peerId: string) => `addr:${peerId}`;
export const getLastAddress = (peerId: string) => getItem<LastAddress>(addrKey(peerId));
export const setLastAddress = (peerId: string, addr: LastAddress) => setItem(addrKey(peerId), addr);
export const forgetLastAddress = (peerId: string) => removeItem(addrKey(peerId));
