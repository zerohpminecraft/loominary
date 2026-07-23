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

Boots a real headless client and drives the mod through actual gameplay:

- **Gradle run** `smokeTest` in `build.gradle` (`./gradlew runSmokeTest`) — mirrors
  `docsShots` but with its own **`runDir "run-smoke"`** sandbox, points
  `-Dloominary.docs.script` at `docs/tools/smoke.json`, and sets
  `-Dloominary.smoke.result=…/run-smoke/smoke-result.txt`.
- **Script** `docs/tools/smoke.json` — sets deterministic gamerules, runs
  `/loominary import sample.png`, then asserts:
  - `assertSourceLoaded: "sample.png"` — the import actually loaded the source
  - `assertTilesAtLeast: 1` — `PayloadState.tiles` is populated
  - `assertActiveChunksAtLeast: 1` — the active tile has encoded banner chunks
- **Verdict** — on `exit`, `DocsDriver` writes `PASS n/n` or `FAIL …/n: <reasons>` to the
  result file. Assertions never throw, so a run reports every check.
- **Wrapper** `scripts/smoke-test.sh` — creates the fresh `run-smoke/` sandbox, copies the
  `web/e2e/fixtures/sample.png` fixture into `run-smoke/loominary_data/`, boots under Xvfb
  + software GL, then turns the result file into the **process exit code** (missing verdict
  = failure, so a boot that never reached the assertions also fails CI).

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
./gradlew build            # Layer A smoke runs as part of the normal test suite
./gradlew test --tests '*LiveEncodeDecodeSmokeTest'   # just the fast smoke test
scripts/smoke-test.sh      # Layer B: live in-game smoke (needs xvfb; downloads MC assets once)
```

## CI

`.github/workflows/build.yml` already runs `./gradlew build test`, which includes **Layer
A**. **Layer B** is heavier (first run downloads ~500 MB of Minecraft assets and renders
under software GL), so it is provided as an **opt-in** job rather than gating every push —
see the commented `smoke-test` job stub in the workflow. Enable it (e.g. on a schedule or a
label) once a cached-assets step is in place.

## TODO for a human to finish the live-boot parts

- **Run it end-to-end once.** Boot Layer B locally (`scripts/smoke-test.sh`) on a machine
  with `xvfb` and network to seed the Loom asset cache, and confirm the verdict is `PASS`.
  Tune the `waitTicks` in `docs/tools/smoke.json` if import needs longer under software GL.
- **Enable the CI job.** Uncomment the `smoke-test` job in `.github/workflows/build.yml` and
  add Minecraft-asset caching so it doesn't re-download every run.
- **Deepen the assertions (optional).** The current live checks stop at "import produced a
  populated `PayloadState`." A fuller flow could place a framed map, let `MapBannerDecoder`
  decode it, and assert the rendered `MapState.colors` match the expected pixels (reuse
  `src/test/java/net/zerohpminecraft/tools/MapRender.java` for the reference bytes), plus a
  banner-placement pass via the anvil auto-fill handler.
