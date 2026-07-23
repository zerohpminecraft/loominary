#!/usr/bin/env bash
# Release approval GATE — enforces the human sign-off recorded in SMOKE_APPROVAL.md.
#
#   scripts/smoke-approve.sh
#
# scripts/smoke-release.sh proves the ASSERTIONS pass and the videos exist. It cannot
# prove a human watched them, so it only writes the manifest. This script is the part a
# release depends on: it exits 0 only when every scenario is signed off, still passing,
# and still current.
#
# Fails when:
#   - the manifest is missing (the smoke suite was never run)
#   - any scenario row is unticked (nobody reviewed that footage)
#   - any scenario row is FAIL (assertions failed or the recording went missing)
#   - any row's rev does not match the current revision (footage is stale — the code
#     moved on after sign-off, so the approval no longer describes what ships)
#
# Use before tagging:
#   ./gradlew build && scripts/smoke-release.sh && <watch videos, tick boxes> \
#     && scripts/smoke-approve.sh && git tag v<version>
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFEST="SMOKE_APPROVAL.md"

[[ -f "$MANIFEST" ]] || {
    echo "APPROVAL FAIL: $MANIFEST missing — run scripts/smoke-release.sh first." >&2
    exit 1
}

REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
    REV="${REV}-dirty"
fi

TOTAL=0; PROBLEMS=0
while IFS= read -r line; do
    [[ "$line" =~ ^-\ \[([ xX])\]\ \*\*([^*]+)\*\*\ —\ (PASS|FAIL)\ —\ rev\ \`([^\`]+)\` ]] || continue
    tick="${BASH_REMATCH[1]}"; scenario="${BASH_REMATCH[2]}"
    status="${BASH_REMATCH[3]}"; rowrev="${BASH_REMATCH[4]}"
    TOTAL=$((TOTAL + 1))

    if [[ "$status" != "PASS" ]]; then
        echo "APPROVAL FAIL [$scenario]: run status is $status." >&2
        PROBLEMS=$((PROBLEMS + 1))
    elif [[ ! "$tick" =~ [xX] ]]; then
        echo "APPROVAL FAIL [$scenario]: not signed off — watch the video and tick its box." >&2
        PROBLEMS=$((PROBLEMS + 1))
    elif [[ "$rowrev" != "$REV" ]]; then
        echo "APPROVAL FAIL [$scenario]: signed off at rev '$rowrev' but HEAD is '$REV' — re-run the smoke suite and re-review." >&2
        PROBLEMS=$((PROBLEMS + 1))
    fi
done < "$MANIFEST"

if [[ "$TOTAL" == 0 ]]; then
    echo "APPROVAL FAIL: no scenario rows found in $MANIFEST — manifest is malformed or empty." >&2
    exit 1
fi

# Every scenario on disk must appear in the manifest, or a newly added one could ship
# unreviewed simply by never having been run.
shopt -s nullglob
for path in docs/tools/smoke/*.json; do
    scenario="$(basename "$path" .json)"
    grep -qF -- "**${scenario}**" "$MANIFEST" || {
        echo "APPROVAL FAIL [$scenario]: scenario exists but is absent from $MANIFEST — re-run scripts/smoke-release.sh." >&2
        PROBLEMS=$((PROBLEMS + 1))
    }
done

if [[ "$PROBLEMS" -gt 0 ]]; then
    echo "== APPROVAL DENIED: $PROBLEMS problem(s) across $TOTAL scenario(s) ==" >&2
    exit 1
fi

echo "== APPROVED: all $TOTAL scenario(s) pass and are signed off at rev $REV ==" >&2
