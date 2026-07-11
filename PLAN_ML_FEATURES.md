# PLAN_ML_FEATURES.md — In-Browser ML Features for the Loominary Web Editor

## Context for the implementing agent

The Loominary web editor lives in `web/` (TypeScript) and is deployed as a **static site on GitHub Pages** (`https://zerohpminecraft.github.io/loominary/`). It imports images, quantizes them to the Minecraft map palette in Oklab space, applies adaptive Floyd–Steinberg dithering, tracks a compressed-byte budget, and exports carpet schematics.

**The byte budget is codec-mode-dependent** — do not hardcode a single number. The authoritative budgets live in `web/src/codec-mode.ts`: `BANNER` 5,290 · `CARPET` 8,176 · `CARPET_SHADE` 10,192 · `CARPET_BANNERS` 13,466 · `CARPET_SHADE_BANNERS` / `CARPET_BANNERS_SHADE` 15,482 bytes/tile. (The older `15,414` figure was the pre-LOOM total and is now stale.) Note that the editor's `computeDataStats()` in `web/src/editor/Editor.tsx` currently displays against the banner budget (`5290`, `banners = ceil((bytes+2)/84)`). Every ML "before/after byte delta" preview must read the **active** `codecMode` budget, never assume one number.

This plan adds ML-powered features. Hard constraints:

1. **No backend.** Everything runs client-side (ONNX Runtime Web / transformers.js) or calls an external API with a user-supplied key. Never embed or proxy API keys.
2. **GitHub Pages limits.** No file over 100 MB may be committed; keep the repo lean. Model weights are **fetched at runtime from the Hugging Face CDN** (or jsDelivr), never committed to the repo.
3. **The editor must stay instant.** No ML code or weights load until the user invokes an ML feature. Core import/edit/export must work offline-from-cache and on browsers without WebGPU.
4. **Every ML output funnels into the existing pipeline.** ML features produce RGBA pixel data; the existing Oklab quantizer, dither pass, and budget badge remain the single source of truth for what gets encoded. Never let an ML feature write map-color bytes directly.

**Before writing any code:** read `web/` to learn the actual module layout, build tool (Vite/esbuild/etc.), state management, and where the import pipeline and canvas tools live. Where this plan names file paths under `web/src/`, treat them as suggestions and adapt to the real structure. Also read `EDITOR.md` and `PIPELINE.md` at repo root for pipeline semantics. Record any assumption you make in the PR description.

---

## Phase 0 — ML infrastructure (prerequisite for everything else)

Goal: a small, shared runtime layer so each feature is a thin plug-in.

### 0.1 Dependencies
- Add `onnxruntime-web` (pin version). Prefer it over transformers.js for the vision models here, since we control pre/post-processing and it keeps the dependency surface small. If a chosen model is materially easier via `@huggingface/transformers`, that's acceptable — decide per model and note it.
- Configure the bundler (Vite — see `web/vite.config.ts`) to serve the ORT `.wasm`/`.mjs` runtime files from our own origin (copy into the build output), since GitHub Pages serves static assets fine and this avoids CDN-version skew with the JS API.
  - **Caveat:** unlike the zstd WASM (which `vite.config.ts` inlines as a base64 data URL under a 1 MB limit), ORT's WASM is far too large to inline — it must be emitted as a real asset and resolved through the `/loominary/` **base path** (`import.meta.env.BASE_URL`), not a root-absolute `/`. Confirm the Pages deploy workflow publishes these assets.

### 0.2 `web/src/ml/runtime.ts`
- Capability detection: WebGPU available? WASM SIMD? Threads (requires cross-origin isolation — GitHub Pages does **not** send COOP/COEP headers, so assume **no multithreaded WASM**; use single-threaded SIMD WASM as baseline and WebGPU when present).
- `createSession(modelId)`: builds an ORT `InferenceSession` with execution providers ordered `['webgpu', 'wasm']`, falling back gracefully.
- Session cache: at most N sessions resident (start with 2, LRU eviction) to bound memory.

### 0.3 `web/src/ml/registry.ts`
A declarative table of models:
```ts
interface MlModel {
  id: string;            // 'rmbg-1.4', 'slimsam-encoder', ...
  url: string;           // HF CDN resolve URL, pinned to a specific revision hash
  sha256: string;        // integrity check after download
  sizeBytes: number;     // for the download-consent dialog
  minBackend: 'wasm' | 'webgpu';
}
```
- **Pin every URL to an immutable revision** (`https://huggingface.co/<org>/<repo>/resolve/<commit-sha>/<file>`), not `main`, so upstream changes can't break or alter the app.
- Verify sha256 after download; refuse to run a mismatched file.

