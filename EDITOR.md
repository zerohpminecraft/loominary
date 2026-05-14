# Loominary Editor Guide

The in-world pixel editor is a full-featured 128×128 canvas (or N×M in multi-tile mode) that operates directly on Minecraft map-color bytes. Because it works in map-color space rather than RGB, what you paint is exactly what encodes — there is no re-quantization delay and no color drift from format conversion.

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

**Tolerance:** `=` / `-` keys (when no selection is active), or Shift+Scroll. Display: `tol:0.xx` in the status bar. A tolerance of 0 fills only exact color matches; higher values fill similar-looking areas.

Right-click while using Fill acts as an eyedropper.

### Rectangle Select (`S`)

Left-click and drag to draw a selection marquee. All subsequent paint, fill, re-quantize, and dither brush operations are clipped to the selection.

- `Ctrl+A` — select all
- `Ctrl+D` — deselect
- `=` / `Shift+=` — grow selection by 1 / 5 pixels outward (morphological dilation)
- `-` / `Shift+-` — shrink selection by 1 / 5 pixels inward (morphological erosion)
- `Esc` — deselect (also cancels lasso in progress)

Selections are stored as a pixel mask, so they can be any shape (including non-rectangular — see Lasso and Magic Wand below). In multi-tile mode the mask spans the full grid.

**Grow/Shrink** is useful after using Magic Wand to select a color region: select the interior, then press `=` once or twice to expand the selection outward and capture the outline pixels.

### Lasso (`L`)

Left-click and drag to draw a freehand selection boundary. Release the mouse button to close the path and convert the enclosed region to a pixel mask. The closed path is rasterized using Java's `Path2D` area fill. In multi-tile mode the lasso can span tile boundaries.

Once you have a lasso selection, all the usual selection operations apply (`Ctrl+D` to deselect, grow/shrink with `=`/`-`, combine with paint/fill/re-quantize, etc.).

### Magic Wand (`W`)

Left-click to select a contiguous region of similar colors. Uses the same 4-connected Oklab flood-fill as the Fill tool, with the same tolerance setting.

**Hover preview:** While the wand is active, a translucent blue overlay shows the region that would be selected if you clicked the pixel under the cursor. The preview updates live as you move the mouse or adjust tolerance.

**Drag to extend:** Hold the mouse button and drag across the canvas to union additional regions into the selection. Each pixel the cursor passes over triggers a new flood-fill from that point, ORed into the growing selection. Pixels already selected are skipped for efficiency. The selection is committed when you release the mouse.

**Tolerance:** `=` / `-` keys (when no selection is active), or Shift+Scroll. Shared with the Fill tool. Changing tolerance while hovering immediately updates the blue preview.

The Magic Wand is useful for selecting a large solid area (or several neighboring areas via drag) before applying a re-quantize or dither-brush pass only to that region. After selecting the interior of a shape, press `=` to grow the selection outward to capture its outline.

### Eyedropper (`E`)

Left-click to pick the color under the cursor and set it as the active paint color. After picking, the tool automatically reverts to whatever tool was active before — so `E`+click is a non-disruptive color sample.

The right-click eyedropper on Brush and Fill also works without switching tools at all.

### Dither Brush (`T`)

Paints values into a floating-point dither-strength mask (`float[16384]`). This mask controls how aggressively Floyd-Steinberg dithering is applied per pixel when you use **Re-Quantize** (`R`) with dither enabled.

- **Left-click / drag** — paint strength (0.0 to 1.0) into the mask
- **Right-click / drag** — erase (paint 0.0)
- **Brush radius:** `[` / `]`, or Shift+Scroll (shared with the paint brush)
- **Strength value:** `=` / `-` keys adjust the paint strength (when no selection is active)

**Mask overlay (`M`):** Toggle a yellow heat-map overlay that visualizes the current dither-strength values. Bright yellow = full dither, transparent = no dither.

The dither mask overrides the automatic Otsu-threshold map when you press `R` — wherever the mask is non-zero, that strength is used instead of the image-relative adaptive value. This lets you apply heavy dithering to gradients while keeping edges and solid fills clean.

---

## Copy / Paste

A clipboard lets you copy, cut, and paste any selection within or across tiles.

- `Ctrl+C` — copy the current selection to the clipboard. The bounding box is captured; transparent pixels and unselected slots within the box are skipped.
- `Ctrl+X` — cut: same as copy, then erase the selected pixels to transparent (undoable).
- `Ctrl+V` — enter **paste mode**. A floating preview of the clipboard appears under the cursor, center-anchored to the bounding box. Move the cursor to position it, then:
  - **Left-click** or **Enter / Y** — stamp the paste at the current position. Pixels that fall outside the canvas are skipped (count reported in the status bar). After committing, the pasted footprint becomes the active selection.
  - **Right-click** or **Esc** — cancel paste mode without writing anything.

