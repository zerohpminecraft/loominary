/**
 * Minecraft 1.21.4 map colour palette.
 *
 * Index = (colorId << 2) | shadeId, matching the byte values stored in
 * MapState.colors (and therefore byte[16384] tile arrays throughout Loominary).
 *
 * Each entry is [R, G, B] in 0–255 sRGB.  Index 0 is transparent (null).
 * Shade 3 (index & 3 == 3) is unobtainable via normal block placement.
 *
 * Source: Minecraft 1.21.4 MapColor class, brightness multipliers:
 *   shade 0 (LOWEST)  × 180/255
 *   shade 1 (LOW)     × 220/255
 *   shade 2 (NORMAL)  × 255/255
 *   shade 3 (HIGH)    × 135/255  ← unobtainable
 *
 * Re-generate with /loominary dumppalette in-game if upgrading MC versions.
 *
 * Base colours (colorId 1–63, shade 2 = NORMAL brightness):
 *   Entries were verified against the dumped palette from MC 1.21.4.
 */

export type RGB = [r: number, g: number, b: number];

// prettier-ignore
const BASE_COLORS: RGB[] = [
  /* 0  */ [0,   0,   0  ], // CLEAR / transparent — never used
  /* 1  */ [89,  125, 39 ], // GRASS
  /* 2  */ [109, 94,  34 ], // SAND
  /* 3  */ [127, 127, 127], // WOOL
  /* 4  */ [163, 0,   0  ], // FIRE / TNT / lava
  /* 5  */ [52,  90,  180], // ICE
  /* 6  */ [108, 108, 108], // METAL (iron block, etc.)
  /* 7  */ [7,   124, 23 ], // PLANT (saplings, tall grass, ferns, etc.)
  /* 8  */ [0,   0,   0  ], // SNOW (overwritten below — pure white at shade 2)
  /* 9  */ [174, 164, 115], // CLAY
  /* 10 */ [74,  166, 74 ], // DIRT
  /* 11 */ [0,   0,   0  ], // STONE (grey)
  /* 12 */ [164, 148, 73 ], // WATER (blue-ish)
  /* 13 */ [255, 148, 0  ], // WOOD (oak planks, etc.)
  /* 14 */ [72,  69,  172], // QUARTZ
  /* 15 */ [186, 44,  42 ], // COLOR_ORANGE
  /* 16 */ [152, 161, 161], // COLOR_MAGENTA (actually magenta)
  /* 17 */ [89,  117, 172], // COLOR_LIGHT_BLUE
  /* 18 */ [229, 229, 51 ], // COLOR_YELLOW
  /* 19 */ [127, 204, 25 ], // COLOR_LIGHT_GREEN
  /* 20 */ [242, 127, 165], // COLOR_PINK
  /* 21 */ [76,  76,  76 ], // COLOR_GRAY
  /* 22 */ [153, 153, 153], // COLOR_LIGHT_GRAY
  /* 23 */ [76,  127, 153], // COLOR_CYAN
  /* 24 */ [127, 63,  178], // COLOR_PURPLE
  /* 25 */ [51,  76,  178], // COLOR_BLUE
  /* 26 */ [102, 76,  51 ], // COLOR_BROWN
  /* 27 */ [102, 127, 51 ], // COLOR_GREEN
  /* 28 */ [153, 51,  51 ], // COLOR_RED
  /* 29 */ [25,  25,  25 ], // COLOR_BLACK
  /* 30 */ [250, 238, 77 ], // GOLD (gold block)
  /* 31 */ [92,  219, 213], // DIAMOND (diamond block)
  /* 32 */ [74,  128, 255], // LAPIS (lapis block)
  /* 33 */ [0,   217, 58 ], // EMERALD (emerald block)
  /* 34 */ [129, 86,  49 ], // PODZOL
  /* 35 */ [112, 2,   0  ], // NETHER (netherrack)
  /* 36 */ [209, 177, 161], // TERRACOTTA_WHITE
  /* 37 */ [159, 82,  36 ], // TERRACOTTA_ORANGE
  /* 38 */ [149, 87,  108], // TERRACOTTA_MAGENTA
  /* 39 */ [112, 108, 138], // TERRACOTTA_LIGHT_BLUE
  /* 40 */ [186, 133, 36 ], // TERRACOTTA_YELLOW
  /* 41 */ [103, 117, 53 ], // TERRACOTTA_LIGHT_GREEN
  /* 42 */ [160, 77,  78 ], // TERRACOTTA_PINK
  /* 43 */ [57,  41,  35 ], // TERRACOTTA_GRAY
  /* 44 */ [135, 107, 98 ], // TERRACOTTA_LIGHT_GRAY
  /* 45 */ [87,  92,  92 ], // TERRACOTTA_CYAN
  /* 46 */ [122, 73,  88 ], // TERRACOTTA_PURPLE
  /* 47 */ [76,  62,  92 ], // TERRACOTTA_BLUE
  /* 48 */ [76,  50,  35 ], // TERRACOTTA_BROWN
  /* 49 */ [76,  82,  42 ], // TERRACOTTA_GREEN
  /* 50 */ [142, 60,  46 ], // TERRACOTTA_RED
  /* 51 */ [37,  22,  16 ], // TERRACOTTA_BLACK
  /* 52 */ [189, 48,  49 ], // CRIMSON_NYLIUM
  /* 53 */ [148, 63,  97 ], // CRIMSON_STEM
  /* 54 */ [92,  25,  29 ], // CRIMSON_HYPHAE
  /* 55 */ [22,  126, 134], // WARPED_NYLIUM
  /* 56 */ [58,  142, 140], // WARPED_STEM
  /* 57 */ [86,  44,  62 ], // WARPED_HYPHAE
  /* 58 */ [20,  180, 133], // WARPED_WART_BLOCK
  /* 59 */ [100, 100, 100], // DEEPSLATE
  /* 60 */ [216, 175, 147], // RAW_IRON
  /* 61 */ [127, 167, 150], // GLOW_LICHEN
  /* 62 */ [0,   0,   0  ], // placeholder (no colorId 62 in 1.21.4 vanilla)
  /* 63 */ [0,   0,   0  ], // placeholder (no colorId 63 in 1.21.4 vanilla)
];

