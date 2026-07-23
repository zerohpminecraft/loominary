#!/usr/bin/env bash
# Live in-game SMOKE test — boots a real headless Fabric client in a SANDBOXED,
# throwaway game directory and drives the mod through actual in-game behavior.
#
#   scripts/smoke-test.sh [--video] [scenario]
#
#   scenario   basename of a scenario script in docs/tools/smoke/ (default: import-basic)
#   --video    also record an .mp4 of the run (for human review / release approval)
#              into docs/videos/out/smoke/<scenario>.mp4
#
# Sandbox / safety guarantees:
#  - Runs under Xvfb with software GL — no window, no GPU needed.
#  - Uses its OWN game dir (run-smoke/, separate from the docs run/), created fresh
#    each run. It never touches your real Minecraft install or saves.
#  - The world is a superflat CREATIVE integrated-server world — no network
#    connection to any real server is ever made.
#  - Safe to run in CI (see the smoke-test job in .github/workflows/build.yml).
#
# The scenario drives DocsDriver, which writes a PASS/FAIL verdict to
# run-smoke/<scenario>.txt. This wrapper turns that verdict into the process exit
# code (missing verdict = failure, so a boot that never reached the assertions fails).
set -euo pipefail
cd "$(dirname "$0")/.."

VIDEO=0
SCENARIO=""
for arg in "$@"; do
    case "$arg" in
        --video) VIDEO=1 ;;
        -*)      echo "unknown flag: $arg" >&2; exit 2 ;;
        *)       SCENARIO="$arg" ;;
    esac
done
SCENARIO="${SCENARIO:-import-basic}"

SCRIPT="docs/tools/smoke/${SCENARIO}.json"
[[ -f "$SCRIPT" ]] || { echo "no such scenario: $SCRIPT" >&2; exit 2; }
RESULT="run-smoke/${SCENARIO}.txt"

command -v Xvfb >/dev/null || command -v xvfb-run >/dev/null \
    || { echo "xvfb required (e.g. sudo apt install xvfb)" >&2; exit 1; }
export LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5

# Fresh, isolated sandbox game dir every run.
rm -rf run-smoke
mkdir -p run-smoke/loominary_data run-smoke/screenshots

# Fixtures the scenarios import (shared with the web/e2e rig for identical art).
cp web/e2e/fixtures/sample.png run-smoke/loominary_data/
cp web/e2e/fixtures/sample-wide.png run-smoke/loominary_data/ 2>/dev/null || true

# pauseOnLostFocus would freeze the client the moment the fake display loses focus.
# Video runs want audible sound; headless verdict runs stay muted.
MASTER=0.0; [[ "$VIDEO" == 1 ]] && MASTER=1.0
printf 'pauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\nguiScale:2\nsoundCategory_master:%s\n' \
    "$MASTER" > run-smoke/options.txt

rm -f "$RESULT"

GRADLE_ARGS=(runSmokeTest -PsmokeScript="$PWD/$SCRIPT" -PsmokeResult="$PWD/$RESULT")

if [[ "$VIDEO" == 1 ]]; then
    # Record the run: dedicated Xvfb display + ffmpeg x11grab, mirroring game-video.sh.
    # Audio (optional) via a private Pulse null sink so nothing plays on your speakers.
    command -v ffmpeg >/dev/null || { echo "--video needs ffmpeg" >&2; exit 1; }
    OUTDIR="docs/videos/out/smoke"; mkdir -p "$OUTDIR"
    OUT="$OUTDIR/${SCENARIO}.mp4"
    W=1280; H=720
    GRADLE_ARGS+=(-PsmokeW="$W" -PsmokeH="$H")

    DISP=:94
    Xvfb "$DISP" -screen 0 "${W}x${H}x24" & XVFB_PID=$!
    sleep 1
    AUDIO=(); SINK_ID=""
    if command -v pactl >/dev/null; then
        SINK_ID=$(pactl load-module module-null-sink sink_name=loom_smoke \
            sink_properties=device.description=LoominarySmoke 2>/dev/null || true)
        [[ -n "$SINK_ID" ]] && AUDIO=(-f pulse -i loom_smoke.monitor -c:a aac -b:a 160k)
    fi
    cleanup() {
        [[ -n "$SINK_ID" ]] && pactl unload-module "$SINK_ID" 2>/dev/null || true
        kill "$XVFB_PID" 2>/dev/null || true
    }
    trap cleanup EXIT

    echo "== Recording sandboxed smoke run '$SCENARIO' → $OUT ==" >&2
    ffmpeg -hide_banner -loglevel error \
        -f x11grab -framerate 30 -video_size "${W}x${H}" -i "$DISP" \
        "${AUDIO[@]}" \
        -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
        "$OUT" -y & FFMPEG_PID=$!

    set +e
    DISPLAY="$DISP" ${SINK_ID:+PULSE_SINK=loom_smoke} ./gradlew "${GRADLE_ARGS[@]}"
    set -e
    kill -INT "$FFMPEG_PID" 2>/dev/null || true
    wait "$FFMPEG_PID" 2>/dev/null || true
    echo "== Wrote $OUT ==" >&2
else
    echo "== Booting sandboxed headless client for smoke scenario '$SCENARIO' ==" >&2
    xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew "${GRADLE_ARGS[@]}"
fi

if [[ ! -f "$RESULT" ]]; then
    echo "SMOKE FAIL [$SCENARIO]: no verdict written — client never reached the assertions." >&2
    exit 1
fi
VERDICT="$(cat "$RESULT")"
echo "== [$SCENARIO] $VERDICT ==" >&2
case "$VERDICT" in
    PASS*) exit 0 ;;
    *)     exit 1 ;;
esac