### 0.4 `web/src/ml/loader.ts`
- Download with progress events; cache weights in the **Cache API** (fall back to OPFS if needed) so repeat visits don't re-download.
- First-use consent dialog per model: "This feature downloads a 44 MB model from huggingface.co. [Download] [Cancel]" with a persisted "don't ask again" preference (per model).
- A "Manage downloaded models" entry in settings: list cached models with sizes, allow deletion.

### 0.5 `web/src/ml/worker.ts`
- Run all inference in a **Web Worker** (ORT supports this) so the canvas UI never jank-freezes. Define a tiny typed RPC: `{ run(modelId, tensors) -> tensors }` with transferable ArrayBuffers.
- All pre/post-processing (resize, normalize, NCHW packing) happens in the worker too.
- **Reuse existing infrastructure:** the editor already runs three ESM workers (`compress-worker.ts`, `filter-worker.ts`, `quantize-worker.ts`) dispatched via a `runWorkerPool` helper in `Editor.tsx`. Reuse that dispatch pattern rather than inventing a new pool. Instantiate with the literal Vite idiom — `new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })` — the URL must be a literal or Vite won't bundle it.

### 0.6 UI plumbing
- A shared "ML task" status component: spinner + progress bar + cancel button, reused by every feature.
- Every ML result enters the editor as a **previewable, undoable operation**, reusing the existing re-quantize preview pattern (overlay + Enter/Y to commit, Esc to discard) and the existing undo snapshot mechanism.

### Acceptance criteria (Phase 0)
- A dummy model (or the Phase 1 model) downloads with progress, caches, verifies, and runs in a worker on both a WebGPU browser and a WASM-only browser.
- Disabling network after first download still allows inference (cache hit).
- No ML code is in the main bundle's critical path (verify via bundle analysis: ML chunks are dynamic imports).

---

## Phase 1 — Background removal (first user-facing feature)

**Why first:** clearest value (subjects on transparent index-0 backgrounds), directly shrinks compressed bytes, smallest model, exercises all Phase 0 plumbing.

