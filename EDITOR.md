# Loominary Editor Guide

The in-world pixel editor is a full-featured 128×128 canvas that operates directly on Minecraft map-color bytes. Because it works in map-color space rather than RGB, what you paint is exactly what encodes — there is no re-quantization delay and no color drift from format conversion.

Open the editor with `/loominary edit` (or your configured hotkey) while looking at a framed map.

---

## Navigation

| Action | Control |
|---|---|
| Pan canvas | Middle-click drag |
| Zoom | Scroll wheel |
| Zoom | `+` / `-` (numpad) |

The canvas is displayed at 4× scale by default and can zoom up to 8× depending on screen resolution. The status bar at the bottom always shows the pixel coordinate under the cursor and the active tool's parameters.

---

## Tools

Press the key to activate a tool. The active tool is shown in the status bar.

| Key | Tool |
|---|---|
| `B` | **Brush** |
| `F` | **Fill** (flood fill) |
| `S` | **Select** (rectangle) |
| `L` | **Lasso** (freehand selection) |
| `W` | **Magic Wand** (flood selection) |
| `E` | **Eyedropper** |
| `T` | **Dither Brush** |

### Brush (`B`)

Left-click or drag to paint pixels with the active color. Supports circle and square footprints — toggle with `X`. The cursor previews the brush footprint as you move.

**Brush radius:** `[` / `]` (without Ctrl), or Shift+Scroll. Range: 0 (single pixel) to 10.

**Right-click** on the canvas while using Brush or Fill picks the color under the cursor (eyedropper), without switching tools.

Painting is constrained to the active selection if one exists.

### Fill (`F`)

Left-click to flood-fill a region. The fill spreads to all 4-connected pixels whose Oklab perceptual distance from the clicked pixel is within the tolerance threshold.

**Tolerance:** `=` / `-` keys, or Shift+Scroll. Display: `tol:0.xx` in the status bar. A tolerance of 0 fills only exact color matches; higher values fill similar-looking areas.

Right-click while using Fill acts as an eyedropper.

### Rectangle Select (`S`)

Left-click and drag to draw a selection marquee. All subsequent paint, fill, re-quantize, and dither brush operations are clipped to the selection.

- `Ctrl+A` — select all
- `Ctrl+D` — deselect
- `Esc` — deselect (also cancels lasso in progress)

Selections are stored as a `boolean[16384]` pixel mask, so they can be any shape (including non-rectangular — see Lasso and Magic Wand below).

### Lasso (`L`)

Left-click and drag to draw a freehand selection boundary. Release the mouse button to close the path and convert the enclosed region to a pixel mask. The closed path is rasterized using Java's `Path2D` area fill.

Once you have a lasso selection, all the usual selection operations apply (`Ctrl+D` to deselect, combine with paint/fill/re-quantize, etc.).

### Magic Wand (`W`)

Left-click to select a contiguous region of similar colors. Uses the same 4-connected Oklab flood-fill as the Fill tool, with the same tolerance setting.

**Tolerance:** `=` / `-` keys, or Shift+Scroll. Shared with the Fill tool.

The Magic Wand is useful for selecting a large solid area before applying a re-quantize or dither-brush pass only to that region.

### Eyedropper (`E`)

Left-click to pick the color under the cursor and set it as the active paint color. After picking, the tool automatically reverts to whatever tool was active before — so `E`+click is a non-disruptive color sample.

The right-click eyedropper on Brush and Fill also works without switching tools at all.

### Dither Brush (`T`)

Paints values into a floating-point dither-strength mask (`float[16384]`). This mask controls how aggressively Floyd-Steinberg dithering is applied per pixel when you use **Re-Quantize** (`R`) with dither enabled.

- **Left-click / drag** — paint strength (0.0 to 1.0) into the mask
- **Right-click / drag** — erase (paint 0.0)
- **Brush radius:** `[` / `]`, or Shift+Scroll (shared with the paint brush)
- **Strength value:** `=` / `-` keys adjust the paint strength. Display: `s:0.x` in the status bar

**Mask overlay (`M`):** Toggle a yellow heat-map overlay that visualizes the current dither-strength values. Bright yellow = full dither, transparent = no dither.

The dither mask overrides the automatic Otsu-threshold map when you press `R` — wherever the mask is non-zero, that strength is used instead of the image-relative adaptive value. This lets you apply heavy dithering to gradients while keeping edges and solid fills clean.

---

## Palette Panel

The right side of the editor shows the colors currently in use in the tile, sorted by frequency (most common first). Click a swatch to set it as the active paint color.

**"All colors" toggle:** Below the palette, a checkbox expands the list to show all ~186 legal map colors (or ~248 with `allshades`). Useful for painting colors not yet present in the tile.

**Frequency tooltip:** Hover over a swatch to see the map-color byte index and how many pixels use that color.

---

## Color Merge Tool

The color merge tool lets you collapse multiple palette entries into one. This is useful when you want to manually consolidate colors that look similar, or free up palette slots before a re-quantize pass.

**How to use:**

