# TODO

Living roadmap for Loominary. Items grouped by rough priority. Strikethrough or remove items as they ship.

## Foundational — do these first; they unblock or de-risk everything else

### Versioned payload format
Add a "manifest" structure (probably the first chunk per tile, or a reserved prefix) that records:
- Format version number
- Capability flags (dithering used, palette mode, etc.)
- Tile dimensions (for grid awareness without requiring spatial inference)
- Source filename hash or checksum

Without this, any future format change risks breaking everyone's existing encoded maps. With it, the decoder can detect old payloads and handle them gracefully, and new features can be opt-in via flags.

The manifest itself needs a stable, versioned format — meta-versioning. Probably a single byte for "manifest version" followed by a fixed schema for that version, with v0 being whatever we pick first.

### Continuous integration via GitHub Actions
- Build the mod on every push and PR
- Run tests (once we have them)
- On `v*` tag push, automatically build and attach jar to GitHub release
- Eventually: auto-publish to Modrinth/CurseForge on tagged release

### Test infrastructure
Currently zero tests. Targets that would benefit most from unit testing:
- `PngToMapColors.convert` — color quantization is deterministic, easy to write fixtures for
- `PngToMapColors.reduceToFit` — the reduction algorithm has clear before/after invariants
- `MapBannerDecoder.reassemblePayload` — chunk reassembly with various edge cases
- `LitematicExporter` — schematic NBT structure validation

End-to-end testing the actual Minecraft integration is harder; probably out of scope for now.

## High-value features

### Auto-right-click of banners with map
Once banners are placed in a 128×128 area and the player is holding the right map, automate the right-click step. Each banner needs to be in range (~4.5 blocks) and visible. Probably:
- Walk near each banner using vanilla movement
- Look at it
- Send `PlayerInteractEntityC2SPacket`
- Verify the marker appeared
- Move to the next

Anti-cheat-friendly version: do this slowly and with realistic movement deltas. Anti-cheat-aggressive version: just spam interactions and hope for the best.

This is the single most tedious manual step in the workflow.

### Wall-grid preview
Currently `/loominary preview` paints only the active tile onto the crosshair-targeted map. Extend it to:
- Detect that the active batch has multiple tiles
- Look at any framed map in a wall of framed maps
- Identify the grid layout from adjacent framed maps in 6 directions
- Paint the entire grid in the appropriate orientation
- Suppress decorations across all painted maps

Bonus: visual indicator showing which tile is going to which map before committing.

### Floyd-Steinberg dithering (compression-aware variant)
Differentiation angle vs. existing mapart tools:
- Operates on the full ~248-color palette including unbuildable shades, not just the buildable subset
- Compression-aware: prefers dither patterns that compress well, since blown budgets are a real cost
- Live in-world preview of multiple dither algorithms (Floyd-Steinberg, Atkinson, Bayer, Sierra, none)
- Per-region dither control via the in-world editor

Implementation lives in `PngToMapColors.convert`. Per-import flag to opt in/out. Default probably should be off until we know how it interacts with the compression budget in practice.

### LAB color space matching
Switch nearest-color matching from RGB Euclidean distance to CIE LAB or Oklab. Better perceptual results everywhere — encoding, palette reduction, dither error distribution.

Maybe 30 lines. Applies automatically once switched.

## Medium-value features

### In-world editor mode
A modal editor for live, in-world editing of the active tile. Keybind to enter/exit. Probably a fullscreen `Screen` subclass that shows the map at 4×–8× scale with pan/zoom, opened from a framed map at the player's crosshair.

Tools to support, roughly in priority order:
- Pixel paintbrush with selectable size and color
- Fill bucket
- Color picker (eyedropper)
- Rectangle / lasso / magic-wand region selection
- Per-region operation: re-quantize with different palette settings
- Per-region operation: apply dithering (Floyd-Steinberg / Atkinson / Bayer / Sierra / none)
- Per-region operation: restrict palette to a subset (e.g., "only carpet colors in this area")
- Undo / redo

On exit, re-encodes the modified map-color bytes back into the active tile's chunks. From there the existing anvil/preview/export pipeline takes over.

