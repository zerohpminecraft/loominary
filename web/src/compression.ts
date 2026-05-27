/**
 * zstd compression wrapper.
 *
 * Mirrors the adaptive-level logic from PngToMapColors.compressCombined():
 *   < 256 KB  → level 22 (max)
 *   256 KB–4 MB → level 9
 *   ≥ 4 MB   → level 3 (fast)
 *
 * Uses the `zstd-codec` WASM package which provides both compress and
 * decompress.  The codec is loaded lazily on first use so it doesn't
 * block page load.
 */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type ZstdCodecModule = any;
let _codec: ZstdCodecModule | null = null;

async function getCodec(): Promise<ZstdCodecModule> {
  if (_codec) return _codec;
  // zstd-codec uses a callback-style init.
  const { ZstdCodec } = await import('zstd-codec');
  return new Promise((resolve) => {
    ZstdCodec.run((c: ZstdCodecModule) => { _codec = c; resolve(c); });
  });
}

function adaptiveLevel(payloadSize: number): number {
  if (payloadSize < 256 * 1024) return 22;
  if (payloadSize < 4 * 1024 * 1024) return 9;
  return 3;
}

/**
 * Compress `data` with zstd at an adaptively chosen level.
 */
export async function compress(data: Uint8Array): Promise<Uint8Array> {
  const codec = await getCodec();
  const level = adaptiveLevel(data.length);
  const simple = new codec.Simple();
  try {
    const result: Uint8Array = simple.compress(data, level);
    return result;
  } finally {
    simple.dispose();
  }
}

/**
 * Compress a manifest prefix + map-color payload in one call, matching the
 * Java `compressCombined(prefix, mapColors)` method.
 */
export async function compressCombined(prefix: Uint8Array, mapColors: Uint8Array): Promise<Uint8Array> {
  const combined = new Uint8Array(prefix.length + mapColors.length);
  combined.set(prefix, 0);
  combined.set(mapColors, prefix.length);
  return compress(combined);
}

/**
 * Decompress a zstd-compressed buffer.
 * `originalSize` is optional but speeds things up when known (avoids internal
 * buffer resizing in the WASM layer).
 */
export async function decompress(compressed: Uint8Array, originalSize?: number): Promise<Uint8Array> {
  const codec = await getCodec();
  const simple = new codec.Simple();
  try {
    // zstd-codec's Simple.decompress accepts an optional size hint.
    const result: Uint8Array = originalSize !== undefined
      ? simple.decompress(compressed, originalSize)
      : simple.decompress(compressed);
    return result;
  } finally {
    simple.dispose();
  }
}

/**
 * Read the uncompressed content size from a zstd frame header without
 * decompressing (mirrors Zstd.getFrameContentSize()).
 * Returns -1 if the size is not stored in the frame header.
 */
export async function getFrameContentSize(compressed: Uint8Array): Promise<number> {
  const codec = await getCodec();
  // zstd-codec exposes this via the Streaming interface or we parse manually.
  // The zstd frame magic is 0xFD2FB528 (LE).  Content size is in the frame
  // header; we use the codec's built-in method if available, else parse.
  if (typeof codec.Simple?.prototype?.frameContentSize === 'function') {
    const simple = new codec.Simple();
    try { return simple.frameContentSize(compressed); } finally { simple.dispose(); }
  }
  // Manual parse: byte 4 = Frame_Header_Descriptor
  if (compressed.length < 6) return -1;
  const magic = (compressed[0] | (compressed[1] << 8) | (compressed[2] << 16) | (compressed[3] << 24)) >>> 0;
  if (magic !== 0xFD2FB528) return -1;
  const fhd = compressed[4];
  const fcsFlag = (fhd >>> 6) & 3;
  if (fcsFlag === 0) return -1; // not stored
  const singleSeg = (fhd >>> 5) & 1;
  let off = 5;
  if (!singleSeg) off++; // Window_Descriptor
  const didFlag = (fhd >>> 0) & 3;
  if (didFlag !== 0) off += [0, 1, 2, 4][didFlag];
  // Read content size
  if (fcsFlag === 1) return singleSeg ? compressed[off] : (compressed[off] | (compressed[off+1] << 8)) + 256;
  if (fcsFlag === 2) return compressed[off] | (compressed[off+1] << 8) | (compressed[off+2] << 16) | (compressed[off+3] << 24);
  // fcsFlag === 3: 8-byte LE (too large for JS safe integers, return approximate)
  return Number(
    BigInt(compressed[off]) | (BigInt(compressed[off+1]) << 8n) |
    (BigInt(compressed[off+2]) << 16n) | (BigInt(compressed[off+3]) << 24n) |
    (BigInt(compressed[off+4]) << 32n)
  );
}
