# MC Map Palette Score — Browser Extension

Scores every image on any page by how well the Minecraft 1.21.4 legal
map-colour palette can represent it, then ranks them in an overlay.

Unlike the bookmarklet, **this extension fetches images via its background
service worker** which has `<all_urls>` permission — so it bypasses the
same-origin policy that blocks `canvas.getImageData()` for cross-origin images.
Every visible image gets scored, not just same-origin ones.

Uses **Manifest V2** for maximum compatibility — works in Firefox and Chrome/Edge/Brave today.

## Install (Firefox)

1. Open `about:debugging#/runtime/this-firefox`.
2. Click **Load Temporary Add-on**.
3. Select `manifest.json` inside this directory.
   *(Temporary — reloads on browser restart. For a permanent install, zip the folder and submit to AMO for signing, or set `xpinstall.signatures.required = false` in `about:config` for local development.)*

## Install (Chrome / Edge / Brave)

1. Open `chrome://extensions` (or `edge://extensions`).
2. Enable **Developer mode** (toggle, top-right).
3. Click **Load unpacked**.
4. Select this `extension/` directory.
5. The icon appears in your toolbar. Click it on any page with images.

## Usage

Click the extension icon on any web page. An overlay appears showing all
images ranked by palette coverage score:

- **≥ 75%** 🟢 Good fit — image colours are well-represented by the MC palette
- **50–74%** 🟡 Moderate — some colour loss expected
- **< 50%** 🔴 Poor fit — image has colours the palette can't represent well

Close the overlay with the **✕** button or by navigating away.

## What the score means

For each pixel: find the nearest of the 186 legal MC 1.21.4 map colours
(shades 0–2 of all 61 base colours) using OKLab ΔE distance.
**Score = % of pixels within ΔE ≤ 0.05 of their nearest palette entry.**

This is the same metric used in the Loominary web editor's import page.
It measures palette suitability, not dither quality — dithering and chroma
boost settings don't affect it.

## Files

| File | Purpose |
|------|---------|
| `manifest.json` | Extension metadata and permissions |
| `background.js` | Service worker — handles cross-origin image fetches |
| `content.js` | Injected into the page — palette data, scoring, UI overlay |
| `icon.png` | Toolbar icon |
