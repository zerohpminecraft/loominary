import { test, expect } from '@playwright/test';
import { loadFixture, proceedToEditor, proceedToExport, shotPath } from './helpers';

/**
 * Detail screenshots for the deep-dive wiki pages. Comparison crops isolate the
 * quantized preview canvas so dither/metric differences are visible side by side.
 */

async function previewClip(page) {
  // The quantized preview is the large canvas on the right of the import page.
  const canvas = page.locator('canvas').last();
  const box = await canvas.boundingBox();
  if (!box) throw new Error('no preview canvas');
  // Central square crop — enough to compare texture without huge files.
  const size = Math.min(box.width, box.height, 600);
  return { x: box.x + (box.width - size) / 2, y: box.y + (box.height - size) / 2, width: size, height: size };
}

test('dither algorithm comparison crops', async ({ page }) => {
  test.setTimeout(600_000);
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  for (const [label, slug] of [
    ['None', 'none'], ['FS', 'fs'], ['Atk', 'atkinson'],
    ['JJN', 'jjn'], ['Stucki', 'stucki'], ['Bayer', 'bayer'],
  ] as const) {
    await page.getByText(label, { exact: true }).first().click();
    await page.waitForTimeout(5000); // re-quantize runs in a worker
    await page.screenshot({ path: shotPath(`dither-${slug}`), clip: await previewClip(page) });
  }
});

test('match metric comparison crops', async ({ page }) => {
  test.setTimeout(300_000);
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  for (const [label, slug] of [['OKLab', 'oklab'], ['RGB', 'rgb'], ['Chr+', 'chroma']] as const) {
    await page.getByText(label, { exact: true }).first().click();
    await page.waitForTimeout(5000);
    await page.screenshot({ path: shotPath(`metric-${slug}`), clip: await previewClip(page) });
  }
});

test('editor: animation frame strip', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await page.waitForTimeout(1000);
  await page.screenshot({ path: shotPath('editor-frames'), fullPage: false });
});

test('export: banner codec selected', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await proceedToExport(page);
  // Pick the banner-only codec (last radio in CODEC_ORDER).
  await page.locator('input[name=codec]').last().check();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: shotPath('export-codec-banner'), fullPage: false });
});

test('export: password section filled', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await proceedToExport(page);
  await page.locator('input[type=checkbox]').last().check(); // encryption toggle
  await page.getByPlaceholder('Add password…').fill('hunter2');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.getByPlaceholder('Add password…').fill('officers-only');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.waitForTimeout(800);
  await page.screenshot({ path: shotPath('export-password'), fullPage: false });
});

test('export: 3D schematic viewer', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await proceedToExport(page);
  const canvases = page.locator('canvas');
  const n = await canvases.count();
  let clip = null as null | { x: number; y: number; width: number; height: number };
  for (let i = 0; i < n; i++) {
    const box = await canvases.nth(i).boundingBox();
    if (box && box.width > 200 && box.height > 150) clip = box; // last sizeable canvas wins
  }
  await page.screenshot({ path: shotPath('export-schematic-3d'), ...(clip ? { clip } : {}) });
});
