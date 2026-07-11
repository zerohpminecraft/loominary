/**
 * Shared types for the in-browser ML layer.
 *
 * The ML runtime (onnxruntime-web) is loaded dynamically from a pinned CDN at
 * runtime — it is never part of the main bundle.  We therefore declare a tiny
 * structural surface of the ORT API we use, rather than depending on its
 * package types at build time.  See `runtime.ts`.
 */

// ─── Minimal ORT surface ──────────────────────────────────────────────────────

export type TensorData =
  | Float32Array
  | Int32Array
  | BigInt64Array
  | Uint8Array;

export type TensorType = 'float32' | 'int32' | 'int64' | 'uint8' | 'bool';

/** Plain, transferable description of a tensor (crosses the worker boundary). */
export interface PlainTensor {
  type: TensorType;
  data: TensorData;
  dims: number[];
}

export interface OrtTensor {
  readonly type: string;
  readonly data: TensorData;
  readonly dims: readonly number[];
}

export interface OrtSession {
  readonly inputNames: readonly string[];
  readonly outputNames: readonly string[];
  run(feeds: Record<string, OrtTensor>): Promise<Record<string, OrtTensor>>;
  release?(): Promise<void>;
}

export interface OrtModule {
  Tensor: new (type: string, data: TensorData, dims: number[]) => OrtTensor;
  InferenceSession: {
    create(
      buffer: ArrayBuffer | Uint8Array,
      options?: Record<string, unknown>,
    ): Promise<OrtSession>;
  };
  env: {
    wasm: { wasmPaths?: string; numThreads?: number; simd?: boolean };
    [k: string]: unknown;
  };
}

// ─── Backends / capability ────────────────────────────────────────────────────

export type Backend = 'webgpu' | 'wasm';

export interface Capabilities {
  webgpu: boolean;
  wasmSimd: boolean;
  /** Cross-origin isolated → multithreaded WASM possible. GitHub Pages: false. */
  crossOriginIsolated: boolean;
  /** OffscreenCanvas available in workers (for in-worker resize). */
  offscreenCanvas: boolean;
}

// ─── Model registry ───────────────────────────────────────────────────────────

export interface MlModel {
  /** Stable identifier used everywhere (e.g. 'rmbg-1.4'). */
  id: string;
  /** Human label for dialogs. */
  label: string;
  /** Immutable HF/CDN resolve URL pinned to a commit SHA. */
  url: string;
  /** Hex sha256 of the file; verified after download. Empty string = skip (dev). */
  sha256: string;
  /** Approx download size, for the consent dialog. */
  sizeBytes: number;
  /** Lowest backend that can run this model acceptably. */
  minBackend: Backend;
  /** SPDX-ish license id surfaced in the consent dialog. */
  license: string;
  /** Extra licensing/usage note shown in consent (e.g. non-commercial). */
  licenseNote?: string;
}

// ─── Worker RPC protocol ──────────────────────────────────────────────────────

export type WorkerRequest =
  | { kind: 'init'; reqId: number; modelId: string; buffer: ArrayBuffer; minBackend: Backend }
  | { kind: 'run'; reqId: number; modelId: string; feeds: Record<string, PlainTensor> }
  | {
      kind: 'preprocess';
      reqId: number;
      bitmap: ImageBitmap;
      targetW: number;
      targetH: number;
      /** mean/std per channel for (v/255 - mean)/std normalization. */
      mean: [number, number, number];
      std: [number, number, number];
      /** 'pad' keeps aspect (letterbox), 'stretch' fills exactly. */
      fit: 'pad' | 'stretch';
      /** Output channel order. */
      layout: 'nchw' | 'nhwc';
    };

export type WorkerResponse =
  | { kind: 'init-done'; reqId: number; backend: Backend; inputNames: string[]; outputNames: string[] }
  | { kind: 'run-done'; reqId: number; outputs: Record<string, PlainTensor> }
  | {
      kind: 'preprocess-done';
      reqId: number;
      tensor: PlainTensor;
      /** Geometry needed to map results back to source pixels. */
      scale: number;
      padX: number;
      padY: number;
      drawW: number;
      drawH: number;
    }
  | { kind: 'error'; reqId: number; message: string };
