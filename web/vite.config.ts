import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

export default defineConfig(({ command }) => ({
  // Project GitHub Pages serves under https://<user>.github.io/<repo>/, so built
  // asset URLs must be prefixed with /loominary/. The dev server stays at / .
  base: command === 'build' ? '/loominary/' : '/',
  plugins: [preact()],
  // Dev-only proxy for ML model weights. Browsers fail CORS when following
  // Hugging Face's cross-origin `resolve` → Xet CDN (us.aws.cdn.hf.co) redirect
  // ("CORS request did not succeed, status null"). Routing `/hf/*` through the
  // dev server — which follows the redirect server-side — makes the download
  // same-origin in dev. Production (GitHub Pages) fetches huggingface.co
  // directly. See src/ml/runtime.ts → resolveWeightsUrl.
  server: {
    proxy: {
      '/hf/': {
        target: 'https://huggingface.co',
        changeOrigin: true,
        followRedirects: true,
        rewrite: (p) => p.replace(/^\/hf\//, '/'),
      },
      // Same-origin proxy for the onnxruntime-web ESM + its .wasm files. Browsers
      // (notably Firefox + tracking protection) can fail the worker's cross-origin
      // module import() of the ORT CDN bundle with "CORS request did not succeed".
      // Routing it through the dev server avoids that. See src/ml/runtime.ts.
      '/cdn-ort/': {
        target: 'https://cdn.jsdelivr.net',
        changeOrigin: true,
        followRedirects: true,
        rewrite: (p) => p.replace(/^\/cdn-ort\//, '/'),
      },
    },
  },
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
