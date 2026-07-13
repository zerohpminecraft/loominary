import { test, expect, Page } from '@playwright/test';
import { loadFixture, proceedToEditor, proceedToExport, shotPath } from './helpers';

/**
 * Screenshots for the "Full color (sRGB)" mode docs (docs/wiki/assets/web/*.png).
 * Same rig as docs-shots.spec.ts: drives the production build to a deterministic
 * state and captures it. Regenerate after UI changes with: npm run shots
 */

/** Switches the import page's Color mode step to Full color (sRGB). */
async function selectFullColor(page: Page) {
  await page.locator('input[name=colorMode][value=srgb]').check();
  // Preview re-renders (600 ms debounce); the pan/zoom hint returns when done.
  await page.waitForTimeout(1500);
  await expect(page.getByText('Scroll to zoom · drag to pan')).toBeVisible({ timeout: 120_000 });
}

/** The ColorPanel container — parent of its (unique) hue slider. */
function colorPanel(page: Page) {
  return page.locator('input[title="Hue"]').locator('..');
}

test('import: full color (sRGB) selected', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await selectFullColor(page);
  await page.screenshot({ path: shotPath('import-fullcolor'), fullPage: false });
});

test('editor: canvas + ColorPanel (sRGB)', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await selectFullColor(page);
  await proceedToEditor(page);
  // sRGB mode swaps the palette panel for the true-colour ColorPanel.
  await expect(colorPanel(page)).toBeVisible({ timeout: 120_000 });
  await page.screenshot({ path: shotPath('editor-fullcolor'), fullPage: false });
});

test('editor: ColorPanel crop', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await selectFullColor(page);
  await proceedToEditor(page);
  const panel = colorPanel(page);
  await expect(panel).toBeVisible({ timeout: 120_000 });
  // The panel container stretches to the full editor column height; cap the crop at the
  // picker widgets so the shot isn't mostly empty background below the Recent row.
  const box = (await panel.boundingBox())!;
  await page.screenshot({
    path: shotPath('colorpanel'),
    clip: { x: box.x, y: box.y, width: box.width, height: Math.min(box.height, 390) },
  });
});

test('export: full color (sRGB) static', async ({ page }) => {
  test.setTimeout(600_000);
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await selectFullColor(page);
  await proceedToEditor(page);
  await proceedToExport(page);
  // The AV1 colour encode runs on mount; the fidelity line appears when done.
  await expect(page.getByText(/avg color error ΔE/)).toBeVisible({ timeout: 300_000 });
  await page.waitForTimeout(1000);
  await page.screenshot({ path: shotPath('export-fullcolor'), fullPage: false });
});

test('export: full color (sRGB) animated', async ({ page }) => {
  test.setTimeout(600_000);
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await selectFullColor(page);
  await proceedToEditor(page);
  await proceedToExport(page);
  await expect(page.getByText(/avg color error ΔE/)).toBeVisible({ timeout: 300_000 });
  // Animated sRGB previews as an MP4 <video> once the compute pass finishes.
  await expect(page.locator('video')).toBeVisible({ timeout: 300_000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: shotPath('export-fullcolor-animated'), fullPage: false });
});
