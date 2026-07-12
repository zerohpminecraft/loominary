# Web editor · Step 2: Edit

The editor is a full pixel editor working directly in map-color space — every color you can paint is a color a map can actually display.

![The editor with canvas, toolbar, and palette panel](assets/web/editor-overview.png)

## Tools

| Tool | What it does |
|---|---|
| **Brush** | Paint with the selected palette color; adjustable radius |
| **Dither brush** | Paint a two-color dither pattern — great for manual gradient touch-ups |
| **Fill** | Flood-fill contiguous color |
| **Rect select / Lasso** | Select regions to constrain painting, or cut/copy/paste them |
| **Magic wand** | Select all contiguous pixels of a color |
| **Eyedropper** | Pick a color from the canvas (or hold a modifier while painting) |

Undo/redo is full-history (Ctrl+Z / Ctrl+Y). Zoom with the scroll wheel, pan by dragging with the appropriate tool or modifier.

## The palette panel

The side panel lists every palette color used in the image with its pixel count. Use it to:

- **Spot budget problems** — rare colors cost compression space far out of proportion to their pixel count. A color used 4 times is usually noise worth merging away.
- **Select by color** — highlight everywhere a color occurs.
- **Merge / requantize** — collapse colors into their nearest neighbors to shrink the payload, with live preview.

## Animation frames

Animated compositions get a frame strip: step through frames, **clone** or **add blank** frames, delete, reorder, and set per-frame delays (or apply one delay to all). **Stride/Skip** thinning drops every Nth frame when a GIF is too heavy. See [Animated Art](Animated-Art) for how frames are encoded.

## Multi-tile canvas

For grid compositions, the **⊞ Grid** toggle in the step bar switches between editing one tile and the whole composition as a single canvas — selections, fills, and brushes work seamlessly across tile boundaries.

![Editing a 2×1 composition as one canvas](assets/web/editor-multitile.png)

## Sessions

Your work auto-saves to the browser (IndexedDB), including the source image — closing the tab loses nothing. The import page lists saved sessions for one-click resume.

→ **[Step 3: Export](Web-Editor-Export)**
