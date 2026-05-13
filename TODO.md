# TODO

Living roadmap for Loominary. Items grouped by rough priority. Strike or remove items as they ship.

## Foundational — shipped

### ~~Versioned payload format~~
**Shipped v1.1.0.** Wire layout: `manifest_version`, `header_size` (skip-pointer), `flags`, `cols/rows/tile_col/tile_row`, `color_crc32`, `username`, `title`. v0 payloads decode identically.

### ~~Continuous integration via GitHub Actions~~
**Shipped v1.1.0.** `build.yml` builds and runs tests on every push/PR. `release.yml` creates a GitHub release with the remapped jar on `v*` tag push.

### ~~Test infrastructure~~
**Shipped.** JUnit 5 wired up. Current test coverage:
- `MapBannerDecoder.reassemblePayload` — v0/v1, out-of-order chunks, corrupt payloads
- `PayloadManifest` — v3 animation fields round-trip
- `CarpetChannel` shade channel — SEQ4/SEQ3 tables, DEC4/DEC3 round-trips, `computeHeights`, `decodeShade` full round-trips
- `CjkCodec` — encode/decode round-trips (empty, single byte, single banner, multi-banner, all bytes 0–255), `buildChunks`/`assembleChunks` round-trip, `chunksNeeded` vs actual chunk count

Still missing:
- `PngToMapColors.convert` — blocked on Minecraft API (`MapColor`); needs either Fabric game tests or extracting the color table as an injectable parameter
- `LitematicExporter` / `SchematicExporter` — depends on Minecraft NBT API

## High-value features — shipped

### ~~Carpet hybrid encoding~~
**Shipped v1.3.0.** 8,192 bytes from carpet channel + up to 2,265 bytes overflow via LC banner + hex-indexed banners. Total 10,457 bytes per tile. Carpet schematic auto-exported to Litematica on import.

### ~~Shade channel (LS format)~~
**Shipped v1.5.0.** Carpet height variation encodes an additional 2,016 bytes. Heights {0,1,2} relative to the north-neighbor row encode 4-bit nibbles via 16 balanced 4-row sequences (SEQ4) plus a 3-row tail (SEQ3). Automatically engaged when the flat carpet + overflow capacity is exceeded. Writes an LS manifest banner (`LS<total><shadeLen>`) instead of LC; staircase schematic instead of flat platform. Old decoders that only know LC ignore LS banners gracefully. Grand total with shade channel: 12,473 bytes per tile.

### ~~CJK overflow banner encoding~~
**Shipped v1.6.0.** Banner overflow encoding switched from base64 (6 bits/char, 36 bytes/banner) to a 14-bit CJK alphabet (U+4E00–U+8DFF, 84 bytes/banner) — 2.33× improvement. Validated on 2b2t via automated rename probes; all tested code points in the range passed unmodified. Format: 2-byte big-endian length header prepended before bit-packing; discriminated from old base64 chunks by a single char-range check (`≥ U+4E00`). Old base64 banners decode correctly on new clients.

### ~~Carpet overflow also CJK-encoded~~
**Shipped v1.8.0.** Carpet overflow banners (the hex-indexed banners beyond the carpet+shade channel) were migrated to the same CJK encoding used by banner tiles. Grand total capacity: 15,414 compressed bytes per tile.

### ~~Named save/load state~~
**Shipped v1.10.0.** `/loominary save [name]` and `/loominary load <name>`. Every import auto-saves to `loominary_saves/<stem>_NNN.json` with a monotonic counter. `PayloadState.saveToFile/loadFromFile` serialize the full batch state. Tab-completion lists existing saves.

### ~~Image export~~
**Shipped v1.10.0.** `/loominary export image` renders the active tile as a PNG (static) or looping animated GIF (animated) to `loominary_exports/`. GIF export builds an `IndexColorModel` from the exact map color bytes — no quantization loss.

### ~~Editor color reduction (K key + budget badge)~~
### ~~FLAG_LINKED shared-dictionary multi-tile animated GIFs~~ **Shipped.** `/loominary import ... linked`, `/loominary link`/`unlink`, rich status reporting, decoder group coordination, and manifest flag support.
**Shipped v1.10.0.** `K` merges one palette step using the active strategy; `Shift+K` cycles strategies. A budget badge in the palette header shows compressed bytes vs. capacity (or banner count vs. 63) and turns red when over budget.

### ~~Selection-aware editor filters and reduction~~
**Shipped v1.10.0.** When a selection is active, re-quantize only considers colors present in the selection as candidates, and single-step reduction only merges from colors present in the selection.

