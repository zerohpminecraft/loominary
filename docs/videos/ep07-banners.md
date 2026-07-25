# Ep. 07: The Banner Channel

**Target length:** ~7 min · **Audience:** eps. 1–6 viewers; the "wait, it's the *names*?" one

## Packaging

- **Title:** Minecraft Map Art Hidden in Banner Names
- **Thumbnail text:** "IT'S THE NAMES" over a wall of banners feeding into a map
- **Description:**
  > Loominary can carry a whole image in nothing but banner names, or spill a big image's overflow into them next to a carpet floor. How 84 bytes fit in one banner, why the mod reads map markers and not the banners themselves, why banner-only art ignores grid alignment, and the automation that names and clicks them all for you. Wiki: https://github.com/zerohpminecraft/loominary/wiki/Anvil-and-Banners

## Setup checklist (for the shoot)

- [ ] A 1.21.4 client with Loominary
- [ ] A banner-only export loaded (`/loominary codec banner`), plus a carpet+banners export for the overflow beat
- [ ] Blank banners, empty bundles, and some XP for the anvil auto-fill beat
- [ ] An anvil, and a wall/floor to place and register banners

## Narration script + shot list

Cues are narration AND burned captions, verbatim. Register: dry and plain, concise and factual, no in-group asides. Burned captions are one row at a time. No em-dashes in cues. Accuracy is the whole point of this one.

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual/game (REAL):** a finished banner-only map on a wall, then the banners that built it | "This map art is not made of blocks you can see. It is made of banners. More exactly, it is made of the names on those banners." |
| 0:30 | **generated:** diagram card, banner name layout (`ep07-name`) | "Any banner you rename in an anvil can hold a fifty character name. Loominary fills it with Chinese, Japanese, and Korean characters, because those survive the server's name filter, and each one packs fourteen bits. Two characters are an index, forty-eight are data. That is eighty-four bytes on a single banner." |
| 1:20 | **generated:** diagram card, banner to map marker (`ep07-marker`) + **game** of a right-click | "Here is the part that trips people up. The mod does not read the banner blocks. When you right-click a placed banner while holding a map, the server records that banner's name as a marker on the map. The mod reads those markers, sorts them by index, and rebuilds the file. The banner is just somewhere to keep a name." |
| 2:15 | **generated:** diagram card, banner-only capacity (`ep07-capacity`) | "In banner only mode there is no carpet platform at all. Sixty-three banners, about five thousand bytes, and the whole image rides in names. It is the right pick for small or flat art, where a full carpet floor would be overkill." |
| 2:55 | **manual/game (REAL):** an empty map, banners placed off the grid, still decoding | "And because the data lives in the names, not in the map's pixels, banner only art does not care about grid alignment. A carpet platform has to line up with the map cell exactly or every pixel shifts. Banners can sit anywhere inside the area the map covers. The map's own picture is ignored." |
| 3:45 | **generated:** diagram card, carpet + overflow banners (`ep07-capacity`, second panel) | "For bigger images there is carpet plus banners. The carpet platform fills first, about eight thousand bytes, and whatever does not fit overflows into banner names, another five thousand. Together that is past thirteen thousand bytes in one tile, with no height staircase involved." |
| 4:30 | **manual/game (REAL):** opening an anvil, banners renaming themselves one by one into a bundle | "Naming sixty-three banners by hand would be miserable, so Loominary does it. Open any anvil and keep it stocked with blank banners, empty bundles, and experience. It renames them one at a time, packs them into bundles, and saves its progress as it goes." |
| 5:20 | **manual/game (REAL):** the anvil pausing on low XP, the chat message | "It spends one experience level per banner, so it pauses when you run low and continues when you top up. If the server rejects a name outright, it stops and tells you to re-export for a fresh salt, which changes every name while keeping the same image." |
| 6:00 | **manual/game (REAL):** `/loominary click` walking the placed banners, markers turning green | "Then registering them. Loominary click walks the placed banners and right-clicks each one onto the map for you. Yellow means clicked, green means the server confirmed it." |
| 6:35 | **manual/game (REAL):** framing the map, the decode reveal | "Scan, frame, and the mod turns every name back into the picture." |
| 6:55 | **generated:** end card (`ep07-end`) | "Carpet for big and detailed, banners for small and portable, and both together when you need the room. Next up: how the whole thing works." |

## Cards

- **`ep07-title`:** "It's the names." with a banner-to-map motif.
- **`ep07-name`:** a banner + its 50-char name broken into `2 hex index` + `48 CJK chars`; "14 bits each → 84 bytes".
- **`ep07-marker`:** banner block + held map → arrow → a map with a labeled marker; "the mod reads markers, not banners".
- **`ep07-capacity`:** two panels: banner-only (63 banners ≈ 5,290 B) and carpet+banners (8,176 + 5,290 ≈ 13,466 B).
- **`ep07-end`:** one-line recap + tease for the finale.

