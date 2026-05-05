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
    void roundtrip_v3_globalDelay() {
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                2, 1, 0, 0, 0xABCDL,
                "Artist", "Anim Title", 0,
                4, 3, new int[]{200});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertEquals(3, m.manifestVersion);
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
    void roundtrip_v3_perFrameDelays() {
        int[] delays = {100, 200, 150, 300};
        byte[] bytes = PayloadManifest.toBytes(
                PayloadManifest.FLAG_ANIMATED,
                1, 1, 0, 0, 0L,
                null, null, 0,
                4, 0, delays);

        PayloadManifest m = PayloadManifest.fromBytes(bytes);

        assertEquals(3, m.manifestVersion);
        assertEquals(bytes.length, m.headerSize);
        assertTrue(m.animated());
        assertEquals(4, m.frameCount);
        assertEquals(0, m.loopCount);
        assertArrayEquals(delays, m.frameDelays);
    }

    @Test
    void v3_headerSize_isSkipPointer() {
        // Verify that header_size correctly skips over all animation fields.
        // Append a known sentinel after the manifest and confirm the skip lands on it.
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
    void v3_tooManyFrames_throws() {
        // 78 per-frame delays × 2 bytes = 156 bytes of delays alone;
        // combined with max strings this should overflow the u8 header_size.
        // In practice the frame count that trips the limit depends on string lengths.
        // With no strings: 21 + 78*2 = 177 bytes — still under 255.
        // With max strings (16 + 64 = 80 bytes): 21 + 80 + 78*2 = 257 bytes — over.
        String maxUsername = "A".repeat(16);
        String maxTitle    = "B".repeat(64);
        int[] manyDelays   = new int[78]; // 78 per-frame delays
        java.util.Arrays.fill(manyDelays, 100);

        assertThrows(IllegalArgumentException.class, () ->
                PayloadManifest.toBytes(
                        PayloadManifest.FLAG_ANIMATED,
                        1, 1, 0, 0, 0L,
                        maxUsername, maxTitle, 0,
                        78, 0, manyDelays));
    }

    @Test
    void v3_staticTile_flagNotSet() {
        // A v3 manifest with frameCount=1 and no FLAG_ANIMATED is a valid static tile.
        byte[] bytes = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, 0xDEADL,
                null, null, 0x11223344,
                1, 0, new int[]{100});

        PayloadManifest m = PayloadManifest.fromBytes(bytes);
        assertEquals(3, m.manifestVersion);
        assertFalse(m.animated());
        assertEquals(1, m.frameCount);
    }
}
