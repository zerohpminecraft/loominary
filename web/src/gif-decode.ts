/**
 * Multi-frame GIF decoder using the browser's ImageDecoder API (Chrome/Edge 94+).
 * Falls back to a single-frame result for browsers that don't support it.
 *
 * ImageDecoder returns composited VideoFrame objects (disposal methods applied),
 * so each frame is the complete rendered image at that animation position.
 *
 * Returns an array of { bitmap, delayMs } — one entry per GIF frame.
 */

export interface GifFrame {
  bitmap:  ImageBitmap;
  delayMs: number;
}

export async function decodeGifFrames(file: File): Promise<GifFrame[]> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const ImageDecoder = (window as any).ImageDecoder as (new (opts: unknown) => unknown) | undefined;

  if (!ImageDecoder) {
    // Browser doesn't support ImageDecoder — return single frame.
    return [{ bitmap: await createImageBitmap(file), delayMs: 100 }];
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const decoder: any = new ImageDecoder({
    data: file.stream(),
    type: 'image/gif',
  });

  try {
    await decoder.completed;
  } catch {
    decoder.close();
    return [{ bitmap: await createImageBitmap(file), delayMs: 100 }];
  }

  const track      = decoder.tracks?.selectedTrack;
  const frameCount: number = track?.frameCount ?? 1;
  const frames: GifFrame[] = [];

  for (let i = 0; i < frameCount; i++) {
    try {
      // Do NOT pass completeFramesOnly: true — that causes delta frames
      // (partial GIF updates) to resolve to the wrong I-frame, producing
      // repeated/looping frames.  Without it the browser composites the
      // full frame including all previous disposal-method operations.
      const result = await decoder.decode({ frameIndex: i });
      const vf: VideoFrame = result.image;
      // duration is in microseconds; convert to ms and clamp to ≥ 10 ms.
      const delayMs = vf.duration ? Math.max(10, Math.round(vf.duration / 1000)) : 100;

      const osc  = new OffscreenCanvas(vf.displayWidth, vf.displayHeight);
      osc.getContext('2d')!.drawImage(vf, 0, 0);
      const bmp  = await createImageBitmap(osc);
      vf.close();
      frames.push({ bitmap: bmp, delayMs });
    } catch {
      // Skip undecodable frames (e.g. corrupt data).
    }
  }

  decoder.close();
  return frames.length > 0 ? frames : [{ bitmap: await createImageBitmap(file), delayMs: 100 }];
}
