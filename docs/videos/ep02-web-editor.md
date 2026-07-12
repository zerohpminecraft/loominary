# Ep. 02 — The Web Editor Deep-Dive

**Target length:** 6–7 min · **Audience:** watched ep. 1, wants better results

## Packaging

- **Title:** Get PERFECT Minecraft Map Art — Loominary Web Editor Masterclass
- **Thumbnail text:** "186 COLORS. USE THEM ALL." over a before/after quantization split
- **Description:**
  > Everything in Loominary's web editor: dithering algorithms compared, palette restriction, the pixel-editing tools, match metrics, and the tricks that make photos survive Minecraft's map palette. Editor: https://zerohpminecraft.github.io/loominary/ · Wiki: https://github.com/zerohpminecraft/loominary/wiki

## Setup checklist

- [ ] Three source images: a photo (hard), pixel art (easy), a gradient-heavy poster (dither showcase)
- [ ] Browser at 1080p+, dark theme

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **generated:** broll — same image with dither off → on, side by side | "Same image, same palette. The only difference is settings. This video is every setting that matters." |
| 0:20 | **generated:** broll — import page, `import-dropzone.png` still | "Import, edit, export. Step one decides ninety percent of your quality." |
| 0:35 | **generated:** broll — adjustments sliders reacting live | "First: adjustments. The map palette is muted and dark-biased, so push saturation up a notch and brightness a hair. Watch the match score — it's judging perceptual closeness, not vibes." |
| 1:20 | **generated:** broll — dither algorithm cycling on the gradient poster | "Dithering. Floyd–Steinberg in OKLab space is the default and it's adaptive — gradients dither, edges don't. Atkinson is softer and more retro. Bayer gives you that ordered, printy pattern. On gradients the difference is night and day." |
| 2:20 | **generated:** broll — palette restriction UI | "Palette restriction lets you ban colors — maybe you want a mood, maybe you want fewer distinct colors so the payload compresses smaller. Fewer colors, smaller bytes: remember that for the animation episode." |
| 2:50 | **generated:** broll — legal vs all-shades toggle | "Legal palette versus all shades: maps technically support a fourth brightness level no real block produces. Toggle it for free fidelity — unless you care that your art could theoretically exist as blocks." |
| 3:20 | **generated:** broll — editor: brush, fill, magic wand on the pixel art; still `editor-overview.png` | "Step two, the editor. Brush, fill, rect and lasso select, magic wand, eyedropper — a real pixel editor working directly in map colors, so nothing you paint can be undisplayable." |
| 4:10 | **generated:** broll — dither brush blending two regions | "The dither brush is the secret weapon: it paints a two-color checker blend for manual gradient repair." |
| 4:40 | **generated:** broll — palette panel, selecting a color, merging | "The palette panel shows every color with its pixel count. A color used six times costs you compression for nothing — merge it into its neighbor. This is also where you rescue an over-budget export." |
| 5:20 | **generated:** broll — export page stats; still `export-overview.png` | "Export shows the receipts: bytes per tile, carpet rows, overflow banners. Green means go." |
| 5:45 | **generated:** broll — sessions list on import page | "Everything auto-saves in your browser, source image included. Close the tab, come back tomorrow, resume." |
| 6:10 | end card | "Next episode: your map art starts moving. Animated GIFs." |

## B-roll manifest

- generated: broll videos — dither comparison, adjustments, palette restriction, editor tools, dither brush, palette merge, export stats, sessions (extend `web/e2e/broll.spec.ts` with these scripted flows)
- generated: stills `import-dropzone.png`, `editor-overview.png`, `export-overview.png`
- manual: none required (talking-head optional)
