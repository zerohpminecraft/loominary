package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CjkCodecTest {

    // ── Codec constants ───────────────────────────────────────────────────

    @Test
    void bytesPerBanner_isExact() {
        // 46 chars × 14 bits = 644 bits = 80.5 bytes — not an exact integer.
        // BYTES_PER_BANNER is the integer floor (80); wire format uses a 4-byte
        // length header to handle any trailing padding precisely.
        assertEquals(46, CjkCodec.PAYLOAD_CHARS);
        assertEquals(80, CjkCodec.BYTES_PER_BANNER);
    }

    // ── encode / decode round-trips ───────────────────────────────────────

    @Test
    void roundtrip_empty() {
        byte[] rt = CjkCodec.decode(CjkCodec.encode(new byte[0]));
        assertArrayEquals(new byte[0], rt);
    }

    @Test
    void roundtrip_oneByte() {
        byte[] src = {(byte) 0xAB};
        assertArrayEquals(src, CjkCodec.decode(CjkCodec.encode(src)));
    }

    @Test
    void roundtrip_exactlyOneFullBanner() {
        // BYTES_PER_BANNER bytes − 4 header bytes = data that fits snugly in one banner
        byte[] src = new byte[CjkCodec.BYTES_PER_BANNER - 4];
        for (int i = 0; i < src.length; i++) src[i] = (byte)(i * 37 ^ 0x5A);
        assertArrayEquals(src, CjkCodec.decode(CjkCodec.encode(src)));
    }

    @Test
    void roundtrip_multipleFullBanners() {
        // Enough data to span several banners
        byte[] src = new byte[CjkCodec.BYTES_PER_BANNER * 5];
        new Random(0xDEADBEEFL).nextBytes(src);
        assertArrayEquals(src, CjkCodec.decode(CjkCodec.encode(src)));
    }

    @Test
    void roundtrip_oddSize() {
        byte[] src = new byte[100];
        new Random(42).nextBytes(src);
        assertArrayEquals(src, CjkCodec.decode(CjkCodec.encode(src)));
    }

    @Test
    void roundtrip_allBytes() {
        // 256-byte payload exercises every possible byte value
        byte[] src = new byte[256];
        for (int i = 0; i < 256; i++) src[i] = (byte) i;
        assertArrayEquals(src, CjkCodec.decode(CjkCodec.encode(src)));
    }

    // ── encode output validation ──────────────────────────────────────────

    @Test
    void encode_allCharsInAlphabet() {
        byte[] src = new byte[200];
        new Random(1).nextBytes(src);
        String encoded = CjkCodec.encode(src);
        for (int i = 0; i < encoded.length(); i++) {
            int v = encoded.charAt(i) - CjkCodec.ALPHA_BASE;
            assertTrue(v >= 0 && v < CjkCodec.ALPHA_SIZE,
                    "char at " + i + " out of alphabet: U+" + Integer.toHexString(encoded.charAt(i)));
        }
    }

    @Test
    void encode_lengthIsCorrect() {
        byte[] src = new byte[76]; // 4-byte header + 76 = 80 bytes → 1 banner (46 CJK chars)
        int expectedChars = (int) Math.ceil((4.0 + src.length) * 8.0 / CjkCodec.ALPHA_BITS);
        assertEquals(expectedChars, CjkCodec.encode(src).length());
    }

    // ── buildChunks / assembleChunks round-trip ───────────────────────────

    @Test
    void chunkRoundtrip_small() {
        byte[] src = {1, 2, 3, 4, 5};
        List<String> chunks = CjkCodec.buildChunks(src);
        assertArrayEquals(src, CjkCodec.assembleChunks(new java.util.ArrayList<>(chunks)));
    }

    @Test
    void chunkRoundtrip_largeRandom() {
        byte[] src = new byte[CjkCodec.BYTES_PER_BANNER * 10];
        new Random(7).nextBytes(src);
        List<String> chunks = CjkCodec.buildChunks(src);
        // Each chunk starts with a 4-char hex index followed by CJK payload
        for (int i = 0; i < chunks.size(); i++) {
            String s = chunks.get(i);
            assertEquals(String.format("%04x", i), s.substring(0, 4), "chunk index prefix");
            assertTrue(s.length() > 4, "chunk has payload");
            assertTrue(s.charAt(4) >= CjkCodec.ALPHA_BASE, "payload starts with CJK char");
        }
        assertArrayEquals(src, CjkCodec.assembleChunks(new java.util.ArrayList<>(chunks)));
    }

    @Test
    void chunksNeeded_matchesBuildChunksSize() {
        Random rng = new Random(99);
        for (int size : new int[]{0, 1, 83, 84, 85, 500, 1000, 5000}) {
            byte[] src = new byte[size];
            rng.nextBytes(src);
            int expected = CjkCodec.buildChunks(src).size();
            assertEquals(expected, CjkCodec.chunksNeeded(src),
                    "chunksNeeded mismatch for size=" + size);
        }
    }

    @Test
    void chunksNeeded_fewerThanBase64() {
        // For any reasonable payload, CJK needs fewer banners than base64
        byte[] src = new byte[1000];
        new Random(0).nextBytes(src);
        int cjkBanners  = CjkCodec.chunksNeeded(src); // ~80.5 bytes/banner
        int b64Banners  = (int) Math.ceil(src.length * 4.0 / 3.0 / 48); // ~36 bytes/banner
        assertTrue(cjkBanners < b64Banners,
                "CJK should need fewer banners: cjk=" + cjkBanners + " b64=" + b64Banners);
    }
}
