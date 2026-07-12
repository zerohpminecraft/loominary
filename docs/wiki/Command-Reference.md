# Command reference

Everything lives under a single `/loominary` command; type it and tab through the subcommands. Image *editing* is deliberately absent, since that's the [web editor](Web-Editor-Import)'s job.

## State & batches

| Command | Effect |
|---|---|
| `/loominary` or `/loominary status` | Batch overview: source, grid, title, per-tile channels/bytes/progress |
| `/loominary status donors [page]` | List mux donor tiles |
| `/loominary save [name]` | Save the batch to `loominary_saves/` (imports auto-save with a counter) |
| `/loominary load <name>` | Load a saved batch (tab-completes; also clears any anvil halt) |
| `/loominary clear [memory\|disk]` | Clear the active batch (both, or just RAM / just the state file) |

## Importing

| Command | Effect |
|---|---|
| `/loominary import <file> [cols rows] [dither] [allshades] [mux]` | Encode an image from `loominary_data/` (carpet codec). Over-budget imports abort; use the web editor to shrink |
| `/loominary import <file> banners [cols rows] [dither] [allshades]` | Same, banner-only codec |
| `/loominary import steal [banners]` | Capture the framed map at your crosshair as a tile |
| `/loominary import header <banner> <screenshot.png>` | Rebuild a payload from a map screenshot + its manifest banner string |

## Tiles & navigation

| Command | Effect |
|---|---|
| `/loominary tile <n>` / `tile next` / `tile prev` | Switch the active tile |
| `/loominary tile pos <col> <row>` | Switch by grid position |
| `/loominary seek <n>` | Set the active tile's chunk index (resume banner work partway) |

## Preview

| Command | Effect |
|---|---|
| `/loominary preview` | Paint the active tile (or the whole discovered grid) onto framed maps at your crosshair (client-side only) |
| `/loominary revert [all]` | Restore previewed maps to their real colors |

## Placement & automation

| Command | Effect |
|---|---|
| `/loominary click [stop]` | Auto-right-click your placed banners while holding the map |
| `/loominary carpets balance` | Arrange inventory to match the Litematica material list |
| `/loominary carpets fill [width]` | Restock carpet stacks from catalogued chests |
| `/loominary carpets catalogue` | Scan and remember which chest holds which carpet color |
| `/loominary walk` | Toggle duty-cycle auto-walk |
| `/loominary walk <on> <off>` | Set walk/pause tick timings |
| `/loominary walk print [width]` / `walk print stop` | Fully autonomous carpet printing ([guide](Autonomous-Printing)) |
| `/loominary walk printer on\|off` | Printer debug logging |
| `/loominary stop` | **Halt every automation immediately** |

## Encoding & payload

| Command | Effect |
|---|---|
| `/loominary codec [mode]` | Show or set the codec (`carpet+banners+shade`, `carpet+shade`, `carpet+banners`, `carpet`, `banner`); re-encodes all tiles |
| `/loominary mux [undo]` | Append blank donor tiles to absorb overflow / remove them |
| `/loominary title [text]` | Set (or clear) the embedded title; re-encodes tiles |
| `/loominary author [name\|clear]` | Set/show/clear the embedded author |

## Passwords

| Command | Effect |
|---|---|
| `/loominary password add <pw>` / `remove <pw>` / `clear` / `list` | Manage stored decryption passwords |
| `/loominary password encrypt <pw>` / `encrypt off` | Encrypt the current batch's payloads / stop encrypting |

## Export & misc

| Command | Effect |
|---|---|
| `/loominary export [name]` | Write Litematica `.litematic` file(s) to `loominary_exports/` |
| `/loominary export image` | Render the active tile back to PNG (or animated GIF) |
| `/loominary whitelist [add\|clear]` | Mark named banners in your inventory as reusable |
| `/loominary dumppalette` / `dumpcarpet` | Debug dumps |

## Removed in v2.0.0 → where it went

The in-game editing commands were removed; the web editor does all of it better. The old names still respond with a pointer.

| Removed command | Do it in the web editor instead |
|---|---|
| `edit` | the entire [Edit step](Web-Editor-Editing) |
| `reduce` (+ undo/strategy/colors) | palette panel → merge/requantize |
| `dither` | import-step dither settings (or the dither brush) |
| `filter smooth/median/sharpen/posterize` | import-step adjustments |
| `requantize` (+ metric/dither) | import-step match metric & dither settings |
| `palette` | palette panel with live counts |
| `stride` / `skip` | editor frame strip → stride/skip |
| `sparse` | export chooses frame packing automatically |
| `resalt` | every export gets a fresh salt; re-export + `/loominary load` |
