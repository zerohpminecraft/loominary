package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the mod's sRGB decode ({@link Av1FrameDecoder#decodeWithRgb}) reproduces the web
 * editor's {@code dec_tu_full} output byte-for-byte — BOTH views: the nearest-palette map bytes
 * ({@code idx}, what {@code MapState.colors} holds) and the reconstructed true-colour RGB planes
 * ({@code rgb}, what the sRGB display path renders).
 *
 * <p>The fixtures are true-RGB gradients deliberately OFF the map palette (regenerate with
 * {@code node web/scripts/gen-av1-fixtures.mjs}), so any accidental palette quantisation in the
 * RGB path shows up as a mismatch.  A passing test proves web preview == in-game result for
 * full-colour (FLAG2_SRGB) art, single-tile and composite.
 */
class Av1SrgbRoundtripTest {

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = Av1SrgbRoundtripTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return in.readAllBytes();
        }
    }

    private static int mismatches(byte[][] frames, byte[] expected, int plane) {
        int bad = 0;
        for (int f = 0; f < frames.length; f++)
            for (int p = 0; p < plane; p++)
                if (frames[f][p] != expected[f * plane + p]) bad++;
        return bad;
    }

    @Test
    void modMatchesWebDecodeBothViews() throws Exception {
        final int W = 128, H = 128, IDX_PLANE = W * H, RGB_PLANE = W * H * 3;
        byte[] stream      = resource("/av1/av1_srgb_stream.bin");
        byte[] expectedIdx = resource("/av1/frames_srgb_idx_expected.bin");
        byte[] expectedRgb = resource("/av1/frames_srgb_rgb_expected.bin");
        int n = expectedIdx.length / IDX_PLANE;
        assertEquals(n, expectedRgb.length / RGB_PLANE, "idx/rgb fixture frame counts");

        Av1FrameDecoder.Decoded dec = Av1FrameDecoder.decodeWithRgb(stream, 0, n, W, H, null);

        assertEquals(n, dec.idxFrames().length, "idx frame count");
        assertEquals(n, dec.rgbFrames().length, "rgb frame count");
        for (int f = 0; f < n; f++) {
            assertEquals(IDX_PLANE, dec.idxFrames()[f].length, "idx frame " + f + " size");
            assertEquals(RGB_PLANE, dec.rgbFrames()[f].length, "rgb frame " + f + " size");
        }
        assertEquals(0, mismatches(dec.idxFrames(), expectedIdx, IDX_PLANE),
                "quantised twin must match the web decode byte-for-byte");
        assertEquals(0, mismatches(dec.rgbFrames(), expectedRgb, RGB_PLANE),
                "RGB planes must match the web decode byte-for-byte");
    }

    @Test
    void compositeRgbMatchesWebDecode() throws Exception {
        final int W = 256, H = 128, RGB_PLANE = W * H * 3;
        byte[] stream      = resource("/av1/av1_srgb_composite_stream.bin");
        byte[] expectedRgb = resource("/av1/frames_srgb_composite_rgb_expected.bin");
        int n = expectedRgb.length / RGB_PLANE;
        assertTrue(n > 1, "fixture must be animated");

        Av1FrameDecoder.Decoded dec = Av1FrameDecoder.decodeWithRgb(stream, 0, n, W, H, null);

        assertEquals(n, dec.rgbFrames().length, "rgb frame count");
        assertEquals(0, mismatches(dec.rgbFrames(), expectedRgb, RGB_PLANE),
                "composite RGB decode must match the web decode byte-for-byte");
    }
}
