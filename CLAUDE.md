# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew build          # compiles and packages the mod → build/libs/loominary-1.0.0.jar
./gradlew build --info   # verbose output for debugging Loom/mixin issues
```

JUnit tests live under `src/test/java/` and run as part of `./gradlew build` (or `./gradlew test`). Web-side Node tests live in `web/test/*.test.mjs` and run individually with `node test/<name>.test.mjs` from `web/`. The CI workflow (`.github/workflows/build.yml`) runs `./gradlew build`.

## Release process

1. Write release notes and add them to the **top** of `CHANGELOG.md` (see that file for style)
2. Bump `mod_version` in `gradle.properties` to match the new tag
3. Run `./gradlew build` — must pass cleanly
4. Commit both files: `git commit -m "v<version>: <summary>"`
5. Tag: `git tag v<version>`
6. Push: `git push origin master && git push origin v<version>`

## Stack

- Fabric mod loader, Minecraft 1.21.4, Java 21, Gradle
- Yarn mappings (`1.21.4+build.8`) — use Yarn names when referencing Minecraft internals
- `zstd-jni 1.5.6-6` bundled in the jar
- `com.dylibso.chicory` (runtime/wasm/wasi/log, 1.7.5) — pure-Java WebAssembly runtime, used to
  run `av1-decode.wasm` in-JVM for animated AV1 decode (see below). The wasm is compiled **to JVM
  bytecode at build time** by the `generateAv1Machine` Gradle task (Chicory build-time compiler →
  generated `net.zerohpminecraft.av1.Av1DecodeMachine` + machine classes + stripped `.meta`
  module; ~50× the interpreter, which took minutes per animation). Do NOT switch to Chicory's
  runtime compiler or nest ASM: the compiler needs ASM 9.9+ and a nested ASM collides with
  fabric-loader's copy (Sodium preLaunch crash). Chicory is nested in the jar with `include`
  alongside zstd-jni, so the mod is still a single cross-platform jar.
- Client-side only (`"environment": "client"` in `fabric.mod.json`)

## Architecture

All source is under `src/main/java/net/zerohpminecraft/`.

### Encoding pipeline (encode → place → decode)

**Encode** (`PngToMapColors`): An image is scaled to 128×128 and each pixel is nearest-color matched to Minecraft's map palette (up to ~186 "legal" or ~248 "all shades" colors). The resulting `byte[16384]` is zstd-compressed at max level, base64-encoded, then split into 48-char chunks. Each chunk gets a 2-char hex index prefix (e.g. `00`, `01`, …, `ff`), giving a max of 255 chunks (banners) per tile.

**Place** (`AnvilAutoFillHandler`): When the player opens an anvil, a tick-based handler iterates `PayloadState.ACTIVE_CHUNKS`, renames unnamed banners one at a time via `RenameItemC2SPacket`, and packs them into bundles. It pauses automatically if XP/banners/bundles run out and resumes when they're restocked.

**Decode** (`MapBannerDecoder`): Every 20 ticks, scans item frames within 32 blocks of the player. For each framed map, reads its `MapDecoration` list via `MapStateAccessor`. Decorations whose names match the `[0-9a-f]{2}.*` pattern are banner chunks. After sorting and concatenating them, it base64-decodes → zstd-decompresses → writes directly into `MapState.colors`, then calls `MapTextureManager.setNeedsUpdate()` to redraw. Decorations are cleared from the client-side `MapState` to suppress the banner pins.

### Animated art & the AV1 codec

Animated tiles carry N frames. `PayloadManifest` (v4/v5) holds `frameCount`/`frameDelays`; `MapBannerDecoder.extractFrames` returns `byte[][]` and `AnimatedMapState` cycles them onto `MapState.colors` on the render thread. Frames 1..N may be stored four ways, selected by manifest flags: raw, `FLAG_DELTA_FRAMES` (XOR), `FLAG_SPARSE_FRAMES` (change lists), or **`FLAG_AV1`** — a lossless AV1 bitstream (length-prefixed temporal units after the header). The web editor's `encode.ts` produces AV1 for animations when it's smaller than the raw path; the encoder/decoder is a libaom build (`native/av1/`, wasi-sdk) exposed through the `shim.c` ABI. Frames are coded as a monochrome plane of palette indices after the fixed OKLab permutation in `PalettePermutation.java` / `web/src/palette-perm.ts` (regenerate both with `node --experimental-strip-types web/scripts/gen-palette-perm.ts`). `Av1FrameDecoder` runs `av1-decode.wasm` (from `src/main/resources/av1/`) via Chicory and applies `INV_PERM`; the web editor decodes the same binary in the browser (`web/src/av1/codec.ts`). Rebuild the wasm with `native/av1/build.sh` (see its README — the `try_table` EH and no-SIMD flags are load-bearing for Chicory).

There is also an **optional lossy mode** (`FLAG_AV1_LOSSY`, opt-in via the export page's "Lossy animation" toggle) for when lossless can't shrink dithered art: frames are encoded as lossy AV1 colour (4:2:0) and the wasm decoder reconstructs RGB and picks the nearest palette entry (using `MapPalette.RGB_ENTRIES`, generated alongside the permutation from the same web palette so the mod's result matches the export preview exactly). The export preview runs the identical encode→decode pipeline; `Av1LossyRoundtripTest` locks web↔mod parity.

There is also a **full-colour sRGB mode** (manifest **v7**: the v5 layout + trailing u16 `flags2`, bit `FLAG2_SRGB`; the u8 `flags` byte is exhausted). The art is arbitrary 24-bit RGB carried as the same lossy AV1 colour stream; the wasm's `dec_tu_full` returns BOTH the nearest-palette plane (written to `MapState.colors` — keeps overwrite detection/minimaps sane) and the raw RGB plane. Display bypasses vanilla's palette lookup: `MapTextureMixin` fills the per-map `NativeImage` from `MapBannerDecoder.currentRgbFrame(mapId)`; under ImmediatelyFast (whose map atlas replaces that path entirely, and which MapMipMapMod requires) `mixin/immediatelyfast/IfMapAtlasFillMixin` instead swaps pixels inside IF's own atlas-fill handler via MixinSquared (bundled; applied only when IF is loaded — see `LoominaryMixinPlugin`). Web-side the editor keeps `rgbFrames` + quantized preview `frames` in sync (`web/src/srgb.ts`, `tools/Tool.ts`); duck interfaces for mixins must live OUTSIDE `net.zerohpminecraft.mixin` (mixin-package classes can't be classloaded). Parity: `Av1SrgbRoundtripTest`, `web/test/manifest-v7.test.mjs` (byte-parity anchor with `PayloadManifestTest`), sRGB sections of `composite-payload.test.mjs`.

**Multi-tile lossy is composition-wide** (`FLAG_AV1_COMPOSITE`): the whole grid is encoded as ONE lossy stream at `(cols·128)×(rows·128)` — no per-tile seams — and the stream bytes are split evenly across the tiles' payloads (segments concatenate in tile-index order). The mod's `MapBannerDecoder` buffers segments per composition (keyed by grid/frames/nonce/author/title) and decodes once off-thread when all `cols×rows` tiles have been seen; until then composite tiles paint transparent. Web side: `encodeCompositionLossy` in `encode.ts` (raw per-tile stays the fallback floor), reassembly in `ImportPage.decodeStateToComposition`. Parity fixtures come from `node web/scripts/gen-av1-fixtures.mjs` (`Av1CompositeRoundtripTest`); the wire format is locked by `web/test/composite-payload.test.mjs`.

### Mux (payload redistribution across tiles)

Over-budget tiles (receivers) spill overflow bytes into spare capacity on donor tiles. The allocation algorithm exists twice and **must stay allocation-equivalent**: `MuxAllocator.allocate` (Java, Minecraft-free; `poolMuxTiles` in `LoominaryCommand` wraps it and encodes the chunks) and `computeMuxAllocation` in `web/src/mux.ts`. The web bakes its allocation into schematic LOOM headers at export while the mod re-runs the Java allocation from the actual payload sizes at state import — divergence corrupts muxed receivers ("Src size is incorrect"). Both must also see the same sizes: the web derives its allocation from the actual exported payload bytes (`encodeAndMux`), never from stats-pass estimates. `MuxAllocationParityTest` locks the equivalence; regenerate its fixtures with `node web/scripts/gen-mux-fixtures.mjs` whenever either implementation changes.

### State management (`PayloadState`)

Static fields hold the active batch. A batch is an N×M grid of tiles, where each tile is one 128×128 map's worth of chunks. State is serialized as JSON to `<gamedir>/config/loominary_state.json` after each banner rename and on every tile switch. `syncToActiveTile()` / `syncFromActiveTile()` copy between the static working set (`ACTIVE_CHUNKS`, `activeChunkIndex`) and the per-tile `TileData` list.

### Commands (`command/LoominaryCommand`)

All `/loominary` subcommands live here. Commands that need the crosshair-targeted map call `resolveCrosshairMap()`, which casts `client.crosshairTarget` to `EntityHitResult` and pulls the `MapState`. Preview state is stored in a command-local `Map<Integer, byte[]>` cache keyed by map ID. Image editing (edit/reduce/dither/filter/requantize/palette/sparse/stride/skip/resalt) was removed in v2.0.0 in favor of the web editor; the old literals are stubs via `removedCommand()` that print a pointer.

### Mixins

Three accessor mixins expose private fields/methods with no behavior injection:
- `MapStateAccessor` — `getDecorations()` (the `Map<String, MapDecoration>` inside `MapState`)
- `AnvilScreenAccessor` / `AnvilScreenHandlerAccessor` — used by `AnvilAutoFillHandler` to read slot contents

### Schematic export (`SchematicExporter`)

Writes Litematica v6 `.litematic` files (gzipped NBT). Banners are arranged in a 16-column grid at y=0, each with its chunk string as the NBT `CustomName`. The file goes to `<gamedir>/loominary_exports/`. The user manually copies it to Litematica's schematics folder.

## Runtime directories (relative to game dir)

| Path | Purpose |
|---|---|
| `loominary_data/` | Source images for `/loominary import` |
| `loominary_exports/` | Litematica schematics from `/loominary export` |
| `config/loominary_state.json` | Persistent batch state |

## Encoding invariants

- Banner name capacity: 50 chars = 2 hex index + 48 base64 payload chars
- Max banners per map: 255 (hex indices `00`–`ff`)
- Max compressed bytes per map: 255 × 48 base64 chars = ~9,000 bytes
- Map color array is always exactly 16,384 bytes (128×128); `reassemblePayload` validates this
- Map color byte layout: upper 6 bits = base color ID (1–63), lower 2 bits = shade ID (0–3); shade 3 is unobtainable via normal block placement
