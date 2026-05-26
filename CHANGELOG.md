# Changelog

> **Process note:** The newest release always goes at the top. Write notes in the style of this document — a short overview paragraph explaining the purpose of each feature group, followed by specific bullet points. Update this file before tagging and pushing to GitHub.

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
