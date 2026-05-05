package net.zerohpminecraft;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Manifest header prepended to map-color bytes inside the zstd frame.
 *
 * Wire layout (bytes at the start of the decompressed payload):
 *   [0]     manifest_version  u8  — schema version; 1, 2, or 3
 *   [1]     header_size       u8  — total bytes in this header (allows future
 *                                   clients to skip unknown versions and still
 *                                   find the map colors at offset header_size)
 *   [2]     flags             u8  — bit 0 = allShades; bit 1 = animated; bits 2-7 reserved
 *   [3]     cols              u8  — grid columns
 *   [4]     rows              u8  — grid rows
 *   [5]     tile_col          u8  — this tile's column (0-indexed)
 *   [6]     tile_row          u8  — this tile's row (0-indexed)
 *   [7-10]  color_crc32       u32 big-endian — CRC32 of the first 16,384 map-color bytes
 *   [11]    username_len      u8  — 0 if absent; max 16
 *   [12…]   username          UTF-8
 *   […]     title_len         u8  — 0 if absent; max 64
 *   […]     title             UTF-8
 *   (v2+)
 *   […]     nonce             u32 big-endian — random salt from /loominary resalt;
 *                                 informational only; decoders use header_size to find
 *                                 map colors and can safely ignore this field.
 *   (v3 only, appended after nonce)
 *   […]     frame_count       u8  — total frames; 1 for static tiles
 *   […]     loop_count        u16 big-endian — 0 = loop forever; >0 = stop after N loops
 *   […]     delay_mode        u8  — 0 = single global delay; 1 = per-frame table
 *   […]     delay             u16 big-endian (delay_mode=0), or
 *                             frame_count × u16 (delay_mode=1) — milliseconds per frame
 *
 * Map-color bytes follow at offset header_size.
 * For animated tiles (FLAG_ANIMATED): frame_count × 16,384 bytes.
 * Old decoders (v0–v2) read only the first 16,384 bytes and render frame 0 as a static image.
 *
 * Format detection: decompressed size == 16384 → v0 (no manifest);
 * decompressed size > 16384 → v1+ (manifest prefix). v0 payloads
 * (encoded by Loominary 1.0.0) continue to decode identically.
 *
 * Manifest fields are informational only — none of them gate decode
 * behavior in v1 or v2. This keeps the decoder simple and means field
 * mistakes are cosmetic, not functional.
 */
public class PayloadManifest {

    /** Highest manifest version this client can decode. */
    public static final int CURRENT_VERSION = 3;

    public static final int FLAG_ALL_SHADES  = 0x01;
    /** Set when the payload contains multiple animation frames. */
    public static final int FLAG_ANIMATED    = 0x02;
    /**
     * Set when FLAG_ANIMATED is set and the frame bytes are stored in temporal-interleaved
     * order rather than frame-sequential order.
     *
     * Interleaved layout: [f0_px0, f1_px0, ..., fN_px0, f0_px1, f1_px1, ..., fN_px(P-1)]
     * Sequential  layout: [f0_px0…f0_px(P-1), f1_px0…f1_px(P-1), ...]
     *
     * Interleaving groups same-position pixels from all frames together, which exposes
     * long runs of identical bytes for static pixels and compresses significantly better
     * for map art animations with mostly-static backgrounds.
     *
     * Old clients (without this flag) will see garbled frame data for interleaved tiles.
     * Old tiles without this flag continue to decode correctly via the sequential path.
     */
    public static final int FLAG_INTERLEAVED = 0x04;

    public final int manifestVersion;
    /** Total bytes consumed by this header; map colors begin at this offset. */
    public final int headerSize;
    public final int flags;
    public final int cols;
    public final int rows;
    public final int tileCol;
    public final int tileRow;
    /** CRC32 of the 16,384 map-color bytes. -1 if unknown (future version stub). */
    public final long colorCrc32;
    public final String username; // null if absent
    public final String title;    // null if absent
    /** v2+: random nonce from /loominary resalt; 0 for v1 payloads. */
    public final int nonce;
    /**
     * v3+: total animation frames; 1 = static (FLAG_ANIMATED not set).
     * Map-color bytes are {@code frameCount × 16384} bytes starting at {@code headerSize}.
     */
    public final int frameCount;
    /** v3+: loop count; 0 = infinite. */
    public final int loopCount;
    /**
     * v3+: per-frame delays in milliseconds.
     * Length 1 = single global delay shared by all frames.
     * Length frameCount = per-frame delay table.
     */
    public final int[] frameDelays;

