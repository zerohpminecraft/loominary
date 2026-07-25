import { test, expect } from '@playwright/test';
import { loadFixture, proceedToEditor, proceedToExport } from './helpers';

/**
 * B-roll screen recordings for the video series (docs/videos/). Each test is a
 * deliberately slow, human-paced flow; Playwright records 1080p webm into
 * e2e/media/ (git-ignored). Run: npm run broll
 * Transcode: ffmpeg -i in.webm -c:v libx264 -crf 18 out.mp4
 */

async function pause(page, ms: number) {
  await page.waitForTimeout(ms);
}

test('broll: full wizard walkthrough (ep01)', async ({ page }) => {
  await page.goto('/loominary/');
  await pause(page, 1500);
  await loadFixture(page, 'sample.png');
  await pause(page, 2500);
  // Zoom into the preview a little, human-style.
  const preview = page.locator('canvas').last();
  await preview.hover();
  await page.mouse.wheel(0, -400);
  await pause(page, 1200);
  await page.mouse.wheel(0, 400);
  await pause(page, 1200);
  await proceedToEditor(page);
  await pause(page, 2500);
  await proceedToExport(page);
  await pause(page, 3000);
});

test('broll: adjustments and dithering (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await pause(page, 1500);
  // Drag the first sliders around so the preview visibly reacts.
  for (const slider of await page.locator('input[type=range]').all()) {
    const box = await slider.boundingBox();
    if (!box) continue;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.8, box.y + box.height / 2, { steps: 20 });
    await pause(page, 800);
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 20 });
    await page.mouse.up();
    await pause(page, 600);
  }
  await pause(page, 1500);
});

test('broll: editor tools (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await pause(page, 1500);
  // Paint a few brush strokes on the canvas.
  const canvas = page.locator('canvas').first();
  const box = await canvas.boundingBox();
  if (box) {
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
    await page.mouse.move(cx - 100, cy - 60);
    await page.mouse.down();
    await page.mouse.move(cx + 100, cy + 40, { steps: 40 });
    await page.mouse.up();
    await pause(page, 800);
    // Undo it.
    await page.keyboard.press('Control+z');
    await pause(page, 1200);
  }
  await pause(page, 1500);
});

test('broll: animated GIF flow (ep03)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await pause(page, 2500);
  await proceedToEditor(page);
  await pause(page, 2500);
  await proceedToExport(page);
  await pause(page, 4000);
});

test('broll: multi-tile flow (ep04)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-wide.png');
  await pause(page, 2000);
  await proceedToEditor(page);
  await pause(page, 2500);
  await proceedToExport(page);
  await pause(page, 3000);
});

test('broll: animated editing + export preview (ep01)', async ({ page }) => {
  // Timing marks let the assembler slice around the variable quantize/encode waits:
  // recordVideo starts with the context, so Date.now deltas ≈ video timestamps.
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await pause(page, 1000);
  await proceedToEditor(page);
  await pause(page, 800);
  marks.editorPlay = (Date.now() - t0) / 1000;
  await page.keyboard.press(' ');       // play the animation on the canvas
  await pause(page, 4500);
  await page.keyboard.press(' ');       // pause
  await pause(page, 400);
  for (let i = 0; i < 4; i++) { await page.keyboard.press('>'); await pause(page, 450); }
  await proceedToExport(page);
  // The encoded MP4 preview appears once the AV1 compute finishes.
  const video = page.locator('video');
  await video.waitFor({ state: 'visible', timeout: 300_000 });
  await page.waitForFunction(() => {
    const v = document.querySelector('video');
    return v && v.currentTime > 0.2 && !v.paused;
  }, { timeout: 60_000 });
  marks.exportPreview = (Date.now() - t0) / 1000;
  await pause(page, 5500);
  const { writeFile } = await import('node:fs/promises');
  await writeFile(new URL('./media/anim-marks.json', import.meta.url), JSON.stringify(marks));
});

