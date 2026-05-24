package net.zerohpminecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Encodes arbitrary bytes as CJK Unified Ideograph characters (U+4E00–U+8DFF,
 * 2^14 = 16,384 chars, 14 bits per character).
 *
 * <p>All probed code points in this range passed through 2b2t's item-rename
 * filter unmodified (alphabet test 2026-05-07). CJK Unified Ideographs have
 * no canonical decomposition, so NFC normalization is a non-issue.
 *
 * <p><b>Wire format</b>: the 2-byte big-endian length of the compressed payload
 * is prepended before bit-packing so the decoder knows exactly where real data
 * ends. This keeps the codec itself a pure byte↔char transformer with no
 * knowledge of zstd or banner structure.
 *
 * <p><b>Bit math</b>: 48 chars × 14 bits = 672 bits = 84 bytes — an exact
 * integer, so every full banner carries exactly {@value #BYTES_PER_BANNER} bytes
 * with zero wasted capacity.
 *
 * <p><b>Backward compatibility</b>: the first payload char of a CJK-encoded
 * banner is always ≥ U+4E00, far above any base64 character (all ASCII ≤ 0x7F),
 * so old and new formats are discriminated by a single char-range check.
 */
public final class CjkCodec {

    /** First character of the encoding alphabet. */
    public static final int ALPHA_BASE  = 0x4E00;
    /** Bits encoded per character (log₂ of alphabet size). */
    public static final int ALPHA_BITS  = 14;
    /** Alphabet size = 2^{@value #ALPHA_BITS}. */
    public static final int ALPHA_SIZE  = 1 << ALPHA_BITS;   // 16 384
    /** CJK payload characters per banner name (after the 2-char hex index). */
    public static final int PAYLOAD_CHARS    = 48;
    /** Bytes per full banner: {@code PAYLOAD_CHARS × ALPHA_BITS / 8} (exact integer). */
    public static final int BYTES_PER_BANNER = PAYLOAD_CHARS * ALPHA_BITS / 8; // 84

    private CjkCodec() {}

    // ── Core codec ────────────────────────────────────────────────────────

    /**
     * Encodes {@code compressed} to a CJK string.
     * A 2-byte big-endian length header is prepended so the decoder knows the
     * exact data length without relying on banner count or padding conventions.
     */
    public static String encode(byte[] compressed) {
        // Build source buffer: [len_hi][len_lo][compressed…]
        byte[] src = new byte[2 + compressed.length];
        src[0] = (byte)(compressed.length >>> 8);
        src[1] = (byte)(compressed.length & 0xFF);
        System.arraycopy(compressed, 0, src, 2, compressed.length);

        // Pack src into CJK chars, 14 bits per char, MSB-first; zero-pad last char.
        int totalBits = src.length * 8;
        int numChars  = (totalBits + ALPHA_BITS - 1) / ALPHA_BITS;
        StringBuilder sb = new StringBuilder(numChars);
        int bitBuf = 0, bitsInBuf = 0, byteIdx = 0;
        for (int c = 0; c < numChars; c++) {
            while (bitsInBuf < ALPHA_BITS) {
                bitBuf    = (bitBuf << 8) | (byteIdx < src.length ? src[byteIdx++] & 0xFF : 0);
                bitsInBuf += 8;
            }
            bitsInBuf -= ALPHA_BITS;
            sb.append((char)(ALPHA_BASE + ((bitBuf >>> bitsInBuf) & (ALPHA_SIZE - 1))));
        }
        return sb.toString();
    }

    /**
     * Decodes a CJK string back to the original compressed bytes.
     * Reads the 2-byte length header to know exactly how many bytes to return.
     */
    public static byte[] decode(String s) {
        if (s.isEmpty()) return new byte[0];
        int numChars  = s.length();   // all chars are BMP so length == codepoint count
        int totalBits = numChars * ALPHA_BITS;
        int rawBytes  = totalBits / 8; // integer division; trailing bits are zero-padding

        byte[] raw = new byte[rawBytes];
        int bitBuf = 0, bitsInBuf = 0, byteIdx = 0;
        for (int i = 0; i < numChars && byteIdx < rawBytes; i++) {
            int val = s.charAt(i) - ALPHA_BASE;
            if (val < 0 || val >= ALPHA_SIZE)
                throw new IllegalArgumentException(
                        "CjkCodec: char out of alphabet range at index " + i
                        + " (U+" + Integer.toHexString(s.charAt(i)).toUpperCase() + ")");
            bitBuf    = (bitBuf << ALPHA_BITS) | val;
            bitsInBuf += ALPHA_BITS;
            while (bitsInBuf >= 8 && byteIdx < rawBytes) {
                bitsInBuf -= 8;
                raw[byteIdx++] = (byte)(bitBuf >>> bitsInBuf);
            }
        }

        if (rawBytes < 2)
            throw new IllegalStateException("CjkCodec: payload too short for length header");
        int n = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        if (2 + n > rawBytes)
            throw new IllegalStateException(
                    "CjkCodec: length header " + n + " exceeds decoded buffer " + rawBytes);
        byte[] out = new byte[n];
        System.arraycopy(raw, 2, out, 0, n);
        return out;
    }

    // ── Banner chunk helpers ──────────────────────────────────────────────

    /**
     * Encodes {@code compressed} and splits the result into banner name strings
     * of the form {@code "NN<48 CJK chars>"} where {@code NN} is a 2-char hex
     * index ({@code 00}, {@code 01}, …).
     */
    public static List<String> buildChunks(byte[] compressed) {
        String encoded = encode(compressed);
        int numBanners = (encoded.length() + PAYLOAD_CHARS - 1) / PAYLOAD_CHARS;
        List<String> chunks = new ArrayList<>(numBanners);
        for (int i = 0; i < numBanners; i++) {
            int start = i * PAYLOAD_CHARS;
            int end   = Math.min(start + PAYLOAD_CHARS, encoded.length());
            chunks.add(String.format("%02x", i) + encoded.substring(start, end));
        }
        return chunks;
    }

    /**
     * Reassembles banner name strings (each {@code "NN<CJK payload>"}) into
     * the original compressed bytes.  Sorts by hex index before decoding.
     */
    public static byte[] assembleChunks(List<String> names) {
        names.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
        StringBuilder sb = new StringBuilder();
        for (String s : names) sb.append(s.substring(2));
        return decode(sb.toString());
    }

    /**
     * Returns the number of banners needed to encode {@code compressed.length} bytes
     * (including the 2-byte header).
     */
    public static int chunksNeeded(byte[] compressed) {
        return (int) Math.ceil((2.0 + compressed.length) / BYTES_PER_BANNER);
    }
}
