# TODO

Living roadmap for Loominary. Items grouped by rough priority. Strikethrough or remove items as they ship.

## Foundational — do these first; they unblock or de-risk everything else

### ~~Versioned payload format~~
~~Add a "manifest" structure (probably the first chunk per tile, or a reserved prefix) that records:~~
- ~~Format version number~~
- ~~Capability flags (dithering used, palette mode, etc.)~~
- ~~Tile dimensions (for grid awareness without requiring spatial inference)~~
- ~~Source filename hash or checksum~~

~~Without this, any future format change risks breaking everyone's existing encoded maps. With it, the decoder can detect old payloads and handle them gracefully, and new features can be opt-in via flags.~~

~~The manifest itself needs a stable, versioned format — meta-versioning. Probably a single byte for "manifest version" followed by a fixed schema for that version, with v0 being whatever we pick first.~~

**Shipped in v1.1.0.** Wire layout: `manifest_version`, `header_size` (skip-pointer), `flags`, `cols/rows/tile_col/tile_row`, `color_crc32` (CRC32 of map-color bytes, self-verifiable at decode), `username`, `title`. v0 payloads decode identically. `/loominary title` command sets the title embedded in subsequent encodes.

### ~~Continuous integration via GitHub Actions~~
- ~~Build the mod on every push and PR~~
- ~~Run tests (once we have them)~~
- ~~On `v*` tag push, automatically build and attach jar to GitHub release~~
- Eventually: auto-publish to Modrinth/CurseForge on tagged release

**Shipped in v1.1.0.** `build.yml` builds and runs tests on every push/PR. `release.yml` creates a GitHub release with the remapped jar on `v*` tag push.

### Test infrastructure
JUnit 5 wired up. 22 tests passing. Still missing:
- `PngToMapColors.convert` — blocked on Minecraft API (`MapColor`); needs either Fabric game tests or extracting the color table as an injectable parameter
- `PngToMapColors.reduceToFit` — same blocker
- `LitematicExporter` — depends on Minecraft NBT API

`MapBannerDecoder.reassemblePayload` is covered (6 tests including v0/v1, out-of-order chunks, corrupt payloads).

## High-value features

### ~~Auto-right-click of banners with map~~
~~Once banners are placed in a 128×128 area and the player is holding the right map, automate the right-click step.~~

**Shipped.** `/loominary click` (toggle) / `/loominary click stop`. Player walks near each row of banners while holding the map; the handler scans a ±5-block cube every 5 ticks, picks the closest unregistered banner in reach, computes a face-aware `BlockHitResult`, and calls `interactionManager.interactBlock`. Auto-stops when `MapBannerDecoder` marks the map as claimed. Action-bar overlay shows remaining count. No movement automation — the player handles walking; the mod handles clicking.

Possible future improvement: automatic path-finding to unvisited banners so the player doesn't need to walk at all.

### ~~Wall-grid preview~~
~~Currently `/loominary preview` paints only the active tile onto the crosshair-targeted map.~~

**Shipped.** `/loominary preview` now BFS-flood-fills from the targeted frame along the wall plane (±right-axis, ±Y), discovers all adjacent same-facing filled-map frames within 24 blocks, and paints the matching tile onto each. Grid dimensions must match the batch exactly or the command refuses with a descriptive error. `originalColors` is populated for every frame so `/loominary revert` works on any map individually. Decoration suppression deferred (decoder relies on them).

Open: visual pre-commit indicator ("which tile goes where" before painting).

### ~~Floyd-Steinberg dithering~~

**Shipped in v1.2.1.** Two-pass pipeline in `PngToMapColors.convertTwoPassGrid`:
- Pass 1 nearest-neighbor discovers candidate map colours; optional palette pre-selection (`colors <n>`) reduces to N global colours.
- Adaptive strength map: local contrast (RMS Oklab distance to 4-connected neighbours) computed across the full grid image; Otsu's method finds the image-relative smooth/edge threshold; linear soft zone [0.5T, 1.5T] gives a gradual fade rather than a binary cut.
- Floyd-Steinberg error diffusion in Oklab space, gated by both the gradient-suppression map and a perceptual error floor (0.015 Oklab units) to avoid noise in well-matched regions.
- Full-grid processing: palette pre-selection, Otsu calibration, and error diffusion all operate on the complete `cols×128 × rows×128` image before tile splitting, eliminating palette discontinuities, dithering-density jumps, and error-reset artefacts at seams.
- `/loominary import … dither`, `/loominary dither [all] [colors <n>]`.

