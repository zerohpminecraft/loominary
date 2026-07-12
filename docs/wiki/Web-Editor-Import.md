# Web editor · Step 1: Import

The import step turns your source image into map-palette pixels. Everything happens locally in your browser — the image never leaves your machine.

![The import page before loading an image](assets/web/import-dropzone.png)

## Loading an image

Drop a file onto the dropzone or click to browse. Supported: **PNG, JPEG, WebP, BMP, and animated GIF** (GIFs bring all their frames — see [Animated Art](Animated-Art)). You can also **import a state JSON** you exported earlier to resume editing without the source image, and previous sessions are auto-saved in the browser for one-click resume.

## The preview pane

The right side shows the quantized result — exactly what the map will display. **Scroll to zoom, drag to pan.** Above it, a **match quality** score reports how many pixels landed within a perceptual hair's breadth of the original; use it to compare settings objectively.

![A loaded image with its quantized preview](assets/web/import-preview.png)

## Settings that matter

- **Grid** — how many maps the image spans (1×1 for a single map; see [Multi-Tile & Mux](Multi-Tile-and-Mux) for bigger). "Auto" suggests a grid from the aspect ratio.
- **Scale vs. center-crop** — fit the whole image or fill the frame.
- **Adjustments** — brightness, contrast, saturation, and friends, applied before quantization. Small saturation boosts often help; the map palette is muted.
- **Palette restriction** — limit which palette entries are used (e.g. exclude hard-to-obtain colors, or force a mood).
- **Dithering** — the algorithm and strength used to fake in-between colors. Adaptive Floyd–Steinberg in OKLab space is the default: gradients dither smoothly, hard edges stay crisp. Try Atkinson for a softer retro look, Bayer for patterns.
- **Match metric** — how "closest color" is judged. OKLab (perceptual) is almost always right; the chroma-boost slider trades luminance accuracy for more saturated palette use.

**Legal palette vs. all shades:** Minecraft's map format has 4 brightness shades per base color, but only 3 occur from real blocks. "All shades" unlocks the fourth for extra fidelity — at the cost that the art can only exist as encoded data, never as an actual block build. Leave it on unless you care about that distinction.

## Moving on

Click **Proceed to Editor →** when the preview looks right. Heavy images take a moment to encode — the button shows progress.

→ **[Step 2: Edit](Web-Editor-Editing)**
