# Ep. 04: Mux & Multi-Tile Art (bigger animations, spread across maps)

**Target length:** ~8 min · **Audience:** eps. 1–3 viewers; the "how does that even fit" one

## Packaging

- **Title:** Fitting Bigger Animations on Vanilla Maps (by smuggling bytes between them)
  - Alt titles (pick at upload): "Minecraft Maps That Smuggle Data Into Each Other" · "One Animation Across Many Vanilla Maps, No Seams, No Server"
- **Thumbnail text:** "TOO BIG → FITS" over an over-budget tile spilling arrows into blank donor maps
- **Description:**
  > A single Minecraft map holds about 15 KB. Detailed animations need more. Loominary's mux automatically spreads a tile's overflow into the spare room on other maps, and adds blank donor maps when it has to, so bigger animations fit on vanilla servers. Plus: splitting one image across a grid with seamless dithering, and encoding an animated grid as a single seam-free stream. Editor: https://zerohpminecraft.github.io/loominary/ · Wiki: https://github.com/zerohpminecraft/loominary/wiki/Multi-Tile-and-Mux

## Setup checklist

- [ ] The heavy animation from ep03 (`sample-anim-heavy.gif`) for the single-tile-plus-blank-donors opener (it already trips "+N blank donors" on export)
- [ ] A 3:2 landscape source for the 3×2 split demo (generate `sample-grid32.png`)
- [ ] A wide animated source for the composite demo (generate a 2×1 animated fixture)
- [ ] Browser at 1080p+, dark theme; no live game capture needed (generated per ep01–03)

## Narration script + shot list

Cues are narration AND burned captions, verbatim. Register: dry and plain, concise and factual, no in-group asides. Burned captions are chunked to one row at a time by the assembler; write cues as normal sentences. In-game beats are tagged **manual/game (REAL...)** for the re-shoot; the rendered cut uses generated substitutes until then.

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **generated** (export stats: heavy animation over budget ✗ 1384%, then "+N blank donors" appears and it flips to fit ✓) **+ manual/game (REAL):** the same animation then playing in-world on a framed map beside its blank donor maps | "This animation does not fit on one Minecraft map. It is too detailed. Watch it fit anyway." |
| 0:18 | **generated:** card, ep04 title (`ep04-title`) | "Episode four. When one map is not enough, Loominary spreads the overflow onto others. It is called mux, and it is the reason your animations can get big." |
| 0:35 | **generated:** card, budget recall (`ep04-budget`, one tile ≈ 15,482 B) | "Remember the number from episode three. One map tile carries, compressed, at most about fifteen thousand bytes. A detailed animation blows straight past that. On any other tool, that is where the art stops." |
| 1:05 | **generated:** broll, the export Mux panel on the heavy GIF: receiver row, blank donor rows, routes to/from | "Loominary does not stop. A tile that runs over budget becomes a receiver, and its overflow spills into the spare room on other tiles, the donors. If there are no spare tiles nearby, it appends blank donor maps for you. You place a couple of extra maps, and the animation fits." |
| 1:45 | **generated:** broll, the Mux panel again, cursor tracing routes; the Export button reading "Export ZIP (mux)" | "The export page shows the whole ledger: every receiver, every donor, every blank one it added, and exactly which bytes route where. You do not press anything. Mux has no button. It is computed the instant a tile goes over budget." |
| 2:25 | **generated:** card, mux mechanism (`ep04-mux`, donor stamps a descriptor; one allocator both sides) | "Underneath: each donor carries a tiny routing descriptor, whose bytes it is holding and where they belong. The identical allocator runs in your browser and in the mod, so a layout baked on the web always reassembles in game, byte for byte. If those two ever disagreed, the mod would refuse the tile and say so. They do not disagree." |
| 3:05 | **manual/game (REAL):** the art map plus its blank donor maps framed together; an empty map scans each; the animation resolves and plays | "In game it looks like this. The art map, plus its blank donor maps, hung together. Scan every one of them once, the mod gathers the pieces, and the animation plays. Those donor maps look blank to anyone without the mod, but they are carrying part of the video." |
| 3:45 | **generated:** broll, Grid & Crop at import on the 3×2 landscape: Cols/Rows, auto, Scale vs Center crop | "Mux also handles the other reason to use more than one map: plain size. Drop a wide image and set a grid, columns by rows, or let Loominary suggest one from the aspect ratio. Three by two. Six tiles, one picture." |
| 4:20 | **generated:** broll, the seamless preview across tile borders; then a flash of the grid lines | "Here is what people get wrong. Quantize and dither the whole image first, then cut it into tiles. Dither each tile on its own and the pattern breaks at every border. Do it in this order and it flows straight through, so the seams are invisible." |
| 4:55 | **generated:** broll, editing across a tile boundary in the editor | "The editor treats the grid as one canvas. Brush straight across a tile boundary. The split is never your problem while you paint." |
| 5:25 | **generated:** broll, per-tile export stats: sky tiles tiny, center tile over budget, mux badges | "Export shows every tile's budget. The sky tiles compress to almost nothing. The busy tile in the middle is over. Same image, wildly different byte counts, and mux quietly moves the middle tile's overflow into the sky. It is the same feature as the opener." |
| 6:05 | **generated:** broll, the wide animated grid with Lossy on, encoding as one composite stream split across tiles | "And for animation across a grid there is a cleaner path still. Turn on lossy, and the whole grid encodes as a single video stream, then splits across the tiles. No per-tile seams, no per-tile budgets to referee. Loominary only takes it when it beats the tile-by-tile version, which, for animation, it usually does." |
| 6:45 | **manual/game (REAL):** placing the tiles of a real grid, then a schematic list named by row and column | "Placement is episode one, once per tile. Loominary exports one schematic per tile, named by row and column, so you cannot cross them. For banner work, tile next steps through them." |
| 7:15 | **manual/game (REAL):** the framed grid wall, tiles self-identifying | "Hang the frames in the same grid shape as the export, and each tile knows which one it is from the data inside it. The frame grid has to match the export exactly." |
| 7:40 | **manual/game (REAL):** the animated grid wall, all tiles on the same frame | "And because every tile shares one sync group, an animated grid stays in lockstep, the whole wall on the same frame at the same instant. Episode three, at scale." |
| 8:00 | **generated:** card, ep04 end (`ep04-end`) | "Next episode, we stop placing all this carpet by hand. The mod walks the build, drives the Litematica printer to lay the schematic, and manages your carpet as it goes." |

