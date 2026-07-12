/**
 * Web Worker: quantizes one prepared ImageData per message and returns the
 * resulting tile Uint8Arrays.  All large buffers are zero-copy transferred.
 *
 * Inbound message  (main → worker):
 *   frameIndex        — identifies the frame so out-of-order results can be sorted
 *   imageBuffer       — transferred ArrayBuffer backing the source ImageData
 *   width/height      — ImageData dimensions (cols*128 × rows*128)
 *   gridCols/Rows     — tile grid shape
 *   reqParams         — RequantizeParams (structurally cloned; customPalette included)
 *   selBuffer         — optional transferred ArrayBuffer (full-grid selection mask)
 *   tileFrameBuffers  — optional transferred ArrayBuffer[] (one 16384-byte current frame
 *                       per tile); required when reqParams.tilePalette is true so the
 *                       worker can build per-tile colour palettes from existing pixels
 *
 * Outbound message (worker → main):
 *   frameIndex   — echo back for ordering
 *   tileBuffers  — transferred ArrayBuffer[] (one 16384-byte buffer per tile)
 */

import { requantizeGrid } from './quantize.js';
import type { RequantizeParams } from './quantize.js';
import { rgbToOklab } from './oklab.js';
import { MC_PALETTE } from './palette.js';

interface WorkerTask {
  frameIndex:       number;
  imageBuffer:      ArrayBuffer;
  width:            number;
  height:           number;
  gridCols:         number;
  gridRows:         number;
  reqParams:        RequantizeParams;
  selBuffer?:       ArrayBuffer | null;
  tileFrameBuffers?: ArrayBuffer[] | null;
}

addEventListener('message', ({ data }: MessageEvent<WorkerTask>) => {
  const {
    frameIndex, imageBuffer, width, height,
    gridCols, gridRows, reqParams,
    selBuffer, tileFrameBuffers,
  } = data;

  const imageData = new ImageData(new Uint8ClampedArray(imageBuffer), width, height);
  const selMask   = selBuffer ? new Uint8Array(selBuffer) : null;

  // If tileFrameBuffers are provided (tilePalette mode), reconstruct per-tile
  // frame arrays so requantizeGrid can derive per-tile colour palettes.
  const frames = tileFrameBuffers
    ? tileFrameBuffers.map(b => [new Uint8Array(b)])
    : Array.from({ length: gridCols * gridRows }, () => [new Uint8Array(128 * 128)]);

  const gridShape = { gridCols, gridRows, frames, activeFrame: 0 };

  const tiles       = requantizeGrid(imageData, gridShape, selMask, reqParams);

  // Fidelity stats: perceptual distance between each source pixel and the
  // palette colour it actually received. One OKLab distance per pixel —
  // negligible next to the quantization itself. Aggregated across all frames
  // by the caller and surfaced in the editor's stats panel.
  const GOOD_SQ = 0.05 * 0.05;
  let good = 0, total = 0, sumDelta = 0;
  for (let gy = 0; gy < height; gy++) {
    for (let gx = 0; gx < width; gx++) {
      const si = (gy * width + gx) * 4;
      if (imageData.data[si + 3] < 128) continue;
      const tile = (gy >> 7) * gridCols + (gx >> 7);
      const b    = tiles[tile][((gy & 127) << 7) | (gx & 127)] & 0xff;
      if (b === 0) continue; // transparent output
      const rgb = MC_PALETTE[b];
      const [sL, sa, sb] = rgbToOklab(imageData.data[si], imageData.data[si + 1], imageData.data[si + 2]);
      const [qL, qa, qb] = rgbToOklab((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
      const dSq = (sL - qL) ** 2 + (sa - qa) ** 2 + (sb - qb) ** 2;
      sumDelta += Math.sqrt(dSq);
      total++;
      if (dSq <= GOOD_SQ) good++;
    }
  }

  const tileBuffers = tiles.map(t => t.buffer as ArrayBuffer);
  self.postMessage({ frameIndex, tileBuffers, stats: { good, total, sumDelta } },
      { transfer: tileBuffers });
});