Open design questions:
- Destructive edits vs. layered editing on top of the source image (v1 = destructive, undo-stack only)
- How fine the brush gets (1 map pixel = ~0.78 blocks, so the GUI needs to abstract from world coordinates)
- Whether to expose source-image awareness (re-quantize from original vs. re-quantize from current state)

This is a big feature — probably 1500+ lines of GUI code, tool framework, undo stack, and re-quantization plumbing — but it's purely client-side and unlocks a workflow no other mapart tool offers: edit the actual final pixel-perfect output, in-world, with live preview, and immediately re-encode.

The per-region dither idea from the Floyd-Steinberg entry naturally lives inside this editor.

### Animated map art
Encode multiple frames into a single payload. Decoder cycles frames on a timer, re-rendering the map texture.
- Frame data layout in payload TBD — probably a manifest field declaring frame count + delay
- Cycle pauses if the player is far from the map (no point burning ticks)
- Need to think about how this interacts with multi-tile murals

Big creative unlock. People will make signs, banners with mottos that scroll, etc.

### Encoding throughput optimization
Brute-force pixel-by-pixel quantization is slow on larger grids. Replace with:
- Precomputed RGB→nearest-map-color lookup, structured as octree or 3D LUT
- For palette reduction: cache neighbor distances between iterations rather than recomputing
- Possibly parallelize per-tile encoding when batch has many tiles

Makes everything snappier; especially noticeable on murals.

### Shareable payload export/import
A `/loominary export payload` that writes the active tile's chunks to a small text file. A `/loominary import payload <file>` that consumes one. Lets people swap pre-encoded designs without sharing the source image or dealing with grid math.

Good fit for a community-driven gallery model.

### Hotkey for export
Add a key binding for `/loominary export`. One-line addition.

### Color histogram in palette command
`/loominary palette` currently lists the rarest 10 colors as text. Augment with a visual representation — clickable widget, or just a colored bar chart in chat.

## Lower-priority / nice-to-have

### Auto-reduce heuristic improvements
Detect that an image won't fit *before* the first compression pass, give early warning. Current behavior: encode → compress → check size → reduce iteratively. Smarter: estimate distinct color count and predict whether reduction will be needed.

### Server-side coordinated mode
Companion server-side mod. When both sides are present, embed data more compactly (binary vs. base64 — roughly doubles capacity per banner). Falls back to current encoding when only client-side. Optional.

### Steganographic mode
Hide payload in LSBs of an otherwise-unmodified map's colors. Decoder recovers data; a non-Loominary user sees an apparently-normal map of real terrain. Much smaller capacity but truly invisible.

Nobody else does this. Real differentiation, but might be a solution looking for a problem.

### Litematica API integration
Push schematics directly into Litematica's placement list rather than writing a `.litematic` file the user has to manually move. Removes a step from the workflow.

### Auto-preview while building
Toggle to live-update preview as banners are placed. Useful when iterating on placement; needs lots of testing to avoid spamming texture re-uploads.

### Internationalization
Pull all user-facing strings into translation keys. Probably premature until the mod has international users; current English-only code is a wash.

### Documentation site
GitHub Pages with screenshots, example workflows, gallery. The README is doing fine for now but a real docs site would help adoption.

### Voxel/3D-aware encoding
Encode block-level reconstruction info — "build this structure with these blocks at these positions" — instead of pixel data. Different domain entirely; the banner-as-data-carrier mechanism generalizes but the use case is unclear.

## Explicitly rejected

### `/loominary import url <url>`
Considered. Rejected on security grounds — letting users trigger downloads from arbitrary URLs at someone else's request creates obvious abuse vectors. Tricking someone into downloading a malicious file just by getting them to run a command is not a tradeoff worth making for the convenience of skipping a file copy.

### "Skip the right-click step" decoder
Considered. The idea was to detect named banners directly in-world, no map markers needed. Rejected because it breaks the mod's core portability story: the whole point of map-based encoding is that a framed map in someone's vault is durable and shareable across players, while banners can be destroyed or griefed. Removing the right-click step means losing the framed-map artifact entirely, which kills the value proposition on servers like 2b.

## Maybe someday

- MIDI-synced animated mapart
- Web-based encoder/preview tool (lives outside the mod)