/* ───────────────────────── Ep02 deep-dive recordings ─────────────────────────
 * One test per episode segment. Where the assembler needs to slice around
 * variable waits or overlay captions (dither montage), the test writes a
 * timing-marks JSON sidecar into e2e/media/ (Date.now deltas ≈ video time,
 * since recordVideo starts with the context).
 */

async function writeMarks(name: string, marks: Record<string, number>) {
  const { writeFile } = await import('node:fs/promises');
  await writeFile(new URL(`./media/${name}.json`, import.meta.url), JSON.stringify(marks));
}

/**
 * The editor renders several canvases (Original preview, frame thumbnails, the
 * main surface) — `.first()` is NOT the editing canvas. Pick the biggest one,
 * and map fractional coords into the art: the comp is drawn fit-to-view as a
 * centered square, so an inscribed square (75% of the short side) is always art.
 */
async function artPoint(page, fx: number, fy: number): Promise<[number, number]> {
  const canvases = page.locator('canvas');
  const n = await canvases.count();
  let best: { x: number; y: number; width: number; height: number } | null = null;
  for (let i = 0; i < n; i++) {
    const b = await canvases.nth(i).boundingBox();
    if (b && (!best || b.width * b.height > best.width * best.height)) best = b;
  }
  if (!best) throw new Error('no canvas found');
  const side = Math.min(best.width, best.height) * 0.75;
  const cx = best.x + best.width / 2, cy = best.y + best.height / 2;
  return [cx + (fx - 0.5) * side, cy + (fy - 0.5) * side];
}

test('broll: pages tour (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await pause(page, 2200);
  await proceedToEditor(page);
  await pause(page, 2200);
  await proceedToExport(page);
  await pause(page, 2500);
});

test('broll: import drop and GIF cameo (ep02)', async ({ page }) => {
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await pause(page, 1200);
  await loadFixture(page, 'sample.png');
  marks.photoLoaded = (Date.now() - t0) / 1000;
  await pause(page, 3500);
  await loadFixture(page, 'sample-anim.gif');
  marks.gifLoaded = (Date.now() - t0) / 1000;
  await pause(page, 4500);
  await writeMarks('ep02-import-marks', marks);
});

test('broll: grid and crop (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-wide.png');   // auto-suggests a 2×1 grid
  await pause(page, 2000);
  const cols = page.locator('input[type=number]').first();
  const rows = page.locator('input[type=number]').nth(1);
  await cols.fill('2'); await pause(page, 700);
  await rows.fill('3'); await pause(page, 2200);   // the mural cameo beat
  await page.getByRole('button', { name: 'auto' }).first().click();
  await pause(page, 1800);
  await page.locator('input[name="cropMode"][value="scale"]').check();
  await pause(page, 1800);
  await page.locator('input[name="cropMode"][value="center"]').check();
  await pause(page, 2200);
});

test('broll: tuning sliders (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await pause(page, 1200);
  // The three Adjustments sliders, dragged one at a time so the preview reacts.
  const sliders = page.locator('input[type=range]');
  for (let i = 0; i < 3; i++) {
    const box = await sliders.nth(i).boundingBox();
    if (!box) continue;
    const cy = box.y + box.height / 2;
    await page.mouse.move(box.x + box.width / 2, cy);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.72, cy, { steps: 18 });
    await pause(page, 900);
    await page.mouse.move(box.x + box.width * 0.55, cy, { steps: 12 });
    await page.mouse.up();
    await pause(page, 900);
  }
  await pause(page, 1500);
});

test('broll: color mode srgb (ep02)', async ({ page }) => {
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await pause(page, 2000);
  marks.srgbOn = (Date.now() - t0) / 1000;
  await page.locator('input[name="colorMode"][value="srgb"]').check();
  await pause(page, 3000);   // smooth 24-bit preview
  marks.paletteBack = (Date.now() - t0) / 1000;
  await page.locator('input[name="colorMode"][value="palette"]').check();
  await pause(page, 2500);
  marks.srgbAgain = (Date.now() - t0) / 1000;
  await page.locator('input[name="colorMode"][value="srgb"]').check();
  await pause(page, 2500);
  await writeMarks('ep02-colormode-marks', marks);
});

