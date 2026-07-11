/**
 * Non-local means denoising (classical, pure). Default Phase-5 denoiser — no
 * model download. Operates on RGBA bytes and preserves edges far better than a
 * Gaussian blur because it averages perceptually-similar patches rather than
 * spatial neighbors.
 *
 * Parameters are tuned for 128² map tiles: a small search window and 3×3 patch
 * keep it ~real-time while still removing dither speckle.
 */

export interface NlMeansParams {
  /** Search window radius (pixels). */
  searchRadius: number;
  /** Patch radius (pixels) for similarity. */
  patchRadius: number;
  /** Filtering strength; higher = more smoothing. */
  h: number;
}

export const DEFAULT_NLMEANS: NlMeansParams = { searchRadius: 3, patchRadius: 1, h: 10 };

/**
 * Denoise an RGBA buffer (w×h, 4 bytes/px). Alpha is passed through unchanged;
 * fully-transparent pixels are ignored as neighbors. Returns a new buffer.
 */
export function nlMeans(
  rgba: Uint8ClampedArray | Uint8Array,
  w: number,
  h: number,
  params: NlMeansParams = DEFAULT_NLMEANS,
): Uint8ClampedArray {
  const { searchRadius: S, patchRadius: P, h: hParam } = params;
  const out = new Uint8ClampedArray(rgba.length);
  const h2 = hParam * hParam;
  const patchNorm = 1 / (3 * (2 * P + 1) * (2 * P + 1));

  const at = (x: number, y: number) => (clamp(y, h) * w + clamp(x, w)) * 4;

  // Precompute patch SSD between (cx,cy) and (nx,ny) over the RGB channels.
  function patchDist(cx: number, cy: number, nx: number, ny: number): number {
    let sum = 0;
    for (let dy = -P; dy <= P; dy++) {
      for (let dx = -P; dx <= P; dx++) {
        const a = at(cx + dx, cy + dy);
        const b = at(nx + dx, ny + dy);
        const dr = rgba[a] - rgba[b];
        const dg = rgba[a + 1] - rgba[b + 1];
        const db = rgba[a + 2] - rgba[b + 2];
        sum += dr * dr + dg * dg + db * db;
      }
    }
    return sum * patchNorm;
  }

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const ci = (y * w + x) * 4;
      out[ci + 3] = rgba[ci + 3];
      if (rgba[ci + 3] < 128) { out[ci] = rgba[ci]; out[ci + 1] = rgba[ci + 1]; out[ci + 2] = rgba[ci + 2]; continue; }

      let wsum = 0, r = 0, g = 0, b = 0;
      for (let ny = y - S; ny <= y + S; ny++) {
        for (let nx = x - S; nx <= x + S; nx++) {
          const ni = (clamp(ny, h) * w + clamp(nx, w)) * 4;
          if (rgba[ni + 3] < 128) continue;
          const d = patchDist(x, y, nx, ny);
          const weight = Math.exp(-Math.max(0, d) / h2);
          wsum += weight;
          r += weight * rgba[ni];
          g += weight * rgba[ni + 1];
          b += weight * rgba[ni + 2];
        }
      }
      if (wsum > 0) {
        out[ci] = Math.round(r / wsum);
        out[ci + 1] = Math.round(g / wsum);
        out[ci + 2] = Math.round(b / wsum);
      } else {
        out[ci] = rgba[ci]; out[ci + 1] = rgba[ci + 1]; out[ci + 2] = rgba[ci + 2];
      }
    }
  }
  return out;
}

function clamp(v: number, n: number): number {
  return v < 0 ? 0 : v >= n ? n - 1 : v;
}
