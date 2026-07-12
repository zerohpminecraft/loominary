# Web editor · Step 2: Edit

A full pixel editor operating directly in map-color space — every color you can pick is a color a map can display, so nothing here can break in-game.

![The editor with canvas, toolbar, and palette panel](assets/web/editor-overview.png)

This page is the tour; the complete tool-by-tool and key-by-key reference lives at **[Editor Tools & Shortcuts](Editor-Tools)**.

## The layout

- **Left toolbar** — the five tools: **B**rush, **F**ill, rect **S**elect, **L**asso, magic **W**and, plus tool options (brush radius/shape, OKLab tolerance for fill/wand) and the selection operations (grow/shrink/invert, copy/cut/paste).
- **Left panels** — the heavy machinery: **Requantize (R)** re-runs the entire [quantization pipeline](Dithering-and-Color-Matching) on the current pixels *or* the original source image, with preview-then-commit; **Filter (P)** offers smooth/median/sharpen/posterize; **Reduce (K)** strips one color at a time by strategy; **Color Merge** commits the palette panel's merge queue.
- **Right palette panel** — every color with live pixel counts across four tabs (all / frame / selection / total), sortable by hue, lightness, chroma, or frequency. **Ctrl+click swatches to queue merges** — the fastest way to clean up a noisy palette before [budget](Codecs-and-Capacity) trouble starts.
- **Status bar** — cursor position (grid + tile-local), hovered color, distinct-color count (turns amber past 120, red past 180 — a compression early-warning), zoom, and frame position.
- **Stats panel** (right edge) — per-tile byte usage, and for imported art a **Palette match** score measured against the actual quantized output across *all* animation frames (computed inside the quantize workers at import, so it's free).

## Three overlays worth knowing

- **H** — rarity heatmap: red pixels use rare colors (cheap to merge away).
- **Z** — compression detail map: bright regions are where your bytes go.
- **M** — the adaptive dither-strength mask from import, viewable and paintable.

## Working across tiles

The **⊞ Grid** toggle (step bar) switches between single-tile editing and the whole composition as one canvas — brush strokes, fills, and selections run straight across tile boundaries.

![Editing a 2×1 composition as one canvas](assets/web/editor-multitile.png)

## Animation

Animated compositions get the frame strip: scrubbing, playback (**Space**), per-frame delays (10–10,000 ms), clone/blank/delete/reorder, and stride/skip thinning. Details in [Editor Tools](Editor-Tools#animation-frames) and the [animation guide](Animated-Art).

![The frame strip](assets/web/editor-frames.png)

## Undo and persistence

Full-history undo (**Ctrl+Z**, 20 levels of complete frame snapshots), and the whole session — source image included — auto-saves to the browser continuously. Close the tab mid-edit; the import page's session history brings it all back.

→ **[Step 3: Export](Web-Editor-Export)**