test('broll: srgb export panel (ep02)', async ({ page }) => {
  test.setTimeout(420_000);
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await page.locator('input[name="colorMode"][value="srgb"]').check();
  await pause(page, 800);
  await proceedToEditor(page);
  await proceedToExport(page);
  await page.getByText(/Full color \(sRGB\)/).first().waitFor({ timeout: 60_000 });
  // Fidelity readout appears once the AV1 compute lands (click Recompute if stale).
  const recompute = page.getByRole('button', { name: /Recompute/ });
  if (await recompute.isVisible().catch(() => false)) await recompute.click();
  await page.getByText(/PSNR/).waitFor({ timeout: 300_000 });
  marks.fidelity = (Date.now() - t0) / 1000;
  await pause(page, 2000);
  // Nudge the quality slider so the readout visibly belongs to it.
  const q = page.locator('input[type=range]').first();
  const box = await q.boundingBox();
  if (box) {
    const cy = box.y + box.height / 2;
    await page.mouse.move(box.x + box.width * 0.8, cy);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.45, cy, { steps: 15 });
    await page.mouse.up();
  }
  marks.qualityMoved = (Date.now() - t0) / 1000;
  await pause(page, 1500);
  const re2 = page.getByRole('button', { name: /Recompute/ });
  if (await re2.isVisible().catch(() => false)) {
    await re2.click();
    await page.getByText(/PSNR/).waitFor({ timeout: 300_000 });
  }
  marks.fidelity2 = (Date.now() - t0) / 1000;
  await pause(page, 3000);
  await writeMarks('ep02-srgb-marks', marks);
});

test('broll: palette presets (ep02)', async ({ page }) => {
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await pause(page, 1500);
  for (const id of ['flat-fullblock', 'legal', 'all', 'flat-carpet', 'staircase-carpet']) {
    marks[id] = (Date.now() - t0) / 1000;
    await page.locator(`input[name="palette"][value="${id}"]`).check();
    await pause(page, 2400);   // debounce 600 ms + requantize + a beat to look
  }
  // Greyscale gets the portrait.
  await loadFixture(page, 'sample-portrait.png');
  marks.portrait = (Date.now() - t0) / 1000;
  await pause(page, 1200);
  await page.locator('input[name="palette"][value="greyscale"]').check();
  marks.greyscale = (Date.now() - t0) / 1000;
  await pause(page, 2200);
  // Sweep the chroma threshold so colors drop in and out.
  const thr = page.locator('input[type=range]').last();
  const box = await thr.boundingBox();
  if (box) {
    const cy = box.y + box.height / 2;
    await page.mouse.move(box.x + box.width * 0.3, cy);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.9, cy, { steps: 20 });
    await pause(page, 800);
    await page.mouse.move(box.x + box.width * 0.3, cy, { steps: 20 });
    await page.mouse.up();
  }
  await pause(page, 2000);
  await writeMarks('ep02-palette-marks', marks);
});

test('broll: coverage score (ep02)', async ({ page }) => {
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await pause(page, 1200);
  // 16 carpet colors on a sunset photo: the score suffers, visibly.
  await page.locator('input[name="palette"][value="flat-carpet"]').check();
  await page.getByText(/Palette coverage:/).waitFor({ timeout: 30_000 });
  marks.poorFit = (Date.now() - t0) / 1000;
  await pause(page, 3000);
  // Back to the default: green.
  await page.locator('input[name="palette"][value="legal"]').check();
  await pause(page, 1200);
  marks.goodFit = (Date.now() - t0) / 1000;
  await pause(page, 3000);
  await writeMarks('ep02-coverage-marks', marks);
});

