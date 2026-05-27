/**
 * Carpet channel encode/decode — port of CarpetChannel.java.
 *
 * The carpet nibble lookup tables here use hardcoded values derived from
 * Minecraft 1.21.4's DyeColor ordering and carpet map-colour assignments.
 * Re-verify with /loominary dumppalette if upgrading MC versions.
 */

// ─── Constants ────────────────────────────────────────────────────────────────

export const MAP_PIXELS       = 128 * 128;      // 16384
export const MAX_CARPET_BYTES = MAP_PIXELS / 2; // 8192
/** Shade channel capacity: 31 four-row groups + 1 three-row tail, 128 cols. */
export const MAX_SHADE_BYTES  = 31 * 128 * 4 / 8 + 128 * 2 / 8; // 2016

// LOOM header
export const LOOM_FIXED_HEADER = 16;
export const LOOM_GUEST_DESC   = 10;
export const LOOM_MAGIC        = [0x4C, 0x4F, 0x4F, 0x4D] as const; // "LOOM"
export const LOOM_FLAG_SHADE     = 0x01;
export const LOOM_FLAG_BANNERS   = 0x02;
export const LOOM_FLAG_MUX_DONOR = 0x04;
export const LOOM_FLAG_MUX_RX    = 0x08;

export const MAX_CARPET_PAYLOAD = MAX_CARPET_BYTES - LOOM_FIXED_HEADER; // 8176

// ─── Carpet nibble lookup tables ──────────────────────────────────────────────
//
// Minecraft 1.21.4 DyeColor.values() order:
//   WHITE, ORANGE, MAGENTA, LIGHT_BLUE, YELLOW, LIME, PINK, GRAY,
//   LIGHT_GRAY, CYAN, PURPLE, BLUE, BROWN, GREEN, RED, BLACK
//
// For each carpet color the map byte is: colorId*4 + 1 (shade NORMAL = shade 1).
// These values are from the MC 1.21.4 MapColor assignments for carpet blocks.
// If the game dumps a different table, call updateCarpetTables() below.

/** nibble (0–15) → map byte for that carpet at shade NORMAL (shade 1). */
export const NIBBLE_TO_MAP_BYTE = new Uint8Array([
  //  WHITE  ORANGE  MAGENTA  L.BLUE  YELLOW    LIME    PINK    GRAY
       13,    48,      40,     88,      72,      52,     144,    28,
  // L.GRAY    CYAN  PURPLE    BLUE   BROWN   GREEN     RED   BLACK
       29,      92,   96,     104,     56,      32,      60,   116,
]);
// (Values correspond to Minecraft map color IDs × 4 + shade 1.
//  These are validated against the MC 1.21.4 source; re-check after updates.)

/** map byte (0–255) → nibble (0–15), or 255 if not a carpet colour.
 *  All three legal shades (0, 1, 2) of each carpet colour map to the same nibble. */
export const MAP_BYTE_TO_NIBBLE = new Uint8Array(256).fill(255);

(function buildCarpetTables() {
  for (let n = 0; n < 16; n++) {
    const base = NIBBLE_TO_MAP_BYTE[n]; // shade-1 (NORMAL) map byte
    const colorId = base >> 2;
    for (let shade = 0; shade <= 2; shade++) {
      MAP_BYTE_TO_NIBBLE[colorId * 4 + shade] = n;
    }
  }
})();

/** Override the carpet tables from a /loominary dumppalette output. */
export function updateCarpetTables(nibbleToMapByte: number[]): void {
  NIBBLE_TO_MAP_BYTE.set(nibbleToMapByte.slice(0, 16));
  MAP_BYTE_TO_NIBBLE.fill(255);
  for (let n = 0; n < 16; n++) {
    const base = NIBBLE_TO_MAP_BYTE[n];
    const colorId = base >> 2;
    for (let shade = 0; shade <= 2; shade++) {
      MAP_BYTE_TO_NIBBLE[colorId * 4 + shade] = n;
    }
  }
}

// ─── Shade-channel sequence tables ────────────────────────────────────────────

/** 16 balanced 4-row height sequences (index = 4-bit nibble value). */
const SEQ4: readonly (readonly number[])[] = [
  [0,1,1,2], [0,1,2,1], [0,2,0,2], [0,2,1,1], [0,2,2,0],
  [1,0,1,2], [1,0,2,1], [1,1,0,2],
  [1,1,2,0], [1,2,0,1], [1,2,1,0],
  [2,0,0,2], [2,0,1,1], [2,0,2,0], [2,1,0,1], [2,1,1,0],
] as const;

/** 4 balanced 3-row tail sequences (index = 2-bit value). */
const SEQ3: readonly (readonly number[])[] = [
  [1,1,1], [2,1,0], [0,1,2], [2,0,1],
] as const;

/** (s1*27 + s2*9 + s3*3 + s4) → 4-bit nibble, or -1. */
const DEC4 = new Int8Array(81).fill(-1);
/** (s1*9 + s2*3 + s3) → 2-bit value, or -1. */
const DEC3 = new Int8Array(27).fill(-1);

