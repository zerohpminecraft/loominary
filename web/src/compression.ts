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
  if (payloadSize < 256 * 1024) return 22;
  if (payloadSize < 4 * 1024 * 1024) return 9;
  return 3;
}

/**
 * Compress `data` with zstd at an adaptively chosen level.
 */
export async function compress(data: Uint8Array): Promise<Uint8Array> {
  const codec  = await getCodec();
  const simple = new codec.Simple();
  const level  = adaptiveLevel(data.length);
  const result: Uint8Array | null = simple.compress(data, level);
  if (!result) throw new Error('zstd compress returned null');
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