test('broll: dither montage (ep02)', async ({ page }) => {
  test.setTimeout(300_000);
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-poster.png');
  await pause(page, 1500);
  // Narration order: error diffusion family, then Atkinson, Bayer, None.
  const ORDER = ['FS', 'Sierra', 'Sierra2', 'SierraL', 'Shiau', 'JJN', 'Stucki', 'Atk', 'Bayer', 'None'];
  for (const label of ORDER) {
    marks[label] = (Date.now() - t0) / 1000;
    await page.getByRole('button', { name: label, exact: true }).click();
    await pause(page, 2400);
  }
  // Wind up on FS and show the tuning knobs.
  await page.getByRole('button', { name: 'FS', exact: true }).click();
  await pause(page, 1000);
  marks.knobs = (Date.now() - t0) / 1000;
  const strength = page.locator('span', { hasText: /FS strength/ }).locator('xpath=following-sibling::input[1]');
  const sbox = await strength.boundingBox().catch(() => null);
  if (sbox) {
    const cy = sbox.y + sbox.height / 2;
    await page.mouse.move(sbox.x + sbox.width * 0.9, cy);
    await page.mouse.down();
    await page.mouse.move(sbox.x + sbox.width * 0.3, cy, { steps: 15 });
    await page.mouse.up();
    await pause(page, 1500);
  }
  await page.getByText('Serpentine scan').click();
  await pause(page, 1500);
  await writeMarks('ep02-dither-marks', marks);
});

test('broll: editor toolbox (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-pixelart.png');
  await proceedToEditor(page);
  await pause(page, 1200);
  const at = (fx: number, fy: number) => artPoint(page, fx, fy);
  // Brush stroke across the sky.
  await page.keyboard.press('b');
  let [x, y] = await at(0.3, 0.15);
  await page.mouse.move(x, y); await page.mouse.down();
  [x, y] = await at(0.62, 0.22);
  await page.mouse.move(x, y, { steps: 30 }); await page.mouse.up();
  await pause(page, 900);
  // Right-click picks up the cap color.
  [x, y] = await at(0.5, 0.3);
  await page.mouse.click(x, y, { button: 'right' });
  await pause(page, 900);
  await page.keyboard.press('Control+z');
  await pause(page, 800);
  // Fill the sky.
  await page.keyboard.press('f');
  [x, y] = await at(0.15, 0.1);
  await page.mouse.click(x, y);
  await pause(page, 1100);
  await page.keyboard.press('Control+z');
  await pause(page, 800);
  // Rect select the stem.
  await page.keyboard.press('s');
  [x, y] = await at(0.3, 0.55);
  await page.mouse.move(x, y); await page.mouse.down();
  [x, y] = await at(0.7, 0.85);
  await page.mouse.move(x, y, { steps: 25 }); await page.mouse.up();
  await page.getByRole('button', { name: /Desel/ }).waitFor({ timeout: 10_000 });
  await pause(page, 1300);
  await page.keyboard.press('Escape');
  // Lasso around the cap.
  await page.keyboard.press('l');
  const path = [[0.25, 0.42], [0.2, 0.2], [0.5, 0.06], [0.8, 0.2], [0.75, 0.42], [0.25, 0.42]];
  [x, y] = await at(path[0][0], path[0][1]);
  await page.mouse.move(x, y); await page.mouse.down();
  for (const [fx, fy] of path.slice(1)) {
    [x, y] = await at(fx, fy);
    await page.mouse.move(x, y, { steps: 12 });
  }
  await page.mouse.up();
  await pause(page, 1300);
  await page.keyboard.press('Escape');
  // Wand the sky.
  await page.keyboard.press('w');
  [x, y] = await at(0.12, 0.08);
  await page.mouse.click(x, y);
  await pause(page, 1600);
  await page.keyboard.press('Escape');
  await pause(page, 1000);
});