Still open:
- Compression-aware variant (prefer dither patterns that compress well; current approach reduces after dithering if needed)
- Live in-world preview of multiple algorithms (Floyd-Steinberg, Atkinson, Bayer, Sierra)
- Per-region dither control via brush → lives in the in-world editor (see below)

### ~~LAB color space matching~~

**Shipped.** All nearest-color matching and error distribution uses Oklab perceptual distance throughout — encoding, palette reduction (`reduceToFit`, `reduceToColorCount`), and dither error diffusion.

## Medium-value features

### In-world editor mode

A modal editor for live, in-world pixel editing of the active tile. The two architecture decisions below are load-bearing and must be resolved before any code is written — they affect every tool and the undo model.

**Architecture decisions (resolve first)**

1. **Working colour space: map-color bytes.** The editor operates directly on the `byte[16384]` array, not on RGB. This means what you paint is exactly what encodes, with no re-quantization latency. The palette panel shows only the map colours (~186 legal or ~248 all-shades) — the same colour space the rest of the pipeline uses.

2. **Undo model: full-frame snapshots.** Before each destructive operation, snapshot the entire 128×128 byte array and push it onto a `Deque<byte[]>` (cap at 20). At 16 KB per snapshot, 20 levels costs 320 KB — fine. No branching undo; v1 is linear only. Ctrl+Z / Ctrl+Y.

3. **Source-image awareness (deferred).** Per-region re-quantization from the original image is Phase 6+ work. v1 edits are destructive; source awareness is not required to ship the core editor.

---

**Phase 1 — Screen skeleton** *(delivers: see-only viewer)*
- `MapEditorScreen extends Screen`: renders the 128×128 colour array as a grid of coloured quads at a configurable scale (default 4×, max ~8× depending on screen resolution)
- Middle-click drag to pan; scroll wheel to zoom
- Mouse → pixel coordinate mapping (accounts for pan/zoom offset)
- Hovered pixel highlighted with an outline or inverted border
- Keybind to open from the crosshair-targeted item-frame map (or `/loominary edit`)
- On close: re-encode the (possibly modified) byte array back into the active tile's chunks and save state

**Phase 2 — Undo stack + single-pixel paint** *(delivers: useful v0 editor)*
- `EditHistory`: snapshot-based undo/redo with Ctrl+Z / Ctrl+Y
- Left-click to paint the hovered pixel with the active colour
- Active colour displayed in a small swatch in the UI corner
- Phase 2 alone — viewer + paint + undo — is already more useful than any existing tool for touching up individual pixels

**Phase 3 — Palette panel + eyedropper** *(delivers: full basic colour workflow)*
- Side panel listing all distinct colours currently in the tile (sorted by frequency), each as a clickable swatch
- Optional "all colours" toggle to expand to the full map palette
- Right-click on canvas = eyedropper: sets active colour to the clicked pixel's value
- Hover shows colour index and pixel count in a tooltip

**Phase 4 — Brush size and fill bucket**
- Configurable brush radius (1 / 2 / 3 / 5 / 7 px); circle footprint; footprint previewed as cursor overlay
- Fill bucket: 4-connected flood fill in map-colour space; snapshots the full frame before filling

**Phase 5 — Rectangle selection**
- Drag to define selection marquee; visual overlay (dashed border, semi-transparent tint)
- All paint/fill operations clamp to selection when one is active
- Escape or click-outside to deselect
- Establishes the selection infrastructure that later phases build on

**Shipped in current version.** All phases 6–8 + Eyedropper:

*Phase 6* — `R` re-quantizes the active selection (or whole tile) from the source image. `D` toggles Floyd-Steinberg dithering. Enter/Y commits the preview, Esc cancels. `PayloadState.dither` persists the dither setting across sessions.

*Phase 7* — `T` activates the dither-strength brush. Left-click paints strength 0–1 into a `float[16384]` overlay; right-click erases to 0. `M` shows/hides the yellow heat overlay. Shift+scroll or `=`/`-` adjusts paint strength. When a custom mask exists and dither is on, `R` (re-quantize) uses the mask instead of the Otsu-computed strength.

