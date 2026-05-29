import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

export default defineConfig({
  plugins: [preact()],
  build: {
    target: 'es2022',
    // Inline the zstd WASM as a base64 data URL so the bundle is
    // truly self-contained (no extra network requests).
    assetsInlineLimit: 1024 * 1024, // 1 MB
  },
  optimizeDeps: {
    include: ['zstd-codec'],
  },
});
