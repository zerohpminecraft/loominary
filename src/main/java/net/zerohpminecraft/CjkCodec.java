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
 * <p><b>Wire format</b>: a 4-byte big-endian length of the compressed payload
 * is prepended before bit-packing so the decoder knows exactly where real data
 * ends. This keeps the codec itself a pure byte↔char transformer with no
 * knowledge of zstd or banner structure.
 *
 * <p><b>Bit math</b>: 46 chars × 14 bits = 644 bits = 80.5 bytes.  Not an
 * exact integer — the last byte of every full banner has 4 wasted bits — but
 * the 4-byte length header in {@link #encode} means the decoder always knows
 * the exact data length without relying on perfect alignment.
 *
 * <p><b>Backward compatibility</b>: a 2-char-indexed (old) banner has its
 * first CJK payload char at position 2; a 4-char-indexed (new) banner has its
 * first CJK payload char at position 4.  Since CJK chars are ≥ U+4E00 and hex
 * digits are all ASCII (≤ 0x7F), callers can probe {@code charAt(2)} vs
 * {@code charAt(4)} to determine which format they hold.  Old banners also use
 * a 2-byte length header — pass {@code headerBytes=2} to {@link #decode(String,int)}
 * when processing old-format chunks.
 */
public final class CjkCodec {

    /** First character of the encoding alphabet. */
    public static final int ALPHA_BASE  = 0x4E00;
    /** Bits encoded per character (log₂ of alphabet size). */
    public static final int ALPHA_BITS  = 14;
    /** Alphabet size = 2^{@value #ALPHA_BITS}. */
    public static final int ALPHA_SIZE  = 1 << ALPHA_BITS;   // 16 384
    /** CJK payload characters per banner name (after the 4-char hex index). */
    public static final int PAYLOAD_CHARS    = 46;
    /**
     * Approximate bytes per full banner: {@code PAYLOAD_CHARS × ALPHA_BITS / 8 ≈ 80.5}.
     * Not an exact integer; use as a conservative estimate only.
     */
    public static final int BYTES_PER_BANNER = PAYLOAD_CHARS * ALPHA_BITS / 8; // 80 (rounds down)

    private CjkCodec() {}

    // ── Core codec ────────────────────────────────────────────────────────

    /**
     * Encodes {@code compressed} to a CJK string.
     * A 4-byte big-endian length header is prepended so the decoder knows the
     * exact data length without relying on banner count or padding conventions.
     */
    public static String encode(byte[] compressed) {
        // Build source buffer: [len3][len2][len1][len0][compressed…]
        int len = compressed.length;
        byte[] src = new byte[4 + len];
        src[0] = (byte)(len >>> 24);
        src[1] = (byte)(len >>> 16);
        src[2] = (byte)(len >>>  8);
        src[3] = (byte)(len        );
        System.arraycopy(compressed, 0, src, 4, len);

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
     * Reads the 4-byte length header to know exactly how many bytes to return.
     * Use {@link #decode(String, int) decode(s, 2)} when processing old-format
     * banners that were encoded with the legacy 2-byte header.
     */
    public static byte[] decode(String s) {
        return decode(s, 4);
    }

    /**
     * Decodes a CJK string with an explicit header size (2 for old format, 4 for new).
     */
    public static byte[] decode(String s, int headerBytes) {
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

        if (rawBytes < headerBytes)
            throw new IllegalStateException("CjkCodec: payload too short for length header");
        int n;
        if (headerBytes == 4) {
            n = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
              | ((raw[2] & 0xFF) <<  8) |  (raw[3] & 0xFF);
        } else {
            n = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        }
        if (n < 0 || headerBytes + n > rawBytes)
            throw new IllegalStateException(
                    "CjkCodec: length header " + n + " exceeds decoded buffer " + rawBytes);
        byte[] out = new byte[n];
        System.arraycopy(raw, headerBytes, out, 0, n);
        return out;
    }

    // ── Banner chunk helpers ──────────────────────────────────────────────

    /**
     * Encodes {@code compressed} and splits the result into banner name strings
     * of the form {@code "NNNN<46 CJK chars>"} where {@code NNNN} is a 4-char hex
     * index ({@code 0000}, {@code 0001}, …, {@code ffff}), supporting up to 65535 banners.
     */
    public static List<String> buildChunks(byte[] compressed) {
        String encoded = encode(compressed);
        int numBanners = (encoded.length() + PAYLOAD_CHARS - 1) / PAYLOAD_CHARS;
        List<String> chunks = new ArrayList<>(numBanners);
        for (int i = 0; i < numBanners; i++) {
            int start = i * PAYLOAD_CHARS;
            int end   = Math.min(start + PAYLOAD_CHARS, encoded.length());
            chunks.add(String.format("%04x", i) + encoded.substring(start, end));
        }
        return chunks;
    }

    /**
     * Reassembles banner name strings into the original compressed bytes.
     * Detects old (2-char index, 2-byte header) vs new (4-char index, 4-byte header)
     * format automatically by probing the character at the expected payload start.
     */
    public static byte[] assembleChunks(List<String> names) {
        if (names.isEmpty()) return new byte[0];
        // Old format: 2-char hex index, CJK payload starts at charAt(2)
        // New format: 4-char hex index, CJK payload starts at charAt(4)
        boolean oldFormat = names.get(0).length() > 2
                && names.get(0).charAt(2) >= ALPHA_BASE;
        int prefix = oldFormat ? 2 : 4;
        int headerBytes = oldFormat ? 2 : 4;
        names.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, prefix), 16)));
        StringBuilder sb = new StringBuilder();
        for (String s : names) sb.append(s.substring(prefix));
        return decode(sb.toString(), headerBytes);
    }

    /**
     * Returns the number of banners needed to encode {@code compressed.length} bytes
     * (including the 4-byte header).  Uses exact bit-level math.
     */
    public static int chunksNeeded(byte[] compressed) {
        // Bits needed: (4 header + data) × 8; each banner holds PAYLOAD_CHARS × ALPHA_BITS bits.
        long bits = ((long)(4 + compressed.length)) * 8;
        long bitsPerBanner = (long) PAYLOAD_CHARS * ALPHA_BITS;
        return (int) ((bits + bitsPerBanner - 1) / bitsPerBanner);
    }
}