*Phase 8* — Selection refactored from a rect to `boolean[16384]` to support non-rectangular shapes. `L` activates the lasso (freehand drag, closes on release via `Path2D`). `W` activates the magic wand (4-connected Oklab flood-fill; tolerance shared with fill bucket via Shift+scroll or `=`/`-`).

*Eyedropper* — `E` activates a dedicated eyedropper tool; left-click picks colour and auto-reverts to the previous tool. Right-click eyedropper still works universally on BRUSH and FILL tools.

---

Estimated scope: Phase 1–3 ≈ 400–500 lines; Phases 4–5 add ~300; Phases 6–8 add ~500 each. Total ≈ 1800–2000 lines. Phases 1–5 can ship as a useful standalone feature before any source-image integration is needed.

### Animated map art

Encode a GIF as multiple frames in a single payload. The decoder cycles frames on a tick timer, repainting the map texture each transition. Big creative unlock — scrolling text, blinking signs, looping sprites.

---

**Capacity analysis (must read first)**

Available space per tile: 255 banners × 48 b64 chars = 12,240 chars → ~9,180 bytes compressed.

Frame storage layout: **concatenated raw frames** — frame 0 bytes followed immediately by frame 1, etc. (`frame_count × 16,384` map-color bytes after the manifest header). Zstd operates on the full combined payload and naturally exploits inter-frame repetition without any delta-coding complexity. Measured estimate:

- Static tile (1 frame): ~1.5–3 KB compressed (typical mapart content)
- 2 frames, similar content: ~2–4 KB
- 4 frames, similar content: ~3–6 KB (realistic target for most GIFs)
- 8 frames, high-similarity: possible but image-dependent

Hard cap: if `frame_count × 16384` bytes do not fit in 9,180 compressed bytes, encoding must fail with a descriptive error (suggest reducing frames or simplifying palette). There is no automatic truncation.

**Backward compatibility:** old decoders (v0–v2) find map colors at `header_size`, read exactly 16,384 bytes, and get frame 0. They render a static image and ignore the rest. No action needed on their side.

---

**Architecture decisions (resolve before writing code)**

1. **Wire format: manifest v3.** Add `FLAG_ANIMATED = 0x02` to the flags byte. New fields appended after existing v2 fields:
   - `frame_count` u8 — total frames; 1 for non-animated tiles (always present in v3 but meaningless unless FLAG_ANIMATED is set)
   - `loop_count` u16 big-endian — 0 = loop forever; >0 = stop after N loops
   - `delay_mode` u8 — 0 = single shared delay, 1 = per-frame table
   - `delay` u16 big-endian (if delay_mode=0) or `frame_count × u16` (if delay_mode=1) — milliseconds per frame

   `header_size` skip-pointer allows future decoders (and old ones) to land at the right byte regardless of delay table length. Map-color bytes follow at `header_size`, totalling `frame_count × 16384` bytes.

2. **GIF ingest: coalesce frames before quantizing.** `ImageIO`'s GIF decoder returns each frame as a partial image with a disposal metadata field (`doNotDispose`, `restoreToBackground`, `restoreToPrevious`). You must composit each decoded frame onto a running canvas (full 128×128 RGBA) respecting the disposal mode before scaling to 128×128 and quantizing. Getting this wrong produces corrupted frames on any GIF that uses partial-region updates.

3. **Shared palette across frames.** Palette reduction (`reduceToColorCount`, dither pre-selection) runs once over the **union** of all frames' pixel sets, then each frame is quantized against the same global palette. This prevents inter-frame color flicker and gives zstd more repetition to exploit.

4. **`PayloadState` extension.** `TileData` gains `int frameCount` and `List<Integer> frameDelays`. The `byte[][]` frame data is **not** stored in `PayloadState` — it is reconstructed from chunks on demand (same as today). The state file only stores frame metadata so the editor knows how many frames exist without decoding.

5. **Decoder reshape (`MapBannerDecoder`).** Currently single-write-then-claim. Animated maps require:
   - Per-map frame state: `Map<Integer, AnimatedMapState>` keyed by map ID storing `byte[][]` frames, current frame index, delay table, loop counter, and a `lastAdvanceTick` timestamp.
   - Tick driver: advances frame index when `currentTick - lastAdvanceTick >= delayTicks`. Updates `MapState.colors` and calls `setNeedsUpdate`.
   - Distance culling: pause advancement when the player is >32 blocks from the map's item frame. Resume on re-approach.
   - Multi-tile sync: all tiles in a mural must declare the same `frame_count` and `frameDelays`. On decode, the decoder groups frames by mural (grid position from manifest) and advances them together in a single tick pass so the grid never shows mixed frames.