test('broll: requantize selection (ep02)', async ({ page }) => {
  test.setTimeout(300_000);
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await pause(page, 1200);
  const at = (fx: number, fy: number) => artPoint(page, fx, fy);
  // Wand the sky (a few clicks to take in the gradient bands).
  await page.keyboard.press('w');
  for (const [fx, fy] of [[0.5, 0.1], [0.3, 0.2], [0.7, 0.25]]) {
    const [x, y] = await at(fx, fy);
    await page.mouse.click(x, y);
    await pause(page, 700);
  }
  await page.getByRole('button', { name: /Desel/ }).waitFor({ timeout: 10_000 });
  marks.wandDone = (Date.now() - t0) / 1000;
  await pause(page, 800);
  // Requantize just the selection: Bayer, from the source image.
  await page.getByRole('button', { name: 'Bayer', exact: true }).click();
  await pause(page, 500);
  await page.getByText('Source image').click();
  await pause(page, 500);
  await page.keyboard.press('r');
  await page.getByText('PREVIEW', { exact: true }).waitFor({ timeout: 120_000 });
  marks.bayerPreview = (Date.now() - t0) / 1000;
  await pause(page, 2800);
  await page.keyboard.press('Enter');   // commit
  marks.bayerCommit = (Date.now() - t0) / 1000;
  await pause(page, 1500);
  await page.keyboard.press('Escape');
  // Lasso the lake, requantize with FS.
  await page.keyboard.press('l');
  const path = [[0.1, 0.7], [0.9, 0.7], [0.9, 0.95], [0.1, 0.95], [0.1, 0.7]];
  let [x, y] = await at(path[0][0], path[0][1]);
  await page.mouse.move(x, y); await page.mouse.down();
  for (const [fx, fy] of path.slice(1)) {
    [x, y] = await at(fx, fy);
    await page.mouse.move(x, y, { steps: 12 });
  }
  await page.mouse.up();
  await pause(page, 900);
  await page.getByRole('button', { name: 'FS', exact: true }).click();
  await pause(page, 500);
  await page.keyboard.press('r');
  await page.getByText('PREVIEW', { exact: true }).waitFor({ timeout: 120_000 });
  marks.fsPreview = (Date.now() - t0) / 1000;
  await pause(page, 2800);
  await page.keyboard.press('Enter');
  marks.fsCommit = (Date.now() - t0) / 1000;
  await pause(page, 2000);
  await writeMarks('ep02-requant-marks', marks);
});

test('broll: filters on selection (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-portrait.png');
  await proceedToEditor(page);
  await pause(page, 1200);
  // Rect-select the lit half.
  await page.keyboard.press('s');
  let [x, y] = await artPoint(page, 0.02, 0.02);
  await page.mouse.move(x, y);
  await page.mouse.down();
  [x, y] = await artPoint(page, 0.52, 0.98);
  await page.mouse.move(x, y, { steps: 25 });
  await page.mouse.up();
  await page.getByRole('button', { name: /Desel/ }).waitFor({ timeout: 10_000 });
  await pause(page, 1000);
  for (const f of ['Smooth', 'Median', 'Sharpen', 'Poster']) {
    await page.getByRole('button', { name: f, exact: true }).click();
    await pause(page, 400);
    await page.getByRole('button', { name: 'Apply (P)' }).click();
    await pause(page, 1600);
    await page.keyboard.press('Control+z');
    await pause(page, 600);
  }
  await pause(page, 1200);
});

test('broll: palette panel (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await pause(page, 1200);
  for (const tip of ['Sort by pixel count', 'Sort dark \u2192 bright', 'Sort grey \u2192 vivid',
                     'Sort by hue (rainbow)', 'Natural byte order']) {
    await page.getByTitle(tip).click();
    await pause(page, 1400);
  }
  // Queue two rare colors and merge them into the active color.
  const swatches = page.locator('div[title^="Byte "]');
  const n = await swatches.count();
  if (n > 4) {
    await swatches.nth(n - 1).click({ modifiers: ['Control'] });
    await pause(page, 900);
    await swatches.nth(n - 2).click({ modifiers: ['Control'] });
    await pause(page, 1400);
    await page.getByRole('button', { name: 'C: Commit' }).click();
    await pause(page, 2000);
  }
  await pause(page, 1200);
});

