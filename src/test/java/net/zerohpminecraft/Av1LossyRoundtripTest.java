package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the mod's LOSSY-colour AV1 decode (Chicory-hosted wasm nearest-palette + committed
 * {@link MapPalette}) reproduces the web editor's decode byte-for-byte.
 *
 * <p>The fixture's "expected" frames are what the WEB decoder produced from the same stream, so
 * a passing test proves web preview == in-game result for lossy art (the whole point of the
 * truthful export preview).  Lossy art is not byte-identical to the ORIGINAL, but web and mod
 * must agree with each other, which requires the same wasm + the same palette RGB on both sides.
 */
class Av1LossyRoundtripTest {

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = Av1LossyRoundtripTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return in.readAllBytes();
        }
    }

    @Test
    void modMatchesWebDecode() throws Exception {
        final int MAP = 128 * 128;
        byte[] stream   = resource("/av1/av1_lossy_stream.bin");
        byte[] expected = resource("/av1/frames_lossy_expected.bin");
        int n = expected.length / MAP;

        byte[][] frames = Av1FrameDecoder.decode(stream, 0, n, true); // lossy = true

        assertEquals(n, frames.length, "frame count");
        int mismatches = 0;
        for (int f = 0; f < n; f++)
            for (int p = 0; p < MAP; p++)
                if (frames[f][p] != expected[f * MAP + p]) mismatches++;
        assertEquals(0, mismatches, "mod lossy decode must match the web decode byte-for-byte");
    }
}
