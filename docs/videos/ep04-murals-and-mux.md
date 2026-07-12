# Ep. 04 — Giant Murals: Multi-Tile & Mux

**Target length:** 5–6 min

## Packaging

- **Title:** Building WALL-SIZED Map Art in Minecraft (multi-tile murals)
- **Thumbnail text:** "4×3 = ONE IMAGE" over a big mural wall
- **Description:**
  > One map is 128×128 pixels. A mural is a grid of them. How Loominary splits images seamlessly, keeps dithering continuous across tile borders, and secretly moves bytes between maps when one tile is too detailed (mux). Wiki: https://github.com/zerohpminecraft/loominary/wiki/Multi-Tile-and-Mux

## Setup checklist

- [ ] A wide landscape image suited to ~3×2
- [ ] Space for 6 platforms + a 3×2 item-frame wall
- [ ] Pre-place most platforms off-camera; film one for the montage

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** finished 3×2 mural reveal, walk-up | "Six maps. One image. And the seams between them are invisible — here's why that's harder than it looks." |
| 0:25 | **generated:** broll — grid controls at import; still `import-multitile.png` | "Set the grid at import — or let auto derive it from the aspect ratio. Here's the important part: quantization and dithering run on the whole image *before* it's split. Dither on each tile separately and the pattern breaks at every border; this way it flows straight through." |
| 1:15 | **generated:** broll — ⊞ Grid canvas editing across a boundary; still `editor-multitile.png` | "The editor treats the mural as one canvas — brush straight across tile boundaries." |
| 1:45 | **generated:** broll — export per-tile stats; still `export-multitile.png` | "Export shows per-tile budgets, and here's where murals get interesting. Your sky tile compresses to almost nothing. Your busy center tile is over budget. Different tiles, same image, wildly different byte counts." |
| 2:30 | animated diagram (simple slides) | "Enter mux. Over-budget tiles — receivers — spill their overflow into the spare capacity of under-budget tiles — donors. The donor map carries a stowaway. When the mod decodes, it collects every piece and reassembles each payload. Fully automatic; the export page just shows you who's donating to whom." |
| 3:30 | **manual:** placement montage — tile platforms, `/loominary tile next`, frames going up | "In-game it's the episode-one flow times six. `/loominary tile next` steps through tiles for any banner work, and the exported schematics are named by row and column so you can't mix them up." |
| 4:20 | **manual/generated:** a tile showing WAITING screen (`status-waiting.png`), then resolving | "One catch: a muxed tile can't decode until its donors have been scanned — you'll see a waiting screen counting scanned tiles. Scan every map once and the whole wall resolves together." |
| 5:00 | **manual:** slow pan across the finished mural | "Seamless dithering, invisible byte smuggling, one image. Next episode, we stop placing carpets by hand." |

## B-roll manifest

- generated: broll grid/canvas/export flows; stills `import-multitile.png`, `editor-multitile.png`, `export-multitile.png`, `status-waiting.png`
- manual: mural reveal + pan, placement montage, waiting→resolved moment
- to make: 3–4 slide mux diagram (receivers/donors arrows) — any slide tool
