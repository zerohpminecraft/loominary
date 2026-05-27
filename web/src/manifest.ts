/**
 * PayloadManifest — port of PayloadManifest.java.
 *
 * Variable-length binary header prepended to the map-colour bytes inside the
 * zstd frame.  Carries grid position, author, title, and animation parameters.
 */

export const CURRENT_VERSION = 6;

export const FLAG_ALL_SHADES   = 0x01;
export const FLAG_ANIMATED     = 0x02;
export const FLAG_MUX          = 0x04;
export const FLAG_DELTA_FRAMES = 0x08;
export const FLAG_SPARSE_FRAMES = 0x10;

const MAP_BYTES = 128 * 128; // 16384

const enc = new TextEncoder();
const dec = new TextDecoder('utf-8');

// ─── Manifest object ──────────────────────────────────────────────────────────

export interface Manifest {
  manifestVersion: number;
  headerSize: number;
  flags: number;
  cols: number;
  rows: number;
  tileCol: number;
  tileRow: number;
  /** CRC32 of the first 16384 map-colour bytes; -1 if unknown. */
  colorCrc32: number;
  username: string | null;
  title: string | null;
  /** v2+: random nonce; 0 for v1. */
  nonce: number;
  /** v3+: total frames; 1 for static. */
  frameCount: number;
  /** v3+: 0 = loop forever. */
  loopCount: number;
  /** v3+: delay(s) in ms. Length 1 = global; length frameCount = per-frame. */
  frameDelays: number[];
}

export function allShades(m: Manifest): boolean  { return (m.flags & FLAG_ALL_SHADES) !== 0; }
export function animated(m: Manifest): boolean   { return (m.flags & FLAG_ANIMATED)   !== 0; }
export function muxed(m: Manifest): boolean      { return (m.flags & FLAG_MUX)        !== 0; }
export function deltaFrames(m: Manifest): boolean { return (m.flags & FLAG_DELTA_FRAMES) !== 0; }
export function sparseFrames(m: Manifest): boolean { return (m.flags & FLAG_SPARSE_FRAMES) !== 0; }

// ─── CRC32 ────────────────────────────────────────────────────────────────────

let _crcTable: Uint32Array | null = null;
function makeCrcTable(): Uint32Array {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
}

export function crc32(data: Uint8Array): number {
  if (!_crcTable) _crcTable = makeCrcTable();
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < data.length; i++) crc = (_crcTable[(crc ^ data[i]) & 0xff] ^ (crc >>> 8)) >>> 0;
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

// ─── Serialization ────────────────────────────────────────────────────────────

/** Produce a v1 manifest (no nonce). */
export function toBytesV1(
  flags: number, cols: number, rows: number, tileCol: number, tileRow: number,
  colorCrc32: number, username: string | null, title: string | null,
): Uint8Array {
  const userBytes  = encodeStr(username, 16);
  const titleBytes = encodeStr(title, 64);
  const totalSize  = 13 + userBytes.length + titleBytes.length;
  const out = new Uint8Array(totalSize);
  let i = 0;
  out[i++] = 1; out[i++] = totalSize;
  out[i++] = flags; out[i++] = cols; out[i++] = rows; out[i++] = tileCol; out[i++] = tileRow;
  writeU32BE(out, i, colorCrc32); i += 4;
  out[i++] = userBytes.length; out.set(userBytes, i); i += userBytes.length;
  out[i++] = titleBytes.length; out.set(titleBytes, i);
  return out;
}

/** Produce a v2 manifest with a nonce (if nonce==0 falls back to v1). */
export function toBytesV2(
  flags: number, cols: number, rows: number, tileCol: number, tileRow: number,
  colorCrc32: number, username: string | null, title: string | null, nonce: number,
): Uint8Array {
  if (nonce === 0) return toBytesV1(flags, cols, rows, tileCol, tileRow, colorCrc32, username, title);
  const userBytes  = encodeStr(username, 16);
  const titleBytes = encodeStr(title, 64);
  const totalSize  = 17 + userBytes.length + titleBytes.length;
  const out = new Uint8Array(totalSize);
  let i = 0;
  out[i++] = 2; out[i++] = totalSize;
  out[i++] = flags; out[i++] = cols; out[i++] = rows; out[i++] = tileCol; out[i++] = tileRow;
  writeU32BE(out, i, colorCrc32); i += 4;
  out[i++] = userBytes.length; out.set(userBytes, i); i += userBytes.length;
  out[i++] = titleBytes.length; out.set(titleBytes, i); i += titleBytes.length;
  writeU32BE(out, i, nonce);
  return out;
}

