#!/usr/bin/env bash
# Live in-game SMOKE test — boots a real headless Fabric client in a SANDBOXED,
# throwaway game directory and drives the mod through actual /loominary import.
#
#   scripts/smoke-test.sh
#
# Sandbox / safety guarantees:
#  - Runs under Xvfb with software GL — no window, no GPU needed.
#  - Uses its OWN game dir (run-smoke/, separate from the docs run/), created fresh
#    each run. It never touches your real Minecraft install or saves.
#  - The world is a superflat CREATIVE integrated-server world — no network
#    connection to any real server is ever made.
#  - Safe to run in CI (see the smoke-test job in .github/workflows/build.yml,
#    which is opt-in because the first run downloads Minecraft assets, ~500 MB).
#
# It executes docs/tools/smoke.json via DocsDriver, which writes a PASS/FAIL verdict
# to run-smoke/smoke-result.txt. This wrapper turns that verdict into the process exit
# code so CI goes red on a failed assertion or a boot that never reached the checks.
set -euo pipefail
cd "$(dirname "$0")/.."

RESULT="run-smoke/smoke-result.txt"

RUNNER=()
if command -v xvfb-run >/dev/null; then
    RUNNER=(xvfb-run -a -s "-screen 0 1280x720x24")
    export LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5
elif [[ -n "${DISPLAY:-}" ]]; then
    echo "NOTE: xvfb not installed — running on the live display ($DISPLAY)." >&2
else
    echo "No display and no xvfb-run — install xvfb (e.g. sudo apt install xvfb)" >&2
    exit 1
fi

# Fresh, isolated sandbox game dir every run.
rm -rf run-smoke
mkdir -p run-smoke/loominary_data run-smoke/screenshots

# The fixture image the smoke script imports (shared with the web/e2e rig).
cp web/e2e/fixtures/sample.png run-smoke/loominary_data/

# pauseOnLostFocus would freeze the client the moment the fake display loses focus.
printf 'pauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\nsoundCategory_master:0.0\n' \
    > run-smoke/options.txt

rm -f "$RESULT"

echo "== Booting sandboxed headless client for smoke test ==" >&2
"${RUNNER[@]}" ./gradlew runSmokeTest

if [[ ! -f "$RESULT" ]]; then
    echo "SMOKE FAIL: no verdict written ($RESULT missing) — client never reached the assertions." >&2
    exit 1
fi

VERDICT="$(cat "$RESULT")"
echo "== Smoke verdict: $VERDICT ==" >&2
case "$VERDICT" in
    PASS*) exit 0 ;;
    *)     exit 1 ;;
esac
