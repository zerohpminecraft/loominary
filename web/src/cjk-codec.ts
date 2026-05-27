/**
 * CJK banner encoding — port of CjkCodec.java.
 *
 * Encodes arbitrary bytes as CJK Unified Ideograph characters (U+4E00–U+8DFF,
 * 14 bits per character).  Wire format: 2-byte big-endian length header prepended
 * before bit-packing so the decoder knows the exact payload length.
 */

export const ALPHA_BASE   = 0x4E00;
export const ALPHA_BITS   = 14;
export const ALPHA_SIZE   = 1 << ALPHA_BITS; // 16 384
export const PAYLOAD_CHARS    = 48;          // CJK chars per banner (after 2-char hex index)
export const BYTES_PER_BANNER = PAYLOAD_CHARS * ALPHA_BITS / 8; // 84

// ─── Core codec ──────────────────────────────────────────────────────────────

/**
 * Encode `compressed` bytes to a CJK string (with 2-byte length header prepended).
 */
export function encode(compressed: Uint8Array): string {
  const src = new Uint8Array(2 + compressed.length);
  src[0] = (compressed.length >>> 8) & 0xff;
  src[1] = compressed.length & 0xff;
  src.set(compressed, 2);

  const totalBits = src.length * 8;
  const numChars  = Math.ceil(totalBits / ALPHA_BITS);
  let bitBuf = 0, bitsInBuf = 0, byteIdx = 0;
  const chars: number[] = [];

  for (let c = 0; c < numChars; c++) {
    while (bitsInBuf < ALPHA_BITS) {
      bitBuf = ((bitBuf << 8) | (byteIdx < src.length ? src[byteIdx++] : 0)) >>> 0;
      bitsInBuf += 8;
    }
    bitsInBuf -= ALPHA_BITS;
    chars.push(ALPHA_BASE + ((bitBuf >>> bitsInBuf) & (ALPHA_SIZE - 1)));
  }
  return String.fromCodePoint(...chars);
}

/**
 * Decode a CJK string back to the original compressed bytes.
 */
export function decode(s: string): Uint8Array {
  if (s.length === 0) return new Uint8Array(0);

  const numChars  = s.length;
  const totalBits = numChars * ALPHA_BITS;
  const rawBytes  = Math.floor(totalBits / 8);
  const raw = new Uint8Array(rawBytes);

  let bitBuf = 0, bitsInBuf = 0, byteIdx = 0;
  for (let i = 0; i < numChars && byteIdx < rawBytes; i++) {
    const val = s.charCodeAt(i) - ALPHA_BASE;
    if (val < 0 || val >= ALPHA_SIZE)
      throw new Error(`CjkCodec: char out of alphabet range at index ${i} (U+${s.charCodeAt(i).toString(16).toUpperCase()})`);
    bitBuf = ((bitBuf << ALPHA_BITS) | val) >>> 0;
    bitsInBuf += ALPHA_BITS;
    while (bitsInBuf >= 8 && byteIdx < rawBytes) {
      bitsInBuf -= 8;
      raw[byteIdx++] = (bitBuf >>> bitsInBuf) & 0xff;
    }
  }

  if (rawBytes < 2) throw new Error('CjkCodec: payload too short for length header');
  const n = (raw[0] << 8) | raw[1];
  if (2 + n > rawBytes) throw new Error(`CjkCodec: length header ${n} exceeds decoded buffer ${rawBytes}`);
  return raw.slice(2, 2 + n);
}

// ─── Banner chunk helpers ─────────────────────────────────────────────────────

/**
 * Encode `compressed` and split into `"NN<48 CJK chars>"` banner name strings.
 */
export function buildChunks(compressed: Uint8Array): string[] {
  const encoded = encode(compressed);
  const numBanners = Math.ceil(encoded.length / PAYLOAD_CHARS);
  const chunks: string[] = [];
  for (let i = 0; i < numBanners; i++) {
    const start = i * PAYLOAD_CHARS;
    const end   = Math.min(start + PAYLOAD_CHARS, encoded.length);
    chunks.push(i.toString(16).padStart(2, '0') + encoded.slice(start, end));
  }
  return chunks;
}

/**
 * Reassemble `"NN<CJK payload>"` banner name strings back to compressed bytes.
 */
export function assembleChunks(names: string[]): Uint8Array {
  const sorted = [...names].sort((a, b) =>
    parseInt(a.slice(0, 2), 16) - parseInt(b.slice(0, 2), 16));
  return decode(sorted.map(s => s.slice(2)).join(''));
}

/**
 * How many banners are needed to encode `byteCount` compressed bytes
 * (including the 2-byte length header)?
 */
export function chunksNeeded(byteCount: number): number {
  return Math.ceil((2 + byteCount) / BYTES_PER_BANNER);
}
