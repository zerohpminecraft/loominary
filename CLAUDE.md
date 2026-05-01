# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew build          # compiles and packages the mod → build/libs/loominary-1.0.0.jar
./gradlew build --info   # verbose output for debugging Loom/mixin issues
```

There are currently no tests. The CI workflow (`.github/workflows/build.yml`) only runs `./gradlew build`.

## Stack

- Fabric mod loader, Minecraft 1.21.4, Java 21, Gradle
- Yarn mappings (`1.21.4+build.8`) — use Yarn names when referencing Minecraft internals
- `zstd-jni 1.5.6-6` bundled in the jar (the only non-Minecraft dependency)
- Client-side only (`"environment": "client"` in `fabric.mod.json`)

## Architecture

All source is under `src/main/java/net/zerohpminecraft/`.

### Encoding pipeline (encode → place → decode)

**Encode** (`PngToMapColors`): An image is scaled to 128×128 and each pixel is nearest-color matched to Minecraft's map palette (up to ~186 "legal" or ~248 "all shades" colors). The resulting `byte[16384]` is zstd-compressed at max level, base64-encoded, then split into 48-char chunks. Each chunk gets a 2-char hex index prefix (e.g. `00`, `01`, …, `ff`), giving a max of 255 chunks (banners) per tile.

**Place** (`AnvilAutoFillHandler`): When the player opens an anvil, a tick-based handler iterates `PayloadState.ACTIVE_CHUNKS`, renames unnamed banners one at a time via `RenameItemC2SPacket`, and packs them into bundles. It pauses automatically if XP/banners/bundles run out and resumes when they're restocked.

**Decode** (`MapBannerDecoder`): Every 20 ticks, scans item frames within 32 blocks of the player. For each framed map, reads its `MapDecoration` list via `MapStateAccessor`. Decorations whose names match the `[0-9a-f]{2}.*` pattern are banner chunks. After sorting and concatenating them, it base64-decodes → zstd-decompresses → writes directly into `MapState.colors`, then calls `MapTextureManager.setNeedsUpdate()` to redraw. Decorations are cleared from the client-side `MapState` to suppress the banner pins.

### State management (`PayloadState`)

Static fields hold the active batch. A batch is an N×M grid of tiles, where each tile is one 128×128 map's worth of chunks. State is serialized as JSON to `<gamedir>/config/loominary_state.json` after each banner rename and on every tile switch. `syncToActiveTile()` / `syncFromActiveTile()` copy between the static working set (`ACTIVE_CHUNKS`, `activeChunkIndex`) and the per-tile `TileData` list.

### Commands (`command/LoominaryCommand`)

All `/loominary` subcommands live here. Commands that need the crosshair-targeted map call `resolveCrosshairMap()`, which casts `client.crosshairTarget` to `EntityHitResult` and pulls the `MapState`. Preview and reduce-undo state are stored in command-local `Map<Integer, byte[]>` caches keyed by map ID or tile index.

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