/** Produce a v4 or v5 animated manifest. */
export function toBytesAnimated(
  flags: number, cols: number, rows: number, tileCol: number, tileRow: number,
  colorCrc32: number, username: string | null, title: string | null, nonce: number,
  frameCount: number, loopCount: number, frameDelays: number[],
): Uint8Array {
  if (frameCount < 1 || frameCount > 65535) throw new Error(`frameCount must be 1–65535, got ${frameCount}`);

  const userBytes  = encodeStr(username, 16);
  const titleBytes = encodeStr(title, 64);

  // Normalize: if all delays are equal, collapse to single global.
  let perFrame = frameDelays.length > 1;
  if (perFrame) {
    const first = frameDelays[0];
    if (frameDelays.every(d => d === first)) { perFrame = false; frameDelays = [first]; }
  }

  const fixedSize = 22 + userBytes.length + titleBytes.length;
  const inlineDelay = perFrame ? frameCount * 2 : 2;
  const trailing = perFrame && (fixedSize + inlineDelay > 255);
  const totalSize = fixedSize + (trailing ? 0 : inlineDelay);

  const out = new Uint8Array(totalSize);
  let i = 0;
  out[i++] = trailing ? 5 : 4; out[i++] = totalSize;
  out[i++] = flags; out[i++] = cols; out[i++] = rows; out[i++] = tileCol; out[i++] = tileRow;
  writeU32BE(out, i, colorCrc32); i += 4;
  out[i++] = userBytes.length; out.set(userBytes, i); i += userBytes.length;
  out[i++] = titleBytes.length; out.set(titleBytes, i); i += titleBytes.length;
  writeU32BE(out, i, nonce); i += 4;
  out[i++] = (frameCount >>> 8) & 0xff; out[i++] = frameCount & 0xff;
  out[i++] = (loopCount >>> 8) & 0xff;  out[i++] = loopCount  & 0xff;
  out[i++] = (perFrame || trailing) ? 1 : 0; // delay_mode

  if (!trailing) {
    if (perFrame) {
      for (let f = 0; f < frameCount; f++) {
        const d = f < frameDelays.length ? frameDelays[f] : 100;
        out[i++] = (d >>> 8) & 0xff; out[i++] = d & 0xff;
      }
    } else {
      const d = frameDelays[0] ?? 100;
      out[i++] = (d >>> 8) & 0xff; out[i++] = d & 0xff;
    }
  }
  return out;
}

/**
 * Returns the trailing delay table that must be appended after all frame data
 * for v5 manifests.  Returns empty Uint8Array for v4 or global-delay cases.
 */
export function trailingDelayBytes(manifest: Uint8Array, frameDelays: number[]): Uint8Array {
  if (manifest.length < 1 || (manifest[0] & 0xff) < 5) return new Uint8Array(0);
  if (frameDelays.length <= 1) return new Uint8Array(0);
  const out = new Uint8Array(frameDelays.length * 2);
  for (let f = 0; f < frameDelays.length; f++) {
    const d = frameDelays[f];
    out[f * 2]     = (d >>> 8) & 0xff;
    out[f * 2 + 1] = d & 0xff;
  }
  return out;
}

// ─── Deserialization ──────────────────────────────────────────────────────────