    private PayloadManifest(int manifestVersion, int headerSize, int flags,
                             int cols, int rows, int tileCol, int tileRow,
                             long colorCrc32, String username, String title, int nonce,
                             int frameCount, int loopCount, int[] frameDelays) {
        this.manifestVersion = manifestVersion;
        this.headerSize = headerSize;
        this.flags = flags;
        this.cols = cols;
        this.rows = rows;
        this.tileCol = tileCol;
        this.tileRow = tileRow;
        this.colorCrc32 = colorCrc32;
        this.username = username;
        this.title = title;
        this.nonce = nonce;
        this.frameCount = frameCount;
        this.loopCount = loopCount;
        this.frameDelays = frameDelays;
    }

    public boolean allShades() {
        return (flags & FLAG_ALL_SHADES) != 0;
    }

    public boolean animated() {
        return (flags & FLAG_ANIMATED) != 0;
    }

    public boolean interleaved() {
        return (flags & FLAG_INTERLEAVED) != 0;
    }

    // ── Interleave / de-interleave utilities ─────────────────────────────

    /**
     * Converts a frame-sequential byte array into temporal-interleaved order.
     * Input:  [f0_px0, f0_px1, ..., f0_px(P-1), f1_px0, ..., f(F-1)_px(P-1)]
     * Output: [f0_px0, f1_px0, ..., f(F-1)_px0, f0_px1, ..., f(F-1)_px(P-1)]
     */
    public static byte[] interleaveFlat(byte[] sequential, int frameCount) {
        if (frameCount <= 1) return sequential;
        int ppf = sequential.length / frameCount;
        byte[] out = new byte[sequential.length];
        for (int p = 0; p < ppf; p++)
            for (int f = 0; f < frameCount; f++)
                out[p * frameCount + f] = sequential[f * ppf + p];
        return out;
    }

    /**
     * Reverses {@link #interleaveFlat}: converts temporal-interleaved back to
     * frame-sequential order so downstream code can extract frames normally.
     */
    public static byte[] deinterleaveFlat(byte[] interleaved, int frameCount) {
        if (frameCount <= 1) return interleaved;
        int ppf = interleaved.length / frameCount;
        byte[] out = new byte[interleaved.length];
        for (int p = 0; p < ppf; p++)
            for (int f = 0; f < frameCount; f++)
                out[f * ppf + p] = interleaved[p * frameCount + f];
        return out;
    }

    // ── CRC helper ───────────────────────────────────────────────────────

    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    // ── Serialization ────────────────────────────────────────────────────

    /** Produces a v1 manifest (no nonce). */
    public static byte[] toBytes(int flags, int cols, int rows, int tileCol, int tileRow,
                                  long colorCrc32, String username, String title) {
        byte[] usernameBytes = encodeString(username, 16);
        byte[] titleBytes    = encodeString(title, 64);

        // 7 fixed bytes + 4 (color_crc32) + 1 (username_len) + username + 1 (title_len) + title
        int totalSize = 13 + usernameBytes.length + titleBytes.length;

        byte[] out = new byte[totalSize];
        int i = 0;
        out[i++] = 1;                  // manifest_version = 1
        out[i++] = (byte) totalSize;   // header_size
        out[i++] = (byte) flags;
        out[i++] = (byte) cols;
        out[i++] = (byte) rows;
        out[i++] = (byte) tileCol;
        out[i++] = (byte) tileRow;
        // color_crc32 big-endian u32
        out[i++] = (byte) ((colorCrc32 >> 24) & 0xFF);
        out[i++] = (byte) ((colorCrc32 >> 16) & 0xFF);
        out[i++] = (byte) ((colorCrc32 >>  8) & 0xFF);
        out[i++] = (byte) ( colorCrc32        & 0xFF);
        out[i++] = (byte) usernameBytes.length;
        System.arraycopy(usernameBytes, 0, out, i, usernameBytes.length);
        i += usernameBytes.length;
        out[i++] = (byte) titleBytes.length;
        System.arraycopy(titleBytes, 0, out, i, titleBytes.length);
        return out;
    }

