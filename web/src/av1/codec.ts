/**
 * AV1 codec wrapper for the web editor.  Lazily loads the wasm reactor modules and exposes
 * lossless-mono encode (payload) and decode, plus color encode (MP4 preview).
 *
 * The decoder binary is byte-identical to the one the mod runs via Chicory, so encode↔decode
 * agree across web and mod.  Payload frames go through the OKLab palette permutation
 * (PERM on encode, INV_PERM on decode) exactly as PalettePermutation.java does.
 */

import { PERM, INV_PERM } from '../palette-perm.js';
import { MC_PALETTE, IS_VALID } from '../palette.js';
import { createWasiShim } from './wasi.js';

const MAP_BYTES = 128 * 128;

const MODE_MONO_LOSSLESS = 0;
const MODE_COLOR_CQ      = 1;

interface CodecExports {
  memory: WebAssembly.Memory;
  shim_malloc(n: number): number;
  shim_free(p: number): void;
  enc_init(w: number, h: number, frameCount: number, mode: number, quality: number): number;
  enc_frame(plane: number, len: number): number;
  enc_finish(): number;
  enc_data(): number;
  enc_reset(): void;
  dec_open(): number;
  dec_set_palette(entries: number, count: number): void;
  dec_tu(obu: number, len: number, out: number, outCap: number): number;
  dec_tu_full(obu: number, len: number, outIdx: number, idxCap: number, outRgb: number, rgbCap: number): number;
  dec_close(): void;
  _initialize?(): void;
}

async function instantiate(url: string): Promise<CodecExports> {
  const wasi = createWasiShim();
  const resp = await fetch(url);
  if (!resp.ok) throw new Error(`failed to fetch ${url}: ${resp.status}`);
  const bytes = await resp.arrayBuffer();
  const { instance } = await WebAssembly.instantiate(bytes, wasi.imports);
  const ex = instance.exports as unknown as CodecExports;
  wasi.bind(ex.memory);
  ex._initialize?.();
  return ex;
}

function assetUrl(name: string): string {
  // Vite serves web/public/av1/* at <base>av1/*.
  const base = (import.meta as unknown as { env?: { BASE_URL?: string } }).env?.BASE_URL ?? '/';
  return `${base}av1/${name}`;
}

let _encoder: Promise<CodecExports> | null = null;
let _decoder: Promise<CodecExports> | null = null;

/** Override the wasm source (used by Node tests to inject local bytes). */
export function _setCodecFactory(
  enc: (() => Promise<CodecExports>) | null,
  dec: (() => Promise<CodecExports>) | null,
): void {
  _encoder = enc ? enc() : null;
  _decoder = dec ? dec() : null;
}

function encoder(): Promise<CodecExports> {
  return (_encoder ??= instantiate(assetUrl('av1-encode.wasm')));
}
function decoder(): Promise<CodecExports> {
  return (_decoder ??= instantiate(assetUrl('av1-decode.wasm')));
}

function writeMem(ex: CodecExports, ptr: number, data: Uint8Array): void {
  new Uint8Array(ex.memory.buffer, ptr, data.length).set(data);
}
function readMem(ex: CodecExports, ptr: number, len: number): Uint8Array {
  return new Uint8Array(ex.memory.buffer, ptr, len).slice();
}

/**
 * Encode animated map-colour frames as a lossless monochrome AV1 stream (length-prefixed
 * temporal units).  Applies the OKLab palette permutation before encoding.
 */
export type ProgressFn = (done: number, total: number) => void;

export async function encodeLosslessMono(frames: Uint8Array[], onProgress?: ProgressFn): Promise<Uint8Array> {
  const ex = await encoder();
  if (ex.enc_init(128, 128, frames.length, MODE_MONO_LOSSLESS, 0) !== 0) throw new Error('enc_init failed');
  const inPtr = ex.shim_malloc(MAP_BYTES);
  try {
    const permd = new Uint8Array(MAP_BYTES);
    for (let f = 0; f < frames.length; f++) {
      const frame = frames[f];
      for (let i = 0; i < MAP_BYTES; i++) permd[i] = PERM[frame[i] & 0xff];
      writeMem(ex, inPtr, permd);
      if (ex.enc_frame(inPtr, MAP_BYTES) !== 0) throw new Error('enc_frame failed');
      onProgress?.(f + 1, frames.length);
    }
    const total = ex.enc_finish();
    if (total <= 0) throw new Error(`enc_finish=${total}`);
    return readMem(ex, ex.enc_data(), total);
  } finally {
    ex.shim_free(inPtr);
    ex.enc_reset();
  }
}

