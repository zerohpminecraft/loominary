# Loominary

A Fabric mod for Minecraft 1.21.4 that encodes images as banner-named map markers, then renders them client-side as custom map art with no server modification required.

Loominary uses Minecraft's banner naming and map marker systems as a data side channel. You give it an image; it encodes the image's pixel data into a sequence of named banners; you place those banners and right-click each one with a map; anyone running Loominary then sees your image painted on the map. Without Loominary, viewers see only a normal map with banner markers.

This works on **any vanilla server** — the data lives in legitimate banner and map NBT, so the server happily stores and synchronizes it without realizing what it represents.

## Features

- **Encode any image** into a sequence of named banners (PNG, JPEG, GIF, BMP — anything `ImageIO` can read)
- **Multi-tile murals**: split a large image across an N×M grid of maps for wall-sized art
- **Steal existing map art** as a Loominary payload by looking at any framed map
- **Perceptual color matching** using Oklab color space — better-looking results than RGB Euclidean distance, especially on gradients and skin tones
- **Progressive palette reduction** automatically simplifies images that won't fit in the 255-banner-per-map limit, with full preview and undo
- **Auto-right-click**: hold your map and walk near your placed banners — `/loominary click` handles every right-click automatically with live status and visual markers
- **Client-side rendering** with marker suppression — the banner pins disappear, leaving a clean image
- **Workflow automation** at the anvil: stack-aware banner extraction, automatic renaming, automatic bundle storage
- **Litematica schematic export** for placement guidance
- **Grid-aware preview**: `/loominary preview` discovers the full wall of framed maps from any frame and paints all tiles at once
- **Embedded metadata**: every payload records the image title, author username, grid position, and a CRC32 integrity check
- **Persistent state** survives game restarts; pick up any unfinished batch
- **Configurable hotkeys** for the most-used actions
- **Crosshair-targeted commands** — preview, revert, and steal use whatever framed map you're looking at

## Requirements

- Minecraft **1.21.4** with Fabric Loader **0.19.2** or newer
- Fabric API
- Java 21
- Client-side only — no server installation required

## Installation

