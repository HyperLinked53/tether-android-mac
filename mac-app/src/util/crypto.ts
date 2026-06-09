/**
 * LAN session encryption (protocol §6). AES-256-GCM over the WebSocket, keyed by HKDF-SHA256 from the
 * pairing token (so the link is private even on the same Wi-Fi). Mirrors Android `ConduitCrypto`.
 *
 * AES comes from @noble/ciphers (pure JS, audited — no native crypto dep, consistent with the rest
 * of this app). HKDF is hand-rolled on the existing HMAC-SHA256. Nonces use Math.random: they only
 * need to be unique (HKDF salt is public; the GCM nonce isn't secret), and the actual secrecy comes
 * from the token, which the phone generates with a real CSPRNG.
 */
import {gcm} from '@noble/ciphers/aes';
import {hmacSha256} from './sha256';
import {utf8Bytes} from './encoding';

export function randomBytes(n: number): Uint8Array {
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = (Math.random() * 256) | 0;
  return b;
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a);
  out.set(b, a.length);
  return out;
}

/** HKDF-SHA256 → `len` bytes (len ≤ 32, one expand block). */
export function hkdf(ikm: Uint8Array, salt: Uint8Array, info: string, len = 32): Uint8Array {
  const prk = hmacSha256(salt, ikm); // extract
  const t = hmacSha256(prk, concat(utf8Bytes(info), new Uint8Array([1]))); // expand block 1
  return t.slice(0, len);
}

/** AES-256-GCM: output = 12-byte nonce ‖ ciphertext ‖ 16-byte tag. */
export function aesGcmEncrypt(key: Uint8Array, plaintext: Uint8Array): Uint8Array {
  const nonce = randomBytes(12);
  return concat(nonce, gcm(key, nonce).encrypt(plaintext));
}

export function aesGcmDecrypt(key: Uint8Array, data: Uint8Array): Uint8Array {
  return gcm(key, data.slice(0, 12)).decrypt(data.slice(12));
}
