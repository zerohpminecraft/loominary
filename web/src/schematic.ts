/**
 * Schematic export — writes Litematica v6 `.litematic` files.
 *
 * Port of SchematicExporter.java.
 *
 * Two schematic types:
 *   exportBannerSchematic    — 16-wide grid of standing banners with CJK custom names
 *   exportCarpetSchematic    — flat carpet platform encoding the zstd payload as nibbles
 *   exportCarpetStaircase    — 3D staircase variant for shade-channel codecs
 *
 * The NBT binary format is written by a minimal NbtWriter.
 * `pako.gzip()` wraps the output for the final .litematic file.
 */

import { gzip } from 'pako';
import {
  encodeNibbles, computeHeights,
  buildLoomHeader,
  LOOM_FLAG_SHADE, LOOM_FLAG_BANNERS, LOOM_FLAG_MUX_RX, LOOM_FLAG_MUX_DONOR,
  MAX_CARPET_BYTES, MAX_SHADE_BYTES, LOOM_FIXED_HEADER,
} from './carpet.js';
import type { CodecMode } from './codec-mode.js';

// ─── MC 1.21.4 data version ──────────────────────────────────────────────────

const MC_DATA_VERSION = 4189; // Minecraft 1.21.4

// ─── NBT tag IDs ─────────────────────────────────────────────────────────────

const TAG = {
  END:      0,
  BYTE:     1,
  SHORT:    2,
  INT:      3,
  LONG:     4,
  FLOAT:    5,
  DOUBLE:   6,
  BYTE_ARR: 7,
  STRING:   8,
  LIST:     9,
  COMPOUND: 10,
  INT_ARR:  11,
  LONG_ARR: 12,
} as const;

// ─── NbtWriter ────────────────────────────────────────────────────────────────

class NbtWriter {
  private buf: number[] = [];

  private u8(v: number): void { this.buf.push(v & 0xff); }

  private u16be(v: number): void {
    this.u8(v >> 8); this.u8(v);
  }

  private u32be(v: number): void {
    this.u8(v >>> 24); this.u8(v >>> 16); this.u8(v >>> 8); this.u8(v);
  }

  private u64be(hi: number, lo: number): void {
    this.u32be(hi); this.u32be(lo);
  }

  private str(s: string): void {
    const enc = new TextEncoder().encode(s);
    this.u16be(enc.length);
    for (const b of enc) this.u8(b);
  }

  private header(type: number, name: string): void {
    this.u8(type);
    this.str(name);
  }

  byte(name: string, v: number): void     { this.header(TAG.BYTE,  name); this.u8(v); }
  short(name: string, v: number): void    { this.header(TAG.SHORT, name); this.u16be(v); }
  int(name: string, v: number): void      { this.header(TAG.INT,   name); this.u32be(v); }

  long(name: string, hiLo: [number, number]): void {
    this.header(TAG.LONG, name);
    this.u64be(hiLo[0], hiLo[1]);
  }

  string(name: string, v: string): void   { this.header(TAG.STRING, name); this.str(v); }

  longArray(name: string, longs: Array<[number, number]>): void {
    this.header(TAG.LONG_ARR, name);
    this.u32be(longs.length);
    for (const [hi, lo] of longs) this.u64be(hi, lo);
  }

  compound(name: string, cb: () => void): void {
    this.header(TAG.COMPOUND, name);
    cb();
    this.u8(TAG.END);
  }

  list(name: string, elemType: number, count: number, cb: () => void): void {
    this.header(TAG.LIST, name);
    this.u8(elemType);
    this.u32be(count);
    cb();
  }

  /** Write a TAG_Compound element inside a TAG_List (no header/name). */
  listCompound(cb: () => void): void {
    cb();
    this.u8(TAG.END);
  }

  /** Write a TAG_String element inside a TAG_List (no header/name). */
  listString(v: string): void { this.str(v); }

  bytes(): Uint8Array { return new Uint8Array(this.buf); }
}

// ─── Block palette helpers ────────────────────────────────────────────────────

interface BlockState {
  name: string;
  props?: Record<string, string>;
}

function writePaletteEntry(w: NbtWriter, bs: BlockState): void {
  w.listCompound(() => {
    w.string('Name', bs.name);
    if (bs.props && Object.keys(bs.props).length > 0) {
      w.compound('Properties', () => {
        for (const [k, v] of Object.entries(bs.props!)) w.string(k, v);
      });
    }
  });
}