## Cards

- **ep04 title card (`ep04-title`):** "Mux." / "when one map is not enough", arrows-between-maps motif matching the thumbnail.
- **budget recall card (`ep04-budget`):** callback to ep03, "one tile ≈ 15,482 bytes" over "a detailed animation needs more" → spill arrows. Reuse the carpet/shade/banner channel chips for series continuity.
- **mux mechanism card (`ep04-mux`):** a receiver tile overflowing arrows into a donor tile and a blank donor map; each donor stamped with a small "descriptor" tag; caption "one allocator, both sides, so it always reassembles."
- **ep04 end card (`ep04-end`):** teases ep05 (autonomous printing); series-consistent with `ep03-end`.

## B-roll manifest

- generated: extend `web/e2e/broll.spec.ts`, **Mux panel** on `sample-anim-heavy.gif` (toggle lossy off/on to move it over/under budget, hover the routes; the Export button shows "(mux)"), **Grid & Crop** on the 3×2 landscape (auto-suggest, Cols/Rows override, Scale vs Center crop), **seamless preview** across borders + grid-line flash, **editing across a boundary**, **per-tile stats** with mux badges, **composite** encode on the wide animated grid (Lossy on → one stream). New fixtures via `gen-fixtures.mjs`: `sample-grid32.png` (3:2 landscape) and a 2×1 animated source; reuse `sample-anim-heavy.gif`.
- generated: cards `cards/ep04-{title,budget,mux,end}.html` (via `record-cards.mjs`).
- generated substitutes for the "in-game" beats (per ep01–03, no live capture): the mux opener and blank-donors-decode from a framed-GIF composite plus the export ✗→✓ recording; placement recap from `out/raw/game.mkv` (plat+scan slice); animated grid wall as a synced 3×2 GIF tiling (`grid` kind, extend to 3×2). `assemble-ep04.py` copies `assemble-ep03.py` (same single-row caption logic, same `anim`/`sync`/`grid`/`game` kinds; add a 3×2 grid variant).

## Production notes

