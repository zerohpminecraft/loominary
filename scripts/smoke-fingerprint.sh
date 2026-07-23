#!/usr/bin/env bash
# Prints a short fingerprint of everything that can change what the mod does in-game.
# Shared by scripts/smoke-release.sh (stamps it onto each approval row) and
# scripts/smoke-approve.sh (refuses a sign-off whose fingerprint no longer matches).
#
# Why not the commit sha: sign-off is recorded by editing SMOKE_APPROVAL.md and committing
# it, which would change the sha and invalidate the approval the moment it was given — the
# gate could never pass. A content fingerprint over the behavior-affecting files is stable
# across that commit, still changes the instant real code or a scenario changes, and can be
# re-checked in CI on the tagged commit.
#
# Read from the WORKING TREE (not the index), so uncommitted edits invalidate approvals too.
#
# `--cached --others --exclude-standard` counts untracked-but-not-ignored files as well as
# tracked ones. Without `--others`, merely `git add`ing a new source file would change the
# fingerprint without any content changing — so a release that adds a file would invalidate
# its own sign-off at commit time. Ignored files stay out via --exclude-standard.
set -euo pipefail
cd "$(dirname "$0")/.."

# Files whose content determines in-game behavior. Excluded on purpose:
# SMOKE_APPROVAL.md (the sign-off itself), CHANGELOG.md, and docs prose.
git ls-files -z --cached --others --exclude-standard -- \
        'src/main/java/*' \
        'src/main/resources/*' \
        'docs/tools/smoke/*.json' \
        'scripts/smoke-test.sh' \
        'scripts/smoke-release.sh' \
        'scripts/smoke-approve.sh' \
        'scripts/smoke-fingerprint.sh' \
        'build.gradle' \
        'gradle.properties' \
    | sort -z -u \
    | xargs -0 -r sha256sum \
    | sha256sum \
    | cut -c1-12
