# Loominary Pipeline Reference

This document traces every byte from the source image file on disk to the decoded
pixels rendered on a Minecraft map, including every intermediate file, in-memory
structure, and on-wire format along the way.

---

## Table of Contents

1. [Overview](#overview)
2. [Stage 0 — Image ingestion](#stage-0--image-ingestion)
3. [Stage 1 — Quantization (`PngToMapColors`)](#stage-1--quantization-pngtomapcolors)
4. [Stage 2 — Manifest construction (`PayloadManifest`)](#stage-2--manifest-construction-payloadmanifest)
5. [Stage 3 — zstd compression](#stage-3--zstd-compression)
6. [Stage 4 — Encoding fork (codec mode)](#stage-4--encoding-fork-codec-mode)
   - [4A — BANNER mode](#4a--banner-mode)
   - [4B — CARPET / CARPET\_SHADE / CARPET\_ONLY modes](#4b--carpet--carpet_shade--carpet_only-modes)
7. [Stage 5 — State serialization (`PayloadState` / `loominary_state.json`)](#stage-5--state-serialization-payloadstate--loominary_statejson)
8. [Stage 6 — Schematic export (`SchematicExporter` / `.litematic`)](#stage-6--schematic-export-schematicexporter--litematic)
9. [Stage 7 — Banner placement (`AnvilAutoFillHandler`)](#stage-7--banner-placement-anvilautofillandler)
10. [Stage 8 — Decode (`MapBannerDecoder`)](#stage-8--decode-mapbannerdecoder)
    - [8A — Banner-only decode path](#8a--banner-only-decode-path)
    - [8B — Legacy carpet decode path (LC/LS)](#8b--legacy-carpet-decode-path-lcls)
    - [8C — LOOM-header carpet decode path](#8c--loom-header-carpet-decode-path)
    - [8D — Mux receiver / donor decode](#8d--mux-receiver--donor-decode)
    - [8E — Shared post-decompression logic](#8e--shared-post-decompression-logic)
11. [Animation pipeline](#animation-pipeline)
12. [Mux pooling (cross-tile redistribution)](#mux-pooling-cross-tile-redistribution)
13. [Key invariants and size budgets](#key-invariants-and-size-budgets)
14. [Codec modes at a glance](#codec-modes-at-a-glance)

---

## Overview

```
loominary_data/<image.png>
        │
        ▼  /loominary import
[Stage 0] Load image
        │
        ▼
[Stage 1] Quantize → byte[16384] map-color array per tile
        │
        ▼
[Stage 2] Build PayloadManifest header bytes
        │
        ▼
[Stage 3] zstd-compress (manifest + map-colors) → byte[] compressed
        │
        ▼
[Stage 4] Encoding fork
   ┌────┴────────────────────────────────┐
   │ BANNER mode                         │ CARPET / CARPET_SHADE / CARPET_ONLY
   ▼                                     ▼
CjkCodec.buildChunks()          LOOM header + carpet nibbles
→ List<String> "00𝄞𝄠…"          + optional shade heights
                                 + optional overflow CJK chunks
   └────────────────┬────────────────────┘
                    ▼
[Stage 5] PayloadState.save() → config/loominary_state.json
                    │
         ┌──────────┴──────────┐
         ▼                     ▼
[Stage 6] /loominary export   [Stage 7] Open anvil
→ schematics/*.litematic       AnvilAutoFillHandler renames
                               banners one per tick
                    │
                    ▼
            [in-world: named banners placed on maps]
                    │
                    ▼
[Stage 8] MapBannerDecoder scans item frames every 20 ticks
→ reassembles chunks → decompresses → paintMap()
→ MapState.colors overwritten → MapTextureManager.setNeedsUpdate()
```

---

## Stage 0 — Image ingestion

**Trigger**: `/loominary import <filename> [cols] [rows] [allshades]`

The player drops an image file into `<gamedir>/loominary_data/`. The command
handler reads it with `ImageIO.read()` into a `BufferedImage`.

For GIF files, `PngToMapColors.convertGif()` is called instead, which uses Java's
built-in GIF image reader to coalesce all frames respecting disposal methods
(`restoreToBackground`, `restoreToPrevious`) into a `BufferedImage[]`.

After reading, the handler:

1. Determines the **grid dimensions** (`cols × rows`). Default `1×1`.
2. Decides the **codec mode** from `PayloadState.codecMode` (default
   `CARPET_SHADE`; set by `/loominary codec`).
3. Decides whether **all shades** (including unobtainable shade 3) are allowed.
4. Chooses **dithering**: whether to call the two-pass grid encoder or the
   single-pass nearest-neighbor encoder.

The full image is **not** sliced into tiles yet; the two-pass grid encoder
operates on the entire image at once.

---

## Stage 1 — Quantization (`PngToMapColors`)

**Class**: `PngToMapColors`  
**Entry points**: `convert()`, `convertTwoPass()`, `convertTwoPassGrid()`, `convertGif()`

### Color space

All nearest-color matching is done in **OKLab** perceptual color space, not RGB.
`buildOklabLookup()` converts every valid Minecraft map-color byte (indices 1–255)
to its OKLab `[L, a, b]` triple by calling `MapColor.get(colorId).getRenderColor(br)`
then `rgbToOklab()`. Index 0 is transparent/void. Shade 3 (`b & 3 == 3`) is
unobtainable in survival and is excluded unless `legalOnly=false`.

The byte value encoding is: `colorId = (b >> 2) & 0x3F`, `shadeId = b & 0x3`.
Valid range: `colorId` 1–63, `shadeId` 0–2 (or 0–3 if `allShades`).

### `convert()` — single-pass nearest-neighbor

1. Scale source image to `128 × 128` with bicubic interpolation (`Graphics2D` +
   `RenderingHints.VALUE_INTERPOLATION_BICUBIC`).
2. For each of 16,384 pixels, call `findClosestMapColorByte(argb, legalOnly,
   oklabLookup)`: convert pixel ARGB → OKLab, iterate all palette entries to find
   the minimum squared Euclidean distance in OKLab space, return the matching
   map-color byte.
3. Returns `byte[16384]`.

### `convertTwoPassGrid()` — two-pass global grid encoder (primary path)

This processes all tiles of the grid as a single image to avoid seam artifacts.

**Pre-processing (optional)**:  
If `FilterParams` is supplied (`--smooth`, `--sharpen`, `--median`,
`--posterize`), `applyFilter()` runs before quantization:
- `SMOOTH` → Gaussian blur via `ConvolveOp`
- `MEDIAN` → per-channel median box filter
- `SHARPEN` → unsharp mask (original minus blurred, scaled by `amount`)
- `POSTERIZE` → uniform tone-level quantization

**Pass 1 — global palette pre-selection**:

1. Scale the full image to `cols×128 × rows×128`.
2. Nearest-neighbor quantize every pixel against the full map palette → flat
   `byte[totalW × totalH]` called `firstPass`.
3. If `targetColors > 0` and the result has more distinct colors, call
   `reduceColorsInPlace(firstPass, targetColors, ...)` to eliminate colors
   iteratively using the active `Strategy` until only `targetColors` remain.
4. Call `buildPalette(firstPass)` → `boolean[256]` marking which color indices
   are in the global palette.

**Adaptive dither strength map** (when `dither=true`):

`computeDitherStrength()` computes a per-pixel float in [0,1]:
1. Convert every pixel to OKLab.
2. Compute RMS OKLab distance to the 4 axis-aligned neighbors → `contrast[]`.
3. Run **Otsu's method** over the contrast histogram to find the threshold `T`
   that maximizes between-class variance.
4. `strength[i] = 1 − clamp((contrast[i] − 0.5T) / (1.5T − 0.5T), 0, 1)`:
   smooth regions (low contrast) get strength near 1.0; edges near 0.0.

Because this runs on the full grid image, the Otsu threshold is globally
calibrated — it does not reset at tile seams.

**Pass 2 — render**:

- **Without dithering**: `renderNearest()` — for each pixel, look up its OKLab
  coordinates and find the closest color in `palette`.
- **With Floyd-Steinberg**: `renderDithered()` — scan left-to-right,
  top-to-bottom. For each pixel:
  1. Add accumulated error from the error-diffusion buffers (`errCur`, `errNxt`).
  2. Quantize to the nearest palette entry.
  3. Compute quantization error `[eL, ea, eb]`.
  4. Gate by two conditions before diffusing:
     - `ditherStrength[i] > 0` (pixel is in a smooth region)
     - `eL² + ea² + eb² > ERROR_FLOOR_SQ` (0.015² — palette doesn't already
       cover this color well)
  5. Distribute error using standard Floyd-Steinberg weights:
     7/16 → right, 3/16 → below-left, 5/16 → below, 1/16 → below-right.
  6. Error propagates naturally across tile seams because the full grid is
     rendered in a single pass.
- **Atkinson** (`renderAtkinson()`): 6/8 of error spread to 6 neighbors (right,
  right+2, below-left, below, below-right, 2-below). Highlights preservation
  mode.
- **Bayer 4×4** (`renderBayer4x4()`): deterministic ordered dither using the
  standard 4×4 Bayer matrix; threshold offset ±`BAYER_SCALE/2` (default 0.04)
  applied uniformly in OKLab space. No error propagation.

**Tile splitting**:

`splitIntoTiles(full, totalW, cols, rows)` extracts `cols × rows` individual
`byte[16384]` tile arrays from the flat grid result in row-major order
(tile index = `tileRow × cols + tileCol`).

### Palette reduction (`reduceToFit`, `reduceToFitKJ`)

After quantization, if the compressed payload exceeds the target banner/byte
budget, reduction runs iteratively:

1. Compute frequency table for all palette colors.
2. Pick victim/survivor pair by strategy:
   - **RAREST**: victim = least-frequent color; survivor = nearest OKLab neighbor.
   - **CLOSEST**: victim = less-frequent of the globally closest color pair;
     survivor = the other.
   - **WEIGHTED**: score each pair as `dist²/(freqA + freqB)`; picks the pair
     where large frequency and small distance combine to score lowest.
3. Replace all victim bytes in `mapColors[]` with survivor bytes.
4. Recompress and recount chunks. Repeat until under budget.

The compression budget function is pluggable: banner mode uses
`chunksNeeded(compressed, 48)` (48 base64 chars per chunk); CJK mode uses
`CjkCodec.chunksNeeded(compressed)` (84 bytes per CJK banner).

---

## Stage 2 — Manifest construction (`PayloadManifest`)

**Class**: `PayloadManifest`

The manifest is a variable-length binary header prepended **before** the
map-color bytes inside the zstd frame. Its purpose is to carry metadata (grid
position, author, title, animation parameters) that survives the full encode/
decode cycle without any side channel.

### Wire format

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | `manifest_version` | u8 | 1–6 |
| 1 | `header_size` | u8 | Total header bytes; map colors begin here |
| 2 | `flags` | u8 | bit0=allShades, bit1=animated, bit2=mux, bit3=delta\_frames, bit4=sparse\_frames |
| 3 | `cols` | u8 | Grid columns |
| 4 | `rows` | u8 | Grid rows |
| 5 | `tile_col` | u8 | This tile's column |
| 6 | `tile_row` | u8 | This tile's row |
| 7–10 | `color_crc32` | u32 BE | CRC32 of first 16,384 map-color bytes (frame 0) |
| 11 | `username_len` | u8 | 0 if absent |
| 12… | `username` | UTF-8 | Max 16 bytes |
| … | `title_len` | u8 | 0 if absent |
| … | `title` | UTF-8 | Max 64 bytes |
| … (v2+) | `nonce` | u32 BE | Random salt from `/loominary resalt` |
| … (v4+) | `frame_count` | u16 BE | Total animation frames |
| … | `loop_count` | u16 BE | 0 = loop forever |
| … | `delay_mode` | u8 | 0=global, 1=per-frame |
| … | `delay` | u16 BE (×1 or ×frame\_count) | ms per frame |

**v5**: when the per-frame delay table would overflow `header_size` (255 bytes
max), `delay_mode=1` is written with no inline delay bytes; the table is appended
**after all frame data** by `PayloadManifest.withTrailing()`.

**v6**: adds `FLAG_DELTA_FRAMES` (bit3) and `FLAG_SPARSE_FRAMES` (bit4) for
compressed animation encoding.

### Delta frames (FLAG\_DELTA\_FRAMES)

Frame 0 stored raw; each subsequent frame stored as `frame[n] XOR frame[n−1]`.
Decoder reconstructs with `reconstructDeltaFrames()`. Typically reduces
compressed payload 5–20× for low-motion animation.

### Sparse frames (FLAG\_SPARSE\_FRAMES)

Frame 0 stored raw. Each subsequent frame stored as:
```
changeCount : u16 BE
changeCount × (pos : u16 BE, val : u8)
```
Decoder reconstructs: copy frame N-1, apply each `(pos, val)` patch.
Effective when < ~2% of pixels change per frame.

**Format detection**: `decompressed.length == 16384` → v0 (no manifest);
`> 16384` → v1+. Old decoders that only know v0 still read the first 16,384
bytes and render frame 0 as a static image.

---

## Stage 3 — zstd compression

**In**: `byte[] combined = manifest + mapColors` (or manifest + all frame bytes)  
**Out**: `byte[] compressed`

Compression is performed by `zstd-jni 1.5.6-6`. The level is chosen adaptively
to avoid OOM-crashing the JVM on large payloads:

| Payload size | zstd level | Notes |
|---|---|---|
| < 256 KB | 22 (max) | Safe — small working window |
| 256 KB – 4 MB | 9 | Fixed ~8 MB native window |
| ≥ 4 MB | 3 | Fast; bounded native memory |

For payloads ≥ 1 MB, **long-range matching** (`setLong(windowLog)`) is enabled
when the call is off the render thread, setting the back-reference window to
cover the entire payload (up to 128 MB). This lets all animation frames
reference each other for much better compression ratios. The compressed format
is fully standard zstd; window size is encoded in the frame header.

The entire `compress()` path in `LoominaryCommand` is async-safe: large
animated imports and reduce/dither operations run on background threads, not
the render/game thread.

---

## Stage 4 — Encoding fork (codec mode)

The active mode is stored in `PayloadState.codecMode` and persists in
`loominary_state.json`. The default is `CARPET_SHADE`.

### 4A — BANNER mode

**Class**: `CjkCodec`

**Wire format**: each banner name is `"NN<48 CJK chars>"` where:
- `NN` = 2-hex-digit index (`00`–`3E`, max 63 banners in BANNER mode)
- 48 CJK chars from alphabet U+4E00–U+8DFF (14 bits each)

**Capacity math**: 48 chars × 14 bits = 672 bits = **84 bytes per banner**.
63 banners × 84 bytes = 5,292 bytes − 2 bytes for the length header = **5,290
bytes** max compressed payload.

**Encoding** (`CjkCodec.encode`):

1. Prepend 2-byte big-endian length header to `compressed`.
2. Bit-pack the byte stream 14 bits at a time, MSB-first.
3. Zero-pad the last character.
4. Offset each code point by `ALPHA_BASE` (0x4E00).

**Splitting** (`CjkCodec.buildChunks`):

Divide the CJK string into 48-character chunks; prefix each chunk with its
2-hex index. Result is `List<String>` stored in `PayloadState.ACTIVE_CHUNKS`.

**Backward compatibility**: first-char of a CJK banner is always ≥ U+4E00,
far above any base64 ASCII char (≤ 0x7F), so old and new formats are
discriminated by a single char-range check at decode time.

### 4B — CARPET / CARPET\_SHADE / CARPET\_ONLY modes

These modes use a physical **carpet platform** (a 128×N region of colored
carpet blocks) as the primary data channel. The LOOM header format is used
for all new-format tiles.

#### LOOM header

The LOOM header is prepended to the **cargo** (before the zstd payload) and
carried in the first bytes of the carpet channel. It is 16 bytes fixed plus
10 bytes per mux guest descriptor:

| Offset | Field | Notes |
|---|---|---|
| 0–3 | Magic `LOOM` (0x4C4F4F4D) | Detected by `peekLoomMagic()` |
| 4 | flags | bit0=SHADE, bit1=BANNERS, bit2=MUX\_DONOR, bit3=MUX\_RX |
| 5 | col | Tile column (for mux receiver identification) |
| 6 | row | Tile row |
| 7–10 | ownBytes | Size of this tile's zstd frame |
| 11–14 | totalBytes | Full logical payload size (= ownBytes for non-mux) |
| 15 | guestCount | Number of mux guest descriptors |
| 16… | guestDescs | `guestCount × 10` bytes: {tCol:1, tRow:1, tOffset:4, tLen:4} |

The LOOM header is **part of the cargo**, not the zstd payload. It occupies
the first `16 + guestCount × 10` bytes of the cargo byte array; the zstd
frame begins at `cargo[dataOffset]`.

#### Carpet channel (all CARPET modes)

16,384 map pixels each encode 1 nibble (4 bits) by choosing among 16 carpet
colors (one for each `DyeColor`). Adjacent pixels share adjacent map bytes,
so two consecutive map bytes = one compressed byte:

```
compressed[i] hi nibble → mapColors[2i]   (carpet block at pixel 2i)
compressed[i] lo nibble → mapColors[2i+1] (carpet block at pixel 2i+1)
```

**Nibble-to-color mapping**: `CarpetChannel.NIBBLE_TO_COLOR[nibble]` gives the
`DyeColor`; `NIBBLE_TO_MAP_BYTE[nibble]` gives the shade-NORMAL map byte for
that color.

**Capacity**: 16,384 pixels / 2 = **8,192 bytes** carpet channel maximum.
After the LOOM header (16 bytes), usable carpet payload = **8,176 bytes**
(`MAX_CARPET_PAYLOAD`).

At encode time: `CarpetChannel.encodeNibbles(cargo, carpetBytes)` produces the
`byte[16384]` nibble array (each element 0–15) for the schematic generator.
At decode time: `CarpetChannel.decodeBytes(mapColors, carpetBytes)` reads the
map byte at positions `2i` and `2i+1`, looks up both nibbles via
`MAP_BYTE_TO_NIBBLE`, and reconstructs each compressed byte. Shades 0, 1, and
2 all map to the same nibble (data is shade-invariant).

#### Shade channel (CARPET\_SHADE mode only)

When the compressed payload exceeds 8,176 bytes of carpet capacity, the shade
channel carries additional bytes by encoding carpet **height** (0, 1, or 2
blocks above the base level).

**Encoding scheme**: heights are grouped into 4-row columns. There are 16
valid "balanced" 4-row sequences (where the carpet returns to the reference
height at each group boundary); each encodes one 4-bit nibble. A 3-row tail
group encodes 2 bits per column.

The 16 valid sequences are stored in `CarpetChannel.SEQ4[0..15]`; lookup table
`DEC4[s1×27 + s2×9 + s3×3 + s4]` decodes a sequence to its nibble value.

**Capacity**: 31 groups × 128 columns × 4 bits + 128 columns × 2 bits = **2,016
bytes** (`MAX_SHADE_BYTES`).

At encode time: `CarpetChannel.computeHeights(shadeData, shadeLen)` returns
`heights[col][row]`, which is fed to `SchematicExporter.exportCarpetStaircase()`
to produce a 3D schematic. Row 0 is always at height 1 (baseline). Groups
beyond the encoded data use the flat {1,1,1,1} sequence.

At decode time: `CarpetChannel.decodeShade(mapColors, shadeBytes)` reads the
shade bits from `mapColors` (bottom 2 bits of each map byte = shade), applies
the sequence decoder, and returns the shade-channel bytes.

#### Overflow banner chunks (CARPET and CARPET\_SHADE modes)

When compressed bytes still exceed carpet+shade capacity, overflow goes into
CJK-encoded hex-indexed banner chunks exactly like BANNER mode, except the
LC/LS manifest banner is eliminated in LOOM format, freeing one extra slot
(up to **63 overflow banners**, 5,290 bytes max).

#### Capacity breakdown

| Mode | Carpet | Shade | Overflow banners | Total |
|---|---|---|---|---|
| BANNER | — | — | 63 × 84 − 2 | **5,290 bytes** |
| CARPET | 8,176 | — | 63 × 84 − 2 | **13,466 bytes** |
| CARPET\_SHADE | 8,176 | 2,016 | 63 × 84 − 2 | **15,482 bytes** |
| CARPET\_ONLY | 8,176 | 2,016 | — | **10,192 bytes** |

---

## Stage 5 — State serialization (`PayloadState` / `loominary_state.json`)

**Class**: `PayloadState`  
**File**: `<gamedir>/config/loominary_state.json`

After every banner rename and on every tile switch, `PayloadState.save()`
serializes the entire batch to JSON.

### JSON structure

```json
{
  "sourceFilename": "myimage.png",
  "columns": 2,
  "rows": 1,
  "activeTileIndex": 0,
  "allShades": false,
  "dither": true,
  "title": "My Painting",
  "codecMode": "CARPET_SHADE",
  "authorOverride": null,
  "whitelist": ["00𝄞𝄠…", "01𝄞𝄠…"],
  "tiles": [
    {
      "chunks": ["00𝄞𝄠…", "01𝄞𝄠…", …],
      "currentIndex": 12,
      "nonce": 1845928374,
      "frameCount": 1,
      "frameDelays": null,
      "frameSourceIndices": null,
      "carpetEncoded": true,
      "loomEncoded": true,
      "carpetCompressedB64": "KLUv/QA…",
      "muxed": false,
      "muxReceiver": false,
      "isDonorOnly": false,
      "muxCargoB64": null
    },
    …
  ]
}
```

### Active working set

The `ACTIVE_CHUNKS` static list and `activeChunkIndex` counter are the **live**
state; `TileData.chunks` and `TileData.currentIndex` are the persisted copies.
`syncToActiveTile()` copies live → persistent before switching or saving;
`syncFromActiveTile()` copies persistent → live after loading or switching.

### Key `TileData` fields

| Field | Meaning |
|---|---|
| `chunks` | All banner names for this tile (hex-indexed CJK strings or CJK overflow) |
| `currentIndex` | Next chunk index to rename (progress cursor) |
| `nonce` | v2 manifest nonce; 0 = no nonce |
| `carpetEncoded` | True = tile uses carpet channel |
| `loomEncoded` | True = LOOM-header format; false = legacy LC/LS |
| `carpetCompressedB64` | Full zstd payload as base64 (stored so export/preview work without the physical carpet) |
| `muxed` | True when tile participates in mux pooling |
| `muxReceiver` | True when this tile's payload is distributed across donor tiles |
| `isDonorOnly` | True when tile is a blank donor added solely to absorb overflow |
| `muxCargoB64` | Physical cargo bytes after mux assignment |

### Loading

On startup, `ClientModInit` calls `PayloadState.load()`. If
`loominary_state.json` exists it is deserialized and `syncFromActiveTile()`
restores `ACTIVE_CHUNKS` and `activeChunkIndex` so the anvil handler can
resume exactly where it left off.

---

## Stage 6 — Schematic export (`SchematicExporter` / `.litematic`)

**Class**: `SchematicExporter`  
**Output**: `<gamedir>/schematics/<name>.litematic`

**Trigger**: `/loominary export [name]`

The user manually copies the file to Litematica's schematics folder.

### Banner schematic

`exportTile(chunks, baseName, description)` writes a Litematica v6 `.litematic`
(gzipped NBT):

- **Region size**: 16 columns wide × ⌈count/16⌉ rows deep × 1 block tall, y=0.
- **Block palette**: index 0 = air, index 1 = `minecraft:white_banner[rotation=8]`
  (south-facing).
- **Block states**: bit-packed long array (2 bits per entry, no cross-long
  spanning). First `count` slots = banner (index 1); remaining = air (index 0).
- **Block entities**: one `minecraft:banner` NBT compound per banner at its
  `(x, z)` position, with `CustomName: {"text":"<chunk_string>"}`.

The `chunk_string` is the exact banner name string from `ACTIVE_CHUNKS` (e.g.
`00𝄞𝄠…`). Banner-name chunks contain only hex digits and CJK characters;
no JSON escaping is needed.

### Carpet schematic (flat)

`exportCarpetTile(nibbles, carpetBytes, baseName)`:

- Region: 128 × 1 × `carpetRows` (= ⌈carpetBytes×2/128⌉).
- Block palette: air + 16 carpet colors in `DyeColor.ordinal()` order (17 entries
  total → 5 bits per entry).
- Block states: bit-packed using the **spanning** format (`packBlockIndices`):
  entries may cross long boundaries, matching Litematica's `LitematicaBitArray`.
- Layout: z varies slowest (row), x varies fastest (column). Pixel `z*128+x`
  → `blockIndices[z*128+x] = 1 + nibbles[z*128+x]`.

### Carpet staircase schematic (CARPET\_SHADE)

`exportCarpetStaircase(nibbles, carpetBytes, heights, baseName)`:

- Region: 128 × `regionY` × 128, where `regionY = maxHeight + 1`.
- Same 17-entry palette as the flat carpet schematic.
- Layout in YZX order: `(y * 128 + z) * 128 + x`.
- At each column `(x, z)`, carpets are stacked from y=0 up to `heights[x][z]`.
  Only the top carpet carries a data color; all lower carpets are white
  (nibble 0) because only the topmost carpet is visible to the map renderer.
- Placement note: the terrain north of the schematic's north edge must be at
  the same y-level as the origin so that row 0 renders at shade NORMAL.

---

## Stage 7 — Banner placement (`AnvilAutoFillHandler`)

**Class**: `AnvilAutoFillHandler`  
**Trigger**: Player opens an anvil screen while `ACTIVE_CHUNKS` is non-empty.

This handler runs in the `END_CLIENT_TICK` event, processing one chunk per
5-tick cooldown cycle to avoid packet flood.

### State machine

The handler operates a multi-priority state machine. Each tick evaluates
priorities top-to-bottom:

**HALT check**: if `haltedForResalt` is set (after 3 consecutive rename
failures), show HUD warning and wait for `/loominary resalt`.

**Cooldown**: if `cooldown > 0`, decrement and return.

**Cursor safety**: if something unexpected is on the cursor (outside an
in-progress operation), park it in an empty inventory slot.

**Extraction states 1–3** (spread across separate ticks to avoid slot-state-ID
mismatches with 1.21.4):
- State 1: right-click the full stack to place one banner onto the cursor.
- State 2: left-click the source slot to return the remaining stack.

**Bundle-extract states 1–2** (for whitelisted banners inside bundles):
- Pre-step: send `BundleItemSelectedC2SPacket(slot, index)` and update client
  state to sync selected index.
- State 1: right-click the bundle to pop the selected banner to cursor.
- State 2: left-click anvil slot 0 to place the banner.

**storeState 1**: wait for `QUICK_MOVE` (shift-click slot 2) confirmation.
Retry if the output slot is re-populated (server rejected the move).

**storeState 2**: insert the renamed banner from cursor into an available bundle
slot.

**Already-in-inventory check**: if the current chunk's banner is already in
inventory (happens when the anvil was closed mid-operation), bundle it directly
and advance without re-renaming.

**PRIORITY 1**: if the output slot has a renamed banner, shift-click it to
inventory (`QUICK_MOVE`), set `storeState = 1`.

**Rename timeout**: if `pendingName` is set but the output remains empty for 60
ticks, retry the rename packet (up to 3 times) before halting.

**PRIORITY 2**: if slot 0 has a single unnamed banner (or a whitelisted named
banner matching `extractedOldName`), send `RenameItemC2SPacket(pendingName)`.
The client deliberately does **not** call `setNewItemName()` to avoid a
premature optimistic `QUICK_MOVE` on the next tick.

**PRIORITY 3**: if slot 0 is empty, run `startExtraction()`:
1. **Pass 1**: scan slots 3+ for an unnamed banner stack. If found, left-click
   to pick up the full stack (extraction step 1 → state 1).
2. **Pass 2**: scan for a loose named banner whose name is in
   `PayloadState.whitelistedBannerNames`. Remove from whitelist, set
   `extractedNamed = true`.
3. **Pass 3**: scan for a whitelisted banner inside a bundle. Select it with
   `BundleItemSelectedC2SPacket` + client-side `setSelectedStackIndex`, set
   `bundleExtractState = 1`.
4. If nothing found, show "paused — add unnamed banners" HUD message.

### `advance(doneIndex)`

Increments `PayloadState.activeChunkIndex`, calls `PayloadState.save()` to
flush to disk, resets all state variables.

---

## Stage 8 — Decode (`MapBannerDecoder`)

**Class**: `MapBannerDecoder`  
**Trigger**: `END_CLIENT_TICK` every 20 ticks + `WorldRenderEvents.END` every frame

### Scan loop

Every 20 ticks, the scanner builds a `Box.expand(32)` around the player and
queries `client.world.getEntitiesByClass(ItemFrameEntity.class, ...)`.

For each item frame:
1. Get the map stack from `frame.getHeldItemStack()`.
2. Extract `MapIdComponent` and look up `MapState`.
3. Get decorations via `((MapStateAccessor) mapState).getDecorations()`.
4. If `claimedMaps.contains(mapId)`, only re-paint if `mapState.colors` was
   overwritten by the server (e.g. another player placed the frame).
5. Otherwise dispatch to the appropriate decode path.

A parallel path runs every tick for **held maps** in main hand and off hand
(`processHeldItem()`). It skips mux-receiver tiles (which require sibling
tiles to be loaded).

### Dispatch order

```
Does carpet channel start with LOOM magic?
    → processLoomCarpetFrame()
Does a decoration start with "LR" (len ≥ 18)?
    → processReceiverCarpetFrame()   (legacy mux receiver)
Does a decoration start with "LB" (len ≥ 18)?
    → processBannerMuxReceiver()
Does a decoration start with "LC" or "LS"?
    → processCarpetFrame()           (legacy carpet)
Are there any "MG" decorations?
    → processBannerMuxDonor()
Are there any hex-indexed decorations (name[0..1] parse as hex)?
    → assembleAndDecompress()        (legacy banner-only)
```

### 8A — Banner-only decode path

`assembleAndDecompress(names)`:

1. Sort names by hex index (first 2 chars parsed as hex).
2. Detect encoding: if `names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE` → CJK;
   else → base64.
3. **CJK**: concatenate payloads (chars after index prefix), call
   `CjkCodec.decode()` to unpack 14-bit chars back to bytes.
4. **Base64**: concatenate payloads, call `Base64.getDecoder().decode()`.
5. Read `Zstd.getFrameContentSize(compressed)` from the zstd frame header.
6. `Zstd.decompress(compressed, originalSize)` → full decompressed bytes.

### 8B — Legacy carpet decode path (LC/LS)

`processCarpetFrame(client, mapId, mapState, lcName, decorations, frame)`:

**LC format** (`LC<4-hex-total>[<b64-overflow>]`):
- `total` = total compressed bytes.
- `carpetBytes = min(total, 8192)`.
- `CarpetChannel.decodeBytes(mapState.colors, carpetBytes)` → carpet data.

**LS format** (`LS<4-hex-total><4-hex-shade>[<b64-overflow>]`):
- `total`, `shadeBytes` extracted from the banner name.
- `CarpetChannel.decodeShade(mapState.colors, shadeBytes)` → shade data.

**Overflow**: if `total > carpetBytes + shadeBytes`, collect hex-indexed
decoration banners, detect CJK vs base64, decode overflow bytes.

**Reassemble**: `compressed = carpet || shade || overflow`.

**Decompress**: `Zstd.decompress(compressed, frameContentSize)` → `full`.

### 8C — LOOM-header carpet decode path

`processLoomCarpetFrame(client, mapId, mapState, decorations, frame)`:

1. `CarpetChannel.decodeBytes(mapState.colors, MAX_CARPET_BYTES)` → `carpetFull`.
2. `CarpetChannel.decodeLoomHeader(carpetFull)` → `LoomHeader` (flags, col, row,
   ownBytes, totalBytes, guestCount, guestDescs, dataOffset).
3. Compute total cargo size = `dataOffset + ownBytes + guestTotal`.
4. Trim carpet to min(totalCargo, 8192) bytes.
5. If `LOOM_FLAG_SHADE` and cargo > carpet: decode shade channel.
6. If `LOOM_FLAG_BANNERS` and cargo > carpet+shade: collect and decode overflow
   banners.
7. Assemble `cargo = carpet || shade || overflow`.
8. **Mux receiver** (`LOOM_FLAG_MUX_RX`): extract own segment from
   `cargo[dataOffset..dataOffset+ownBytes]`, add to `MuxBuffer` at offset 0.
9. **Non-receiver**: extract zstd frame from `cargo[dataOffset..dataOffset+ownBytes]`,
   decompress, call `processDecompressedPayload()`.
10. **CARPET\_ONLY donor**: route guest segments at byte positions
    `dataOffset + ownBytes + guestOffset` per LOOM guest descriptor.
11. **CARPET/CARPET\_SHADE donor with MG banners**: parse `MG` banner
    descriptors and route guest bytes.

### 8D — Mux receiver / donor decode

**MuxBuffer**: a `MuxBuffer(total)` is allocated lazily when the first segment
for a receiver arrives. Fields: `data[total]`, `received` (byte count so far),
`mapId`, `mapState`, `frame` (the item frame entity).

`routeMuxSegment(key, targetOffset, srcBytes, srcPos, len)`:
- If buffer exists: copy bytes directly.
- Else: buffer in `muxPendingOffsets` / `muxPendingBytes` lists, applied
  when the LR/LB banner is later scanned.

`checkMuxCompletion(key, client)`:
- If `buf.received >= buf.data.length`:
  - Remove buffer.
  - `Zstd.decompress(buf.data, frameContentSize)` → `full`.
  - Call `processDecompressedPayload()`.

**MG banner format**: `MG<seqIdx:2><ownLen:4><tCol:2><tRow:2><tOffset:8><tLen:4>`
where `seqIdx` is a sort key, `ownLen` is the length of this donor's own zstd
frame (to find where guest bytes start), `tCol/tRow` identify the receiver tile,
`tOffset` is the byte position within the receiver's assembled payload, `tLen`
is the byte count.

### 8E — Shared post-decompression logic

`processDecompressedPayload(client, mapId, mapState, frameEntity, full)`:

1. If `full.length == 16384`: v0 payload (no manifest) → `paintMap()`.
2. Parse manifest: `PayloadManifest.fromBytes(full)`.
3. If `manifest.colorCrc32 >= 0`: verify CRC32 of `full[offset..offset+16384]`.
4. If future version: render frame 0 without metadata.
5. Store `title` / `username` in `mapMeta` for in-game action-bar display.
6. If `manifest.animated() && frameCount > 1 && frameEntity != null`:
   - Call `extractFrames(full, manifest)` to decode all frames (handles delta
     and sparse formats).
   - Create `AnimatedMapState` and register in `animatedMaps`.
   - Mark `animSyncGroupsDirty = true`.
   - `paintMap()` with frame 0.
7. Else: `paintMap()` with `full[offset..offset+16384]`.

`paintMap(client, mapId, mapState, mapColors)`:
1. Save original colors in `rawColors.computeIfAbsent(...)` (for toggle).
2. Store decoded colors in `decodedColors.put(...)`.
3. If `decodingEnabled = false`: return (raw mode).
4. Unlock `mapState` if locked (the MapMipMapMod compatibility requirement).
5. `System.arraycopy(mapColors, 0, mapState.colors, 0, 16384)`.
6. `client.getMapTextureManager().setNeedsUpdate(mapId, mapState)` — triggers
   GPU texture upload on the next render frame.

---

## Animation pipeline

**Registration**: `AnimatedMapState` is created in `processDecompressedPayload()`
when the payload is animated and a valid `ItemFrameEntity` reference is available.
Fields: `mapId`, `frames[frameIndex][16384]`, `delaysMs[]`, `loopCount`,
`framePos`, `manifestCols`, `manifestRows`, `syncUsername`, `syncTitle`.

**Sync groups**: tiles that share the same `syncKey()` (grid dimensions,
frame count, author, title) advance together. The sync map is rebuilt lazily
when `animSyncGroupsDirty = true`.

**Advancement** (`advanceAnimatedFrames`, called from `WorldRenderEvents.END`):
1. Skip if `decodingEnabled = false`.
2. For each sync group: check if any in-range member is due to advance
   (`currentMs - lastAdvanceMs >= currentDelayMs()`).
3. If any is due: advance all group members to `(currentFrame + 1) % frameCount`.
4. Respect `loopCount` (0 = infinite; > 0 = stop after N loops).
5. Only call `paintMap()` for members within `SCAN_RADIUS` (32 blocks).

The render-thread tick rate (60–144 Hz) means frame delays shorter than one
game tick (50 ms) are honored accurately without integer rounding.

**Preview animation** (`/loominary preview`): `MapBannerDecoder.registerAnimatedFromChunks()`
re-runs the full decode path from chunks and registers animation for a map
that was painted via the preview command, so it animates even without
banner markers.

---

## Mux pooling (cross-tile redistribution)

**Trigger**: `/loominary mux`

When one or more tiles are over their per-tile payload budget, mux pooling
redistributes excess bytes across blank "donor" tiles. The encoder appends
donor-only `TileData` entries (`isDonorOnly = true`) and writes physical cargo
assignments into `muxCargoB64`.

**Encoding side** (in `LoominaryCommand`):

For CARPET/CARPET\_SHADE donors using the **MG banner format**:
- Receiver's own zstd frame goes into its carpet/shade/overflow channels.
- Overflow bytes are split across donors, each carrying a contiguous segment.
- Each donor's banner list contains hex-indexed CJK banners for the combined
  own-frame + guest bytes, plus `MG` routing banners.

For CARPET\_ONLY tiles using the **LOOM guest descriptor format**:
- The LOOM header of the donor tile contains `guestDescs[]` pointing directly
  to byte ranges within the donor's cargo.

For BANNER tiles using the **LB receiver format**:
- Receiver's own segment in hex-indexed CJK banners; `LB` header banner
  replaces the first chunk slot.
- Donors carry guest bytes in their banner lists.

**Decoding side**: scan order is undefined. The decoder handles arrival of
donors before receivers via the pending-segment queues
(`muxPendingOffsets` / `muxPendingBytes`). `checkMuxCompletion()` fires
whenever any segment arrives; the MuxBuffer tracks `received` vs `data.length`.

---

## Key invariants and size budgets

| Invariant | Value | Source |
|---|---|---|
| Map color array size | 16,384 bytes (128×128) | `MapState.colors` |
| Max banner name length | 50 chars | Minecraft server limit |
| Hex index prefix | 2 chars | `"00"`–`"3E"` |
| CJK payload chars per banner | 48 | 50 − 2 index |
| Bytes per CJK banner | 84 | 48 × 14 bits / 8 |
| Max banners (BANNER mode, 2b2t) | 63 | Empirically validated |
| Carpet channel capacity | 8,192 bytes | 16,384 pixels / 2 |
| LOOM header overhead | 16 bytes | Fixed header |
| Usable carpet (LOOM) | 8,176 bytes | 8,192 − 16 |
| Shade channel capacity | 2,016 bytes | 31×512 + 32 bits / 8 |
| Max compressed bytes (CARPET\_SHADE) | 15,482 bytes | carpet + shade + overflow |
| Banner name capacity | 50 Unicode code points | Minecraft NBT limit |
| Shade encoding (unobtainable shade 3) | Excluded unless `allShades=true` | `UNOBTAINABLE_SHADE_ID = 3` |
| Decoder scan radius | 32 blocks | `SCAN_RADIUS` constant |
| Decoder scan interval | 20 ticks (1 second) | `SCAN_INTERVAL_TICKS` |
| Max manifest version understood | 6 | `CURRENT_VERSION` |

---

## Codec modes at a glance

```
BANNER
  payload → zstd → CjkCodec → 63 × "NN<48 CJK>" banners
  budget: ~5,290 bytes compressed

CARPET   (LOOM, no shade)
  payload → zstd → LOOM header || payload →
    carpet: 8,176 bytes as nibble pairs
    overflow: CjkCodec → up to 63 × "NN<48 CJK>" banners
  budget: ~13,466 bytes compressed

CARPET_SHADE   (LOOM, default)
  payload → zstd → LOOM header || payload →
    carpet: 8,176 bytes as nibble pairs
    shade: 2,016 bytes as staircase heights
    overflow: CjkCodec → up to 63 × "NN<48 CJK>" banners
  budget: ~15,482 bytes compressed

CARPET_ONLY   (LOOM, no overflow)
  payload → zstd → LOOM header || payload →
    carpet: 8,176 bytes as nibble pairs
    shade: 2,016 bytes as staircase heights
  budget: ~10,192 bytes compressed
```
