# Ep. 08 — Tips, Troubleshooting & How It Actually Works

**Target length:** 7–8 min · the finale for the curious

## Packaging

- **Title:** How Loominary Smuggles Images Through Vanilla Minecraft
- **Thumbnail text:** "8,192 BYTES OF CARPET" over a zoomed carpet platform with byte annotations
- **Description:**
  > The finale: how images actually travel through carpet colors, carpet heights, and banner names — plus every status screen decoded and the fixes for the common failure modes. Wiki: https://github.com/zerohpminecraft/loominary/wiki

## Setup checklist

- [ ] Slides/diagrams prepared (see manifest)
- [ ] A platform you can fly over for annotation shots

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** flyover of a carpet platform | "Sixteen carpet colors. Look closely — this isn't a picture. It's a file." |
| 0:20 | diagram: nibble encoding | "Minecraft has exactly sixteen carpet colors, and sixteen values fit in four bits. Two carpets per byte, sixteen thousand map pixels — 8,192 bytes on a flat platform. When you scan a map over it, the server *itself* hands every mod user the data, as map colors." |
| 1:10 | diagram: staircase / shade channel | "Need more? Carpet *height* is a second channel — the map renders carpets brighter or darker by elevation. Balanced four-row height patterns encode another two thousand bytes. That's why big payloads export staircase schematics." |
| 2:00 | diagram: banner name + CJK | "Still more? Banner names. An anvil name holds fifty characters, and CJK ideographs pack fourteen bits each — 84 bytes per banner, across up to 62 banners. Grand total: about fifteen and a half thousand bytes per tile, and zstd compression makes that feel like sixty." |
| 2:50 | diagram: full pipeline (from `docs/dev/pipeline.md`) | "So the pipeline: quantize to the map palette, compress, encrypt if asked, split across carpet, shade, and banners. The mod reverses every step client-side. The server never runs a line of our code — it's just storing blocks and item names." |
| 3:40 | **generated:** all four status screens (`status-*.png` + `status-decoding-anim.gif`) | "Now troubleshooting, because the mod tells you what's wrong on the map itself. Progress bar: AV1 decoding, give it seconds. Waiting n-of-m: a mural or muxed batch needs every tile scanned once. Padlock: encrypted, add the password. Warning triangle: decode failed — check the log, usually a partial scan or an outdated mod." |
| 5:00 | **manual:** rescan fixing a stale map | "Rule of thumb: when in doubt, rescan the map after the platform is fully placed." |
| 5:30 | **generated:** broll — palette panel merging rare colors, byte count dropping | "Quality tips in thirty seconds: bold beats busy. Rare colors cost compression far beyond their pixel count — merge them. Boost saturation at import. For animations, fewer colors and no import-dither." |
| 6:10 | **manual:** anvil stuck message + re-export/load fix | "If the anvil handler says it's stuck, the server's rejecting a name: re-export — fresh salt, fresh names — and load the new state." |
| 6:45 | series outro over mural footage | "That's the series. The wiki has every detail, the web editor is free and in your browser, and the whole thing is open source. Show me what you make." |

## B-roll manifest

- generated: `status-waiting.png`, `status-decoding.png`, `status-decoding-anim.gif`, `status-locked.png`, `status-error.png` (from `./gradlew renderMapPreviews`); broll palette-merge flow
- manual: platform flyover, rescan fix, anvil stuck + recovery, outro footage
- to make: 4 diagram slides — nibble encoding, shade staircase, banner/CJK capacity, full pipeline (source material: `docs/dev/pipeline.md`)
