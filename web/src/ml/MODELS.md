# ML models & runtime — choices, revisions, licenses

This document records every model the in-browser ML layer can download, why it
was chosen, the **pinned immutable revision**, and licensing. Nothing here is
bundled: weights are fetched at runtime from the Hugging Face CDN and cached in
the Cache API; the ONNX runtime is loaded from a pinned CDN (see below).

## Runtime: onnxruntime-web (pinned, CDN)

- **Version:** `1.20.1`, loaded from `https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/ort.all.min.mjs` (see `runtime.ts`).
- **Why CDN, not npm:** keeps ORT entirely out of the main Vite bundle (a Phase 0
  requirement), keeps `vite build` green with no wasm-copy config, and avoids
  version skew because the version is pinned in the URL. Switch to self-hosting
  by editing `ORT_DIST_BASE` / `ORT_ESM_URL` in `runtime.ts`.
- **Execution providers:** `webgpu` when available, else single-threaded SIMD
  `wasm`. GitHub Pages sends no COOP/COEP, so multithreaded WASM is unavailable
  (assumed off); WebGPU still works.

## Models

| id | file (pinned) | repo @ commit | size≈ | license | notes |
|---|---|---|---|---|---|
| `rmbg-1.4` | `onnx/model_quantized.onnx` | `briaai/RMBG-1.4` @ `2ceba5a5efaec153162aedea169f76caf9b46cf8` | 44 MB | **bria-rmbg-1.4 (NON-COMMERCIAL)** | Phase 1 default (quality-first). License surfaced in consent dialog. |
| `ormbg` | `ormbg.onnx` | `schirrmacher/ormbg` @ `6253b318240ef7a8670017b88d242f9f87f5abeb` | 88 MB | Apache-2.0 | License-clean background-removal fallback. |
| `sam-encoder` | `onnx/vision_encoder_quantized.onnx` | `Xenova/slimsam-77-uniform` @ `5850ab45f587c112167512ffef949107115e26a0` | 28 MB | Apache-2.0 | Phase 3 Smart Wand image encoder (run once/image). |
| `sam-decoder` | `onnx/prompt_encoder_mask_decoder_quantized.onnx` | `Xenova/slimsam-77-uniform` @ `5850ab45f587c112167512ffef949107115e26a0` | 4 MB | Apache-2.0 | Phase 3 Smart Wand prompt decoder (run per click). |

Phase 2 (saliency auto-crop) reuses the active background-removal model's alpha
matte as a saliency proxy — **no extra download**.

### `sha256` integrity hashes

The registry (`registry.ts`) leaves `sha256` empty. The loader treats an empty
hash as "skip verification" and the verification path is already wired
(`loader.ts` → `verifySha256`). To enforce integrity, fill each model's hash:

```sh
curl -sL "<pinned resolve URL>" | sha256sum
```

then paste the hex into the matching `sha256` field. Mismatches are then refused
at download time.

## Per-model IO assumptions

- **RMBG / ormbg:** input `[1,3,1024,1024]`, normalized `(v/255 - 0.5)/1.0`,
  aspect-preserving letterbox pad. Output is a single-channel matte; we min–max
  normalize, un-pad, resize to source, then threshold+feather to alpha.
- **SAM:** encoder input `[1,3,1024,1024]` with ImageNet normalization
  (`mean 0.485/0.456/0.406`, `std 0.229/0.224/0.225`), letterbox pad. The exact
  input/output **tensor names** of this export are matched heuristically from the
  session's reported names (`smartSelect.ts` → `pickName`), so the code tolerates
  `pixel_values` vs `input`, `input_points` vs `point_coords`, etc. Decoder fed
  `image_embeddings` + point coords (in the 1024 padded frame) + int64 labels;
  best mask chosen by IoU; 256² logits → sigmoid → grid selection mask.
  **If a future SlimSAM export changes its IO contract, update `smartSelect.ts`.**

## CORS / dev proxy

Browsers fail CORS when following HF's cross-origin `resolve` → Xet CDN
(`us.aws.cdn.hf.co`) redirect ("CORS request did not succeed, status null").
In **dev**, `resolveWeightsUrl` (runtime.ts) rewrites `huggingface.co` downloads
to the same-origin `/hf/*` Vite proxy (vite.config.ts), which follows the
redirect server-side. **Production** (GitHub Pages) fetches HF directly — its
responses reflect the page origin, so CORS succeeds there without a proxy. If a
self-hosted deployment is blocked from huggingface.co, host the weights on your
own origin and update the URLs in `registry.ts`.

## Deferred / not-yet-shipped

- **Neural pixelization (Phase 4 neural path):** no model with an exportable ONNX
  checkpoint **and** a redistribution-by-link-compatible license was available at
  build time. The classical content-adaptive downscale modes (`pixelize.ts`:
  average / dominant / edge-weighted) ship instead and cover the need well.
- **Neural denoiser (Phase 5 neural path):** the classical NL-means filter
  (`helpers/nlmeans.ts`) is the shipped default; a DnCNN/FFDNet ONNX model can be
  added behind the same feature later.
- **Tiny-source upscale (Phase 7.3):** deferred (low priority) — add a
  ≤20 MB ESRGAN-lite ONNX model to the registry and a feature when one is chosen.
