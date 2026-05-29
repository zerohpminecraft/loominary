/**
 * Web Worker: quantizes one prepared ImageData per message and returns the
 * resulting tile Uint8Arrays.  All large buffers are zero-copy transferred.
 *
 * Inbound message  (main → worker):
 *   frameIndex   — identifies the frame so out-of-order results can be sorted
 *   imageBuffer  — transferred ArrayBuffer backing the source ImageData
 *   width/height — ImageData dimensions (cols*128 × rows*128)
 *   gridCols/Rows — tile grid shape
 *   reqParams    — RequantizeParams (structurally cloned; customPalette included)
 *
 * Outbound message (worker → main):
 *   frameIndex   — echo back for ordering
 *   tileBuffers  — transferred ArrayBuffer[] (one 16384-byte buffer per tile)
 */

import { requantizeGrid } from './quantize.js';
import type { RequantizeParams } from './quantize.js';

interface WorkerTask {
  frameIndex: number;
  imageBuffer: ArrayBuffer;
  width:       number;
  height:      number;
  gridCols:    number;
  gridRows:    number;
  reqParams:   RequantizeParams;
}

// addEventListener is available in both Window and DedicatedWorkerGlobalScope
// scopes, so TypeScript's DOM lib accepts this without casts.
addEventListener('message', ({ data }: MessageEvent<WorkerTask>) => {
  const { frameIndex, imageBuffer, width, height, gridCols, gridRows, reqParams } = data;

  // Reconstruct ImageData from the transferred buffer.
  const imageData = new ImageData(new Uint8ClampedArray(imageBuffer), width, height);

  // Minimal shape expected by requantizeGrid — frames array is unused in the
  // non-tilePalette path (the default), but required by the type.
  const gridShape = {
    gridCols,
    gridRows,
    frames:      Array.from({ length: gridCols * gridRows }, () => [new Uint8Array(128 * 128)]),
    activeFrame: 0,
  };

  const tiles       = requantizeGrid(imageData, gridShape, null, reqParams);
  const tileBuffers = tiles.map(t => t.buffer as ArrayBuffer);

  // postMessage(message, options) — the { transfer } overload is accepted by
  // TypeScript's Window.postMessage signature (targetOrigin is optional).
  self.postMessage({ frameIndex, tileBuffers }, { transfer: tileBuffers });
});