- **Register:** dry and plain, concise and factual, no in-group asides (scrubbed 2026-07-14). Burned captions are one row at a time (assembler's `wrap_chunks`).
- **The mux payoff is the thesis.** The opener must land "too big → fits" before any mechanism. If anything is cut for time, cut the editor-canvas beat or the composite beat, never the opener or the Mux-panel beat.
- **Reality-check corrections (verified against source, 2026-07-14):**
  - **Mux is fully AUTOMATIC, there is no button.** An effect recomputes the allocation whenever stats/codec/encryption change and appends blank donors until nothing is unresolved. The export page only *shows* the routing table; the export button label becomes **"⬇ Export ZIP (mux)"**. The script says "you do not press anything" and "there is no mux button" on purpose. Do NOT tell viewers to click a Mux control.
  - **The over-budget modal's "compute Mux" wording is misleading**, that modal (`⚠ Over Budget`) only appears when mux *cannot* resolve (no room even with blank donors). In the normal case it never shows. Don't feature it as the happy path.
  - **The UI never says "mural."** It says Grid & Crop, grid, tile, multi-tile, Mux, donor/receiver, blank donor. Match it. (This whole episode was renamed off "Giant Murals".)
  - **Mux panel strings** (verbatim): heading **"Mux"**, status **"✓ auto-applied on export"**, badge **"+{n} blank donor(s)"**, roles `normal`/`receiver`/`donor`, blank auto-donors labeled **"blank {n}"** with an **"auto"** badge, routes **"→ (row,col)"** (receiver→donors) / **"← (row,col)"** (donor←receivers).
  - **Grid & Crop strings** (verbatim): header **"Grid & Crop"**, inputs **"Cols"** / **"Rows"** (1–128), **"auto"** reset button (only after a manual override), radios **"Scale to grid"** / **"Center crop"**. Auto-suggest scans up to 8×8 from the aspect ratio; non-square images default to **Center crop**. Quantize/dither run on the whole image *before* the split, the seamless claim is correct.
  - **Composite lossy is a size-gated fallback, not automatic-on.** It rides the ep03 **"⚡ Lossy animation"** toggle (or full-color sRGB), needs a multi-tile grid, and is kept only if the single stream beats the raw per-tile total. The stream splits across tiles *roughly* evenly (the first few tiles carry one extra byte), concatenated in tile-index order. Say "splits across the tiles," not "exactly evenly."
  - **The WAITING / "SCAN ALL TILES" / "TILES x/y" screen is the composite path** (a composite tile paints it until *all* siblings are seen). A **mux receiver** also waits on its donor tiles, but through a different buffer (no "SCAN ALL TILES" screen). This episode's payoff is the **mux blank-donors** case, so the beat shows the donor maps being scanned then the decode, it does NOT use the composite WAITING screen (that asset belongs to a composite-grid beat if one is added).
  - **Schematics: one `.litematic` per tile**, named **`<title>_r<row>_c<col>.litematic`** (title sanitized, fallback "loominary"), plus **`<title>_donor{n}.litematic`** per blank donor. Not one combined file, and not literally "loominary_carpet_...". Tiles self-identify by the grid position embedded in each payload; the item-frame grid must match cols×rows.
  - **Byte budgets** (carpet channel): carpet **8,176** · +shade **10,192** · +banners **13,466** · max **15,482** · banner-only **5,290**. Footer note reads **"Channels: carpet 8 176 B · shade 2 016 B · overflow 5 290 B (63 banners)"**. Mux descriptors: 10 bytes per guest (carpet) or one 84-byte "MG" banner per guest (banner mode). "Src size is incorrect" is the zstd failure symptom if the two allocators ever diverge (`MuxAllocationParityTest` locks them).
  - **Animated grid sync** key = `cols:rows:frameCount:author:title` (no nonce), sibling tiles advance as one group. Correct as stated.
- **DECtalk pronunciations** (synth text only; captions keep normal spelling): "AV1" → **"A V one"**; "GIF" → **"giff"**; "mux" reads fine; "LOOM" → **"loom"**; "litematic" → **"lite matic"**; "n-th" → **"enth"**; spell byte figures in words ("fifteen thousand four hundred and eighty-two") so Perfect Paul does not rush the digits. "Cols" is spoken as **"columns"** in the narration (never "cols").
- Copy/adapt `assemble-ep03.py` → `assemble-ep04.py`: keep the **one-row caption** logic (`wrap_chunks` + separate `burn.srt`) and the `anim`/`sync`/`grid`/`game` kinds; add a 3×2 variant to `grid`, and a "framed GIF beside blank donor maps" composite for the payoff beat.