### ~~GIF requantize frame awareness~~
**Shipped v1.10.0; enhanced v1.11.0.** The editor's re-quantize tool (`R`) now correctly uses the active animation frame as the source rather than always reading frame 0. Uses `PngToMapColors.coalesceGifFrames()` extracted for reuse. v1.11.0 adds full frame provenance tracking: after `/loominary stride` or `/loominary skip` thins the GIF, each editor frame remembers which original GIF frame it came from. `R` always pulls the correct source frame automatically. `Shift+[` / `Shift+]` lets users override the source frame manually.

### ~~Auto-right-click of banners with map~~
**Shipped.** `/loominary click` (toggle). Player walks near banners while holding the map; handler scans a ±5-block cube every 5 ticks, computes face-aware `BlockHitResult`, calls `interactionManager.interactBlock`. Auto-stops when all banners are registered.

### ~~Wall-grid preview~~
**Shipped.** `/loominary preview` BFS-flood-fills from the targeted frame along the wall plane, discovers all adjacent same-facing filled-map frames within 24 blocks, and paints the matching tile onto each. `/loominary revert` works on any map individually.

### ~~Floyd-Steinberg dithering~~
**Shipped v1.2.1.** Two-pass pipeline: palette pre-selection → Otsu adaptive strength map → error diffusion in Oklab space. Full-grid processing eliminates seam artefacts. `/loominary import … dither`, `/loominary dither [all] [colors <n>]`.

### ~~Oklab color space matching~~
**Shipped.** All nearest-color matching and error distribution uses Oklab throughout.

### ~~Animated map art~~
**Shipped v1.3.0.** GIF ingest, multi-frame payload, wall-clock-based frame advancement, distance culling, multi-tile sync. `/loominary stride <n>` and `/loominary skip <n>` thin animated tiles directly.

### ~~In-world editor~~
**Shipped.** Full 128×128 pixel editor. See [EDITOR.md](EDITOR.md) for complete documentation.

### ~~Color palette histogram~~
**Shipped v1.2.1.** `/loominary palette` shows proportional `█` bar chart plus cumulative removal-cost table.

## Open work

### Encoding throughput optimization
Brute-force pixel-by-pixel quantization is slow on larger grids. Opportunities:
- Precomputed RGB→nearest-map-color lookup (octree or 3D LUT)
- Cache neighbor distances in palette reduction between iterations rather than recomputing
- Parallelize per-tile encoding when the batch has many tiles

Especially noticeable on large murals and animated GIFs.

### Shareable payload export/import
A `/loominary export payload` that writes the active tile's chunks to a small text file. A `/loominary import payload <file>` that consumes one. Lets people swap pre-encoded designs without sharing the source image or dealing with grid math. Good fit for a community gallery model.

### Per-frame dither masks in the editor
Each animated frame should have its own `float[16384]` dither mask. Currently the mask is shared across all frames. The dither brush (`T`), mask overlay (`M`), and re-quantize (`R`) should all operate per-frame.

### Onion-skin view in animated editor
`O` toggle compositing the previous frame at ~30% opacity as a read-only guide beneath the active frame. Makes animation editing much easier.

### Per-frame undo in animated editor
The undo stack currently clears on frame switch. A `Deque<byte[]>[]` (one deque per frame) would fix this. The single shared stack is functional but loses history when switching frames.

### Hotkey for export
Add a key binding for `/loominary export`. One-line addition.

### Visual tile assignment overlay
Before `/loominary preview`, show an overlay indicating which tile in the batch maps to which frame in the wall, so the user can verify alignment before committing.

## Lower-priority / nice-to-have

### Auto-reduce heuristic improvements
Detect that an image won't fit *before* the first compression pass. Estimate distinct color count and predict whether reduction will be needed, giving early warning rather than encode → compress → check → reduce.

### Documentation site
GitHub Pages with screenshots, example workflows, gallery. The README is doing fine but a real site would help adoption.

### Hotkey for editor
A direct keybind to open the editor without typing the command.

### Litematica API integration
Push schematics directly into Litematica's placement list rather than writing a `.litematic` file the user has to manually move.

### Auto-preview while building
Toggle to live-update preview as banners are placed. Needs testing to avoid spamming texture re-uploads.

### Internationalization
Pull all user-facing strings into translation keys. Probably premature until the mod has international users.

## Explicitly rejected

### `/loominary import url <url>`
Rejected on security grounds — letting users trigger downloads from arbitrary URLs creates obvious abuse vectors.

### "Skip the right-click step" decoder
Rejected — it breaks the core portability story. The framed map artifact is durable and shareable; removing the right-click step kills the value proposition on servers like 2b2t.

### Server-side coordinated mode
Rejected for now — adds deployment complexity; the CJK encoding already achieves near-parity with a "binary vs. base64" server-side optimization without requiring a server mod.

## Maybe someday

- MIDI-synced animated mapart
- Web-based encoder/preview tool (lives outside the mod)
- Steganographic mode (LSBs of normal-looking terrain map)
- Voxel/3D-aware encoding ("build this structure with these blocks")
