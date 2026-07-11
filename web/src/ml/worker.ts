/**
 * ML inference worker.
 *
 * All ONNX inference and image pre-processing happen here so the canvas UI
 * never jank-freezes. ORT is imported dynamically from a pinned CDN (see
 * runtime.ts) — it never enters the main bundle. Sessions are cached by model
 * id with simple LRU eviction to bound memory.
 *
 * Instantiated by mlClient.ts via the Vite literal-URL idiom:
 *   new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
 */

import { ORT_ESM_URL, ORT_DIST_BASE, executionProviders } from './runtime.js';
import { rgbaToTensor } from './imageOps.js';
import type {
  Backend, OrtModule, OrtSession, OrtTensor, PlainTensor,
  WorkerRequest, WorkerResponse,
} from './types.js';

const MAX_SESSIONS = 2; // LRU bound (Phase 0.2)

let ortPromise: Promise<OrtModule> | null = null;
const sessions = new Map<string, { session: OrtSession; backend: Backend }>();

async function getOrt(): Promise<OrtModule> {
  if (!ortPromise) {
    ortPromise = (async () => {
      const mod = (await import(/* @vite-ignore */ ORT_ESM_URL)) as unknown as
        { default?: OrtModule } & OrtModule;
      const ort = (mod.default ?? mod) as OrtModule;
      // ORT fetches its .wasm files relative to wasmPaths; point at the CDN dir.
      ort.env.wasm.wasmPaths = ORT_DIST_BASE;
      // GitHub Pages sends no COOP/COEP → no cross-origin isolation → single
      // thread only. SIMD stays on.
      ort.env.wasm.numThreads = 1;
      ort.env.wasm.simd = true;
      return ort;
    })();
  }
  return ortPromise;
}

function toOrtTensor(ort: OrtModule, t: PlainTensor): OrtTensor {
  return new ort.Tensor(t.type, t.data, t.dims);
}

function toPlainTensor(t: OrtTensor): PlainTensor {
  // Copy the data out so it can be transferred without aliasing ORT buffers.
  const data = (t.data as Float32Array).slice();
  return { type: t.type as PlainTensor['type'], data, dims: [...t.dims] };
}

interface InitInfo { backend: Backend; inputNames: string[]; outputNames: string[]; }

async function ensureSession(modelId: string, buffer: ArrayBuffer, minBackend: Backend): Promise<InitInfo> {
  const existing = sessions.get(modelId);
  if (existing) {
    // Refresh LRU position.
    sessions.delete(modelId);
    sessions.set(modelId, existing);
    return info(existing);
  }
  const ort = await getOrt();
  const hasGpu = !!(self as unknown as { navigator?: { gpu?: unknown } }).navigator?.gpu;
  const want: Backend = hasGpu ? 'webgpu' : 'wasm';
  void minBackend;
  let session: OrtSession;
  let backend: Backend = want;
  try {
    session = await ort.InferenceSession.create(buffer, {
      executionProviders: executionProviders(want),
      graphOptimizationLevel: 'all',
    });
  } catch {
    // Fall back to wasm if the preferred EP failed to initialize.
    backend = 'wasm';
    session = await ort.InferenceSession.create(buffer, {
      executionProviders: executionProviders('wasm'),
      graphOptimizationLevel: 'all',
    });
  }
  const entry = { session, backend };
  sessions.set(modelId, entry);
  evictIfNeeded();
  return info(entry);
}

function info(entry: { session: OrtSession; backend: Backend }): InitInfo {
  return {
    backend: entry.backend,
    inputNames: [...entry.session.inputNames],
    outputNames: [...entry.session.outputNames],
  };
}

function evictIfNeeded(): void {
  while (sessions.size > MAX_SESSIONS) {
    const oldest = sessions.keys().next().value as string | undefined;
    if (oldest == null) break;
    const entry = sessions.get(oldest);
    sessions.delete(oldest);
    void entry?.session.release?.();
  }
}

/** Letterbox-resize an ImageBitmap into target dims, returning pixels + geometry. */
function letterbox(
  bmp: ImageBitmap, targetW: number, targetH: number, fit: 'pad' | 'stretch',
): { rgba: Uint8ClampedArray; scale: number; padX: number; padY: number; drawW: number; drawH: number } {
  const canvas = new OffscreenCanvas(targetW, targetH);
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  let scale = 1, padX = 0, padY = 0, drawW = targetW, drawH = targetH;
  if (fit === 'pad') {
    scale = Math.min(targetW / bmp.width, targetH / bmp.height);
    drawW = bmp.width * scale;
    drawH = bmp.height * scale;
    padX = (targetW - drawW) / 2;
    padY = (targetH - drawH) / 2;
    ctx.clearRect(0, 0, targetW, targetH);
    ctx.drawImage(bmp, padX, padY, drawW, drawH);
  } else {
    ctx.drawImage(bmp, 0, 0, targetW, targetH);
  }
  const img = ctx.getImageData(0, 0, targetW, targetH);
  return { rgba: img.data, scale, padX, padY, drawW, drawH };
}

self.onmessage = async (ev: MessageEvent<WorkerRequest>) => {
  const msg = ev.data;
  try {
    if (msg.kind === 'init') {
      const { backend, inputNames, outputNames } = await ensureSession(msg.modelId, msg.buffer, msg.minBackend);
      post({ kind: 'init-done', reqId: msg.reqId, backend, inputNames, outputNames });
    } else if (msg.kind === 'run') {
      const ort = await getOrt();
      const entry = sessions.get(msg.modelId);
      if (!entry) throw new Error(`session not initialized: ${msg.modelId}`);
      const feeds: Record<string, OrtTensor> = {};
      for (const [k, v] of Object.entries(msg.feeds)) feeds[k] = toOrtTensor(ort, v);
      const out = await entry.session.run(feeds);
      const outputs: Record<string, PlainTensor> = {};
      const transfer: ArrayBuffer[] = [];
      for (const [k, v] of Object.entries(out)) {
        const p = toPlainTensor(v);
        outputs[k] = p;
        transfer.push(p.data.buffer as ArrayBuffer);
      }
      post({ kind: 'run-done', reqId: msg.reqId, outputs }, transfer);
    } else if (msg.kind === 'preprocess') {
      const { rgba, scale, padX, padY, drawW, drawH } =
        letterbox(msg.bitmap, msg.targetW, msg.targetH, msg.fit);
      msg.bitmap.close();
      const tensor = rgbaToTensor(rgba, msg.targetW, msg.targetH, msg.mean, msg.std, msg.layout);
      post(
        { kind: 'preprocess-done', reqId: msg.reqId, tensor, scale, padX, padY, drawW, drawH },
        [tensor.data.buffer as ArrayBuffer],
      );
    }
  } catch (err) {
    post({ kind: 'error', reqId: msg.reqId, message: String((err as Error)?.message ?? err) });
  }
};

function post(resp: WorkerResponse, transfer: Transferable[] = []): void {
  (self as unknown as Worker).postMessage(resp, transfer);
}
