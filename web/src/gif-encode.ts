/**
 * Minimal animated GIF encoder.
 *
 * Minecraft map bytes (0–255) are used directly as palette indices, so no
 * colour quantization is needed here — the MC_PALETTE becomes the GIF's
 * 256-colour global colour table.
 *
 * GIF LZW notes:
 *   - Minimum code size is 8 (for a 256-colour palette).
 *   - Bits are packed LSB-first within each byte.
 *   - Sub-blocks are at most 255 bytes, terminated by a 0-length block.
 */

import { MC_PALETTE } from './palette.js';
import type { CompositionState } from './payload-state.js';

const MAP_SIZE = 128;

// ─── Colour table ─────────────────────────────────────────────────────────────

function buildColorTable(): Uint8Array {
  const ct = new Uint8Array(256 * 3);
  for (let i = 0; i < 256; i++) {
    const rgb = MC_PALETTE[i] ?? 0;
    ct[i * 3    ] = (rgb >> 16) & 0xff;
    ct[i * 3 + 1] = (rgb >>  8) & 0xff;
    ct[i * 3 + 2] =  rgb        & 0xff;
  }
  return ct;
}

// ─── LZW encoder ─────────────────────────────────────────────────────────────

function lzwEncode(pixels: Uint8Array): Uint8Array {
  const MIN  = 8;                // minimum code size for 256-colour palette
  const CLR  = 1 << MIN;        // 256 — clear code
  const EOI  = CLR + 1;         // 257 — end-of-information code

  const bits: number[] = [];
  let buf = 0, bLen = 0;
  let codeSize = MIN + 1;
  let nextCode  = EOI + 1;

  // Dictionary keyed by  (prefixCode << 8) | suffixByte → newCode
  const dict = new Map<number, number>();

  const initDict = () => {
    dict.clear();
    codeSize = MIN + 1;
    nextCode  = EOI + 1;
  };

  const emit = (code: number) => {
    buf  |= code << bLen;
    bLen += codeSize;
    while (bLen >= 8) { bits.push(buf & 0xff); buf >>= 8; bLen -= 8; }
  };

  initDict();
  emit(CLR);

  let prefix = pixels[0];                          // starts as raw byte (0–255)
  for (let i = 1; i < pixels.length; i++) {
    const suf = pixels[i];
    const key = (prefix << 8) | suf;
    const hit = dict.get(key);
    if (hit !== undefined) {
      prefix = hit;
    } else {
      emit(prefix);
      if (nextCode <= 0xfff) {
        dict.set(key, nextCode++);
        if (nextCode > (1 << codeSize) && codeSize < 12) codeSize++;
      } else {
        emit(CLR);
        initDict();
      }
      prefix = suf;
    }
  }
  emit(prefix);
  emit(EOI);
  if (bLen > 0) bits.push(buf & 0xff);

  // Pack into GIF sub-blocks (≤ 255 bytes each) + block terminator
  const out: number[] = [];
  for (let i = 0; i < bits.length; ) {
    const n = Math.min(255, bits.length - i);
    out.push(n);
    for (let j = 0; j < n; j++) out.push(bits[i++]);
  }
  out.push(0); // block terminator
  return new Uint8Array(out);
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Encode `comp` as an animated GIF.
 *
 * @param loopCount  0 = loop forever, 1 = play once, N = loop N times
 */
export function encodeAnimatedGif(
  comp:      CompositionState,
  loopCount: number = 0,
): Uint8Array {
  const { gridCols, gridRows, frames, frameDelays } = comp;
  const W    = gridCols * MAP_SIZE;
  const H    = gridRows * MAP_SIZE;
  const maxF = Math.max(...frames.map(t => t.length), 1);

  const out: number[] = [];
  const w8  = (b: number)            => out.push(b & 0xff);
  const w16 = (v: number)            => { w8(v); w8(v >> 8); };
  const wArr = (a: Uint8Array | number[]) => { for (const b of a) out.push(b); };

  // ── GIF header ──────────────────────────────────────────────────────────────
  wArr([0x47,0x49,0x46,0x38,0x39,0x61]); // "GIF89a"

  // Logical Screen Descriptor
  w16(W); w16(H);
  w8(0xf7);  // global colour table present, size=7 (2^8 = 256 colours)
  w8(0);     // background colour index
  w8(0);     // pixel aspect ratio (1:1)

  // Global Colour Table
  wArr(buildColorTable());

  // ── Netscape looping extension ───────────────────────────────────────────────
  wArr([
    0x21, 0xff, 0x0b,                            // Extension, AppExt, block size
    0x4e,0x45,0x54,0x53,0x43,0x41,0x50,0x45,0x32,0x2e,0x30, // "NETSCAPE2.0"
    0x03, 0x01,                                   // sub-block id
  ]);
  w16(loopCount);                                 // loop count
  w8(0);                                          // block terminator

  // ── Frames ───────────────────────────────────────────────────────────────────
  const pixels = new Uint8Array(W * H);

  for (let fi = 0; fi < maxF; fi++) {
    const delayCentis = Math.max(1, Math.round((frameDelays[0]?.[fi] ?? 100) / 10));

    // Graphic Control Extension
    wArr([0x21, 0xf9, 0x04]);
    w8(0x09);           // disposal=2 (restore to background), transparent colour = index 0
    w16(delayCentis);   // delay in centiseconds
    w8(0);              // transparent colour index
    w8(0);              // block terminator

    // Composite tiles into the flat pixel buffer
    pixels.fill(0);
    for (let tileRow = 0; tileRow < gridRows; tileRow++) {
      for (let tileCol = 0; tileCol < gridCols; tileCol++) {
        const ti    = tileRow * gridCols + tileCol;
        const frame = frames[ti]?.[fi] ?? frames[ti]?.[0];
        if (!frame) continue;
        for (let ly = 0; ly < MAP_SIZE; ly++) {
          const gy = tileRow * MAP_SIZE + ly;
          for (let lx = 0; lx < MAP_SIZE; lx++) {
            pixels[gy * W + tileCol * MAP_SIZE + lx] = frame[ly * MAP_SIZE + lx];
          }
        }
      }
    }

    // Image Descriptor
    w8(0x2c);        // image separator
    w16(0); w16(0);  // left, top
    w16(W); w16(H);  // width, height
    w8(0);           // no local colour table, not interlaced

    // Image Data: minimum LZW code size + sub-blocks
    w8(8);           // minimum LZW code size (log2(256))
    wArr(lzwEncode(pixels));
  }

  // GIF Trailer
  w8(0x3b);

  return new Uint8Array(out);
}
