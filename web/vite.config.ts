import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

export default defineConfig(({ command }) => ({
  // Project GitHub Pages serves under https://<user>.github.io/<repo>/, so built
  // asset URLs must be prefixed with /loominary/. The dev server stays at / .
  base: command === 'build' ? '/loominary/' : '/',
  plugins: [preact()],
  worker: {
    format: 'es',  // ESM workers support dynamic imports (needed by compress-worker)
  },
  build: {
    target: 'es2022',
    // Inline the zstd WASM as a base64 data URL so the bundle is
    // truly self-contained (no extra network requests).
    assetsInlineLimit: 1024 * 1024, // 1 MB
  },
  optimizeDeps: {
    // @bokuweb/zstd-wasm is Emscripten-based; pre-bundling breaks its wasm URL resolution
    // in dev (the wasm 404s → "magic number" error).  Exclude it so it's served from source.
    exclude: ['@bokuweb/zstd-wasm'],
  },
}));
