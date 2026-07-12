# Building & developing

## The mod (Java)

```bash
./gradlew build     # → build/libs/loominary-<version>.jar
```

Stack: Fabric on Minecraft 1.21.4, Java 21, Yarn mappings. JUnit tests run as part of `build`. Two notable build steps:

- `generateAv1Machine` compiles `av1-decode.wasm` to JVM bytecode at build time (Chicory build-time compiler), which is why AV1 decode is fast and the jar stays pure-Java. Don't switch it to runtime compilation; the required ASM version collides with fabric-loader's.
- The jar bundles zstd-jni and the Chicory runtime via `include`, producing one cross-platform artifact.

## The web editor (TypeScript/Preact)

```bash
cd web
npm ci
npm run dev       # vite dev server
npm run build     # tsc + vite build → dist/
node test/<name>.test.mjs   # node-side tests, run individually
```

Deployed to GitHub Pages by `.github/workflows/pages.yml` on pushes touching `web/`.

## Cross-language parity

The mod and the web editor implement several formats twice (payload wire format, mux allocation, palette permutation, AV1 streams). Fixture generators under `web/scripts/` produce test vectors consumed by the Java tests; regenerate them when you touch either side:

| If you change… | Regenerate with |
|---|---|
| mux allocation (`MuxAllocator` / `web/src/mux.ts`) | `node web/scripts/gen-mux-fixtures.mjs` |
| palette permutation | `node --experimental-strip-types web/scripts/gen-palette-perm.ts` |
| AV1 encode/decode | `node web/scripts/gen-av1-fixtures.mjs` |

## Documentation tooling

- **This wiki** is generated from [`docs/wiki/`](https://github.com/zerohpminecraft/loominary/tree/master/docs/wiki) in the main repo. Edit there, and a workflow syncs it here on push to master.
- **Web screenshots**: `cd web && npm run shots` (Playwright, headless, deterministic fixtures) → `docs/wiki/assets/web/`. `npm run broll` records video B-roll.
- **In-game screenshots**: `scripts/game-shots.sh` runs a real Fabric dev client (headless under xvfb if installed), creates a superflat world, and executes `docs/tools/game-shots.json` → `docs/wiki/assets/game/`.
- **Map renders without the game**: `./gradlew renderMapPreviews` renders map-byte frames (status screens etc.) straight to PNG/GIF.

## Deeper reading

- [`docs/dev/pipeline.md`](https://github.com/zerohpminecraft/loominary/blob/master/docs/dev/pipeline.md) covers the full encode→place→decode pipeline and wire format
- [`native/av1/README.md`](https://github.com/zerohpminecraft/loominary/blob/master/native/av1/README.md) covers rebuilding the AV1 wasm (wasi-sdk + libaom)
- `CLAUDE.md` is the architecture crib sheet and release process
