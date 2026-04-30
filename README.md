# Loominary

A Fabric mod for Minecraft 1.21.4 that encodes images as banner-named map markers, then renders them client-side as custom map art with no server modification required.

Loominary uses Minecraft's banner naming and map marker systems as a data side channel. You give it an image; it encodes the image's pixel data into a sequence of named banners; you place those banners and right-click each one with a map; anyone running Loominary then sees your image painted on the map. Without Loominary, viewers see only a normal map with banner markers.

This works on **any vanilla server** — the data lives in legitimate banner and map NBT, so the server happily stores and synchronizes it without realizing what it represents.

## Features

- **Encode any image** into a sequence of named banners (PNG, JPEG, GIF, BMP — anything `ImageIO` can read)
- **Multi-tile murals**: split a large image across an N×M grid of maps for wall-sized art
- **Steal existing map art** as a Loominary payload by looking at any framed map
- **Progressive palette reduction** automatically simplifies images that won't fit in the 255-banner-per-map limit, with full preview and undo
- **Client-side rendering** with marker suppression — the banner pins disappear, leaving a clean image
- **Workflow automation** at the anvil: stack-aware banner extraction, automatic renaming, automatic bundle storage
- **Litematica schematic export** for placement guidance
- **Persistent state** survives game restarts; pick up any unfinished batch
- **Configurable hotkeys** for the most-used actions
- **Crosshair-targeted commands** — preview, revert, and steal use whatever framed map you're looking at

## Requirements

