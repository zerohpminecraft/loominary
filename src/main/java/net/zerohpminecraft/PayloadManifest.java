package net.zerohpminecraft;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Manifest header prepended to map-color bytes inside the zstd frame.
 *
 * Wire layout (bytes at the start of the decompressed payload):
 *   [0]     manifest_version  u8  — schema version; 1, 2, 3, or 4
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
 *   (v3, appended after nonce — frame_count is u8, max 255 frames)
 *   […]     frame_count       u8  — total frames; 1 for static tiles
 *   […]     loop_count        u16 big-endian — 0 = loop forever; >0 = stop after N loops
 *   […]     delay_mode        u8  — 0 = single global delay; 1 = per-frame table
 *   […]     delay             u16 big-endian (delay_mode=0), or
 *                             frame_count × u16 (delay_mode=1) — milliseconds per frame
 *   (v4, same as v3 but frame_count is u16, max 65535 frames)
 *   […]     frame_count       u16 big-endian — total frames; 1 for static tiles
 *   […]     loop_count        u16 big-endian
 *   […]     delay_mode        u8
 *   […]     delay             u16 (delay_mode=0) or frame_count × u16 (delay_mode=1)
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

    /**
     * Highest manifest version this client can decode.
     *
     * <p>v5 adds <em>trailing delays</em>: when a per-frame delay table would overflow the
     * 255-byte {@code header_size} field, {@code delay_mode=1} is written with no inline delay
     * bytes and the table is appended after the last frame's map-color data.  The decoder
     * reads it from {@code data[headerSize + frameCount × 16384]}.  Old decoders (v4 and
     * earlier) seeing a v5 tile return a stub and render frame 0 as a static image.
     */
    public static final int CURRENT_VERSION = 5;

    public static final int FLAG_ALL_SHADES = 0x01;
    /** Set when the payload contains multiple animation frames. */
    public static final int FLAG_ANIMATED   = 0x02;
    /** Set when the tile participates in cross-tile payload redistribution (mux pooling). */
    public static final int FLAG_MUX        = 0x04;

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

    public boolean muxed() {
        return (flags & FLAG_MUX) != 0;
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
     * Produces a v4 or v5 manifest with animation metadata.
     *
     * <p>If all frame delays are equal they are collapsed to a single global delay
     * (saves space and avoids unnecessary per-frame tables).
     *
     * <p>If after normalization the delay table would overflow the 255-byte
     * {@code header_size} field, a <b>v5</b> manifest is produced: {@code delay_mode=1}
     * is written with no inline delay bytes.  The caller must append the delay table
     * after all frame data using {@link #trailingDelayBytes(byte[], int[])}.
     *
     * @param nonce       0 = no salt; non-zero = /loominary resalt value
     * @param frameCount  total animation frames (≥1)
     * @param loopCount   0 = loop forever; &gt;0 = stop after N loops
     * @param frameDelays delay(s) in ms — length 1 = global shared delay,
     *                    length frameCount = per-frame delay table
     */
    public static byte[] toBytes(int flags, int cols, int rows, int tileCol, int tileRow,
                                  long colorCrc32, String username, String title, int nonce,
                                  int frameCount, int loopCount, int[] frameDelays) {
        byte[] usernameBytes = encodeString(username, 16);
        byte[] titleBytes    = encodeString(title, 64);

        if (frameCount < 1 || frameCount > 65535) {
            throw new IllegalArgumentException(
                    "frameCount must be 1–65535, got " + frameCount);
        }

        // ── Normalize: collapse a uniform per-frame array to a single global delay ──
        boolean perFrame = frameDelays.length > 1;
        if (perFrame) {
            int first = frameDelays[0];
            boolean allSame = true;
            for (int d : frameDelays) if (d != first) { allSame = false; break; }
            if (allSame) { perFrame = false; frameDelays = new int[]{first}; }
        }

        // ── Decide whether delay table is inline (v4) or trailing (v5) ──
        // Fixed overhead: 7 (header) + 4 (crc32) + 1 (usernameLen) + username
        //                 + 1 (titleLen) + title + 4 (nonce) + 2 (frameCount u16)
        //                 + 2 (loopCount) + 1 (delayMode) = 22 bytes.
        int fixedSize   = 22 + usernameBytes.length + titleBytes.length;
        int inlineDelay = perFrame ? frameCount * 2 : 2;
        boolean trailing = perFrame && (fixedSize + inlineDelay > 255);

        int totalSize = fixedSize + (trailing ? 0 : inlineDelay);
        // totalSize ≤ 255 is now guaranteed by construction.

        byte[] out = new byte[totalSize];
        int i = 0;
        out[i++] = (byte) (trailing ? 5 : 4);  // manifest_version
        out[i++] = (byte) totalSize;             // header_size — always ≤255
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
        out[i++] = (byte) ((nonce >> 24) & 0xFF);
        out[i++] = (byte) ((nonce >> 16) & 0xFF);
        out[i++] = (byte) ((nonce >>  8) & 0xFF);
        out[i++] = (byte) ( nonce        & 0xFF);
        out[i++] = (byte) (frameCount >>> 8);
        out[i++] = (byte)  frameCount;
        out[i++] = (byte) ((loopCount >> 8) & 0xFF);
        out[i++] = (byte)  (loopCount       & 0xFF);
        out[i++] = (byte) (perFrame || trailing ? 1 : 0); // delay_mode

        if (trailing) {
            // No delay bytes here; caller appends table after frame data via trailingDelayBytes().
        } else if (perFrame) {
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
     * Returns the per-frame delay table that must be appended <em>after all frame data</em>
     * when the manifest produced by {@link #toBytes} is a v5 manifest (byte 0 == 5).
     * Returns an empty array for v4 manifests (table is already inline) and for
     * global-delay manifests (no table needed).
     */
    public static byte[] trailingDelayBytes(byte[] manifest, int[] frameDelays) {
        if (manifest.length < 1 || (manifest[0] & 0xFF) < 5) return new byte[0];
        if (frameDelays.length <= 1) return new byte[0];
        byte[] out = new byte[frameDelays.length * 2];
        for (int f = 0; f < frameDelays.length; f++) {
            int d = frameDelays[f];
            out[f * 2]     = (byte) ((d >> 8) & 0xFF);
            out[f * 2 + 1] = (byte)  (d       & 0xFF);
        }
        return out;
    }

    /**
     * Appends a trailing delay table to {@code frameData} when required by a v5 manifest.
     * Returns {@code frameData} unchanged for v4 manifests or global-delay payloads.
     * Use this at every call site that constructs {@code payload = manifest + frameData}
     * before compressing, so the delay table is included inside the zstd frame.
     */
    public static byte[] withTrailing(byte[] manifest, int[] frameDelays, byte[] frameData) {
        byte[] trailing = trailingDelayBytes(manifest, frameDelays);
        if (trailing.length == 0) return frameData;
        byte[] out = new byte[frameData.length + trailing.length];
        System.arraycopy(frameData,  0, out, 0,               frameData.length);
        System.arraycopy(trailing,   0, out, frameData.length, trailing.length);
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
            if (ver >= 4 && i + 2 <= data.length) {
                // v4+: frame_count is u16
                frameCount = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                i += 2;
            } else {
                // v3: frame_count is u8
                frameCount = data[i++] & 0xFF;
            }
            if (i + 2 <= data.length) {
                loopCount = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                i += 2;
            }
            if (i < data.length) {
                int delayMode = data[i++] & 0xFF;
                if (delayMode == 0 && i + 2 <= data.length) {
                    frameDelays = new int[]{((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF)};
                } else if (delayMode == 1) {
                    if (ver >= 5) {
                        // v5 trailing: delay table lives after all frame data.
                        // Location: headerSize + frameCount * MAP_BYTES.
                        int MAP_BYTES = 128 * 128;
                        int dt = headerSize + frameCount * MAP_BYTES;
                        frameDelays = new int[frameCount];
                        for (int f = 0; f < frameCount && dt + 2 <= data.length; f++, dt += 2)
                            frameDelays[f] = ((data[dt] & 0xFF) << 8) | (data[dt + 1] & 0xFF);
                    } else {
                        // v3/v4: per-frame inline
                        frameDelays = new int[frameCount];
                        for (int f = 0; f < frameCount && i + 2 <= data.length; f++, i += 2)
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
