/**
 * Image pre-processing (brightness / contrast / saturation) applied before
 * palette quantization.  All transforms are per-pixel on RGBA ImageData.
 */

export interface PreprocessParams {
  brightness: number;  // 1.0 = neutral, 0.5 = dark, 1.5 = bright
  contrast:   number;  // 1.0 = neutral, 0.5 = flat, 2.0 = punchy
  saturation: number;  // 1.0 = neutral, 0 = grey, 2.0 = vivid
}

export const DEFAULT_PREPROCESS: PreprocessParams = {
  brightness: 1,
  contrast:   1,
  saturation: 1,
};

/**
 * Apply brightness, contrast, and saturation adjustments to a copy of `src`.
 * Returns the original ImageData reference if all params are 1.0.
 *
 * Processing order:
 *   1. Contrast  — scale each channel around 0.5 in [0,1] space
 *   2. Brightness — multiply by brightness factor
 *   3. Saturation — luma-preserving lerp toward greyscale
 */
/**
 * Scale/crop an ImageBitmap to (targetW × targetH), applying preprocessing.
 *
 * cropMode 'scale'  — stretch the whole bitmap to the target dimensions.
 * cropMode 'center' — crop the centre of the bitmap to match the target
 *                     aspect ratio before scaling (no distortion).
 *
 * Preprocessing is applied to the full-resolution source before downscaling.
 */
import { downscale, type DownscaleStyle } from './ml/pixelize.js';

export async function prepareSourceImage(
  bmp:      ImageBitmap,
  targetW:  number,
  targetH:  number,
  cropMode: 'scale' | 'center',
  pre:      PreprocessParams,
  downscaleStyle: DownscaleStyle = 'bilinear',
): Promise<ImageData> {
  // Cap the intermediate resolution to avoid creating huge OffscreenCanvas
  // objects for large source images.  4× the target is more than enough
  // resolution for colour accuracy while keeping memory manageable.
  const MAX_INTERMEDIATE = Math.max(targetW, targetH) * 4;
  const srcW = Math.min(bmp.width,  MAX_INTERMEDIATE);
  const srcH = Math.min(bmp.height, MAX_INTERMEDIATE);

  const srcCanvas = new OffscreenCanvas(srcW, srcH);
  const srcCtx    = srcCanvas.getContext('2d')!;
  srcCtx.imageSmoothingEnabled = true;
  srcCtx.imageSmoothingQuality = 'high';
  srcCtx.drawImage(bmp, 0, 0, srcW, srcH);
  const raw       = srcCtx.getImageData(0, 0, srcW, srcH);
  const processed = applyPreprocess(raw, pre);
  if (processed !== raw) srcCtx.putImageData(processed, 0, 0);

  // Determine the crop rectangle (in intermediate/source pixels).
  let sx = 0, sy = 0, sw = srcW, sh = srcH;
  if (cropMode === 'center') {
    const srcAR = srcW / srcH;
    const dstAR = targetW / targetH;
    if (srcAR > dstAR) { sw = srcH * dstAR; sx = (srcW - sw) / 2; }
    else               { sh = srcW / dstAR; sy = (srcH - sh) / 2; }
  }

  // Content-adaptive downscale styles operate on the cropped, preprocessed
  // intermediate (up to 4× the target res) → target. Bilinear lets the canvas
  // do it. All styles only produce RGBA; quantization stays downstream.
  if (downscaleStyle !== 'bilinear') {
    const cx = Math.max(0, Math.floor(sx)), cy = Math.max(0, Math.floor(sy));
    const cw = Math.max(1, Math.min(srcW - cx, Math.round(sw)));
    const ch = Math.max(1, Math.min(srcH - cy, Math.round(sh)));
    const crop = srcCtx.getImageData(cx, cy, cw, ch);
    const reduced = downscale(crop.data, cw, ch, targetW, targetH, downscaleStyle);
    const out = new ImageData(targetW, targetH);
    out.data.set(reduced);
    return out;
  }

  // Bilinear scale/crop to target dimensions.
  const dstCanvas = new OffscreenCanvas(targetW, targetH);
  const dstCtx    = dstCanvas.getContext('2d')!;
  dstCtx.imageSmoothingEnabled = true;
  dstCtx.imageSmoothingQuality = 'high';
  dstCtx.drawImage(srcCanvas, sx, sy, sw, sh, 0, 0, targetW, targetH);
  return dstCtx.getImageData(0, 0, targetW, targetH);
}

export function applyPreprocess(src: ImageData, p: PreprocessParams): ImageData {
  if (p.brightness === 1 && p.contrast === 1 && p.saturation === 1) return src;

  const out  = new ImageData(src.width, src.height);
  const s    = src.data;
  const d    = out.data;
  const n    = s.length;

  for (let i = 0; i < n; i += 4) {
    const alpha = s[i + 3];
    d[i + 3] = alpha;
    if (alpha < 128) continue;

    // Normalise to [0, 1]
    let r = s[i    ] / 255;
    let g = s[i + 1] / 255;
    let b = s[i + 2] / 255;

    // 1. Contrast: scale around 0.5
    if (p.contrast !== 1) {
      r = (r - 0.5) * p.contrast + 0.5;
      g = (g - 0.5) * p.contrast + 0.5;
      b = (b - 0.5) * p.contrast + 0.5;
    }

    // 2. Brightness: multiply
    if (p.brightness !== 1) {
      r *= p.brightness;
      g *= p.brightness;
      b *= p.brightness;
    }

    // 3. Saturation: luma-preserving lerp toward greyscale
    if (p.saturation !== 1) {
      // Rec.601 luma
      const luma = 0.299 * r + 0.587 * g + 0.114 * b;
      r = luma + (r - luma) * p.saturation;
      g = luma + (g - luma) * p.saturation;
      b = luma + (b - luma) * p.saturation;
    }

    d[i    ] = Math.round(Math.min(1, Math.max(0, r)) * 255);
    d[i + 1] = Math.round(Math.min(1, Math.max(0, g)) * 255);
    d[i + 2] = Math.round(Math.min(1, Math.max(0, b)) * 255);
  }

  return out;
}
