/**
 * Node test for manifest v7 (flags2 / FLAG2_SRGB) in web/src/manifest.ts.
 *
 * CANONICAL_HEX is the byte-parity anchor shared with the Java twin
 * (PayloadManifestTest.v7_matchesWebCanonicalBytes) — both writers must produce these exact
 * bytes for the same inputs, so a drift in either implementation fails its test.
 *   node test/manifest-v7.test.mjs
 */
import { build } from 'esbuild';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

async function load(p) {
  const o = join(tmpdir(), `t-${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`);
  await build({ entryPoints: [p], bundle: true, format: 'esm', outfile: o, platform: 'node', logLevel: 'silent' });
  return import(pathToFileURL(o).href);
}

const m = await load('src/manifest.ts');

const CANONICAL_HEX =
  '072962020201000000abcd064172746973740b46756c6c20436f6c6f75721122334400040002010001';

// Same inputs as the Java parity test.
const flags = m.FLAG_ANIMATED | m.FLAG_AV1 | m.FLAG_AV1_LOSSY;
const header = m.toBytesV7(flags, m.FLAG2_SRGB, 2, 2, 1, 0, 0xABCD, 'Artist', 'Full Colour', 0x11223344, 4, 2);
assert.equal(Buffer.from(header).toString('hex'), CANONICAL_HEX, 'v7 writer must match the canonical bytes');
assert.equal(header[1], header.length, 'headerSize must equal header length');

// Roundtrip through the payload shape: header ++ delay prefix ++ stand-in stream bytes.
const delays = [40, 60, 80, 100];
const prefix = m.delayPrefixBytes(4, delays);
assert.equal(prefix.length, 8);
const combined = new Uint8Array([...header, ...prefix, 1, 2, 3, 4]);
const p = m.fromBytes(combined);
assert.equal(p.manifestVersion, 7);
assert.ok(m.srgb(p), 'FLAG2_SRGB must survive roundtrip');
assert.ok(m.animated(p) && m.av1(p) && m.av1Lossy(p));
assert.equal(p.frameCount, 4);
assert.equal(p.loopCount, 2);
assert.equal(p.nonce, 0x11223344);
assert.equal(p.username, 'Artist');
assert.equal(p.title, 'Full Colour');
assert.deepEqual(p.frameDelays, delays, 'v7 per-frame delays parse from the prefix');
assert.equal(m.av1StreamOffset(p), header.length + prefix.length, 'stream offset = header + prefix');

// Static sRGB: frameCount=1, FLAG_ANIMATED unset, 2-byte prefix still present.
const sh = m.toBytesV7(m.FLAG_AV1 | m.FLAG_AV1_LOSSY, m.FLAG2_SRGB, 1, 1, 0, 0, 0, null, null, 0, 1, 0);
const sp = m.fromBytes(new Uint8Array([...sh, ...m.delayPrefixBytes(1, [100]), 9, 9]));
assert.equal(sp.manifestVersion, 7);
assert.ok(m.srgb(sp) && !m.animated(sp));
assert.equal(sp.frameCount, 1);
assert.equal(m.av1StreamOffset(sp), sh.length + 2);

// flags2 defaults to 0 for older versions; the v5 trailing-table helper must not fire on v7.
const v4 = m.fromBytes(m.toBytesAnimated(m.FLAG_ANIMATED, 1, 1, 0, 0, 0, null, null, 0, 2, 0, [100]));
assert.equal(v4.flags2, 0);
assert.ok(!m.srgb(v4));
assert.equal(m.trailingDelayBytes(header, delays).length, 0, 'v7 must not produce a trailing table');

// Max-string header still fits the u8 header_size.
const maxH = m.toBytesV7(flags, m.FLAG2_SRGB, 8, 8, 7, 7, 0xFFFFFFFF, 'A'.repeat(16), 'B'.repeat(64), -1 >>> 0, 65535, 65535);
assert.ok(maxH.length <= 255, 'v7 header must fit u8 header_size');
assert.equal(maxH[1], maxH.length);

console.log('manifest-v7: canonical bytes + roundtrips OK');