/**
 * Pack block state indices, no cross-long spanning (banner schematics).
 * Uses max(4, ceil(log2(paletteSize))) bits per state.
 */
function packBlockStates(states: number[], paletteSize: number): Array<[number, number]> {
  const bits     = Math.max(4, Math.ceil(Math.log2(Math.max(paletteSize, 2))));
  const perLong  = Math.floor(64 / bits);
  const numLongs = Math.ceil(states.length / perLong);
  const mask     = (1 << bits) - 1;
  const result: Array<[number, number]> = [];

  for (let li = 0; li < numLongs; li++) {
    let hiWord = 0, loWord = 0;
    for (let j = 0; j < perLong; j++) {
      const si = li * perLong + j;
      if (si >= states.length) break;
      const state = states[si] & mask;
      const bitPos = j * bits;
      if (bitPos < 32) {
        loWord |= (state << bitPos);
        if (bitPos + bits > 32) hiWord |= (state >>> (32 - bitPos));
      } else {
        hiWord |= (state << (bitPos - 32));
      }
    }
    result.push([hiWord >>> 0, loWord >>> 0]);
  }
  return result;
}

/**
 * Pack block indices using Litematica's spanning format (LitematicaBitArray).
 * Entries are at consecutive bit positions and MAY cross long boundaries.
 * Used for carpet schematics — matches SchematicExporter.packBlockIndices() in Java.
 */
function packBlockIndicesSpanning(indices: number[], bitsPerEntry: number): Array<[number, number]> {
  const totalBits = indices.length * bitsPerEntry;
  const numLongs  = Math.ceil(totalBits / 64);
  // [hi, lo]: bits 32–63 = hi, bits 0–31 = lo (big-endian NBT long)
  const longs: [number, number][] = Array.from({ length: numLongs }, () => [0, 0]);

  for (let i = 0; i < indices.length; i++) {
    const bitStart = i * bitsPerEntry;
    const li       = Math.floor(bitStart / 64);
    const bitOff   = bitStart % 64;   // bit position within 64-bit long (0 = LSB)
    const value    = indices[i];

    if (bitOff < 32) {
      longs[li][1] = (longs[li][1] | (value << bitOff)) >>> 0;
      if (bitOff + bitsPerEntry > 32)
        longs[li][0] = (longs[li][0] | (value >>> (32 - bitOff))) >>> 0;
    } else {
      longs[li][0] = (longs[li][0] | (value << (bitOff - 32))) >>> 0;
    }

    const bitsInFirst = 64 - bitOff;
    if (bitsInFirst < bitsPerEntry && li + 1 < numLongs)
      longs[li + 1][1] = (longs[li + 1][1] | (value >>> bitsInFirst)) >>> 0;
  }

  return longs;
}

// ─── Carpet palette (DyeColor order — matches NIBBLE_TO_MAP_BYTE in carpet.ts) ─

const CARPET_DYE_NAMES = [
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
] as const;

// ─── Litematica v6 wrapper ────────────────────────────────────────────────────

function writeLitematicaWrapper(
  w: NbtWriter,
  schematicName: string,
  author: string,
  regionSizeX: number,
  regionSizeY: number,
  regionSizeZ: number,
  writeRegion: () => void,
): void {
  const now = Date.now();
  const timeHi = Math.floor(now / 0x100000000);
  const timeLo = now >>> 0;

  w.compound('Schematic', () => {
    w.int('Version', 6);
    w.int('MinecraftDataVersion', MC_DATA_VERSION);
    w.compound('Metadata', () => {
      w.string('Name', schematicName);
      w.string('Author', author);
      w.string('Description', 'Created by Loominary Web');
      w.compound('EnclosingSize', () => {
        w.int('x', regionSizeX);
        w.int('y', regionSizeY);
        w.int('z', regionSizeZ);
      });
      w.int('RegionCount', 1);
      w.long('TimeCreated',  [timeHi, timeLo]);
      w.long('TimeModified', [timeHi, timeLo]);
    });
    w.compound('Regions', () => {
      w.compound(schematicName, () => {
        w.compound('Position', () => { w.int('x',0); w.int('y',0); w.int('z',0); });
        w.compound('Size', () => {
          w.int('x', regionSizeX);
          w.int('y', regionSizeY);
          w.int('z', regionSizeZ);
        });
        writeRegion();
        w.list('Entities',          TAG.COMPOUND, 0, () => {});
        w.list('PendingBlockTicks', TAG.COMPOUND, 0, () => {});
        w.list('PendingFluidTicks', TAG.COMPOUND, 0, () => {});
      });
    });
  });
}

