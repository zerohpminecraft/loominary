# Animated map art

Drop an animated GIF into the web editor and the whole pipeline stays the same — import, edit, export, place — except the map now *plays* the animation in-game, frames advancing on a wall-clock timer, synced across every viewer and every tile of a mural.

![Importing an animated GIF](assets/web/import-gif.png)

## How animations are encoded

Map bytes are precious: a single frame is 16 KB raw, and a tile's budget is ~15 KB *total* (compressed). Animations only work because consecutive frames are massively redundant, and Loominary exploits that with a real video codec:

- **Lossless AV1** (default): frames are encoded as an AV1 stream over the palette indices — pixel-exact playback, typically far smaller than storing frames raw. The exporter automatically picks whichever is smaller per tile (AV1 vs raw+zstd).
- **Lossy AV1** (opt-in toggle on the export page): for heavily dithered or noisy animations that refuse to compress, frames are encoded as actual lossy video and re-quantized to the palette on decode. The export preview runs the *identical* decode the mod will run, so what you see is byte-for-byte what players get.
- **Multi-tile animations are seamless**: a lossy animated mural is encoded as **one AV1 stream covering the whole composition**, then split across the tiles' payloads — no per-tile encode boundaries, so no visible seams. The trade-off: every tile of the mural must be scanned at least once before any of them can play (until then, tiles show a WAITING screen counting scanned tiles).

The same AV1 decoder binary runs in your browser (WebAssembly) and inside the mod's JVM — that's how the preview can promise exactness.

## Working with frames in the editor

The [editor](Web-Editor-Editing)'s frame strip gives you per-frame editing, cloning, blank frames, reordering, per-frame delays, and **stride/skip thinning** (keep every Nth frame / drop every Nth frame) when a GIF has more frames than budget.

![An animated composition on the export page](assets/web/export-animated.png)

## In-game behavior

- Playback starts as soon as the tile decodes. Heavy animations show a **DECODING** progress bar on the map while the codec runs (a few seconds for long animations), then start playing.
- Frames advance on wall-clock time with the GIF's original delays; distant tiles cull playback for performance.
- Multi-tile murals frame-sync: the grid never shows mixed frames.

## Budget tips

- Fewer distinct colors compress dramatically better — try a palette restriction at import.
- Dithering fights video compression; for animations, consider dithering OFF at import and let lossy mode handle gradients instead.
- Long GIFs: thin with stride/skip before reaching for lossy.
