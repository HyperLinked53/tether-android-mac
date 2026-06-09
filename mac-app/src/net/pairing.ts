/**
 * Pairing proof computation — must match Android `PairingManager.computeProof`.
 * proof = base64( HMAC-SHA256(key = utf8(secret), msg = utf8(salt + deviceId)) )
 */
import {base64Encode, utf8Bytes} from '../util/encoding';
import {hmacSha256} from '../util/sha256';

export function computeProof(secret: string, salt: string, deviceId: string): string {
  const key = utf8Bytes(secret);
  const msg = utf8Bytes(salt + deviceId);
  return base64Encode(hmacSha256(key, msg));
}

/** Parse the `tether://pair?...` payload from a scanned QR or pasted code. */
export function parsePairingPayload(input: string): {deviceId?: string; secret: string} {
  const trimmed = input.trim();
  if (trimmed.startsWith('tether://pair')) {
    const query = trimmed.slice(trimmed.indexOf('?') + 1);
    const params = new URLSearchParams(query);
    return {
      deviceId: params.get('deviceId') ?? undefined,
      secret: decodeURIComponent(params.get('secret') ?? ''),
    };
  }
  // Bare secret pasted from the phone's "copy code" affordance.
  return {secret: trimmed};
}
