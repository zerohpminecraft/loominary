#!/usr/bin/env bash
# Records ep05's autonomous-printing in-game footage headlessly.
#
#   scripts/capture-ep05.sh <docsScript> <outname>
#   scripts/capture-ep05.sh docs/tools/game-ep05.json game-ep05
#
# Like scripts/game-video.sh, but loads Litematica + the printer fork (-Pep05capture)
# so /loominary walk print actually places carpet, and stages the demo LOOM state into
# run/loominary_saves so `/loominary load demo` works. Writes <out>/<outname>.mkv and
# <out>/markers-<outname>.txt (screenshot mtimes → offsets into the recording).
set -euo pipefail
cd "$(dirname "$0")/.."

SCRIPT="${1:-docs/tools/game-ep05.json}"
NAME="${2:-game-ep05}"
OUT="docs/videos/out/raw"
mkdir -p "$OUT" run/loominary_saves run/screenshots run/localmods
cp docs/tools/anim-demo-state.json run/loominary_saves/demo.json
cp docs/tools/anim-ball-state.json run/loominary_saves/ball.json
cp docs/tools/anim-demo-enc-state.json run/loominary_saves/demoenc.json 2>/dev/null || true
cp docs/tools/banner-art-state.json run/loominary_saves/bannerart.json 2>/dev/null || true

command -v Xvfb >/dev/null || { echo "Xvfb required" >&2; exit 1; }
export LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5

touch run/options.txt
grep -q soundCategory_master run/options.txt \
    && sed -i 's/^soundCategory_master:.*/soundCategory_master:1.0/' run/options.txt \
    || printf 'soundCategory_master:1.0\n' >> run/options.txt
grep -q pauseOnLostFocus run/options.txt \
    || printf 'pauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\nguiScale:2\n' >> run/options.txt
restore_mute() { sed -i 's/^soundCategory_master:.*/soundCategory_master:0.0/' run/options.txt; }

rm -rf run/saves/docs-world
rm -f run/screenshots/mk-*.png
# Start each shoot with fresh chest memory so the catalogue pass always runs on camera.
rm -f run/config/loominary_chest_memory.json 2>/dev/null || true
rm -f run/config/loominary_passwords.json 2>/dev/null || true

DISP=:94
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
    "$OUT/$NAME.mkv" -y &
FFMPEG_PID=$!

DISPLAY=$DISP PULSE_SINK=loom_rec ./gradlew runDocsVideo -Pep05capture "-PdocsScript=$PWD/$SCRIPT"

kill -INT "$FFMPEG_PID"
wait "$FFMPEG_PID" || true

: > "$OUT/markers-$NAME.txt"
for f in run/screenshots/mk-*.png; do
    [ -e "$f" ] || continue
    n=$(basename "$f" .png)
    mt=$(stat -c %.3Y "$f")
    off=$(echo "$mt - $REC_START" | bc)
    printf '%s %s\n' "${n#mk-}" "$off" >> "$OUT/markers-$NAME.txt"
done
sort -k2 -n -o "$OUT/markers-$NAME.txt" "$OUT/markers-$NAME.txt"
echo "Recorded $OUT/$NAME.mkv"
cat "$OUT/markers-$NAME.txt"