/**
 * Decode a lossless-mono AV1 stream back into absolute map-colour frames.
 * @param full   payload bytes (manifest header + AV1 stream, or the bare stream)
 * @param offset where the AV1 stream begins
 */
export async function decodeLosslessMono(
  full: Uint8Array, offset: number, frameCount: number,
): Promise<Uint8Array[]> {
  const ex = await decoder();
  if (ex.dec_open() !== 0) throw new Error('dec_open failed');
  const obuPtr = ex.shim_malloc(1 << 20);
  const outPtr = ex.shim_malloc(MAP_BYTES);
  try {
    const frames: Uint8Array[] = [];
    let off = offset;
    for (let f = 0; f < frameCount; f++) {
      if (off + 4 > full.length) break;
      const len = full[off] | (full[off+1] << 8) | (full[off+2] << 16) | (full[off+3] << 24);
      off += 4;
      if (len <= 0 || off + len > full.length) break;
      writeMem(ex, obuPtr, full.subarray(off, off + len));
      off += len;
      const wrote = ex.dec_tu(obuPtr, len, outPtr, MAP_BYTES);
      if (wrote !== MAP_BYTES) throw new Error(`dec_tu frame ${f} = ${wrote}`);
      const plane = new Uint8Array(ex.memory.buffer, outPtr, MAP_BYTES);
      const frame = new Uint8Array(MAP_BYTES);
      for (let i = 0; i < MAP_BYTES; i++) frame[i] = INV_PERM[plane[i]];
      frames.push(frame);
    }
    if (frames.length === 0) throw new Error('AV1 decode produced no frames');
    return frames;
  } finally {
    ex.dec_close();
    ex.shim_free(obuPtr);
    ex.shim_free(outPtr);
  }
}

// ─── Lossy colour path (map-index frames ↔ AV1 4:2:0) ───────────────────────────

/** Palette entries {mapByte, r, g, b} the decoder uses to re-quantise lossy colour → index. */
function paletteEntries(): Uint8Array {
  const valid: number[] = [];
  for (let b = 0; b < 256; b++) if (IS_VALID[b]) valid.push(b);
  const out = new Uint8Array(valid.length * 4);
  valid.forEach((b, i) => {
    const p = MC_PALETTE[b];
    out[i * 4] = b; out[i * 4 + 1] = (p >> 16) & 0xff; out[i * 4 + 2] = (p >> 8) & 0xff; out[i * 4 + 3] = p & 0xff;
  });
  return out;
}
let _palEntries: Uint8Array | null = null;
function palEntries(): Uint8Array { return (_palEntries ??= paletteEntries()); }

/** Frame dimensions for the lossy colour path; defaults to one 128×128 tile. */
export interface Dims { width: number; height: number }
const TILE_DIMS: Dims = { width: 128, height: 128 };

/** Largest length-prefixed temporal unit in the stream (for sizing the decode scratch buffer). */
function maxTuLength(full: Uint8Array, offset: number): number {
  let max = 0, off = offset;
  while (off + 4 <= full.length) {
    const len = full[off] | (full[off+1] << 8) | (full[off+2] << 16) | (full[off+3] << 24);
    off += 4;
    if (len <= 0 || off + len > full.length) break;
    if (len > max) max = len;
    off += len;
  }
  return max;
}

/**
 * Encode animated map-colour frames as a LOSSY colour AV1 stream at `quality`
 * (1 = best/largest … 63 = worst/smallest).  Frames are rasterised to RGB via the palette;
 * the decoder re-quantises back to the nearest palette index, so the result is a close
 * approximation, not byte-identical.  `dims` selects the frame size (composite streams cover
 * the whole composition; the default is a single tile).
 */
