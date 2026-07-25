# Ep. 05: Fully Autonomous Printing

**Target length:** ~8 min · **Audience:** eps. 1–4 viewers; the "watch the robot work" one

## Packaging

- **Title:** Loominary Makes Printing Carpet Automatic
  - Alt titles (pick at upload): "My Minecraft Client Builds Map Art By Itself" · "Hands Off: My Client Prints the Carpet Floor"
- **Thumbnail text:** "HANDS OFF." over the player mid-walk with carpets appearing in its wake
- **Description:**
  > /loominary walk print walks your player along the platform and drives a Litematica printer to lay every carpet, cataloguing your chests and restocking itself, and darting back to fix anything it skipped. One command; it does the rest. Needs IceTank's continuous litematica-printer fork: https://github.com/IceTank/litematica-printer · Wiki: https://github.com/zerohpminecraft/loominary/wiki/Autonomous-Printing

## Setup checklist (for the shoot)

- [ ] 1.21.4 client with Fabric API, Loominary, **Litematica**, and **IceTank's litematica-printer fork** (`litematica-printer-3.4.0-mc1.21.4.jar`)
- [ ] A carpet schematic exported from Loominary, loaded in Litematica, and its ghost **placed** in the world
- [ ] A chest station near the build, stocked with the carpet colours the art needs (double chests fine)
- [ ] Deliberately under-stock one colour, and use a band wider than the printer's reach, so the restock and missed-cell recovery happen on camera
- [ ] Long-take recording; plan a real-time stretch and a timelapse stretch

## Narration script + shot list

Cues are narration AND burned captions, verbatim. Register: dry and plain, concise and factual, no in-group asides. Burned captions are one row at a time.

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual/game (REAL):** hands visibly off the keyboard; the player walks by itself while carpets appear in its wake. No intro. | "I am not touching the keyboard. Watch the ground." |
| 0:20 | **generated:** card, ep05 title (`ep05-title`) | "This is Loominary printing a carpet floor by itself. It walks your player, lays every carpet, restocks when it runs low, and fixes what it misses. One command starts it." |
| 0:45 | **manual/game (REAL):** the four jars in the mods folder | "Before you try this, the one hard requirement. Placement is done by a Litematica printer, and it has to be the continuous fork, IceTank's, linked below. Base Litematica only places on right-click and will not lay a floor. Loominary drives the fork for you; you never configure it." |
| 1:25 | **manual/game (REAL):** export in Loominary, then load and place the ghost in Litematica | "Setup is the normal build flow. Export the schematic from Loominary, load it in Litematica, and place the ghost where you want the floor. The printer only prints a placement that exists." |
| 2:05 | **manual/game (REAL):** a row of chests, each stocked with one carpet colour | "Then a chest station near the site, stocked with the carpet colours the art uses. The mod pulls from these, so they need to hold enough." |
| 2:35 | **manual/game (REAL):** the player walks the chest row on its own, opening each chest in turn; chat shows "Cataloguing 16 chests nearby" then "Catalogue done, recorded 16 chests" | "First it takes stock. Before it lays a single carpet, Loominary walks your storage and opens every chest once, noting which colour lives where. That catalogue is what lets it restock on its own later, and it saves it, so it only does this the first time." |
| 3:00 | **manual/game (REAL):** typing `/loominary walk print`, the player heads back to the chests and fills its inventory, then walking to the floor begins | "Now the whole thing: one command. Walk print. It plans a serpentine path across the unbuilt floor. It is short on almost every colour, so it goes to the chests it just catalogued, fills up, and then starts laying. You do not run those steps." |
| 3:10 | **manual/game (REAL):** real-time stretch of the walk + printer laying columns; the start chat line visible | "It walks the centre of a band, the printer lays the columns within reach, it turns at the end and starts the next band. The pacing adapts: it crawls through dense, many-colour work and sprints across the sparse stretches, so it is not spamming placements the server will reject." |
| 4:10 | **manual/game (REAL):** timelapse of bands filling in | "Most of a floor is repetition, so this is a timelapse. In real time you would leave it running." |
| 4:50 | **manual/game (REAL):** the carpet in hand runs low, the player breaks off to a chest, fills up, and walks back to where it left off | "Run low on a colour and it does not stop. Using that catalogue, it walks to the chests that hold what it needs, refills, and carries on from where it left off. This is real carpet leaving your chests and going into the floor." |
| 5:35 | **manual/game (REAL):** a cell the printer skipped; the bot darts back and lays it | "The printer is a real physics placer, so reach and timing sometimes skip a cell. Before a band counts as done, it darts back and lays anything it missed. It tries not to leave holes." |
| 6:15 | **manual/game (REAL):** alt-tab away, the print continues; a close-up of the chat lines | "There is no progress bar. The chat is the readout: it tells you it started, when it is restocking, and when it is done. And it keeps printing while the window is not focused, so you can alt-tab and let it work." |
| 6:45 | **manual/game (REAL):** typing `/loominary stop` | "One stop command. Loominary stop halts this and every other automation at once." |
| 7:05 | **manual/game (REAL):** the print completes; the player stops walking; the done chat line | "When the floor is finished it stops itself and says so." |
| 7:25 | **generated:** card, caveats (`ep05-caveats`) | "The honest limits. It needs the printer fork, a placed schematic, and stocked chests. It walks in straight lines, so it wants open, flat, line-of-sight floor: walls and corners will defeat it. It self-heals most misses, but it is not perfect. And server automation rules differ, so check yours before you run it on someone else's server." |
| 7:55 | **manual/game (REAL):** scan an empty map on the finished floor, hang it in an item frame, and Loominary decodes it back to the animation | "Scan, frame, done. A floor that would have eaten an evening took a coffee break." |
| 8:15 | **generated:** card, ep05 end (`ep05-end`) | "Next episode: locking your art behind a password." |

