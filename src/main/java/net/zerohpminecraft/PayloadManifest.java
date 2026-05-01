package net.zerohpminecraft;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Manifest header prepended to map-color bytes inside the zstd frame.
 *
 * Wire layout (bytes at the start of the decompressed payload):
 *   [0]     manifest_version  u8  — schema version; currently 1
 *   [1]     header_size       u8  — total bytes in this header (allows future
 *                                   clients to skip unknown versions and still
 *                                   find the map colors at offset header_size)
 *   [2]     flags             u8  — bit 0 = allShades; bits 1-7 reserved
 *   [3]     cols              u8  — grid columns
 *   [4]     rows              u8  — grid rows
 *   [5]     tile_col          u8  — this tile's column (0-indexed)
 *   [6]     tile_row          u8  — this tile's row (0-indexed)
 *   [7-10]  color_crc32       u32 big-endian — CRC32 of the 16,384 map-color
 *                                 bytes that follow the header
 *   [11]    username_len      u8  — 0 if absent; max 16
 *   [12…]   username          UTF-8
 *   […]     title_len         u8  — 0 if absent; max 64
 *   […]     title             UTF-8
 *
 * The 16,384 map-color bytes follow immediately after the header.
 *
 * Format detection: decompressed size == 16384 → v0 (no manifest);
 * decompressed size > 16384 → v1+ (manifest prefix). v0 payloads
 * (encoded by Loominary 1.0.0) continue to decode identically.
 *
 * Manifest fields are informational only — none of them gate decode
 * behavior in v1. This keeps the decoder simple and means field
 * mistakes are cosmetic, not functional.
 */
public class PayloadManifest {

    public static final int CURRENT_VERSION = 1;

    public static final int FLAG_ALL_SHADES = 0x01;

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

    private PayloadManifest(int manifestVersion, int headerSize, int flags,
                             int cols, int rows, int tileCol, int tileRow,
                             long colorCrc32, String username, String title) {
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
    }

    public boolean allShades() {
        return (flags & FLAG_ALL_SHADES) != 0;
    }

    // ── CRC helper ───────────────────────────────────────────────────────

    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    // ── Serialization ────────────────────────────────────────────────────

    public static byte[] toBytes(int flags, int cols, int rows, int tileCol, int tileRow,
                                  long colorCrc32, String username, String title) {
        byte[] usernameBytes = encodeString(username, 16);
        byte[] titleBytes    = encodeString(title, 64);

        // 7 fixed bytes + 4 (color_crc32) + 1 (username_len) + username + 1 (title_len) + title
        int totalSize = 13 + usernameBytes.length + titleBytes.length;

        byte[] out = new byte[totalSize];
        int i = 0;
        out[i++] = (byte) CURRENT_VERSION;
        out[i++] = (byte) totalSize;       // header_size
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
            return new PayloadManifest(ver, headerSize, 0, 0, 0, 0, 0, -1L, null, null);
        }

        // Parse v1 manifest — minimum 13 bytes (11 fixed + 2 length fields)
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
        }

        return new PayloadManifest(ver, headerSize, flags, cols, rows, tileCol, tileRow,
                colorCrc32, username, title);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static byte[] encodeString(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return new byte[0];
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        return raw.length <= maxBytes ? raw : Arrays.copyOf(raw, maxBytes);
    }
}
