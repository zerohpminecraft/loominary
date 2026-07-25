# Ep. 02: The Web Editor Deep-Dive

**Target length:** 10–11 min · **Rendered:** 6:53 (2026-07-14, segments run only as long as narration + footage need; table timestamps below are pre-production estimates, the SRT is authoritative) · **Audience:** watched ep. 1, wants better results

## Packaging

- **Title:** Minecraft Maps Have 183 Colors. Mine Have 16 Million.
- **Thumbnail text:** "183 COLORS | 16,000,000" over the same photo split: palette-quantized | full-color
- **Description:**
  > Every setting in Loominary's web editor: all ten dithering algorithms, the six palette presets, selection-scoped requantize, filters, the palette panel, the codec byte budgets, and the full-color sRGB mode that skips the palette entirely. Editor: https://zerohpminecraft.github.io/loominary/ · Wiki: https://github.com/zerohpminecraft/loominary/wiki

## Setup checklist

- [ ] Four source images: a photo (adjustments + sRGB hero), pixel art (editor tools), a gradient-heavy poster (dither montage), a B&W portrait (greyscale preset)
- [ ] Browser at 1080p+, dark theme

## Narration script + shot list

Cues are narration AND burned captions, verbatim. Register: concise and factual, no in-group asides.

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **generated:** card, opening slide (`ep02-title`) | "Episode two: the web editor. Every setting, in the order you will meet them. None will be skipped." |
| 0:10 | **generated:** broll, editor landing, the three pages flashed in order | "Three pages: import, edit, export. Import decides ninety percent of your quality, so that is where we start." |
| 0:45 | **generated:** broll, dropping the photo on the import page; brief GIF-drop cameo | "Step one: drop in an image. Nothing uploads. Your browser does the work, and it keeps the original, which will matter later. Yes, it takes GIFs. Animation is episode three." |
| 1:15 | **generated:** broll, Grid & Crop: auto-suggested grid, typing 2×3, back to 1×1; scale vs center crop toggle | "One tile is 128 by 128 pixels in-game. Loominary reads your aspect ratio and suggests a grid. Type 2 by 3 and you have multi-tile art, that is episode four. Today, one tile. Scale stretches to fill it; center crop trims the edges. Choose crop." |
| 1:55 | **generated:** broll, Adjustments sliders reacting live on the photo | "Adjustments: brightness, contrast, saturation. The map palette is muted and dark-biased, so push saturation and lift brightness a little. Small moves." |
| 2:30 | **generated:** broll, Color mode radio: Map palette ↔ Full color (sRGB), preview switching | "Step four is a decision. Map palette quantizes every pixel to Minecraft's colors: editable, ditherable, classic. Full color skips the palette. True 24-bit sRGB, shipped as a lossy AV1 stream, painted straight into the map texture. Sixteen million colors on a vanilla map." |
| 3:05 | **generated:** broll, sRGB export panel: quality slider, ΔE/PSNR readout | "Full color gets a quality slider and a fidelity readout in delta-E and PSNR. Viewers need the latest release. Vanilla players still see decorative carpet either way. We continue in palette mode, the palette is where the settings live." |
| 3:35 | **generated:** card, six palette presets with counts; broll, cycling presets on the photo, then greyscale + chroma threshold slider on the portrait | "Palette: six presets. Flat fullblock, 61 colors. Staircase fullblock, 183. All shades, 244, including one shade no block placement can produce. Greyscale keeps colors under a chroma threshold you control. And two carpet presets, sixteen colors each, for people building actual carpet." |
| 4:25 | **generated:** broll, palette coverage score turning from yellow to green as the preset widens | "The coverage score judges how well the palette fits this image. Green at seventy-five percent. It measures the palette, not the dithering, so fix it here, not later." |
| 4:50 | **generated:** broll, dither montage on the gradient poster: all ten cycled, each name captioned; then strength slider + serpentine + chroma boost | "Quantization. Dithering spreads each pixel's matching error onto its neighbors. There are ten algorithms. Error diffusion: Floyd–Steinberg, three Sierras, Shiau-Fan, Jarvis-Judice-Ninke, Stucki. Atkinson, which drops error on purpose. Bayer, ordered. And none. Each has a strength slider. There is serpentine scanning. There is chroma boost." |
| 6:00 | **generated:** broll, Proceed to Editor; brush, fill, rect select, lasso, magic wand on the pixel art; right-click color pick | "The editor. Brush, fill, rectangle, lasso, magic wand, a real pixel editor working directly in map colors, so nothing you paint can be undisplayable. Right-click picks up any color under the cursor." |
| 6:45 | **generated:** broll, THE combo: wand-select the sky, open requantize, pick Bayer, apply, only the selection changes; then lasso a face, requantize from source with FS | "Select an area: rectangle, lasso, or wand. Requantize re-runs the whole quantizer on those pixels only: any dither, any palette, any strength, pulled fresh from your original source image. The rest of the art does not move. Bayer for the sky. Floyd–Steinberg for the lake." |
| 7:50 | **generated:** broll, filters cycling on a selection: smooth, median, sharpen, posterize | "Filters: smooth, median, sharpen, posterize. Posterize flattens photos into poster art. They respect your selection too." |
| 8:20 | **generated:** broll, palette panel: sort modes cycled, pixel counts, Ctrl+click merge queue, merge | "The palette panel counts every pixel of every color. Sort by frequency, lightness, chroma, hue, or natural byte order. A color used six times is costing you compression for nothing. Control-click queues it; merge sends its pixels to a color you choose. This is also how you rescue an over-budget export." |
| 9:05 | **generated:** card, codec byte table; broll, export page stats + codec picker | "Export shows the byte counts. Carpet alone carries 8,176 bytes. Add the shade channel: 10,192. Add overflow banners: 13,466. All three: 15,482. Banners alone need no platform at all: 5,290. These numbers are exact." |
| 9:40 | **generated:** broll, title/author fields; password field cameo; 3D schematic viewer orbit | "Give it a title and an author, the metadata travels with the art. The password field encrypts art for people you choose. Episode six. And before you export, preview the schematic in 3D. What you see is what you place." |
| 10:10 | **generated:** broll, sessions list on import page, restoring one | "Everything auto-saves in your browser, source image included. Close the tab. It will all be here tomorrow." |
| 10:25 | end card | "Next episode, your art starts moving." |

