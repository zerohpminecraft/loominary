# Installation

Loominary is **client-side only**. You install it in your own game and it works everywhere: singleplayer, Realms, and any multiplayer server where you can place blocks.

## Requirements

| | |
|---|---|
| Minecraft | **1.21.4** |
| Mod loader | Fabric Loader **0.16+** |
| Dependency | Fabric API |
| Java | 21 (bundled with the vanilla launcher) |

## Install the mod

1. Download the latest `loominary-<version>.jar` from the [releases page](https://github.com/zerohpminecraft/loominary/releases).
2. Drop it into your `mods/` folder alongside [Fabric API](https://modrinth.com/mod/fabric-api).
3. Launch the game. You'll see `[Loominary] Client-side mod initialized successfully!` in the log.

There is nothing to configure; the mod stays passive until you use a `/loominary` command or walk near encoded map art.

## Install Litematica (recommended)

Carpet-based art (the default and best mode) is placed as a **[Litematica](https://modrinth.com/mod/litematica)** schematic. The web editor exports a `.litematic` file with the exact carpet layout, so install Litematica and its dependency [MaLiLib](https://modrinth.com/mod/malilib) to see a ghost preview of where every carpet goes.

## Optional: the printer, for hands-free building

If you want `/loominary walk print` to place all the carpets for you (see [Autonomous Printing](Autonomous-Printing)), you also need a Litematica printer build. The commonly used fork is **[IceTank's litematica-printer](https://github.com/IceTank/litematica-printer)**. Loominary toggles it automatically while printing; you never interact with it directly.

Without a printer you can still place carpets by hand from the Litematica ghost preview, just more slowly.

## The web editor needs no installation

All image editing happens at **https://zerohpminecraft.github.io/loominary/**, which runs entirely in your browser (nothing is uploaded anywhere; the encoding is done locally). Bookmark it; it's the first stop of every workflow.

## Next step

→ **[Quick Start](Quick-Start)**: your first map art in about ten minutes.