Paste works in both single-tile and multi-tile canvas mode. Out-of-bounds pixels in the preview are shown in red to indicate they will be clipped. The stamp is undoable with `Ctrl+Z`.

---

## Palette Panel

The right side of the editor shows the active color palette with frequency data. Click any swatch to set it as the active paint color. **Hovering a swatch pulses a white highlight over every pixel of that color on the canvas** so you can see where it appears before committing to a change.

**Budget badge:** The palette panel header shows current budget usage — compressed bytes vs. the 15,414-byte ceiling for carpet tiles, or banner count vs. 63 for banner tiles. The display turns red when over budget. It updates whenever you make a change that would affect compressed size.

**Show: tabs** — Three tabs below the budget badge control which colors are listed:

- **Tile** (default) — colors present in the current frame, sorted by frequency. A small green bar below each swatch shows relative pixel count.
- **All** — every legal map color (~186 standard, ~248 with `allshades`). Use this to pick colors not yet present in the tile.
- **Sel** — colors present within the active selection only, with per-selection-pixel frequency bars. This tab appears automatically when you create a selection and switches back to Tile when the selection is cleared. You can switch tabs manually at any time.

**Shift+click a swatch in the Sel tab** to remove all pixels of that color from the current selection. This lets you quickly refine a wand or lasso selection by color — select a region, switch to Sel, then Shift+click any colors you don't want included. If the selection becomes empty it is cleared automatically.

**Transparency row:** A dedicated checkerboard swatch sits between the Show: tabs and the color grid. Click it to set transparent (index 0) as the active color. In Tile and Sel modes the swatch shows a frequency bar if the tile contains transparent pixels.

**Scrolling:** If the palette has more swatches than fit on screen, a scrollbar appears on the right edge of the panel. Scroll the mouse wheel while hovering the palette to scroll through it.

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

## Color Reduction (`K` / `Shift+K`)

Single-step palette reduction directly in the editor. Each press of `K` merges one color into its nearest neighbor — the same mechanism as `/loominary reduce`, but one step at a time so you can watch the effect live.

- `K` — Remove the rarest (or closest, or weighted-closest) color from the palette and remap every pixel using it to its nearest surviving color. If a selection is active, only colors present in the selection are candidates for merging.
- `Shift+K` — Cycle the active reduction strategy: **Rarest → Closest → Weighted**.

The current strategy is shown in the guide panel. The budget badge in the palette header updates after each step, so you can drive the tile under budget one merge at a time. Undoable with `Ctrl+Z`.

---

## Filter Tool (`P` / `Shift+P`)

The filter tool applies a spatial image filter to the current frame in-place, then re-quantizes each pixel back to the nearest color already present in the tile. Because re-quantization is restricted to the existing palette, the banner/carpet count does not increase.

**Selection scope:** If a selection is active, the re-quantization step after filtering only considers colors present within the selection as match candidates. This prevents the filter from pulling in colors from outside the region you're working on.

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

Re-quantizes the selection (or the whole tile if nothing is selected) from the original source image. This re-derives the best possible color mapping for the selected region, optionally with dithering. When a selection is active, the candidate palette for re-quantization is restricted to colors already present within the selection — so dithering can't introduce colors from outside the selected area.

**Steps:**
1. Optionally make a selection with Select, Lasso, or Magic Wand.
2. Optionally activate dithering with `D`.
3. Press `R`.
4. The editor enters **preview mode** — the re-quantized result is shown overlaid on the canvas with a yellow border.
5. Press `Enter` or `Y` to commit, `Esc` to discard.

In multi-tile mode, `Shift+R` runs a cross-tile Floyd-Steinberg pass across the entire grid simultaneously, eliminating color and dither discontinuities at seams. The preview is shown on all tiles before you commit.

When a dither mask exists and dither is on, `R` uses the mask's per-pixel strength instead of the image-relative Otsu threshold. Paint the mask first with `T` to control exactly where dithering is heavy vs. smooth.

**Dither toggle (`D`):** Toggle Floyd-Steinberg dithering on or off for re-quantize. The current state is shown in the status bar (`§aDITHER` when active).