## Cards

- **Palette presets card:** six rows, Flat fullblock 61 · Staircase fullblock 183 · All shades 244 · Greyscale (chroma < threshold) · Flat carpet 16 · Staircase carpet 48
- **Codec byte table card:** BANNER 5,290 · CARPET 8,176 · CARPET+SHADE 10,192 · CARPET+BANNERS 13,466 · CARPET+SHADE+BANNERS / +BANNERS+SHADE 15,482, three channel icons (carpet / shade / banner)

## B-roll manifest

- generated: extend `web/e2e/broll.spec.ts`, pages tour, import drop + GIF cameo, grid & crop, adjustments, color-mode toggle + sRGB export panel, palette presets + greyscale threshold, coverage score, ten-dither montage (caption each algorithm name via timing marks), editor tools deep (5 tools + right-click pick), **selection→requantize combo** (wand + Bayer, lasso + FS from source), filters on selection, palette panel sort/merge, export stats + codec picker, title/author/password, 3D viewer orbit, sessions restore
- generated: cards `cards/ep02-title.html`, `cards/palette-presets.html`, `cards/codec-table.html`, `cards/ep02-end.html` (`record-cards.mjs`)
- no game footage: the original game cold open was cut in review (too close to ep01's reveal) in favor of the opening slide

## Production notes

- **Color-count canon: 183 for "regular Minecraft maps."** Ground truth is `web/src/palette.ts` (dumped from the running 1.21.4 client): 61 base colors × 3 obtainable shades = 183; the 4th shade brings it to 244 but is unobtainable via block placement, so public-facing claims say 183 (also the editor's default `legal` preset). 244 appears only when explicitly discussing the all-shades preset (the palette beat above). The old 248/186 figures counted MapColor 0, transparency. Ep01 says 183 too.

- DECtalk: spell "sRGB" as "S R G B" in the synth text (caption keeps "sRGB"); "delta-E" reads fine; hyphenate "Shiau-Fan" for the synth as "Shau Fan"; "Jarvis-Judice-Ninke" may need "Jarvis, Judice, Ninkey".
- The dither montage cue is long, let the montage breathe under it; captions for algorithm names come from the montage overlay, not the cue.
- The requantize segment is the episode's thesis. If anything gets cut for time, cut filters or 3D viewer, never this.
- Reality checks applied 2026-07-14: the editor UI has five tools (no dither-brush or eyedropper button, `DitherBrush.ts`/`Eyedropper.ts` exist but aren't in `ALL_TOOLS`; right-click picks color), and Adjustments has three sliders (no hue). Cues were adjusted to match.