test('broll: export stats and codecs (ep02)', async ({ page }) => {
  test.setTimeout(300_000);
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await proceedToExport(page);
  await page.getByText(/Payload —/).waitFor({ timeout: 120_000 });
  marks.stats = (Date.now() - t0) / 1000;
  await pause(page, 2500);
  for (const c of ['BANNER', 'CARPET', 'CARPET_SHADE', 'CARPET_BANNERS', 'CARPET_SHADE_BANNERS']) {
    marks[c] = (Date.now() - t0) / 1000;
    await page.locator(`input[name="codec"][value="${c}"]`).check();
    await pause(page, 600);
    const re = page.getByRole('button', { name: /Recompute/ });
    if (await re.isVisible().catch(() => false)) await re.click();
    await pause(page, 1800);
  }
  await pause(page, 1500);
  await writeMarks('ep02-codec-marks', marks);
});

test('broll: export metadata and 3d (ep02)', async ({ page }) => {
  test.setTimeout(300_000);
  const t0 = Date.now();
  const marks: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await proceedToExport(page);
  await page.getByText(/Payload —/).waitFor({ timeout: 120_000 });
  marks.meta = (Date.now() - t0) / 1000;
  await page.getByPlaceholder('Map title…').pressSequentially('Sunset No. 244', { delay: 70 });
  await pause(page, 600);
  await page.getByPlaceholder('Your username…').pressSequentially('mapautist', { delay: 70 });
  await pause(page, 1500);
  // Password cameo.
  marks.password = (Date.now() - t0) / 1000;
  await page.getByText('Encrypt output').click();
  await pause(page, 700);
  await page.getByPlaceholder('Add password…').pressSequentially('hunter2', { delay: 80 });
  await pause(page, 500);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await pause(page, 1800);
  await page.getByText('Encrypt output').click();   // back off — it's a cameo
  await pause(page, 800);
  // 3D schematic orbit — the per-tile 👁 button selects the tile AND flips to 3D.
  marks.viewer3d = (Date.now() - t0) / 1000;
  await page.getByTitle("Preview this tile's schematic in 3D").first().click();
  await pause(page, 2000);
  const v = page.locator('canvas').last();
  const box = await v.boundingBox();
  if (box) {
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx + box.width * 0.25, cy - box.height * 0.1, { steps: 60 });
    await page.mouse.move(cx - box.width * 0.2, cy - box.height * 0.15, { steps: 80 });
    await page.mouse.up();
    await pause(page, 800);
    await page.mouse.wheel(0, -300);
    await pause(page, 1500);
  }
  await pause(page, 1500);
  await writeMarks('ep02-meta-marks', marks);
});

test('broll: sessions restore (ep02)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);   // saves the session
  await pause(page, 800);
  await page.getByText('① Import').first().click();
  await pause(page, 1800);
  const row = page.locator('div[title="Restore this session"]').first();
  await row.waitFor({ timeout: 15_000 });
  await pause(page, 1500);
  await row.click();
  await pause(page, 3000);
});

/* ───────────────────────── Ep03 animated deep-dive ─────────────────────────
 * One recording per script beat. sample-anim.gif (clean 12-frame loop) drives
 * the frame-editor tour; sample-anim-heavy.gif (24-frame dithered plasma) is the
 * lossy hero. Marks sidecars (ep03-*-marks.json) let the assembler slice around
 * the variable quantize/encode waits and caption the Stride/Skip frame counts.
 * Run just these:  npx playwright test --config e2e/playwright.config.ts --project=broll -g ep03
 */

async function readFrameCount(page): Promise<number> {
  const t = await page.getByText(/^\s*\d+\s*\/\s*\d+\s*$/).first().textContent();
  return parseInt((t || '0 / 0').split('/')[1].trim(), 10);
}
async function tryClick(page, loc) {
  try { if (await loc.isEnabled()) { await loc.click(); await pause(page, 850); } } catch { /* skip */ }
}

test('broll: ep03 import drop and info box', async ({ page }) => {
  const t0 = Date.now(); const m: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  m.loaded = (Date.now() - t0) / 1000;
  await pause(page, 3800);            // hold on the "only previews the first frame" info box
  await proceedToEditor(page);
  m.editor = (Date.now() - t0) / 1000;
  await pause(page, 2600);            // frame strip populated
  await writeMarks('ep03-import-marks', m);
});

