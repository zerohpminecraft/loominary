/**
 * Node smoke test for the animated-image import gate.
 *   node test/animated-import.test.mjs
 *
 * Locks isMultiFrameType(): the predicate the editor uses to decide whether a
 * dropped/opened image gets split into frames via ImageDecoder (GIF and animated
 * WebP) versus loaded as a single still. WebP is what unlocks full-colour,
 * dither-free animation for sRGB mode — so a regression that stops routing
 * image/webp through the multi-frame path would silently reduce it to one frame.
 *
 * gif-decode.ts only touches browser globals (ImageDecoder, createImageBitmap)
 * inside decodeGifFrames' body, so importing the module and calling the pure
 * isMultiFrameType helper is safe under Node.
 */
import { build } from 'esbuild';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

async function load(tsPath) {
  const out = join(tmpdir(), `loom-${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`);
  await build({ entryPoints: [tsPath], bundle: true, format: 'esm', outfile: out, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(out).href);
}

let passed = 0;
const ok = (n, c) => { assert.ok(c, n); passed++; };

const { isMultiFrameType } = await load('src/gif-decode.ts');

// Animated-capable image types → multi-frame path.
ok('image/gif is multi-frame',  isMultiFrameType('image/gif') === true);
ok('image/webp is multi-frame', isMultiFrameType('image/webp') === true);

// Still images → single-frame path (createImageBitmap).
ok('image/png is single-frame',  isMultiFrameType('image/png')  === false);
ok('image/jpeg is single-frame', isMultiFrameType('image/jpeg') === false);
ok('image/bmp is single-frame',  isMultiFrameType('image/bmp')  === false);

// Non-image / empty MIME (e.g. a mime-less blob) → not multi-frame.
ok('video/webm is not multi-frame', isMultiFrameType('video/webm') === false);
ok('empty mime is not multi-frame', isMultiFrameType('') === false);

console.log(`animated-import: ${passed} checks passed`);