6. **Editor frame model.** `MapEditorScreen` gains:
   - `byte[][] frames` working copy (loaded from chunks on open; frame 0 is current static tile, additional frames appended when editing animated content).
   - `int activeFrame` index; `←`/`→` (or `[`/`]`) navigate frames.
   - Per-frame undo stack: `Deque<byte[]>[]` — one deque per frame, same 20-snapshot cap.
   - Frame ops: `Ins` to duplicate the active frame, `Del` to remove it (minimum 1 frame).
   - Dither mask `float[16384]`: **per-frame** to allow different dither densities per frame.
   - Onion skin toggle (`O`): composites previous frame at 30% opacity underneath the active frame's pixels as a visual guide.

7. **Multi-tile enforcement.** `/loominary import <file.gif> …` encodes all tiles with the same `frame_count` and `frameDelays` (derived from GIF metadata). The encoder rejects any per-tile result that exceeds the 255-banner cap and reports which frame and tile failed. `/loominary dither all` on an animated batch must re-encode all frames; single-tile dither is blocked with an explanatory error.

---

**Phase 0 — Manifest v3 + format scaffolding** *(no visible behavior change)*

- Add `FLAG_ANIMATED`, `frame_count`, `loop_count`, `delay_mode`, `delay` to `PayloadManifest.toBytes` and `fromBytes`. Manifest version bumps to 3.
- `PayloadManifest.CURRENT_VERSION = 3`.
- Unit tests: encode/decode round-trip with 1 frame (no behavioral change), 4 frames, per-frame vs. global delays.
- `PayloadState.TileData` gains `frameCount` and `frameDelays`; `Snapshot` serializes them. No behavioral change.

**Phase 1 — GIF ingest + multi-frame encode** *(delivers: `/loominary import file.gif` works)*

- `PngToMapColors.convertGif(Path)`: reads GIF with `ImageIO`, coalesces frames respecting disposal metadata, scales each coalesced frame to `128 × 128`, extracts frame delays from metadata.
- Shared-palette path: run `reduceToColorCount` / dither pre-selection over the pixel union of all frames, then quantize each frame independently against the global palette.
- `LoominaryCommand.importFile`: detect `.gif` extension, delegate to GIF path, build one `TileData` per spatial tile (each containing `frame_count × 16384` bytes worth of chunks).
- Error path: if any tile's chunk count > 255, report `"Tile (col,row): N chunks for M frames — reduce colors or frame count"`.
- Non-animated files (`.png`, `.jpg`) continue to use the existing single-frame path unchanged.

**Phase 2 — Animated decoder** *(delivers: GIF plays in-world)*

- `MapBannerDecoder`: on decode, if `FLAG_ANIMATED` is set, parse all frames from the decompressed payload (`frame_count × 16384` bytes starting at `header_size`).
- `AnimatedMapState`: record struct holding frames array, delays, loop count, current frame, last-advance tick.
- Tick driver: per-map, compare elapsed ticks to current frame's delay; advance if due. Call `paintMap` and `setNeedsUpdate`.
- Distance culling: skip tick advancement if no item frame containing this map ID is within 32 blocks of the player.
- Multi-tile sync: group maps by `(title, cols, rows)` or by mural spatial proximity; advance all in the same pass.

**Phase 3 — Editor frame nav** *(delivers: frame-by-frame editing in MapEditorScreen)*

- Open: if tile has `frameCount > 1`, decode all frames from chunks into `byte[][] frames`. Otherwise `frames = new byte[][]{currentColors}`.
- Frame nav HUD: "Frame N/M" indicator in corner; `[` / `]` to step.
- Per-frame undo (`Deque<byte[]>[]`).
- Frame ops: `Ins` = duplicate active frame after current position; `Del` = remove active frame (blocked if frameCount == 1).
- On close: re-encode all frames back into chunks; update `TileData.frameCount` and `TileData.frameDelays`.

**Phase 4 — Per-frame dither mask + onion skin** *(quality-of-life for animators)*

