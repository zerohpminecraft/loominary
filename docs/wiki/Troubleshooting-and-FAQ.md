# Troubleshooting & FAQ

## Status screens on maps

When a tile can't display its art yet, the mod paints a status screen instead of leaving the map blank:

| Screen | Meaning | Fix |
|---|---|---|
| ![decoding](assets/game/status-decoding.png) | AV1 decode in progress (live progress bar) | Wait a few seconds |
| ![waiting](assets/game/status-waiting.png) | A composite/muxed tile is waiting for sibling tiles | Scan every map of the grid once |
| ![locked](assets/game/status-locked.png) | Encrypted, and no stored password matches | `/loominary password add <pw>` |
| ![error](assets/game/status-error.png) | Decode failed | Check the game log; usually a truncated scan or an old mod version |

## Common problems

**Other players don't see my image.**
Expected — Loominary is client-side. Viewers need the mod (and the password, if encrypted). Without it they see a normal carpet-colored map.

**The map shows carpet colors, not my art.**
The mod didn't recognize the map. Checklist: the state JSON is loaded (`/loominary status`); the map was scanned *after* the platform was fully placed; overflow banners (if any) are placed and clicked; you're within 32 blocks of the frame.

**My image looks fuzzy or the colors are off.**
Minecraft's map palette is ~186 usable colors. In the [web editor](Web-Editor-Import): boost saturation slightly, try different dither algorithms, and watch the match-quality score. Bold shapes and clear colors always beat photographic noise.

**"Import aborted — over budget" in-game.**
The in-game `/loominary import` is the no-frills path and can't shrink images. Use the web editor: its palette reduction, dithering control, mux, and (for animations) lossy AV1 solve every over-budget case. Multi-tile in-game imports can also try the `mux` flag.

**The anvil handler says it's stuck.**
The server permanently rejected a banner name. Re-export from the web editor — each export gets a fresh salt so all names change — then `/loominary load` the new state (loading clears the halt). Discard already-renamed banners for that tile.

**I ran out of XP / banners / bundles at the anvil.**
The handler pauses cleanly and resumes when you restock.

**Animated tile plays for me but freezes for a friend.**
They're likely on an old mod version without the AV1 decoder — the art needs the version that made it (or newer).

**A removed command told me to use the web editor.**
v2.0.0 moved all image editing to the web editor. The [command reference](Command-Reference) has the full removed→replacement table.

## FAQ

**Is this allowed on servers?**
Loominary uses only vanilla mechanics: renaming banners, right-clicking them with maps, and placing blocks. Rendering happens client-side. Automation features (auto-click, walk-print) may fall under a server's automation rules — check them.

**Does it work in singleplayer / Realms?**
Yes and yes. It's fully client-side.

**Can I share or archive art?**
Yes — see [Encryption & Sharing](Encryption-and-Sharing) and [Archiving map art](Stealing-Map-Art).

**What's "legal palette" vs "all shades"?**
Maps encode 4 brightness shades per color but only 3 occur from real blocks. "All shades" unlocks the 4th for extra fidelity, at the cost that the art can't be reproduced as an actual block build.

**Why do budgets top out around 15 KB per tile?**
That's the total of the vanilla data channels: 8,192 B of carpet nibbles + 2,016 B of carpet-height shade data + ~5,200 B across 62 named banners. zstd compression means that's usually plenty — a typical image compresses to 1.5–6 KB.

**How big is the state file?**
Tens of KB typically; it's readable JSON at `config/loominary_state.json`.

**How do I uninstall?**
Delete the jar. Art already in the world remains visible to other Loominary users; you'll see plain maps.

## Runtime directories

| Path (relative to game dir) | Purpose |
|---|---|
| `config/loominary_state.json` | the active batch |
| `loominary_data/` | source images for in-game import |
| `loominary_saves/` | named batch saves |
| `loominary_exports/` | schematics and image exports |
