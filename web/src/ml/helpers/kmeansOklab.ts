/**
 * Tiny Lloyd's k-means over Oklab samples (pure, dependency-free, testable).
 * Used by the palette-suggestion helper to propose the N most useful colors.
 * k-means++ seeding gives stable, well-spread centers.
 */

export interface KMeansResult {
  /** Cluster centers, 3 floats (L,a,b) each → length 3*k. */
  centers: Float32Array;
  /** Population of each cluster. */
  counts: Int32Array;
}

/**
 * @param samples flat [L,a,b, L,a,b, …] (length = 3*n)
 * @param k       desired cluster count (clamped to n)
 * @param iters   max Lloyd iterations
 * @param seed    PRNG seed for reproducibility
 */
export function kmeansOklab(samples: Float32Array, k: number, iters = 12, seed = 1): KMeansResult {
  const n = Math.floor(samples.length / 3);
  k = Math.max(1, Math.min(k, n));
  const rng = mulberry32(seed);

  // k-means++ seeding.
  const centers = new Float32Array(k * 3);
  const first = Math.floor(rng() * n);
  centers[0] = samples[first * 3];
  centers[1] = samples[first * 3 + 1];
  centers[2] = samples[first * 3 + 2];
  const dist2 = new Float32Array(n);
  for (let c = 1; c < k; c++) {
    let total = 0;
    for (let i = 0; i < n; i++) {
      dist2[i] = nearestCenterDist2(samples, i, centers, c);
      total += dist2[i];
    }
    let r = rng() * total, pick = n - 1;
    for (let i = 0; i < n; i++) { r -= dist2[i]; if (r <= 0) { pick = i; break; } }
    centers[c * 3] = samples[pick * 3];
    centers[c * 3 + 1] = samples[pick * 3 + 1];
    centers[c * 3 + 2] = samples[pick * 3 + 2];
  }

  const assign = new Int32Array(n);
  const counts = new Int32Array(k);
  for (let it = 0; it < iters; it++) {
    let moved = 0;
    for (let i = 0; i < n; i++) {
      const a = nearestCenter(samples, i, centers, k);
      if (a !== assign[i]) { assign[i] = a; moved++; }
    }
    // Recompute centers.
    const sums = new Float64Array(k * 3);
    counts.fill(0);
    for (let i = 0; i < n; i++) {
      const a = assign[i];
      sums[a * 3] += samples[i * 3];
      sums[a * 3 + 1] += samples[i * 3 + 1];
      sums[a * 3 + 2] += samples[i * 3 + 2];
      counts[a]++;
    }
    for (let c = 0; c < k; c++) {
      if (counts[c] === 0) continue;
      centers[c * 3] = sums[c * 3] / counts[c];
      centers[c * 3 + 1] = sums[c * 3 + 1] / counts[c];
      centers[c * 3 + 2] = sums[c * 3 + 2] / counts[c];
    }
    if (moved === 0 && it > 0) break;
  }
  return { centers, counts };
}

function nearestCenter(s: Float32Array, i: number, centers: Float32Array, k: number): number {
  let best = 0, bestD = Infinity;
  for (let c = 0; c < k; c++) {
    const dL = s[i * 3] - centers[c * 3];
    const da = s[i * 3 + 1] - centers[c * 3 + 1];
    const db = s[i * 3 + 2] - centers[c * 3 + 2];
    const d = dL * dL + da * da + db * db;
    if (d < bestD) { bestD = d; best = c; }
  }
  return best;
}

function nearestCenterDist2(s: Float32Array, i: number, centers: Float32Array, count: number): number {
  let bestD = Infinity;
  for (let c = 0; c < count; c++) {
    const dL = s[i * 3] - centers[c * 3];
    const da = s[i * 3 + 1] - centers[c * 3 + 1];
    const db = s[i * 3 + 2] - centers[c * 3 + 2];
    const d = dL * dL + da * da + db * db;
    if (d < bestD) bestD = d;
  }
  return bestD;
}

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
