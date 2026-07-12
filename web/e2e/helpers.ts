import { Page, expect } from '@playwright/test';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const FIXTURES = join(HERE, 'fixtures');
export const SHOTS_DIR = join(HERE, '..', '..', 'docs', 'wiki', 'assets', 'web');

/** Loads an image fixture through the ImportPage's hidden file input. */
export async function loadFixture(page: Page, filename: string) {
  await page.setInputFiles('input[type=file]', join(FIXTURES, filename));
  // Quantized preview is ready when the pan/zoom hint replaces the progress text.
  await expect(page.getByText('Scroll to zoom · drag to pan')).toBeVisible({ timeout: 120_000 });
}

/** Import → Editor. Assumes a fixture is already loaded. */
export async function proceedToEditor(page: Page) {
  const btn = page.getByRole('button', { name: /Proceed to Editor/ });
  await expect(btn).toBeEnabled({ timeout: 120_000 });
  await btn.click();
  // The editor is up once its canvas exists.
  await expect(page.locator('canvas').first()).toBeVisible({ timeout: 120_000 });
  await page.waitForTimeout(500); // first paint settles
}

/** Editor → Export via the step bar. */
export async function proceedToExport(page: Page) {
  await page.getByText('③ Export').first().click();
  await expect(page.getByRole('button', { name: /Export ZIP/ })).toBeVisible({ timeout: 180_000 });
  await page.waitForTimeout(500);
}

export function shotPath(name: string) {
  return join(SHOTS_DIR, `${name}.png`);
}
