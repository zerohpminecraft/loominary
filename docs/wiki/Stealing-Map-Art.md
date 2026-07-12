# Archiving existing map art

Found map art in the world — Loominary-made or ordinary vanilla map art — and want a copy of your own? The mod can capture any framed map's pixels as a payload in your batch, ready to re-encode, edit in the web editor, or rebuild elsewhere.

## Capturing a framed map

1. Look at the framed map (crosshair on it, within reach).
2. Run:

   ```
   /loominary import steal
   ```

3. The map's 128×128 colors become a new carpet-encoded tile in your batch, titled `map_<id>`. Steal several maps in sequence — each becomes its own tile.

From there it's the normal flow: `/loominary export` writes the schematic(s), then [place and scan](In-Game-Placement) at the new location.

There's also `/loominary import steal banners` for a banner-only capture ([legacy mode](Banner-Mode-Legacy)).

## Editing what you captured

Captured art often needs cleanup (compression noise, colors you'd rather merge). In-game editing was removed in v2 — instead, round-trip through the web editor:

1. `/loominary save my-capture` — write the batch to `loominary_saves/my-capture.json`.
2. Open the [web editor](https://zerohpminecraft.github.io/loominary/) → **Import state JSON** → pick that file.
3. Edit freely, re-export, reinstall the state.

## Reconstructing from a screenshot

The exotic one: if you have a **screenshot of a map** plus its manifest banner string (the LC/LS name), Loominary can rebuild the payload without ever having decoded it in-game:

```
/loominary import header <banner-string> <screenshot.png>
```

The screenshot (a straight-on 128×128 map capture in `loominary_data/`) supplies the carpet nibbles; the banner string supplies the manifest. Useful for archiving art you only have pictures of.

## Debug helpers

- `/loominary dumpcarpet` — dump the carpet-channel mapping for the map you're looking at
- `/loominary dumppalette` — dump the full map palette with byte values
