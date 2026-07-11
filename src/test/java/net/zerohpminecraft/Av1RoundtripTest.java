package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end AV1 decode test: proves the mod-side {@link Av1FrameDecoder} (Chicory-hosted
 * av1-decode.wasm + INV_PERM) reconstructs frames byte-identically from a committed fixture.
 *
 * <p>The fixture was produced ONCE by the web-side encoder (av1-encode.wasm, monochrome lossless)
 * from map-colour frames with the OKLab permutation applied — so a passing test proves the web
 * encoder and the Java decoder agree bit-for-bit, and that the wasm loads and runs in the JVM
 * (exercising the wasm exception-handling opcodes libaom's setjmp lowering emits).
 *
 * <p>Regenerate the fixture if the codec or permutation changes (see native/av1/README.md).
 */
class Av1RoundtripTest {

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = Av1RoundtripTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return in.readAllBytes();
        }
    }

    @Test
    void decodesFixtureLosslessly() throws Exception {
        final int MAP = 128 * 128;
        byte[] stream   = resource("/av1/av1_stream.bin");        // AV1 bytes as they'd sit after the manifest
        byte[] expected = resource("/av1/frames_expected.bin");   // original map-colour frames
        int n = expected.length / MAP;

        // Av1FrameDecoder reads the stream starting at `offset` — pass the bare stream with offset 0.
        byte[][] frames = Av1FrameDecoder.decode(stream, 0, n, false);

        assertEquals(n, frames.length, "frame count");
        int mismatches = 0;
        for (int f = 0; f < n; f++) {
            assertEquals(MAP, frames[f].length, "frame " + f + " size");
            for (int p = 0; p < MAP; p++)
                if (frames[f][p] != expected[f * MAP + p]) mismatches++;
        }
        assertEquals(0, mismatches, "AV1 decode (via Chicory + INV_PERM) must be byte-identical");
    }
}