1. `Ctrl+click` one or more swatches in the palette panel **or pixels on the canvas** to queue them as merge sources. Queued swatches are highlighted in orange. The palette header shows how many sources are queued.
2. Click a swatch (normal left-click) to set it as the target color.
3. Press `C` to commit — every pixel using a source color is repainted with the target color. The merge is undoable.

Press `Esc` to clear the merge-source queue without committing.

**Merge scope (`V`):** Press `V` to cycle the commit scope:
- **Frame** — merge only in the current frame (default)
- **Tile** — merge across all frames of this tile simultaneously
- **All tiles** — merge across every tile in the batch; other tiles are re-encoded immediately and undo state is saved for `/loominary reduce undo all`

The current scope is shown in the guide panel and in the status bar (`[2 merge/tile]` etc.). Scopes that aren't applicable (e.g., Tile when the tile is single-frame) are hidden from the cycle.

The merge tool is a quick alternative to `/loominary reduce` when you want precise control over which colors collapse together.

---

## Filter Tool (`P` / `Shift+P`)

The filter tool applies a spatial image filter to the current frame in-place, then re-quantizes each pixel back to the nearest color already present in the tile. Because re-quantization is restricted to the existing palette, the banner/carpet count does not increase.

**Controls:**
- `Shift+P` — cycle the active filter type: Smooth → Median → Sharpen → Posterize
- `P` — apply the active filter to the current frame (undoable with `Ctrl+Z`)

**Filter types:**
- **Smooth** — Gaussian blur, radius 1.0. Reduces noise and softens dithering.
- **Median** — Edge-preserving median, radius 1. Removes isolated pixel noise without blurring edges.
- **Sharpen** — Unsharp mask, amount 0.8. Clarifies soft edges.
- **Posterize** — Reduces each channel to 4 discrete tone levels, creating flat color regions that compress better.

The current filter type is shown in the guide panel. For more control (larger radius, all-frame or all-tile scope, and re-quantization to the full map palette rather than the existing tile palette), use `/loominary filter` from the command line instead.

---

## Re-Quantize (`R`)

Re-quantizes the selection (or the whole tile if nothing is selected) from the original source image. This re-derives the best possible color mapping for the selected region, optionally with dithering.

**Steps:**
1. Optionally make a selection with Select, Lasso, or Magic Wand.
2. Optionally activate dithering with `D`.
3. Press `R`.
4. The editor enters **preview mode** — the re-quantized result is shown overlaid on the canvas with a yellow border.
5. Press `Enter` or `Y` to commit, `Esc` to discard.

When a dither mask exists and dither is on, `R` uses the mask's per-pixel strength instead of the image-relative Otsu threshold. Paint the mask first with `T` to control exactly where dithering is heavy vs. smooth.

**Dither toggle (`D`):** Toggle Floyd-Steinberg dithering on or off for re-quantize. The current state is shown in the status bar (`§aDITHER` when active).

---

## Undo / Redo

- `Ctrl+Z` — Undo (up to 20 levels)
- `Ctrl+Y` — Redo

Every destructive operation (paint stroke, fill, lasso close, magic wand select + paint, re-quantize commit, color merge commit, filter apply) snapshots **all frames** before modifying them. This means `Ctrl+Z` after a Tile-scope merge or a filter correctly reverts all frames, not just the active one. Memory cost scales with frame count — roughly 20 × frameCount × 16 KB per undo level; negligible for typical animated tiles.

---

## Tile Minimap (`G`)

Press `G` to toggle a minimap overlay in the corner of the screen. For multi-tile batches, this shows a thumbnail of every tile in the batch arranged in the correct grid layout.

Click a thumbnail to jump directly to that tile's editor state without closing the screen. The active tile is highlighted.

The minimap is rendered at import time and cached; it only updates when you switch tiles.

---

## Animated Tiles

When editing an animated tile (imported from a GIF, or assembled from multiple frames), the editor loads all frames and provides per-frame navigation.

| Action | Control |
|---|---|
| Previous frame | `Ctrl+[` |
| Next frame | `Ctrl+]` |
| Go to first frame | `Ctrl+Shift+[` (when on single tile; navigates tiles in multi-tile batches) |
| Decrease frame delay | `,` (−10 ms; Shift+`,` = −100 ms) |
| Increase frame delay | `.` (+10 ms; Shift+`.` = +100 ms) |
| Delete current frame | `Delete` (blocked if only one frame remains) |

The status bar shows `Frame N/M` and the current frame's delay in milliseconds.

Frame delays are saved back into the payload on close. If you've edited delays, re-exporting the tile will embed the new timing.

**Note:** The dither mask is currently shared across all frames. Per-frame masks are a planned improvement.

---

## Tile Navigation (Multi-tile batches)

When your batch has more than one tile, you can switch between tiles without closing the editor:

| Action | Control |
|---|---|
| Previous tile | `Ctrl+Shift+[` |
| Next tile | `Ctrl+Shift+]` |

The editor saves any unsaved changes to the current tile before switching. Undo history is reset per tile switch.

---

## Saving