test('broll: ep03 frame strip', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await pause(page, 1500);
  await page.keyboard.press(' ');    // play (editor listens on window)
  await pause(page, 3800);
  await page.keyboard.press(' ');    // pause
  await pause(page, 700);
  for (let i = 0; i < 5; i++) { await page.getByTitle(/Next frame/).click(); await pause(page, 520); }
  await pause(page, 1500);
});

test('broll: ep03 frame delays', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await pause(page, 1500);
  const ms = page.getByTitle('Frame delay in milliseconds');
  for (const v of ['220', '60', '150']) { await ms.click(); await ms.fill(v); await pause(page, 950); }
  await page.getByTitle(/Next frame/).click(); await pause(page, 500);
  await ms.click(); await ms.fill('300'); await pause(page, 950);   // a different delay on another frame
  await page.getByTitle('Apply this delay to all frames').click();  // then stamp all
  await pause(page, 1900);
});

test('broll: ep03 frame ops', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await pause(page, 1500);
  await tryClick(page, page.getByTitle('Clone current frame (add copy after)'));
  await tryClick(page, page.getByTitle('Add blank frame after current'));
  await tryClick(page, page.getByTitle('Move frame left'));
  await tryClick(page, page.getByTitle('Move frame right'));
  await tryClick(page, page.getByTitle('Delete current frame'));
  for (let i = 0; i < 4; i++) { await tryClick(page, page.getByTitle(/Previous frame/)); }  // scrub back toward frame 1
  await pause(page, 1500);
});

test('broll: ep03 thin stride skip', async ({ page }) => {
  const m: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await pause(page, 1500);
  m.before = await readFrameCount(page);
  const n = page.getByTitle('Thinning factor n for Stride / Skip');
  await n.click(); await n.fill('2'); await pause(page, 800);
  await page.getByRole('button', { name: 'Stride' }).click(); await pause(page, 2200);
  m.afterStride = await readFrameCount(page);
  await pause(page, 1600);
  await n.click(); await n.fill('3'); await pause(page, 800);
  await page.getByRole('button', { name: 'Skip' }).click(); await pause(page, 2200);
  m.afterSkip = await readFrameCount(page);
  await pause(page, 1800);
  await writeMarks('ep03-thin-marks', m);
});

test('broll: ep03 export preview', async ({ page }) => {
  const t0 = Date.now(); const m: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await pause(page, 800);
  await proceedToExport(page);
  const video = page.locator('video');
  await video.waitFor({ state: 'visible', timeout: 300_000 });
  await page.waitForFunction(() => {
    const v = document.querySelector('video');
    return !!v && v.currentTime > 0.2 && !v.paused;
  }, { timeout: 60_000 });
  m.playing = (Date.now() - t0) / 1000;
  await pause(page, 8000);           // let the AV1 preview loop
  await writeMarks('ep03-preview-marks', m);
});

test('broll: ep03 lossy toggle', async ({ page }) => {
  const t0 = Date.now(); const m: Record<string, number> = {};
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim-heavy.gif');
  await proceedToEditor(page);
  await pause(page, 800);
  await proceedToExport(page);
  await pause(page, 3000);           // 24 frames < 60 → raw stats auto-compute (massively over budget)
  m.beforeLossy = (Date.now() - t0) / 1000;
  await page.getByText('⚡ Lossy animation — much smaller').click();
  await pause(page, 2200);           // panel reveals Quality slider + Recompute button
  m.lossyOn = (Date.now() - t0) / 1000;
  const q = page.locator('input[type=range][min="1"][max="100"]').first();
  const recompute = page.getByRole('button', { name: /Recompute at quality/ });
  await q.fill('40'); await pause(page, 800);
  await recompute.click();           // encode as lossy AV1 colour → fidelity readout + fit appear
  await page.getByText(/of pixels differ from the original/).waitFor({ timeout: 120_000 });
  m.recomputed = (Date.now() - t0) / 1000;
  await pause(page, 2600);           // hold on the fidelity readout
  await q.fill('80'); await pause(page, 900);   // stare at the number; nudge it up
  await recompute.click();
  await page.waitForTimeout(4000);   // re-encode at the higher quality
  await pause(page, 2600);
  await writeMarks('ep03-lossy-marks', m);
});