/** Parse a manifest from the start of a decompressed payload. */
export function fromBytes(data: Uint8Array): Manifest {
  if (data.length < 2) throw new Error(`Payload too short for manifest: ${data.length} bytes`);

  const ver        = data[0];
  const headerSize = data[1];

  if (ver > CURRENT_VERSION) {
    // Unknown version — stub so caller can still skip to map colours.
    return { manifestVersion: ver, headerSize, flags: 0, cols: 0, rows: 0,
             tileCol: 0, tileRow: 0, colorCrc32: -1, username: null, title: null,
             nonce: 0, frameCount: 1, loopCount: 0, frameDelays: [100] };
  }

  if (data.length < 13) throw new Error(`v1 manifest too short: ${data.length} bytes`);

  let i = 2;
  const flags   = data[i++];
  const cols    = data[i++];
  const rows    = data[i++];
  const tileCol = data[i++];
  const tileRow = data[i++];
  const colorCrc32 = readU32BE(data, i); i += 4;

  const usernameLen = data[i++];
  let username: string | null = null;
  if (usernameLen > 0) { username = dec.decode(data.subarray(i, i + usernameLen)); i += usernameLen; }

  const titleLen = data[i++];
  let title: string | null = null;
  if (titleLen > 0) { title = dec.decode(data.subarray(i, i + titleLen)); i += titleLen; }

  let nonce = 0;
  if (ver >= 2 && i + 4 <= data.length) { nonce = readU32BE(data, i); i += 4; }

  let frameCount = 1, loopCount = 0;
  let frameDelays: number[] = [100];

  if (ver >= 3 && i < data.length) {
    if (ver >= 4 && i + 2 <= data.length) {
      frameCount = (data[i] << 8) | data[i + 1]; i += 2;
    } else {
      frameCount = data[i++];
    }
    if (i + 2 <= data.length) { loopCount = (data[i] << 8) | data[i + 1]; i += 2; }
    if (i < data.length) {
      const delayMode = data[i++];
      if (delayMode === 0 && i + 2 <= data.length) {
        frameDelays = [(data[i] << 8) | data[i + 1]];
      } else if (delayMode === 1) {
        frameDelays = new Array(frameCount).fill(100);
        if (ver >= 5) {
          // Trailing: delay table is after all frame data.
          let dt = headerSize + frameCount * MAP_BYTES;
          for (let f = 0; f < frameCount && dt + 2 <= data.length; f++, dt += 2) {
            frameDelays[f] = (data[dt] << 8) | data[dt + 1];
          }
        } else {
          // v3/v4: inline per-frame table.
          for (let f = 0; f < frameCount && i + 2 <= data.length; f++, i += 2) {
            frameDelays[f] = (data[i] << 8) | data[i + 1];
          }
        }
      }
    }
  }

  return { manifestVersion: ver, headerSize, flags, cols, rows, tileCol, tileRow,
           colorCrc32, username, title, nonce, frameCount, loopCount, frameDelays };
}

// ─── Frame extraction ─────────────────────────────────────────────────────────

/**
 * Extract all animation frames from a decompressed payload.
 * Handles delta and sparse encoding (v6).
 */
export function extractFrames(full: Uint8Array, manifest: Manifest): Uint8Array[] {
  const { headerSize, frameCount } = manifest;
  const frames: Uint8Array[] = [];

  if (deltaFrames(manifest)) {
    const prev = full.slice(headerSize, headerSize + MAP_BYTES);
    frames.push(prev.slice());
    let off = headerSize + MAP_BYTES;
    for (let f = 1; f < frameCount; f++) {
      const cur = new Uint8Array(MAP_BYTES);
      for (let p = 0; p < MAP_BYTES; p++) cur[p] = prev[p] ^ full[off + p];
      prev.set(cur);
      frames.push(cur.slice());
      off += MAP_BYTES;
    }
  } else if (sparseFrames(manifest)) {
    frames.push(full.slice(headerSize, headerSize + MAP_BYTES));
    let off = headerSize + MAP_BYTES;
    for (let f = 1; f < frameCount; f++) {
      const cur = frames[f - 1].slice();
      const changeCount = (full[off] << 8) | full[off + 1]; off += 2;
      for (let c = 0; c < changeCount; c++) {
        const pos = (full[off] << 8) | full[off + 1]; off += 2;
        cur[pos] = full[off++];
      }
      frames.push(cur);
    }
  } else {
    for (let f = 0; f < frameCount; f++) {
      frames.push(full.slice(headerSize + f * MAP_BYTES, headerSize + (f + 1) * MAP_BYTES));
    }
  }
  return frames;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function encodeStr(s: string | null, maxBytes: number): Uint8Array {
  if (!s) return new Uint8Array(0);
  const raw = enc.encode(s);
  return raw.length <= maxBytes ? raw : raw.subarray(0, maxBytes);
}

function readU32BE(data: Uint8Array, i: number): number {
  return (((data[i] << 24) | (data[i+1] << 16) | (data[i+2] << 8) | data[i+3]) >>> 0);
}

function writeU32BE(out: Uint8Array, i: number, v: number): void {
  out[i    ] = (v >>> 24) & 0xff;
  out[i + 1] = (v >>> 16) & 0xff;
  out[i + 2] = (v >>>  8) & 0xff;
  out[i + 3] =  v         & 0xff;
}