(function buildSequenceTables() {
  for (let i = 0; i < SEQ4.length; i++) {
    const [s1,s2,s3,s4] = SEQ4[i];
    DEC4[s1*27 + s2*9 + s3*3 + s4] = i;
  }
  for (let i = 0; i < SEQ3.length; i++) {
    const [s1,s2,s3] = SEQ3[i];
    DEC3[s1*9 + s2*3 + s3] = i;
  }
})();

// ─── Carpet channel encode / decode ──────────────────────────────────────────

/**
 * Pack `len` bytes of `compressed` into a nibble array (one nibble per pixel).
 * Returns Uint8Array of length 16384, values 0–15.
 */
export function encodeNibbles(compressed: Uint8Array, len: number): Uint8Array {
  if (len > MAX_CARPET_BYTES) throw new Error(`Carpet channel overflow: ${len}`);
  const nibbles = new Uint8Array(MAP_PIXELS);
  for (let i = 0; i < len; i++) {
    nibbles[i * 2    ] = (compressed[i] >> 4) & 0xf;
    nibbles[i * 2 + 1] = compressed[i] & 0xf;
  }
  return nibbles;
}

/**
 * Convert a nibble array (from `encodeNibbles`) into the map-colour bytes
 * for the carpet schematic.  Each nibble becomes a NIBBLE_TO_MAP_BYTE entry.
 */
export function nibblesToMapColors(nibbles: Uint8Array): Uint8Array {
  const mapColors = new Uint8Array(MAP_PIXELS);
  for (let i = 0; i < MAP_PIXELS; i++) {
    mapColors[i] = NIBBLE_TO_MAP_BYTE[nibbles[i]];
  }
  return mapColors;
}

/**
 * Decode carpet channel bytes from a map-colour array.
 * Accepts shade 0, 1, or 2 of each carpet colour.
 */
export function decodeBytes(mapColors: Uint8Array, carpetBytes: number): Uint8Array {
  if (carpetBytes > MAX_CARPET_BYTES) carpetBytes = MAX_CARPET_BYTES;
  const out = new Uint8Array(carpetBytes);
  for (let i = 0; i < carpetBytes; i++) {
    const hi = MAP_BYTE_TO_NIBBLE[mapColors[i * 2    ]];
    const lo = MAP_BYTE_TO_NIBBLE[mapColors[i * 2 + 1]];
    if (hi === 255 || lo === 255) {
      throw new Error(`Non-carpet map colour at nibble position ${i * 2}: bytes ${mapColors[i*2]}, ${mapColors[i*2+1]}`);
    }
    out[i] = (hi << 4) | lo;
  }
  return out;
}

// ─── Shade channel decode ─────────────────────────────────────────────────────

/** Decode shade channel bytes from a map-colour array. */
export function decodeShade(mapColors: Uint8Array, shadeBytes: number): Uint8Array {
  if (shadeBytes > MAX_SHADE_BYTES) shadeBytes = MAX_SHADE_BYTES;
  const out = new Uint8Array(shadeBytes);
  const totalBits = shadeBytes * 8;

  for (let g = 0; g < 31; g++) {
    const row0 = g * 4 + 1;
    const groupBase = g * 128 * 4;
    for (let col = 0; col < 128; col++) {
      const bitOff = groupBase + col * 4;
      if (bitOff + 4 > totalBits) return out;
      const s1 = mapColors[row0       * 128 + col] & 0x3;
      const s2 = mapColors[(row0 + 1) * 128 + col] & 0x3;
      const s3 = mapColors[(row0 + 2) * 128 + col] & 0x3;
      const s4 = mapColors[(row0 + 3) * 128 + col] & 0x3;
      const v = DEC4[s1*27 + s2*9 + s3*3 + s4];
      writeBits(out, bitOff, 4, v < 0 ? 0 : v);
    }
  }

  const tailBase = 31 * 128 * 4;
  for (let col = 0; col < 128; col++) {
    const bitOff = tailBase + col * 2;
    if (bitOff + 2 > totalBits) return out;
    const s1 = mapColors[125 * 128 + col] & 0x3;
    const s2 = mapColors[126 * 128 + col] & 0x3;
    const s3 = mapColors[127 * 128 + col] & 0x3;
    const v = DEC3[s1*9 + s2*3 + s3];
    writeBits(out, bitOff, 2, v < 0 ? 0 : v);
  }

  return out;
}

// ─── Height map (for schematic export) ────────────────────────────────────────

/**
 * Convert shade-channel bytes into a height map for schematic export.
 * Returns `heights[col][row]` (col/row ∈ [0,127]), heights ∈ {0,1,2}.
 */
