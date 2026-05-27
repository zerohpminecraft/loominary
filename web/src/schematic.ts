/**
 * Schematic export — writes Litematica v6 `.litematic` files.
 *
 * Port of SchematicExporter.java.
 *
 * Two schematic types:
 *   exportBannerSchematic — 16-wide grid of standing banners with CJK custom names
 *   exportCarpetSchematic — carpet blocks + optional shade-height terrain
 *
 * The NBT binary format is written by a minimal NbtWriter.
 * `pako.gzip()` wraps the output for the final .litematic file.
 */

import { gzip } from 'pako';

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
 * Pack block state indices into a TAG_Long_Array.
 * Uses max(4, ceil(log2(paletteSize))) bits per state, no cross-long spanning.
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
        if (bitPos + bits > 32) {
          // State straddles the 32/64-bit boundary — only allowed if no cross-long spanning.
          // (For 4-bit states in 64-bit longs: bits=4, perLong=16, no straddling)
          hiWord |= (state >>> (32 - bitPos));
        }
      } else {
        hiWord |= (state << (bitPos - 32));
      }
    }
    result.push([hiWord >>> 0, loWord >>> 0]);
  }
  return result;
}

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

/** Carpet color names in DyeColor order (matches NIBBLE_TO_MAP_BYTE index). */
const CARPET_NAMES = [
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
] as const;

/**
 * Shade-channel block types indexed by height 0/1/2.
 * 0=snow (dark), 1=grass (normal), 2=stone (bright).
 * Only relevant when exporting the full carpet+shade schematic.
 */
const SHADE_BLOCKS: BlockState[] = [
  { name: 'minecraft:snow_block'  },
  { name: 'minecraft:grass_block' },
  { name: 'minecraft:stone'       },
];

import { MAP_BYTE_TO_NIBBLE } from './carpet.js';

/**
 * Export a carpet schematic for one tile.
 *
 * @param mapColors  16384-byte map colour array.
 * @param name       Schematic name.
 * @param hasShade   Whether shade-channel rows are present.
 * @returns  Gzip-compressed .litematic bytes.
 */
export async function exportCarpetSchematic(
  mapColors: Uint8Array,
  name = 'loominary_carpet',
  _author = 'Loominary',
  hasShade = false,
): Promise<Uint8Array> {
  const SIZE = 128;

  // Build carpet block palette: air + 16 carpet colors + (if shade) 3 terrain blocks
  const paletteMap = new Map<string, number>();
  const palette: BlockState[] = [];

  function addState(bs: BlockState): number {
    const key = bs.name + (bs.props ? JSON.stringify(bs.props) : '');
    if (!paletteMap.has(key)) {
      paletteMap.set(key, palette.length);
      palette.push(bs);
    }
    return paletteMap.get(key)!;
  }

  const airIdx = addState({ name: 'minecraft:air' });
  const carpetIdxs: number[] = CARPET_NAMES.map(c => addState({ name: `minecraft:${c}_carpet` }));
  // Register shade blocks in palette when shade mode is active.
  if (hasShade) { for (const b of SHADE_BLOCKS) addState(b); }

  // Build state grid: 128 × ? × 128 (x × y × z)
  // Row 0 of the map is z=0, col 0 is x=0.
  // The carpet layer sits at y=0.
  // For shade: the terrain blocks are at varying heights (y=0 to y=2).

  const sizeX = SIZE, sizeZ = SIZE, sizeY = hasShade ? 3 : 1;
  const totalBlocks = sizeX * sizeY * sizeZ;
  const states: number[] = new Array(totalBlocks).fill(airIdx);

  for (let z = 0; z < SIZE; z++) {
    for (let x = 0; x < SIZE; x++) {
      const mapByte = mapColors[x + z * SIZE];
      const nibble  = MAP_BYTE_TO_NIBBLE[mapByte];
      if (nibble !== 255) {
        // Carpet pixel: place carpet at y=0
        const si = (0 * sizeZ + z) * sizeX + x;
        states[si] = carpetIdxs[nibble];
      }
      // Shade layer: would need shade-channel data, skipped in this overload.
    }
  }

  const packed = packBlockStates(states, palette.length);

  const w = new NbtWriter();
  writeLitematicaWrapper(w, name, _author, sizeX, sizeY, sizeZ, () => {
    w.list('BlockStatePalette', TAG.COMPOUND, palette.length, () => {
      for (const bs of palette) writePaletteEntry(w, bs);
    });
    w.longArray('BlockStates', packed);
    w.list('TileEntities', TAG.COMPOUND, 0, () => {});
  });

  return gzip(w.bytes(), { level: 9 });
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

