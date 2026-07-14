/**
 * Records the file-manager animations (cards/fm.html scenes) to
 * docs/videos/out/raw/fm-<scene>.webm at 1080p via Playwright.
 *   cd web && node ../docs/videos/tools/record-fm.mjs
 */
import { createRequire } from 'node:module';
// Resolve playwright from the CALLER's tree (run from web/), not this file's location.
const req = createRequire(process.cwd() + '/package.json');
const { chromium } = req('@playwright/test');
import { rename } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const RAW = join(HERE, '../out/raw');
for (const scene of ['install', 'files']) {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1920, height: 1080 },
    recordVideo: { dir: RAW, size: { width: 1920, height: 1080 } } });
  const p = await ctx.newPage();
  await p.goto('file://' + join(HERE, 'cards/fm.html') + '?scene=' + scene);
  await p.waitForFunction(() => document.title === 'done', { timeout: 30000 });
  await p.waitForTimeout(800);
  const path = await p.video().path();
  await ctx.close(); await b.close();
  await rename(path, join(RAW, `fm-${scene}.webm`));
  console.log(scene, 'recorded');
}
