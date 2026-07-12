import { defineConfig } from '@playwright/test';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Screenshot/B-roll harness for the docs (not a test suite — specs produce
 * artifacts). Serves the production build so shots match the deployed app,
 * including the /loominary/ base path.
 *
 *   npm run shots   → docs screenshots into ../docs/wiki/assets/web/
 *   npm run broll   → screen-recording videos into e2e/media/ (git-ignored)
 */
export default defineConfig({
  testDir: '.',
  outputDir: './media/.pw-artifacts',
  timeout: 180_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  webServer: {
    command: 'npx vite build && node e2e/serve.mjs',
    cwd: join(dirname(fileURLToPath(import.meta.url)), '..'),
    url: 'http://localhost:4173/loominary/',
    reuseExistingServer: true,
    timeout: 240_000,
  },
  use: {
    baseURL: 'http://localhost:4173/loominary/',
    viewport: { width: 1600, height: 1000 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
    reducedMotion: 'reduce',
  },
  projects: [
    { name: 'shots', testMatch: /docs-shots\.spec\.ts/ },
    {
      name: 'broll',
      testMatch: /broll\.spec\.ts/,
      use: {
        viewport: { width: 1920, height: 1080 },
        deviceScaleFactor: 1,
        video: { mode: 'on', size: { width: 1920, height: 1080 } },
      },
    },
  ],
});