1. Download `loominary-1.1.1.jar` from the [releases page](https://github.com/zerohpminecraft/loominary/releases)
2. Drop it into your `mods/` folder alongside Fabric API
3. Launch the game

You'll see `[Loominary] Client-side mod initialized successfully!` in the log.

## Quick Start

### Encoding an image

1. Drop a PNG into `<gamedir>/loominary_data/`.
2. Run `/loominary import <filename>`. Tab-completion works.
3. Loominary tells you how many banners and bundles you'll need.
4. Optionally run `/loominary preview` while looking at any framed map to see what your image will look like before committing.
5. Make sure you have enough unnamed banners (any color) and empty bundles in your inventory.
6. Walk to an anvil, open it, and let Loominary work — it places banners, renames them with payload chunks, and stores the renamed banners in your bundles automatically. You need 1 XP level per banner.
7. When all banners are renamed, place them anywhere in the world using the exported Litematica schematic as a guide.
8. Hold the map you want to encode onto. Run `/loominary click`, then walk near your placed banners — the mod right-clicks each one automatically. Or right-click them manually if you prefer.
9. Place the map in an item frame.
10. Anyone with Loominary installed and within 32 blocks of the framed map will now see your image instead of the map's terrain.

### Stealing existing map art

1. Look at the framed map
2. Run `/loominary import steal`
3. The map's data is captured as a tile in your batch
4. You can now replicate it elsewhere by following steps 6–10 above

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

The title is embedded in the payload manifest and displayed to other Loominary users when the map is decoded. Clear it with `/loominary title` (no argument). The title applies to the next import, not existing tiles.

## Commands

All functionality is under a single `/loominary` command. Type `/loominary` and tab through subcommands.

### Importing payloads

- `/loominary import <filename>` — Import an image from `loominary_data/`. Defaults to a single 128×128 tile.
- `/loominary import <filename> <cols> <rows>` — Split image into a `cols × rows` grid.
- `/loominary import <filename> [cols] [rows] allshades` — Use the full ~248-color palette including the unobtainable shade. Default is the ~186-color legal palette.
- `/loominary import steal` — Append the framed map at your crosshair as a new tile.

### Inspecting state

- `/loominary` — Equivalent to `/loominary status`.
- `/loominary status` — Shows file, grid size, title, and per-tile progress.
- `/loominary palette` — Shows distinct colors, banner count, and the rarest colors in the active tile.

### Tile navigation

- `/loominary tile <n>` — Switch to tile `n`.
- `/loominary tile next` — Switch to the next incomplete tile.
- `/loominary seek <n>` — Set the chunk index of the active tile (for resuming partway through).

### Map manipulation

- `/loominary preview` — Paint the active tile (or all tiles for a multi-tile batch) onto the framed map(s) at your crosshair. For multi-tile batches, Loominary discovers the full connected wall of frames and paints each tile in the correct position.
- `/loominary revert` — Restore the previewed map at your crosshair to its original colors.

### Auto-clicking banners

- `/loominary click` — Toggle auto-right-click mode. Hold your map, walk near your placed banners, and Loominary right-clicks each unregistered one every 5 ticks. Shows remaining count in the action bar; wire-box markers appear above each banner (yellow = click sent, green = server confirmed). Stops automatically when all banners are registered.
- `/loominary click stop` — Stop auto-clicking.

### Metadata

- `/loominary title <text>` — Set the title to embed in the next encode's manifest.
- `/loominary title` — Clear the title.

### Palette reduction

- `/loominary reduce` — Reduce the active tile to fit in 255 banners by merging the rarest colors into their visual neighbors.
- `/loominary reduce <target>` — Reduce to a specific banner count (1–255).
- `/loominary reduce undo` — Restore the active tile to its pre-reduction state.

### Export

- `/loominary export` — Write a Litematica `.litematic` schematic of the active tile's banner placement to `<gamedir>/loominary_exports/`.
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

**Banner names are arbitrary text.** The anvil lets you rename a banner up to 50 characters, and that custom name persists in NBT. Loominary renames each banner with a 2-character hex index followed by up to 48 characters of base64-encoded payload.

**Maps store banner-marker names.** When you right-click a banner with a map, the server records the banner's position, dye color, and custom name as a `MapDecoration` in the map's NBT. This decoration data syncs to clients.

**The client renders maps from a `byte[16384]` color array.** Loominary intercepts maps whose banner markers start with two hex digits, reassembles the chunks in order, base64-decodes and zstd-decompresses the result, and overwrites the client's local color array. The server is unaware.

**The encoding works in map-color space.** The image is quantized to Minecraft's map palette using Oklab perceptual distance (rather than RGB Euclidean), then the resulting `byte[16384]` is compressed. Spatial coherence in the quantized output makes zstd very effective — most images compress to 1,500–6,000 bytes. The maximum payload is 255 banners × 48 base64 chars ≈ 9,000 bytes of compressed data per map.

**Payloads carry a versioned manifest.** Every payload begins with a small binary header recording the format version, grid position (col, row, total cols/rows), author username, optional title, and a CRC32 of the image data. This lets the decoder display metadata and handle future format changes gracefully without breaking old payloads.

**Decorations are suppressed.** Once Loominary identifies a map as one of its own, it clears the decorations list in the client-side `MapState`, so the banner pin icons don't clutter the image.

## FAQ

### Will this get me banned?

Loominary renames banners (vanilla feature), right-clicks them with maps (vanilla feature), and reads map data the server sends (vanilla feature). The image-rendering step happens entirely client-side. The anvil handler clicks quickly during renaming, and the auto-click feature right-clicks banners at a measured pace. If you're on a strict anti-cheat server, pay attention to any warnings on your first use.

### Other players don't see my image.

Expected. Loominary is client-side only. Anyone who wants to see the encoded image needs Loominary installed. Without the mod they see a normal map with banner pins.

### Can I share an encoded map?

Yes. The data lives in the map's NBT in the world save, so it persists and anyone with Loominary within 32 blocks of the framed map will see the image.

### My image looks fuzzy or wrong.

Minecraft's map palette has ~62 base colors with up to 4 brightness shades. Photographs get color-quantized. Try:

- Simpler images with bold colors and clear shapes
- `/loominary palette` to see which colors are rare
- The `allshades` option for ~62 extra colors (at the cost of buildability)

### I keep getting "OVER BUDGET."

Use `/loominary reduce` to merge the rarest colors into their nearest neighbors. You can also target a specific ceiling: `/loominary reduce 200`. Undo with `/loominary reduce undo`. For pathological images (noise, fine gradients), there's a hard ceiling — 255 banners can hold ~9,000 bytes maximum.

### What is the "legal palette" vs "all shades"?

Minecraft's map format encodes 64 base colors × 4 shade levels = 256 byte values. Only ~62 base colors are populated, and only 3 shades occur naturally (the 4th exists in the format but no real block produces it). The default mode restricts to those ~186 reachable colors. The `allshades` option adds the ~62 unreachable 4th-shade entries for slightly better fidelity at the cost of "this could never be built as real blocks."

### Does this work on servers / Realms / single-player?

Yes to all three. Loominary is fully client-side.

### What does the title/author metadata do?

Every encoded payload includes the encoder's username and optional title in a small binary manifest. When another Loominary user decodes the map, this information is logged. Future versions may display it in-game. Set the title before importing with `/loominary title`.

### Why hex indices instead of decimal?

Two decimal digits covers 0–99, but a single map can hold up to 255 banner markers. Two hex digits covers 00–ff = 256 values, enough for all 255 payload chunks.

### What happens if I run out of XP, banners, or bundles mid-batch?

The anvil handler pauses cleanly, logs what's missing, and resumes automatically when you restock. No input needed.

### How big is the state file?

Tens of kilobytes for a typical batch; low single-digit megabytes for many large tiles. It lives at `<gamedir>/config/loominary_state.json` as readable JSON.

### Will Loominary update for newer Minecraft versions?

The mod targets specific Minecraft internals. Each major Minecraft version requires a port. 1.21.4 is the current target.

### How do I uninstall?

Remove the jar. Encoded maps already in the world remain visible to other Loominary users, but you'll see them as plain maps.

## Credits

Built for Minecraft 1.21.4. Uses [zstd-jni](https://github.com/luben/zstd-jni) for compression.

Vibe-coded almost entirely by Claude Sonnet 4.6.

## License

MIT
