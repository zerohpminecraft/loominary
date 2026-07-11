/**
 * Animated preview export: an AV1-in-MP4 video of the rendered art, replacing the old GIF.
 *
 * This is a *human-viewable* preview (real colours), separate from the lossless-mono payload
 * codec.  It uses the browser's WebCodecs `VideoEncoder('av01')` — which hands `mp4-muxer` the
 * AV1 config record (av1C) MP4 needs — and falls back to the existing GIF encoder when av01
 * encoding isn't available (e.g. Firefox/Safari without AV1 encode).
 */

import { Muxer, ArrayBufferTarget } from 'mp4-muxer';
import { MC_PALETTE } from '../palette.js';
import { encodeAnimatedGif } from '../gif-encode.js';
import { buildAv1c } from './av1c.js';
import type { CompositionState } from '../payload-state.js';

const MAP_SIZE = 128;

/**
 * Re-package an already-encoded lossy AV1 stream (one 128×128 tile) into a playable MP4 — the
 * preview IS the payload encode, no decode and no re-encode.  Returns null if the av1C config
 * can't be derived (caller falls back to the decode+re-encode path).
 */
export function muxAv1Stream(tus: Uint8Array[], w: number, h: number, frameDelays: number[]): Uint8Array | null {
  if (tus.length === 0) return null;
  const av1c = buildAv1c(tus[0]);
  if (!av1c) return null;

  const muxer = new Muxer({
    target: new ArrayBufferTarget(),
    video: { codec: 'av1', width: w, height: h },
    fastStart: 'in-memory',
  });
  let tsMicros = 0;
  for (let i = 0; i < tus.length; i++) {
    const durationMicros = Math.max(1000, (frameDelays[i] ?? 100) * 1000);
    const meta = i === 0
      ? { decoderConfig: { codec: 'av01.0.00M.08', codedWidth: w, codedHeight: h, description: av1c } }
      : undefined;
    // TU[0] is the keyframe (carries the seq header); the rest are inter frames.
    muxer.addVideoChunkRaw(tus[i], i === 0 ? 'key' : 'delta', tsMicros, durationMicros, meta);
    tsMicros += durationMicros;
  }
  muxer.finalize();
  return new Uint8Array(muxer.target.buffer);
}

export interface AnimatedPreview {
  bytes: Uint8Array;
  mime: string;   // 'video/mp4' | 'image/gif'
  ext:  string;   // 'mp4' | 'gif'
}

/** True if the browser can encode AV1 at this size. */
async function av1EncodeSupported(width: number, height: number): Promise<boolean> {
  const VE = (globalThis as { VideoEncoder?: typeof VideoEncoder }).VideoEncoder;
  if (!VE?.isConfigSupported) return false;
  try {
    const { supported } = await VE.isConfigSupported({
      codec: 'av01.0.08M.08', width, height, bitrate: 2_000_000,
    });
    return !!supported;
  } catch {
    return false;
  }
}

/** Composite all tiles for animation frame `fi` into an RGBA buffer. */
function rasterizeFrame(frames: Uint8Array[][], fi: number, gridCols: number, gridRows: number, W: number, H: number): Uint8ClampedArray {
  const rgba = new Uint8ClampedArray(W * H * 4);
  for (let tileRow = 0; tileRow < gridRows; tileRow++) {
    for (let tileCol = 0; tileCol < gridCols; tileCol++) {
      const ti = tileRow * gridCols + tileCol;
      const frame = frames[ti]?.[fi] ?? frames[ti]?.[0];
      if (!frame) continue;
      for (let ly = 0; ly < MAP_SIZE; ly++) {
        const gy = tileRow * MAP_SIZE + ly;
        for (let lx = 0; lx < MAP_SIZE; lx++) {
          const packed = MC_PALETTE[frame[ly * MAP_SIZE + lx]] ?? 0;
          const o = (gy * W + tileCol * MAP_SIZE + lx) * 4;
          rgba[o]     = (packed >> 16) & 0xff;
          rgba[o + 1] = (packed >> 8) & 0xff;
          rgba[o + 2] = packed & 0xff;
          rgba[o + 3] = 255;
        }
      }
    }
  }
  return rgba;
}

/**
 * Build an animated preview from explicit `frames` ([tile][frame]): a compact AV1 MP4 when the
 * runtime can encode av01 (works on the main thread AND in a worker), else a GIF.  Used both for
 * the export ZIP (rendered art) and for the truthful lossy preview (decoded frames).
 */
export async function buildPreview(
  frames: Uint8Array[][], gridCols: number, gridRows: number, frameDelays: number[][],
  onProgress?: (done: number, total: number) => void,
): Promise<AnimatedPreview> {
  const W = gridCols * MAP_SIZE;
  const H = gridRows * MAP_SIZE;
  const maxF = Math.max(...frames.map(t => t.length), 1);

  if (typeof VideoEncoder !== 'undefined' && await av1EncodeSupported(W, H)) {
    try {
      return { bytes: await encodeMp4(frames, gridCols, gridRows, W, H, maxF, frameDelays, onProgress), mime: 'video/mp4', ext: 'mp4' };
    } catch (e) {
      console.warn('[av1] MP4 preview failed — falling back to GIF', e);
    }
  }
  const comp = { gridCols, gridRows, frames, frameDelays } as CompositionState;
  return { bytes: encodeAnimatedGif(comp, 0), mime: 'image/gif', ext: 'gif' };
}

/** Convenience for the ZIP export path (renders the composition's own frames). */
export function buildAnimatedPreview(comp: CompositionState): Promise<AnimatedPreview> {
  return buildPreview(comp.frames, comp.gridCols, comp.gridRows, comp.frameDelays);
}

async function encodeMp4(
  frames: Uint8Array[][], gridCols: number, gridRows: number, W: number, H: number, maxF: number, frameDelays: number[][],
  onProgress?: (done: number, total: number) => void,
): Promise<Uint8Array> {
  const muxer = new Muxer({
    target: new ArrayBufferTarget(),
    video: { codec: 'av1', width: W, height: H },
    fastStart: 'in-memory',
  });

  const encoder = new VideoEncoder({
    output: (chunk, meta) => muxer.addVideoChunk(chunk, meta),
    error: (e) => { throw e; },
  });
  encoder.configure({ codec: 'av01.0.08M.08', width: W, height: H, bitrate: 2_000_000 });

  let tsMicros = 0;
  for (let fi = 0; fi < maxF; fi++) {
    const rgba = rasterizeFrame(frames, fi, gridCols, gridRows, W, H);
    const delayMs = frameDelays[0]?.[fi] ?? 100;
    const durationMicros = Math.max(1000, delayMs * 1000);
    const frame = new VideoFrame(rgba, {
      format: 'RGBA', codedWidth: W, codedHeight: H,
      timestamp: tsMicros, duration: durationMicros,
    });
    encoder.encode(frame, { keyFrame: fi % 60 === 0 });
    frame.close();
    tsMicros += durationMicros;
    onProgress?.(fi + 1, maxF);
    // Backpressure: don't queue thousands of frames at once (memory blowup) — let the encoder
    // drain before feeding more.
    if (encoder.encodeQueueSize > 16) {
      await new Promise<void>(resolve => {
        const check = () => (encoder.encodeQueueSize <= 8 ? resolve() : encoder.addEventListener('dequeue', check, { once: true }));
        check();
      });
    }
  }

  await encoder.flush();
  encoder.close();
  muxer.finalize();
  return new Uint8Array(muxer.target.buffer);
}
