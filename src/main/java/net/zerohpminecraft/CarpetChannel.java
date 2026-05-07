package net.zerohpminecraft;

import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.Arrays;

/**
 * Carpet colour nibble channel + shade channel.
 *
 * <p><b>Carpet channel</b>: 16,384 pixels packed as nibble pairs → 8,192 bytes.
 *
 * <p><b>Shade channel</b>: carpet heights {0,1,2} encode extra data via 4-row
 * balanced groups.  31 groups of 4 rows × 128 cols carry 4 bits each (16 of 17
 * valid balanced sequences); a 3-row tail group carries 2 bits per col.
 * Total: 31×64 + 32 = 2,016 bytes.  Max carpet height above floor: 2.
 *
 * <p>Tiles that use the shade channel use an {@code LS} LC-banner prefix instead
 * of {@code LC}; old decoders that only know {@code LC} ignore them gracefully.
 *
 * <p>Call {@link #init()} once after Minecraft's registries are loaded.
 */
public class CarpetChannel {

    public static final int MAP_PIXELS        = 128 * 128;            // 16384
    public static final int MAX_CARPET_BYTES  = MAP_PIXELS / 2;       // 8192
    /** Shade channel capacity: 31 four-row groups + 1 three-row tail, 128 cols. */
    public static final int MAX_SHADE_BYTES   = 31 * 128 * 4 / 8 + 128 * 2 / 8; // 2016
    public static final int MAX_OVERFLOW_BYTES = 33 + 62 * 36;        // 2265
    public static final int MAX_TOTAL_BYTES   =
            MAX_CARPET_BYTES + MAX_SHADE_BYTES + MAX_OVERFLOW_BYTES;   // 12473

    /** Base64 chars in the LC banner's overflow payload slot (no shade channel). */
    public static final int LC_PAYLOAD_CHARS = 44; // 33 bytes
    /** Base64 chars in the LS banner's overflow payload slot (shade channel present). */
    public static final int LS_PAYLOAD_CHARS = 40; // 30 bytes; 4 extra chars hold the shade count

    // ── Shade-channel sequence tables ────────────────────────────────────

    /**
     * 16 balanced 4-row sequences (heights in {0,1,2}, start/end at baseline 1).
     * Index = 4-bit data nibble. The 17th valid sequence {1,1,1,1} is intentionally
     * omitted; it is used by {@code computeHeights} as the "no data" fill sequence.
     */
    static final int[][] SEQ4 = {
        {0,1,1,2}, {0,1,2,1}, {0,2,0,2}, {0,2,1,1}, {0,2,2,0},
        {1,0,1,2}, {1,0,2,1}, {1,1,0,2},
        {1,1,2,0}, {1,2,0,1}, {1,2,1,0},
        {2,0,0,2}, {2,0,1,1}, {2,0,2,0}, {2,1,0,1}, {2,1,1,0}
    };

    /**
     * 4 balanced 3-row sequences for the tail group (rows 125–127, heights in {0,1,2}).
     * Index = 2-bit data value.
     */
    static final int[][] SEQ3 = {
        {1,1,1}, {2,1,0}, {0,1,2}, {2,0,1}
    };

    /** Decoder: (s1×27 + s2×9 + s3×3 + s4) → 4-bit nibble, or −1 if not a valid sequence. */
    static final int[] DEC4 = new int[81]; // 3^4

    /** Decoder: (s1×9 + s2×3 + s3) → 2-bit value, or −1 if not a valid sequence. */
    static final int[] DEC3 = new int[27]; // 3^3

    static {
        Arrays.fill(DEC4, -1);
        for (int i = 0; i < SEQ4.length; i++) {
            int[] s = SEQ4[i];
            DEC4[s[0]*27 + s[1]*9 + s[2]*3 + s[3]] = i;
        }
        Arrays.fill(DEC3, -1);
        for (int i = 0; i < SEQ3.length; i++) {
            int[] s = SEQ3[i];
            DEC3[s[0]*9 + s[1]*3 + s[2]] = i;
        }
    }

