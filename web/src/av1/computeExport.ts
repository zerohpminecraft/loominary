/**
 * Unified per-composition compute: encodes every tile ONCE (for size/mux) and, in the same pass,
 * produces the truthful preview from that same encode — no second encode.  For lossy tiles the
 * preview frames are the *decoded* lossy result (byte-exact to what ships); for lossless/raw they
 * are the originals.  Heavy, so it runs in the encode worker.
 */

import {
  encodeTilePayload, encodeCompositionLossy, compositeEligible,
  encodeSrgbTilePayload, encodeCompositionSrgb, compositeEligibleSrgb,
} from '../encode.js';
import { buildPreview, muxAv1Stream } from './mp4Preview.js';
import { splitTus, decodeLossyColor } from './codec.js';
import { rgbToOklab, oklabDistSq } from '../oklab.js';
import { isSrgb } from '../payload-state.js';
import type { CompositionState } from '../payload-state.js';

const TILE = 128;          // tile pixel width/height
const MAP = TILE * TILE;   // bytes per frame
const RGB_MAP = MAP * 3;   // bytes per RGB frame

export interface ComputeTile { compressed: Uint8Array; manifestBytes: Uint8Array }

/** sRGB fidelity: perceptual mean OKLab ΔE (primary) + RGB PSNR in dB (secondary). */
export interface SrgbFidelity { meanDeltaE: number; psnrDb: number }

export interface ComputeResult {
  tiles:       ComputeTile[];
  /** Preview of the exact result (null for non-animated). */
  previewBytes: Uint8Array | null;
  /** MIME of the preview: 'video/mp4' or 'image/gif'. */
  previewMime:  string | null;
  /** Lossy fidelity (fraction of pixels changed) — null when not lossy or in sRGB mode. */
  changedFrac:  number | null;
  /** sRGB fidelity vs the original true-colour frames — null outside sRGB mode. */
  fidelity?:    SrgbFidelity | null;
  /** sRGB: decoded frame-0 truth per tile (packed RGB) for the static preview image. */
  srgbPreviewRgb?: Uint8Array[] | null;
}

export interface ComputeOpts {
  author: string | null; title: string | null; nonce: number; lossyQuality?: number | null;
}

export type ComputeProgress = (
  tilesDone: number, tilesTotal: number, phase: string, frameDone: number, frameTotal: number,
) => void;

/** Mean OKLab ΔE + PSNR between original and decoded packed-RGB frame lists. */
function srgbFidelity(orig: Uint8Array[][], dec: Uint8Array[][], maxFrames: number): SrgbFidelity {
  let deSum = 0, sqErr = 0, n = 0;
  for (let ti = 0; ti < orig.length; ti++) {
    const of = orig[ti] ?? [], df = dec[ti] ?? [];
    const fc = Math.min(of.length, df.length, maxFrames);
    for (let f = 0; f < fc; f++) {
      const a = of[f], b = df[f];
      for (let p = 0; p < MAP; p++) {
        const i = p * 3;
        const [L1, a1, b1] = rgbToOklab(a[i], a[i + 1], a[i + 2]);
        const [L2, a2, b2] = rgbToOklab(b[i], b[i + 1], b[i + 2]);
        deSum += Math.sqrt(oklabDistSq(L1, a1, b1, L2, a2, b2));
        for (let c = 0; c < 3; c++) { const d = a[i + c] - b[i + c]; sqErr += d * d; }
        n++;
      }
    }
  }
  const mse = n > 0 ? sqErr / (n * 3) : 0;
  return {
    meanDeltaE: n > 0 ? deSum / n : 0,
    psnrDb: mse > 0 ? 10 * Math.log10(255 * 255 / mse) : 99,
  };
}

