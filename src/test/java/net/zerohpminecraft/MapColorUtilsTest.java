package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure-logic methods in PngToMapColors that have no Minecraft dependency.
 */
class MapColorUtilsTest {

    private static final float OKLAB_EPSILON = 1e-4f;

    // ── countDistinct ────────────────────────────────────────────────────

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

    // ── chunksNeeded ─────────────────────────────────────────────────────

    @Test
    void chunksNeeded_exactDivisor() {
        byte[] data = new byte[128 * 128];
        byte[] compressed = Zstd.compress(data, Zstd.maxCompressionLevel());
        String b64 = Base64.getEncoder().encodeToString(compressed);
        int chunkSize = 48;
        int expected = (b64.length() + chunkSize - 1) / chunkSize;
        assertEquals(expected, PngToMapColors.chunksNeeded(compressed, chunkSize));
    }

    @Test
    void chunksNeeded_ceilingBehavior() {
        int chunkSize = 4;
        for (int len = 1; len <= 32; len++) {
            byte[] compressed = Zstd.compress(new byte[len], Zstd.maxCompressionLevel());
            String b64 = Base64.getEncoder().encodeToString(compressed);
            int expected = (b64.length() + chunkSize - 1) / chunkSize;
            assertEquals(expected, PngToMapColors.chunksNeeded(compressed, chunkSize),
                    "failed for len=" + len);
        }
    }

    // ── srgbToLinear ─────────────────────────────────────────────────────

    @Test
    void srgbToLinear_zero_isZero() {
        assertEquals(0f, PngToMapColors.srgbToLinear(0f), OKLAB_EPSILON);
    }

    @Test
    void srgbToLinear_one_isOne() {
        assertEquals(1f, PngToMapColors.srgbToLinear(1f), OKLAB_EPSILON);
    }

    @Test
    void srgbToLinear_lowBranch_isLinear() {
        // Below threshold (0.04045) the formula is c / 12.92
        float c = 0.01f;
        assertEquals(c / 12.92f, PngToMapColors.srgbToLinear(c), OKLAB_EPSILON);
    }

    @Test
    void srgbToLinear_highBranch_isPower() {
        // Above threshold the formula is ((c + 0.055) / 1.055)^2.4
        float c = 0.5f;
        float expected = (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
        assertEquals(expected, PngToMapColors.srgbToLinear(c), OKLAB_EPSILON);
    }

    // ── rgbToOklab ───────────────────────────────────────────────────────

    @Test
    void rgbToOklab_black_isOrigin() {
        float[] lab = PngToMapColors.rgbToOklab(0x000000);
        assertEquals(0f, lab[0], OKLAB_EPSILON);
        assertEquals(0f, lab[1], OKLAB_EPSILON);
        assertEquals(0f, lab[2], OKLAB_EPSILON);
    }

    @Test
    void rgbToOklab_white_isLOne_abNearZero() {
        float[] lab = PngToMapColors.rgbToOklab(0xFFFFFF);
        assertEquals(1f,  lab[0], OKLAB_EPSILON);
        assertEquals(0f,  lab[1], OKLAB_EPSILON);
        assertEquals(0f,  lab[2], OKLAB_EPSILON);
    }

    @Test
    void rgbToOklab_sameColor_zeroDistance() {
        float[] a = PngToMapColors.rgbToOklab(0x4A90D9);
        float[] b = PngToMapColors.rgbToOklab(0x4A90D9);
        float dist = (a[0]-b[0])*(a[0]-b[0]) + (a[1]-b[1])*(a[1]-b[1]) + (a[2]-b[2])*(a[2]-b[2]);
        assertEquals(0f, dist, OKLAB_EPSILON);
    }

    @Test
    void rgbToOklab_differentColors_nonZeroDistance() {
        float[] red   = PngToMapColors.rgbToOklab(0xFF0000);
        float[] green = PngToMapColors.rgbToOklab(0x00FF00);
        float dist = (red[0]-green[0])*(red[0]-green[0])
                   + (red[1]-green[1])*(red[1]-green[1])
                   + (red[2]-green[2])*(red[2]-green[2]);
        assertTrue(dist > 0.01f, "red and green should be far apart in Oklab");
    }

    @Test
    void rgbToOklab_midGray_abNearZero() {
        // Neutral grays have chroma ≈ 0 in any perceptual color space
        float[] lab = PngToMapColors.rgbToOklab(0x808080);
        assertEquals(0f, lab[1], 0.01f, "mid-gray a should be near zero");
        assertEquals(0f, lab[2], 0.01f, "mid-gray b should be near zero");
    }
}
