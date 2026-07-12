# Quick Start: your first map art

This walkthrough takes one image from your disk to a framed map in-game. Budget about ten minutes the first time. You'll need the mod [installed](Installation) plus Litematica, and any image you like.

## Step 1: Encode it in the web editor

1. Open **https://zerohpminecraft.github.io/loominary/**.
2. Drop your image onto the import area (PNG, JPEG, WebP, GIF, or BMP).

   ![Import step with a live quantized preview](assets/web/import-preview.png)

3. The right side shows a live preview of how the image maps onto Minecraft's palette, with a match-quality score. Dithering is **off by default**. For photos and gradients, switching it to `FS` in the Quantization step is the first knob worth turning; pixel art and logos are usually best left undithered.
4. Click **Proceed to Editor →**. You can paint, fill, and fix pixels here ([full tour](Web-Editor-Editing)), or go straight on.
5. Click **③ Export** in the step bar, then **⬇ Export ZIP**.

You now have a ZIP containing:

| File | What it is |
|---|---|
| `loominary_state.json` | The encoded payload the mod reads |
| `loominary_carpet_r0_c0.litematic` | The carpet-platform schematic |
| `preview.png` | What the finished map will look like |
| `README.txt` | These same instructions, offline |

## Step 2: Install the export

1. Copy `loominary_state.json` into your game's **config** folder:
   - Windows: `%appdata%\.minecraft\config\`
   - Linux: `~/.minecraft/config/`
   - macOS: `~/Library/Application Support/minecraft/config/`
2. Copy the `.litematic` file into your **schematics** folder (`.minecraft/schematics/`).
3. Launch (or re-enter) the game. `/loominary status` should show your import.

> Already in-game with the file on disk? There's also a direct path: drop the image in `<gamedir>/loominary_data/` and run `/loominary import <filename>`. The web editor gives you more control, and it's the only way to reduce colors, dither selectively, or encrypt, but for a quick simple image the command works.

## Step 3: Place the carpet platform

1. Open Litematica's menu (**M** by default), load your schematic, and position it flat on the ground where you want the art to live. Any open 128×128 area works.
2. Place the carpets, either by hand from the ghost preview or hands-free with [`/loominary walk print`](Autonomous-Printing) if you have a printer installed. The finished platform is only as deep as your data:

   ![A placed carpet platform](assets/game/carpet-platform.png)
3. If the export mentioned **overflow banners**, head to an anvil with unnamed banners, empty bundles, and some XP, and the mod renames them automatically. Place them anywhere inside the map's area and run `/loominary click` while holding the map. Small images usually need none.

## Step 4: Scan and frame

1. Hold an **empty map** while standing on or near the platform, and use it. This snapshots the carpet colors into the map.
2. **Lock the map in a cartography table** (map + glass pane). This stops the server from redrawing it if the terrain changes.
3. Place the locked map in an item frame.

![The decoded art on a framed map](assets/game/preview-map.png)

The mod reads the carpet data back out of the map, decodes it, and paints your image for you and every other Loominary user within 32 blocks. Players without the mod see the ordinary carpet-colored map.

## Where to next?

- **[The web editor in depth](Web-Editor-Import)**: dithering, palettes, grids, quality tuning
- **[Animated art](Animated-Art)**: the same flow with a GIF
- **[Multi-tile murals](Multi-Tile-and-Mux)**: wall-sized art across many maps
- **[Autonomous printing](Autonomous-Printing)**: let the mod build the platform
- **[Troubleshooting](Troubleshooting-and-FAQ)**: when a step doesn't behave