## Cards

- **ep05 title card (`ep05-title`):** "Hands off." / "Loominary prints the floor for you", walking-footprints motif matching the thumbnail.
- **caveats card (`ep05-caveats`):** four short lines, printer fork required · schematic placed + chests stocked · open flat terrain · check your server's automation rules.
- **ep05 end card (`ep05-end`):** teases ep06 (encrypted art); series-consistent.

## B-roll manifest

- manual/game (REAL, the whole episode): cold-open hands-off walk, mods folder, export + load + place, chest station, `walk print`, real-time print stretch, timelapse, restock recovery, missed-cell recovery, alt-tab + chat close-up, `stop`, completion, scan/lock/frame reveal.
- generated: cards `cards/ep05-{title,caveats,end}.html` (via `record-cards.mjs`).

## Production notes

- **Register:** dry and plain, concise and factual, no in-group asides. Burned captions one row at a time (`wrap_chunks`). No em-dashes in cues.
- **Capture approach: headless capture tooling — BUILT and working (2026-07-14).** Real gameplay per [[feedback_real_capture]]; the capture runs end to end and ep05's game footage (`game-ep05.mkv`, 141.9 s) is a single authentic take. How it was done (see [[project_autoprint]] for the full recipe):
  1. **Mods on the dev run:** `build.gradle` gained a `-Pep05capture` gate that `modRuntimeOnly`-loads `run/localmods/{malilib-0.23.5, litematica-0.21.6, litematica-printer-3.4.0}-1.21.4.jar` into the docsVideo run. Gate with `hasProperty`, NOT `findProperty` (`-Pep05capture` sets `""`, which Groovy treats as falsy).
  2. **DocsDriver steps added:** `placeSchematic{w,d,origin}` builds a **real LOOM `.litematic` from the active loaded `PayloadState` tile** (`writeLoomSchematic`: header+compressed → carpet nibbles, 128×dataRows + a white noobline row) and places its ghost by reflection (`LitematicaSchematic.createFromFile` → `SchematicPlacement.createFor` → `SchematicPlacementManager.addSchematicPlacement`). Also `perspective:"third|first"` and `openInventory`.
  3. **`docs/tools/game-ep05.json`** (authored directly): gives carpet (noobline needs 128+ white → give 320), `/loominary load ball`, `placeSchematic` at an origin ≡ −64 mod 128 (map-cell aligned so it decodes), third-person + HUD + inventory, `/loominary walk print`, mid-print screenshots, then scan an empty map on the finished floor → frame it → Loominary LOOM-decodes it back to the "Bouncing Ball" animation.
  4. **Bigger schematic + real restock (v3, the current cut).** The `demo` LOOM state (`web/scripts/gen-docs-anim-states.mjs`, ~1.3 KB payload → a 128×23 carpet floor, ~2.9 k carpets) is large enough that a **survival** print drains inventory and forces real restocks on camera. `stockChests` (a DocsDriver step) places a row of **16 single chests north of the platform, each filled with one carpet colour** on the server thread (`server.execute` — a render-thread `setBlockState`+read-back left the chests empty). The scene: understock the player, `gamemode survival` + infinite saturation/resistance, `/loominary carpets catalogue` (the bot tours all 16 chests and records them; `waitIdle` blocks until it finishes), then `/loominary walk print` (restocks from the catalogued chests, prints, and restocks again as loads advance — the hotbar carpet counts visibly drop and refill), then `gamemode creative` for the scan → item-frame → decode reveal.
     - **Bug fixed to make this work:** `CarpetFillHandler.approachStep` bailed with "inventory full" whenever `totalNeed==0`, which is always true during a catalogue pass (no fill goal) — so the catalogue quit before opening a single chest and chest memory never populated, and every later restock reported "no reachable chest". Guarded that early-exit with `!cataloguing` (matching `scanStep`/`grabStep`, which already were catalogue-aware). This fixes autonomous catalogue+restock for real users too, not just the capture.
     - **What captured:** catalogue tour of the 16 chests, the restock trips (walk to chests, fill, return), the survival print consuming and refilling real carpet, third-person walk with carpet in hand + full HUD, Litematica's placement bounding box, chat readout, and the scan → frame → decode reveal. **Still not captured:** Loominary's *own* covered-region overlay boxes (`renderCoveredRegions`) — they render only during a fill; Litematica's cyan/red placement box carries the "show the boxes" beat.
  5. `scripts/capture-ep05.sh <docsScript> <outname>` is the wrapper (Xvfb + x11grab + Pulse, `-Pep05capture`, stages `demo`/`ball` states into `run/loominary_saves`, wipes chest memory so the catalogue always runs). It writes `game-ep05.mkv` + `markers-game-ep05.txt`; `assemble-ep05.py` slices by those markers (one-row captions kept). Diagnostic scripts: `game-ep05-diag.json` (mechanics), `game-ep05-frame.json` (camera framing).
