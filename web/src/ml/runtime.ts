/**
 * ML runtime configuration + capability detection.
 *
 * Design decision (deviation from PLAN_ML_FEATURES.md §0.1): rather than adding
 * `onnxruntime-web` as an npm dependency and bundling its WASM, we load ORT
 * dynamically from a **version-pinned** CDN at runtime (see ORT_* below). This
 * keeps ORT entirely out of the main Vite bundle (a Phase 0 requirement), keeps
 * `vite build` green with zero wasm-copy config, and still avoids version skew
 * because the version is pinned in the URL. The source is centralized here so it
 * can later be switched to a self-hosted copy by editing two constants.
 */

import type { Backend, Capabilities } from './types.js';

// ─── Pinned ORT distribution ──────────────────────────────────────────────────

/** Pinned ORT version. Bump deliberately; never float. */
export const ORT_VERSION = '1.20.1';

// In dev, route ORT (its .mjs entry AND the .wasm files it loads via wasmPaths)
// through the same-origin `/cdn-ort/` Vite proxy. The worker's cross-origin
// module import() of the jsDelivr bundle can fail CORS at the transport level
// ("CORS request did not succeed, status null"), notably in Firefox. Production
// loads from jsDelivr directly (ACAO:* — works); self-host by changing this base.
const ORT_IS_DEV = (import.meta as unknown as { env?: { DEV?: boolean } }).env?.DEV === true;
const ORT_CDN_BASE = ORT_IS_DEV ? '/cdn-ort/' : 'https://cdn.jsdelivr.net/';

/** Directory that holds ORT's .mjs entry + its .wasm files. */
export const ORT_DIST_BASE = `${ORT_CDN_BASE}npm/onnxruntime-web@${ORT_VERSION}/dist/`;

/**
 * ESM entry that bundles both the WebGPU and WASM execution providers.
 * (`ort.all.min.mjs` carries every backend; ORT picks per session options.)
 */
export const ORT_ESM_URL = `${ORT_DIST_BASE}ort.all.min.mjs`;

// ─── Capability detection (main thread) ───────────────────────────────────────

let cached: Capabilities | null = null;

export async function detectCapabilities(): Promise<Capabilities> {
  if (cached) return cached;

  const webgpu = await probeWebGpu();
  cached = {
    webgpu,
    wasmSimd: probeWasmSimd(),
    crossOriginIsolated: typeof globalThis.crossOriginIsolated === 'boolean'
      ? globalThis.crossOriginIsolated
      : false,
    offscreenCanvas: typeof OffscreenCanvas !== 'undefined',
  };
  return cached;
}

async function probeWebGpu(): Promise<boolean> {
  try {
    const gpu = (navigator as unknown as { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
    if (!gpu) return false;
    const adapter = await gpu.requestAdapter();
    return adapter != null;
  } catch {
    return false;
  }
}

/** Minimal SIMD probe: the bytes are a valid module iff SIMD is supported. */
function probeWasmSimd(): boolean {
  try {
    // (module (func (result v128) (v128.const i32x4 0 0 0 0)))
    const bytes = new Uint8Array([
      0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0,
      10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11,
    ]);
    return WebAssembly.validate(bytes);
  } catch {
    return false;
  }
}

/**
 * Choose the best backend that satisfies a model's minimum requirement, given
 * detected capabilities. Order: webgpu → wasm.
 */
export function chooseBackend(caps: Capabilities, minBackend: Backend): Backend {
  if (caps.webgpu) return 'webgpu';
  if (minBackend === 'webgpu') {
    // Model wants GPU but none available; fall back to wasm and let the caller
    // warn about reduced speed rather than refusing outright.
    return 'wasm';
  }
  return 'wasm';
}

/** ORT execution-provider list for a chosen backend, best-first with fallback. */
export function executionProviders(backend: Backend): string[] {
  return backend === 'webgpu' ? ['webgpu', 'wasm'] : ['wasm'];
}

const HF_ORIGIN = 'https://huggingface.co/';

/**
 * Resolve a model-weights URL for the current environment.
 *
 * In dev, rewrite huggingface.co downloads to the same-origin `/hf/*` Vite proxy
 * (see vite.config.ts). Browsers fail CORS when following HF's cross-origin
 * resolve→Xet-CDN redirect; the dev proxy follows that redirect server-side so
 * the request stays same-origin. In production the direct HF URL is used (its
 * responses reflect the page origin, so CORS succeeds).
 */
export function resolveWeightsUrl(url: string): string {
  const isDev = (import.meta as unknown as { env?: { DEV?: boolean } }).env?.DEV === true;
  if (isDev && url.startsWith(HF_ORIGIN)) {
    return '/hf/' + url.slice(HF_ORIGIN.length);
  }
  return url;
}
