/**
 * Main-thread canvas helpers for ML features. These need a 2D context (resize,
 * bitmap↔ImageData) so they can't live in the pure imageOps module, but they're
 * cheap and only run when a feature is explicitly invoked.
 */

/** Draw an ImageBitmap into an ImageData at its native resolution. */
export function bitmapToImageData(bmp: ImageBitmap): ImageData {
  const c = new OffscreenCanvas(bmp.width, bmp.height);
  const ctx = c.getContext('2d')!;
  ctx.drawImage(bmp, 0, 0);
  return ctx.getImageData(0, 0, bmp.width, bmp.height);
}

/** Build an ImageData of (w×h) from raw RGBA bytes (copy via .set to avoid buffer-variance issues). */
export function rgbaToImageData(rgba: Uint8ClampedArray | Uint8Array, w: number, h: number): ImageData {
  const img = new ImageData(w, h);
  img.data.set(rgba);
  return img;
}

/** High-quality scale of raw RGBA pixels to (dw×dh) ImageData. */
export function scaleRgbaToImageData(
  rgba: Uint8ClampedArray | Uint8Array,
  sw: number,
  sh: number,
  dw: number,
  dh: number,
): ImageData {
  const src = new OffscreenCanvas(sw, sh);
  const sctx = src.getContext('2d')!;
  sctx.putImageData(rgbaToImageData(rgba, sw, sh), 0, 0);

  const dst = new OffscreenCanvas(dw, dh);
  const dctx = dst.getContext('2d')!;
  dctx.imageSmoothingEnabled = true;
  dctx.imageSmoothingQuality = 'high';
  dctx.clearRect(0, 0, dw, dh);
  dctx.drawImage(src, 0, 0, dw, dh);
  return dctx.getImageData(0, 0, dw, dh);
}

/** Create an ImageBitmap from an ImageData (for SAM encoder input, etc.). */
export async function imageDataToBitmap(img: ImageData): Promise<ImageBitmap> {
  return await createImageBitmap(img);
}

/** High-quality scale of an ImageBitmap to grid (gw×gh) ImageData. */
export function bitmapToGridImageData(bmp: ImageBitmap, gw: number, gh: number): ImageData {
  const c = new OffscreenCanvas(gw, gh);
  const ctx = c.getContext('2d')!;
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bmp, 0, 0, gw, gh);
  return ctx.getImageData(0, 0, gw, gh);
}

/**
 * Render the active frame of a composition to an ImageBitmap by drawing its
 * map-color pixels — the fallback "source" when no original image is retained.
 */
export async function compToBitmap(
  rgba: Uint8ClampedArray | Uint8Array,
  w: number,
  h: number,
): Promise<ImageBitmap> {
  return await createImageBitmap(rgbaToImageData(rgba, w, h));
}
