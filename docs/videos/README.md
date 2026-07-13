# Loominary video series — production cards

One card per episode. Each contains: title/thumbnail/description (paste into YouTube), a full narration script, a timestamped shot list, a setup checklist, and a **B-roll manifest** tagged `generated:` (produced by our tooling — regenerate on demand) vs `manual:` (film in your own client).

## Generating the B-roll

| Asset type | Command | Output |
|---|---|---|
| Web editor screen recordings | `cd web && npm run broll` | `web/e2e/media/**/*.webm` (1080p; transcode with `ffmpeg -i in.webm -c:v libx264 out.mp4`) |
| Web editor stills | `cd web && npm run shots` | `docs/wiki/assets/web/*.png` (2× DPI) |
| In-game stills | `scripts/game-shots.sh` | `docs/wiki/assets/game/*.png` |
| Status screens / map renders (PNG + GIF) | `./gradlew renderMapPreviews` | `docs/wiki/assets/game/status-*` |

## Episode list

1. [Your First Map Art in 10 Minutes](ep01-first-map-art.md) — the end-to-end hook
2. [The Web Editor Deep-Dive](ep02-web-editor.md) — tools, palette, dithering
3. [Animated Map Art](ep03-animated-art.md) — GIF → AV1 → playing map
4. [Giant Murals: Multi-Tile & Mux](ep04-murals-and-mux.md)
5. [Fully Autonomous Printing](ep05-autonomous-printing.md)
6. [Encrypted Map Art & Sharing](ep06-encryption-sharing.md)
7. [Archiving Existing Map Art](ep07-archiving.md)
8. [Tips, Troubleshooting & How It Works](ep08-how-it-works.md)

Recommended cadence: publish 1 and 2 together (hook + depth), then weekly.

## Fully generated cut (ep. 01)

Episode 1 can be produced end-to-end with no manual filming: `scripts/game-video.sh` records
the in-game segments (1080p X11 grab, markers at segment boundaries; audio capture works on a
desktop session but not under Xvfb), and `docs/videos/tools/assemble-ep01.py` cuts, captions
(burned + `.srt` sidecar), scores (captured audio when present, vanilla sound assets when the
capture is silent), and encodes the final MP4 (H.264 NVENC by default, `--codec av1` for
SVT-AV1). Output lands in `docs/videos/out/ep01/` (git-ignored). The in-game decode shot is
real: the harness places the LOOM carpet platform (header + noobline), scans it with an empty
map on camera, and the framed map decodes live.
