# Ep. 03 — Animated Map Art (yes, really)

**Target length:** 6 min · **Audience:** eps. 1–2 viewers; the shareable one

## Packaging

- **Title:** Animated GIFs as Minecraft Map Art — on VANILLA servers
- **Thumbnail text:** "IT MOVES." over a framed map mid-animation (motion blur arrows)
- **Description:**
  > Loominary encodes animated GIFs with a real AV1 video codec and plays them back on vanilla Minecraft maps — frame-synced for every viewer. How it works, how to fit more frames, and lossless vs lossy explained. Editor: https://zerohpminecraft.github.io/loominary/ · Wiki: https://github.com/zerohpminecraft/loominary/wiki/Animated-Art

## Setup checklist

- [ ] Two GIFs: a clean simple one (pixel-art loop) and a heavy dithered one (lossy showcase)
- [ ] In-game wall + frame ready; second account or friend for the sync shot (optional but great)

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** framed map playing an animation, no intro, 5 s hold | "That's a vanilla map. It's playing a GIF. Let me explain." |
| 0:20 | **generated:** broll — dropping GIF on import page; still `import-gif.png` | "Drop an animated GIF into the web editor and the pipeline is identical to a static image — except now there's a frame strip." |
| 0:45 | **generated:** broll — frame strip: scrub, per-frame delay, stride/skip | "Scrub frames, tweak delays, thin a too-long GIF with stride and skip. Every frame is editable like any static image." |
| 1:30 | diagram (make from `docs/dev/pipeline.md` material) | "Here's the problem: one frame is sixteen kilobytes and a map tile carries about fifteen — total. Animations only work because frames repeat themselves, and nothing exploits that better than an actual video codec. Loominary encodes your frames as AV1 — the codec behind YouTube — losslessly, over the palette indices." |
| 2:20 | **generated:** broll — export page animated; still `export-animated.png` | "The export page shows the result: sixty frames fitting where one raw frame wouldn't. If your GIF is heavily dithered and refuses to compress, flip on lossy mode — and because the preview runs the exact same decoder the mod uses, what you see is literally byte-for-byte what players get." |
| 3:20 | **manual:** in-game placement montage (fast recap of ep. 1 flow) | "Place, scan, lock, frame — exactly like episode one." |
| 3:50 | **generated:** `status-decoding-anim.gif` render + **manual:** the map showing the progress bar | "Heavy animations take a few seconds to decode — the map shows a live progress bar, then starts playing." |
| 4:20 | **manual:** the animation playing; two players/accounts viewing simultaneously | "Playback runs on wall-clock time, so every viewer sees the same frame at the same moment. Murals stay in sync across every tile too." |
| 5:00 | **generated:** broll — palette restriction on the GIF, byte count dropping | "Budget tips: fewer distinct colors is the biggest lever. Skip import-dithering for animations — dither noise is what video codecs hate most — and let lossy mode handle the gradients instead." |
| 5:40 | end card | "Next: wall-sized murals, and what happens when one tile's bytes don't fit." |

## B-roll manifest

- generated: broll GIF-import + frame-strip + export flows; stills `import-gif.png`, `export-animated.png`; `status-decoding-anim.gif` (from `./gradlew renderMapPreviews`)
- manual: cold-open animation loop, placement recap, decode progress on a real map, two-viewer sync shot
