/**
 * OKLab colour-space conversions.
 *
 * Exact port of the Java constants in PngToMapColors.rgbToOklab() /
 * PngToMapColors.oklabToLinearRgb().  Do not adjust the coefficients.
 */

export type OKLab = [L: number, a: number, b: number];

// ─── sRGB gamma ──────────────────────────────────────────────────────────────

function linearize(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function delinearize(c: number): number {
  return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
}

// ─── RGB (0–255 ints) → OKLab ────────────────────────────────────────────────

export function rgbToOklab(r255: number, g255: number, b255: number): OKLab {
  const r = linearize(r255 / 255);
  const g = linearize(g255 / 255);
  const b = linearize(b255 / 255);

  const l0 = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
  const m0 = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
  const s0 = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

  const l_ = Math.cbrt(l0);
  const m_ = Math.cbrt(m0);
  const s_ = Math.cbrt(s0);

  return [
    0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
    1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
    0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
  ];
}

/** Convenience: unpack a packed 0xRRGGBB integer. */
export function rgbIntToOklab(rgb: number): OKLab {
  return rgbToOklab((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
}

// ─── OKLab → linear RGB floats (may exceed [0,1]) ────────────────────────────

export function oklabToLinearRgb(L: number, a: number, b: number): [r: number, g: number, b_: number] {
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.2914855480 * b;

  const l = l_ * l_ * l_;
  const m = m_ * m_ * m_;
  const s = s_ * s_ * s_;

  return [
    +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
  ];
}

/** OKLab → packed 0xRRGGBB integer (clamped). */
export function oklabToRgbInt(L: number, a: number, b: number): number {
  const [rl, gl, bl] = oklabToLinearRgb(L, a, b);
  const r = Math.round(Math.min(1, Math.max(0, delinearize(rl))) * 255);
  const g = Math.round(Math.min(1, Math.max(0, delinearize(gl))) * 255);
  const bv = Math.round(Math.min(1, Math.max(0, delinearize(bl))) * 255);
  return (r << 16) | (g << 8) | bv;
}

// ─── Squared Euclidean distance in OKLab ─────────────────────────────────────

export function oklabDistSq(
  L1: number, a1: number, b1: number,
  L2: number, a2: number, b2: number,
): number {
  const dL = L1 - L2;
  const da = a1 - a2;
  const db = b1 - b2;
  return dL * dL + da * da + db * db;
}
