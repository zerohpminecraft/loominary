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
