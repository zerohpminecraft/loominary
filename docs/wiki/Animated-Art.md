# Animated map art

Drop an animated GIF into the web editor and the pipeline stays the same — import, edit, export, place — except the framed map now *plays* the animation, frames advancing on a wall-clock timer, synchronized for every viewer and across every tile of a mural.

![Importing an animated GIF](assets/web/import-gif.png)

## Importing animation

GIF frames are fully composited on import (disposal methods handled), with each frame's own delay preserved (clamped to ≥10 ms; frames without timing default to 100 ms). The import preview shows frame 1; **Proceed** then quantizes every frame in a parallel worker pool with a per-frame progress bar. Decoding uses the browser's `ImageDecoder` (Chrome/Edge 94+ — other browsers import the first frame only).

## Editing frames

The [editor's frame strip](Editor-Tools#animation-frames) gives you playback, scrubbing, per-frame delays (**,** / **.** nudge ±10 ms; an "all" button syncs every frame), clone/blank/delete/reorder, and **stride/skip thinning** — keep or drop every *n*-th frame with delays merged so overall timing is preserved. Every tool and overlay works per frame; requantize, filters, and palette merges optionally apply across **all frames**.

![The frame strip](assets/web/editor-frames.png)

## How animations fit in the budget

A raw frame is 16,384 bytes; a tile's whole [budget](Codecs-and-Capacity) is ~15 KB *compressed*. Animation only works because consecutive frames are hugely redundant, and Loominary attacks that with an actual video codec:

- **Lossless AV1** (automatic): frames are encoded as an AV1 stream over the palette indices — pixel-exact, and the exporter uses it whenever it beats raw-frames-plus-zstd for the tile.
- **Lossy AV1** (the "⚡ Lossy animation" toggle, quality 1–100, default 60): real lossy video, re-quantized to the palette on decode. For dithered or noisy animations that refuse lossless compression, it's transformative — and honest: the export preview runs the *same decoder binary* the mod ships (compiled to WebAssembly in the browser, to JVM bytecode in the mod), so the preview is byte-for-byte what players will see. The panel reports the measured pixel-difference percentage at your chosen quality.
- **Multi-tile animations are seamless**: a lossy animated mural encodes as **one AV1 stream across the whole composition**, split byte-wise over the tiles — no per-tile encode boundaries, no seams. The trade-off is all-or-nothing decoding: every tile must be scanned once before any of them plays; waiting tiles show a WAITING screen counting scanned siblings.

## In-game playback

- **Decode**: heavy animations take a few seconds off-thread; the map shows a live **DECODING** progress bar, then starts playing.

  ![decode progress](assets/game/status-decoding-anim.gif)

- **Timing**: frames advance on wall-clock time using the GIF's own per-frame delays (loop counts honored).
- **Sync**: maps are grouped by author + title + grid — an entire mural advances as one unit, so the wall never shows mixed frames, and two players standing together see the same frame.
- **Culling**: tiles farther than 32 blocks pause; they rejoin the sync group, on the correct frame, as you approach.

## Making animations fit

1. **Fewer distinct colors** is the biggest lever — restrict the palette at import.
2. **Keep import dithering off** (the default): dither noise varies frame to frame, and temporal noise is what video codecs handle worst. Let lossy mode render gradients instead.
3. **Thin frames** with stride/skip before dropping quality — 15 fps reads as smooth on a map.
4. Then the lossy toggle, walking quality down from 60 until it fits.

![An animated composition on the export page](assets/web/export-animated.png)