// ─── Banner schematic ─────────────────────────────────────────────────────────

const BANNER_COLS = 16;

/**
 * Export a banner schematic.
 *
 * @param chunks  Array of chunk strings in order ("NN<48 CJK chars>").
 * @param name    Schematic name (used as file name prefix + region name).
 * @param author  Author string.
 * @returns  Gzip-compressed NBT bytes (write to .litematic file).
 */
export async function exportBannerSchematic(
  chunks: string[],
  name = 'loominary_banners',
  author = 'Loominary',
): Promise<Uint8Array> {
  const numBanners = chunks.length;
  const numRows    = Math.ceil(numBanners / BANNER_COLS);
  const sizeX      = BANNER_COLS;
  const sizeY      = 1;
  const sizeZ      = numRows;
  const totalBlocks = sizeX * sizeY * sizeZ;

  const palette: BlockState[] = [
    { name: 'minecraft:air' },
    { name: 'minecraft:white_banner', props: { rotation: '0' } },
  ];

  // State array: 0=air, 1=banner at each occupied position.
  const states: number[] = new Array(totalBlocks).fill(0);
  const tileEntities: Array<{ x: number; y: number; z: number; name: string }> = [];

  for (let i = 0; i < numBanners; i++) {
    const bx = i % BANNER_COLS;
    const bz = Math.floor(i / BANNER_COLS);
    // YZX ordering: (y * sizeZ + z) * sizeX + x
    const stateIdx = (0 * sizeZ + bz) * sizeX + bx;
    states[stateIdx] = 1;
    tileEntities.push({ x: bx, y: 0, z: bz, name: chunks[i] });
  }

  const packed = packBlockStates(states, palette.length);

  const w = new NbtWriter();
  writeLitematicaWrapper(w, name, author, sizeX, sizeY, sizeZ, () => {
    // BlockStatePalette
    w.list('BlockStatePalette', TAG.COMPOUND, palette.length, () => {
      for (const bs of palette) writePaletteEntry(w, bs);
    });
    // BlockStates
    w.longArray('BlockStates', packed);
    // TileEntities
    w.list('TileEntities', TAG.COMPOUND, tileEntities.length, () => {
      for (const te of tileEntities) {
        w.listCompound(() => {
          w.string('id', 'minecraft:banner');
          w.int('x', te.x);
          w.int('y', te.y);
          w.int('z', te.z);
          w.string('CustomName', JSON.stringify({ text: te.name }));
          w.list('Patterns', TAG.COMPOUND, 0, () => {});
        });
      }
    });
  });

  return gzip(w.bytes(), { level: 9 });
}

// ─── Carpet schematic ─────────────────────────────────────────────────────────

/**
 * Build the shared carpet block palette: index 0=air, indices 1–16=carpets
 * in DyeColor order (white=1 … black=16).  Returns the palette array.
 */
function buildCarpetPalette(): BlockState[] {
  const palette: BlockState[] = [{ name: 'minecraft:air' }];
  for (const name of CARPET_DYE_NAMES) palette.push({ name: `minecraft:${name}_carpet` });
  return palette;
}

/**
 * Export a flat carpet schematic: a 128 × 1 × carpetRows region of carpet
 * blocks encoding the carpet channel of the LOOM payload.
 *
 * Port of SchematicExporter.exportCarpetTile().
 */