    /**
     * Produces a v2 manifest with a random nonce that changes all chunk names
     * without affecting the decoded image. If {@code nonce == 0}, delegates to
     * {@link #toBytes(int, int, int, int, int, long, String, String)} so callers
     * can always pass the tile's stored nonce without checking it first.
     */
    public static byte[] toBytes(int flags, int cols, int rows, int tileCol, int tileRow,
                                  long colorCrc32, String username, String title, int nonce) {
        if (nonce == 0) return toBytes(flags, cols, rows, tileCol, tileRow,
                colorCrc32, username, title);

        byte[] usernameBytes = encodeString(username, 16);
        byte[] titleBytes    = encodeString(title, 64);

        // 7 fixed + 4 crc32 + 1 username_len + username + 1 title_len + title + 4 nonce
        int totalSize = 17 + usernameBytes.length + titleBytes.length;

        byte[] out = new byte[totalSize];
        int i = 0;
        out[i++] = 2;                  // manifest_version = 2
        out[i++] = (byte) totalSize;   // header_size
        out[i++] = (byte) flags;
        out[i++] = (byte) cols;
        out[i++] = (byte) rows;
        out[i++] = (byte) tileCol;
        out[i++] = (byte) tileRow;
        out[i++] = (byte) ((colorCrc32 >> 24) & 0xFF);
        out[i++] = (byte) ((colorCrc32 >> 16) & 0xFF);
        out[i++] = (byte) ((colorCrc32 >>  8) & 0xFF);
        out[i++] = (byte) ( colorCrc32        & 0xFF);
        out[i++] = (byte) usernameBytes.length;
        System.arraycopy(usernameBytes, 0, out, i, usernameBytes.length);
        i += usernameBytes.length;
        out[i++] = (byte) titleBytes.length;
        System.arraycopy(titleBytes, 0, out, i, titleBytes.length);
        i += titleBytes.length;
        // nonce big-endian u32
        out[i++] = (byte) ((nonce >> 24) & 0xFF);
        out[i++] = (byte) ((nonce >> 16) & 0xFF);
        out[i++] = (byte) ((nonce >>  8) & 0xFF);
        out[i  ] = (byte) ( nonce        & 0xFF);
        return out;
    }

    /**
     * Produces a v3 manifest with animation metadata.
     *
     * @param nonce       0 = no salt; non-zero = /loominary resalt value
     * @param frameCount  total animation frames (≥1)
     * @param loopCount   0 = loop forever; >0 = stop after N loops
     * @param frameDelays delay(s) in ms — length 1 = global shared delay,
     *                    length frameCount = per-frame delay table
     */
    public static byte[] toBytes(int flags, int cols, int rows, int tileCol, int tileRow,
                                  long colorCrc32, String username, String title, int nonce,
                                  int frameCount, int loopCount, int[] frameDelays) {
        byte[] usernameBytes = encodeString(username, 16);
        byte[] titleBytes    = encodeString(title, 64);

        boolean perFrame = frameDelays.length > 1;
        int delayBytes = perFrame ? frameCount * 2 : 2;

        // 7 fixed + 4 crc32 + 1 username_len + username + 1 title_len + title
        // + 4 nonce + 1 frame_count + 2 loop_count + 1 delay_mode + delayBytes
        int totalSize = 21 + usernameBytes.length + titleBytes.length + delayBytes;

        if (totalSize > 255) {
            throw new IllegalArgumentException(
                    "v3 manifest header exceeds 255 bytes (" + totalSize + ") — "
                    + "reduce frame count or shorten username/title");
        }

        byte[] out = new byte[totalSize];
        int i = 0;
        out[i++] = 3;                  // manifest_version = 3
        out[i++] = (byte) totalSize;   // header_size
        out[i++] = (byte) flags;
        out[i++] = (byte) cols;
        out[i++] = (byte) rows;
        out[i++] = (byte) tileCol;
        out[i++] = (byte) tileRow;
        out[i++] = (byte) ((colorCrc32 >> 24) & 0xFF);
        out[i++] = (byte) ((colorCrc32 >> 16) & 0xFF);
        out[i++] = (byte) ((colorCrc32 >>  8) & 0xFF);
        out[i++] = (byte) ( colorCrc32        & 0xFF);
        out[i++] = (byte) usernameBytes.length;
        System.arraycopy(usernameBytes, 0, out, i, usernameBytes.length);
        i += usernameBytes.length;
        out[i++] = (byte) titleBytes.length;
        System.arraycopy(titleBytes, 0, out, i, titleBytes.length);
        i += titleBytes.length;
        // nonce big-endian u32
        out[i++] = (byte) ((nonce >> 24) & 0xFF);
        out[i++] = (byte) ((nonce >> 16) & 0xFF);
        out[i++] = (byte) ((nonce >>  8) & 0xFF);
        out[i++] = (byte) ( nonce        & 0xFF);
        // animation fields
        out[i++] = (byte) frameCount;
        out[i++] = (byte) ((loopCount >> 8) & 0xFF);
        out[i++] = (byte)  (loopCount       & 0xFF);
        out[i++] = (byte) (perFrame ? 1 : 0);  // delay_mode
        if (perFrame) {
            for (int f = 0; f < frameCount; f++) {
                int d = f < frameDelays.length ? frameDelays[f] : 100;
                out[i++] = (byte) ((d >> 8) & 0xFF);
                out[i++] = (byte)  (d       & 0xFF);
            }
        } else {
            int d = frameDelays.length > 0 ? frameDelays[0] : 100;
            out[i++] = (byte) ((d >> 8) & 0xFF);
            out[i  ] = (byte)  (d       & 0xFF);
        }
        return out;
    }