- **Fact-check corrections (verified against current code, 2026-07-14):**
  - **Command syntax:** the autonomous command is **`/loominary walk print`** (under `walk`, not a top-level `print`); `/loominary walk print <width>` sets band width (1–64); `/loominary walk print stop` stops it. Panic button is **`/loominary stop`** (halts print + fill + catalogue + walk). The carpet commands `/loominary carpets catalogue|balance|fill` exist but are **optional**, `walk print` catalogues and restocks itself on first shortfall. The script leads with just `walk print` accordingly.
  - **No HUD or progress bar.** The only readout is **chat messages** (sent to chat, not the action bar) plus stdout log lines. Do NOT show or imply a progress overlay. Verbatim chat strings to feature (these are the app's own text; the em-dashes inside them are verbatim): start `§a[Loominary] Auto-print started — serpentine band <N> wide.`; restock failure `§c[Loominary] Auto-print: couldn't gather enough carpet (out of stock, or no chests nearby). Stopping.`; done `§a[Loominary] Auto-print done — nothing left to build nearby.`; stop `§e[Loominary] Auto-print stopped.`; no-fork warning `§e[Loominary] Warning: no Litematica printer found — carpets won't be placed. Install the litematica-printer fork. Walking anyway.`; no-Litematica `§c[Loominary] Litematica is not installed.`
  - **Hard dependency:** the **continuous litematica-printer fork** (aleksilassila/IceTank, `litematica-printer-3.4.0-mc1.21.4.jar`), not base Litematica. Loominary sets the fork's `PRINT_MODE` config directly via reflection (`LitematicaBridge`). Base Litematica's Easy Place only places on right-click and will NOT autonomously lay a floor. Loominary is client-side-only.
  - **Preflight** `/loominary walk printer on|off` verifies the binding (prints `printer → ON (now reads true)` or `couldn't toggle the printer — litematica-printer fork not found.`). Decision: **not shown on camera**; the dependency is covered in narration.
  - **Prerequisites:** Litematica + fork installed; schematic exported, loaded, and its ghost **placed**; chests stocked with the needed carpet colours. **Band width vs reach:** default band width 5; "8 works well with a printer range ≥ 4"; keep band width **≤ 2× the printer's `PRINTING_RANGE`** or far-edge columns get skipped and recovery slows.
  - **Movement is straight-line for the print** (`WaypointMover`, not a pathfinder): assumes open, flat, line-of-sight floor; walls/corners defeat it (it goes `stuck` → replan → may give up). A* (`Pathfinder`) is used only for chest-station navigation. Be honest about this in the caveats beat.
  - **Recovery is real but not perfect:** missed-cell recovery revisits skipped cells (and can break a wrong-colour carpet up to 3×, a printer-fork glitch); restock retries are capped at 3, after which it stops with the "couldn't gather enough carpet" message; a ~1-hour watchdog stops runaway sessions. Do NOT claim "never gets stuck," "never misses," or "works anywhere."
  - **It is RELEASED** (shipped in v1.24.0; current `mod_version` 2.1.1). The old `project_autoprint` memory said "no release cut", that is stale; treat it as shipped and field-tested. It also survives alt-tab (suppresses pause-on-lost-focus, releases/recaptures the cursor), the "alt-tab and let it work" beat is accurate.
- **DECtalk pronunciations** (synth only; captions keep normal spelling): "Litematica" → **"lite-o-matica"** (check it reads cleanly; else "lite matica"); "IceTank" → **"ice tank"**; "serpentine" reads fine; spell "walk print" as two words.
- Copy/adapt `assemble-ep04.py` → `assemble-ep05.py`: keep the one-row caption logic and the `gamevid` kind (pointed at `game-ep05.mkv`).
