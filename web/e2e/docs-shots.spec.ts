import { test, expect } from '@playwright/test';
import { loadFixture, proceedToEditor, proceedToExport, shotPath } from './helpers';

/**
 * Produces the wiki's web-editor screenshots (docs/wiki/assets/web/*.png).
 * Each test drives the real production build to a deterministic UI state and
 * captures it. Regenerate after UI changes with: npm run shots
 */

test('import: empty dropzone', async ({ page }) => {
  await page.goto('/loominary/');
  await expect(page.getByText(/Drag & drop/i).first()).toBeVisible();
  await page.screenshot({ path: shotPath('import-dropzone'), fullPage: false });
});

test('import: quantized preview + settings', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await page.screenshot({ path: shotPath('import-preview'), fullPage: false });
});

test('import: animated GIF', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await page.screenshot({ path: shotPath('import-gif'), fullPage: false });
});

test('editor: canvas + palette panel', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await page.screenshot({ path: shotPath('editor-overview'), fullPage: false });
});

test('export: static single tile', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample.png');
  await proceedToEditor(page);
  await proceedToExport(page);
  await page.screenshot({ path: shotPath('export-overview'), fullPage: false });
});

test('export: multi-tile 2x1', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-wide.png');
  await page.screenshot({ path: shotPath('import-multitile'), fullPage: false });
  await proceedToEditor(page);
  await page.screenshot({ path: shotPath('editor-multitile'), fullPage: false });
  await proceedToExport(page);
  await page.screenshot({ path: shotPath('export-multitile'), fullPage: false });
});

test('export: animated', async ({ page }) => {
  await page.goto('/loominary/');
  await loadFixture(page, 'sample-anim.gif');
  await proceedToEditor(page);
  await proceedToExport(page);
  await page.screenshot({ path: shotPath('export-animated'), fullPage: false });
});