**Source frame (animated GIF tiles):** The editor tracks which original GIF frame each editor frame came from, even after `/loominary stride` or `/loominary skip` has thinned the sequence. Re-quantize always pulls from the correct source frame automatically. If you want to manually override — for example to re-derive frame 3 from GIF frame 7 — use `Shift+[` / `Shift+]` to step the source frame index. The status bar shows `R source: GIF frame N of M` while stepping. If a re-quantize preview is already active, stepping the source frame live-updates the preview so you can see the result before committing. Press `Insert` to insert the re-quantized frame as a new frame after the current one without entering preview mode.

---

## Undo / Redo

- `Ctrl+Z` — Undo (up to 20 levels)
- `Ctrl+Y` — Redo

Every destructive operation (paint stroke, fill, lasso close, magic wand select + paint, re-quantize commit, color merge commit, filter apply, cut, paste) snapshots the full grid state before modifying it. In multi-tile mode this means `Ctrl+Z` reverts all tiles touched by the operation, not just the active one.

---

## Tile Minimap (`G`)

Press `G` to toggle a minimap overlay in the corner of the screen. For multi-tile batches, this shows a thumbnail of every tile in the batch arranged in the correct grid layout.

Click a thumbnail to jump directly to that tile's editor state without closing the screen. The active tile is highlighted.

The minimap is rendered at import time and cached; it only updates when you switch tiles.

---

## Multi-tile Canvas (`Shift+G`)

In multi-tile batches, `Shift+G` toggles **multi-tile canvas mode**. Instead of showing one 128×128 tile at a time, the canvas expands to show all tiles simultaneously — arranged in their correct grid positions.

- **Active tile** — rendered at full brightness with a highlighted border.
- **Other tiles** — rendered at reduced brightness.
- **Click a tile region** — switches the active tile without leaving the editor.
- **All selection tools** (lasso, wand, rect select) work across tile boundaries; a lasso path or wand drag can span multiple tiles.
- **Painting and fill** are still constrained to the active tile.
- **Grow/Shrink** (`=`/`-`) respects tile boundaries in the global mask.

Switching between single-tile and multi-tile mode clears the current selection (the selection mask size changes between modes).

In multi-tile mode, press `Shift+R` (rather than plain `R`) to run a seamless cross-tile dither pass.

---

## Animated Tiles

When editing an animated tile (imported from a GIF, or assembled from multiple frames), the editor loads all frames and provides per-frame navigation.

| Action | Control |
|---|---|
| Previous frame | `Ctrl+[` |
| Next frame | `Ctrl+]` |
| Decrease frame delay | `,` (−10 ms; Shift+`,` = −100 ms) |
| Increase frame delay | `.` (+10 ms; Shift+`.` = +100 ms) |
| Delete current frame | `Delete` (blocked if only one frame remains) |
| Insert frame from GIF source | `Insert` (animated GIF tiles only — inserts a frame after the current one, derived from the current R source frame) |
| R source: previous GIF frame | `Shift+[` (animated GIF tiles — overrides which source frame `R` reads; also live-updates the re-quantize preview if active) |
| R source: next GIF frame | `Shift+]` (animated GIF tiles only) |

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
| Multi-tile canvas | `Shift+G` |

The editor saves any unsaved changes to the current tile before switching. Undo history is reset per tile switch.

---

## Saving

The editor saves automatically when you close it (press `Esc` from the base state — no selection, no lasso in progress, no merge queue). The pixel data is re-encoded into the tile's banner chunks and written to the saved state file.

If you close without changes, the save is skipped. If the save fails (e.g., encoding error), an error message is printed to chat.

**Discard and exit:** Press `Shift+Esc` to close the editor immediately without saving any changes. This is useful if you've made edits you don't want to keep and undo would take too many steps. The shortcut is shown in red in the guide panel as a reminder.

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
| `=` | Increase tolerance (when no selection is active) |
| `-` | Decrease tolerance (when no selection is active) |
| Shift+Scroll | Increase / decrease tolerance |

### Dither Brush strength
| Key | Action |
|---|---|
| `=` | Increase paint strength (when no selection is active) |
| `-` | Decrease paint strength (when no selection is active) |

### Selection
| Key | Action |
|---|---|
| `Ctrl+A` | Select all |
| `Ctrl+D` | Deselect |
| `=` | Grow selection by 1 pixel (when selection is active) |
| `Shift+=` | Grow selection by 5 pixels (when selection is active) |
| `-` | Shrink selection by 1 pixel (when selection is active) |
| `Shift+-` | Shrink selection by 5 pixels (when selection is active) |
| `Esc` | Clear merge queue → cancel lasso → deselect → close |
| `Shift+Esc` | Discard all changes and close immediately |

