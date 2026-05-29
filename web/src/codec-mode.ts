/**
 * Port of CodecMode.java — plus CARPET_SHADE_BANNERS which is a new web-side
 * codec exposing the alternative shade-before-banners encoding order.
 *
 * Priority order in naming: carpet > banners > shade (higher capacity listed first).
 * Physical encoding order inside each codec name:
 *   CARPET_BANNERS_SHADE  → carpet → banners → shade  (banners fill first)
 *   CARPET_SHADE_BANNERS  → carpet → shade  → banners (shade fills first)
 */

export const CodecMode = {
  BANNER:               'BANNER',
  CARPET:               'CARPET',
  CARPET_SHADE:         'CARPET_SHADE',
  CARPET_BANNERS:       'CARPET_BANNERS',
  CARPET_SHADE_BANNERS: 'CARPET_SHADE_BANNERS',
  CARPET_BANNERS_SHADE: 'CARPET_BANNERS_SHADE',
} as const;
export type CodecMode = typeof CodecMode[keyof typeof CodecMode];

export const DEFAULT_CODEC: CodecMode = 'CARPET_BANNERS_SHADE';

/**
 * Maximum compressed payload bytes per tile for each codec.
 * Values from CarpetChannel.java (LOOM format).
 * CARPET_SHADE_BANNERS has the same capacity as CARPET_BANNERS_SHADE;
 * the only difference is which channel is filled first when both are needed.
 */
export const CAPACITY: Record<CodecMode, number> = {
  BANNER:               5_290,   // 63 × 84 − 2
  CARPET:               8_176,   // MAX_CARPET_PAYLOAD = 8192 − 16 LOOM header
  CARPET_SHADE:        10_192,   // 8176 + 2016 shade
  CARPET_BANNERS:      13_466,   // 8176 + 5290 overflow
  CARPET_SHADE_BANNERS:15_482,   // 8176 + 2016 shade + 5290 overflow  (shade fills first)
  CARPET_BANNERS_SHADE:15_482,   // 8176 + 5290 overflow + 2016 shade  (banners fill first)
};

export const CODEC_LABEL: Record<CodecMode, string> = {
  BANNER:               'banners',
  CARPET:               'carpet',
  CARPET_SHADE:         'carpet+shade',
  CARPET_BANNERS:       'carpet+banners',
  CARPET_SHADE_BANNERS: 'carpet+shade+banners',
  CARPET_BANNERS_SHADE: 'carpet+banners+shade',
};

export const CODEC_HINT: Record<CodecMode, string> = {
  BANNER:               'CJK banner chunks only — no carpet platform required',
  CARPET:               'LOOM carpet channel only — 8 176 B max',
  CARPET_SHADE:         'Carpet + shade height channel — 10 192 B max',
  CARPET_BANNERS:       'Carpet + CJK overflow banners — 13 466 B max',
  CARPET_SHADE_BANNERS: 'Carpet + shade + banners — shade fills first, then overflow — 15 482 B max',
  CARPET_BANNERS_SHADE: 'Carpet + banners + shade — banners fill first, then shade — 15 482 B max (default)',
};

export const NEEDS_CARPET = new Set<CodecMode>([
  'CARPET', 'CARPET_SHADE', 'CARPET_BANNERS', 'CARPET_SHADE_BANNERS', 'CARPET_BANNERS_SHADE',
]);