The editor saves automatically when you close it (press `Esc` from the base state — no selection, no lasso in progress, no merge queue). The pixel data is re-encoded into the tile's banner chunks and written to the saved state file.

If you close without changes, the save is skipped. If the save fails (e.g., encoding error), an error message is printed to chat.

For carpet tiles, closing the editor does **not** automatically re-export the schematic. Run `/loominary export` after editing if you need an updated `.litematic`.

---

## Full Keyboard Reference

### Tools
| Key | Action |
|---|---|
| `B` | Brush |
| `F` | Fill |
| `S` | Rectangle Select |
| `L` | Lasso |
| `W` | Magic Wand |
| `E` | Eyedropper (reverts to previous tool after one click) |
| `T` | Dither Brush |
| `X` | Toggle brush shape: circle ↔ square |

### Brush / Dither Brush radius
| Key | Action |
|---|---|
| `[` | Decrease radius by 1 |
| `]` | Increase radius by 1 |
| Shift+Scroll | Increase / decrease radius |

### Fill / Magic Wand tolerance
| Key | Action |
|---|---|
| `=` | Increase tolerance |
| `-` | Decrease tolerance |
| Shift+Scroll | Increase / decrease tolerance |

### Dither Brush strength
| Key | Action |
|---|---|
| `=` | Increase paint strength |
| `-` | Decrease paint strength |

### Selection
| Key | Action |
|---|---|
| `Ctrl+A` | Select all |
| `Ctrl+D` | Deselect |
| `Esc` | Clear merge queue → cancel lasso → deselect → close |

### Re-quantize / dither
| Key | Action |
|---|---|
| `R` | Re-quantize selection from source (enters preview mode) |
| `D` | Toggle dither for re-quantize |
| `M` | Toggle dither-mask heat-map overlay |
| `Enter` / `Y` | Commit preview |
| `Esc` | Cancel preview |

### Color merge
| Key | Action |
|---|---|
| `Ctrl+click` swatch or canvas pixel | Add / remove color from merge-source queue |
| `V` | Cycle merge scope: frame → tile → all tiles |
| `C` | Commit merge (sources → active color, in current scope) |
| `Esc` | Clear merge queue |

### Filter
| Key | Action |
|---|---|
| `Shift+P` | Cycle active filter type (smooth / median / sharpen / posterize) |
| `P` | Apply active filter to current frame |

### Undo / redo
| Key | Action |
|---|---|
| `Ctrl+Z` | Undo |
| `Ctrl+Y` | Redo |

### Tile / frame navigation
| Key | Action |
|---|---|
| `G` | Toggle tile minimap |
| `Ctrl+Shift+[` | Previous tile (multi-tile batches) |
| `Ctrl+Shift+]` | Next tile (multi-tile batches) |
| `Ctrl+[` | Previous frame (animated tiles) |
| `Ctrl+]` | Next frame (animated tiles) |
| `,` | Decrease current frame delay by 10 ms |
| `.` | Increase current frame delay by 10 ms |
| `Shift+,` | Decrease current frame delay by 100 ms |
| `Shift+.` | Increase current frame delay by 100 ms |
| `Delete` | Drop current frame (blocked if only one frame) |

---

## Typical Workflows

### Touching up a specific area

1. Open the editor (`/loominary edit`).
2. Use Magic Wand (`W`) or Lasso (`L`) to select the area you want to fix.
3. Press `R` to re-quantize just that region from the original source image. Toggle `D` before pressing `R` if the area is a gradient that would benefit from dithering.
4. Accept with `Enter` or adjust with paint brush first.
5. Close to save.

### Painting a custom dither gradient

1. Open the editor.
2. Press `T` to activate the Dither Brush.
3. Press `M` to show the heat-map overlay.
4. Paint high strength (up to 1.0) into the smooth areas you want to dither; leave edges and flat-color regions at 0. Adjust strength with `=` / `-`.
5. Optionally switch back to Select (`S`) or Lasso (`L`) to restrict the re-quantize to a sub-region.
6. Press `D` to enable dither, then `R` to re-quantize using your mask.
7. Commit with `Enter`.

### Collapsing similar palette entries

1. Open the editor. The palette panel on the right shows swatches by frequency.
2. `Ctrl+click` the colors you want to eliminate (they turn orange).
3. Click the swatch you want them to merge into (this becomes the target).
4. Press `C` to commit. Undo with `Ctrl+Z` if the result isn't right.

### Editing a specific tile in a mural

1. Run `/loominary tile <n>` to switch to the target tile, then `/loominary edit`.
2. Or: open the editor on any tile, press `G` to show the minimap, and click the target tile thumbnail.
3. Use `Ctrl+Shift+[` / `Ctrl+Shift+]` to step through tiles without leaving the editor.

### Trimming an animated GIF

1. Open the editor.
2. Navigate frames with `Ctrl+[` / `Ctrl+]` to find frames to remove.
3. Press `Delete` to drop the current frame.
4. Adjust delays with `,` / `.` as needed.
5. Close to save; re-run the anvil batch to place the updated tile.