/** sRGB compute: AV1 colour streams only; preview + ΔE/PSNR fidelity from the same decode. */
async function computeSrgbComposition(
  comp: CompositionState, opts: ComputeOpts, onProgress?: ComputeProgress,
): Promise<ComputeResult> {
  const { gridCols, gridRows } = comp;
  const tilesTotal = gridCols * gridRows;
  const quality = opts.lossyQuality ?? 30;
  const rgbTiles = Array.from({ length: tilesTotal }, (_, ti) => comp.rgbFrames![ti] ?? [new Uint8Array(RGB_MAP)]);
  const pvTiles  = Array.from({ length: tilesTotal }, (_, ti) => comp.frames[ti] ?? [new Uint8Array(MAP)]);
  const tileDelays = Array.from({ length: tilesTotal }, (_, ti) =>
    comp.frameDelays[ti] ?? new Array(rgbTiles[ti].length).fill(100));
  const frameCount = Math.max(1, rgbTiles[0]?.length ?? 1);
  const animated = frameCount > 1;
  const srgbOpts = {
    author: opts.author, title: opts.title, nonce: opts.nonce, lossyQuality: quality,
    captureLossyFrames: true,
    onProgress: (phase: string, d: number, t: number) => onProgress?.(0, 1, phase, d, t),
  };

  const tiles: ComputeTile[] = [];
  let decodedRgb: Uint8Array[][];   // [tile][frame] decoded truth
  let stream: Uint8Array | undefined;
  let streamDims = { w: TILE, h: TILE };

  if (compositeEligibleSrgb(comp.rgbFrames!, tilesTotal)) {
    const result = await encodeCompositionSrgb(rgbTiles, pvTiles, tileDelays, gridCols, gridRows, srgbOpts);
    for (const tp of result.tiles) tiles.push({ compressed: tp.compressed, manifestBytes: tp.manifestBytes });
    decodedRgb = result.lossyTileRgb
      ?? rgbTiles.map(tf => tf.map(f => f.slice())); // per-tile fallback captured below
    if (!result.composite) {
      // Fallback produced per-tile streams; re-collect their decoded truth.
      decodedRgb = result.tiles.map((tp, ti) => tp.lossyRgbFrames ?? rgbTiles[ti]);
    }
    stream = result.stream;
    streamDims = { w: gridCols * TILE, h: gridRows * TILE };
  } else {
    decodedRgb = [];
    for (let ti = 0; ti < tilesTotal; ti++) {
      const payload = await encodeSrgbTilePayload(rgbTiles[ti], pvTiles[ti], tileDelays[ti],
        ti % gridCols, Math.floor(ti / gridCols), gridCols, gridRows,
        { ...srgbOpts, onProgress: (phase, d, t) => onProgress?.(ti, tilesTotal, phase, d, t) });
      tiles.push({ compressed: payload.compressed, manifestBytes: payload.manifestBytes });
      decodedRgb.push(payload.lossyRgbFrames ?? rgbTiles[ti]);
      if (tilesTotal === 1) stream = payload.lossyStream;
    }
  }

  // Fidelity vs the true originals (sample cap keeps huge animations fast).
  const fidelity = srgbFidelity(rgbTiles, decodedRgb, 24);

  // Preview: the payload stream muxed to MP4 for animated; frame-0 truth for static.
  let previewBytes: Uint8Array | null = null;
  let previewMime:  string | null = null;
  if (animated && stream) {
    const mp4 = muxAv1Stream(splitTus(stream), streamDims.w, streamDims.h, comp.frameDelays[0] ?? []);
    if (mp4) { previewBytes = mp4; previewMime = 'video/mp4'; }
  }
  const srgbPreviewRgb = decodedRgb.map(tf => tf[0] ?? new Uint8Array(RGB_MAP));

  return { tiles, previewBytes, previewMime, changedFrac: null, fidelity, srgbPreviewRgb };
}

