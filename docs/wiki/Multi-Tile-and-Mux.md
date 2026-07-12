# Multi-tile murals & mux

One map is 128×128 pixels. A mural is just more maps: the web editor splits your image across an **N×M grid** of tiles, each its own map + schematic, hung as a wall of item frames.

![A 2×1 composition in the editor's grid view](assets/web/editor-multitile.png)

## Making a mural

1. At [import](Web-Editor-Import), set the grid (or let "auto" derive it from the aspect ratio). Quantization and dithering run on the **whole image before splitting**, so colors and dither patterns flow seamlessly across tile boundaries.
2. Edit as usual — the ⊞ Grid toggle edits the whole composition as one canvas.
3. Export produces one `.litematic` per tile, named `loominary_carpet_r<row>_c<col>.litematic`.

![Per-tile stats for a multi-tile export](assets/web/export-multitile.png)

In-game, place each tile's platform and scan a map for each ([placement guide](In-Game-Placement)); `/loominary tile next` steps the active tile through the grid for banner work. Hang the maps in a matching wall of item frames. `/loominary preview` on any one frame discovers the whole wall and paints every tile.

## Mux: sharing bytes between tiles

Tiles rarely fill their budgets evenly — a sky tile compresses to nothing while the busy center tile overflows. **Mux** fixes this by letting over-budget tiles (*receivers*) spill their overflow into the spare capacity of under-budget tiles (*donors*). The donor carries the guest bytes invisibly; the mod reassembles every payload before decoding.

- **In the web editor** it's automatic: the export page computes the allocation and shows which tiles donate to which. If even mux can't fit everything, you'll get a budget warning with the shortfall.
- **In-game** you can also run `/loominary mux` on a loaded batch to append dedicated blank donor tiles that exist only to carry overflow (undo with `/loominary mux undo`; inspect with `/loominary status donors`).

The allocation algorithm is implemented identically on both sides (locked by cross-language tests), so a web-exported mux always reassembles correctly in the mod.

## All-or-nothing decoding

A muxed receiver can only decode once its donors have been scanned, and a composite lossy animation needs **every** tile scanned. Until then, waiting tiles paint a status screen counting scanned tiles:

![WAITING status screen](assets/game/status-waiting.png)

Scan every map of the grid once and everything resolves.
