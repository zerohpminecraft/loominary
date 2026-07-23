#!/usr/bin/env bash
# Release smoke-test pass — runs EVERY in-game smoke scenario in docs/tools/smoke/,
# records a video of each, and produces an approval bundle for a human to sign off on
# before a release. This is the "watch the mod actually do the thing in-game" gate.
#
#   scripts/smoke-release.sh
#
# For each docs/tools/smoke/<scenario>.json it invokes
#   scripts/smoke-test.sh --video <scenario>
# which boots a fresh SANDBOXED headless client (own game dir, integrated-server
# superflat world, no network), asserts the PayloadState verdict, and writes
#   docs/videos/out/smoke/<scenario>.mp4
#
# Output:
#   docs/videos/out/smoke/*.mp4          — one recording per scenario, to be reviewed
#   docs/videos/out/smoke/APPROVAL.md    — checklist manifest (verdicts + review boxes)
#
# The script exits non-zero if any scenario's assertions fail. A green run still
# requires a HUMAN to watch each video and tick the boxes in APPROVAL.md — the
# assertions prove the state, the video proves the on-screen behavior.
set -euo pipefail
cd "$(dirname "$0")/.."

OUTDIR="docs/videos/out/smoke"
mkdir -p "$OUTDIR"
MANIFEST="$OUTDIR/APPROVAL.md"

shopt -s nullglob
SCENARIOS=(docs/tools/smoke/*.json)
[[ ${#SCENARIOS[@]} -gt 0 ]] || { echo "no scenarios in docs/tools/smoke/" >&2; exit 1; }

{
    echo "# Loominary smoke-test release approval"
    echo
    echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Commit: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo
    echo "Watch each video and confirm the in-game behavior is correct, then tick the box."
    echo
} > "$MANIFEST"

FAILED=0
for path in "${SCENARIOS[@]}"; do
    scenario="$(basename "$path" .json)"
    echo "########## SMOKE SCENARIO: $scenario ##########" >&2
    if scripts/smoke-test.sh --video "$scenario"; then
        verdict="$(cat "run-smoke/${scenario}.txt" 2>/dev/null || echo 'PASS (no file)')"
        status="PASS"
    else
        verdict="$(cat "run-smoke/${scenario}.txt" 2>/dev/null || echo 'FAIL (no verdict)')"
        status="FAIL"
        FAILED=1
    fi
    # Only link a video that actually exists — a dead link in the approval bundle
    # means a reviewer ticks a box for footage nobody ever saw.
    if [[ -s "$OUTDIR/${scenario}.mp4" ]]; then
        video="[${scenario}.mp4](${scenario}.mp4)"
    else
        video='**MISSING — not recorded**'
        status="FAIL"; FAILED=1
    fi
    printf -- '- [ ] **%s** — %s — assertions: `%s` — video: %s\n' \
        "$scenario" "$status" "$verdict" "$video" >> "$MANIFEST"
done

echo >> "$MANIFEST"
echo "Approve this release only when every box above is ticked." >> "$MANIFEST"

echo "== Approval bundle written to $MANIFEST ==" >&2
cat "$MANIFEST" >&2
exit "$FAILED"