export async function encodeLossyColor(
  frames: Uint8Array[], quality: number, onProgress?: ProgressFn, dims: Dims = TILE_DIMS,
): Promise<Uint8Array> {
  const { width, height } = dims;
  const planeBytes = width * height;
  const ex = await encoder();
  if (ex.enc_init(width, height, frames.length, MODE_COLOR_CQ, quality) !== 0) throw new Error('enc_init(color) failed');
  const inPtr = ex.shim_malloc(planeBytes * 3);
  try {
    const rgb = new Uint8Array(planeBytes * 3);
    for (let f = 0; f < frames.length; f++) {
      const frame = frames[f];
      for (let i = 0; i < planeBytes; i++) {
        const p = MC_PALETTE[frame[i] & 0xff] ?? 0;
        rgb[i * 3] = (p >> 16) & 0xff; rgb[i * 3 + 1] = (p >> 8) & 0xff; rgb[i * 3 + 2] = p & 0xff;
      }
      writeMem(ex, inPtr, rgb);
      if (ex.enc_frame(inPtr, rgb.length) !== 0) throw new Error('enc_frame(color) failed');
      onProgress?.(f + 1, frames.length);
    }
    const total = ex.enc_finish();
    if (total <= 0) throw new Error(`enc_finish(color)=${total}`);
    return readMem(ex, ex.enc_data(), total);
  } finally {
    ex.shim_free(inPtr);
    ex.enc_reset();
  }
}

/** Decode a lossy colour AV1 stream into absolute map-colour frames (nearest-palette, no INV_PERM). */
export async function decodeLossyColor(
  full: Uint8Array, offset: number, frameCount: number, onProgress?: ProgressFn, dims: Dims = TILE_DIMS,
): Promise<Uint8Array[]> {
  const { width, height } = dims;
  const planeBytes = width * height;
  const ex = await decoder();
  if (ex.dec_open() !== 0) throw new Error('dec_open failed');
  const entries = palEntries();
  const palPtr = ex.shim_malloc(entries.length);
  const obuPtr = ex.shim_malloc(Math.max(1 << 20, maxTuLength(full, offset)));
  const outPtr = ex.shim_malloc(planeBytes);
  try {
    writeMem(ex, palPtr, entries);
    ex.dec_set_palette(palPtr, entries.length / 4);
    const frames: Uint8Array[] = [];
    let off = offset;
    for (let f = 0; f < frameCount; f++) {
      if (off + 4 > full.length) break;
      const len = full[off] | (full[off+1] << 8) | (full[off+2] << 16) | (full[off+3] << 24);
      off += 4;
      if (len <= 0 || off + len > full.length) break;
      writeMem(ex, obuPtr, full.subarray(off, off + len));
      off += len;
      const wrote = ex.dec_tu(obuPtr, len, outPtr, planeBytes);
      if (wrote !== planeBytes) throw new Error(`dec_tu(color) frame ${f} = ${wrote}`);
      frames.push(readMem(ex, outPtr, planeBytes)); // already real map bytes
      onProgress?.(f + 1, frameCount);
    }
    if (frames.length === 0) throw new Error('AV1 lossy decode produced no frames');
    return frames;
  } finally {
    ex.dec_close();
    ex.shim_free(palPtr);
    ex.shim_free(obuPtr);
    ex.shim_free(outPtr);
  }
}

/**
 * Encode packed-RGB frames (`width*height*3` bytes each) as a LOSSY colour AV1 stream —
 * the sRGB-mode twin of {@link encodeLossyColor}, minus the palette rasterise step (the
 * frames are already true colour and never came from palette indices).
 */