- Minecraft **1.21.4** with Fabric Loader **0.16.10** or newer
- Fabric API
- A modern Java 21 runtime
- The Fabric mod loader (Loominary is client-side only — you don't need to install it on a server)

## Installation

1. Download `loominary-1.0.0.jar` from the releases page
2. Drop it into your `mods/` folder alongside Fabric API
3. Launch the game

You'll see `[Loominary] Client-side mod initialized successfully!` in your log if everything loaded.

## Quick Start

### Encoding an image

1. Drop a PNG into `<gamedir>/loominary_data/`. 
2. Run `/loominary import <filename>`. Tab-completion works. See "Importing Payloads" below for more options.
3. Loominary tells you how many banners and bundles you'll need.
4. Optionally run `/loominary preview` while looking at any framed map to see what your image will look like before committing.
5. Make sure you have enough unnamed banners (any color) and empty bundles in your inventory.
6. Walk to an anvil, open it, and let Loominary work — it places banners, renames them with payload chunks, and stores the renamed banners in your bundles automatically. You'll need 1 XP level per banner.
7. When all banners are renamed, place them in any 128×128 area of the world.
8. Hold a map showing that area. Right-click each banner with the map to register it as a marker.
9. Place the map in an item frame.
10. Anyone with Loominary installed and within 32 blocks of the framed map will now see your image instead of the map's terrain.

### Stealing existing map art

You can copy any map's data into Loominary's state model to do color reduction or recreate it as a banner-based map:

1. Look at the framed map at the crosshair
2. Run `/loominary import steal` (or press your steal hotkey)
3. The map's data is captured as a tile in your batch
4. You can now replicate it elsewhere by following steps 6–10 of "Encoding an image" above

You can steal multiple maps in sequence — each one becomes its own tile.

### Multi-tile murals

For larger images, split across a grid:

```
/loominary import landscape.png 4 2
```

This produces 8 tiles arranged in a 4-column, 2-row grid. Each tile is its own 128×128 map. Work through them with `/loominary tile next` to advance to the next incomplete tile when you finish one. Place the maps in a 4×2 arrangement of item frames to view the assembled mural.

## Commands

All functionality is under a single `/loominary` command. Type `/loominary` and tab through subcommands.

### Importing payloads

- `/loominary import <filename>` — Import an image from `bannermapdata/`. Defaults to a single 128×128 tile.
- `/loominary import <filename> <cols> <rows>` — Split image into a `cols × rows` grid.
- `/loominary import <filename> [cols] [rows] allshades` — Use the full ~248-color palette including shades that can't be reproduced as block placement. Default is the ~186-color "legal" palette.
- `/loominary import steal` — Append the framed map at your crosshair as a new tile.

### Inspecting state

- `/loominary` — Equivalent to `/loominary status`. Shows current batch.
- `/loominary status` — Shows file, grid size, and per-tile progress.
- `/loominary palette` — Shows distinct colors, banner count, and the rarest colors in the active tile.

### Tile navigation

- `/loominary tile <n>` — Switch to tile `n` of the current batch.
- `/loominary tile next` — Switch to the next incomplete tile.
- `/loominary seek <n>` — Set the chunk index of the active tile (for resuming partway through).

### Map manipulation

- `/loominary preview` — Paint the active tile onto the map at your crosshair (client-side only).
- `/loominary revert` — Restore a previewed map to its original colors.

### Palette reduction

- `/loominary reduce` — Reduce the active tile to fit in 255 banners by merging the rarest colors into their visual neighbors.
- `/loominary reduce <target>` — Reduce to a specific banner count (1–255).
- `/loominary reduce undo` — Restore the active tile to its pre-reduction state.

### Export

- `/loominary export` — Write a Litematica `.litematic` schematic of the active tile's banner placement to `<gamedir>/loominary_exports/`. Move the file to Litematica's `schematics/` directory to use it.

### Cleanup

- `/loominary clear` — Clear in-memory state and delete the saved state file.
- `/loominary clear memory` — Clear only in-memory state. Reload from disk on next launch.
- `/loominary clear disk` — Delete only the saved state file. Current session continues.

## Hotkeys

Five actions can be bound to keys in `Options → Controls → Key Binds → Loominary`:

- **Steal map at crosshair** — equivalent to `/loominary import steal`
- **Preview active tile on crosshair map** — equivalent to `/loominary preview`
- **Revert previewed map at crosshair** — equivalent to `/loominary revert`
- **Switch to next incomplete tile** — equivalent to `/loominary tile next`
- **Show batch status** — equivalent to `/loominary status`

All five are unbound by default.

## How It Works

Loominary exploits a chain of Minecraft mechanics that aren't normally connected:

**Banner names are arbitrary text.** The anvil lets you rename a banner up to 50 characters, and that custom name persists in NBT. Loominary renames each banner with a base64-encoded chunk of image data, prefixed with a 2-character hex index for ordering.

**Maps store banner-marker names.** When you right-click a banner with a map, the server records the banner's position, dye color, and custom name as a `MapDecoration` in the map's NBT. This decoration data syncs to clients with the map.

**The client renders maps from a `byte[16384]` color array.** Loominary intercepts maps with banner markers whose names start with two hex digits (its signature pattern), reassembles the chunks in order, base64-decodes and zstd-decompresses the result, and overwrites the client's local color array. The server is unaware.

**The encoding works in map-color space.** The image is converted to Minecraft's exact map-color byte format *before* compression. This compresses far better than compressing the original PNG, because adjacent pixels in a map have high spatial coherence after color quantization. zstd at maximum compression typically reduces a 16,384-byte map to 1,500–6,000 bytes.

**Each banner carries 48 base64 characters** of compressed payload, plus a 2-character hex index (00–ff). That's a maximum of 255 banners × 48 = 12,240 base64 characters = ~9,000 bytes of compressed data per map. Most natural images fit comfortably; complex photographs may need progressive palette reduction, which is usually unnoticeable to the naked eye.

**Decorations are suppressed.** Once Loominary identifies a map as one of its own, it clears the decorations list in the client-side `MapState`, so the banner pin icons don't clutter the image.

## FAQ

### Will this get me banned?

Loominary doesn't modify packets, doesn't fake position, doesn't interact with chat suspiciously, and doesn't interact with the server in any way that vanilla doesn't. It renames banners (vanilla feature), right-clicks them with maps (vanilla feature), and reads map data the server sends (vanilla feature). The image-rendering step happens entirely on your client, after the server has already accepted all the actions.

That said: anti-cheat plugins are unpredictable and can flag any unfamiliar behavior. The anvil-handler does fast clicks at the anvil during renaming. If you're on a server with strict anti-cheat, watch for warnings on your first few renames.

### Other players don't see my image — they see a normal map.

That's expected — Loominary is client-side. Anyone who wants to see the encoded image needs Loominary installed. Without the mod, they see whatever the map normally shows, plus the banner pins from the markers.

### Can I share an encoded map with someone else?

Yes — they need Loominary installed and need to be near the framed map. The data lives in the world save (in the map's NBT), so it's persistent and survives the server.

### My image looks fuzzy or wrong on the map.

Minecraft's map palette is limited to ~62 base colors with up to 4 brightness shades each. Photographs and complex images get color-quantized to fit. Try:

- Use simpler images with bold colors and clear shapes
- Run `/loominary palette` to see how many colors are in use and which are rare
- Try the `allshades` option for ~62 extra colors at the cost of "buildable as blocks" reproducibility

### I keep getting "OVER BUDGET" — too many banners needed.

Use `/loominary reduce` to merge the rarest colors automatically. The tool tells you how many colors were merged and what percentage of pixels were affected. You can also try `/loominary reduce 200` for more headroom, or undo with `/loominary reduce undo` if the result is too aggressive.

For naturally-pathological images (random noise, gradients with no compressible patterns), there's a hard ceiling — 255 banners can hold ~9,000 bytes maximum.

### What's the "legal palette" vs "all shades" distinction?

Minecraft's map color format encodes 64 base colors × 4 shade levels = 256 byte values, but only ~62 of the 64 base colors are populated, and only 3 of the 4 shades occur naturally on real maps (the 4th shade exists in the format but no block-scanning code ever produces it). Loominary's default mode restricts output to those ~186 reachable colors, which means your map art *could* be reproduced with real blocks. The `allshades` option uses the full ~248-color palette, including the unreachable shade, for slightly better visual fidelity at the cost of "this can never be built as actual blocks."

### Does this work on servers?

Yes. Loominary is fully client-side. The server stores banners with custom names and maps with marker data — both are vanilla features. No server-side mod is required.

### Does this work in single-player?

Yes.

### Does this work in Realms?

Yes, with the same caveats as any other server. You're the only one who sees the rendered image unless other players also install Loominary.

### Can I encode something other than images?

The current implementation always interprets the payload as map-color bytes. To encode arbitrary binary data, you'd need a different decoder. The encoding mechanism (zstd → base64 → indexed banner chunks) is generic, so this is theoretically possible — but Loominary itself only does images.

### Why hex indices instead of decimal?

The 2-character prefix on each banner name encodes its position in the chunk sequence. Two decimal digits only covers 0–99, but a single map can hold up to 255 banner markers. Hex covers 0–255 (00–ff) in two characters.

### Will Loominary update for newer Minecraft versions?

The mod targets specific Minecraft internals (the `MapState.colors` byte array, `MapTextureManager.setNeedsUpdate`, `MapDecoration.name`). When Mojang refactors map rendering, Loominary needs to be updated to match. Each Minecraft version is a separate branch.

### How do I uninstall?

Remove the jar from `mods/`. Encoded maps you've already created will still be visible to other Loominary users on the same server, but you'll see them as plain maps with banner pins.

### Can I change the banner spacing or layout?

When *encoding*, Loominary doesn't care where you place the banners — only that you right-click each one with the correct map. You can space them however you want within the 128×128 area the map covers.

When *exporting* schematics, the layout is a fixed 16-column grid for organizational convenience (one column per bundle slot). You can manually edit the schematic NBT if you need a different layout.

### How big is the saved state file?

Tens of kilobytes for a typical batch. For the full 255-banner case across many tiles, low single-digit megabytes. It lives at `<gamedir>/config/loominary_state.json` as pretty-printed JSON, so you can inspect or hand-edit it if needed.

### What happens if I run out of XP mid-batch?

The anvil handler pauses cleanly, logs `Paused: out of XP`, and waits. Farm some levels and the work resumes automatically with no input from you.

### What happens if I run out of bundles or banners mid-batch?

Similar to XP — the handler pauses with a log message describing what's missing. Restock and it resumes.

### How can I tell which maps in my world are Loominary-encoded?

If you're standing within 32 blocks of one and Loominary has had a chance to scan it, the rendered image will appear in place of the normal map. Maps that look unchanged either aren't encoded by Loominary, or have an unrecognized banner-name pattern.

## Credits

Built for and tested on Minecraft 1.21.4. Uses [zstd-jni](https://github.com/luben/zstd-jni) for compression.

Vibe-coded almost entirely by Claude Opus 4.7.

## License

MIT