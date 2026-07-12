# Changelog

> **Process note:** The newest release always goes at the top. Write notes in the style of this document — a short overview paragraph explaining the purpose of each feature group, followed by specific bullet points. Update this file before tagging and pushing to GitHub.

---

## v1.25.1

### Feat: status screens on maps (progress, lock, error)

Tiles that can't display their art yet no longer sit transparent or noisy — they paint a status screen (procedural map-palette pixel art, `PlaceholderArt`):

- **Decoding** — AV1 tiles (per-tile and composite) show a live progress bar with percentage, repainted every ~5% straight from the decode thread. Composite tiles waiting for their sibling tiles show a "WAITING — TILES n/N — SCAN ALL TILES" screen with one bar segment per tile, updated on every tile scanned.
- **Password required** — encrypted tiles with no matching password (or an empty password list) show a lock icon; painted once per session, replaced by the art as soon as a working password is added.
- **Error** — failed decodes (AV1 stream errors, composite decode failures, mux reassembly failures) show a warning triangle with "CHECK LOGS" instead of failing invisibly.

## v1.25.0

### Feat: composition-wide lossy encoding (seamless multi-tile animations)

Per-tile lossy encoding produced visible lines along tile boundaries on multi-tile animated art — each 128×128 tile was a separate AV1 encode, so block artifacts disagreed where tiles met. Multi-tile lossy animations are now encoded as **one AV1 stream covering the whole composition** at `(cols·128)×(rows·128)`, then the stream bytes are divided evenly across the tiles' payloads — the boundary between two tiles is just another part of one video frame, so no seams. The tradeoff: the composition is all-or-nothing — **every tile must be within scan range (seen at least once) before any of them can display**; until then composite tiles render transparent.

- **Format** — new manifest flag `FLAG_AV1_COMPOSITE (0x80)` (implies `FLAG_AV1 | FLAG_AV1_LOSSY`); each tile's payload after the header is its byte-segment of the composition stream, and segments concatenate in tile-index order (`tileRow·cols + tileCol`). No new manifest fields — grid position and frame count already identify everything; compositions are keyed by grid + frame count + nonce + author + title.
- **Mod assembly** — `MapBannerDecoder` buffers segments per composition as tiles are scanned, then decodes the full stream **once, off the game thread**, crops each tile's window, and registers the usual synced animations. Works across all transports (banner, carpet, LOOM, mux) since only the payload inside the zstd wrapper changed.
- **Web parity** — the export preview muxes the composite stream itself into the MP4 (zero re-encode, byte-truthful), the fidelity metric compares the real decoded composite, and importing a composite state JSON reassembles it exactly like the mod. `Av1CompositeRoundtripTest` locks mod↔web decode parity at composition dimensions (fixtures via `node web/scripts/gen-av1-fixtures.mjs`); `web/test/composite-payload.test.mjs` locks the split/reassembly wire format.
- **Fallback** — raw frames+zstd per tile remains the floor: if the composite total isn't smaller (e.g. pure-noise content), the export ships raw tiles exactly as before. Single-tile lossy animations keep the existing per-tile path. Old mod builds cannot display composite tiles (decode fails cleanly; update the mod).

### Fix: AV1 decode ~50× faster and no longer dies mid-stream (build-time wasm compilation)

