/**
 * Produces an ENCRYPTED demo LOOM state for the ep06 capture: takes the demo
 * state's compressed payload and runs it through the web editor's encrypt()
 * (AES-256-GCM envelope, PBKDF2 password slot), then writes it back as the
 * tile's carpetCompressedB64. The mod places this as a carpet platform; scanning
 * it without the password paints the padlock lock screen, and
 * `/loominary password add <pw>` reveals it.
 *
 *   cd web && node scripts/gen-enc-state.mjs <password>   (default: hunter2)
 *
 * Output: docs/tools/anim-demo-enc-state.json  (+ copied to run/loominary_saves/demoenc.json by the wrapper)
 */
import { build } from 'esbuild';
import { writeFile, rm, readFile } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const webDir  = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(webDir, '..');
const password = process.argv[2] || 'hunter2';

const entry = join(webDir, `.enc-entry-${process.pid}.ts`);
await writeFile(entry, `export { encrypt } from './src/encryption.ts';\n`);
let mod;
try {
  const o = join(webDir, `.enc-bundle-${process.pid}.mjs`);
  try {
    await build({ entryPoints: [entry], bundle: true, format: 'esm', outfile: o, platform: 'node', logLevel: 'silent' });
    mod = await import(pathToFileURL(o).href);
  } finally { await rm(o, { force: true }); }
} finally { await rm(entry, { force: true }); }

const statePath = join(repoDir, 'docs/tools/anim-demo-state.json');
const state = JSON.parse(await readFile(statePath, 'utf8'));
const tile = state.tiles[0];

const compressed = Buffer.from(tile.carpetCompressedB64, 'base64');
const envelope = await mod.encrypt(new Uint8Array(compressed), [password], 'Loominary', 'Secret Ball');

tile.carpetCompressedB64 = Buffer.from(envelope).toString('base64');
// mark the state so its title reads sensibly if inspected
state.title = 'Secret Ball';

const outPath = join(repoDir, 'docs/tools/anim-demo-enc-state.json');
await writeFile(outPath, JSON.stringify(state, null, 2));
console.log(`anim-demo-enc-state.json: password="${password}", plaintext=${compressed.length}B -> envelope=${envelope.length}B (carpetCompressedB64)`);
