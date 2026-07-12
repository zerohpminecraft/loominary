# Troubleshooting & FAQ

## Status screens on maps

When a tile can't display its art yet, the mod paints a status screen instead of leaving the map blank or noisy:

| Screen | Meaning | Fix |
|---|---|---|
| ![decoding](assets/game/status-decoding.png) | AV1 decode in progress (live bar, ~0.1–0.25 s per frame) | Wait a few seconds |
| ![waiting](assets/game/status-waiting.png) | Composite animation or muxed tile awaiting sibling tiles (`TILES n/N`) | Scan every map of the grid once |
| ![locked](assets/game/status-locked.png) | Encrypted, no stored password matches | `/loominary password add <pw>` |
| ![error](assets/game/status-error.png) | Decode failed | Check the game log; usually a partial scan or an outdated mod |

## Common problems

**Other players don't see my image.**
Expected — Loominary is client-side. Viewers need the mod (and the password, if encrypted). Everyone else sees a normal carpet-colored map.

**The map shows raw carpet colors, not art.**
The decoder didn't engage. Checklist, in order: ① `/loominary status` shows the batch (state JSON in `config/`?). ② The map was scanned *after* the platform was completely placed — rescan with a fresh map if unsure. ③ Overflow banners placed and registered (`/loominary click` finishes with "Map decoded — auto-click complete."). ④ You're within 32 blocks (the decoder rescans every second). ⑤ Decoding is toggled ON (there's a keybind that flips raw/decoded view — easy to hit accidentally; it says "Loominary decoding OFF (raw maps)" in chat when off).

**My image looks fuzzy / colors are off.**
Palette limits. Work through the [Dithering & Color Matching](Dithering-and-Color-Matching) tuning recipe — saturation nudge, dither algorithm, chroma boost — and watch the coverage score. Bold shapes beat photographic noise.

**"Import aborted — over budget" in-game.**
The in-game import is the no-frills path and can't shrink images. The web editor solves every over-budget case: [palette reduction](Editor-Tools#requantize-filters-reduce), gentler dithering, [mux](Multi-Tile-and-Mux), or [lossy AV1](Animated-Art) for animations. Multi-tile in-game imports can also try the `mux` import flag.

**The anvil handler says `Paused — out of XP.`** (or banners/bundles)
Not an error. Restock and it resumes by itself. You need 1 level per banner. Details: [Anvil & Banners](Anvil-and-Banners).

**The anvil handler says `Stuck — re-export from the web editor (fresh salt), then /loominary load`.**
The server permanently rejected a banner name (rare; usually a chat filter). Re-export — every export carries a fresh salt, so all names change while the art doesn't — copy the new state JSON in (or `/loominary load` a saved copy), and discard the old renamed banners. Loading fresh state clears the halt.

**`/loominary click` says `Move closer to banners (N remaining)`.**
Its reach is ~4.5 blocks and it scans ±5 around you — walk the banner field and it works through them. `Hold your map to continue.` means you switched items mid-run.

**Animated tile plays for me, freezes or errors for a friend.**
Their mod predates the AV1 codec — art needs the mod version that made it, or newer.

**The animation stutters when I walk away and back.**
That's distance culling (32 blocks) doing its job — the tile rejoins its sync group on the correct frame; brief catch-up is normal.

**A command told me it was removed in v2.0.0.**
All image editing moved to the web editor. The [command reference](Command-Reference#removed-in-v200--where-it-went) maps each removed command to its replacement.

## FAQ

**Is this allowed on servers?**
The pipeline uses only vanilla mechanics — renaming banners, right-clicking them with maps, placing blocks — and rendering is client-side. The *automation* features (auto-click, walk-print, auto-fill) may fall under a server's automation rules: check them.

**Singleplayer? Realms?**
Yes and yes — fully client-side.

**What's "legal palette" vs "all shades"?**
Maps encode 4 brightness shades per base color; only 3 occur from real block placement. "All shades" adds the fourth (244 colors total) for fidelity, at the cost that the art can't exist as a physical build. [Details](Dithering-and-Color-Matching#palette-restriction).

**Why ~15 KB per tile?**
That's the sum of the vanilla channels: 8,176 B carpet + 2,016 B shade + 5,290 B banners. Compression makes it feel much bigger — typical images use 1.5–6 KB. Full math: [Codecs & Capacity](Codecs-and-Capacity).

**Where does everything live on disk?**

| Path (relative to game dir) | Purpose |
|---|---|
| `config/loominary_state.json` | the active batch |
| `loominary_data/` | source images for in-game import |
| `loominary_saves/` | named + auto batch saves |
| `loominary_exports/` | schematics and image exports |
| `loominary_chest_memory.json` | the carpet chest catalogue |

**How do I uninstall?**
Delete the jar. Art already in the world stays visible to other Loominary users; you'll see plain maps.
