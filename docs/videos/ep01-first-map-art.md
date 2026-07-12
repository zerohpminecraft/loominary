# Ep. 01 — Your First Minecraft Map Art in 10 Minutes

**Target length:** 7–8 min · **Audience:** never heard of Loominary

## Packaging

- **Title:** Turn ANY Image Into Minecraft Map Art (no server plugins)
- **Thumbnail text:** "ANY IMAGE → MAP ART" over a split: source photo | in-game framed map
- **Description:**
  > Loominary turns any image into Minecraft map art that works on vanilla servers — no plugins, no permissions. In this video: install the mod, encode an image in the free web editor, place the carpet platform, and frame the finished map. Web editor: https://zerohpminecraft.github.io/loominary/ · Wiki: https://github.com/zerohpminecraft/loominary/wiki · Downloads: https://github.com/zerohpminecraft/loominary/releases

## Setup checklist

- [ ] Fresh 1.21.4 Fabric instance with Loominary, Fabric API, Litematica (+ printer if you'll tease ep. 5)
- [ ] A visually striking source image (bold colors — sunset, poster art, logo)
- [ ] A flat scenic build spot with an item-frame wall prepared
- [ ] OBS scenes: browser full-screen, game full-screen

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** finished framed map art reveal in a nice base, slow zoom | "This is a Minecraft map. A completely vanilla map, on a completely vanilla server. And it's showing a photograph. In the next ten minutes you'll be able to do this with any image you want — and if a friend walks by with the same mod, they'll see it too." |
| 0:25 | **generated:** web editor wizard video (broll), title card | "The tool is called Loominary. Two halves: a client-side Fabric mod, and a web editor that runs entirely in your browser. Nothing gets uploaded, nothing touches the server." |
| 0:45 | **manual:** dropping jars into mods/ folder, launcher | "Install is the usual Fabric routine: Loominary, Fabric API, and Litematica into your mods folder. Links below." |
| 1:10 | **generated:** broll — dropping an image onto the import page, preview appearing | "Now the fun part. Open the web editor and drop in your image. What you're seeing on the right isn't a mockup — it's the exact result, quantized to Minecraft's actual map palette." |
| 1:45 | **generated:** broll — nudging saturation, dither toggle; stills `import-preview.png` | "Minecraft's palette is muted, so a small saturation boost usually helps. The dithering is doing heavy lifting here — smooth gradients get dithered, sharp edges stay crisp. There's a match-quality score if you want to be scientific about it." |
| 2:30 | **generated:** broll — Proceed to Editor, quick brush stroke, undo | "Step two is a full pixel editor if you want to touch anything up. We don't, today." |
| 2:50 | **generated:** broll — export page tour; still `export-overview.png` | "Step three: export. This screen tells you exactly how your image travels — carpet colors carry most of the bytes, banners catch any overflow. Hit Export ZIP." |
| 3:20 | **manual:** file manager copying the two files | "Two files matter: the state JSON goes into your Minecraft config folder, the litematic into your schematics folder." |
| 3:50 | **manual:** in-game, `/loominary status` output | "In game, `/loominary status` confirms the mod loaded it." |
| 4:05 | **manual:** Litematica placement + placing carpets (timelapse) | "Load the schematic with Litematica and place the platform — it's a 128 by 128 sheet of carpet. Yes, you can place it by hand from the ghost preview. Yes, there's a mod feature that walks around and places it all for you — that's a whole other video." |
| 5:20 | **manual:** using an empty map on the platform | "Stand on the platform and use an empty map. That snapshot is the trick — the carpet colors just became data on the map." |
| 5:40 | **manual:** cartography table lock, framing the map | "Lock it at a cartography table so it never redraws, and frame it." |
| 6:00 | **manual:** the decode moment — art appears; slow push-in | "And… there it is. The mod reads the carpet data back off the map and paints the real image. Anyone with Loominary sees this. Anyone without sees a mildly abstract carpet pattern." |
| 6:40 | **generated:** stills carousel — `import-gif.png`, `export-multitile.png`, `status-locked.png` | "Animated GIFs, wall-sized murals, password-locked art — all supported, all coming up in this series." |
| 7:10 | end card | "Web editor and wiki linked below. Go make something." |

## B-roll manifest

- generated: `web/e2e/media/` wizard walkthrough video (broll spec), stills `import-preview.png`, `export-overview.png`, `import-gif.png`, `export-multitile.png`, `status-locked.png`
- generated: `docs/wiki/assets/game/preview-map.png` (backup for the reveal if the manual shot fails)
- manual: base reveal, install, file copying, Litematica placement timelapse, map scan + lock + frame, decode moment