## B-roll manifest

- manual/game (REAL): finished banner-only map + its banners, the right-click registration, off-grid placement that still decodes, the anvil auto-fill renaming, the low-XP pause, `/loominary click`, the framed decode reveal.
- generated: cards `ep07-{title,name,marker,capacity,end}` via `record-cards.mjs`.

## Production notes

- **Register:** dry and plain, no in-group asides. Burned captions one row at a time. No em-dashes in cues. No "mural."
- **Accuracy is the point.** Verified against current code (2026-07-15):
  - A banner carries data in its **custom name** (set via anvil rename). Alphabet is **CJK Unified Ideographs U+4E00–U+8DFF** (16,384 code points), **14 bits/char** (`CjkCodec.java` `ALPHA_BASE=0x4E00`, `ALPHA_BITS=14`, `ALPHA_SIZE=16384`). Name = **2-char hex index + 48 CJK payload = 50 chars = 84 bytes/banner** (`PAYLOAD_CHARS=48`, `BYTES_PER_BANNER=84`). 48 chars is chosen because 48×14=672 bits = exactly 84 bytes.
  - **Max 63 banners per map** (`MAX_CHUNKS=63`, a 2b2t server limit on markers per map, not the 255 hex-index ceiling).
  - **Decode reads MapDecoration names, not banner blocks.** Right-clicking a placed banner while holding a filled map makes the server attach a banner-type map decoration whose label is the banner's name (the only server-side write). The mod reads decorations via `MapStateAccessor.getDecorations()`, keeps names matching `[0-9a-f]{2}.*`, sorts by hex index, concatenates, decompresses, paints (`MapBannerDecoder.java` legacy/banner branch). CJK-vs-base64 auto-detected by whether the first payload char ≥ U+4E00.
  - **BANNER mode** = 63 CJK banners, **5,290 bytes** (63×84−2, the −2 is a length header), no carpet. **CARPET_BANNERS** = carpet payload **8,176** + overflow banners **5,290** = **13,466** bytes, no shade. (Default `CARPET_BANNERS_SHADE` adds 2,016 shade for 15,482 total.) Numbers from `CarpetChannel.java` / `MuxAllocator.java`.
  - **Grid alignment:** banner-only is alignment-independent (the decode path never reads `mapState.colors`; the map's pixels are irrelevant, banners just need to sit inside the map's covered 128×128 area). Carpet must align to −64 mod 128 because it reads pixel colors. This contrast is the clean teaching moment.
  - **Automated naming** = `AnvilAutoFillHandler` — runs automatically whenever an anvil screen is open (no keybind/command). It extracts a blank banner, sends `RenameItemC2SPacket` with the chunk string, retrieves the output, bundles it, and saves progress after each rename (`PayloadState.save`). **1 XP level per rename**; pauses "out of XP" / "add unnamed banners"; after 3 rejected attempts halts with "Stuck — re-export from the web editor (fresh salt), then /loominary load". Fresh salt changes every name while the image is byte-identical.
  - **Automated clicking** = `/loominary click` (`BannerAutoClickHandler`) walks placed banners and `interactBlock`s each while holding the map; reach ≈ 4.5 blocks, one every 5 ticks; wireframe markers yellow (clicked) → green (server-confirmed).
  - **Full lifecycle:** web editor → chunk strings in `loominary_state.json` → anvil auto-rename onto blank banners → place banners anywhere in the map's area → `/loominary click` registers them as markers → frame the map → decoder reassembles + decompresses + paints.
- **Capture (as produced, 2026-07-15):** the money shot — a **real banner-only decode** — is captured: a `BANNER`-mode state (`banner-art-state.json`, a Heart, ~6 chunks; `web/scripts/gen-banner-state.mjs`) is loaded, an empty map is scanned, and a DocsDriver `registerBanners` step adds the tile's chunk strings as banner map-decorations on the **server** `MapState` (`sworld.getMapState(id)` + `markDirty()`, the same records a real right-click creates), so the mod reassembles them and paints the Heart. A `decodeToggle` beat shows the raw map (a meaningless floor render) versus the decoded art, making the "the data is invisible, it is in the names" point. **Two headless limits, carried by the diagram cards:** (1) banner *blocks* do not render under the capture's software GL (they show as stubs), so there is no live "wall of banners" shot; the banners are shown in the `ep07-name`/`ep07-marker` cards. (2) The anvil auto-fill, low-XP pause, and `/loominary click` walk need the full anvil/XP/bundle pipeline and a rendered field; those beats are carried by the `ep07-automation` card + narration. The mechanism itself (names → image) is proven on camera.
- **DECtalk pronunciations:** "CJK" spelled out; "Loominary click" as words. Copy/adapt `assemble-ep05.py` → `assemble-ep07.py`.
