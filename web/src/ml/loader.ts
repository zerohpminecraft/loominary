/**
 * Model-weight loader: download with progress, integrity-check, and cache in the
 * Cache API so repeat visits don't re-download. Consent (the first-use download
 * dialog) is handled separately in `consent.ts`; this module assumes the caller
 * has already obtained consent and focuses on bytes.
 */

import type { MlModel } from './types.js';
import { resolveWeightsUrl } from './runtime.js';

export type ProgressFn = (loaded: number, total: number) => void;

const CACHE_NAME = 'loominary-ml-weights-v1';

async function openCache(): Promise<Cache | null> {
  try {
    if (typeof caches === 'undefined') return null;
    return await caches.open(CACHE_NAME);
  } catch {
    return null;
  }
}

/**
 * Resolve a model's weights as an ArrayBuffer: cache-first, else download with
 * progress callbacks, verify sha256 (when provided), and persist to cache.
 */
export async function fetchModelWeights(model: MlModel, onProgress?: ProgressFn): Promise<ArrayBuffer> {
  const cache = await openCache();
  if (cache) {
    const hit = await cache.match(model.url);
    if (hit) {
      onProgress?.(model.sizeBytes, model.sizeBytes);
      return await hit.arrayBuffer();
    }
  }

  // Fetch via the dev proxy when in dev (dodges HF's cross-origin redirect CORS
  // failure); the canonical model.url stays the Cache API key in all envs.
  const fetchUrl = resolveWeightsUrl(model.url);
  let resp: Response;
  try {
    resp = await fetch(fetchUrl, { mode: 'cors' });
  } catch (e) {
    throw new Error(
      `Could not download ${model.label} (network/CORS error). ` +
      `If this is a self-hosted deployment, the browser may be blocked from ` +
      `huggingface.co — host the weights on your own origin and update the registry. ` +
      `(${String((e as Error).message ?? e)})`,
    );
  }
  if (!resp.ok) throw new Error(`download failed (${resp.status}) for ${model.label}`);

  const total = Number(resp.headers.get('content-length')) || model.sizeBytes;
  const buffer = await readWithProgress(resp, total, onProgress);

  if (model.sha256) {
    const ok = await verifySha256(buffer, model.sha256);
    if (!ok) throw new Error(`integrity check failed for ${model.label} — refusing to run`);
  }

  // Persist a fresh Response (the streamed body is already consumed).
  if (cache) {
    try {
      await cache.put(model.url, new Response(buffer.slice(0), {
        headers: { 'content-type': 'application/octet-stream', 'content-length': String(buffer.byteLength) },
      }));
    } catch {
      // Cache write is best-effort (quota, private mode, etc.).
    }
  }
  return buffer;
}

async function readWithProgress(resp: Response, total: number, onProgress?: ProgressFn): Promise<ArrayBuffer> {
  if (!resp.body || !onProgress) return await resp.arrayBuffer();
  const reader = resp.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) { chunks.push(value); loaded += value.byteLength; onProgress(loaded, total); }
  }
  const out = new Uint8Array(loaded);
  let off = 0;
  for (const c of chunks) { out.set(c, off); off += c.byteLength; }
  return out.buffer;
}

async function verifySha256(buffer: ArrayBuffer, expectedHex: string): Promise<boolean> {
  if (typeof crypto === 'undefined' || !crypto.subtle) return true; // can't verify; allow
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return hex === expectedHex.toLowerCase();
}

// ─── Cache management (settings UI) ───────────────────────────────────────────

export interface CachedEntry { url: string; sizeBytes: number; }

export async function listCachedModels(): Promise<CachedEntry[]> {
  const cache = await openCache();
  if (!cache) return [];
  const keys = await cache.keys();
  const out: CachedEntry[] = [];
  for (const req of keys) {
    const resp = await cache.match(req);
    const len = Number(resp?.headers.get('content-length')) || 0;
    out.push({ url: req.url, sizeBytes: len });
  }
  return out;
}

export async function deleteCachedModel(url: string): Promise<boolean> {
  const cache = await openCache();
  if (!cache) return false;
  return await cache.delete(url);
}

export async function clearAllCachedModels(): Promise<void> {
  if (typeof caches === 'undefined') return;
  await caches.delete(CACHE_NAME);
}
