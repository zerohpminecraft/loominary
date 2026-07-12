# Ep. 07 — Archiving Existing Map Art

**Target length:** 4–5 min

## Packaging

- **Title:** Copy ANY Minecraft Map Art You Find (archive & rebuild it)
- **Thumbnail text:** "FOUND IT. KEPT IT." over a crosshair on a framed map
- **Description:**
  > Found map art in the wild? Loominary captures any framed map — vanilla map art included — into a payload you can rebuild anywhere, edit in the web editor, or archive forever. Plus: reconstructing art from just a screenshot. Wiki: https://github.com/zerohpminecraft/loominary/wiki/Stealing-Map-Art

## Setup checklist

- [ ] A wall with several framed maps (mix of vanilla art and Loominary art)
- [ ] A second location to rebuild at

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** walking up to someone's impressive map wall | "Somebody built this. It's great. I'm taking a copy." |
| 0:20 | **manual:** crosshair on map, `/loominary import steal`, chat output | "Look at the map, one command: import steal. The map's sixteen thousand pixels are now a tile in my batch — works on any framed map, vanilla art included, because it's reading the same color data your client already renders." |
| 1:00 | **manual:** stealing three maps in sequence, `/loominary status` | "Chain it across a wall — every steal appends a tile. Status shows the collection." |
| 1:30 | **manual:** `/loominary export`, then rebuild montage at new location | "From here it's the standard flow: export schematics, place, scan, frame. The art now exists in two places." |
| 2:20 | **generated:** broll — Import state JSON into the web editor, palette cleanup | "Want to clean it up first? Save the batch, feed the JSON to the web editor, and you've got the full pixel editor — merge the compression noise away, re-export." |
| 3:10 | **manual:** screenshot of a map + `/loominary import header <banner> <file>` | "And the deep cut: if all you have is a *screenshot* of a Loominary map plus its manifest banner text, `import header` rebuilds the payload from the picture. Archival from evidence alone." |
| 3:50 | end card | "Archive responsibly — credit the artist. Finale next: everything that can go wrong, and how the whole thing actually works." |

## B-roll manifest

- generated: broll state-JSON import + editing flow
- manual: wall discovery, steal commands + chat, rebuild montage, import-header demo
