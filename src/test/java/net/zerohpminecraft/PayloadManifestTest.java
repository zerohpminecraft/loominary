package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PayloadManifestTest {

    @Test
    void roundtrip_allFields() {
        long crc = 0xDEADBEEFL;
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ALL_SHADES,
                3, 2, 1, 0, crc,
                "TestPlayer", "My Title");

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertEquals(1, m.manifestVersion);
        assertEquals(bytes.length, m.headerSize);
        assertEquals(PayloadManifest.FLAG_ALL_SHADES, m.flags);
        assertEquals(3, m.cols);
        assertEquals(2, m.rows);
        assertEquals(1, m.tileCol);
        assertEquals(0, m.tileRow);
        assertEquals(crc, m.colorCrc32);
        assertEquals("TestPlayer", m.username);
        assertEquals("My Title", m.title);
        assertTrue(m.allShades());
    }

    @Test
    void roundtrip_nullFields() {
        byte[] bytes = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, null, null);
        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertNull(m.username);
        assertNull(m.title);
        assertFalse(m.allShades());
        assertEquals(bytes.length, m.headerSize);
    }

    @Test
    void headerSize_equalsArrayLength() {
        byte[] bytes = PayloadManifest.toBytes(0, 4, 3, 2, 1, 0xCAFEBABEL, "Alice", "Some Art");
        assertEquals(bytes.length, bytes[1] & 0xFF,
                "header_size field must equal the byte array length");
    }

    @Test
    void colorCrc32_roundtrip() {
        byte[] data = new byte[128 * 128];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        long crc = PayloadManifest.crc32(data);
        assertTrue(crc > 0);

        byte[] bytes = PayloadManifest.toBytes(0, 1, 1, 0, 0, crc, null, null);
        PayloadManifest m = PayloadManifest.fromBytes(bytes);
        assertEquals(crc, m.colorCrc32);
    }

    @Test
    void username_truncatedAt16Bytes() {
        String long16 = "ABCDEFGHIJKLMNOP"; // exactly 16 ASCII bytes
        String long17 = "ABCDEFGHIJKLMNOPQ"; // 17 bytes

        byte[] exact   = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, long16, null);
        byte[] trunced = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, long17, null);

        PayloadManifest me = PayloadManifest.fromBytes(exact);
        PayloadManifest mt = PayloadManifest.fromBytes(trunced);

        assertEquals(long16, me.username);
        assertEquals(long16, mt.username, "17-char username should be truncated to 16");
    }

    @Test
    void title_truncatedAt64Bytes() {
        String exact64 = "A".repeat(64);
        String over64  = "A".repeat(65);

        PayloadManifest me = PayloadManifest.fromBytes(
                PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, null, exact64));
        PayloadManifest mo = PayloadManifest.fromBytes(
                PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, null, over64));

        assertEquals(exact64, me.title);
        assertEquals(exact64, mo.title, "65-char title should be truncated to 64");
    }

    @Test
    void unknownVersion_returnsStubWithHeaderSize() {
        byte[] fake = new byte[20];
        fake[0] = (byte) (PayloadManifest.CURRENT_VERSION + 1);
        fake[1] = (byte) 20; // header_size

        PayloadManifest stub = PayloadManifest.fromBytes(fake);

        assertEquals(PayloadManifest.CURRENT_VERSION + 1, stub.manifestVersion);
        assertEquals(20, stub.headerSize);
        assertEquals(0, stub.flags);
        assertEquals(-1L, stub.colorCrc32);
        assertNull(stub.username);
        assertNull(stub.title);
    }

    @Test
    void roundtrip_v2_withNonce() {
        int nonce = 0x12345678;
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ALL_SHADES,
                3, 2, 1, 0, 0xDEADBEEFL,
                "Player", "Title", nonce);

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertEquals(2, m.manifestVersion);
        assertEquals(bytes.length, m.headerSize);
        assertEquals(nonce, m.nonce);
        assertEquals("Player", m.username);
        assertEquals("Title", m.title);
        assertTrue(m.allShades());
    }

    @Test
    void toBytes_zeroNonce_producesV1() {
        byte[] v1       = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, "Player", "Title");
        byte[] nonce0   = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, "Player", "Title", 0);
        assertArrayEquals(v1, nonce0, "nonce=0 should delegate to v1 encoding");
    }

    @Test
    void tooShort_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> PayloadManifest.fromBytes(new byte[]{(byte) 1}));
    }

    @Test
    void v1TooShort_throws() {
        // version=1 but only 5 bytes — too short for a full v1 header
        byte[] truncated = {1, 5, 0, 1, 1};
        assertThrows(IllegalArgumentException.class,
                () -> PayloadManifest.fromBytes(truncated));
    }

    @Test
    void malformedUsername_throws() {
        // username_len says 20 but payload only has 3 bytes after it
        byte[] bytes = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, null, null);
        // Patch username_len to 20 (way past end)
        bytes[11] = 20;
        assertThrows(IllegalArgumentException.class,
                () -> PayloadManifest.fromBytes(bytes));
    }

    // ── v3 animated manifest ──────────────────────────────────────────────

    @Test
    void roundtrip_v4_globalDelay() {
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                2, 1, 0, 0, 0xABCDL,
                "Artist", "Anim Title", 0,
                4, 3, new int[]{200});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertEquals(4, m.manifestVersion);
        assertEquals(bytes.length, m.headerSize);
        assertTrue(m.animated());
        assertEquals(4, m.frameCount);
        assertEquals(3, m.loopCount);
        assertEquals(1, m.frameDelays.length);
        assertEquals(200, m.frameDelays[0]);
        assertEquals("Artist", m.username);
        assertEquals("Anim Title", m.title);
    }

    @Test
    void roundtrip_av1Flag() {
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1,
                2, 1, 0, 0, 0xABCDL,
                "Artist", "Anim Title", 0,
                4, 0, new int[]{100});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertTrue(m.animated());
        assertTrue(m.av1(), "FLAG_AV1 must survive manifest roundtrip");
        assertFalse(m.deltaFrames());
        assertFalse(m.sparseFrames());
        assertEquals(4, m.frameCount);
    }

    @Test
    void av1V5_perFrameDelaysReadFromPrefix() {
        // Many per-frame delays overflow the 255-byte header → v5.  For AV1 the delay table is a
        // PREFIX after the header (not trailing after frames), so fromBytes must read it there.
        int n = 130;
        int[] delays = new int[n];
        for (int f = 0; f < n; f++) delays[f] = 40 + (f % 13) * 5;

        byte[] header = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1,
                1, 1, 0, 0, 0L, "a", "t", 0, n, 0, delays);
        assertEquals(5, header[0] & 0xFF, "should be v5 for 130 per-frame delays");

        // Combined = header ++ prefix delay table ++ (stand-in AV1 stream bytes).
        byte[] prefix = new byte[n * 2];
        for (int f = 0; f < n; f++) { prefix[f * 2] = (byte) (delays[f] >> 8); prefix[f * 2 + 1] = (byte) delays[f]; }
        byte[] combined = new byte[header.length + prefix.length + 32];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(prefix, 0, combined, header.length, prefix.length);

        PayloadManifest m = PayloadManifest.fromBytes(combined);
        assertEquals(5, m.manifestVersion);
        assertTrue(m.av1());
        assertEquals(n, m.frameCount);
        assertArrayEquals(delays, m.frameDelays, "v5 AV1 per-frame delays must parse from the prefix");
    }

    @Test
    void av1Flag_defaultsFalse() {
        PayloadManifest m = PayloadManifest.fromBytes(
                PayloadManifest.toBytes(PayloadManifest.FLAG_ANIMATED,
                        1, 1, 0, 0, 0L, null, null, 0, 2, 0, new int[]{100}));
        assertFalse(m.av1());
    }

    @Test
    void roundtrip_av1CompositeFlag() {
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1
                        | PayloadManifest.FLAG_AV1_LOSSY | PayloadManifest.FLAG_AV1_COMPOSITE,
                3, 2, 2, 1, 0xABCDL,
                "Artist", "Seamless", 0,
                8, 0, new int[]{100});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertTrue(m.animated());
        assertTrue(m.av1());
        assertTrue(m.av1Lossy());
        assertTrue(m.av1Composite(), "FLAG_AV1_COMPOSITE must survive manifest roundtrip");
        assertEquals(3, m.cols);
        assertEquals(2, m.rows);
        assertEquals(2, m.tileCol);
        assertEquals(1, m.tileRow);
        assertEquals(8, m.frameCount);
    }

    @Test
    void av1CompositeFlag_defaultsFalse() {
        PayloadManifest m = PayloadManifest.fromBytes(
                PayloadManifest.toBytes(
                        PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1 | PayloadManifest.FLAG_AV1_LOSSY,
                        1, 1, 0, 0, 0L, null, null, 0, 2, 0, new int[]{100}));
        assertFalse(m.av1Composite());
    }

    @Test
    void roundtrip_v4_perFrameDelays() {
        int[] delays = {100, 200, 150, 300};
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                1, 1, 0, 0, 0L,
                null, null, 0,
                4, 0, delays);

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertEquals(4, m.manifestVersion);
        assertEquals(bytes.length, m.headerSize);
        assertTrue(m.animated());
        assertEquals(4, m.frameCount);
        assertEquals(0, m.loopCount);
        assertArrayEquals(delays, m.frameDelays);
    }

    @Test
    void roundtrip_v4_largeFrameCount() {
        // v4 supports up to 65535 frames via u16 frame_count field.
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                1, 1, 0, 0, 0L,
                null, null, 0,
                1000, 0, new int[]{50});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);
        assertEquals(4, m.manifestVersion);
        assertEquals(1000, m.frameCount);
        assertEquals(50, m.frameDelays[0]);
    }

    @Test
    void roundtrip_v4_maxFrameCount() {
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                1, 1, 0, 0, 0L,
                null, null, 0,
                65535, 0, new int[]{100});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);
        assertEquals(4, m.manifestVersion);
        assertEquals(65535, m.frameCount);
    }

    @Test
    void v4_headerSize_isSkipPointer() {
        // Verify that header_size correctly skips over all animation fields.
        byte[] manifest = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                1, 1, 0, 0, 0L,
                "User", "Title", 0x99887766,
                5, 0, new int[]{100, 200, 150, 300, 50});
        byte sentinel = (byte) 0xAB;
        byte[] combined = Arrays.copyOf(manifest, manifest.length + 1);
        combined[manifest.length] = sentinel;

        PayloadManifest m = PayloadManifest.fromBytes(combined);
        assertEquals(sentinel, combined[m.headerSize],
                "header_size must point to the first byte after the manifest");
    }

    @Test
    void v5_tooManyFrames_producesTrailingDelays() {
        // Per-frame delays of N frames add N×2 bytes; combined with max strings
        // this overflows the u8 header_size (max 255 bytes).
        // With max strings (16+64=80 extra): 22 + 80 + 77*2 = 256 bytes — over.
        // v5 should be produced: compact header, delay table appended after frames.
        String maxUsername = "A".repeat(16);
        String maxTitle    = "B".repeat(64);
        // Use variable delays so normalization doesn't collapse them to a global value.
        int[] manyDelays = new int[77];
        for (int f = 0; f < 77; f++) manyDelays[f] = 80 + (f % 3) * 20; // 80, 100, 120, 80, ...

        byte[] manifest = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                1, 1, 0, 0, 0L,
                maxUsername, maxTitle, 0,
                77, 0, manyDelays);

        assertEquals(5, manifest[0] & 0xFF, "should produce v5 manifest");
        assertTrue(manifest[1] <= 255, "header_size must fit in u8");
        assertTrue(manifest.length <= 255, "compact header must fit");

        byte[] trailing = PayloadManifest.trailingDelayBytes(manifest, manyDelays);
        assertEquals(77 * 2, trailing.length, "trailing delay table must be frameCount×2 bytes");

        // Verify round-trip: fromBytes should recover the delays from the trailing table.
        int MAP_BYTES = 128 * 128;
        byte[] fakeFrames = new byte[77 * MAP_BYTES];
        byte[] full = PayloadManifest.withTrailing(manifest, manyDelays, fakeFrames);
        // Prepend manifest to form the full decompressed payload:
        byte[] decompressed = new byte[manifest.length + full.length];
        System.arraycopy(manifest, 0, decompressed, 0,               manifest.length);
        System.arraycopy(full,     0, decompressed, manifest.length, full.length);
        // fromBytes expects the manifest at the start of data:
        PayloadManifest parsed = PayloadManifest.fromBytes(decompressed);
        assertEquals(5, parsed.manifestVersion);
        assertEquals(77, parsed.frameCount);
        assertNotNull(parsed.frameDelays);
        assertEquals(77, parsed.frameDelays.length, "should have per-frame delay table");
        assertEquals(80,  parsed.frameDelays[0]);  // (0  % 3) == 0 → 80
        assertEquals(80,  parsed.frameDelays[75]); // (75 % 3) == 0 → 80
        assertEquals(100, parsed.frameDelays[76]); // (76 % 3) == 1 → 100
    }

    @Test
    void v4_invalidFrameCount_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                PayloadManifest.toBytes(PayloadManifest.FLAG_ANIMATED,
                        1, 1, 0, 0, 0L, null, null, 0,
                        0, 0, new int[]{100}));
        assertThrows(IllegalArgumentException.class, () ->
                PayloadManifest.toBytes(PayloadManifest.FLAG_ANIMATED,
                        1, 1, 0, 0, 0L, null, null, 0,
                        65536, 0, new int[]{100}));
    }

    @Test
    void v4_staticTile_flagNotSet() {
        // A v4 manifest with frameCount=1 and no FLAG_ANIMATED is a valid static tile.
        byte[] bytes = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, 0xDEADL,
                null, null, 0x11223344,
                1, 0, new int[]{100});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);
        assertEquals(4, m.manifestVersion);
        assertFalse(m.animated());
        assertEquals(1, m.frameCount);
    }

    @Test
    void roundtrip_FLAG_MUX() {
        int flags = PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_MUX;
        byte[] bytes = PayloadManifest.toBytes(
                flags, 3, 3, 1, 1, 0xABCDEFL,
                "Player", "Mux Test", 0,
                5, 0, new int[]{100});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);
        assertEquals(4, m.manifestVersion);
        assertTrue(m.animated());
        assertTrue(m.muxed());
        assertEquals(flags, m.flags);
        assertEquals(3, m.cols);
        assertEquals(3, m.rows);
        assertEquals(1, m.tileCol);
        assertEquals(1, m.tileRow);
        assertEquals(5, m.frameCount);
        assertEquals("Player", m.username);
        assertEquals("Mux Test", m.title);
    }

    // ── v7 (flags2 / sRGB) ───────────────────────────────────────────────

    @Test
    void roundtrip_v7_srgbAnimated() {
        int flags = PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1
                | PayloadManifest.FLAG_AV1_LOSSY;
        int[] delays = {40, 60, 80, 100};
        byte[] header = PayloadManifest.toBytesV7(
                flags, PayloadManifest.FLAG2_SRGB, 2, 2, 1, 0,
                0xABCDL, "Artist", "Full Colour", 0x11223344, 4, 2);
        assertEquals(7, header[0] & 0xFF);
        assertEquals(header.length, header[1] & 0xFF, "headerSize must equal header length");

        // Combined = header ++ prefix delay table ++ (stand-in AV1 stream bytes).
        byte[] prefix = PayloadManifest.delayPrefixBytes(4, delays);
        byte[] combined = new byte[header.length + prefix.length + 32];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(prefix, 0, combined, header.length, prefix.length);

        PayloadManifest m = PayloadManifest.fromBytes(combined);
        assertEquals(7, m.manifestVersion);
        assertTrue(m.srgb(), "FLAG2_SRGB must survive roundtrip");
        assertTrue(m.animated());
        assertTrue(m.av1());
        assertTrue(m.av1Lossy());
        assertEquals(4, m.frameCount);
        assertEquals(2, m.loopCount);
        assertEquals(0x11223344, m.nonce);
        assertEquals("Artist", m.username);
        assertEquals("Full Colour", m.title);
        assertArrayEquals(delays, m.frameDelays, "v7 per-frame delays must parse from the prefix");
        // The v5+ AV1 stream-offset rule: header + frameCount×u16 prefix.
        assertEquals(m.headerSize + 4 * 2, header.length + prefix.length);
    }

    @Test
    void roundtrip_v7_srgbStatic_frameCount1() {
        // Static sRGB: frameCount=1, FLAG_ANIMATED unset, but the 2-byte delay prefix is still
        // written so the stream offset rule stays uniform.
        int flags = PayloadManifest.FLAG_AV1 | PayloadManifest.FLAG_AV1_LOSSY;
        byte[] header = PayloadManifest.toBytesV7(
                flags, PayloadManifest.FLAG2_SRGB, 1, 1, 0, 0,
                0L, null, null, 0, 1, 0);
        byte[] prefix = PayloadManifest.delayPrefixBytes(1, new int[]{100});
        assertEquals(2, prefix.length);
        byte[] combined = new byte[header.length + prefix.length + 16];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(prefix, 0, combined, header.length, prefix.length);

        PayloadManifest m = PayloadManifest.fromBytes(combined);
        assertEquals(7, m.manifestVersion);
        assertTrue(m.srgb());
        assertFalse(m.animated());
        assertEquals(1, m.frameCount);
    }

    @Test
    void v7_headerSize_staysU8_withMaxStrings() {
        byte[] header = PayloadManifest.toBytesV7(
                PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1 | PayloadManifest.FLAG_AV1_LOSSY,
                PayloadManifest.FLAG2_SRGB, 8, 8, 7, 7,
                0xFFFFFFFFL, "A".repeat(16), "B".repeat(64), -1, 65535, 65535);
        assertTrue(header.length <= 255, "v7 header must fit the u8 header_size");
        assertEquals(header.length, header[1] & 0xFF);
    }

    @Test
    void flags2_defaultsZero_forOlderVersions() {
        PayloadManifest v4 = PayloadManifest.fromBytes(PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED, 1, 1, 0, 0, 0L, null, null, 0, 2, 0, new int[]{100}));
        assertEquals(0, v4.flags2);
        assertFalse(v4.srgb());

        PayloadManifest v2 = PayloadManifest.fromBytes(PayloadManifest.toBytes(
                0, 1, 1, 0, 0, 0L, "u", "t", 7));
        assertEquals(0, v2.flags2);
        assertFalse(v2.srgb());
    }

    @Test
    void v7_matchesWebCanonicalBytes() {
        // Byte-parity anchor shared with web/test/manifest-v7.test.mjs — both writers must
        // produce these exact bytes for the same inputs.
        String canonicalHex =
                "072962020201000000abcd064172746973740b46756c6c20436f6c6f75721122334400040002010001";
        byte[] header = PayloadManifest.toBytesV7(
                PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1 | PayloadManifest.FLAG_AV1_LOSSY,
                PayloadManifest.FLAG2_SRGB, 2, 2, 1, 0,
                0xABCDL, "Artist", "Full Colour", 0x11223344, 4, 2);
        StringBuilder hex = new StringBuilder();
        for (byte b : header) hex.append(String.format("%02x", b));
        assertEquals(canonicalHex, hex.toString(), "v7 writer must match the web writer byte-for-byte");
    }

    @Test
    void v7_trailingDelayBytes_isEmpty() {
        // v7 uses the PREFIX table; the v5 trailing-table helper must not fire on it.
        byte[] header = PayloadManifest.toBytesV7(
                PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_AV1 | PayloadManifest.FLAG_AV1_LOSSY,
                PayloadManifest.FLAG2_SRGB, 1, 1, 0, 0, 0L, null, null, 0, 3, 0);
        assertEquals(0, PayloadManifest.trailingDelayBytes(header, new int[]{40, 60, 80}).length);
    }
}
