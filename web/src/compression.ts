/**
 * zstd compression wrapper — uses @bokuweb/zstd-wasm (growable-memory libzstd).
 *
 * Replaces the older zstd-codec build, whose fixed 16 MB heap OOM'd above ~level 9 on
 * tile-sized (~1 MB) payloads and forced a low level for animated art.  @bokuweb/zstd-wasm
 * grows its memory, so we can use level 19 across the board — ~20% smaller output on the
 * high-entropy (dithered) map art that dominates our payloads.  Output is a standard zstd
 * frame, decoded unchanged by zstd-jni in the mod.
 *
 * The wasm is initialised lazily on first use so it doesn't block page load.
 */

import { init, compress as zCompress, decompress as zDecompress } from '@bokuweb/zstd-wasm';

let _ready: Promise<void> | null = null;
function ready(): Promise<void> {
  return (_ready ??= init());
}

/**
 * Level policy.  With growable memory the old OOM cliff is gone, so we use a high level
 * everywhere it's affordable.  Level 19 is the sweet spot (22 gives no measurable gain on
 * this content); very large inputs drop a bit to keep export time reasonable.
 */
function adaptiveLevel(payloadSize: number): number {
  if (payloadSize < 4 * 1024 * 1024) return 19;
  if (payloadSize < 16 * 1024 * 1024) return 15;
  return 12;
}

/** Compress `data` with zstd at an adaptively chosen level. */
export async function compress(data: Uint8Array): Promise<Uint8Array> {
  await ready();
  const out = zCompress(data, adaptiveLevel(data.length));
  if (!out) throw new Error('zstd compress returned null');
  return out instanceof Uint8Array ? out : new Uint8Array(out);
}

/** Compress a manifest prefix + map-color payload in one call. */
export async function compressCombined(prefix: Uint8Array, mapColors: Uint8Array): Promise<Uint8Array> {
  const combined = new Uint8Array(prefix.length + mapColors.length);
  combined.set(prefix, 0);
  combined.set(mapColors, prefix.length);
  return compress(combined);
}

/** Decompress a zstd-compressed buffer (any standard zstd frame). */
export async function decompress(compressed: Uint8Array): Promise<Uint8Array> {
  await ready();
  const out = zDecompress(compressed);
  if (!out) throw new Error('zstd decompress returned null');
  return out instanceof Uint8Array ? out : new Uint8Array(out);
}