test('broll: ep03 export stats', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await pause(page, 800);
  await proceedToExport(page);
  await pause(page, 3500);           // show the per-tile stats table
  await page.mouse.move(760, 500);
  await page.mouse.wheel(0, 220); await pause(page, 1600);
  await page.mouse.wheel(0, -220); await pause(page, 1600);
  await pause(page, 1800);
});

/* ───────────────────────── Ep04 multi-tile & mux ───────────────────────────
 * Web-editor beats for the mux/multi-tile episode. sample-anim-heavy.gif drives
 * the Mux panel (over budget → auto blank donors); sample-grid32.png (3:2) drives
 * the grid / seamless / stats beats; sample-anim-wide.gif (2×1) drives composite.
 * Run just these:  npx playwright test --config e2e/playwright.config.ts --project=broll -g ep04
 */

test('broll: ep04 mux panel', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim-heavy.gif');   // raw is far over one tile's budget
  await proceedToEditor(page);
  await pause(page, 800);
  await proceedToExport(page);
  await pause(page, 3500);                             // stats compute → mux auto-applies
  await page.getByText(/auto-applied on export|unresolved/).first().scrollIntoViewIfNeeded().catch(() => {});
  await pause(page, 4500);                             // hold on the routing table (+N blank donors)
});

test('broll: ep04 grid and crop', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-grid32.png');        // 3:2 → auto-suggests ~3×2
  await pause(page, 2500);
  await page.getByText('Scale to grid').click().catch(() => {});
  await pause(page, 1400);
  await page.getByText('Center crop').click().catch(() => {});
  await pause(page, 1600);
  const nums = page.locator('input[type=number]');
  if (await nums.count() >= 2) {
    await nums.nth(0).fill('3'); await pause(page, 700);
    await nums.nth(1).fill('2'); await pause(page, 1400);
  }
  await pause(page, 1500);
});

test('broll: ep04 seamless grid', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-grid32.png');
  await pause(page, 2500);                             // import preview shows the stitched, seamless grid
  await proceedToEditor(page);
  await pause(page, 3000);                             // editor shows the whole grid as one canvas
});

test('broll: ep04 editor across boundary', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-grid32.png');
  await proceedToEditor(page);
  await pause(page, 1500);
  const [x1, y1] = await artPoint(page, 0.15, 0.5);
  const [x2, y2] = await artPoint(page, 0.85, 0.5);
  await page.mouse.move(x1, y1);
  await page.mouse.down();
  await page.mouse.move(x2, y2, { steps: 60 });        // brush straight across tile boundaries
  await page.mouse.up();
  await pause(page, 1800);
});

test('broll: ep04 per-tile stats', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-grid32.png');
  await proceedToEditor(page);
  await pause(page, 800);
  await proceedToExport(page);
  await pause(page, 3500);                             // per-tile stats table (6 tiles)
  await page.mouse.move(760, 520);
  await page.mouse.wheel(0, 220); await pause(page, 1600);
  await page.mouse.wheel(0, -220); await pause(page, 1500);
  await pause(page, 1500);
});

test('broll: ep04 composite', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim-wide.gif');     // 2×1 animated → composite eligible
  await proceedToEditor(page);
  await pause(page, 800);
  await proceedToExport(page);
  await pause(page, 3000);
  await page.getByText('⚡ Lossy animation — much smaller').click().catch(() => {});
  await pause(page, 2200);
  const recompute = page.getByRole('button', { name: /Recompute at quality/ });
  await recompute.click().catch(() => {});
  await pause(page, 5000);                             // encodes as one composite stream across both tiles
  await pause(page, 2500);
});
