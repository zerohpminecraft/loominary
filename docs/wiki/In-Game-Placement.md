# Placing your art in-game

You've exported from the web editor and copied `loominary_state.json` to `config/` and the `.litematic` file(s) to `schematics/`. Now the in-game half.

## 1. Load the state

Launch the game and run `/loominary status` — you should see your composition (title, grid, per-tile byte counts). The mod loaded it from the state JSON automatically. If you keep several projects, `/loominary save <name>` and `/loominary load <name>` switch between them.

## 2. Place the carpet platform

Load the schematic in Litematica (**M**) and position it **flat on the ground** — any clear 128×128 area aligned with where your map will be. If the schematic is a *staircase* (the export page tells you), the terrain one block north of its north edge must sit at the same y-level as the schematic origin, so the first row reads at the right height.

Then place the carpets:

- **By hand** from Litematica's ghost preview, or
- **Hands-free** with [`/loominary walk print`](Autonomous-Printing), which walks and places for you.

`/loominary carpets balance` pre-arranges your inventory to match the schematic's material list, and `/loominary carpets fill` restocks carpet stacks from nearby chests.

## 3. Overflow banners (if any)

If the export listed overflow banners for a tile, the payload didn't quite fit in the carpet channel. Head to an **anvil** with unnamed banners (any color), empty bundles, and XP (1 level per banner):

1. Open the anvil. The mod renames one banner per tick automatically and packs finished ones into bundles. It pauses if you run out of anything and resumes when you restock.
2. Place the renamed banners anywhere inside the 128×128 area your map covers.
3. Hold the target map and run `/loominary click` — the mod right-clicks each banner for you (wire-frame markers show which are left). Each click registers the banner's name onto the map as a marker; that's the entire server-side footprint.

If the anvil handler ever reports it's **stuck** (the server keeps rejecting a name), re-export from the web editor — every export gets a fresh salt and therefore fresh names — then `/loominary load` the new state.

## 4. Scan, lock, frame

1. Stand at the platform and use an **empty map** — the carpet colors snapshot into it.
2. **Lock it in a cartography table** (map + glass pane) so later terrain changes can't redraw it.
3. Hang it in an item frame.

![Decoded map art in an item frame](assets/game/preview-map.png)

Within a second the mod decodes the map and paints the art. Anyone else running Loominary within 32 blocks sees it too — with banner pins suppressed so nothing clutters the image. While a heavy tile decodes you'll see a progress screen on the map; see [Troubleshooting](Troubleshooting-and-FAQ) for the other status screens.

## Verifying before you build: preview

Want to see the result before placing a single block? Hang any map in an item frame, look at it, and run `/loominary preview` — the mod paints the active tile (or the whole grid, if it finds a wall of frames) directly onto the framed maps, client-side only. `/loominary revert` restores them. This is purely a local preview — other players' maps are untouched.

## Command crib sheet

| Command | Purpose |
|---|---|
| `/loominary status` | batch overview, per-tile progress |
| `/loominary tile next` / `tile <n>` | switch active tile in a grid |
| `/loominary preview` / `revert` | paint / restore framed maps locally |
| `/loominary click` | auto-right-click banners while holding the map |
| `/loominary carpets balance` / `fill` | inventory prep for printing |
| `/loominary export` | re-generate schematics from the loaded state |
| `/loominary stop` | halt all automation immediately |

Full list: [Command Reference](Command-Reference).