In-game AV1 decode ran on Chicory 1.4.0's **interpreter** — roughly 12 s per composite frame, so a 60-frame 1×2 composition needed ~12 *minutes*, and about 8 minutes in it died anyway with an uncaught `WasmException` from libaom's error path. Chicory is upgraded to **1.7.5** and `av1-decode.wasm` is now compiled **to JVM bytecode at build time** (Chicory's build-time compiler, `generateAv1Machine` in `build.gradle`): the same 60-frame composition decodes in ~14 s, with no first-decode warm-up and nothing new at runtime — still pure Java, still one jar.

- Build-time (not runtime) compilation matters on Fabric: Chicory's runtime compiler needs ASM 9.9+, and nesting our own ASM in the jar collides with the copy fabric-loader puts on the classpath — Sodium crashed on the duplicate at preLaunch. The pre-compiled machine classes depend only on `chicory-runtime`.
- The raw wasm no longer ships in the jar (the generated classes + a stripped `.meta` module replace it); net jar growth ~0.6 MB.
- The composite decode task now catches `Throwable` (an `Error` used to vanish silently into the executor's unread `Future`) and logs the decode wall time on completion.
- **Per-tile AV1 decode moved off the game thread** too: a 60-frame single-tile lossy map used to freeze the game for several seconds (minutes, pre-compiler) when first scanned. The tile paints transparent while decoding and shows frame 0 the moment it's ready, same as composite tiles.

### Hardening: web↔mod mux allocation locked by a parity test

The mux allocation algorithm exists twice — `poolMuxTiles` (Java, re-run by the mod at state import) and `computeMuxAllocation` (TS, baked into schematic LOOM headers at export) — and any divergence corrupts muxed receivers. The Java algorithm is now extracted into the Minecraft-free `MuxAllocator`, and `MuxAllocationParityTest` asserts it reproduces the TS implementation's exact output across 24 fixture scenarios (all five codecs, threshold edges, partial donor spare, rollback/unresolved cases). Regenerate fixtures with `node web/scripts/gen-mux-fixtures.mjs` when either side changes.

### Fix: mux corruption from stale export allocation ("Src size is incorrect")

The web export computed its mux allocation (which tiles donate, receiver totals, guest slice offsets) from the **stats-pass** tile sizes, but the exported bytes could differ — recompute at changed settings, resalt re-encode, or the fixed encryption-overhead estimate. The schematic's LOOM headers then promised receiver totals that didn't match what the donors actually carried (and the mod re-pools from the *actual* sizes at import, compounding the disagreement), so muxed receivers reassembled a truncated/garbled zstd frame: `Mux completion failed … Src size is incorrect`. Composite lossy made this near-certain to bite, since its payloads are incompressible AV1 slices that always need donors.

- `encodeAndMux` now derives the allocation **from the actual payload bytes it just produced** (post-encryption), escalates blank donors as needed, and the ZIP's schematic headers consume that same allocation — one allocation over one set of bytes, so web schematics and mod-generated banners always agree.
- `web/test/composite-payload.test.mjs` now also simulates the mod's receiver reassembly (own segment + donor cargo slices per the allocation) over real composite payloads and asserts byte-exact reconstruction.

### Fix: AV1 payload decode edge cases

- Compact AV1 payloads (stream + header smaller than one 16,384-byte frame) no longer throw `header_size past payload end` — the raw-frame size floor now only applies to raw payloads.
- The manifest CRC check is skipped for AV1 payloads (the bytes after the header are a compressed stream, not frame 0), silencing a spurious per-tile mismatch warning.
- A **held** AV1 animated map (not in an item frame) now decodes and shows frame 0 instead of painting stream bytes as noise.

### Feat: lossless AV1 codec for animated art

Animations exported from the web editor are now encoded as a **lossless AV1** stream instead of zstd-over-concatenated-frames, cutting the compressed payload — and therefore the number of banners/carpets you place in-game — substantially by exploiting inter-frame prediction. Old-format art (raw / delta / sparse frames) still decodes unchanged; only new animated tiles use AV1, and only when it comes out smaller than the old encoding (per-tile fallback).

- **Shared wasm codec** — one `av1-decode.wasm` (libaom, built via wasi-sdk) is run *inside the JVM* by the mod via [Chicory](https://github.com/dylibso/chicory) and by the web editor in the browser, so decode is byte-identical on both sides with **no native dependency** — the mod stays a single cross-platform jar (Chicory jars + the ~1.2 MB wasm are nested with `include`). Frames are coded as an 8-bit monochrome plane of palette indices after a fixed **OKLab-similarity permutation** (`PalettePermutation` / `palette-perm.ts`) so neighbouring indices are visually close, tightening lossless prediction.
- **Format** — new manifest flag `FLAG_AV1 (0x20)`; the AV1 bitstream is stored as length-prefixed temporal units after the (v4-inline-delay) manifest header, kept inside the existing zstd wrapper so nothing in the banner/carpet/mux transport changes. See `native/av1/README.md` to rebuild the wasm.
- **Preview** — the exported ZIP's animated preview is now an **AV1 `.mp4`** (via WebCodecs + `mp4-muxer`) instead of `preview.gif`, with a GIF fallback where AV1 encode is unavailable; the on-page export preview plays it as a looping `<video>`. Static art still exports `preview.png`.
- **Compatibility** — a mod build predating this release cannot render AV1 animated tiles (it reads the bitstream as raw frames → noise); update the mod. Static art is unaffected.

### Feat: optional lossy animation (big savings for dithered art)

Lossless AV1 can't beat zstd on dithered map art (dithering is high-entropy noise). For a *much* smaller payload, the export page now has an optional **Lossy animation** toggle + quality slider: the animation is encoded as lossy AV1 colour video and re-quantised to the palette on decode, so the in-game art is a close approximation rather than byte-identical — often ~50% smaller on detailed/dithered art.

- **Truthful preview** — the export preview runs the exact encode→decode→re-quantise pipeline the payload uses, so what you see is what ships. `Av1LossyRoundtripTest` proves the mod's decode matches the web decode byte-for-byte (shared wasm + a committed `MapPalette` RGB table generated from the same source as the web palette), so the preview equals the in-game result.
- **Per-tile, opt-in** — new `FLAG_AV1_LOSSY`; the wasm decoder (both web and mod, via Chicory) reconstructs YUV→RGB and picks the nearest palette entry. Off by default; lossless remains the default path.

### Feat: stronger zstd compression (web editor)

The web editor now compresses payloads with **zstd level 19** (via `@bokuweb/zstd-wasm`) instead of level 9. The previous zstd build had a fixed 16 MB heap that OOM'd above ~level 9 on tile-sized (~1 MB) payloads, so animated/large tiles were under-compressed; the new build grows its memory, cutting ~20% off the highly-dithered map art that AV1 can't help. Output is still a standard zstd frame, so the mod decodes it unchanged (no mod changes). Each animated tile now picks the smaller of {raw + zstd-19, AV1}, so dithered art wins via better zstd while flat/coherent art can still win via AV1.

### Fix: printing survives alt-tab

Vanilla opens the pause menu ~500 ms after the window loses focus, halting input and therefore the printer. `/loominary walk print` now disables `pauseOnLostFocus` for the session (the original setting is restored when printing stops) and releases/recaptures the cursor grab on focus loss/regain, so a print keeps running while you work in another window.

## v1.24.0

### Feat: `/loominary walk print` — fully autonomous carpet printing

Prints an entire Litematica carpet placement hands-free. The bot walks the unbuilt region in serpentine order with the Litematica printer on, notices when it's running low, walks itself to the chests and restocks, and resumes — looping until the floor is done, with no intervention. Placement is done by the **Icetank/aleksilassila `litematica-printer` fork** (the continuous printer); loominary only toggles it and drives the movement. Run `/loominary walk print [width]` (default band width 5; 8 works well with a printer range ≥ 4). `/loominary walk print stop` stops it.

- **Serpentine print walk** — reuses the side-aware banding (`computePrintPath`): from the east it sweeps NE→SW, from the west NW→SE, one inventory-load at a time, walking each band's centre line while the printer fills its width.
- **Autonomous restock** — when a load needs more carpet than you're carrying, it balances, walks to the right chests itself, grabs exactly the planned load, and resumes. It gathers the *exact* planned load regardless of where it ends up, so it never chases a moving target.
- **Printer toggle** — `LitematicaBridge` flips the fork's `PRINT_MODE` config by reflection; the printer is held on **only** while laying carpet and forced off while navigating, restocking, or stopped. Verify the binding with `/loominary walk printer on|off`.

### Feat: adaptive print pacing

The print walk paces itself on the same duty cycle as `/loominary walk` (`<on> <off>` ticks; `off 0` = continuous), but scales the pause to the real cost of the floor just ahead — so it crawls through dense, many-colour work and sprints across sparse or finished stretches.

- **Placement cost** — only counts cells whose colour you actually hold (cells you can't print are skipped, not slowed for).
- **Colour-swap cost** — distinct colours ahead beyond the printer's hotbar capacity (`PRINTER_HOTBAR_SLOTS`, ≤ 9) force item swaps that steal placement ticks, so they slow the walk further; a dense single-colour band stays faster than a dense many-colour one.

### Feat: missed-cell recovery

A few edge cells the printer skips (band wider than its reach, or timing) used to compound into orphan strips that never got revisited. The bot now darts back at full speed to lay any unbuilt cell sitting in a band the sweep has already passed, then resumes — direction-agnostic, driven by the plan's actual band order, so it never wanders into not-yet-printed bands. Keep band width ≤ 2× the printer's range for efficiency.

### Feat: chest cataloguing, navigation, and a stop button

- **`/loominary carpets catalogue`** — walks and opens every chest within range once (a greedy nearest-neighbour tour, so it follows the walls instead of ping-ponging) to build authoritative chest memory. Auto-print runs it only when the persisted memory doesn't already know a chest for every needed colour, so a catalogued storage isn't re-walked every session.
- **Pathfinding** — restock/catalogue navigation now routes **around walls** with a bounded any-angle A\* (8-connected + line-of-sight smoothing), falling back to straight-line steering when no route is found.
- **`/loominary stop`** and a bindable **"Stop all"** key — halt every loominary automation (print, fill, catalogue, walk) at once.

### Fix: chest memory corruption and loose carpet

- **Double-chest partner** — recording a double chest no longer scans all neighbours (which, in a wall of chests, stamped a neighbour's contents onto a *different* chest and marked it visited). It resolves the one true partner from chest geometry, so memory stays clean and restock stops trekking across stations.
- **Sync stability** — chest contents are recorded only once the carpet total holds steady for a few ticks, so a half-synced read on a laggy server no longer persists an undercount.
- **No loose carpet** — during autonomous print the balance keeps surplus in inventory instead of dropping it on the floor where the bot would walk over and re-collect it.

> Note: delete `config/loominary_chest_memory.json` and re-run `/loominary carpets catalogue` once after updating, to purge any smeared entries written by earlier versions.

---

## v1.23.0

### Fix: carpet fill goes to the right chest

The fill tool was filing each opened chest's contents under the wrong position — it attributed them to the chest block nearest your feet, which in a wall of chests is rarely the one you opened, and the double-chest pass then stamped neighbours too. Over a session one chest's contents smeared across many positions, so fill sent you to a "magenta" chest for black, ignored the chest you were standing in front of, and reopened the same chest repeatedly. Attribution is now exact.

- **Exact attribution** — a chest's contents are recorded against the block the tool actually interacted with (or, for chests you open yourself, the block under your crosshair), falling back to nearest-in-reach only when neither is available. No more smearing one chest across the wall.
- **Proximity-first selection** — fill now heads to whichever needed chest is actually nearest, instead of always preferring an uncatalogued chest in reach. Standing in front of the black chest opens *it*.
- **No more reopen loop** — once a chest opens, the state machine leaves `WAIT_OPEN`, so closing a chest (yours or ours) re-scans instead of re-firing the open and reopening it.
- **Memory stays accurate** — a chest's remembered contents are refreshed when we close it, so a drained chest isn't remembered as still full.
- **Auto-guide range** raised from 5 to 16 blocks; fill cadence roughly halved (snappier grabbing) while the anti-false-empty and give-up safety caps are unchanged.

> Note: existing `config/loominary_chest_memory.json` files written before this release may contain smeared positions. Delete the file to let it rebuild cleanly; it repopulates as you open chests.

### Fix: side-aware banding actually works

The "sweep from your side of the build" logic silently defaulted to west→east: it detected your side via Litematica's selected-placement reflection wrapped in a catch-all that returned `false` on any hiccup (e.g. no placement *selected*). The NE→SW band never ran. Side detection now keys off the live extent of the *unbuilt* carpets the scan already finds — self-contained, no placement selection required, and tracking the frontier as the build fills in.

### Feat: `/loominary walk` — duty-cycle auto-walk

A bindable hands-free forward walk on a duty cycle, for tracing a build edge while you balance/fill or print.

- **Hotkey toggles** auto-walk on/off (Controls → Loominary, unbound by default); **`/loominary walk <on> <off>`** sets the on/off durations in ticks that the hotkey follows (default 10t forward / 20t pause = 0.5s / 1.0s). `/loominary walk` toggles; `/loominary walk stop` stops.
- **Coexists with the Litematica printer** — it forces forward into the computed movement input (via a `KeyboardInput` mixin) rather than pressing the forward key, so the printer's per-tick key resets no longer cancel it. Pauses while a GUI/container is open so the printer can restock without you walking off.

---

## v1.22.0

### Feat: `/loominary carpets fill` and smarter proportional balancing

Adds a carpet-gathering companion to `/loominary carpets balance` and makes the balance layout track what the build actually needs. Both are bindable under Controls → Loominary.

- **`/loominary carpets fill`** — tops up your carpet stacks from nearby chests. It tells you what it wants (e.g. "Need 192 brown — open a chest with it"), takes exactly the carpet needed from whatever chest you open (returning any excess), marks needed chests in-world, and walks you only short hops (≤5 blocks) — never across the room. Run it again to stop.
- **Chest memory** — remembers each chest's carpet contents (carpet counts only) per server + dimension, persisted to `config/loominary_chest_memory.json`, so later runs go straight to the chests that have what's needed. Empty/ambiguous reads are never recorded, and contents are only trusted once the server has synced them (fixes stocked chests being seen as empty on laggy servers).
- **Proportional allocation** — balance now sizes each color's stacks proportionally to its Litematica material count (capped at what's actually needed: a color needing one stack gets one slot, not several), instead of a fixed 3/3/2 split.
- **Closest-incomplete targeting** — balance/fill gather the nearest *unplaced* part of the build (read from Litematica's schematic world), capped at one inventory-load, swept in a predictable order from your side of the build, so each trip maps to a contiguous build frontier. Falls back to whole-build totals when the placement isn't loaded nearby.
- **Fresh material counts** — read directly from the selected schematic placement each run, so stale or unopened material lists no longer skew the layout.

### Web editor

The web editor is now published at **https://zerohpminecraft.github.io/loominary/** (GitHub Pages), with muxed-schematic export, a worker-loading fix, and privacy-friendly usage analytics. (Web only; not part of the mod jar.)

---

## v1.21.1

### Fix: muxed tiles decode on sight; blank donors render transparent

Two decoder bugs around carpet mux tiles.

- **Muxed tiles failed to decode on initial view** — `processFrame` bailed out whenever a map had no banner decorations, but a LOOM carpet tile carries its data in the carpet channel and can legitimately have none (a pure-CARPET tile, or a blank mux donor that only carries guest payload). Those tiles were skipped, so a donor's guest segments never reached the receiver and the receiver waited forever; removing and replacing a donor only "fixed" it by coincidentally re-triggering a scan after its colors had synced. The empty-decorations guard now also checks `CarpetChannel.peekLoomMagic`, so LOOM tiles are always processed and muxed walls decode on sight.
- **Blank donor tiles showed encoding noise** — a donor with no image of its own (`ownBytes == 0`) was left displaying its raw carpet bytes. It's now painted fully transparent (map color id 0) so the wall shows a clean hole; guest routing is unaffected.

### Fix: carpet balance never discards needed carpet

The balancer could still drop carpet of a needed color when its goal slots were temporarily blocked by another color and the inventory was too full to park the held stack. Dropping is now count-based and provably safe — a stack is dropped only when at least the keep amount (allotted slots × 64) of that color remains — and blocked goal slots are resolved with a swap that needs no free slot, so needed carpet is always placed via seed/fill/swap and never discarded.

---

## v1.21.0

### Feat: `/loominary carpets balance` — lay out carpets to match a Litematica material list

A new command (and bindable hotkey) that reads the open Litematica schematic's material list and rearranges your inventory carpets into a canonical placing layout, so you can run through an assorted pile and end up with the right amount of every color.

- **Layout** — the two highest-count colors (by material count, usually white + one other) each get **3 stacks (192)**; every other carpet color in the list gets **2 stacks (128)**. Anything over those targets, and any carpet color not in the list, is dropped on the ground. Stacks need not be full.
- **Slot reservation** — every allotted slot is seeded with at least one carpet (e.g. 7 brown across 2 slots becomes `[6, 1]`, a full stack across 2 slots becomes `[63, 1]`), so each slot is claimed by its color and a later pickup run fills them without other colors intruding.
- **Scan-then-plan** — the inventory is scanned once and goal slots are assigned around what's already there: slots holding non-carpet items (firework rockets, food) are never used, so those items are never moved or dropped, and each color prefers the slots it already occupies to minimize shuffling.
- **Soft dependency** — the Litematica material list is read by reflection (`DataManager.getMaterialList()`), so the mod still loads cleanly when Litematica isn't installed; the command then reports a friendly error.
- **Hotkey** — bind *Balance carpets to Litematica materials* under Controls → Loominary (unbound by default). Runs against the open survival inventory; the command/hotkey opens it for you.

---

## v1.20.0

### Fix: mux now works for BANNER codec; donor count correctly estimates overhead

Two bugs in `/loominary mux` caused mux to silently fail for certain codec configurations.

- **BANNER codec mux broken entirely** — `applyMux()` extracted payloads via `carpetCompressedB64`, which is only populated for carpet tiles. BANNER tiles have `carpetEncoded=false`, so all payloads were treated as empty and the algorithm exited early with "no tiles are over budget". Fixed by reassembling the payload from the raw CJK chunks when `carpetEncoded` is false.
- **Last receiver unallocated with many receivers** — `mgOverhead` was hardcoded to `84 * 4` (enough for four guests), but the correct value scales with the number of receivers. With ≥5 receivers the donor capacity was overestimated, leaving the last receiver(s) unresolved. Fixed by computing `mgOverhead = numReceivers * overheadPerGuest` (84 bytes per guest for CARPET_BANNERS modes, `LOOM_GUEST_DESC` bytes for CARPET/CARPET_SHADE modes), with a retry loop that appends blank donors when the initial donor count proves insufficient.
- **`/loominary mux undo` for BANNER** — receiver tiles were left with their mux-encoded chunks rather than restored to their original CJK encoding. Fixed by restoring from `muxCargoB64` (the receiver's own segment) via `CjkCodec.buildChunks()` when `carpetEncoded=false`.

### Feat: codec renamed to channel-list style; new pure CARPET mode

Codec names now reflect exactly which output channels are enabled. Channel priority is fixed: carpet > banners > shade.

| Old name | New name | Channels |
|---|---|---|
| — | `CARPET` | carpet only |
| `CARPET_ONLY` | `CARPET_SHADE` | carpet + shade |
| `CARPET` | `CARPET_BANNERS` | carpet + banners |
| `CARPET_SHADE` | `CARPET_BANNERS_SHADE` | carpet + banners + shade (default) |
| `BANNER` | `BANNER` | banners only (unchanged) |

- **New `CARPET` mode** — encodes the payload entirely in the carpet channel (up to 8176 bytes). No overflow banners, no shade channel. Mux pooling supported.
- **Existing save files migrate automatically** — old enum names in `loominary_state.json` (`CARPET`, `CARPET_SHADE`, `CARPET_ONLY`) are mapped to their new names on first load; the file is rewritten in the new format on the next save.
- **`/loominary codec`** — help text updated to list all five modes with their channel descriptions.

---

## v1.19.2

### Fix: `/loominary palette` no longer freezes the game on large animations

For a 5000-frame tile the palette command was allocating ~600 MB (decompressed payload, union frame, `colorInRow` matrix, simulation clones) and running three `Zstd.compress` calls — all synchronously on the main render thread. The OS killed the LWJGL window when the render loop went unresponsive.

The entire computation now runs on a `loominary-palette` daemon thread. State is snapshotted before dispatch, results are posted back via `client.execute()`, and `importInProgress` gates concurrent operations. The command immediately prints `Computing palette…` and completes asynchronously.

---

## v1.19.1

### Author override and in-game title/author display

Adds `/loominary author` to set a persistent author name embedded in every tile's manifest, and shows that name (plus the title) in the action bar when the player looks at or holds a decoded map.

**`/loominary author [name|clear]`**
- `/loominary author` — shows the current override or confirms the player's IGN is being used
- `/loominary author <name>` — sets an override applied to all subsequent imports and re-encodes
- `/loominary author clear` — removes the override and reverts to the player's IGN
- Persisted in `loominary_state.json` alongside `title` and `codecMode`

**In-game display**
- When looking at (crosshair → item frame) or holding a Loominary-decoded map, its title and author appear in the action bar: `"Bad Apple" by ZeroHPMinecraft (1,1 of 2×3)`
- Tile position is only shown for multi-tile murals
- Disappears ~2 seconds after looking away (standard action bar timeout)

---

## v1.19.0

### Sub-tick animation

Animation frames now advance on the render thread (`WorldRenderEvents.END`) instead of the game tick event, allowing frame delays shorter than 50 ms to be honoured. The sync-group map used to group sibling tiles is now cached and rebuilt only when `animatedMaps` changes, eliminating per-frame allocations at high refresh rates.

### v5 manifest — large animated GIF support (Bad Apple)

The v4 manifest stored per-frame delay tables inline in the header, capping the total header at 255 bytes. For animations with many frames and variable delays (≥ ~116 frames), the table overflowed and import failed. A new v5 manifest format moves the delay table to after the last frame's data; `header_size` stays within the u8 field and old decoders gracefully render frame 0. Uniform delay arrays are normalized to a single global delay before deciding whether v5 is needed, so most animations remain v4.

- **v5 manifest** — trailing delay table at `data[headerSize + frameCount × 16384]`; `fromBytes` detects version and reads accordingly
- **Normalization** — uniform per-frame arrays collapsed to global delay at encode time
- **`withTrailing` / `trailingDelayBytes`** — helpers for callers that build the payload before compressing

### Mux redesign — strand donors replace ring

`/loominary mux` no longer redistributes overflow across existing art tiles. Instead it calculates the minimum number of blank donor tiles needed to absorb all overflow and appends them as a numbered strand after the last art tile. Donors can be placed anywhere within 32 blocks of the art — they don't need to surround it.

- **Remux** — calling `/loominary mux` again removes old donor tiles and recomputes the minimum, which may be fewer after reduction
- **`/loominary mux undo`** — removes all donor tiles and resets art tiles to their original (possibly over-budget) state
- **`/loominary ring` removed** — superseded by the donor-strand approach
- **`isDonorOnly` flag** on `TileData` — identifies appended donor tiles for mux undo and status display

### Import never fails on size

Carpet imports that produce payloads too large for the current codec now fall back to a force-encode rather than aborting the entire import. The tile is imported as over-budget with a note; the user can then reduce or mux.

### Adaptive compression level — native OOM fix

`Zstd.compress` at level 22 allocates native working memory proportional to the window size. For Bad Apple's ~86 MB frame payload this reaches 500 MB–2 GB of native heap, crashing the LWJGL window without a Java exception. All compression calls now use an adaptive level: max (22) for payloads under 256 KB, level 9 up to 4 MB, level 3 above that.

### Status and export improvements for mux batches

- **Status receiver line** — shows own-segment bytes and full logical payload size so the user can see how much the donor strand is absorbing
- **Donor summary** — shows total overflow banner count and links to the new paged donor view
- **`/loominary status donors [page]`** — 10 donors per page with per-tile banner count and placement progress; navigation links at the bottom
- **Export naming** — donor tiles export as `name_donor_001.litematic`, `_002`, etc. rather than all overwriting the same file

---

## v1.18.1

Five bug fixes for the 1.18.0 codec/LOOM changes:

- **Preview broken on LOOM tiles** — `tile.chunks.isEmpty()` was skipping all carpet tiles with no overflow banners; replaced with a content check that reads `carpetCompressedB64`
- **Codec change now re-encodes immediately** — `/loominary codec <mode>` triggers a background re-encode of all tiles in the batch; over-budget tiles are reported but not blocked
- **Editor save now prints a confirmation** — `saveEditorChangesAsync` now sends a `§aSaved tile … — N bytes/banners` message on completion
- **Status facelift** — carpet tiles no longer show a meaningless `0/0` progress ratio; they show `§a✓ [loom] N bytes` (or `§c✗ over budget`); banner tiles keep the `done/total` progress; the summary line is codec-aware
- **Banner codec import no longer fails on large images** — default import (`/loominary import <file>`) with codec set to `banner` now routes through the banner-only path rather than the carpet path, which was throwing when the payload exceeded the 63-banner capacity

---

## v1.18.0

### Codec selection and LOOM carpet format (1.17.3 + fix)

Adds `/loominary codec` to choose the encoding strategy for the session, and replaces the LC/LS banner header with a compact in-carpet LOOM header for all new carpet-mode tiles, freeing one banner slot per tile.

**Codec modes** (`/loominary codec <mode>`):
- `banner` — 63 hex-indexed CJK banner chunks; no carpet platform required
- `carpet` — LOOM carpet + overflow banners; shade disabled
- `carpet+shade` — LOOM carpet + shade + overflow banners **(default)**
- `carpet-only` — LOOM carpet + shade; no overflow banners

**LOOM header**: a 16-byte uncompressed prefix replaces the LC/LS banner, detected by `"LOOM"` magic in the first 4 carpet bytes. Frees one overflow banner slot per tile (63 vs 62 previously). Backward compatible — old LC/LS tiles still decode correctly.

**Mux in all modes**: `/loominary mux` now works across all four codec modes. For `carpet-only`, routing descriptors are embedded in the LOOM header itself (10 bytes per guest), requiring no MG banners.

**Bug fix**: `/loominary edit`, `palette`, `preview`, `reduce`, and `resalt` were incorrectly blocked on LOOM tiles that have no overflow banners, because those tiles have an empty chunk list (overflow banners are only written when the compressed payload exceeds the carpet+shade capacity). The guard now checks `carpetCompressedB64` for carpet tiles rather than the chunk list.

- **`CodecMode` enum** — `BANNER`, `CARPET`, `CARPET_SHADE`, `CARPET_ONLY`; persisted in `loominary_state.json`
- **`TileData.loomEncoded`** — tracks LOOM vs legacy LC/LS format for correct budget display and re-encoding
- **`CarpetChannel` helpers** — `peekLoomMagic`, `decodeLoomHeader`, `buildLoomHeader`, updated capacity constants
- **Decoder** — `processLoomCarpetFrame`, `processBannerMuxReceiver` (LB banner), `processBannerMuxDonor` (MG without carpet) added

---

## v1.17.3

### Codec selection and LOOM carpet format

Adds `/loominary codec` to select the encoding strategy per session, and replaces the LC/LS banner header with a compact in-carpet LOOM header for all new carpet-mode tiles. The freed banner slot is added to the overflow pool.

**Codec modes** (select with `/loominary codec <mode>`):
- `banner` — 63 hex-indexed CJK banner chunks; no carpet platform required
- `carpet` — LOOM carpet + overflow banners; shade disabled
- `carpet+shade` — LOOM carpet + shade + overflow banners **(default)**
- `carpet-only` — LOOM carpet + shade; no overflow banners

**LOOM header format**: a 16-byte uncompressed prefix at the start of the carpet channel replaces the LC/LS banner. Detected by the magic `"LOOM"` (0x4C4F4F4D) in the first 4 carpet bytes. Frees one banner slot per tile (63 overflow banners vs 62 previously).

**Mux in all modes**: `/loominary mux` now works across all four codec modes.
- `banner` and `carpet`/`carpet+shade`: receiver identified by `LB`/LOOM-MUX_RX banner; donors use MG routing banners (same wire format, updated capacity formula)
- `carpet-only`: routing info embedded entirely in the LOOM header guest descriptors (10 bytes per guest vs 84 bytes for a banner slot); no MG banners needed

**Backward compatibility**: existing LC/LS tiles decode identically via the unchanged `processCarpetFrame` path. LOOM tiles are detected by carpet magic; old tiles are detected by LC/LS banner presence.

- **`CodecMode` enum** — `BANNER`, `CARPET`, `CARPET_SHADE`, `CARPET_ONLY`; persisted in `loominary_state.json`
- **`TileData.loomEncoded`** — distinguishes new LOOM tiles from legacy LC/LS tiles for budget display and re-encoding
- **`CarpetChannel` LOOM helpers** — `peekLoomMagic`, `decodeLoomHeader`, `buildLoomHeader`, updated capacity constants
- **Decoder** — `processLoomCarpetFrame`, `processBannerMuxReceiver` (LB), `processBannerMuxDonor` (MG without carpet) added to `MapBannerDecoder`

---

## v1.17.2

### Decode and render maps held in hand

Loominary maps now decode and display their image when held in the main hand or off-hand, not only when placed in an item frame. Banner markers are hidden in both cases.

- **Held-map decode** — carpet-hybrid (LC/LS) and legacy banner-encoded maps are detected and decoded the first time they appear in the player's hand
- **Marker suppression** — banner pin decorations are cleared every tick while the map is held, matching item-frame behaviour
- **Claimed-map maintenance** — already-decoded maps repaint from cache if the server overwrites their colors while held
- **LR mux-receiver tiles skipped** — a single held tile cannot reconstruct its payload without the sibling donor tiles; these are left for the item-frame scanner to handle once placed

---

## v1.17.1

### Animated frame count raised to 65535

The manifest `frame_count` field was a 1-byte unsigned integer, capping animated art at 255 frames regardless of how many banners were available. The manifest format is bumped to v4, expanding `frame_count` to a 2-byte unsigned integer. Old decoders (v3 and earlier) continue to read their own format correctly; new decoders handle both v3 (u8 frame_count) and v4 (u16 frame_count) transparently.

- **v4 manifest** — `frame_count` is now u16 (big-endian), supporting 1–65535 frames
- **Wire-format backward compat** — v3 banners already placed in item frames decode identically; the encoder now produces v4 for all animated tiles
- **65536-frame validation** — `toBytes` throws `IllegalArgumentException` for out-of-range frame counts

### Whitelist-banner reuse and bundle extraction

The anvil renamer can now recycle existing named banners as raw material, eliminating the need to always supply a stock of fresh unnamed banners.

- **`/loominary whitelist add`** — scans inventory (loose slots and bundle contents) and marks every named banner as reusable raw material
- **`/loominary whitelist clear`** — empties the whitelist
- **Three-pass extraction** — the renamer first tries unnamed banners (existing behavior), then loose whitelisted banners, then banners inside bundles; the bundle selection protocol (`BundleItemSelectedC2SPacket` + right-click) pops the target banner to the cursor
- **Output-bundle protection** — bundles holding whitelisted source banners are never used as rename output targets to avoid index desync; a HUD warning fires when all bundles are either full or excluded
- **Whitelist persistence** — the whitelist survives save/load cycles via `config/loominary_state.json`

### Carpet import from map screenshot

- **`/loominary import header <lc-ls-banner> <mapshot.png>`** — reconstructs a carpet-encoded `PayloadState` from a 128×128 map screenshot and an LC/LS manifest banner string, enabling schematic export without re-encoding

---

## v1.16.1

### Multi-tile rendering reliability

Two distinct bugs could leave one or more tiles of a mural showing the raw carpet pattern instead of the decoded image. Both are fixed.

- **MapMipMapMod interaction** — placing or approaching a freshly-decoded mural could leave tiles unrendered because the texture commit was suppressed for maps that were locked when `setNeedsUpdate` ran. `paintMap` now unlocks the `MapState` before marking the texture dirty, so the dirty-mark and atlas commit both see an unlocked map. `MapRendererMixin` continues to handle re-locks from later server packets.
- **Observer overwrite** — when another player placed or replaced a tile, the server re-sent the raw map snapshot to other observers, overwriting Loominary's decoded bytes. The map was already claimed, so the scanner's early-return path skipped re-decoding and the tile stayed raw. The scanner now compares `mapState.colors` against the cached decoded bytes on each scan tick and re-paints when they diverge.

### Diagnostics

Rate-limited per-mapId logging now fires on first scan, first paint (up to 3 paints), first `MapRenderer.update` call, and first unlock — useful for diagnosing any future multi-tile rendering anomalies without flooding the log.

---

## v1.16.0

### Editor threading

CPU-intensive editor operations (requantize preview, chroma boost, dithering, color interpolation, spatial filter) now run on a dedicated background thread, preventing watchdog crashes on large multi-tile maps. The approach mirrors the segmentation solver: stale-task elimination via a version counter and `DiscardOldestPolicy`, results dispatched back to the render thread via `mc.execute()`.

- **Background compute** — requantize preview, all-tile requantize, and the spatial filter are no longer blocking main-thread operations
- **Computing indicator** — status bar shows `⟳ Computing...` while a task is in flight
- **Esc to cancel** — pressing Esc while computing discards the in-flight task immediately
- **Canvas blocked during compute** — mouse events are ignored while computing to prevent state races
- **Grid sentinel fix** — changing dither/metric parameters (D/N/Q) while a grid requantize is computing no longer silently cancels it

### Merge fix

When scope is "all tiles" with an active selection spanning multiple tiles, the color merge now correctly restricts to the selected area on every tile. Previously, non-current tiles had the merge applied to all their pixels regardless of the selection mask.

---

## v1.15.0

### Segmentation

The segmentation tool helps you draw region boundaries on a map so the mod knows which areas to encode with which colors. This release makes it significantly faster and less tedious to use across multi-tile grids.

- **Adjustable seed brush** — `[` / `]` or Shift+scroll to resize (default r=5, max r=32)
- **Auto-solve** — solver reruns automatically after every stroke and every β adjustment; no manual trigger needed
- **Seed template** — seeds are saved when the solver runs and restored when you switch frames, so you don't have to redraw them per-frame. Shift+Z clears the template; the guide panel shows ✓ when one is active
- **Default β raised from 10 → 30** — better segmentation quality out of the box
- **Multi-tile mode** — segmentation now works in global coordinates across the full grid, not just the active tile
- **Async saves** — the editor no longer freezes on close while compressing; compression runs in the background

### Mux

Mux redistributes overflow data from tiles that are too large to encode on their own into spare capacity on neighboring tiles. Previously it would dump everything into whichever single tile had the most room, which could overload that tile and leave other donors underused. It now spreads the load evenly.

- **Even distribution** — overflow is divided equally across all viable donor tiles each pass; donors that fill up drop out and the remainder is redistributed among the rest
- **`/loominary ring`** — adds a border of blank transparent tiles around your grid (+2 cols, +2 rows). The ring tiles are empty so they contribute nothing visually, but they give mux more donor slots to work with. Run `/loominary mux` after. Requires carpet encoding; must be run before placing any banners
- **`/loominary reduce revert all`** — reverts every previewed map at once instead of one at a time
- **Mux state cleared on editor save** — if you open the editor and make changes after running mux, the mux state is cleared on save and you are prompted to re-run `/loominary mux`

### MapMipMapMod compatibility

If you use MapMipMapMod alongside Loominary, decoded map images were previously invisible due to a conflict in how both mods handle map texture rendering. This is now fixed — both mods work correctly together.

---

## v1.14.0

### Editor: chroma boost and per-algorithm dither tuning

The editor gains two new tools for fine-tuning how a source image re-quantizes to the map palette. Chroma boost amplifies or dampens color saturation before re-quantization, which is useful when the map palette desaturates an image more than you'd like. Dither strength is now adjustable per algorithm rather than being a fixed value.

- **Chroma boost** — N key adjusts OKLab chroma boost (0.25×–4.0×) before requantize; boosts faded-looking results or tones down garish ones
- **Per-algo dither tuning** — Shift+scroll adjusts the strength parameter of the active dither algorithm: error diffusion amount for Floyd-Steinberg and Atkinson, Bayer scale for ordered dithering
- **Ctrl+click canvas** — adds the clicked pixel's color directly to the merge-source queue without needing to find it in the palette panel
- **Shift+click transparency swatch** — deselects all transparent pixels from the current selection
- **Close-on-damage** — editor closes automatically if the player takes damage (prevents accidentally editing while fighting)
- **Cross-tile requantize** — uses precomputed preview results across all tiles simultaneously

### Decoder: raw/decoded toggle

A new keybinding lets you instantly flip between Loominary's decoded image and the map's raw underlying data (banner pins, chunk markers, etc.) for any framed map. Both states are cached, so the toggle is instantaneous.

- **Decode toggle** — bound in Controls → Loominary; flips between raw and decoded display on the targeted map; both color states are cached so switching is instant

### Palette panel

- **Color family grouping** — palette swatches now group related color families together, making it easier to find nearby colors when merging

---

## v1.13.0

### Editor: multi-tile canvas and cross-tile operations

Previously the editor worked on one tile at a time. This release adds a full-grid canvas view where you can see and edit all tiles simultaneously, with selection and undo that span tile boundaries.

- **Multi-tile canvas** — Shift+G toggles the full-grid view; all tiles are shown at their correct grid positions as a seamless canvas
- **Cross-tile lasso and wand** — selection tools work across tile boundaries in multi-tile mode
- **Cross-tile undo** — Ctrl+Z snapshots all tiles touched by an operation and reverts all of them together

### Copy, paste, and selection refinement

- **Copy/paste** — Ctrl+C/X/V; cut/copy captures the selected region, paste shows a floating preview that follows the cursor before committing with click or Enter
- **Selection grow/shrink** — `=` / `-` to dilate or erode the selection by one pixel; Shift multiplies by 5
- **Shift+click Sel swatch** — deselects all pixels of that color from the current selection
- **Shift+Esc** — discard all unsaved changes and close the editor immediately

### UI polish

- Palette swatch hover now pulses with a sine-wave animation instead of a constant highlight
- Selection fill tint reduced to barely visible; marching ants remain prominent
- Minecraft's default dark gradient overlay removed from the editor background
- Cross-tile requantize previews shown on all tiles at the same time

---

## v1.12.0

### Palette panel overhaul

The palette panel has been redesigned to give more space and more information. It now shows frequency bars so you can see at a glance which colors dominate the image, and it has separate tabs for tile colors, all available colors, and the current selection.

- **2× wider** (160px), 9 swatches per row
- **Three-tab layout** — Tile (with frequency bars), All, and Sel (auto-activates when you have a selection)
- **Transparency row** — dedicated checkerboard row with its own frequency bar
- **Scrollable swatch grid** with a scrollbar; scroll wheel works in the palette area
- **Hover highlight** — hovering a swatch highlights all matching pixels on the canvas

### Magic wand: hover preview and drag-extend

- **Hover preview** — a translucent blue overlay shows the region a click would select, updating live as you move the cursor or change tolerance with Shift+scroll
- **Drag to extend** — dragging ORs each hovered pixel's flood-fill region into a growing selection; already-covered pixels are skipped

### Mux correctness

These fixes correct cases where re-muxing after editing could produce an invalid or corrupt tile layout.

- `muxCargoB64` (physical cargo) is now tracked separately from `carpetCompressedB64` (logical payload) so preview, edit, and requantize always see a valid zstd frame
- All tile chunks are re-encoded from the logical payload at the start of `poolMuxTiles`, clearing stale MG/LR banners on re-mux
- MG banner overhead now counted correctly in spare capacity (63-banner limit)

### Export all tiles

- **`/loominary export`** — now writes all tiles at once, with a `_tileN` suffix per tile; single-tile batches keep the plain name

---

## v1.11.0

### GIF requantize: frame provenance tracking

When you import a GIF and use stride or skip to prune frames, the editor previously lost track of which original source frame corresponded to each remaining editor frame. The requantize tool (R) would always pull from frame 0 regardless of which frame you were editing. This release tracks provenance so requantize always uses the correct source.

- **`frameSourceIndices`** — each editor frame now records which original GIF frame it came from; updated correctly when stride/skip prune frames or when you delete a frame in the editor
- **Manual source override** — Shift+`[` / Shift+`]` step the requantize source frame independently of the playback frame; the guide panel shows `R src:N/M` in yellow when source and playback are out of sync

---

## v1.10.0

### Save and load state

You can now save the entire batch state to a named file and restore it later. Imports also auto-save on every run, so you can always recover the pre-edit state.

- **`/loominary save [name]`** — saves to `loominary_saves/<name>.json`, or auto-names as `<stem>_NNN.json`; tab-completes existing saves
- **`/loominary load <name>`** — restores a named save, replacing current state
- **Auto-save on every import** — all carpet, banner, and GIF imports save automatically on completion
- Schematic export is no longer triggered automatically on carpet import — use `/loominary export` explicitly

### Image export

- **`/loominary export image`** — exports the active tile as a lossless PNG (static) or looping GIF (animated) to `loominary_exports/`

### Editor improvements (v1.9.0 + v1.10.0)

A batch of editor improvements shipped across two internal milestones that were tagged together.

- **Dither algorithms** — D key cycles between None, Floyd-Steinberg, Atkinson, and Bayer 4×4 dithering; requantize preview refreshes live
- **Per-algorithm tuning** — Shift+scroll adjusts the active algorithm's strength parameter during preview; Shift+R toggles tile-palette vs full-palette during requantize
- **K key** — reduce the palette by one color step using the current reduction strategy; Shift+K cycles the strategy (Rarest / Closest / Weighted)
- **Budget badge** — palette panel header shows current byte or banner count, highlighted red when over budget
- **Selection-aware filters** — filter and reduce operations respect the active wand or lasso selection, only remapping colors present within the selection
- **Fine tolerance control** — Ctrl+`=` / Ctrl+`-` adjust wand and fill tolerance in 0.005 steps (vs 0.025 for plain `=` / `-`); Ctrl+scroll does the same

---

## v1.8.0

### Carpet overflow: CJK encoding (15,414 byte capacity)

Carpet overflow banners switched from base64 (36 bytes/banner) to the same 14-bit CJK encoding used by banner-only tiles since v1.6.0, giving a 2.3× capacity increase for the overflow channel and raising the total carpet tile capacity from ~12,473 to 15,414 bytes.

- **MAX_TOTAL_BYTES: 12,473 → 15,414**
- Old carpet tiles (base64 overflow) continue to decode correctly — the decoder distinguishes formats by character range
- Shade-channel threshold raised accordingly: more images now encode as flat LC tiles instead of requiring the staircase LS layout

### Editor: heatmap mode

- **H key** — toggles a heatmap overlay showing pixel change frequency across frames in animated tiles

### Palette command

- Per-tile color counts reported separately
- All-three-strategy removal cost simulation (shows projected size under Rarest, Closest, and Weighted simultaneously)

---

## v1.7.0

### Palette reduction strategies

Previously `/loominary reduce` always used the rarest-color strategy. This release adds two more strategies that produce better results on images with many similar colors clustered together.

- **CLOSEST** — merges the globally closest color pair each step; targets similar-color clusters
- **WEIGHTED** — scores pairs by dist²/(freqA + freqB); eliminates large similar-color clusters efficiently
- **`reduce strategy <rarest|closest|weighted>`** — sets the active strategy; banner-count reduce always uses Rarest for predictable compression, color-count reduce uses the active strategy
- **`reduce undo all`** — restores every tile to its pre-reduction state in one command

### Image filters

Filters apply in-place to the current tile state — they work after stride/skip/edit without reloading from disk.

- **`/loominary filter <smooth|median|sharpen|posterize> [all]`** — applies to the active tile or all tiles
- **P / Shift+P** — apply or cycle filter in the editor with palette-restricted re-quantization

### Editor improvements

- Ctrl+click canvas pixels to queue merge sources directly (same as Ctrl+clicking a palette swatch)
- Merge scope (V key) — cycle between frame, tile, and all-tiles scope for color merge commits
- Undo now snapshots all frames, so Ctrl+Z correctly reverts multi-frame merges and filters

### Palette command improvements

- Color counts now taken across all frames (union), not just frame 0
- Sigmoid-adaptive histogram bucket boundaries based on actual frequency distribution
- `palette all` — combined analysis across every tile in the batch

---

## v1.6.3

### Bugfix

- Reverted an over-budget import guard that incorrectly blocked carpet tiles — carpet tiles are allowed to import over the flat-channel budget because the overflow channel absorbs the excess

---

## v1.6.2

### Bugfixes

- Fixed carpet reduce budget calculation that was producing incorrect over-budget reports
- Fixed a dead catch block in the import path that was swallowing errors silently

---

## v1.6.1

### Cleanup

- Removed `AlphabetTestHandler` (development tool used during CJK alphabet validation; no longer needed in released builds)

---

## v1.6.0

### CJK encoding: 2.33× banner capacity

Banner names can hold Unicode characters, and Minecraft's item-rename filter passes CJK Unified Ideographs (U+4E00–U+8DFF) through unmodified on 2b2t. This release switches the banner encoding from base64 (6 bits/char) to a 14-bit CJK alphabet (14 bits/char), increasing payload capacity per banner from 36 bytes to 84 bytes.

- **Capacity: 2,268 → 5,292 bytes per tile** (63 banners × 84 bytes)
- Old base64-encoded maps continue to decode correctly — the decoder distinguishes formats by character range (ASCII = legacy, ≥ U+4E00 = CJK)
- Carpet overflow channel remains base64 in this release (upgraded in v1.8.0)

---

## v1.5.0

### Shade channel for large carpet tiles (LS format)

Carpet tiles that exceed the flat LC+overflow budget gain a second data channel encoded in carpet block heights. Three height levels (0, 1, 2) encode 4 bits per 4-row group, providing an additional ~2,016 bytes of capacity. The schematic exporter gains a staircase layout to match.

- **LS manifest format** — shade-channel tiles use an `LS`-prefixed manifest banner; old LC tiles are migrated automatically on export
- **Staircase schematics** — `SchematicExporter.exportCarpetStaircase()` places the stepped carpet layout in Litematica
- **Wall-clock animation timing** — frame delays switched from game ticks to milliseconds for frame-accurate playback regardless of TPS

---

## v1.4.0

### GIF frame control: stride and skip

Two commands let you reduce frame count on animated GIFs before encoding — useful when the animation is over the compression budget.

- **`/loominary stride <n>`** — keep every Nth frame (e.g. stride 3 on a 24-frame GIF → 8 frames)
- **`/loominary skip <n>`** — drop every Nth frame (e.g. skip 4 on a 24-frame GIF → 18 frames); dropped frame delays fold into the adjacent kept frame
- Both commands apply across all animated tiles in the batch

### Editor: color merge and palette histogram

- **Color merge** — Ctrl+click palette swatches to queue merge sources (shown with an orange ring); press C to commit, replacing all queued colors with the active color. Respects lasso/rect selection if active
- **Frequency histogram** — a thin green bar below each palette swatch shows that color's pixel frequency relative to the most frequent color

### Editor: tile minimap and navigation

- **G key** — opens a floating minimap showing all tiles as 32×32 thumbnails in their grid positions; click any tile to switch to it
- **Ctrl+Shift+`[`/`]`** — navigate to the previous/next tile by index within the editor
- **`/loominary tile pos <col> <row>`** — navigate to tile at specific grid coordinates

---

## v1.3.1

### Bugfix

- Fixed an issue that prevented images from reducing when they were already over budget — the over-budget check was blocking the reduction that would have fixed it

---

## v1.3.0

### Carpet encoding is now the default

Carpet encoding (which uses carpet block data as an additional data channel alongside banner names) provides far higher capacity than banner-only encoding and is now the default for all import paths. Pass `banners` as a keyword to use the legacy banner-only format.

- **`/loominary import <file>`** — carpet by default (was banner-only)
- **`/loominary import steal`** — carpet by default; `banners` keyword opts into legacy

### Animated GIFs on carpet tiles

- **`/loominary import <file.gif>`** — animated GIFs can now be imported as carpet tiles, compressing the full frame stack into the carpet+overflow channel

### Frame-aware editor

- The editor now loads all frames of an animated tile; Ctrl+`[` / Ctrl+`]` navigate between frames
- Frame N/M indicator shown in the status bar and guide panel
- On close, all frames are saved back

### Title rework

- **`/loominary title <text>`** — re-encodes all existing tiles with the new title embedded
- Auto-title: imports now derive the title from the filename stem when no explicit title has been set; steal uses `map_<id>`

---

## v1.2.2

### Map editor

A full pixel editor for Loominary map tiles, accessible via `/loominary edit` or a configurable keybind. Lets you paint directly on the 128×128 map color array before or after encoding, without touching the source image.

- **Pan/zoom** — scroll to zoom (1–16×, pixel-locked), middle-drag to pan; visible-range clipping keeps rendering fast at high zoom
- **Tools** — Brush (B), Fill bucket (F), Eyedropper (right-click), Select (S); brush size adjustable with `[`/`]` or Shift+scroll
- **Fill tolerance** — adjustable via `=`/`-` or Shift+scroll; uses Oklab perceptual distance so the fill respects visible color similarity, not exact byte matches
- **Palette panel** — shows colors present in the current tile sorted by frequency; click to select; right-click canvas to eyedropper
- **Rectangle selection** — drag to select; marching-ants border; Ctrl+A to select all, Ctrl+D/Esc to deselect; selection constrains all tools
- **Undo/redo** — Ctrl+Z / Ctrl+Y; snapshot-based (one snapshot per stroke, not per pixel)
- **Transparency** — color 0 (transparent/erase) is selectable from the palette

---

## v1.2.1

### Dithering and tile-grid seaming fix

The dithering pipeline was processing each tile independently, which caused visible color discontinuities and dithering-density jumps at tile seams. The full image is now processed as a single unit before being split into tiles.

- **Tile-grid seaming fix** — palette pre-selection, Otsu dither-strength threshold, and Floyd-Steinberg error diffusion now all operate on the complete image; error diffusion rows cross tile column boundaries naturally
- **Adaptive dithering** — `computeDitherStrength()` builds a per-pixel strength map using gradient suppression (Otsu threshold on local contrast) and an error floor; smooth areas dither at full strength, hard edges dither at zero to preserve sharpness
- **`/loominary dither [all] [colors <n>]`** — re-encodes from the saved source file with dithering; `colors <n>` triggers palette pre-selection to N colors before the dithered pass

---

## v1.2.0

### Anvil handler reliability: retry limit and resalt

On 2b2t a specific banner name became permanently rejected server-side after the handler's retry loop triggered an anti-spam threshold. This release adds a retry cap and a way to escape it.

- **Retry limit** — after 3 failed renames for the same chunk, the handler halts and shows a persistent overlay; it no longer loops indefinitely
- **Server-confirmed output** — the output slot now only triggers a pickup when the server confirms it via a real slot update, not from client-side prediction; this prevents premature moves to a slot the server sees as empty
- **`/loominary resalt`** — re-encodes the active tile with a random 4-byte nonce in a v2 manifest, producing entirely new chunk names for the same image; clears the halt state and resumes the handler

---

## v1.1.1

### Auto-click and full-grid preview

- **`/loominary click`** — toggles an auto-click handler that scans nearby banners and right-clicks any whose name matches a pending chunk in the active tile; 60-tick debounce per chunk, retries on timeout; wire-box visual markers show click status (yellow = sent, green = confirmed)
- **`/loominary preview`** — extended to BFS-flood the wall plane from the targeted item frame and paint all adjacent same-facing map frames within 24 blocks; paints as many tiles as possible and warns about missing or mismatched frames
- **Oklab color matching** — switched from Euclidean RGB to Oklab perceptual distance for nearest-color matching; produces more accurate palette assignments, especially for saturated colors
- **Manifest budget fix** — manifest header bytes were not counted in the `reduceToFit` compression budget, causing tiles to be slightly over-budget in practice

---

## v1.1.0

### Payload manifest and CI

A structured header is now prepended to every compressed tile payload, enabling format detection, CRC validation, and metadata (title, grid dimensions, allShades flag) without breaking old encoded maps. Automated GitHub releases were also set up in this version.

- **Versioned manifest (v1)** — wire layout: version, header size, flags, grid dimensions, CRC32 of map colors, username, title; decoder detects v0 (raw 16,384-byte payload) vs v1+ automatically
- **`/loominary title [text]`** — sets the title embedded in subsequent encodes
- **Automated releases** — `release.yml` triggers on `v*` tag push, builds the mod, and attaches the remapped jar to a GitHub release
- **CI fixes** — corrected action versions, Java target (25 → 21), added Gradle caching and test execution

---

## v1.0.0

### Initial release

The core encoding pipeline: import an image, encode it into banner names, place banners in item frames, and have clients automatically decode and render the image on any map that displays those banners.

- **`/loominary import <file>`** — scales image to 128×128, nearest-color matches to the Minecraft map palette, zstd-compresses and base64-encodes into banner name chunks
- **`AnvilAutoFillHandler`** — tick-based anvil automation that renames banners one at a time with the encoded chunks and packs them into bundles
- **`MapBannerDecoder`** — scans nearby item frames every 20 ticks, reads banner decorations off framed maps, reassembles and decompresses the payload, and writes directly into `MapState.colors`
- **`/loominary export`** — writes a Litematica v6 `.litematic` schematic with banners arranged in a 16-column grid
- **`/loominary preview`** — decodes the active tile onto the crosshair-targeted map without placing banners; `/loominary revert` restores the original
- **`/loominary status`** — shows current batch state, chunk progress, and encoding stats