function buildFlatCarpetSchematic(
  nibbles: Uint8Array,
  carpetBytes: number,
  name: string,
  author: string,
): Uint8Array {
  const carpetRows   = Math.ceil(carpetBytes * 2 / 128);
  const nibblesUsed  = carpetBytes * 2;
  const totalBlocks  = 128 * carpetRows;

  const palette      = buildCarpetPalette();
  const paletteSize  = palette.length; // 17
  const bitsPerEntry = Math.max(2, Math.ceil(Math.log2(paletteSize)));  // 5

  // Block indices in YZX order: y=0 always (flat), z = row, x = col
  const blockIndices = new Array<number>(totalBlocks).fill(0);
  for (let z = 0; z < carpetRows; z++) {
    for (let x = 0; x < 128; x++) {
      const nibbleIdx = z * 128 + x;
      if (nibbleIdx < nibblesUsed)
        blockIndices[z * 128 + x] = 1 + nibbles[nibbleIdx];  // palette 1-based
    }
  }

  const packed = packBlockIndicesSpanning(blockIndices, bitsPerEntry);

  const w = new NbtWriter();
  writeLitematicaWrapper(w, name, author, 128, 1, carpetRows, () => {
    w.list('BlockStatePalette', TAG.COMPOUND, paletteSize, () => {
      for (const bs of palette) writePaletteEntry(w, bs);
    });
    w.longArray('BlockStates', packed);
    w.list('TileEntities', TAG.COMPOUND, 0, () => {});
  });

  return w.bytes();
}

/**
 * Export a staircase carpet schematic: a 128 × regionY × 128 region where
 * each (x, z) column has carpets stacked from y=0 to y=heights[x][z].
 * The top carpet carries the data nibble; fillers below are white carpet.
 *
 * Port of SchematicExporter.exportCarpetStaircase().
 */
function buildStaircaseCarpetSchematic(
  nibbles: Uint8Array,
  carpetBytes: number,
  heights: number[][],
  name: string,
  author: string,
): Uint8Array {
  let maxHeight = 0;
  for (let col = 0; col < 128; col++)
    for (let row = 0; row < 128; row++)
      if (heights[col][row] > maxHeight) maxHeight = heights[col][row];

  const regionY      = maxHeight + 1;
  const nibblesUsed  = Math.min(carpetBytes * 2, nibbles.length);
  const totalVoxels  = regionY * 128 * 128;

  const palette      = buildCarpetPalette();
  const paletteSize  = palette.length; // 17
  const bitsPerEntry = Math.max(2, Math.ceil(Math.log2(paletteSize)));  // 5

  // YZX ordering: idx = (y * 128 + z) * 128 + x
  const blockIndices = new Array<number>(totalVoxels).fill(0);
  for (let y = 0; y < regionY; y++) {
    for (let z = 0; z < 128; z++) {
      for (let x = 0; x < 128; x++) {
        const nibbleIdx = z * 128 + x;
        const h         = heights[x][z];
        if (y <= h) {
          const nibble = (y === h && nibbleIdx < nibblesUsed) ? nibbles[nibbleIdx] : 0;
          blockIndices[(y * 128 + z) * 128 + x] = 1 + nibble;
        }
      }
    }
  }

  const packed = packBlockIndicesSpanning(blockIndices, bitsPerEntry);

  const w = new NbtWriter();
  writeLitematicaWrapper(w, name, author, 128, regionY, 128, () => {
    w.list('BlockStatePalette', TAG.COMPOUND, paletteSize, () => {
      for (const bs of palette) writePaletteEntry(w, bs);
    });
    w.longArray('BlockStates', packed);
    w.list('TileEntities', TAG.COMPOUND, 0, () => {});
  });

  return w.bytes();
}

/** Guest descriptor shape matching LoomHeader's guestDescs[][] layout. */
export interface GuestDesc {
  tCol: number; tRow: number; tOffset: number; tLen: number;
}

/**
 * Export a carpet schematic for one tile.
 *
 * Takes the zstd-compressed payload (carpetCompressedB64 from TileData),
 * wraps it in a LOOM header, encodes as carpet nibbles, and generates either
 * a flat or staircase litematic depending on whether the codec uses the shade
 * channel.  Mirrors SchematicExporter.exportCarpetTile / exportCarpetStaircase.
 *
 * @param carpetCompressedB64  Base64-encoded payload.
 *   - Normal/receiver tiles: the tile's own segment (muxCargoB64 or full payload).
 *   - Donor tiles: own frame + guest bytes concatenated (muxCargoB64).
 * @param name                 Schematic name (region name + file stem)
 * @param author               Author string embedded in NBT metadata
 * @param tileCol              Tile column in the grid (for LOOM header)
 * @param tileRow              Tile row in the grid (for LOOM header)
 * @param codec                Active codec mode (drives shade/banner channel split)
 * @param muxRole              Mux role of this tile (default: 'normal')
 * @param muxTotalBytes        Receivers only: full payload length (sets totalBytes in LOOM header)
 * @param muxOwnBytes          Donors only: own-frame byte count within the payload
 *                             (sets ownBytes in LOOM header; the rest are guest bytes)
 * @param muxGuestDescs        CARPET/CARPET_SHADE donors only: guest descriptors to embed
 *                             in the LOOM header (used by the decoder to route guest segments)
 */
