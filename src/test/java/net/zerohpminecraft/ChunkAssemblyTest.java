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

    // ── Delta frame encoding ──────────────────────────────────────────────

    @Test
    void deltaEncodeDecodeRoundTrip() {
        int frameCount = 4;
        // Build deterministic raw frames: frame 0 = alternating bytes, subsequent frames change ~10% of pixels.
        byte[][] rawFrames = new byte[frameCount][MAP_BYTES];
        for (int p = 0; p < MAP_BYTES; p++) rawFrames[0][p] = (byte)(p & 0xFF);
        java.util.Random rng = new java.util.Random(42);
        for (int f = 1; f < frameCount; f++) {
            System.arraycopy(rawFrames[f - 1], 0, rawFrames[f], 0, MAP_BYTES);
            // Change ~10% of pixels
            for (int p = 0; p < MAP_BYTES; p++)
                if (rng.nextInt(10) == 0) rawFrames[f][p] = (byte) rng.nextInt(256);
        }

        // Flatten to a single byte array as the encoder does.
        byte[] flat = new byte[frameCount * MAP_BYTES];
        for (int f = 0; f < frameCount; f++)
            System.arraycopy(rawFrames[f], 0, flat, f * MAP_BYTES, MAP_BYTES);

        // Apply delta encoding.
        byte[] deltaFlat = net.zerohpminecraft.command.LoominaryCommand.toDeltaFrames(flat, frameCount);

        // Frame 0 must be unchanged.
        for (int p = 0; p < MAP_BYTES; p++)
            assertEquals(rawFrames[0][p], deltaFlat[p], "frame 0 must be raw");

        // Reconstruct via the decoder helper (in-place on byte[][]).
        byte[][] deltaFrames = new byte[frameCount][MAP_BYTES];
        for (int f = 0; f < frameCount; f++)
            System.arraycopy(deltaFlat, f * MAP_BYTES, deltaFrames[f], 0, MAP_BYTES);
        MapBannerDecoder.reconstructDeltaFrames(deltaFrames);

        // After reconstruction every frame must match the original raw frame.
        for (int f = 0; f < frameCount; f++)
            assertArrayEquals(rawFrames[f], deltaFrames[f], "frame " + f + " mismatch after reconstruction");
    }

    @Test
    void deltaCompressesBetterThanRaw() {
        // Demonstrates delta benefit when the animation is long enough to exhaust zstd's
        // back-reference window.  Zstd level 3 uses a ~512 KB window (≈ 31 map frames).
        // With 60 frames, raw zstd can no longer back-reference frame 0 from frame 31+,
        // so identical pixels in late frames must be stored as literals.  Delta frames are
        // ~98% zeros throughout and remain highly compressible regardless of window size.
        int frameCount = 60;
        // Binary frames simulating Bad Apple: value 0 (black) vs value 119 (white-ish).
        byte[] rawFrames0 = new byte[MAP_BYTES]; // all-zero base
        // Pepper in ~20% foreground pixels.
        java.util.Random rng = new java.util.Random(99);
        for (int p = 0; p < MAP_BYTES; p++) if (rng.nextInt(5) == 0) rawFrames0[p] = 119;

        byte[] flat = new byte[frameCount * MAP_BYTES];
        System.arraycopy(rawFrames0, 0, flat, 0, MAP_BYTES);
        // Each frame: copy previous, change ~2% of pixels.
        for (int f = 1; f < frameCount; f++) {
            System.arraycopy(flat, (f - 1) * MAP_BYTES, flat, f * MAP_BYTES, MAP_BYTES);
            for (int p = 0; p < MAP_BYTES; p++) {
                if (rng.nextInt(50) == 0)
                    flat[f * MAP_BYTES + p] ^= 119; // toggle black ↔ white
            }
        }

        byte[] deltaFlat = net.zerohpminecraft.command.LoominaryCommand.toDeltaFrames(flat, frameCount);

        int rawSize   = Zstd.compress(flat,      3).length;
        int deltaSize = Zstd.compress(deltaFlat, 3).length;
        assertTrue(deltaSize < rawSize,
                "delta should compress better than raw once the animation exceeds zstd's window "
                + "(delta=" + deltaSize + " raw=" + rawSize + ")");
    }
}
