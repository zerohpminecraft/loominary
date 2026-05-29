/**
 * Web Worker: zstd-compress one tile frame and return its compressed length.
 *
 * Kept in a persistent module-level pool (see Editor.tsx) so WASM is
 * initialised once per worker instance and reused across stats recomputations.
 *
 * Inbound  (main → worker):  { taskIndex: number; buffer: ArrayBuffer }
 * Outbound (worker → main):  { taskIndex: number; compressedLength: number }
 */

interface CompressTask {
  taskIndex: number;
  buffer:    ArrayBuffer;
}

addEventListener('message', async ({ data }: MessageEvent<CompressTask>) => {
  const { taskIndex, buffer } = data;
  // Dynamic import so the WASM module is loaded lazily and cached within
  // this worker context.
  const { compress } = await import('./compression.js');
  const result = await compress(new Uint8Array(buffer));
  self.postMessage({ taskIndex, compressedLength: result.length });
});
