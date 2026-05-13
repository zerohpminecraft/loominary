# Loominary

A Fabric mod for Minecraft 1.21.4 that encodes images as map art and plays them back client-side, with no server modification required.

Loominary encodes image data into colored carpet blocks and named banners, then renders it client-side as custom map art. Place the carpet schematic, click the banner marker(s) with a map, and anyone running Loominary sees your image painted on the map — including animated GIFs. Without the mod, viewers see a normal map.

This works on **any vanilla server**. The primary encoding mode uses carpet block colors as a data channel (8,192 bytes) supplemented by a shade channel (up to 2,016 bytes via carpet height variation) and up to 62 named overflow banners carrying 84 bytes each (CJK-encoded), with a single LC/LS manifest banner as the decoder trigger. This design was built for servers like 2b2t that impose strict limits on map banner markers (63 per map).

## Features

- **Carpet channel encoding** (default): 16 carpet colors encode 4-bit nibbles across 128×128 map positions — 8,192 bytes primary channel
- **Shade channel** (LS format): carpet height variation encodes an additional 2,016 bytes via balanced 4-row height sequences; requires a staircase schematic instead of a flat platform
- **CJK overflow banners**: named overflow banners carry 84 bytes each (14-bit CJK alphabet, 2.33× the base64 capacity). Combined capacity: 15,414 compressed bytes per tile
- **Encode any image** into carpet + banner data (PNG, JPEG, GIF, BMP — anything `ImageIO` can read)
- **Animated GIF import**: encode a GIF as multiple frames in a single payload; the decoder cycles frames on a wall-clock timer with distance culling and multi-tile sync
- **In-world pixel editor** (`/loominary edit`): full-featured 128×128 canvas — see [EDITOR.md](EDITOR.md) for a complete guide
- **Multi-tile murals**: split a large image across an N×M grid of maps for wall-sized art
- **Steal existing map art** as a Loominary payload by looking at any framed map
- **Perceptual color matching** using Oklab color space — better-looking results than RGB Euclidean distance, especially on gradients and skin tones
- **Progressive palette reduction** automatically simplifies images that won't fit within capacity, with full preview and undo
- **Auto-right-click**: hold your map and walk near your placed banners — `/loominary click` handles every right-click automatically with live status and visual markers
- **Client-side rendering** with marker suppression — the banner pins disappear, leaving a clean image
- **Workflow automation** at the anvil: stack-aware banner extraction, automatic renaming, automatic bundle storage
- **Stuck-chunk recovery**: if the server permanently rejects a banner name the handler halts cleanly; `/loominary resalt` re-encodes the tile with a random nonce — same image, new chunk names, works for both carpet and banner tiles
- **Two-pass dithering with adaptive edge detection**: Floyd-Steinberg error diffusion in Oklab space, with per-pixel strength controlled by an image-relative Otsu threshold — smooth gradients dither fully, sharp edges stay crisp, solid fills stay clean
- **Multi-tile grid consistency**: dithering operates on the full grid image before splitting into tiles, eliminating colour and texture discontinuities at seams
- **Color palette histogram**: `/loominary palette [all]` shows a rarity distribution histogram with sigmoid-adaptive bucket boundaries and cumulative removal-cost table; `palette all` covers every tile's frames together
- **Flexible palette reduction**: reduce by banner count or distinct colour count, on the active tile or all tiles at once; three palette strategies (rarest, closest, weighted) control how colors are merged
- **Image filters**: `/loominary filter smooth|median|sharpen|posterize [all]` applies spatial pre-processing in-place on the current tile state — no source file required, preserves skip/stride/edit work; also available in the editor with `P`
- **Named save/load**: `/loominary save [name]` saves the full batch state to `loominary_saves/`; every import auto-saves with a monotonic counter. `/loominary load <name>` restores any save, replacing the current state. Tab-completion lists existing saves
- **Image export**: `/loominary export image` renders the active tile as a lossless PNG (static) or looping animated GIF to `loominary_exports/`
- **Litematica schematic export**: `/loominary export` (or `/loominary export <name>`) writes a Litematica `.litematic` for the active tile — flat or staircase depending on the tile's channels; on demand only (no longer automatic on import)
- **Grid-aware preview**: `/loominary preview` discovers the full wall of framed maps from any frame and paints all tiles at once
- **Embedded metadata**: every payload records the image title, author username, grid position, and a CRC32 integrity check; animated payloads carry frame count, loop count, and per-frame delays
- **Auto-title**: each import derives a title from the filename stem; steal uses `map_<id>`; `/loominary title` overrides and immediately re-encodes all existing tiles
- **Persistent state** survives game restarts; pick up any unfinished batch
- **Configurable hotkeys** for the most-used actions
- **Crosshair-targeted commands** — preview, revert, edit, and steal use whatever framed map you're looking at
- **Legacy banner-only mode**: add `banners` to any import command for servers where carpet placement isn't feasible

