# Live-gameplay smoke tests

A sandboxed smoke-test harness that drives the **real mod** through actual in-game
behavior — not just unit tests. It is designed to be safe to run in CI: everything
happens in a throwaway, isolated game directory with no network connections to real
servers and no contact with any real Minecraft install or world save.

## Why this exists

The `src/test/java/` JUnit suite and `web/test/` Node tests cover the codec/allocation
math in isolation. They do **not** prove that the assembled mod boots inside Minecraft,
registers `/loominary`, and produces a populated `PayloadState` when a user actually runs
`/loominary import`. These smoke tests close that gap.

## What it reuses

The project already has a real headless in-game harness for documentation screenshots and
video capture. The smoke tests are built on **exactly** that machinery rather than
inventing a new one:

- **`DocsDriver`** (`src/main/java/net/zerohpminecraft/docs/DocsDriver.java`) — a
  dev-only, tick-based step engine, gated behind `-Dloominary.docs=true` and excluded from
  the release jar (`jar.exclude "net/zerohpminecraft/docs/**"` in `build.gradle`). On
  launch it creates (or rejoins) a **superflat creative integrated-server world**
  (`docs-world`) and executes a JSON step script given by `-Dloominary.docs.script`.
- **Loom `runs {}`** in `build.gradle` — `docsShots` / `docsVideo` already boot a real
  Fabric dev client headlessly with a dedicated `runDir`.
- **`scripts/game-shots.sh`** — the pattern for booting under `Xvfb` + software GL
  (`LIBGL_ALWAYS_SOFTWARE=1`), a fresh sandbox game dir, and `pauseOnLostFocus:false`.

The only additions to `DocsDriver` are a few **assertion step types** and a **PASS/FAIL
result file** — the world boot, the step loop, and the isolation model are unchanged.

## Two layers

### Layer A — fast, boot-free smoke (runs in `./gradlew build`, CI-safe today)

`src/test/java/net/zerohpminecraft/LiveEncodeDecodeSmokeTest.java` (JUnit, tagged
`"smoke"`). It writes a source PNG into a `@TempDir` sandbox and drives the **real** encode
→ place-format → decode roundtrip through the same mod classes `/loominary import` uses:

- `PngToMapColors.convert(...)` — scale-to-128 + nearest-palette match → 16 384 map bytes
- the banner-name chunk split (zstd max + base64 + 48-char indexed chunks)
- `MapBannerDecoder.reassemblePayload(...)` — decode back to map bytes

and asserts the decoded bytes are **pixel-identical** to the encoded bytes. No Minecraft
boot, no network, milliseconds to run. This is the floor that keeps CI green on every push.

### Layer B — live in-game smoke (opt-in Gradle run + wrapper, boots real MC)

Boots a real headless client and drives the mod through actual gameplay. The tested
behaviors are the same kind demonstrated in the video series (import, framed-map preview,
banner/carpet placement) — see the scenario scripts.

- **Scenario scripts** live in `docs/tools/smoke/` (one JSON step script per behavior). They
  reuse the `DocsDriver` step vocabulary plus the assertion steps:
  - `assertSourceLoaded: "sample.png"` — the import actually loaded the source
  - `assertTilesAtLeast: 1` — `PayloadState.tiles` is populated
  - `assertActiveChunksAtLeast: 1` — the active tile has encoded banner chunks

  Current scenarios:
  - `import-basic.json` — deterministic gamerules, `/loominary import sample.png`, assert.
  - `preview-map.json` — builds a wall, places an item frame + empty map, runs
    `/loominary import` then `/loominary preview`, and views the decoded map on the frame
    (a full visual behavior worth recording).
- **Gradle run** `smokeTest` in `build.gradle` (`./gradlew runSmokeTest`) — mirrors
  `docsShots` but with its own **`runDir "run-smoke"`** sandbox. The scenario, result path,
  and window size are Gradle properties (`-PsmokeScript`, `-PsmokeResult`, `-PsmokeW`,
  `-PsmokeH`) so one run config drives the whole suite and both headless and video modes.
- **Verdict** — on `exit`, `DocsDriver` writes `PASS n/n` or `FAIL …/n: <reasons>` to the
  result file. Assertions never throw, so a run reports every check.
- **Wrapper** `scripts/smoke-test.sh [--video] [scenario]` — creates the fresh `run-smoke/`
  sandbox, copies the `web/e2e/fixtures/*.png` fixtures into `run-smoke/loominary_data/`,
  boots under Xvfb + software GL, then turns the result file into the **process exit code**
  (missing verdict = failure, so a boot that never reached the assertions also fails CI).

## Video emission (human-visible proof of behavior)

Assertions prove the mod's *state*; a video proves the *on-screen behavior* a human must
sign off on. `scripts/smoke-test.sh --video <scenario>` records the run to
`docs/videos/out/smoke/<scenario>.mp4` using the **same pipeline as `scripts/game-video.sh`**:
a dedicated `Xvfb` display captured by `ffmpeg -f x11grab`, with optional game audio through
a private Pulse null sink (nothing plays on your speakers). Video runs use a larger window
(1280×720) and unmute the client; the default (no `--video`) stays fast, silent, and headless
for CI. The `docs/videos/out/` tree is git-ignored, so recordings are build artifacts.

## Release process (produce videos → human approval)

The gate is two scripts: one produces the evidence, the other enforces the sign-off.

**`scripts/smoke-release.sh`** runs **every** scenario in `docs/tools/smoke/` with `--video`:

- `docs/videos/out/smoke/<scenario>.mp4` — one recording per behavior (git-ignored artifact).
- `SMOKE_APPROVAL.md` — the manifest, at the repo root and **committed**, so sign-off is
  auditable in history rather than living in an ignored directory. One row per scenario with
  its PASS/FAIL verdict, the revision the footage was made at, a link to its video, and a box.

It exits non-zero if any scenario's assertions fail or its recording is missing. That is only
half the gate — it cannot prove a human watched anything.

**`scripts/smoke-approve.sh`** is the half a release actually depends on. It exits 0 only when
every scenario is ticked, still PASSing, and still current, and fails when:

- the manifest is missing (the suite was never run);
- any row is unticked (nobody reviewed that footage);
- any row is FAIL (assertions failed, or the recording went missing);
- any row's rev ≠ the current revision (footage is stale — the code moved on after sign-off);
- a scenario exists in `docs/tools/smoke/` but has no row (a new scenario can't ship unreviewed).

Ticks are preserved across re-runs at the same revision, so re-recording doesn't wipe review
work — but any commit or working-tree edit changes the rev (a dirty tree gets a `-dirty`
suffix) and drops the ticks, forcing re-review of footage that no longer matches the code.

Release sequence, before tagging:

```bash
./gradlew build && scripts/smoke-release.sh   # produce verdicts + videos
# watch each video, change its `- [ ]` to `- [x]` in SMOKE_APPROVAL.md
scripts/smoke-approve.sh && git tag v<version>
```

Note this is **not yet wired into `CLAUDE.md`'s release steps** — adding it there is still an
open decision.

## Sandbox / isolation model

- **Own game dir.** Layer B uses `runDir "run-smoke"` (git-ignored), created fresh each run
  and separate from the docs `run/`. Loom's dev client only ever reads/writes inside this
  dir — it never touches the user's real `.minecraft`.
- **No network.** The world is a local **integrated-server** superflat world; the client
  never connects to any multiplayer server.
- **Fresh world each run.** `scripts/smoke-test.sh` deletes `run-smoke/` before booting, so
  runs are reproducible and leave no state behind.
- **Headless.** Xvfb + `LIBGL_ALWAYS_SOFTWARE=1`; no display or GPU required.

## How to run

```bash
./gradlew build                         # Layer A smoke runs as part of the normal test suite
./gradlew test --tests '*LiveEncodeDecodeSmokeTest'   # just the fast smoke test
scripts/smoke-test.sh                    # Layer B: default scenario, headless (needs xvfb; downloads MC assets once)
scripts/smoke-test.sh preview-map        # a specific scenario, headless
scripts/smoke-test.sh --video preview-map # record docs/videos/out/smoke/preview-map.mp4
scripts/smoke-release.sh                  # run ALL scenarios with video → SMOKE_APPROVAL.md
scripts/smoke-approve.sh                  # enforce sign-off; exit 0 only if fully approved
```

## CI

`.github/workflows/build.yml` has three jobs:

- **`build`** — `./gradlew build test`, which includes **Layer A** (the fast, boot-free smoke
  test) alongside the rest of the suite.
- **`smoke-test`** — **Layer B**, enabled on every push/PR. Installs `xvfb` and boots a real
  headless client for every scenario in `docs/tools/smoke/`. The first run downloads ~500 MB
  of Minecraft assets, so `~/.gradle/caches/fabric-loom` and `modules-2` are cached, keyed on
  `build.gradle`/`gradle.properties`. On failure it uploads `run-smoke/logs/` as an artifact,
  which is the fastest way to see what the client actually did. No video here — recording is
  a release-time concern, so no `ffmpeg` is needed.
- **`release-gate`** — runs only on `refs/tags/v*`. Executes `scripts/smoke-approve.sh` to
  refuse a tagged release whose in-game behavior was never signed off. It boots nothing and
  just parses the committed `SMOKE_APPROVAL.md`, so it costs seconds.

## TODO for a human

- **Review the footage.** `SMOKE_APPROVAL.md` is committed **unticked** — the suite has been
  booted and both scenarios pass their assertions, but no human has watched the recordings
  yet, so nothing is signed off. Run `scripts/smoke-release.sh`, watch both videos, and tick
  the boxes. Until then `scripts/smoke-approve.sh` (and the `release-gate` CI job) will
  correctly refuse a tagged release.
- **Confirm the CI job's cost.** The `smoke-test` job now runs on every push/PR; its first
  run populates the asset cache and will be slow (~500 MB download). If that proves too
  heavy for routine PRs, narrow it to `push` on `master` plus tags rather than disabling it.
- **Enable the CI job.** Flip `if: false` on the `smoke-test` job in
  `.github/workflows/build.yml` and add Minecraft-asset caching so it doesn't re-download
  every run. Optionally upload the recorded `docs/videos/out/smoke/*.mp4` as job artifacts.
- **Wire the release gate.** Add `scripts/smoke-release.sh` + `scripts/smoke-approve.sh` to the
  release steps in `CLAUDE.md`, before `git tag`.
- **Grow the scenario suite.** Add scenarios mirroring the rest of the video series
  (banner anvil-fill placement, carpet platform via `placeCarpets`, animated/sRGB art) —
  each new `docs/tools/smoke/*.json` is picked up automatically by `smoke-release.sh`.
- **Deepen the assertions (optional).** The current checks stop at "import produced a
  populated `PayloadState`." A fuller flow could let `MapBannerDecoder` decode the framed map
  and assert the rendered `MapState.colors` match expected pixels (reuse
  `src/test/java/net/zerohpminecraft/tools/MapRender.java` for the reference bytes).