export async function encodeLossyRgb(
  rgbFrames: Uint8Array[], quality: number, onProgress?: ProgressFn, dims: Dims = TILE_DIMS,
): Promise<Uint8Array> {
  const { width, height } = dims;
  const planeBytes = width * height;
  const ex = await encoder();
  if (ex.enc_init(width, height, rgbFrames.length, MODE_COLOR_CQ, quality) !== 0) throw new Error('enc_init(rgb) failed');
  const inPtr = ex.shim_malloc(planeBytes * 3);
  try {
    for (let f = 0; f < rgbFrames.length; f++) {
      const rgb = rgbFrames[f];
      if (rgb.length !== planeBytes * 3) throw new Error(`rgb frame ${f} is ${rgb.length} bytes, want ${planeBytes * 3}`);
      writeMem(ex, inPtr, rgb);
      if (ex.enc_frame(inPtr, rgb.length) !== 0) throw new Error('enc_frame(rgb) failed');
      onProgress?.(f + 1, rgbFrames.length);
    }
    const total = ex.enc_finish();
    if (total <= 0) throw new Error(`enc_finish(rgb)=${total}`);
    return readMem(ex, ex.enc_data(), total);
  } finally {
    ex.shim_free(inPtr);
    ex.enc_reset();
  }
}

/** Both views of a decoded lossy colour stream (sRGB mode): nearest-palette map bytes and raw RGB. */
export interface DecodedRgb { idx: Uint8Array[]; rgb: Uint8Array[] }

/**
 * Decode a lossy colour AV1 stream keeping BOTH the nearest-palette map bytes (`idx`, the
 * quantised preview twin) and the reconstructed packed RGB (`rgb`, `width*height*3` per frame).
 * Byte-for-byte the same maths the mod runs via Chicory (`Av1FrameDecoder.decodeWithRgb`).
 */
export async function decodeLossyRgb(
  full: Uint8Array, offset: number, frameCount: number, onProgress?: ProgressFn, dims: Dims = TILE_DIMS,
): Promise<DecodedRgb> {
  const { width, height } = dims;
  const planeBytes = width * height;
  const ex = await decoder();
  if (ex.dec_open() !== 0) throw new Error('dec_open failed');
  const entries = palEntries();
  const palPtr = ex.shim_malloc(entries.length);
  const obuPtr = ex.shim_malloc(Math.max(1 << 20, maxTuLength(full, offset)));
  const outPtr = ex.shim_malloc(planeBytes);
  const rgbPtr = ex.shim_malloc(planeBytes * 3);
  try {
    writeMem(ex, palPtr, entries);
    ex.dec_set_palette(palPtr, entries.length / 4);
    const idx: Uint8Array[] = [];
    const rgb: Uint8Array[] = [];
    let off = offset;
    for (let f = 0; f < frameCount; f++) {
      if (off + 4 > full.length) break;
      const len = full[off] | (full[off+1] << 8) | (full[off+2] << 16) | (full[off+3] << 24);
      off += 4;
      if (len <= 0 || off + len > full.length) break;
      writeMem(ex, obuPtr, full.subarray(off, off + len));
      off += len;
      const wrote = ex.dec_tu_full(obuPtr, len, outPtr, planeBytes, rgbPtr, planeBytes * 3);
      if (wrote !== planeBytes) throw new Error(`dec_tu_full frame ${f} = ${wrote}`);
      idx.push(readMem(ex, outPtr, planeBytes));
      rgb.push(readMem(ex, rgbPtr, planeBytes * 3));
      onProgress?.(f + 1, frameCount);
    }
    if (idx.length === 0) throw new Error('AV1 sRGB decode produced no frames');
    return { idx, rgb };
  } finally {
    ex.dec_close();
    ex.shim_free(palPtr);
    ex.shim_free(obuPtr);
    ex.shim_free(outPtr);
    ex.shim_free(rgbPtr);
  }
}

/** Split a length-prefixed temporal-unit stream into individual TUs. */
export function splitTus(stream: Uint8Array): Uint8Array[] {
  const tus: Uint8Array[] = [];
  let off = 0;
  while (off + 4 <= stream.length) {
    const len = stream[off] | (stream[off+1] << 8) | (stream[off+2] << 16) | (stream[off+3] << 24);
    off += 4;
    if (len <= 0 || off + len > stream.length) break;
    tus.push(stream.subarray(off, off + len));
    off += len;
  }
  return tus;
}
