package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MapBannerDecoder.reassemblePayload — chunk sorting, concatenation,
 * decompression, and v0/v1 payload detection.
 */
class ChunkAssemblyTest {

    private static final int MAP_BYTES = 128 * 128;
    private static final int CHUNK_SIZE = 48;

    /** Compress data, base64-encode, split into indexed chunks. */
    private static List<String> makeChunks(byte[] data) {
        byte[] compressed = Zstd.compress(data, Zstd.maxCompressionLevel());
        String b64 = Base64.getEncoder().encodeToString(compressed);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i * CHUNK_SIZE < b64.length(); i++) {
            int end = Math.min((i + 1) * CHUNK_SIZE, b64.length());
            chunks.add(String.format("%02x", i) + b64.substring(i * CHUNK_SIZE, end));
        }
        return chunks;
    }

    @Test
    void v0_singleChunk_returnsMapBytes() {
        byte[] colors = new byte[MAP_BYTES];
        colors[0] = 42;
        colors[MAP_BYTES - 1] = (byte) 0xFF;

        List<String> chunks = makeChunks(colors);
        byte[] result = MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));

        assertArrayEquals(colors, result);
    }

    @Test
    void v0_multipleChunks_returnsMapBytes() {
        byte[] colors = new byte[MAP_BYTES];
        for (int i = 0; i < MAP_BYTES; i++) colors[i] = (byte) (i & 0xFF);

        List<String> chunks = makeChunks(colors);
        assertTrue(chunks.size() > 1, "expected more than one chunk for varied data");

        byte[] result = MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));
        assertArrayEquals(colors, result);
    }

    @Test
    void v0_chunksOutOfOrder_sortedAndReassembled() {
        byte[] colors = new byte[MAP_BYTES];
        for (int i = 0; i < MAP_BYTES; i++) colors[i] = (byte) (i % 200);

        List<String> chunks = makeChunks(colors);
        Collections.shuffle(chunks);

        byte[] result = MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));
        assertArrayEquals(colors, result);
    }

    @Test
    void v1_withManifest_returnsMapBytes() {
        byte[] colors = new byte[MAP_BYTES];
        colors[100] = 77;

        long crc = PayloadManifest.crc32(colors);
        byte[] manifest = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ALL_SHADES, 2, 1, 0, 0, crc, "TestUser", "Art");
        byte[] combined = new byte[manifest.length + MAP_BYTES];
        System.arraycopy(manifest, 0, combined, 0, manifest.length);
        System.arraycopy(colors, 0, combined, manifest.length, MAP_BYTES);

        List<String> chunks = makeChunks(combined);
        byte[] result = MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));

        assertArrayEquals(colors, result);
    }

    @Test
    void nonContiguousChunk_throws() {
        byte[] colors = new byte[MAP_BYTES];
        List<String> chunks = makeChunks(colors);
        // Remove chunk at index 1 (leaving a gap)
        if (chunks.size() > 2) {
            chunks.remove(1);
        } else {
            // Single-chunk payload: swap index to create a gap
            chunks.set(0, "01" + chunks.get(0).substring(2));
        }

        assertThrows(IllegalStateException.class,
                () -> MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks)));
    }

    @Test
    void decompressedSmallerThanMapBytes_throws() {
        // Compress a tiny payload so decompressed size < 16384
        byte[] tiny = {1, 2, 3, 4};
        List<String> chunks = makeChunks(tiny);

        assertThrows(IllegalStateException.class,
                () -> MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks)));
    }
}
