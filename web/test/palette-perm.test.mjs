/**
 * Node smoke test for the generated OKLab palette permutation.
 *   node test/palette-perm.test.mjs
 *
 * Locks the TypeScript PERM/INV_PERM tables: inverse bijection over 0–255 and
 * CRC32(INV_PERM) === PERM_CRC32.  The Java side asserts the identical constant
 * (PalettePermutationTest), so matching CRC32s prove web/mod parity.
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

function crc32(data) {
  let crc = 0xffffffff;
  for (let i = 0; i < data.length; i++) {
    crc ^= data[i];
    for (let k = 0; k < 8; k++) crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

let passed = 0;
const ok = (n, c) => { assert.ok(c, n); passed++; };

const { PERM, INV_PERM, PERM_CRC32 } = await load('src/palette-perm.ts');

ok('PERM length 256', PERM.length === 256);
ok('INV_PERM length 256', INV_PERM.length === 256);

const seen = new Uint8Array(256);
let bijection = true;
for (let i = 0; i < 256; i++) {
  const mapByte = INV_PERM[i];
  if (seen[mapByte]) bijection = false;
  seen[mapByte] = 1;
  if (PERM[mapByte] !== i) bijection = false;
}
for (let b = 0; b < 256; b++) if (!seen[b]) bijection = false;
ok('PERM/INV_PERM inverse bijection over 0–255', bijection);

ok('CRC32(INV_PERM) === PERM_CRC32', crc32(INV_PERM) === (PERM_CRC32 >>> 0));

console.log(`palette-perm: ${passed} checks passed (CRC32=0x${(PERM_CRC32 >>> 0).toString(16).padStart(8, '0')})`);
