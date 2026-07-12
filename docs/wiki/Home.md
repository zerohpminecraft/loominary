# Loominary

**Turn any image, including animated GIFs, into Minecraft map art that works on any vanilla server.**

Loominary is a client-side Fabric mod paired with a browser-based editor. You design and encode your art in the **[web editor](https://zerohpminecraft.github.io/loominary/)** (nothing to install), then the mod renders it in-game as custom map art. There is no server plugin or resource pack involved, and you don't need any permissions. The data travels inside vanilla game mechanics: colored carpet blocks and named banners.

![The web editor's import step](assets/web/import-preview.png)

## How it works, in one paragraph

The web editor quantizes your image to Minecraft's map palette, compresses it, and packs the bytes into things a vanilla server stores natively: the colors of a carpet platform (read back through the map itself) and the names of a few banners (registered on the map as markers). Anyone running Loominary who comes within range of your framed map sees the full image (photographs, pixel art, even animations) painted client-side. Everyone else just sees an ordinary map.

![The same art decoded onto a map in-game](assets/game/preview-map.png)

## What can it do?

- **Any image in, map art out.** PNG, JPEG, WebP, GIF, BMP; perceptual OKLab color matching and adaptive dithering get a lot out of the limited palette.
- **Animated map art**: GIFs become AV1-encoded animations that play back on the map, frame-synced across multi-tile walls.
- **Giant murals.** Split one image across an N×M wall of maps, with seamless dithering and automatic byte-budget balancing between tiles (mux).
- **Fully autonomous building**: `/loominary walk print` walks your player along the platform and places every carpet for you.
- **Encrypted art.** Lock your art with passwords (AES-256-GCM); other players need both the mod and the password.
- **Archiving**: capture any existing framed map art into your own collection.
- **Sharing.** Hand your exported files to anyone; their mod renders your art identically.

## Where to start

1. **[Installation](Installation)**: the mod, Fabric, and the optional printing setup (5 minutes)
2. **[Quick Start](Quick-Start)**: your first map art, from image file to framed map (10 minutes)
3. **[The web editor](Web-Editor-Import)**: the full three-step editor tour

Questions? Check the **[Troubleshooting & FAQ](Troubleshooting-and-FAQ)** page or [open an issue](https://github.com/zerohpminecraft/loominary/issues).

> **Fun fact:** Loominary started life as "Banner Mod", and the original version squeezed entire images into nothing but banner names. That mode [still exists](Banner-Mode-Legacy) and requires no building at all, but the carpet channel carries ~3× the data, so it's now the default.