### Model
- Primary: **RMBG-1.4** (BriaAI) ONNX, quantized variant (~44 MB). Check the license note in-app (RMBG-1.4 is non-commercial; Loominary is MIT but the *feature's model* carries its own terms — surface this in the consent dialog).
- **Decision: ship RMBG-1.4 as the default** (quality-first). It is non-commercial, so the first-use consent dialog must clearly surface that license note (Loominary is MIT, but the model carries its own terms).
- Fallback/alternative: **MODNet** or **U²-Net (u2netp, ~4.5 MB)** — u2netp is small and Apache-licensed; quality is lower but acceptable. Implement against an interface so models are swappable, and keep u2netp available as the license-clean option — but RMBG-1.4 is the shipped default.

### Implementation
- `web/src/ml/features/backgroundRemoval.ts`
- Input: the **original source** at source resolution (not the quantized tile) whenever it is retained. **How retention actually works** (`web/src/persistence.ts`): the source is kept as the *original encoded file bytes* in the IndexedDB `session_images` store (≤20 MB), and the editor re-decodes + re-scales it on every requantize (the "R" path in `web/src/quantize.ts` `requantizeGrid`). So re-decode from that store rather than expecting a retained RGBA buffer; full source resolution is available, which is good for matte quality. If no source exists (e.g., stolen maps), run on the 128×128 canvas directly and warn that quality is reduced — upscaling the canvas first is NOT acceptable.
- Pipeline: resize to model input (e.g., 1024×1024 for RMBG, preserving aspect with padding) → infer alpha matte → resize matte back → threshold/feather control (slider, default soft threshold 0.5 with 1px feather) → composite: pixels under threshold become transparent → feed result through the **existing import/re-quantize path**.
- UI: button in the import dialog ("Remove background") and an editor action that re-derives the tile from source with the matte applied. Show before/after byte budget delta in the preview.

### Acceptance criteria
- A photo of a person/object on a busy background yields a clean cutout on transparency; budget badge visibly drops.
- Works on WASM-only browser in < ~10 s for a 1024² input; WebGPU substantially faster.
- Fully undoable; preview before commit.

---

## Phase 2 — Saliency auto-crop & mural framing

### Model
- **u2netp saliency** (reuse the Phase 1 fallback model if it's already shipped — saliency and matting from the same small network keeps total downloads tiny).

### Implementation
- `web/src/ml/features/autoCrop.ts`
- Compute a saliency map of the source image. From it:
  - **Single tile:** propose the best square crop (maximize contained saliency, padded ~5%). Show the proposed crop rectangle in the import dialog with draggable handles; "Auto" button re-runs.
  - **Mural (N×M):** score candidate placements so high-saliency regions (faces, focal subjects) avoid tile seam lines; render seam lines over the proposal so the user sees why.
- Pure post-processing on a small saliency map — keep this in TypeScript, no extra model.

### Acceptance criteria
- For an off-center subject, "Auto crop" lands a visibly sensible square; for a 4×2 mural, the subject's center is moved off seam intersections when possible.
- Suggestion only — user can always override; zero changes to default flow when unused.

---

## Phase 3 — Semantic selection ("Smart Wand")

**Why this is high-value for Loominary (consider promoting ahead of Phases 4–5):** selecting on a *dithered* 128px tile with the existing OKLab Magic Wand is genuinely painful — dithering shatters flat regions into noise, so flood-fill selection is unreliable. SAM selecting a whole subject in one click is arguably the single most useful ML feature for *this* editor. Run the encoder on the **full-res retained source** (re-decoded per B3 / Phase 1) for a usable embedding — running it on the 128px dithered canvas gives weak masks; fall back to canvas only when no source exists.

### Model
- **SlimSAM / MobileSAM** ONNX pair: image **encoder** (the heavy part, run once per image) + prompt **decoder** (tiny, runs per click).

### Implementation
- `web/src/ml/features/smartSelect.ts`
- New editor tool (suggest key `Q`, "Smart Wand"), sitting beside Magic Wand:
  - On activation: run the encoder **once** on the source image (or canvas if no source), cache the embedding per tile/frame; show progress while encoding.
  - On click: run the decoder with the point prompt → mask → downscale/align mask to the 128×128 (or grid) pixel space → set as the editor's selection mask (same mask structure the wand/lasso produce).
  - Shift+click adds positive points; Ctrl+click adds negative points (refine the same mask). Drag = box prompt if straightforward.
- Everything downstream is free: grow/shrink, Sel-tab color refinement, re-quantize, merge, reduction all already operate on selection masks.

### Acceptance criteria
- Click on a character selects the whole character (not just the contiguous color region) in one click on a typical image.
- Per-click latency after encoding: well under 1 s on WASM; near-instant on WebGPU.
- Multi-tile canvas mode: mask spans tiles correctly.

---

## Phase 4 — Neural pixelization import mode

**Treat this as two separable deliverables.** The **classical content-adaptive downscale modes** (below) attack Loominary's core problem — downscale-to-128² without turning detail to mud — are cheap, need no model download, and should ship **near-term** (right after Phase 0+1). The **neural pixelizer** depends on an uncertain model survey and should be a clearly-**deferred** follow-up; do not block the classical modes on it.

### Model
- A pixelization network (the "Make Your Own Sprites: Aliasing-Aware and Cell-Controllable Pixelization" lineage or a comparable open ONNX-exportable model). **Task for the agent:** survey what's currently available with an exportable checkpoint and a license compatible with redistribution-by-link; document the choice in `web/src/ml/MODELS.md`. If nothing suitable exists, implement the fallback below and mark the neural path as deferred.
- **Non-ML fallback (implement regardless, it's cheap and good):** content-adaptive downscaling — e.g., dominant-color/mode downsampling and edge-weighted area averaging — as additional "Downscale style" options (`average | dominant | edge-weighted | neural`) in the import dialog.

### Implementation
- `web/src/ml/features/pixelize.ts`
- Slots in **before** the existing quantize/dither stages: source RGBA → pixelize to 128×128 (or grid size) → existing Oklab quantization → existing dithering. The neural path must not bypass quantization.
- Side-by-side preview against the default downscale, with budget deltas shown for each.

### Acceptance criteria
- On a detailed photo or illustration, at least one new mode produces visibly crisper 128×128 results than plain averaging (sharper edges, less mud).
- Defaults unchanged; new modes are opt-in.

---

## Phase 5 — Artifact denoising (stolen maps & crusty GIFs)

### Model
- A small DnCNN/FFDNet-class denoiser exported to ONNX (a few MB). Alternatively skip the neural model and implement a **non-local means** filter in TS/WASM as `filter nlmeans` — decide based on measured quality on real stolen-map data; document the decision.

### Implementation
- `web/src/ml/features/denoise.ts`
- Expose as an additional entry in the existing filter cycle (Smooth → Median → Sharpen → Posterize → **Denoise**), following the existing convention: apply in-place, re-quantize to the existing tile palette so banner/carpet count never increases, respect selection scope, undoable.
- **Architecture caveat:** `web/src/filter-worker.ts` is a *pure spatial filter + requantize* worker with no ORT. The neural denoiser therefore can't live there — its inference runs in the Phase 0 ML worker, and only its RGBA output then feeds the existing requantize-to-same-palette step. The *UX* (a new entry in the P-key filter cycle) is unchanged; only the implementation path differs. (The non-neural `nlmeans` fallback *could* live in the spatial filter worker.)
- For stolen maps there is no RGB source — the input is already quantized map-bytes; the map-bytes → RGBA → denoise → requantize-to-same-palette path described here is the right (and only) one.
- For animated tiles: offer "current frame" and "all frames".

### Acceptance criteria
- A heavily-dithered/compressed GIF frame shows reduced speckle and a measurably lower distinct-color count after denoise + requantize, with edges preserved better than Smooth.

---

## Phase 7 — Smaller ML-adjacent enhancements (each independently shippable)

1. **Auto dither-mask painting.** Derive the dither-strength mask automatically: saliency/edge map → high strength on smooth gradient regions, zero on edges and flats (extend the existing Otsu approach with the u2netp edge/saliency output). Expose as "Auto-mask" button in the Dither Brush tool; result is just a painted mask the user can touch up.
2. **Palette suggestion.** K-means in Oklab on the source image to propose the N most useful map-palette colors before quantization, displayed as a suggested-palette strip; purely classical, no model download.
3. **Tiny-source upscale.** For sources smaller than the target (e.g., 64² art going to 128²), offer a small ESRGAN-lite/quantized 2× upscaler so edges stay clean instead of bilinear blur. Low priority; only if a <20 MB model is available.
4. **Smart "reduce" preview ranking.** When over budget, simulate the three reduction strategies (rarest/closest/weighted) a few steps each and show projected byte savings per strategy so the user picks with data. Classical, reuses existing reduction code.
5. **GIF frame importance scoring.** When a GIF is over budget, rank frames by inter-frame perceptual difference and suggest which to drop (smarter than uniform `stride`/`skip`). Classical (Oklab frame-diff), no model.

---

## Cross-cutting requirements

- **Bundle discipline:** every feature behind a dynamic `import()`. Measure main-bundle size before/after; it must not grow more than a few KB (feature registry stubs only).
- **Memory (has teeth here):** dispose ORT tensors/sessions deterministically; test a 10-import session for leaks (Performance/Memory panel). This codebase has already hit a WASM-heap OOM (commit `e324427`, "stream zstd above 1.5 MB to avoid 16 MB WASM heap OOM"); a 1024² RMBG run under single-threaded WASM is exactly the kind of allocation that will OOM on weak devices — budget for it and degrade gracefully.
- **Mobile/weak hardware:** features must either work (slowly) on WASM or disable themselves with a clear message; never crash the editor.
- **Privacy note in README/UI:** images never leave the browser — every ML feature runs fully client-side, and no feature makes external network calls beyond the one-time, consented model-weight download from the Hugging Face CDN.
- **Docs:** add `web/src/ml/MODELS.md` (model choices, revisions, hashes, licenses, why) and a user-facing section in the site/README ("AI tools" — what downloads, sizes, offline behavior).
- **Testing:** unit-test pre/post-processing (resize, NCHW packing, mask alignment to grid coords) with fixture tensors; add a manual test checklist per feature (WebGPU browser, WASM-only browser, multi-tile, animated tile, undo/redo).
- **CI:** GitHub Pages deploy workflow unchanged; ensure ORT wasm assets are copied into the published artifact and paths respect the `/loominary/` base path.

## Suggested implementation order & sizing

| Phase | Feature | Rough effort | Depends on |
|---|---|---|---|
| 0 | ML runtime/loader/worker | M | — |
| 1 | Background removal | M | 0 |
| 4 (classical) | Content-adaptive downscale modes | S–M | — (no model) |
| 7.2 / 7.4 / 7.5 | Classical helpers (palette, reduce ranking, GIF frame scoring) | S each | — (no model) |
| 3 | Smart Wand (SAM) — high utility for this editor | M–L | 0 |
| 2 | Saliency auto-crop | S | 0 (model shared w/ 1 if u2netp) |
| 7.1 | Auto dither-mask | S | 2 |
| 5 | Denoise filter | S–M | 0 |
| 4 (neural) | Neural pixelize — **deferred** | M–L | 0 |

Ship Phase 0+1 together as the first PR; everything after is independently mergeable. The classical, no-download items (Phase 4 downscale modes, Phase 7.2/7.4/7.5) are cheap high-ROI wins and can land before any further model work.
