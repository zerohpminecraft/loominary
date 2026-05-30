/**
 * zstd compression wrapper — uses zstd-codec's Simple API.
 *
 * The codec is loaded lazily on first use so it doesn't block page load.
 */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type ZstdCodecModule = any;
let _codec: ZstdCodecModule | null = null;

async function getCodec(): Promise<ZstdCodecModule> {
  if (_codec) return _codec;
  const { ZstdCodec } = await import('zstd-codec');
  return new Promise((resolve) => {
    ZstdCodec.run((c: ZstdCodecModule) => { _codec = c; resolve(c); });
  });
}

function adaptiveLevel(payloadSize: number): number {
  if (payloadSize <   256 * 1024) return 22;
  if (payloadSize < 4 * 1024 * 1024) return 9;
  return 3;
}

// Above this size the Simple API's all-at-once WASM allocation OOMs.
// The Streaming API processes one chunk at a time and stays within limits.
const STREAMING_THRESHOLD = 16 * 1024 * 1024; // 16 MB
const STREAMING_CHUNK     =  4 * 1024 * 1024; // 4 MB per chunk

/**
 * Compress `data` with zstd at an adaptively chosen level.
 * Uses the streaming API for large payloads to avoid WASM OOM.
 */
export async function compress(data: Uint8Array): Promise<Uint8Array> {
  const codec = await getCodec();
  const level = adaptiveLevel(data.length);

  if (data.length <= STREAMING_THRESHOLD) {
    const simple = new codec.Simple();
    const result: Uint8Array | null = simple.compress(data, level);
    if (!result) throw new Error('zstd compress returned null');
    return result;
  }

  // Split into chunks — WASM only sees one chunk at a time.
  const chunks: Uint8Array[] = [];
  for (let off = 0; off < data.length; off += STREAMING_CHUNK) {
    chunks.push(data.subarray(off, Math.min(off + STREAMING_CHUNK, data.length)));
  }
  // Hint: map art is very compressible; guess ~15% of input for the output buffer.
  const sizeHint = Math.max(512 * 1024, Math.ceil(data.length * 0.15));
  const streaming = new codec.Streaming();
  const result: Uint8Array | null = streaming.compressChunks(chunks, sizeHint, level);
  if (!result) throw new Error('zstd streaming compress returned null');
  return result;
}

/**
 * Compress a manifest prefix + map-color payload in one call.
 */
export async function compressCombined(prefix: Uint8Array, mapColors: Uint8Array): Promise<Uint8Array> {
  const combined = new Uint8Array(prefix.length + mapColors.length);
  combined.set(prefix, 0);
  combined.set(mapColors, prefix.length);
  return compress(combined);
}

/**
 * Decompress a zstd-compressed buffer.
 */
export async function decompress(compressed: Uint8Array): Promise<Uint8Array> {
  const codec  = await getCodec();
  const simple = new codec.Simple();
  const result: Uint8Array | null = simple.decompress(compressed);
  if (!result) throw new Error('zstd decompress returned null');
  return result;
}
