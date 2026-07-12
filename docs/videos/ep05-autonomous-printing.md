# Ep. 05 — Fully Autonomous Printing

**Target length:** 5–6 min · the "watch the robot work" episode

## Packaging

- **Title:** My Minecraft Client Builds Map Art By Itself
- **Thumbnail text:** "HANDS OFF." over the player mid-walk placing carpets
- **Description:**
  > /loominary walk print: the mod walks your player along the platform, places every carpet from the Litematica placement, restocks from chests, and recovers anything it missed. Setup with IceTank's printer: https://github.com/IceTank/litematica-printer · Wiki: https://github.com/zerohpminecraft/loominary/wiki/Autonomous-Printing

## Setup checklist

- [ ] IceTank's litematica-printer installed alongside Litematica
- [ ] A large placement (2×2 mural minimum — bigger is more impressive)
- [ ] Chests stocked with carpets near the site
- [ ] Long-take recording; plan a timelapse segment

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** player standing still, then starts walking + carpets appearing in its wake | "I'm not touching the keyboard. Watch the ground." |
| 0:25 | **manual:** mods folder showing the printer jar | "One extra ingredient this episode: a Litematica printer — the commonly used build is IceTank's fork, linked below. Loominary drives it; you never configure it." |
| 0:55 | **manual:** `/loominary carpets catalogue` at the chest wall, chat output | "Setup, three commands. First: catalogue — the mod scans your chests and memorizes which one holds every carpet color." |
| 1:25 | **manual:** `/loominary carpets balance` inventory shuffle | "Second: balance — it arranges your inventory to match the schematic's material list." |
| 1:45 | **manual:** `/loominary walk print`, walking begins | "Third: walk print. And now we narrate over a robot." |
| 2:00 | **manual:** long take → timelapse of bands filling in | "It walks a band, prints the columns around it, turns at the end, starts the next band. The pacing adapts to what the server actually accepts — it's not spamming placements into rejection. Run dry on a color? It walks to the right chest and restocks itself. Lag leave a hole? Missed cells get revisited before a band counts as done." |
| 3:30 | **manual:** chat progress lines close-up | "Progress lands in chat per band. You can alt-tab — it doesn't need window focus." |
| 3:50 | **manual:** `/loominary stop` demonstration | "`/loominary stop` is the big red button for every automation, this one included." |
| 4:10 | **manual:** print finishes, mod stops walking | "It stops itself when the placement is complete." |
| 4:25 | **manual:** scan maps, frame, mural reveal | "Scan, lock, frame — and a build that would've eaten an evening took a coffee break. One caveat: automation rules differ per server. Read yours." |
| 5:10 | end card | "Next: locking your art behind a password." |

## B-roll manifest

- generated: `docs/wiki/assets/game/import-chat.png` style chat stills as inserts (rerun `scripts/game-shots.sh` with print commands if desired)
- manual: essentially everything — this episode is real footage by nature (cold open, catalogue/balance/print, timelapse, restock moment, stop demo, reveal)
