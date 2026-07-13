#!/usr/bin/env bash
# Generates the wiki's in-game screenshots headlessly.
#
#   scripts/game-shots.sh [--keep-world]
#
# Runs a real Fabric dev client under Xvfb with software GL, drives it with
# DocsDriver (docs/tools/game-shots.json), then copies the screenshots into
# docs/wiki/assets/game/.
#
# Notes:
#  - First run downloads Minecraft assets via Loom (~500 MB, one-time).
#  - llvmpipe (software GL) renders slowly; a full run takes a few minutes.
#  - The world (run/saves/docs-world) is deleted before each run for
#    reproducibility unless --keep-world is passed.
#  - Audio errors in the log are normal under Xvfb and harmless.
set -euo pipefail
cd "$(dirname "$0")/.."

RUNNER=()
if command -v xvfb-run >/dev/null; then
    RUNNER=(xvfb-run -a -s "-screen 0 1920x1080x24")
    export LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5
elif [[ -n "${DISPLAY:-}" ]]; then
    echo "NOTE: xvfb not installed — running on the live display ($DISPLAY)." >&2
    echo "      A Minecraft window will open for a few minutes. Install xvfb for headless runs." >&2
else
    echo "No display and no xvfb-run — install xvfb (e.g. sudo apt install xvfb)" >&2
    exit 1
fi

# Fixtures the script imports (shared with the web rig for identical art).
mkdir -p run/loominary_data run/screenshots
cp web/e2e/fixtures/sample.png web/e2e/fixtures/sample-wide.png run/loominary_data/

# sRGB demo state for the full-color shot (regenerate: node web/scripts/gen-docs-srgb-state.mjs).
mkdir -p run/loominary_saves
cp docs/tools/srgb-state.json run/loominary_saves/srgb.json

# Fresh world each run keeps shots reproducible.
if [[ "${1:-}" != "--keep-world" ]]; then
    rm -rf run/saves/docs-world
fi
rm -f run/screenshots/*.png

# pauseOnLostFocus would freeze the game the moment the fake display loses focus.
mkdir -p run
if [[ ! -f run/options.txt ]] || ! grep -q pauseOnLostFocus run/options.txt; then
    printf 'pauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\nguiScale:2\n' >> run/options.txt
fi
# Keep the harness silent regardless of system volume.
grep -q 'soundCategory_master' run/options.txt || printf 'soundCategory_master:0.0\n' >> run/options.txt

"${RUNNER[@]}" ./gradlew runDocsShots

mkdir -p docs/wiki/assets/game
cp run/screenshots/*.png docs/wiki/assets/game/
echo "Screenshots:"
ls -la docs/wiki/assets/game/