- Per-frame `float[16384]` dither mask; `M` overlay works per-frame.
- Onion skin: `O` toggle composites the previous frame at ~30% opacity beneath the current frame's paint layer. Read-only guide layer, never encoded.
- `R` (re-quantize) applies shared-palette re-quantization to the active frame only; uses this frame's dither mask.

---

Files touched across all phases: `PayloadManifest` (v3 wire format), `PngToMapColors` (GIF ingest, shared-palette multi-frame), `PayloadState` (frame metadata persistence), `MapBannerDecoder` (cycling, culling, multi-tile sync), `MapEditorScreen` (frame nav, per-frame undo, onion skin), `LoominaryCommand` (GIF detection, import wiring), `ChunkAssemblyTest` / `PayloadManifestTest` (v3 coverage).

Estimated scope: Phase 0 ≈ 100 lines; Phase 1 ≈ 300; Phase 2 ≈ 250; Phase 3 ≈ 200; Phase 4 ≈ 150. Total ≈ 1,000 lines.

### Encoding throughput optimization
Brute-force pixel-by-pixel quantization is slow on larger grids. Replace with:
- Precomputed RGB→nearest-map-color lookup, structured as octree or 3D LUT
- For palette reduction: cache neighbor distances between iterations rather than recomputing
- Possibly parallelize per-tile encoding when batch has many tiles

Makes everything snappier; especially noticeable on murals.

### Shareable payload export/import
A `/loominary export payload` that writes the active tile's chunks to a small text file. A `/loominary import payload <file>` that consumes one. Lets people swap pre-encoded designs without sharing the source image or dealing with grid math.

Good fit for a community-driven gallery model.

### Hotkey for export
Add a key binding for `/loominary export`. One-line addition.

### ~~Color histogram in palette command~~

**Shipped in v1.2.1.** `/loominary palette` now shows a proportional `█` bar chart bucketed by pixel frequency (1 / 2–5 / 6–20 / 21–100 / 101+), plus a cumulative removal-cost table at the 5/10/20/50-color thresholds. Also shipped in the same release: multi-tile reduce (`reduce all`), color-count reduction target (`reduce [all] colors <n>`), and reduction undo across tiles.

## Lower-priority / nice-to-have

### Auto-reduce heuristic improvements
Detect that an image won't fit *before* the first compression pass, give early warning. Current behavior: encode → compress → check size → reduce iteratively. Smarter: estimate distinct color count and predict whether reduction will be needed.

### Server-side coordinated mode
Companion server-side mod. When both sides are present, embed data more compactly (binary vs. base64 — roughly doubles capacity per banner). Falls back to current encoding when only client-side. Optional.

### Steganographic mode
Hide payload in LSBs of an otherwise-unmodified map's colors. Decoder recovers data; a non-Loominary user sees an apparently-normal map of real terrain. Much smaller capacity but truly invisible.

Nobody else does this. Real differentiation, but might be a solution looking for a problem.

### Litematica API integration
Push schematics directly into Litematica's placement list rather than writing a `.litematic` file the user has to manually move. Removes a step from the workflow.

### Auto-preview while building
Toggle to live-update preview as banners are placed. Useful when iterating on placement; needs lots of testing to avoid spamming texture re-uploads.

### Internationalization
Pull all user-facing strings into translation keys. Probably premature until the mod has international users; current English-only code is a wash.

### Documentation site
GitHub Pages with screenshots, example workflows, gallery. The README is doing fine for now but a real docs site would help adoption.

### Voxel/3D-aware encoding
Encode block-level reconstruction info — "build this structure with these blocks at these positions" — instead of pixel data. Different domain entirely; the banner-as-data-carrier mechanism generalizes but the use case is unclear.

## Explicitly rejected

### `/loominary import url <url>`
Considered. Rejected on security grounds — letting users trigger downloads from arbitrary URLs at someone else's request creates obvious abuse vectors. Tricking someone into downloading a malicious file just by getting them to run a command is not a tradeoff worth making for the convenience of skipping a file copy.

### "Skip the right-click step" decoder
Considered. The idea was to detect named banners directly in-world, no map markers needed. Rejected because it breaks the mod's core portability story: the whole point of map-based encoding is that a framed map in someone's vault is durable and shareable across players, while banners can be destroyed or griefed. Removing the right-click step means losing the framed-map artifact entirely, which kills the value proposition on servers like 2b.

## Maybe someday

- MIDI-synced animated mapart
- Web-based encoder/preview tool (lives outside the mod)