    // ── Carpet-channel lookup tables ──────────────────────────────────────

    static final DyeColor[] NIBBLE_TO_COLOR  = DyeColor.values();
    static final byte[]     NIBBLE_TO_MAP_BYTE = new byte[16];
    /** Map color byte → nibble (0–15), or −1. Accepts shade 0, 1, or 2. */
    static final int[]      MAP_BYTE_TO_NIBBLE = new int[256];

    public static void init() {
        // Build carpet lookup tables (needs Minecraft registries).
        Arrays.fill(MAP_BYTE_TO_NIBBLE, -1);
        for (int i = 0; i < 16; i++) {
            DyeColor color = NIBBLE_TO_COLOR[i];
            Block carpet = Registries.BLOCK.get(
                    Identifier.of("minecraft", color.getName() + "_carpet"));
            if (carpet == null)
                throw new IllegalStateException("CarpetChannel: no carpet for " + color.getName());
            MapColor mc = carpet.getDefaultState().getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            int mapByte = mc.id * 4 + 1;
            NIBBLE_TO_MAP_BYTE[i] = (byte) mapByte;
            if (MAP_BYTE_TO_NIBBLE[mapByte] != -1)
                throw new IllegalStateException("CarpetChannel: map byte collision at nibble " + i);
            MAP_BYTE_TO_NIBBLE[mapByte] = i;
            MAP_BYTE_TO_NIBBLE[mc.id * 4 + 0] = i; // shade LOW  → same nibble
            MAP_BYTE_TO_NIBBLE[mc.id * 4 + 2] = i; // shade HIGH → same nibble
        }
    }

    // ── Carpet channel ────────────────────────────────────────────────────

    public static byte[] encodeNibbles(byte[] compressed, int len) {
        if (len > MAX_CARPET_BYTES)
            throw new IllegalArgumentException("Carpet channel overflow: " + len);
        byte[] nibbles = new byte[MAP_PIXELS];
        for (int i = 0; i < len; i++) {
            int b = compressed[i] & 0xFF;
            nibbles[i * 2]     = (byte)(b >> 4);
            nibbles[i * 2 + 1] = (byte)(b & 0xF);
        }
        return nibbles;
    }

    /** Accepts shade 0, 1, or 2 — all map to the same nibble. */
    public static byte[] decodeBytes(byte[] mapColors, int carpetBytes) {
        if (carpetBytes > MAX_CARPET_BYTES) carpetBytes = MAX_CARPET_BYTES;
        byte[] out = new byte[carpetBytes];
        for (int i = 0; i < carpetBytes; i++) {
            int hi = MAP_BYTE_TO_NIBBLE[mapColors[i * 2]     & 0xFF];
            int lo = MAP_BYTE_TO_NIBBLE[mapColors[i * 2 + 1] & 0xFF];
            if (hi < 0 || lo < 0)
                throw new IllegalStateException(
                        "Non-carpet map color at nibble position " + (i * 2)
                        + ": bytes " + (mapColors[i * 2] & 0xFF)
                        + ", " + (mapColors[i * 2 + 1] & 0xFF));
            out[i] = (byte)((hi << 4) | lo);
        }
        return out;
    }

    // ── Shade channel ─────────────────────────────────────────────────────

