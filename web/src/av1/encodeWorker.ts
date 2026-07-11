/**
 * Off-main-thread tile encoder.  Runs the full per-tile encode (raw/zstd + AV1 lossless + optional
 * lossy) so huge animations (thousands of frames) don't freeze the UI, and streams frame-level
 * progress back to the main thread for a progress bar.
 */

import { encodeTilePayload } from '../encode.js';
import { computeComposition, type ComputeOpts } from './computeExport.js';
import type { CompositionState } from '../payload-state.js';

interface EncodeReq {
  id: number; kind?: 'encode';
  frames:      Uint8Array[];   // structured-cloned (caller keeps its originals)
  frameDelays: number[];
  tileCol:     number;
  tileRow:     number;
  gridCols:    number;
  gridRows:    number;
  allShades:   boolean;
  author:      string | null;
  title:       string | null;
  nonce:       number;
  lossyQuality?: number | null;
}

interface ComputeReq {
  id: number; kind: 'compute';
  comp: CompositionState;
  opts: ComputeOpts;
}

self.onmessage = async (e: MessageEvent<EncodeReq | ComputeReq>) => {
  const r = e.data;
  try {
    if (r.kind === 'compute') {
      let lastReport = -1;
      const result = await computeComposition(r.comp, r.opts, (td, tt, phase, fd, ft) => {
        const step = Math.max(1, Math.floor(ft / 200));
        if (ft <= 1 || fd === ft || fd - lastReport >= step) {
          lastReport = fd;
          self.postMessage({ id: r.id, type: 'progress', tilesDone: td, tilesTotal: tt, phase, frameDone: fd, frameTotal: ft });
        }
      });
      self.postMessage({ id: r.id, type: 'done', ...result });
      return;
    }
    let lastReported = -1;
    const result = await encodeTilePayload(
      r.frames, r.frameDelays, r.tileCol, r.tileRow, r.gridCols, r.gridRows, r.allShades,
      {
        author: r.author, title: r.title, nonce: r.nonce, lossyQuality: r.lossyQuality,
        onProgress: (phase, done, total) => {
          // Throttle to ~200 messages per encode to avoid flooding the main thread.
          const step = Math.max(1, Math.floor(total / 200));
          if (done === total || done - lastReported >= step) {
            lastReported = done;
            self.postMessage({ id: r.id, type: 'progress', phase, done, total });
          }
        },
      },
    );
    self.postMessage({
      id: r.id, type: 'done',
      compressed: result.compressed, manifestBytes: result.manifestBytes, av1: result.av1,
    });
  } catch (err) {
    self.postMessage({ id: r.id, type: 'error', message: String((err as Error)?.message ?? err) });
  }
};
