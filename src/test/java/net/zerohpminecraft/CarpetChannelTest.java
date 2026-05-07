package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CarpetChannelTest {

    // ── Sequence table sanity ─────────────────────────────────────────────

    @Test
    void seq4_allSequencesBalancedAndBounded() {
        for (int i = 0; i < CarpetChannel.SEQ4.length; i++) {
            int[] s = CarpetChannel.SEQ4[i];
            assertEquals(4, s.length, "SEQ4[" + i + "] length");
            int h = 1, sum = 0;
            for (int shade : s) {
                h += shade - 1;
                sum += shade - 1;
                assertTrue(h >= 0 && h <= 2, "SEQ4[" + i + "] height out of {0,1,2}: h=" + h);
            }
            assertEquals(0, sum, "SEQ4[" + i + "] not balanced");
        }
    }

    @Test
    void seq3_allSequencesBalancedAndBounded() {
        for (int i = 0; i < CarpetChannel.SEQ3.length; i++) {
            int[] s = CarpetChannel.SEQ3[i];
            assertEquals(3, s.length, "SEQ3[" + i + "] length");
            int h = 1, sum = 0;
            for (int shade : s) {
                h += shade - 1;
                sum += shade - 1;
                assertTrue(h >= 0 && h <= 2, "SEQ3[" + i + "] height out of {0,1,2}: h=" + h);
            }
            assertEquals(0, sum, "SEQ3[" + i + "] not balanced");
        }
    }

    @Test
    void dec4_roundtrips_allValues() {
        for (int v = 0; v < 16; v++) {
            int[] s = CarpetChannel.SEQ4[v];
            int key = s[0]*27 + s[1]*9 + s[2]*3 + s[3];
            assertEquals(v, CarpetChannel.DEC4[key], "DEC4 roundtrip for value " + v);
        }
    }

    @Test
    void dec3_roundtrips_allValues() {
        for (int v = 0; v < 4; v++) {
            int[] s = CarpetChannel.SEQ3[v];
            int key = s[0]*9 + s[1]*3 + s[2];
            assertEquals(v, CarpetChannel.DEC3[key], "DEC3 roundtrip for value " + v);
        }
    }

    // ── computeHeights ────────────────────────────────────────────────────

    @Test
    void computeHeights_emptyShadeData_allAtBaseline1() {
        int[][] h = CarpetChannel.computeHeights(new byte[0], 0);
        for (int col = 0; col < 128; col++)
            for (int row = 0; row < 128; row++)
                assertEquals(1, h[col][row], "col=" + col + " row=" + row);
    }

    @Test
    void computeHeights_allHeightsInRange() {
        byte[] shadeData = new byte[CarpetChannel.MAX_SHADE_BYTES];
        for (int i = 0; i < shadeData.length; i++) shadeData[i] = (byte)(i * 37 ^ 0xA5);

        int[][] h = CarpetChannel.computeHeights(shadeData, CarpetChannel.MAX_SHADE_BYTES);

        for (int col = 0; col < 128; col++)
            for (int row = 0; row < 128; row++)
                assertTrue(h[col][row] >= 0 && h[col][row] <= 2,
                        "height out of {0,1,2} at col=" + col + " row=" + row);
    }

    @Test
    void computeHeights_row0AlwaysBaseline1() {
        byte[] shadeData = new byte[CarpetChannel.MAX_SHADE_BYTES];
        Arrays.fill(shadeData, (byte) 0xFF);

        int[][] h = CarpetChannel.computeHeights(shadeData, CarpetChannel.MAX_SHADE_BYTES);

        for (int col = 0; col < 128; col++)
            assertEquals(1, h[col][0], "col=" + col + " row=0 must be baseline 1");
    }

    @Test
    void computeHeights_groupsReturnToBaseline() {
        // After each 4-row group, the height should return to 1.
        byte[] shadeData = new byte[CarpetChannel.MAX_SHADE_BYTES];
        for (int i = 0; i < shadeData.length; i++) shadeData[i] = (byte)(i * 73);

        int[][] h = CarpetChannel.computeHeights(shadeData, CarpetChannel.MAX_SHADE_BYTES);

        // After each 4-row group boundary (row 4, 8, 12, ..., 124) height must be 1.
        for (int g = 0; g < 31; g++) {
            int endRow = g * 4 + 4;
            for (int col = 0; col < 128; col++)
                assertEquals(1, h[col][endRow],
                        "height not back to 1 after group " + g + " col=" + col);
        }
        // After tail group (row 127) also returns to 1.
        for (int col = 0; col < 128; col++)
            assertEquals(1, h[col][127], "height not 1 at row 127 col=" + col);
    }

    @Test
    void computeHeights_knownSequence_firstGroupCol0() {
        // Encode value 5 (SEQ4[5] = {1,0,1,2}) into group 0, col 0.
        // bits 0-3 = 0101 = 5.  Byte 0, MSB-first: bit0=0, bit1=1, bit2=0, bit3=1 → 0b0101xxxx.
        byte[] shadeData = new byte[CarpetChannel.MAX_SHADE_BYTES];
        shadeData[0] = (byte) 0x50; // 0101 0000 → bits 0-3 = 0101 = 5

        int[][] h = CarpetChannel.computeHeights(shadeData, CarpetChannel.MAX_SHADE_BYTES);

        // SEQ4[5] = {1,0,1,2}: from h=1 → 1+0=1, 1-1=0, 0+0=0, 0+1=1
        assertEquals(1, h[0][0], "row 0");
        assertEquals(1, h[0][1], "row 1 (shade 1 → +0)");
        assertEquals(0, h[0][2], "row 2 (shade 0 → -1)");
        assertEquals(0, h[0][3], "row 3 (shade 1 → +0)");
        assertEquals(1, h[0][4], "row 4 (shade 2 → +1, back to baseline)");
    }

    // ── decodeShade roundtrip ─────────────────────────────────────────────

    private static byte[] buildMapColors(int[][] heights) {
        byte[] mapColors = new byte[128 * 128];
        for (int row = 1; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int delta = heights[col][row] - heights[col][row - 1];
                int shade = delta > 0 ? 2 : (delta < 0 ? 0 : 1);
                mapColors[row * 128 + col] = (byte)(4 + shade); // color_id=1 → base=4
            }
        }
        return mapColors;
    }

    @Test
    void roundtrip_fullShadeBytes() {
        byte[] original = new byte[CarpetChannel.MAX_SHADE_BYTES];
        for (int i = 0; i < original.length; i++) original[i] = (byte)(i * 59 ^ 0x3C);

        int[][] heights = CarpetChannel.computeHeights(original, original.length);
        byte[] mapColors = buildMapColors(heights);
        byte[] decoded = CarpetChannel.decodeShade(mapColors, original.length);

        assertArrayEquals(original, decoded, "full shade roundtrip");
    }

    @Test
    void roundtrip_partialShadeBytes() {
        // 200 bytes = 1600 bits; fits 3 full groups (192 bytes) + partial 4th group start.
        // decodeShade stops at 200 bytes, so partial group is just not decoded.
        int len = 200;
        byte[] original = new byte[len];
        for (int i = 0; i < len; i++) original[i] = (byte)(i * 97 ^ 0x7F);

        int[][] heights = CarpetChannel.computeHeights(original, len);
        byte[] mapColors = buildMapColors(heights);
        byte[] decoded = CarpetChannel.decodeShade(mapColors, len);

        assertArrayEquals(original, decoded, "partial shade roundtrip (" + len + " bytes)");
    }

    @Test
    void roundtrip_allZeroShadeData() {
        byte[] original = new byte[CarpetChannel.MAX_SHADE_BYTES]; // all zeros → SEQ4[0] / SEQ3[0]

        int[][] heights = CarpetChannel.computeHeights(original, original.length);
        byte[] mapColors = buildMapColors(heights);
        byte[] decoded = CarpetChannel.decodeShade(mapColors, original.length);

        assertArrayEquals(original, decoded);
    }

    @Test
    void roundtrip_allMaxValueShadeData() {
        byte[] original = new byte[CarpetChannel.MAX_SHADE_BYTES];
        Arrays.fill(original, (byte) 0xFF); // max nibble = 15, max 2-bit = 3

        // 4-bit nibbles: 0xFF → pairs of nibbles: high=1111=15, low=1111=15. But we read 4 bits per group.
        // Actually: readBits(0xFF..., 0, 4) = 1111 = 15. readBits at 4 = 1111 = 15. Etc.
        // SEQ4[15] = {2,1,1,0}: from h=1 → 2,2,2,1. SEQ3[3] = {2,0,1}: from h=1 → 2,1,1.

        int[][] heights = CarpetChannel.computeHeights(original, original.length);
        byte[] mapColors = buildMapColors(heights);
        byte[] decoded = CarpetChannel.decodeShade(mapColors, original.length);

        assertArrayEquals(original, decoded);
    }
}