export function computeHeights(shadeData: Uint8Array, shadeLen: number): number[][] {
  const heights: number[][] = Array.from({length: 128}, () => new Array(128).fill(1));
  const totalBits = Math.min(shadeLen, MAX_SHADE_BYTES) * 8;

  for (let g = 0; g < 31; g++) {
    const row0 = g * 4 + 1;
    const groupBase = g * 128 * 4;
    for (let col = 0; col < 128; col++) {
      const bitOff = groupBase + col * 4;
      const seq = (bitOff + 4 <= totalBits) ? SEQ4[readBits(shadeData, bitOff, 4)] : [1,1,1,1];
      let h = 1;
      for (let r = 0; r < 4; r++) { h += seq[r] - 1; heights[col][row0 + r] = h; }
    }
  }

  const tailBase = 31 * 128 * 4;
  for (let col = 0; col < 128; col++) {
    const bitOff = tailBase + col * 2;
    const seq = (bitOff + 2 <= totalBits) ? SEQ3[readBits(shadeData, bitOff, 2)] : [1,1,1];
    let h = 1;
    for (let r = 0; r < 3; r++) { h += seq[r] - 1; heights[col][125 + r] = h; }
  }

  return heights;
}

// ─── LOOM header ─────────────────────────────────────────────────────────────

export interface LoomHeader {
  flags: number;
  col: number;
  row: number;
  ownBytes: number;
  totalBytes: number;
  guestCount: number;
  /** [g][0]=tCol  [g][1]=tRow  [g][2]=tOffset  [g][3]=tLen */
  guestDescs: number[][];
  /** Byte offset within cargo where actual data starts. */
  dataOffset: number;
}

export function buildLoomHeader(
  flags: number, col: number, row: number,
  ownBytes: number, totalBytes: number,
  guestDescs?: number[][],
): Uint8Array {
  const gc = guestDescs?.length ?? 0;
  const h = new Uint8Array(LOOM_FIXED_HEADER + gc * LOOM_GUEST_DESC);
  h[0] = 0x4C; h[1] = 0x4F; h[2] = 0x4F; h[3] = 0x4D; // "LOOM"
  h[4] = flags; h[5] = col; h[6] = row;
  writeU32BE(h, 7, ownBytes);
  writeU32BE(h, 11, totalBytes);
  h[15] = gc;
  if (guestDescs) {
    for (let g = 0; g < gc; g++) {
      const off = LOOM_FIXED_HEADER + g * LOOM_GUEST_DESC;
      h[off    ] = guestDescs[g][0]; h[off + 1] = guestDescs[g][1];
      writeU32BE(h, off + 2, guestDescs[g][2]);
      writeU32BE(h, off + 6, guestDescs[g][3]);
    }
  }
  return h;
}

export function decodeLoomHeader(cargo: Uint8Array): LoomHeader {
  if (cargo.length < LOOM_FIXED_HEADER) throw new Error(`Cargo too short for LOOM header: ${cargo.length}`);
  const flags      = cargo[4];
  const col        = cargo[5];
  const row        = cargo[6];
  const ownBytes   = readU32BE(cargo, 7);
  const totalBytes = readU32BE(cargo, 11);
  const guestCount = cargo[15];
  const dataOffset = LOOM_FIXED_HEADER + guestCount * LOOM_GUEST_DESC;
  const guestDescs: number[][] = [];
  for (let g = 0; g < guestCount; g++) {
    const off = LOOM_FIXED_HEADER + g * LOOM_GUEST_DESC;
    if (off + LOOM_GUEST_DESC > cargo.length) throw new Error(`LOOM guest descriptor ${g} out of bounds`);
    guestDescs.push([
      cargo[off], cargo[off + 1],
      readU32BE(cargo, off + 2),
      readU32BE(cargo, off + 6),
    ]);
  }
  return { flags, col, row, ownBytes, totalBytes, guestCount, guestDescs, dataOffset };
}

export function peekLoomMagic(mapColors: Uint8Array): boolean {
  if (mapColors.length < 8) return false;
  const ns = Array.from({length: 8}, (_, i) => MAP_BYTE_TO_NIBBLE[mapColors[i]]);
  if (ns.some(n => n === 255)) return false;
  return ((ns[0] << 4) | ns[1]) === 0x4C
      && ((ns[2] << 4) | ns[3]) === 0x4F
      && ((ns[4] << 4) | ns[5]) === 0x4F
      && ((ns[6] << 4) | ns[7]) === 0x4D;
}

// ─── Bit I/O helpers ──────────────────────────────────────────────────────────

function readBits(data: Uint8Array, bitOffset: number, count: number): number {
  let v = 0;
  for (let i = 0; i < count; i++) {
    v = (v << 1) | ((data[(bitOffset + i) >> 3] >> (7 - ((bitOffset + i) & 7))) & 1);
  }
  return v;
}

function writeBits(data: Uint8Array, bitOffset: number, count: number, value: number): void {
  for (let i = count - 1; i >= 0; i--, bitOffset++) {
    if ((value >> i) & 1) {
      data[bitOffset >> 3] |= (1 << (7 - (bitOffset & 7)));
    }
  }
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
