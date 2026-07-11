# AI tools (in-browser)

The Loominary web editor includes optional AI-assisted tools. **Everything runs
in your browser** — your images never leave the page. The only network traffic
is a one-time, consented download of a model file from huggingface.co the first
time you use a feature that needs one; downloads are cached so repeat visits are
offline-capable.

## Where to find them

- **Editor → "✨ AI tools…"** button (in the right-hand panel) opens the AI menu.
- **Editor → "✨ Smart" tool** in the toolbar (semantic click-to-select).
- **Import screen → "✨ Auto-crop to subject"** and the **Downscale style**
  selector.

## What each tool does, and what it downloads

| Tool | What it does | Download (first use) |
|---|---|---|
| Remove background | Cuts the subject onto a transparent background — shrinks the byte budget | RMBG-1.4, ~44 MB *(non-commercial license — shown in the dialog)* |
| Smart Wand | Click a subject to select the *whole thing*, even on dithered tiles | SlimSAM, ~32 MB |
| Auto-crop to subject | Finds the subject and crops to it before import | reuses the background-removal model |
| Denoise | Removes speckle from stolen maps / crusty GIFs, then re-quantizes | none (classical NL-means) |
| Suggest palette | Proposes the most useful map colors (k-means in Oklab) | none |
| Auto dither-mask | Dithers smooth areas, keeps edges crisp | none |
| Rank reduce strategies | Projects color savings for each reduction strategy | none |
| Score GIF frames | Ranks animation frames to pick which to drop when over budget | none |
| Downscale styles | Content-adaptive shrink (dominant / edge-weighted) for crisper 128² | none |

Every result is a **preview**: press **Enter** to keep or **Esc** to discard,
and **Ctrl+Z** undoes it like any other edit. Nothing is written to the map
encoding directly — AI output always flows through the normal quantize + budget
pipeline.

## Requirements & limits

- Works on any modern browser. **WebGPU** (Chrome/Edge, recent Firefox/Safari)
  makes the model tools much faster; otherwise they run on WASM (slower, still
  fine for one-off use). Weak devices may be slow but won't crash — features
  degrade or warn rather than freezing the editor.
- Background removal / Smart Wand need the **original source image**. For maps
  with no source (e.g. stolen maps) they run on the 128-grid with a quality
  warning, or are unavailable.
- Manage cached models from the model registry (Cache API, key
  `loominary-ml-weights-v1`).

See `src/ml/MODELS.md` for model revisions, licenses, and integrity hashes.