    /**
     * Decodes the shade channel from {@code mapColors}, returning {@code shadeBytes} bytes.
     *
     * <p>Bit layout: group g (rows 4g+1 .. 4g+4), col c → bits at offset (g×128+c)×4.
     * Tail group (rows 125–127), col c → bits at offset 31×128×4 + c×2.
     * Bits are packed MSB-first.  Invalid shade patterns produce 0.
     */
    public static byte[] decodeShade(byte[] mapColors, int shadeBytes) {
        if (shadeBytes > MAX_SHADE_BYTES) shadeBytes = MAX_SHADE_BYTES;
        byte[] out = new byte[shadeBytes];
        int totalBits = shadeBytes * 8;

        for (int g = 0; g < 31; g++) {
            int row0 = g * 4 + 1;
            int groupBase = g * 128 * 4;
            for (int col = 0; col < 128; col++) {
                int bitOff = groupBase + col * 4;
                if (bitOff + 4 > totalBits) return out;
                int s1 = mapColors[row0       * 128 + col] & 0x3;
                int s2 = mapColors[(row0 + 1) * 128 + col] & 0x3;
                int s3 = mapColors[(row0 + 2) * 128 + col] & 0x3;
                int s4 = mapColors[(row0 + 3) * 128 + col] & 0x3;
                int v = DEC4[s1*27 + s2*9 + s3*3 + s4];
                writeBits(out, bitOff, 4, v < 0 ? 0 : v);
            }
        }

        int tailBase = 31 * 128 * 4;
        for (int col = 0; col < 128; col++) {
            int bitOff = tailBase + col * 2;
            if (bitOff + 2 > totalBits) return out;
            int s1 = mapColors[125 * 128 + col] & 0x3;
            int s2 = mapColors[126 * 128 + col] & 0x3;
            int s3 = mapColors[127 * 128 + col] & 0x3;
            int v = DEC3[s1*9 + s2*3 + s3];
            writeBits(out, bitOff, 2, v < 0 ? 0 : v);
        }

        return out;
    }

    /**
     * Converts shade-channel bytes into a per-pixel height map for the schematic.
     *
     * <p>Row 0 is always at height 1 (baseline). 4-row groups (rows 1–124) and the
     * 3-row tail (rows 125–127) are filled from the encoded sequences; heights stay in
     * {0,1,2}.  Groups beyond the available data use the all-flat sequence, keeping
     * height at 1.
     *
     * @return {@code heights[col][row]}, col/row ∈ [0,127]
     */
    // All-flat null sequences used when a group has no encoded data.
    private static final int[] SEQ4_FLAT = {1, 1, 1, 1};
    private static final int[] SEQ3_FLAT = {1, 1, 1};

    public static int[][] computeHeights(byte[] shadeData, int shadeLen) {
        int[][] heights = new int[128][128];
        int totalBits = Math.min(shadeLen, MAX_SHADE_BYTES) * 8;

        for (int col = 0; col < 128; col++) heights[col][0] = 1;

        for (int g = 0; g < 31; g++) {
            int row0 = g * 4 + 1;
            int groupBase = g * 128 * 4;
            for (int col = 0; col < 128; col++) {
                int bitOff = groupBase + col * 4;
                int[] seq = (bitOff + 4 <= totalBits)
                        ? SEQ4[readBits(shadeData, bitOff, 4)]
                        : SEQ4_FLAT;
                int h = 1;
                for (int r = 0; r < 4; r++) heights[col][row0 + r] = h += seq[r] - 1;
            }
        }

        int tailBase = 31 * 128 * 4;
        for (int col = 0; col < 128; col++) {
            int bitOff = tailBase + col * 2;
            int[] seq = (bitOff + 2 <= totalBits)
                    ? SEQ3[readBits(shadeData, bitOff, 2)]
                    : SEQ3_FLAT;
            int h = 1;
            for (int r = 0; r < 3; r++) heights[col][125 + r] = h += seq[r] - 1;
        }

        return heights;
    }

    // ── Bit I/O ───────────────────────────────────────────────────────────

    static int readBits(byte[] data, int bitOffset, int count) {
        int v = 0;
        for (int i = 0; i < count; i++)
            v = (v << 1) | ((data[(bitOffset + i) / 8] >> (7 - ((bitOffset + i) & 7))) & 1);
        return v;
    }

    static void writeBits(byte[] data, int bitOffset, int count, int value) {
        for (int i = count - 1; i >= 0; i--, bitOffset++)
            if (((value >> i) & 1) != 0)
                data[bitOffset / 8] |= (byte)(1 << (7 - (bitOffset & 7)));
    }
}