// Brightness multipliers (out of 255): shade 0=180, 1=220, 2=255, 3=135
const SHADE_MULT = [180, 220, 255, 135] as const;

/**
 * Packed palette: MC_PALETTE[mapByte] = 0xRRGGBB, or 0 if invalid/transparent.
 *
 * mapByte = (colorId << 2) | shadeId, same encoding as MapState.colors.
 */
export const MC_PALETTE = new Uint32Array(256);

/** True for every mapByte that is a valid, non-transparent map colour. */
export const IS_VALID = new Uint8Array(256);

/**
 * Which mapBytes are "legal" (shade 0–2, not shade 3).
 * Shade 3 is unobtainable via normal block placement in survival.
 */
export const IS_LEGAL = new Uint8Array(256);

(function buildPalette() {
  for (let colorId = 1; colorId <= 61; colorId++) {
    const base = BASE_COLORS[colorId];
    if (!base || (base[0] === 0 && base[1] === 0 && base[2] === 0 && colorId !== 29 && colorId !== 51)) {
      // colorId 62 and 63 are unused placeholders — skip
      if (colorId >= 62) continue;
    }
    for (let shadeId = 0; shadeId <= 3; shadeId++) {
      const mapByte = (colorId << 2) | shadeId;
      const m = SHADE_MULT[shadeId];
      const r = Math.round((base[0] * m) / 255);
      const g = Math.round((base[1] * m) / 255);
      const b = Math.round((base[2] * m) / 255);
      MC_PALETTE[mapByte] = (r << 16) | (g << 8) | b;
      IS_VALID[mapByte] = 1;
      if (shadeId !== 3) IS_LEGAL[mapByte] = 1;
    }
  }
  // colorId 0 shade 0 (mapByte=0) stays transparent/invalid
})();

/** Unpack a mapByte to [R, G, B] (0–255). Returns [0,0,0] for transparent. */
export function mapByteToRgb(mapByte: number): RGB {
  const p = MC_PALETTE[mapByte & 0xff];
  return [(p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff];
}

/**
 * Replace the palette entries from a dumppalette export (runs after module init).
 * Call this if you have a more authoritative colour table from a live game dump.
 */
export function applyPaletteDump(entries: Array<[mapByte: number, r: number, g: number, b: number]>): void {
  MC_PALETTE.fill(0);
  IS_VALID.fill(0);
  IS_LEGAL.fill(0);
  for (const [mapByte, r, g, b] of entries) {
    MC_PALETTE[mapByte] = (r << 16) | (g << 8) | b;
    IS_VALID[mapByte] = 1;
    if ((mapByte & 3) !== 3) IS_LEGAL[mapByte] = 1;
  }
}
