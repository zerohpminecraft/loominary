# Web editor · Step 3: Export

The export step encodes your composition into the actual bytes the mod will decode, shows you exactly what fits where, and packages everything for the game.

![The export page: codec, metadata, and per-tile stats](assets/web/export-overview.png)

## Codec: how the data travels

Each 128×128 map tile has a byte budget determined by the **codec** — which vanilla data channels carry the payload:

| Channel | Capacity | How |
|---|---|---|
| **Carpet** | 8,192 B | 16 carpet colors = 4-bit nibbles read back through the map's own colors |
| **Shade** | +2,016 B | carpet *height* variation (staircase schematic instead of flat) |
| **Banners** | +~5,200 B | up to 62 named banners, 84 bytes each (14-bit CJK alphabet) |

The default codec (carpet + banners + shade) gives **~15,400 compressed bytes per tile** — most images fit comfortably after zstd compression. The codec picker shows each mode's budget and what your composition needs; the per-tile stats show carpet rows, overflow banner count, and bytes with an over-budget warning if you exceed it. A banner-only codec also exists for the no-building workflow — see [Banner-Only Mode](Banner-Mode-Legacy).

## Metadata

- **Title** — embedded in the payload, shown to whoever decodes it (defaults to the filename).
- **Author** — your name, also embedded.
- Every payload carries a CRC32 integrity check and a random salt, so re-exports are always distinguishable.

## Password protection

Add one or more passwords to encrypt the payload (AES-256-GCM, PBKDF2-derived keys — each password is an independent "key slot"). Only players who've run `/loominary password add <pw>` see the art; everyone else's mod shows a lock icon. Details: [Encryption & Sharing](Encryption-and-Sharing).

## Mux: balancing multi-tile budgets

On multi-tile grids, busy tiles can blow their budget while plain tiles have room to spare. **Mux** redistributes the overflow into donor tiles automatically. The export page shows the allocation; the mod reassembles it transparently. Details: [Multi-Tile & Mux](Multi-Tile-and-Mux).

## Animations

Animated compositions are encoded with a **lossless AV1** codec (or optional **lossy** mode for dithered content that won't compress — the preview shows the exact decoded result before you commit). Multi-tile animations encode as one seamless stream. Details: [Animated Art](Animated-Art).

![Export of an animated composition](assets/web/export-animated.png)

## The ZIP

**⬇ Export ZIP** produces:

- `loominary_state.json` → your game's `config/` folder — this is the payload
- `loominary_carpet_r<row>_c<col>.litematic` → your `schematics/` folder, one per tile (carpet codecs only)
- `preview.png` / `preview.mp4` — the exact decoded result
- `README.txt` — offline install instructions

Then head in-game: **[Placing your art](In-Game-Placement)**.
