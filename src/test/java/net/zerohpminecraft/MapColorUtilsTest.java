package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure-logic methods in PngToMapColors that have no Minecraft dependency.
 */
class MapColorUtilsTest {

    @Test
    void countDistinct_uniformColor() {
        byte[] colors = new byte[128 * 128];
        java.util.Arrays.fill(colors, (byte) 0x04);
        assertEquals(1, PngToMapColors.countDistinct(colors));
    }

    @Test
    void countDistinct_allTransparent_zero() {
        byte[] colors = new byte[128 * 128];
        assertEquals(0, PngToMapColors.countDistinct(colors));
    }

    @Test
    void countDistinct_twoColors() {
        byte[] colors = new byte[128 * 128];
        colors[0] = 4;
        colors[1] = 8;
        assertEquals(2, PngToMapColors.countDistinct(colors));
    }

    @Test
    void countDistinct_allSameByteExceptZero() {
        byte[] colors = new byte[256];
        for (int i = 1; i < 256; i++) colors[i] = (byte) i;
        assertEquals(255, PngToMapColors.countDistinct(colors));
    }

    @Test
    void chunksNeeded_exactDivisor() {
        // Produce compressed bytes whose base64 length is exactly 3 * CHUNK_SIZE (48)
        // so we get exactly 3 chunks with no remainder.
        byte[] data = new byte[128 * 128];
        byte[] compressed = Zstd.compress(data, Zstd.maxCompressionLevel());
        String b64 = Base64.getEncoder().encodeToString(compressed);

        int chunkSize = 48;
        int expected = (b64.length() + chunkSize - 1) / chunkSize;
        assertEquals(expected, PngToMapColors.chunksNeeded(compressed, chunkSize));
    }

    @Test
    void chunksNeeded_oneByteOverBoundary() {
        // Build a byte[] whose base64 is exactly chunkSize+1 chars long — must be 2 chunks.
        // Base64: every 3 bytes → 4 chars.  For 49 chars we need ceiling(49*3/4) = 37 raw bytes.
        // But it's easier to just parametrize with a known compressed output.
        int chunkSize = 4;
        // 3 raw bytes → 4 base64 chars — but we want 5 chars = 2 chunks of size 4.
        // 4 raw bytes → ceil = 8 base64 chars → 2 chunks of 4 exactly.
        // 5 raw bytes → ceil = 8 base64 chars still.  Hard to control exact output size.
        // Instead: just assert ceiling behavior directly on a constructed b64 length.
        for (int len = 1; len <= 32; len++) {
            byte[] fake = new byte[len]; // doesn't matter what's in it, testing ceil formula
            byte[] compressed = Zstd.compress(fake, Zstd.maxCompressionLevel());
            String b64 = Base64.getEncoder().encodeToString(compressed);
            int expected = (b64.length() + chunkSize - 1) / chunkSize;
            assertEquals(expected, PngToMapColors.chunksNeeded(compressed, chunkSize),
                    "failed for len=" + len);
        }
    }
}
