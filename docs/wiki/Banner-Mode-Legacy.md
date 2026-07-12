# Banner-only mode (legacy)

Before carpets, there were banners. Loominary began as **"Banner Mod"** — a proof that you could smuggle an entire image through nothing but *item names*: rename banners at an anvil, right-click them with a map, and the server obligingly stores every name as a map decoration. The mod on the other side reads the names back and reconstructs the image. No blocks placed, no schematic, no build.

That original mode still works, and it's still the right tool in one situation: **when you can't place blocks** — protected areas, plot borders, places where a 128×128 carpet platform isn't happening.

## The trade-off

| | Carpet (default) | Banner-only |
|---|---|---|
| Capacity per tile | ~15,400 bytes | **~5,290 bytes** |
| Building required | 128×128 carpet platform | none |
| Banners needed | 1 manifest + overflow (often 0) | up to 63 |
| XP cost | 1 level per banner | 1 level per banner (×63) |

5.3 KB fits simple, bold images (and short animations) after compression — but photographs and dithered art usually need the carpet channel.

## How to use it

**From the web editor:** pick the **Banner** codec on the [export page](Web-Editor-Export). The ZIP contains only the state JSON — no schematics.

**In-game:** add `banners` to imports — `/loominary import <file> banners` or `/loominary import steal banners`.

Then the placement flow ([full guide](In-Game-Placement)) is just the anvil half:

1. Open an anvil with unnamed banners, empty bundles, and XP. The mod renames one banner per tick with the payload chunks (each name: a 2-char index + 48 CJK characters ≈ 84 bytes) and bundles them up.
2. Place the banners anywhere inside the 128×128 area the map covers.
3. Hold the map, `/loominary click`, and let it register every banner.
4. Lock the map at a cartography table, frame it, done. Banner pins are suppressed client-side; the mod decodes the names into your image.

## Why CJK characters?

An anvil name holds 50 characters. Base64 would carry ~36 bytes per banner; the CJK Unified Ideographs block (U+4E00 onward) survives server text normalization untouched and packs **14 bits per character** — 84 bytes per banner, 2.33× denser. That one trick is what made the original Banner Mod viable, and the same alphabet still carries the carpet mode's overflow banners today.