## Requirements

- Minecraft **1.21.4** with Fabric Loader **0.19.2** or newer
- Fabric API
- Java 21
- Client-side only — no server installation required

## Installation

1. Download `loominary-1.10.0.jar` from the [releases page](https://github.com/zerohpminecraft/loominary/releases)
2. Drop it into your `mods/` folder alongside Fabric API
3. Launch the game

You'll see `[Loominary] Client-side mod initialized successfully!` in the log.

## Quick Start

### Encoding an image (carpet mode — default)

1. Drop a PNG into `<gamedir>/loominary_data/`.
2. Run `/loominary import <filename>`. Tab-completion works.
3. Loominary tells you how many carpet rows and compressed bytes, and auto-saves the state to `loominary_saves/`. When you're ready to build, run `/loominary export` to write the Litematica schematic. A staircase schematic is automatically chosen if the tile uses the shade channel.
4. Optionally run `/loominary preview` while looking at any framed map to see what your image will look like before committing.
5. Make sure you have enough unnamed banners (any color) and empty bundles in your inventory for the LC/LS manifest banner and any overflow banners. You need 1 XP level per banner.
6. Walk to an anvil, open it, and let Loominary rename the manifest banner (and any overflow banners) automatically.
7. Place the carpet schematic using Litematica — flat carpet platform, or staircase if the schematic uses the shade channel. The terrain one block north of the schematic's north edge must be at the same y-level as the schematic origin.
8. Place the manifest and overflow banners nearby.
9. Hold the map you want to encode onto. Run `/loominary click`, then walk near your placed banners — the mod right-clicks each one automatically. Or right-click them manually.
10. Place the map in an item frame. Anyone with Loominary installed and within 32 blocks will see your image.

**Legacy: banner-only mode** — add `banners` to use the original encoding on any server where carpet placement isn't an option:
```
/loominary import <filename> banners
```
This uses up to 63 named CJK-encoded banners and 5,292 bytes of capacity.

### Encoding an animated GIF

```
/loominary import animation.gif
```

Loominary reads each GIF frame, coalesces partial-region updates, quantizes all frames against a shared palette, and encodes them into a single compressed payload. The decoder cycles frames in-world at the original GIF delays. All frames must compress within the tile's capacity — simpler GIFs with fewer distinct colors compress much smaller.

To thin out a GIF that's too large:
- `/loominary stride <n>` — keep every Nth frame (2 = half frame count)
- `/loominary skip <n>` — drop every Nth frame (2 = drop half)

### Editing a map in-world

1. Look at a framed map.
2. Run `/loominary edit` (or press the bound hotkey).
3. The editor opens with the map's pixel data as a 128×128 canvas.
4. See [EDITOR.md](EDITOR.md) for a full guide to all tools and controls.

### Stealing existing map art

1. Look at the framed map.
2. Run `/loominary import steal` (carpet mode) or `/loominary import steal banners`.
3. The map's data is captured as a tile in your batch.
4. Follow steps 6–10 above to replicate it.

You can steal multiple maps in sequence — each one becomes its own tile.

### Multi-tile murals

For larger images, split across a grid:

```
/loominary import landscape.png 4 2
```

This produces 8 tiles arranged in a 4-column, 2-row grid. Each tile is its own 128×128 map. Work through them with `/loominary tile next`. Place the maps in a 4×2 wall of item frames, then run `/loominary preview` while looking at any one of them — it discovers the full grid automatically and paints all tiles.

### Setting a title

```
/loominary title My Map Art
```

The title is auto-derived from the filename on each import (e.g. `landscape.png` → `landscape`). Setting a title explicitly overrides this and immediately re-encodes all tiles in the current batch with the new title embedded. For carpet tiles run `/loominary export` after changing the title to get an updated schematic. Clear it with `/loominary title` (no argument).

## Commands

All functionality is under a single `/loominary` command. Type `/loominary` and tab through subcommands.

### Importing payloads

- `/loominary import <filename>` — Import an image from `loominary_data/` using carpet encoding (default). State is auto-saved to `loominary_saves/` on import.
- `/loominary import <filename> banners` — Import using legacy banner-only encoding.
- `/loominary import <filename> [cols rows] [allshades] [dither] [linked]` — Import with grid and/or options. Add `linked` for shared-dictionary multi-tile animated GIFs (FLAG_LINKED).
- `/loominary import steal` — Append the framed map at your crosshair as a new carpet tile.
- `/loominary import steal banners` — Same, using legacy banner encoding.

### Inspecting state

- `/loominary` — Equivalent to `/loominary status`.
- `/loominary status` — Shows file, grid size, title, and per-tile progress. Carpet tiles show channel info (carpet rows, shade bytes if any).
- `/loominary palette` — Shows distinct colors (across all frames), budget, and a frequency histogram with adaptive sigmoid-based bucket boundaries.
- `/loominary palette all` — Same analysis across every tile in the batch combined.

### Tile navigation

- `/loominary tile <n>` — Switch to tile `n`.
- `/loominary tile next` — Switch to the next incomplete tile.
- `/loominary tile prev` — Switch to the previous tile.
- `/loominary tile pos` — Print the grid coordinates of the active tile.
- `/loominary seek <n>` — Set the chunk index of the active tile (for resuming partway through).

### GIF frame operations

- `/loominary stride <n>` — Keep only every Nth frame of all animated tiles (e.g. `stride 2` halves the frame count).
- `/loominary skip <n>` — Drop every Nth frame of all animated tiles (e.g. `skip 3` removes frames 3, 6, 9, …).

### Map manipulation

- `/loominary preview` — Paint the active tile (or all tiles for a multi-tile batch) onto the framed map(s) at your crosshair. Also triggers animation playback for animated tiles.
- `/loominary revert` — Restore the previewed map at your crosshair to its original colors.
- `/loominary edit` — Open the in-world pixel editor for the active tile. For animated tiles, loads all frames.

### Auto-clicking banners

- `/loominary click` — Toggle auto-right-click mode. Hold your map, walk near your placed LC/LS/overflow banners, and Loominary right-clicks each unregistered one every 5 ticks. Shows remaining count in the action bar; wire-box markers appear above each banner. Stops automatically when all banners are registered.
- `/loominary click stop` — Stop auto-clicking.

### Metadata

- `/loominary title <text>` — Set the title. Immediately re-encodes all tiles in the current batch with the new title. Warns if carpet schematics need re-export.
- `/loominary title` — Clear the title (reverts to auto-derive from filename on next import).

### Save / load state

- `/loominary save` — Auto-save current batch to `loominary_saves/<stem>_NNN.json` (monotonic counter per source filename).
- `/loominary save <name>` — Save with an explicit name.
- `/loominary load <name>` — Load a named save, replacing the current state. Tab-completes existing saves.

### Recovery

- `/loominary resalt` — Re-encode the active tile with a random nonce, producing new chunk names for the same image. Works for both carpet and banner tiles. Use this when the anvil handler shows "Stuck — run /loominary resalt."

### Quality and dithering

- `/loominary dither` — Re-encode the active tile from the source image using adaptive Floyd-Steinberg dithering.
- `/loominary dither all` — Re-encode every tile with seamless cross-tile dithering.
- `/loominary dither [all] colors <n>` — Pre-select `n` colours globally before dithering (1–248).

### Filters

Filters apply **in-place** to the current tile state — they work even on stolen tiles or frames that have been thinned with `skip`/`stride`, and they don't reload from the source file.

- `/loominary filter smooth [all] [radius <r>]` — Gaussian blur (default radius 1.5). Reduces high-frequency noise; effective before color reduction.
- `/loominary filter median [all] [radius <r>]` — Edge-preserving median filter (default radius 1). Removes salt-and-pepper noise without blurring edges.
- `/loominary filter sharpen [all] [amount <a>]` — Unsharp mask (default amount 0.8). Clarifies edges on clean or previously blurred images.
- `/loominary filter posterize [all] <levels>` — Reduces each color channel to `levels` discrete tones (2–16), creating large flat-color bands that compress well.

All filter commands re-quantize pixel colors back to the existing tile palette after filtering, so banner/carpet count does not increase.

### Palette reduction

- `/loominary palette` — Shows distinct color count (across all frames), budget, and a sigmoid-adaptive rarity histogram.
- `/loominary palette all` — Same analysis across all tiles combined.
- `/loominary reduce` — Reduce the active tile to fit within capacity.
- `/loominary reduce <n>` — Reduce the active tile to at most `n` banners (1–63). Always uses rarest-first for predictable compression.
- `/loominary reduce colors <n>` — Reduce the active tile to at most `n` distinct colors (1–248). Uses the active strategy.
- `/loominary reduce all` — Apply reduction to every tile in the batch.
- `/loominary reduce all <n>` — Apply banner-count reduction to every tile with target `n`.
- `/loominary reduce all colors <n>` — Apply color-count reduction to every tile.
- `/loominary reduce strategy <rarest|closest|weighted>` — Set the strategy used by color-count reduction:
  - `rarest` (default) — removes least-frequent colors first; predictable, good for cleaning up isolated accent pixels
  - `closest` — merges the globally closest color pair each step; targets clusters of similar-looking colors
  - `weighted` — scores pairs by `dist²/(freqA+freqB)`; large clusters of similar colors are eliminated first, good for noisy compressed GIFs
- `/loominary reduce undo` — Restore the active tile to its pre-reduction state.
- `/loominary reduce undo all` — Restore every tile to its pre-reduction state.

### Export

- `/loominary export` — Write a Litematica `.litematic` for **every tile** in the batch. Each tile is written as a separate file (`<name>_tile0.litematic`, `_tile1.litematic`, …; single-tile batches drop the suffix). Carpet tiles get flat or staircase schematics; banner tiles get a banner-layout schematic.
- `/loominary export <name>` — Write with a custom base filename.
- `/loominary export image` — Export the active tile as a PNG (static tiles) or looping animated GIF (animated tiles) to `loominary_exports/`. Pixel colors are rendered from the map palette exactly — no re-quantization.

### Cleanup

- `/loominary clear` — Clear in-memory state and delete the saved state file.
- `/loominary clear memory` — Clear only in-memory state.
- `/loominary clear disk` — Delete only the saved state file.
- `/loominary link` — Convert current batch to linked (FLAG_LINKED) shared-dictionary mode.
- `/loominary unlink` — Convert back to regular mode (allowed even if over per-tile budget).

## Hotkeys

Five actions can be bound in `Options → Controls → Key Binds → Loominary`:

- **Steal map at crosshair** — equivalent to `/loominary import steal`
- **Preview active tile on crosshair map** — equivalent to `/loominary preview`
- **Revert previewed map at crosshair** — equivalent to `/loominary revert`
- **Switch to next incomplete tile** — equivalent to `/loominary tile next`
- **Show batch status** — equivalent to `/loominary status`

All are unbound by default.

## How It Works

Loominary exploits a chain of Minecraft mechanics that aren't normally connected:

**Carpet blocks encode data visually.** Minecraft has 16 carpet colors. Loominary maps them to nibble values (0–15) and writes pairs of nibbles into consecutive map pixels. A 128×128 map has 16,384 pixels — enough for 8,192 bytes of compressed data in the carpet channel alone. Carpets on a flat surface render at shade NORMAL (map byte = `base_id × 4 + 1`), giving a predictable and reversible encoding.

**Carpet height encodes a second channel (LS format).** When the flat carpet channel plus overflow banners can't hold the payload, Loominary also encodes data in the vertical arrangement of carpets. Heights {0, 1, 2} relative to the map's northward neighbor determine the shade byte (LOW, NORMAL, HIGH). Groups of 4 rows are encoded as one of 16 balanced height sequences (each starts and ends at height 1 so the pattern tiles correctly), giving 4 bits per group. The 128-column map has 31 four-row groups plus a 3-row tail, yielding 2,016 additional bytes. Tiles using this channel write an LS manifest banner instead of LC, and the schematic is a staircase rather than a flat platform.

**An LC/LS manifest banner triggers the decoder.** After placing the carpet, the user clicks a single named banner with the map. The name encodes the total compressed size and, for LS banners, the shade-channel byte count. The decoder uses this to know how many nibbles to read from the color array and how many shade bytes to extract.

**Overflow banners carry the remainder using a CJK alphabet.** If the compressed payload exceeds the carpet + shade channels, the remainder is CJK-encoded and split into up to 62 named overflow banners. Each banner carries a 2-character hex index followed by 48 CJK characters from the range U+4E00–U+8DFF (14 bits per character, 84 bytes per banner). Total overflow capacity: 62 × 84 − 2 = 5,206 bytes (the −2 is the codec's internal length header). Grand total per tile: **15,414 compressed bytes**.

**The CJK alphabet was validated on 2b2t.** All code points in the U+4E00–U+9000 range were tested via automated item-rename probes and passed through 2b2t's filter unmodified. CJK Unified Ideographs have no canonical decomposition, so the server's NFC normalization pass is a no-op. The 14-bit alphabet gives 2.33× the payload capacity of base64 in the same 48-character banner slot.

**Banner names store overflow data.** The anvil lets you rename a banner up to 50 characters. Loominary renames each overflow banner with a 2-character hex index followed by 48 CJK payload characters. The encoded bytes are preceded by a 2-byte big-endian length header so the decoder knows the exact compressed size regardless of how many trailing zero-padding bits are in the last character.

**Maps store banner-marker names.** When you right-click a banner with a map, the server records the banner's custom name as a `MapDecoration` in the map's NBT. This decoration data syncs to clients.

**The client renders maps from a `byte[16384]` color array.** Loominary intercepts maps whose banner markers include an LC/LS decoration, reads the carpet nibbles and shade bytes from the map's existing color array, reassembles any overflow chunks in order, CJK-decodes and zstd-decompresses the combined payload, and overwrites the client's local color array. The server is unaware.

**The encoding works in map-color space.** The image is quantized to Minecraft's map palette using Oklab perceptual distance, then the resulting `byte[16384]` is compressed. Spatial coherence in the quantized output makes zstd very effective — most images compress to 1,500–6,000 bytes.

**Animated payloads concatenate frames.** For GIFs, all frames are stored back-to-back in the payload after the manifest header. Zstd exploits inter-frame repetition naturally. The decoder advances frames on a wall-clock timer, culls by distance, and syncs multi-tile murals so the full grid never shows mixed frames.

**Payloads carry a versioned manifest.** Every payload begins with a small binary header recording the format version, grid position, author username, title, and a CRC32 of the image data. Animated payloads additionally carry frame count, loop count, and per-frame delays. Old decoders find map colors at the `header_size` skip-pointer and render frame 0 as a static image.

**Decorations are suppressed.** Once Loominary identifies a map as one of its own, it clears the decorations list in the client-side `MapState`, so the banner pin icons don't clutter the image.

## FAQ

### Will this get me banned?

Loominary renames banners (vanilla feature), right-clicks them with maps (vanilla feature), and reads map data the server sends (vanilla feature). Placing a carpet schematic is normal block placement. The image-rendering step happens entirely client-side. On strict anti-cheat servers, watch for any warnings on first use.

### Other players don't see my image.

Expected. Loominary is client-side only. Anyone who wants to see the encoded image needs Loominary installed. Without the mod they see a normal map with any banner pins.

### Can I share an encoded map?

Yes. The data lives in the map's NBT in the world save, so it persists and anyone with Loominary within 32 blocks of the framed map will see the image.

### My image looks fuzzy or wrong.

Minecraft's map palette has ~62 base colors with up to 4 brightness shades. Photographs get color-quantized. Try:

- Simpler images with bold colors and clear shapes
- `/loominary palette` to see which colors are rare
- The `allshades` option for ~62 extra colors (at the cost of buildability)
- `/loominary dither` for smoother gradients

### I keep getting "OVER BUDGET."

Use `/loominary reduce` to merge the rarest colors into their nearest neighbors. You can also target a specific ceiling: `/loominary reduce 50`. Undo with `/loominary reduce undo`. Carpet mode has ~15,414 bytes of capacity; banner-only mode (legacy) has ~5,292 bytes at the 63-banner limit.

### What is the "legal palette" vs "all shades"?

Minecraft's map format encodes 64 base colors × 4 shade levels = 256 byte values. Only ~62 base colors are populated, and only 3 shades occur naturally (the 4th exists in the format but no real block produces it). The default mode restricts to those ~186 reachable colors. The `allshades` option adds the ~62 unreachable 4th-shade entries for slightly better fidelity at the cost of "this could never be built as real blocks."

### Does this work on servers / Realms / single-player?

Yes to all three. Loominary is fully client-side. Carpet mode works on any server where you can place blocks. Banner-only mode works everywhere.

### What does the title/author metadata do?

Every encoded payload includes the encoder's username and title in a small binary manifest. The title is auto-derived from the filename on import; you can override it with `/loominary title`. When another Loominary user decodes the map, this information is logged.

### Why does carpet mode use an LC/LS banner at all?

The manifest banner is the decoder trigger — without it, Loominary has no way to distinguish a carpet-encoded map from normal terrain. The banner name also carries the total compressed size and (for LS tiles) the shade-channel byte count, plus the first bytes of overflow data. For most images the payload fits in the carpet channel and the manifest banner is the only banner needed.

### Why 63 banners?

The hard limit of 63 banners per map was observed on 2b2t. Vanilla servers nominally allow 255, but the mod targets the more restrictive limit by default. Each overflow banner now carries 84 bytes (CJK encoding), so 63 banners at 84 bytes each = 5,292 bytes — 2.33× the 2,268 bytes that base64 delivered at the same banner count.

### What happens if I run out of XP, banners, or bundles mid-batch?

The anvil handler pauses cleanly, logs what's missing, and resumes automatically when you restock.

### Why is my carpet tile producing a staircase schematic?

Your image compressed to more than 10,240 bytes (carpet + overflow limit), so Loominary activated the shade channel to carry extra data. The staircase encodes 2,016 additional bytes via carpet height variation. Place it the same way as a flat schematic — the terrain one block north of the schematic's north edge must be at the same y-level as the schematic origin so row 0 renders at shade NORMAL.

### How big is the state file?

Tens of kilobytes for a typical batch; low single-digit megabytes for many large tiles. It lives at `<gamedir>/config/loominary_state.json` as readable JSON.

### The anvil handler says "Stuck — run /loominary resalt."

The server permanently rejected a specific banner name. Running `/loominary resalt` re-encodes the active tile with a random nonce — the image is identical but all chunk names change. Works for both carpet and banner tiles. Discard any already-renamed banners for this tile before continuing.

### How do I uninstall?

Remove the jar. Encoded maps already in the world remain visible to other Loominary users, but you'll see them as plain maps.

## Credits

Built for Minecraft 1.21.4. Uses [zstd-jni](https://github.com/luben/zstd-jni) for compression.

Vibe-coded almost entirely by Claude Sonnet 4.6.

## License

MIT
