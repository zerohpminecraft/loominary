/**
 * Palette restriction helpers for the import wizard and palette panel filter.
 *
 * Each PaletteRestriction value maps to a PaletteFlag (Uint8Array[256])
 * that controls which map-colour bytes the quantizer (or palette panel) may use.
 */

import { IS_LEGAL, IS_VALID, MC_PALETTE } from './palette.js';
import { NIBBLE_TO_MAP_BYTE } from './carpet.js';
import type { PaletteFlag } from './quantize.js';

// ─── Types ────────────────────────────────────────────────────────────────────

/** Extra options for specific palette restrictions. */
export interface PaletteOptions {
  /** Chroma threshold for the greyscale restriction (0–255, default 40). */
  greyThreshold?: number;
}

export type PaletteRestriction =
  | 'flat-fullblock'    // shade 1 only of all 61 base colors (61 colors)
  | 'legal'             // shades 0–2 of all 61 base colors (186 colors) — "staircase fullblock"
  | 'all'               // includes shade 3 (~248 colors)
  | 'greyscale'
  | 'flat-carpet'       // 16 carpet colors at shade 1
  | 'staircase-carpet'; // 16 carpet colors × shades 0–2

export interface PaletteChoice {
  id:          PaletteRestriction;
  label:       string;
  description: string;
}

// ─── Choice manifest ──────────────────────────────────────────────────────────

export const PALETTE_CHOICES: PaletteChoice[] = [
  {
    id:          'flat-fullblock',
    label:       'Flat fullblock',
    description: 'Shade 1 of all 61 base colors (61 total) — for flat single-height full-block builds',
  },
  {
    id:          'legal',
    label:       'Staircase fullblock',
    description: '61 base colors × shades 0–2 (183 total) — full-block builds with height variation',
  },
  {
    id:          'all',
    label:       'All shades',
    description: 'Includes shade 3 (244 colors total) — unobtainable via normal block placement',
  },
  {
    id:          'greyscale',
    label:       'Greyscale',
    description: 'Neutral/grey tones only (legal colors where chroma < 40)',
  },
  {
    id:          'flat-carpet',
    label:       'Flat carpet',
    description: '16 carpet colors at shade 1 — for flat single-height carpet schematics',
  },
  {
    id:          'staircase-carpet',
    label:       'Staircase carpet',
    description: '16 carpet colors × shades 0–2 — for depth-shaded staircase builds',
  },
];

// ─── buildPaletteFlag ────────────────────────────────────────────────────────

/**
 * Build the PaletteFlag (Uint8Array[256]) for a given restriction.
 * Non-zero entries = the byte is available to the quantizer.
 */
export function buildPaletteFlag(r: PaletteRestriction, opts?: PaletteOptions): PaletteFlag {
  switch (r) {
    case 'flat-fullblock': {
      // Shade 1 only (c & 3 === 1) of every legal base color.
      const flag = new Uint8Array(256);
      for (let c = 1; c < 256; c++) {
        if (IS_LEGAL[c] && (c & 3) === 1) flag[c] = 1;
      }
      return flag;
    }

    case 'legal':
      return IS_LEGAL.slice();

    case 'all':
      return IS_VALID.slice();

    case 'greyscale': {
      const threshold = opts?.greyThreshold ?? 40;
      const flag = new Uint8Array(256);
      for (let b = 1; b < 256; b++) {
        if (!IS_LEGAL[b]) continue;
        const rgb  = MC_PALETTE[b];
        if (!rgb) continue;
        const rv = (rgb >> 16) & 0xff;
        const gv = (rgb >>  8) & 0xff;
        const bv =  rgb        & 0xff;
        if (Math.max(rv, gv, bv) - Math.min(rv, gv, bv) < threshold) flag[b] = 1;
      }
      return flag;
    }

    case 'flat-carpet': {
      const flag = new Uint8Array(256);
      for (let n = 0; n < 16; n++) flag[NIBBLE_TO_MAP_BYTE[n]] = 1;
      return flag;
    }

    case 'staircase-carpet': {
      const flag = new Uint8Array(256);
      for (let n = 0; n < 16; n++) {
        const colorId = NIBBLE_TO_MAP_BYTE[n] >> 2;
        for (let shade = 0; shade <= 2; shade++) flag[colorId * 4 + shade] = 1;
      }
      return flag;
    }
  }
}

/**
 * Returns true only for 'all' (the restriction that enables shade 3).
 */
export function paletteMeansAllShades(r: PaletteRestriction): boolean {
  return r === 'all';
}
