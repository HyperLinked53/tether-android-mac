/**
 * Binary data-frame codec — mirror of Android `BinaryFrame` (protocol/README §3).
 * Header: [version=1][channel][16-byte streamId][3 reserved] then payload.
 */
import {BINARY_HEADER_SIZE, BINARY_VERSION, BinaryChannel} from '../protocol/types';
import {bytesToUuid, uuidToBytes} from '../util/encoding';

export interface DecodedFrame {
  channel: number;
  streamId: string;
  payload: Uint8Array;
}

export function encodeFrame(channel: BinaryChannel, streamId: string, payload: Uint8Array): ArrayBuffer {
  const buf = new Uint8Array(BINARY_HEADER_SIZE + payload.length);
  buf[0] = BINARY_VERSION;
  buf[1] = channel;
  buf.set(uuidToBytes(streamId), 2);
  // bytes 18..20 reserved (already zero)
  buf.set(payload, BINARY_HEADER_SIZE);
  return buf.buffer;
}

export function decodeFrame(data: ArrayBuffer): DecodedFrame | null {
  const buf = new Uint8Array(data);
  if (buf.length < BINARY_HEADER_SIZE || buf[0] !== BINARY_VERSION) return null;
  return {
    channel: buf[1],
    streamId: bytesToUuid(buf.subarray(2, 18)),
    payload: buf.subarray(BINARY_HEADER_SIZE),
  };
}
