# Loominary

**Turn any image, even an animated GIF, into Minecraft map art that works on any vanilla server.**

### 🎨 [Open the web editor →](https://zerohpminecraft.github.io/loominary/) · 📖 [Read the wiki →](https://github.com/zerohpminecraft/loominary/wiki) · ⬇ [Releases](https://github.com/zerohpminecraft/loominary/releases)

Loominary is a client-side Fabric mod (Minecraft 1.21.4) paired with a browser-based editor. You design and encode your art in the web editor, place a carpet schematic in-game, and scan it with a map. Everyone running Loominary then sees your image painted on that map, while players without the mod just see an ordinary map of some carpet. There is no server plugin and nothing to install server-side; the data travels inside vanilla mechanics (carpet colors and banner names).

<p align="center">
  <img src="docs/wiki/assets/web/import-preview.png" width="70%" alt="The web editor"><br>
  <img src="docs/wiki/assets/game/preview-map.png" width="70%" alt="The same art decoded in-game">
</p>

## Highlights

- **Any image in**: PNG, JPEG, WebP, GIF, or BMP, with perceptual OKLab color matching and optional dithering
- **Animated map art**: GIFs become AV1-encoded animations that play back on maps, frame-synced between viewers
- **Murals**: split one image across an N×M wall of maps, with seamless dithering and automatic byte-budget balancing
- **Autonomous building**: `/loominary walk print` walks your player along the platform and places every carpet
- **Encrypted art**: AES-256-GCM password protection with multiple key slots
- **Shareable**: export files anyone can rebuild from, or archive art straight out of the world

## Getting started

1. Drop the [release jar](https://github.com/zerohpminecraft/loominary/releases) and [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/` ([full install guide](https://github.com/zerohpminecraft/loominary/wiki/Installation))
2. Follow the [Quick Start](https://github.com/zerohpminecraft/loominary/wiki/Quick-Start) to make your first map art in about ten minutes

The [wiki](https://github.com/zerohpminecraft/loominary/wiki) covers the rest: the web editor, placement, animation, murals, encryption, and a full command reference.

## Credits & license

Built with [zstd-jni](https://github.com/luben/zstd-jni), [Chicory](https://github.com/dylibso/chicory), and [libaom](https://aomedia.googlesource.com/aom/). Vibe-coded almost entirely by Claude.

MIT
