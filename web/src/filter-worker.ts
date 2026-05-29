/**
 * Web Worker: apply a spatial filter then re-quantize one animation frame.
 *
 * Dispatched once per frame by Editor.applyEditorFilter so all frames run in
 * parallel.  Zero-copy: tile buffers are transferred in both directions.
 *
 * Inbound  (main → worker):
 *   frameIndex   — ordering key echoed back in the response
 *   tileBuffers  — transferred ArrayBuffer[] (one 16 384-byte map-colour frame per tile)
 *   gridCols / gridRows
 *   filterParams — { type: FilterType; strength: number }
 *   reqParams    — RequantizeParams
 *   selMask      — Uint8Array | null (full-grid selection, structurally cloned)
 *
 * Outbound (worker → main):
 *   frameIndex
 *   tileBuffers  — transferred ArrayBuffer[] with the filtered + re-quantized result
 */

import { mapBytesToImageData, requantizeGrid } from './quantize.js';
import type { RequantizeParams } from './quantize.js';
import { applyFilter } from './filters.js';
import type { FilterParams } from './filters.js';

interface FilterTask {
  frameIndex:   number;
  tileBuffers:  ArrayBuffer[];
  gridCols:     number;
  gridRows:     number;
  filterParams: FilterParams;
  reqParams:    RequantizeParams;
  selMask:      Uint8Array | null;
}

interface FilterResult {
  frameIndex:  number;
  tileBuffers: ArrayBuffer[];
}

addEventListener('message', ({ data }: MessageEvent<FilterTask>) => {
  const { frameIndex, tileBuffers, gridCols, gridRows, filterParams, reqParams, selMask } = data;

  // Reconstruct the per-tile frame arrays (one frame per tile = frame index 0).
  const frames: Uint8Array[][] = tileBuffers.map(b => [new Uint8Array(b)]);

  // Compose the minimal shape that mapBytesToImageData expects.
  const gridShape = { gridCols, gridRows, frames, activeFrame: 0 };

  // 1. Render all tiles for this frame into a single ImageData.
  const imageData = mapBytesToImageData(frames, 0, gridCols, gridRows);

  // 2. Apply the spatial filter.
  const filtered = applyFilter(imageData, filterParams);

  // 3. Re-quantize filtered pixels back into map-colour bytes.
  const newTiles    = requantizeGrid(filtered, gridShape, selMask ?? null, reqParams);
  const outBuffers  = newTiles.map(t => t.buffer as ArrayBuffer);

  const result: FilterResult = { frameIndex, tileBuffers: outBuffers };
  self.postMessage(result, { transfer: outBuffers });
});
