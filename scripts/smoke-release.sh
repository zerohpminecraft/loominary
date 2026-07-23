#!/usr/bin/env bash
# Release smoke-test pass — runs EVERY in-game smoke scenario in docs/tools/smoke/,
# records a video of each, and maintains an approval manifest a human signs off on
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
#   docs/videos/out/smoke/*.mp4  — one recording per scenario, to be reviewed (git-ignored)
#   SMOKE_APPROVAL.md            — the manifest, COMMITTED so sign-off is auditable
#
# This script exits non-zero if any scenario's assertions fail or its recording is
# missing. That is only half the gate: the assertions prove the state, the video
# proves the on-screen behavior. A human must watch each video and tick its box in
# SMOKE_APPROVAL.md; `scripts/smoke-approve.sh` then enforces that sign-off and is
# what a release should actually depend on.
#
# Ticks are PRESERVED across runs, but only while they still mean something: each row
# records the revision its footage was made at, and a tick is dropped as soon as that
# revision changes (including a dirty tree). Re-recorded footage must be re-reviewed.
set -euo pipefail
cd "$(dirname "$0")/.."

OUTDIR="docs/videos/out/smoke"
mkdir -p "$OUTDIR"
MANIFEST="SMOKE_APPROVAL.md"

# Revision the footage is being made at. A dirty tree gets a -dirty suffix so edits
# invalidate prior sign-off rather than silently inheriting it.
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
    REV="${REV}-dirty"
fi

shopt -s nullglob
SCENARIOS=(docs/tools/smoke/*.json)
[[ ${#SCENARIOS[@]} -gt 0 ]] || { echo "no scenarios in docs/tools/smoke/" >&2; exit 1; }

# Remember which scenarios were already signed off, and at which revision.
declare -A PREV_TICK PREV_REV
if [[ -f "$MANIFEST" ]]; then
    while IFS= read -r line; do
        if [[ "$line" =~ ^-\ \[([ xX])\]\ \*\*([^*]+)\*\*\ —\ (PASS|FAIL)\ —\ rev\ \`([^\`]+)\` ]]; then
            PREV_TICK["${BASH_REMATCH[2]}"]="${BASH_REMATCH[1]}"
            PREV_REV["${BASH_REMATCH[2]}"]="${BASH_REMATCH[4]}"
        fi
    done < "$MANIFEST"
fi

ROWS=""
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
        video="[${scenario}.mp4](${OUTDIR}/${scenario}.mp4)"
    else
        video='**MISSING — not recorded**'
        status="FAIL"; FAILED=1
    fi

    # Carry a tick forward only if it was for THIS revision and the run still passes.
    tick=" "
    if [[ "${PREV_TICK[$scenario]:- }" =~ [xX] \
       && "${PREV_REV[$scenario]:-}" == "$REV" \
       && "$status" == "PASS" ]]; then
        tick="x"
    fi

    ROWS+="$(printf -- '- [%s] **%s** — %s — rev `%s` — assertions: `%s` — video: %s' \
        "$tick" "$scenario" "$status" "$REV" "$verdict" "$video")"$'\n'
done

{
    echo "# Loominary smoke-test release approval"
    echo
    echo "Last run: $(date -u +%Y-%m-%dT%H:%M:%SZ) at rev \`$REV\`"
    echo
    echo "Watch each video and confirm the in-game behavior is correct, then change its"
    echo "\`- [ ]\` to \`- [x]\`. A tick is dropped automatically when the revision changes,"
    echo "so re-recorded footage must be re-reviewed."
    echo
    printf '%s' "$ROWS"
    echo
    echo "Enforced by \`scripts/smoke-approve.sh\` — a release must not proceed until that"
    echo "exits 0. Videos live under \`$OUTDIR/\` and are git-ignored; this manifest is"
    echo "committed so sign-off is auditable."
} > "$MANIFEST"

echo "== Approval manifest written to $MANIFEST ==" >&2
cat "$MANIFEST" >&2
[[ "$FAILED" == 0 ]] && echo "== Assertions green. Now review the videos and run scripts/smoke-approve.sh ==" >&2
exit "$FAILED"
