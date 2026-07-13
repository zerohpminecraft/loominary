#!/usr/bin/env bash
# Records the video-series in-game footage (docs/videos/) headlessly.
#
#   scripts/game-video.sh [outdir]        default outdir: docs/videos/out/raw
#
# Runs the dev client under Xvfb driven by DocsDriver (docs/tools/game-video.json),
# while ffmpeg records the X display and the game's audio through a private Pulse
# null sink (nothing plays on your speakers). The step script drops marker
# screenshots (run/screenshots/mk-*.png) at segment boundaries; their mtimes,
# written to markers.txt as offsets into the recording, are the cut points.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-docs/videos/out/raw}"
mkdir -p "$OUT" run/loominary_saves run/screenshots
cp docs/tools/srgb-state.json run/loominary_saves/srgb.json

command -v Xvfb >/dev/null || { echo "Xvfb required" >&2; exit 1; }
export LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5

# The screenshot harness keeps the client muted; this run wants real game audio.
touch run/options.txt
grep -q soundCategory_master run/options.txt \
    && sed -i 's/^soundCategory_master:.*/soundCategory_master:1.0/' run/options.txt \
    || printf 'soundCategory_master:1.0\n' >> run/options.txt
grep -q pauseOnLostFocus run/options.txt \
    || printf 'pauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\nguiScale:2\n' >> run/options.txt
restore_mute() { sed -i 's/^soundCategory_master:.*/soundCategory_master:0.0/' run/options.txt; }

rm -rf run/saves/docs-world
rm -f run/screenshots/mk-*.png

DISP=:93
Xvfb "$DISP" -screen 0 1920x1080x24 &
XVFB_PID=$!
sleep 1

SINK_ID=$(pactl load-module module-null-sink sink_name=loom_rec sink_properties=device.description=LoominaryRecord)
cleanup() {
    restore_mute
    pactl unload-module "$SINK_ID" 2>/dev/null || true
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

REC_START=$(date +%s.%N)
ffmpeg -hide_banner -loglevel error \
    -f x11grab -framerate 30 -video_size 1920x1080 -i "$DISP" \
    -f pulse -i loom_rec.monitor \
    -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p \
    -c:a aac -b:a 192k \
    "$OUT/game.mkv" -y &
FFMPEG_PID=$!

DISPLAY=$DISP PULSE_SINK=loom_rec ./gradlew runDocsVideo

kill -INT "$FFMPEG_PID"
wait "$FFMPEG_PID" || true

# Marker offsets (seconds into the recording), from screenshot mtimes.
: > "$OUT/markers.txt"
for f in run/screenshots/mk-*.png; do
    [ -e "$f" ] || continue
    name=$(basename "$f" .png)
    mt=$(stat -c %.3Y "$f")
    off=$(echo "$mt - $REC_START" | bc)
    printf '%s %s\n' "${name#mk-}" "$off" >> "$OUT/markers.txt"
done
sort -k2 -n -o "$OUT/markers.txt" "$OUT/markers.txt"
echo "Recorded $OUT/game.mkv"
cat "$OUT/markers.txt"