    /**
     * Parses the manifest from the start of a decompressed payload.
     * For unknown future versions, returns a stub with only manifestVersion
     * and headerSize set — the caller can still locate the map colors.
     */
    public static PayloadManifest fromBytes(byte[] data) {
        if (data.length < 2) {
            throw new IllegalArgumentException(
                    "Decompressed payload too short to contain manifest: " + data.length + " bytes");
        }

        int ver        = data[0] & 0xFF;
        int headerSize = data[1] & 0xFF;

        if (ver > CURRENT_VERSION) {
            // Unknown version — return stub so the caller can still skip to map colors.
            return new PayloadManifest(ver, headerSize, 0, 0, 0, 0, 0, -1L, null, null,
                    0, 1, 0, new int[]{100});
        }

        // Parse v1/v2 manifest — minimum 13 bytes (11 fixed + 2 length fields)
        if (data.length < 13) {
            throw new IllegalArgumentException(
                    "v1 manifest too short: expected at least 13 bytes, got " + data.length);
        }

        int i       = 2;
        int flags   = data[i++] & 0xFF;
        int cols    = data[i++] & 0xFF;
        int rows    = data[i++] & 0xFF;
        int tileCol = data[i++] & 0xFF;
        int tileRow = data[i++] & 0xFF;

        long colorCrc32 = ((long)(data[i++] & 0xFF) << 24)
                        | ((long)(data[i++] & 0xFF) << 16)
                        | ((long)(data[i++] & 0xFF) <<  8)
                        | ((long)(data[i++] & 0xFF));

        int usernameLen = data[i++] & 0xFF;
        String username = null;
        if (usernameLen > 0) {
            if (i + usernameLen > data.length) {
                throw new IllegalArgumentException(
                        "v1 manifest username extends past payload end");
            }
            username = new String(data, i, usernameLen, StandardCharsets.UTF_8);
            i += usernameLen;
        }

        if (i >= data.length) {
            throw new IllegalArgumentException(
                    "v1 manifest truncated before title_len");
        }
        int titleLen = data[i++] & 0xFF;
        String title = null;
        if (titleLen > 0) {
            if (i + titleLen > data.length) {
                throw new IllegalArgumentException(
                        "v1 manifest title extends past payload end");
            }
            title = new String(data, i, titleLen, StandardCharsets.UTF_8);
            i += titleLen;
        }

        int nonce = 0;
        if (ver >= 2 && i + 4 <= data.length) {
            nonce = ((data[i    ] & 0xFF) << 24)
                  | ((data[i + 1] & 0xFF) << 16)
                  | ((data[i + 2] & 0xFF) <<  8)
                  | ( data[i + 3] & 0xFF);
            i += 4;
        }

        int frameCount = 1;
        int loopCount  = 0;
        int[] frameDelays = {100};
        if (ver >= 3 && i < data.length) {
            frameCount = data[i++] & 0xFF;
            if (i + 2 <= data.length) {
                loopCount = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                i += 2;
            }
            if (i < data.length) {
                int delayMode = data[i++] & 0xFF;
                if (delayMode == 0 && i + 2 <= data.length) {
                    frameDelays = new int[]{((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF)};
                } else if (delayMode == 1) {
                    frameDelays = new int[frameCount];
                    for (int f = 0; f < frameCount && i + 2 <= data.length; f++, i += 2) {
                        frameDelays[f] = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                    }
                }
            }
        }

        return new PayloadManifest(ver, headerSize, flags, cols, rows, tileCol, tileRow,
                colorCrc32, username, title, nonce, frameCount, loopCount, frameDelays);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static byte[] encodeString(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return new byte[0];
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        return raw.length <= maxBytes ? raw : Arrays.copyOf(raw, maxBytes);
    }
}