When a selection is active, `=` and `-` control grow/shrink rather than Fill/Wand tolerance or Dither Brush strength.

### Copy / Paste
| Key | Action |
|---|---|
| `Ctrl+C` | Copy selection to clipboard |
| `Ctrl+X` | Cut selection (copy + erase selected pixels) |
| `Ctrl+V` | Enter paste mode (floating preview follows cursor) |
| Left-click / `Enter` / `Y` | Commit paste at cursor position (in paste mode) |
| Right-click / `Esc` | Cancel paste mode |

### Re-quantize / dither
| Key | Action |
|---|---|
| `R` | Re-quantize selection from source (enters preview mode) |
| `Shift+R` | Cross-tile re-quantize (multi-tile mode only; seamless dither across full grid) |
| `D` | Toggle dither for re-quantize |
| `M` | Toggle dither-mask heat-map overlay |
| `Enter` / `Y` | Commit preview |
| `Esc` | Cancel preview |

### Color merge
| Key | Action |
|---|---|
| `Ctrl+click` swatch or canvas pixel | Add / remove color from merge-source queue |
| `Shift+click` swatch (Sel tab only) | Remove all selected pixels of that color from the selection |
| `V` | Cycle merge scope: frame → tile → all tiles |
| `C` | Commit merge (sources → active color, in current scope) |
| `Esc` | Clear merge queue |

### Filter
| Key | Action |
|---|---|
| `Shift+P` | Cycle active filter type (smooth / median / sharpen / posterize) |
| `P` | Apply active filter to current frame |

### Color reduction
| Key | Action |
|---|---|
| `K` | Merge one color step (rarest/closest/weighted — whichever strategy is active) |
| `Shift+K` | Cycle reduction strategy: Rarest → Closest → Weighted |

### Undo / redo
| Key | Action |
|---|---|
| `Ctrl+Z` | Undo |
| `Ctrl+Y` | Redo |

### Tile / frame navigation
| Key | Action |
|---|---|
| `G` | Toggle tile minimap |
| `Shift+G` | Toggle multi-tile canvas mode (multi-tile batches only) |
| `Ctrl+Shift+[` | Previous tile (multi-tile batches) |
| `Ctrl+Shift+]` | Next tile (multi-tile batches) |
| `Ctrl+[` | Previous frame (animated tiles) |
| `Ctrl+]` | Next frame (animated tiles) |
| `,` | Decrease current frame delay by 10 ms |
| `.` | Increase current frame delay by 10 ms |
| `Shift+,` | Decrease current frame delay by 100 ms |
| `Shift+.` | Increase current frame delay by 100 ms |
| `Delete` | Drop current frame (blocked if only one frame) |
| `Insert` | Insert frame after current, derived from R source frame (animated GIF tiles only) |
| `Shift+[` | R source: previous GIF frame (animated GIF tiles only; live-updates preview) |
| `Shift+]` | R source: next GIF frame (animated GIF tiles only; live-updates preview) |

---

## Typical Workflows

### Touching up a specific area

1. Open the editor (`/loominary edit`).
2. Use Magic Wand (`W`) or Lasso (`L`) to select the area you want to fix.
3. Press `R` to re-quantize just that region from the original source image. Toggle `D` before pressing `R` if the area is a gradient that would benefit from dithering.
4. Accept with `Enter` or adjust with paint brush first.
5. Close to save.

### Selecting a region and its outline

1. Use Magic Wand (`W`) to select the interior of the shape (adjust tolerance with Shift+Scroll until the interior is covered cleanly).
2. Press `=` once or twice to grow the selection outward by 1–2 pixels, capturing the outline.
3. The selection now covers both the fill and the border and can be used for re-quantize, copy, or color reduction.

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
3. Press `Shift+G` to switch to multi-tile canvas mode and view the full mural at once.
4. Use `Ctrl+Shift+[` / `Ctrl+Shift+]` to step through tiles without leaving the editor.

### Trimming an animated GIF

1. Open the editor.
2. Navigate frames with `Ctrl+[` / `Ctrl+]` to find frames to remove.
3. Press `Delete` to drop the current frame.
4. Adjust delays with `,` / `.` as needed.
5. Close to save; re-run the anvil batch to place the updated tile.

### Refining a selection by color

1. Make a selection with Lasso or Magic Wand.
2. The palette panel switches to the **Sel** tab automatically, showing only colors present in the selection.
3. `Shift+click` any swatch you want to exclude — all pixels of that color are removed from the selection instantly.
4. Repeat until only the pixels you want remain selected.