export async function computeComposition(
  comp: CompositionState, opts: ComputeOpts, onProgress?: ComputeProgress,
): Promise<ComputeResult> {
  if (isSrgb(comp)) return computeSrgbComposition(comp, opts, onProgress);

  const { gridCols, gridRows, allShades } = comp;
  const tilesTotal = gridCols * gridRows;
  const lossy = opts.lossyQuality != null;
  // Single-tile lossy: the payload AV1 stream IS the preview video — mux it, no decode/re-encode.
  const directMux = lossy && tilesTotal === 1 && (comp.frames[0]?.length ?? 1) > 1;

  const tiles: ComputeTile[] = [];
  const previewFrames: Uint8Array[][] = [];
  let directStream: Uint8Array | undefined;
  let changed = 0, total = 0;
  let previewBytes: Uint8Array | null = null;
  let previewMime:  string | null = null;

  // Multi-tile lossy: composition-wide encode — ONE stream over the whole grid, so tile
  // boundaries carry no lossy seams; the stream is split across the tiles' payloads.
  if (lossy && compositeEligible(comp.frames, tilesTotal)) {
    const tileFrames = Array.from({ length: tilesTotal }, (_, ti) => comp.frames[ti] ?? [new Uint8Array(MAP)]);
    const tileDelays = Array.from({ length: tilesTotal }, (_, ti) =>
      comp.frameDelays[ti] ?? new Array(tileFrames[ti].length).fill(100));
    const result = await encodeCompositionLossy(tileFrames, tileDelays, gridCols, gridRows, allShades, {
      author: opts.author, title: opts.title, nonce: opts.nonce, lossyQuality: opts.lossyQuality!,
      captureLossyFrames: true,
      onProgress: (phase, d, t) => onProgress?.(0, 1, phase, d, t),
    });
    for (const tp of result.tiles) tiles.push({ compressed: tp.compressed, manifestBytes: tp.manifestBytes });

    if (result.composite && result.lossyTileFrames) {
      for (let ti = 0; ti < tilesTotal; ti++) {
        const orig = tileFrames[ti], dec = result.lossyTileFrames[ti];
        previewFrames.push(dec);
        for (let f = 0; f < orig.length && f < dec.length; f++)
          for (let p = 0; p < MAP; p++) { total++; if (orig[f][p] !== dec[f][p]) changed++; }
      }
      // The composite stream IS a video of the whole composition — mux it directly (no
      // re-encode); the frames fallback below covers browsers where the av1C can't be built.
      if (result.stream) {
        const mp4 = muxAv1Stream(splitTus(result.stream), gridCols * TILE, gridRows * TILE,
          comp.frameDelays[0] ?? []);
        if (mp4) { previewBytes = mp4; previewMime = 'video/mp4'; }
      }
    } else {
      // Raw floor won (or composite encode failed): the shipped payload is exact.
      for (let ti = 0; ti < tilesTotal; ti++) previewFrames.push(tileFrames[ti]);
    }

    const changedFrac = total > 0 ? changed / total : 0;
    if (previewBytes == null) {
      const p = await buildPreview(previewFrames, gridCols, gridRows, comp.frameDelays,
        (d, t) => onProgress?.(tilesTotal, tilesTotal, 'preview', d, t));
      previewBytes = p.bytes; previewMime = p.mime;
    }
    return { tiles, previewBytes, previewMime, changedFrac };
  }

  for (let ti = 0; ti < tilesTotal; ti++) {
    const tileCol = ti % gridCols, tileRow = Math.floor(ti / gridCols);
    const frames  = comp.frames[ti]      ?? [new Uint8Array(MAP)];
    const delays  = comp.frameDelays[ti] ?? new Array(frames.length).fill(100);

    const payload = await encodeTilePayload(frames, delays, tileCol, tileRow, gridCols, gridRows, allShades, {
      author: opts.author, title: opts.title, nonce: opts.nonce, lossyQuality: opts.lossyQuality,
      captureLossyFrames: lossy && !directMux, // direct-mux doesn't need decoded frames
      onProgress: (phase, d, t) => onProgress?.(ti, tilesTotal, phase, d, t),
    });
    tiles.push({ compressed: payload.compressed, manifestBytes: payload.manifestBytes });

    if (directMux) {
      directStream = payload.lossyStream;
    } else {
      const pf = payload.lossyFrames ?? frames; // lossy → exact decoded; else originals are exact
      previewFrames.push(pf);
      if (payload.lossyFrames) {
        for (let f = 0; f < frames.length && f < pf.length; f++)
          for (let p = 0; p < MAP; p++) { total++; if (frames[f][p] !== pf[f][p]) changed++; }
      }
    }
  }

  let changedFrac:  number | null = lossy ? 0 : null;

  if (directMux && directStream) {
    // Preview = the payload stream, remuxed to MP4 (near-instant, no re-encode).
    const tileDelays = comp.frameDelays[0] ?? [];
    const mp4 = muxAv1Stream(splitTus(directStream), TILE, TILE, tileDelays);
    if (mp4) {
      previewBytes = mp4; previewMime = 'video/mp4';
      // Fidelity from a small prefix decode (inter-frames must be decoded from the start).
      const orig = comp.frames[0] ?? [];
      const K = Math.min(orig.length, 24);
      if (K > 0) {
        onProgress?.(tilesTotal, tilesTotal, 'preview', 0, K);
        const sample = await decodeLossyColor(directStream, 0, K, (d, t) => onProgress?.(tilesTotal, tilesTotal, 'preview', d, t));
        for (let f = 0; f < sample.length; f++)
          for (let p = 0; p < MAP; p++) { total++; if (orig[f][p] !== sample[f][p]) changed++; }
        changedFrac = total > 0 ? changed / total : 0;
      }
    }
  }

  // Multi-tile / lossless (or if the direct mux couldn't build av1C): render frames → MP4/GIF.
  if (previewBytes == null && Math.max(...(directMux ? [comp.frames[0]?.length ?? 1] : previewFrames.map(t => t.length)), 1) > 1) {
    const framesForPreview = directMux
      ? [await decodeLossyColor(directStream!, 0, comp.frames[0]!.length, (d, t) => onProgress?.(tilesTotal, tilesTotal, 'preview', d, t))]
      : previewFrames;
    const p = await buildPreview(framesForPreview, gridCols, gridRows, comp.frameDelays,
      (d, t) => onProgress?.(tilesTotal, tilesTotal, 'preview', d, t));
    previewBytes = p.bytes; previewMime = p.mime;
    if (directMux) changedFrac = null; // fell back — sample metric not computed
  }

  return { tiles, previewBytes, previewMime, changedFrac: lossy ? changedFrac : null };
}
