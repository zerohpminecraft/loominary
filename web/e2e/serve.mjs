/**
 * Dead-simple static server for the built app, used by the Playwright rig.
 * (vite preview 6.x 404s asset requests carrying `Sec-Fetch-Dest: script`,
 * which is every real-browser script load — so we serve dist/ ourselves.)
 *
 *   node e2e/serve.mjs   → http://localhost:4173/loominary/
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIST = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const BASE = '/loominary/';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.map': 'application/json',
};

createServer(async (req, res) => {
  try {
    let path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (!path.startsWith(BASE)) { res.writeHead(302, { Location: BASE }); return res.end(); }
    path = path.slice(BASE.length) || 'index.html';
    if (path.endsWith('/')) path += 'index.html';
    const file = normalize(join(DIST, path));
    if (!file.startsWith(DIST)) { res.writeHead(403); return res.end(); }
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end('not found');
  }
}).listen(4173, () => console.log('serving dist at http://localhost:4173' + BASE));
