/**
 * Screenshots card HTML pages (cards/*.html) to docs/videos/out/card-<name>.png
 * at 1920×1080 via Playwright.
 *   cd web && node ../docs/videos/tools/record-cards.mjs [name ...]
 * With no args, renders every cards/*.html except fm.html (that one is a
 * scene-driven animation recorded by record-fm.mjs).
 */
import { createRequire } from 'node:module';
// Resolve playwright from the CALLER's tree (run from web/), not this file's location.
const req = createRequire(process.cwd() + '/package.json');
const { chromium } = req('@playwright/test');
import { readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, '../out');

let names = process.argv.slice(2);
if (!names.length) {
  names = (await readdir(join(HERE, 'cards')))
    .filter(f => f.endsWith('.html') && f !== 'fm.html')
    .map(f => f.replace(/\.html$/, ''));
}

const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
for (const name of names) {
  await p.goto('file://' + join(HERE, 'cards', `${name}.html`));
  await p.waitForTimeout(300);
  await p.screenshot({ path: join(OUT, `card-${name}.png`) });
  console.log(name, 'rendered');
}
await b.close();
