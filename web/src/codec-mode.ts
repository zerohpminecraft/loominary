/**
 * Port of CodecMode.java.
 */

export const CodecMode = {
  BANNER:       'BANNER',
  CARPET:       'CARPET',
  CARPET_SHADE: 'CARPET_SHADE',
  CARPET_ONLY:  'CARPET_ONLY',
} as const;
export type CodecMode = typeof CodecMode[keyof typeof CodecMode];

export const DEFAULT_CODEC = CodecMode.CARPET_SHADE;

/**
 * Maximum compressed payload (in bytes) for each codec mode.
 * 63 banners × 84 bytes/banner − 2 bytes length header = 5290 bytes overflow cap.
 */
export const CAPACITY: Record<CodecMode, number> = {
  BANNER:       5_290,
  CARPET:      13_466,
  CARPET_SHADE: 15_482,
  CARPET_ONLY:  10_192,
};
