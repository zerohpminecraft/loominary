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

`scripts/smoke-release.sh` is the pre-release gate. It runs **every** scenario in
`docs/tools/smoke/` with `--video` and writes an approval bundle to `docs/videos/out/smoke/`:

- `docs/videos/out/smoke/<scenario>.mp4` — one recording per behavior.
- `docs/videos/out/smoke/APPROVAL.md` — a checklist manifest listing each scenario, its
  PASS/FAIL assertion verdict, and a link to its video, with an unticked box per scenario.

The script exits non-zero if any scenario's assertions fail, but a green run **still requires
a human** to watch each video and tick the boxes before the release is approved. Wire this
into the release steps in `CLAUDE.md` (before tagging): run `scripts/smoke-release.sh`, review
the videos, and confirm `APPROVAL.md` is fully ticked.

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
scripts/smoke-release.sh                  # run ALL scenarios with video → approval bundle
```

## CI

`.github/workflows/build.yml` already runs `./gradlew build test`, which includes **Layer
A**. **Layer B** is heavier (first run downloads ~500 MB of Minecraft assets and renders
under software GL), so it is provided as an **opt-in** job rather than gating every push —
see the commented `smoke-test` job stub in the workflow. Enable it (e.g. on a schedule or a
label) once a cached-assets step is in place.

## TODO for a human to finish the live-boot parts

- **Run it end-to-end once.** Boot Layer B locally (`scripts/smoke-test.sh` and
  `scripts/smoke-test.sh --video preview-map`) on a machine with `xvfb`, `ffmpeg`, and
  network to seed the Loom asset cache, and confirm the verdicts are `PASS` and the videos
  show the expected behavior. Tune the `waitTicks` in the `docs/tools/smoke/*.json` scenarios
  if a step needs longer under software GL (import/preview are slower there).
- **Validate `preview-map.json`.** Its framed-map + preview steps are copied from the proven
  `docs/tools/game-shots.json` sequence but have not been booted here; the video-emitting run
  is exactly how a human confirms it.
- **Enable the CI job.** Flip `if: false` on the `smoke-test` job in
  `.github/workflows/build.yml` and add Minecraft-asset caching so it doesn't re-download
  every run. Optionally upload the recorded `docs/videos/out/smoke/*.mp4` as job artifacts.
- **Wire the release gate.** Add `scripts/smoke-release.sh` (+ human review of `APPROVAL.md`)
  to the release steps in `CLAUDE.md`, before `git tag`.
- **Grow the scenario suite.** Add scenarios mirroring the rest of the video series
  (banner anvil-fill placement, carpet platform via `placeCarpets`, animated/sRGB art) —
  each new `docs/tools/smoke/*.json` is picked up automatically by `smoke-release.sh`.
- **Deepen the assertions (optional).** The current checks stop at "import produced a
  populated `PayloadState`." A fuller flow could let `MapBannerDecoder` decode the framed map
  and assert the rendered `MapState.colors` match expected pixels (reuse
  `src/test/java/net/zerohpminecraft/tools/MapRender.java` for the reference bytes).
