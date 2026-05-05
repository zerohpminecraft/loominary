# Loominary

A Fabric mod for Minecraft 1.21.4 that encodes images as map art and plays them back client-side, with no server modification required.

Loominary encodes image data into colored carpet blocks and named banners, then renders it client-side as custom map art. Place the carpet schematic, click the banner marker(s) with a map, and anyone running Loominary sees your image painted on the map — including animated GIFs. Without the mod, viewers see a normal map.

This works on **any vanilla server**. The primary encoding mode uses carpet block colors as a data channel (8,192 bytes) supplemented by up to 62 named overflow banners, with a single LC manifest banner as the decoder trigger. This design was built for servers like 2b2t that impose strict limits on map banner markers (63 per map) — carpet mode delivers 10,457 compressed bytes within that limit, vs. 2,268 bytes that banner-only encoding could achieve at 63 banners.

## Features

- **Carpet channel encoding** (default): 16 carpet colors encode 4-bit nibbles across 128×128 map positions — 8,192 bytes primary channel, up to 2,265 bytes overflow via named banners. Total: 10,457 compressed bytes per tile
- **Encode any image** into carpet + banner data (PNG, JPEG, GIF, BMP — anything `ImageIO` can read)
- **Animated GIF import**: encode a GIF as multiple frames in a single payload; the decoder cycles frames on a tick timer with distance culling and multi-tile sync
- **In-world pixel editor** (`/loominary edit`): live 128×128 canvas with paint, undo/redo, palette panel, eyedropper, brush size, fill bucket, rectangle selection, lasso, magic wand, per-region re-quantize from source, dither toggle, and a dither-strength brush with heat-map overlay
- **Multi-tile murals**: split a large image across an N×M grid of maps for wall-sized art
- **Steal existing map art** as a Loominary payload by looking at any framed map
- **Perceptual color matching** using Oklab color space — better-looking results than RGB Euclidean distance, especially on gradients and skin tones
- **Progressive palette reduction** automatically simplifies images that won't fit within capacity, with full preview and undo
- **Auto-right-click**: hold your map and walk near your placed banners — `/loominary click` handles every right-click automatically with live status and visual markers
- **Client-side rendering** with marker suppression — the banner pins disappear, leaving a clean image
- **Workflow automation** at the anvil: stack-aware banner extraction, automatic renaming, automatic bundle storage
- **Stuck-chunk recovery**: if the server permanently rejects a banner name the handler halts cleanly; `/loominary resalt` re-encodes the tile with a random nonce — same image, new chunk names, works for both carpet and banner tiles
- **Two-pass dithering with adaptive edge detection**: Floyd-Steinberg error diffusion runs after palette pre-selection, with per-pixel strength controlled by an image-relative Otsu threshold — smooth gradients dither fully, sharp edges stay crisp, solid fills stay clean
- **Multi-tile grid consistency**: dithering operates on the full grid image before splitting into tiles, eliminating colour and texture discontinuities at seams
- **Color palette histogram**: `/loominary palette` shows a rarity distribution histogram and cumulative removal-cost table to guide reduction decisions
- **Flexible palette reduction**: reduce by banner count or distinct colour count, on the active tile or all tiles at once
- **Litematica schematic export**: carpet tiles auto-export a schematic on import (written directly to Litematica's `schematics/` folder); banner tiles export on demand with `/loominary export`
- **Grid-aware preview**: `/loominary preview` discovers the full wall of framed maps from any frame and paints all tiles at once
- **Embedded metadata**: every payload records the image title (auto-derived from filename), author username, grid position, and a CRC32 integrity check; animated payloads carry frame count, loop count, and per-frame delays
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

1. Download `loominary-1.3.0.jar` from the [releases page](https://github.com/zerohpminecraft/loominary/releases)
2. Drop it into your `mods/` folder alongside Fabric API
3. Launch the game

You'll see `[Loominary] Client-side mod initialized successfully!` in the log.

## Quick Start

### Encoding an image (carpet mode — default)

1. Drop a PNG into `<gamedir>/loominary_data/`.
2. Run `/loominary import <filename>`. Tab-completion works.
3. Loominary tells you how many carpet rows and compressed bytes, and auto-exports the carpet schematic to Litematica's `schematics/` folder.
4. Optionally run `/loominary preview` while looking at any framed map to see what your image will look like before committing.
5. Make sure you have enough unnamed banners (any color) and empty bundles in your inventory for the LC manifest banner and any overflow banners. You need 1 XP level per banner.
6. Walk to an anvil, open it, and let Loominary rename the LC banner (and any overflow banners) automatically.
7. Place the carpet schematic using Litematica — a flat platform of colored carpet blocks on any flat surface, extending slightly north of the map boundary so all rows render at shade NORMAL.
8. Place the LC banner (and any overflow banners) nearby.
9. Hold the map you want to encode onto. Run `/loominary click`, then walk near your placed banners — the mod right-clicks each one automatically. Or right-click them manually.
10. Place the map in an item frame. Anyone with Loominary installed and within 32 blocks will see your image.

**Legacy: banner-only mode** — add `banners` to use the original encoding on any server where carpet placement isn't an option:
```
/loominary import <filename> banners
```
This uses up to 63 named banners and ~2,268 bytes of capacity.

### Encoding an animated GIF

```
/loominary import animation.gif
```

Loominary reads each GIF frame, coalesces partial-region updates, quantizes all frames against a shared palette, and encodes them into a single compressed payload. The decoder cycles frames in-world at the original GIF delays. Capacity: all frames must compress to ≤10,457 bytes total — simpler GIFs with fewer distinct colors compress much smaller.

### Editing a map in-world

1. Look at a framed map.
2. Run `/loominary edit` (or press the bound hotkey).
3. The editor opens with the map's pixel data as a 128×128 canvas.
4. Tools: left-click paints, right-click eyedropper, `R` re-quantizes selection from source, `D` toggles dither, `T` dither-strength brush, `L` lasso, `W` magic wand, `E` eyedropper mode.
5. For animated tiles: `Ctrl+[` / `Ctrl+]` navigate frames.
6. On close, changes are saved back into the tile's chunks.

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

The title is auto-derived from the filename on each import (e.g. `landscape.png` → `landscape`). Setting a title explicitly overrides this and immediately re-encodes all tiles in the current batch with the new title embedded. For carpet tiles you'll need to re-export the schematic after changing the title. Clear it with `/loominary title` (no argument).

## Commands

All functionality is under a single `/loominary` command. Type `/loominary` and tab through subcommands.

### Importing payloads

- `/loominary import <filename>` — Import an image from `loominary_data/` using carpet encoding (default). Carpet schematic auto-exported on import.
- `/loominary import <filename> banners` — Import using legacy banner-only encoding.
- `/loominary import <filename> [cols rows] [allshades] [dither]` — Import with grid and/or options. All work in both carpet and banner modes.
- `/loominary import steal` — Append the framed map at your crosshair as a new carpet tile.
- `/loominary import steal banners` — Same, using legacy banner encoding.

### Inspecting state

- `/loominary` — Equivalent to `/loominary status`.
- `/loominary status` — Shows file, grid size, title, and per-tile progress. Carpet tiles show `[carpet]` tag.
- `/loominary palette` — Shows distinct colors, banner count, and the rarest colors in the active tile.

### Tile navigation

- `/loominary tile <n>` — Switch to tile `n`.
- `/loominary tile next` — Switch to the next incomplete tile.
- `/loominary seek <n>` — Set the chunk index of the active tile (for resuming partway through).

### Map manipulation

- `/loominary preview` — Paint the active tile (or all tiles for a multi-tile batch) onto the framed map(s) at your crosshair. Also triggers animation playback for animated tiles.
- `/loominary revert` — Restore the previewed map at your crosshair to its original colors.
- `/loominary edit` — Open the in-world pixel editor for the active tile. For animated tiles, loads all frames.

### Auto-clicking banners

- `/loominary click` — Toggle auto-right-click mode. Hold your map, walk near your placed LC/overflow banners, and Loominary right-clicks each unregistered one every 5 ticks. Shows remaining count in the action bar; wire-box markers appear above each banner. Stops automatically when all banners are registered.
- `/loominary click stop` — Stop auto-clicking.

### Metadata

- `/loominary title <text>` — Set the title. Immediately re-encodes all tiles in the current batch with the new title. Warns if carpet schematics need re-export.
- `/loominary title` — Clear the title (reverts to auto-derive from filename on next import).

### Recovery

- `/loominary resalt` — Re-encode the active tile with a random nonce, producing new chunk names for the same image. Works for both carpet and banner tiles. Use this when the anvil handler shows "Stuck — run /loominary resalt."

### Quality and dithering

- `/loominary dither` — Re-encode the active tile from the source image using adaptive Floyd-Steinberg dithering.
- `/loominary dither all` — Re-encode every tile with seamless cross-tile dithering.
- `/loominary dither [all] colors <n>` — Pre-select `n` colours globally before dithering (1–248).

### Palette reduction

- `/loominary palette` — Shows color stats, rarity histogram, and cumulative removal-cost table.
- `/loominary reduce` — Reduce the active tile to fit within capacity.
- `/loominary reduce <n>` — Reduce the active tile to at most `n` banners (1–63 for carpet, 1–63 for banner mode).
- `/loominary reduce colors <n>` — Reduce the active tile to at most `n` distinct colors (1–248).
- `/loominary reduce all` — Apply reduction to every tile in the batch.
- `/loominary reduce all <n>` — Apply banner-count reduction to every tile with target `n`.
- `/loominary reduce all colors <n>` — Apply color-count reduction to every tile.
- `/loominary reduce undo` — Restore the active tile to its pre-reduction state.

### Export

- `/loominary export` — Carpet tiles: re-export the carpet schematic (useful after `/loominary title`). Banner tiles: write a Litematica `.litematic` schematic. Both go to Litematica's `schematics/` folder.
- `/loominary export <name>` — Write with a custom filename.

### Cleanup

- `/loominary clear` — Clear in-memory state and delete the saved state file.
- `/loominary clear memory` — Clear only in-memory state.
- `/loominary clear disk` — Delete only the saved state file.

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

**Carpet blocks encode data visually.** Minecraft has 16 carpet colors. Loominary maps them to nibble values (0–15) and writes pairs of nibbles into consecutive map pixels. A 128×128 map has 16,384 pixels — enough for 8,192 bytes of compressed data in the carpet channel alone. The key constraint is shade: carpets on a flat surface at the same elevation as the row to their north render at shade NORMAL (map byte = `base_id × 4 + 1`), giving a predictable and reversible encoding.

**An LC manifest banner triggers the decoder.** After placing the carpet, the user clicks a single named LC banner with the map. The banner name encodes the total compressed size (`LC<NNNN>`) and optionally the first 44 base64 characters of overflow data. The decoder sees the LC decoration and knows to read the map's color array as carpet data rather than terrain.

**Overflow banners carry the rest.** If the compressed payload exceeds 8,192 bytes, the remainder is split into up to 62 hex-indexed overflow banners (`00`–`3D`), each carrying 48 base64 characters (36 bytes). Combined with the LC banner's 33-byte payload, total overflow capacity is 2,265 bytes. Grand total: 10,457 compressed bytes per tile.

**Banner names store overflow data.** The anvil lets you rename a banner up to 50 characters, and that custom name persists in NBT. Loominary renames each overflow banner with a 2-character hex index followed by up to 48 characters of base64-encoded overflow data.

**Maps store banner-marker names.** When you right-click a banner with a map, the server records the banner's position, dye color, and custom name as a `MapDecoration` in the map's NBT. This decoration data syncs to clients.

**The client renders maps from a `byte[16384]` color array.** Loominary intercepts maps whose banner markers include an LC decoration, reads the carpet nibbles from the map's existing color array, reassembles any overflow chunks in order, base64-decodes and zstd-decompresses the combined payload, and overwrites the client's local color array. The server is unaware.

**The encoding works in map-color space.** The image is quantized to Minecraft's map palette using Oklab perceptual distance, then the resulting `byte[16384]` is compressed. Spatial coherence in the quantized output makes zstd very effective — most images compress to 1,500–6,000 bytes. The 10,457-byte ceiling is almost never reached by typical mapart.

**Animated payloads concatenate frames.** For GIFs, all frames are stored back-to-back in the payload (`frame_count × 16,384` bytes) after the manifest header. Zstd exploits inter-frame repetition naturally. The decoder advances frames on a tick timer, culls distance, and syncs multi-tile murals so the full grid never shows mixed frames.

**Payloads carry a versioned manifest.** Every payload begins with a small binary header recording the format version, grid position, author username, title, and a CRC32 of the image data. Animated payloads (manifest v3) additionally carry frame count, loop count, and per-frame delays. Old decoders find map colors at the `header_size` skip-pointer and render frame 0 as a static image.

**Decorations are suppressed.** Once Loominary identifies a map as one of its own, it clears the decorations list in the client-side `MapState`, so the banner pin icons don't clutter the image.

## FAQ

### Will this get me banned?

Loominary renames banners (vanilla feature), right-clicks them with maps (vanilla feature), and reads map data the server sends (vanilla feature). Placing a carpet schematic is normal block placement. The image-rendering step happens entirely client-side. The anvil handler clicks quickly during renaming, and the auto-click feature right-clicks banners at a measured pace. On strict anti-cheat servers, watch for any warnings on first use.

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

Use `/loominary reduce` to merge the rarest colors into their nearest neighbors. You can also target a specific ceiling: `/loominary reduce 50`. Undo with `/loominary reduce undo`. Carpet mode has ~10,457 bytes of capacity; banner-only mode (legacy) is ~2,268 bytes at the 63-banner limit.

### What is the "legal palette" vs "all shades"?

Minecraft's map format encodes 64 base colors × 4 shade levels = 256 byte values. Only ~62 base colors are populated, and only 3 shades occur naturally (the 4th exists in the format but no real block produces it). The default mode restricts to those ~186 reachable colors. The `allshades` option adds the ~62 unreachable 4th-shade entries for slightly better fidelity at the cost of "this could never be built as real blocks."

### Does this work on servers / Realms / single-player?

Yes to all three. Loominary is fully client-side. Carpet mode works on any server where you can place blocks. Banner-only mode works everywhere.

### What does the title/author metadata do?

Every encoded payload includes the encoder's username and title in a small binary manifest. The title is auto-derived from the filename on import; you can override it with `/loominary title`. When another Loominary user decodes the map, this information is logged.

### Why does carpet mode use an LC banner at all?

The LC banner is the decoder trigger — without it, Loominary has no way to distinguish a carpet-encoded map from normal terrain. The banner name also carries the total compressed size (so the decoder knows how many nibbles to read from the color array) and the first 33 bytes of overflow data. For most images the payload fits in the carpet channel and the LC banner is the only banner needed.

### Why 63 banners?

The hard limit of 63 banners per map was observed on 2b2t. Vanilla servers nominally allow 255 (two hex digits: `00`–`ff`), but the mod targets the more restrictive limit by default. The carpet channel was designed specifically to maximize payload within that constraint: instead of 63 × 36 bytes (≈2,268 bytes), carpet mode delivers 8,192 bytes from the carpet blocks plus 2,265 bytes of banner overflow = 10,457 bytes total.

### What happens if I run out of XP, banners, or bundles mid-batch?

The anvil handler pauses cleanly, logs what's missing, and resumes automatically when you restock.

### How big is the state file?

Tens of kilobytes for a typical batch; low single-digit megabytes for many large tiles. It lives at `<gamedir>/config/loominary_state.json` as readable JSON.

### Will Loominary update for newer Minecraft versions?

The mod targets specific Minecraft internals. Each major Minecraft version requires a port. 1.21.4 is the current target.

### The anvil handler says "Stuck — run /loominary resalt."

The server permanently rejected a specific banner name. Running `/loominary resalt` re-encodes the active tile with a random nonce — the image is identical but all chunk names change. Works for both carpet and banner tiles. Discard any already-renamed banners for this tile before continuing.

### How do I uninstall?

Remove the jar. Encoded maps already in the world remain visible to other Loominary users, but you'll see them as plain maps.

## Credits

Built for Minecraft 1.21.4. Uses [zstd-jni](https://github.com/luben/zstd-jni) for compression.

Vibe-coded almost entirely by Claude Sonnet 4.6.

## License

MIT
