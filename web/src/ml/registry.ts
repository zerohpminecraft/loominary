/**
 * Declarative model registry.
 *
 * Every URL is pinned to an **immutable commit SHA** (not `main`) so upstream
 * changes can't alter or break the app. Commit SHAs were resolved from the
 * Hugging Face API at integration time and are documented in `MODELS.md`.
 *
 * `sha256` integrity hashes are intentionally left empty here: computing them
 * requires downloading each multi-megabyte weight file, which is done lazily at
 * runtime. The loader treats an empty hash as "skip verification" (acceptable
 * for first ship); fill these in from `MODELS.md` to enforce integrity. The
 * verification path in `loader.ts` is already wired and will reject mismatches
 * once hashes are present.
 */

import type { MlModel } from './types.js';

const HF = 'https://huggingface.co';

/** Build a pinned HF resolve URL. */
function hf(repo: string, sha: string, file: string): string {
  return `${HF}/${repo}/resolve/${sha}/${file}`;
}

export const MODELS = {
  // ── Phase 1: background removal (default, quality-first) ──────────────────
  // RMBG-1.4 is NON-COMMERCIAL — surfaced in the consent dialog.
  'rmbg-1.4': {
    id: 'rmbg-1.4',
    label: 'RMBG 1.4 (background removal)',
    url: hf('briaai/RMBG-1.4', '2ceba5a5efaec153162aedea169f76caf9b46cf8', 'onnx/model_quantized.onnx'),
    sha256: '',
    sizeBytes: 44_000_000,
    minBackend: 'wasm',
    license: 'bria-rmbg-1.4 (non-commercial)',
    licenseNote:
      'RMBG-1.4 is licensed for NON-COMMERCIAL use only. Loominary itself is MIT, ' +
      'but this model carries its own terms — do not use outputs commercially.',
  },

  // ── License-clean fallback for background removal / matting ────────────────
  // ormbg is an open (Apache-2.0) reimplementation of RMBG.
  'ormbg': {
    id: 'ormbg',
    label: 'Open RMBG (background removal, Apache)',
    url: hf('schirrmacher/ormbg', '6253b318240ef7a8670017b88d242f9f87f5abeb', 'ormbg.onnx'),
    sha256: '',
    sizeBytes: 88_000_000,
    minBackend: 'wasm',
    license: 'Apache-2.0',
  },

  // ── Phase 2 reuses a matte model's alpha as a saliency proxy ───────────────
  // (No separate saliency download; the active bg-removal model supplies it.)

  // ── Phase 3: Smart Wand (SAM) — encoder + decoder pair ─────────────────────
  'sam-encoder': {
    id: 'sam-encoder',
    label: 'SlimSAM vision encoder',
    url: hf('Xenova/slimsam-77-uniform', '5850ab45f587c112167512ffef949107115e26a0', 'onnx/vision_encoder_quantized.onnx'),
    sha256: '',
    sizeBytes: 28_000_000,
    minBackend: 'wasm',
    license: 'Apache-2.0',
  },
  'sam-decoder': {
    id: 'sam-decoder',
    label: 'SlimSAM prompt encoder + mask decoder',
    url: hf('Xenova/slimsam-77-uniform', '5850ab45f587c112167512ffef949107115e26a0', 'onnx/prompt_encoder_mask_decoder_quantized.onnx'),
    sha256: '',
    sizeBytes: 4_000_000,
    minBackend: 'wasm',
    license: 'Apache-2.0',
  },
} as const satisfies Record<string, MlModel>;

export type ModelId = keyof typeof MODELS;

export function getModel(id: ModelId): MlModel {
  return MODELS[id];
}