export async function exportCarpetSchematic(
  carpetCompressedB64: string,
  name = 'loominary_carpet',
  author = 'Loominary',
  tileCol = 0,
  tileRow = 0,
  codec: CodecMode = 'CARPET_BANNERS_SHADE',
  muxRole: 'normal' | 'receiver' | 'donor' = 'normal',
  muxTotalBytes?: number,
  muxOwnBytes?: number,
  muxGuestDescs?: GuestDesc[],
): Promise<Uint8Array> {
  const useShade   = codec === 'CARPET_SHADE' || codec === 'CARPET_BANNERS_SHADE' || codec === 'CARPET_SHADE_BANNERS';
  const useBanners = codec === 'CARPET_BANNERS' || codec === 'CARPET_BANNERS_SHADE' || codec === 'CARPET_SHADE_BANNERS';

  // Decode the stored compressed payload
  const compressed = Uint8Array.from(atob(carpetCompressedB64), c => c.charCodeAt(0));

  // Build LOOM flags: base channel flags + mux role flags.
  // CARPET/CARPET_SHADE donors embed guest descriptors in the header (MUX_DONOR flag).
  // CARPET_BANNERS donors use MG routing banners instead (no MUX_DONOR flag).
  let flags = (useShade ? LOOM_FLAG_SHADE : 0) | (useBanners ? LOOM_FLAG_BANNERS : 0);
  if (muxRole === 'receiver') flags |= LOOM_FLAG_MUX_RX;
  if (muxRole === 'donor' && !useBanners) flags |= LOOM_FLAG_MUX_DONOR;

  // Build LOOM header with correct ownBytes/totalBytes.
  // Receivers: ownBytes = own segment length, totalBytes = full payload length.
  // Donors: ownBytes = own frame length (guest bytes follow in the same cargo buffer).
  // Normal: ownBytes = totalBytes = full compressed length (no mux).
  const ownBytes   = muxRole === 'donor' ? (muxOwnBytes ?? compressed.length) : compressed.length;
  const totalBytes = muxRole === 'receiver' ? (muxTotalBytes ?? compressed.length) : ownBytes;
  const guestDescArrays = muxGuestDescs?.map(d => [d.tCol, d.tRow, d.tOffset, d.tLen]);
  const loomHeader = buildLoomHeader(flags, tileCol, tileRow, ownBytes, totalBytes, guestDescArrays);

  const cargo = new Uint8Array(loomHeader.length + compressed.length);
  cargo.set(loomHeader, 0);
  cargo.set(compressed, loomHeader.length);

  // Split cargo into channels
  const carpetBytes = Math.min(cargo.length, MAX_CARPET_BYTES);
  const shadeBytes  = useShade && cargo.length > MAX_CARPET_BYTES
    ? Math.min(cargo.length - carpetBytes, MAX_SHADE_BYTES)
    : 0;

  // Encode carpet channel as nibbles (one nibble per pixel in the carpet region)
  const nibbles = encodeNibbles(cargo, carpetBytes);

  let nbtBytes: Uint8Array;
  if (shadeBytes > 0) {
    const shadeData = cargo.slice(carpetBytes, carpetBytes + shadeBytes);
    const heights   = computeHeights(shadeData, shadeBytes);
    nbtBytes = buildStaircaseCarpetSchematic(nibbles, carpetBytes, heights, name, author);
  } else {
    nbtBytes = buildFlatCarpetSchematic(nibbles, carpetBytes, name, author);
  }

  return gzip(nbtBytes, { level: 9 });
}

// ─── Download helper ──────────────────────────────────────────────────────────

/**
 * Trigger a browser download of a .litematic file.
 */
export function downloadLitematic(bytes: Uint8Array, filename: string): void {
  const blob = new Blob([bytes], { type: 'application/octet-stream' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

