package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the mod's COMPOSITE lossy decode — one AV1 stream covering a whole multi-tile
 * composition ({@link PayloadManifest#FLAG_AV1_COMPOSITE}) — reproduces the web editor's decode
 * byte-for-byte at composition dimensions (here 2×1 tiles → 256×128).
 *
 * <p>Like {@link Av1LossyRoundtripTest}, the fixture's "expected" frames are what the WEB decoder
 * produced from the same stream (regenerate with {@code node web/scripts/gen-av1-fixtures.mjs}),
 * so a passing test proves web preview == in-game result for seamless composite lossy art.
 */
class Av1CompositeRoundtripTest {

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = Av1CompositeRoundtripTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return in.readAllBytes();
        }
    }

    @Test
    void modMatchesWebDecodeAtCompositionDims() throws Exception {
        final int W = 256, H = 128, PLANE = W * H;
        byte[] stream   = resource("/av1/av1_composite_stream.bin");
        byte[] expected = resource("/av1/frames_composite_expected.bin");
        int n = expected.length / PLANE;
        assertTrue(n > 1, "fixture must be animated");

        byte[][] frames = Av1FrameDecoder.decode(stream, 0, n, true, W, H);

        assertEquals(n, frames.length, "frame count");
        int mismatches = 0;
        for (int f = 0; f < n; f++) {
            assertEquals(PLANE, frames[f].length, "frame " + f + " plane size");
            for (int p = 0; p < PLANE; p++)
                if (frames[f][p] != expected[f * PLANE + p]) mismatches++;
        }
        assertEquals(0, mismatches, "mod composite decode must match the web decode byte-for-byte");
    }
}
