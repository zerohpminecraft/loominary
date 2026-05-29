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
  const tileBuffers = tiles.map(t => t.buffer as ArrayBuffer);

  self.postMessage({ frameIndex, tileBuffers }, { transfer: tileBuffers });
});
