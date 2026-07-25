# Ep. 08: Tips, Troubleshooting & How It Actually Works

**Target length:** ~7 min · **Audience:** the finale, for the curious

## Packaging

- **Title:** How Loominary Smuggles Images Through Vanilla Minecraft
- **Thumbnail text:** "8,192 BYTES OF CARPET" over a zoomed carpet platform with byte annotations
- **Description:**
  > The finale: how an image actually travels through carpet colors, carpet heights, and banner names, every status screen decoded, and the fixes for the common failure modes. Wiki: https://github.com/zerohpminecraft/loominary/wiki

## Setup checklist (for the shoot)

- [ ] Diagram cards prepared (see cards list)
- [ ] A carpet platform you can fly over for the annotation shots
- [ ] The four status screens available (padlock, waiting, decoding, error)

## Narration script + shot list

Cues are narration AND burned captions, verbatim. Register: dry and plain, concise and factual, no in-group asides. Burned captions are one row at a time. No em-dashes in cues. Format is a mix: diagram cards for the concepts, real in-game footage for the payoffs.

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual/game (REAL):** slow flyover of a carpet platform | "Sixteen carpet colors. Look closely. This is not a picture. It is a file." |
| 0:20 | **generated:** diagram card, nibble encoding (`ep08-nibble`) | "Minecraft has exactly sixteen carpet colors, and sixteen values fit in four bits. So two carpets store one byte, and a full platform holds about eight thousand. When you scan a map over it, the server itself hands every mod user the data, as map colors." |
| 1:10 | **generated:** diagram card, shade / height channel (`ep08-shade`) | "Need more room? Carpet height is a second channel. Minecraft shades a map by elevation, so raising carpets into low staircases writes another two thousand bytes as brightness. That is why big payloads export a staircase schematic." |
| 2:00 | **generated:** diagram card, banner name + CJK capacity (`ep08-banner`) | "Still more? Banner names. An anvil name holds fifty characters, and Chinese, Japanese, and Korean characters pack fourteen bits each. That is eighty-four bytes per banner, across up to sixty-three banners. One tile carries about fifteen thousand bytes before compression, and zstd takes it much further." |
| 2:50 | **generated:** diagram card, full pipeline (`ep08-pipeline`) | "So the whole pipeline. Quantize to the map palette, compress, encrypt if you asked, then split across carpet colors, carpet height, and banner names. The mod reverses every step in your client. The server never runs a line of our code. It is just storing blocks and item names." |
| 3:40 | **generated:** all four status screens (`status-*.png` + `status-decoding-anim.gif`) | "The mod tells you what is wrong on the map itself. A progress bar means it is decoding, give it a moment. Waiting, with a tile count, means a multi-tile piece needs every tile scanned once. A padlock means it is encrypted, add the password. A warning triangle means the decode failed, so check the log, usually a partial scan or an outdated mod." |
| 5:00 | **manual/game (REAL):** a rescan fixing a stale map | "Rule of thumb. When in doubt, rescan the map after the platform is fully placed." |
| 5:30 | **generated:** broll, palette panel merging rare colors, byte count dropping | "Bold beats busy. Rare colors cost far more in compression than their pixel count suggests, so merge them. Push saturation a little at import. And for animations, use fewer colors and turn off import dithering." |
| 6:10 | **manual/game (REAL):** the anvil stuck message, then re-export and load fix | "If the anvil handler says it is stuck, the server is rejecting a name. Export again for a fresh salt and fresh names, then load the new state." |
| 6:45 | **generated:** series outro over multi-tile art footage (`ep08-end`) | "That is the series. The wiki has every detail, the web editor is free and runs in your browser, and the whole thing is open source. Go make something." |

## Cards

- **`ep08-nibble`:** 16 carpet swatches → 4 bits → two carpets = one byte → 128×128 = ~8,000 bytes on a platform.
- **`ep08-shade`:** carpet height as a shade channel; a low 4-row staircase; ~2,000 bytes.
- **`ep08-banner`:** a 50-char banner name; CJK glyph = 14 bits; 84 bytes/banner × up to 63 banners.
- **`ep08-pipeline`:** quantize → compress → encrypt (optional) → split across carpet / height / banners → client decodes (source: `docs/dev/pipeline.md`).
- **`ep08-end`:** series outro (wiki, web editor, open source), series-consistent.

## B-roll manifest

- generated: diagram cards (`ep08-{nibble,shade,banner,pipeline,end}` via `record-cards.mjs`); the four status screens `status-{waiting,decoding,locked,error}.png` + `status-decoding-anim.gif` (from `./gradlew renderMapPreviews`); broll of the palette-merge / byte-count flow (`-g ep08`).
- manual/game (REAL): platform flyover, rescan fix, anvil stuck + recovery, outro footage.

## Production notes

- **Register:** dry and plain, no in-group asides. Burned captions one row at a time. No em-dashes in cues. No "mural" (use multi-tile / grid).
- **Format (decided): mix** of diagram cards + real footage; **intuitive + accurate** depth (real mechanism, a few round numbers, no exhaustive capacity math).
- **Fact-check (verified against current code, 2026-07-15 — the three-channel model is CURRENT, not legacy):**
  - Three live channels per tile, priority carpet > banners > shade (`CodecMode.java`, `CarpetChannel.java`):
    - **Carpet color:** 16 dye carpets = one nibble, 2 pixels/byte, 128×128 = 16,384 px. Gross `MAX_CARPET_BYTES = 8192`; net payload `8176` after the 16-byte LOOM header. Narration rounds to "about eight thousand."
    - **Carpet height / shade:** balanced 4-row height sequences {0,1,2} read out as map shading; `MAX_SHADE_BYTES = 2016` ("another two thousand"). Exported as the "staircase schematic" (`SchematicExporter`).
    - **Banner names (CJK):** 2-char hex index + 48 CJK payload chars, 14 bits each = **84 bytes/banner**; current LOOM cap is **63 banners** (`MAX_OVERFLOW_BYTES_LOOM = 63×84−2 = 5290`). **Fix applied: 62 → 63.**
    - **Total** `MAX_TOTAL_BYTES_LOOM = 8176 + 2016 + 5290 = 15,482` ("about fifteen thousand"), before zstd.
  - **AV1** is a *payload codec* chosen per-tile for animation only when it beats raw-frames + zstd; its output is still zstd-wrapped and carried by the same three channels. Do NOT imply AV1 replaces the carpet platform.
  - **Pipeline order:** quantize → zstd compress → encrypt (optional, on the compressed bytes) → channel split. Client-side only (`fabric.mod.json` `"environment": "client"`).
  - **Four status screens** are drawn programmatically by `PlaceholderArt` (not PNG assets in-game): `decoding` (progress bar), `waiting` (N-of-M tiles), `locked` (padlock), `error` (warning triangle).
  - **Quality tips** all confirmed in the wiki (rare-color merge, saturation push 1.1–1.3, animations: fewer colors + no import dither).
  - **Anvil stuck:** `AnvilAutoFillHandler` halts with "Stuck — re-export from the web editor (fresh salt), then /loominary load"; cleared by loading fresh state.
- **DECtalk pronunciations:** "zstd" → "zee standard" or "Z S T D" (pick what reads cleanly); "CJK" spelled out; "nibble" reads fine. Copy/adapt `assemble-ep05.py` → `assemble-ep08.py`.
