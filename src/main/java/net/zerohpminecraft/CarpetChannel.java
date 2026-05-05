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
 * Maps the 16 Minecraft carpet colours to nibble values (0–15) and back, giving
 * a 128×128 = 16,384-position primary data channel worth 8,192 bytes.
 *
 * Encoding: pair of positions (2i, 2i+1) stores one byte — high nibble at even
 * position, low nibble at odd position. Each nibble selects one of the 16 carpet
 * colours; the map byte written to MapState.colors for that position on a flat
 * surface (all carpets at the same elevation) is base_id * 4 + 1 (shade NORMAL).
 *
 * Call {@link #init()} once at mod startup after Minecraft's registries are loaded.
 */
public class CarpetChannel {

    /** Number of pixels in a 128×128 map. */
    public static final int MAP_PIXELS = 128 * 128;             // 16384

    /** Max bytes encodable in the carpet channel (two nibbles per pixel). */
    public static final int MAX_CARPET_BYTES = MAP_PIXELS / 2;  // 8192

    /** Max overflow bytes across LC banner (33) + 62 hex-indexed banners (62×36). */
    public static final int MAX_OVERFLOW_BYTES = 33 + 62 * 36;  // 2265

    /** Combined maximum compressed payload per tile. */
    public static final int MAX_TOTAL_BYTES = MAX_CARPET_BYTES + MAX_OVERFLOW_BYTES; // 10457

    /** Number of base64 payload chars in the LC manifest banner after the 4-char length prefix. */
    public static final int LC_PAYLOAD_CHARS = 44; // encodes 33 bytes (44 = 33/3*4, no padding)

    /** DyeColor at ordinal i is nibble i. */
    static final DyeColor[] NIBBLE_TO_COLOR = DyeColor.values();

    /** Nibble (0–15) → map color byte at shade NORMAL (flat surface). */
    static final byte[] NIBBLE_TO_MAP_BYTE = new byte[16];

    /** Map color byte → nibble (0–15), or -1 if this byte is not a carpet byte. */
    static final int[] MAP_BYTE_TO_NIBBLE = new int[256];

    public static void init() {
        Arrays.fill(MAP_BYTE_TO_NIBBLE, -1);
        for (int i = 0; i < 16; i++) {
            DyeColor color = NIBBLE_TO_COLOR[i];
            Block carpet = Registries.BLOCK.get(
                    Identifier.of("minecraft", color.getName() + "_carpet"));
            if (carpet == null) {
                throw new IllegalStateException("CarpetChannel: no carpet block for " + color.getName());
            }
            MapColor mc = carpet.getDefaultState().getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            // Shade NORMAL = id 1 → map byte = base_id * 4 + 1 (flat surface, same height as north row)
            int mapByte = mc.id * 4 + 1;
            NIBBLE_TO_MAP_BYTE[i] = (byte) mapByte;
            if (MAP_BYTE_TO_NIBBLE[mapByte] != -1) {
                throw new IllegalStateException(
                        "CarpetChannel: map byte collision between nibble "
                        + MAP_BYTE_TO_NIBBLE[mapByte] + " and " + i + " (map byte " + mapByte + ")");
            }
            MAP_BYTE_TO_NIBBLE[mapByte] = i;
        }
    }

    /**
     * Encodes {@code len} bytes from {@code compressed} into a 16384-element nibble
     * array. Position 2i = high nibble of byte i; position 2i+1 = low nibble.
     * Only positions 0..{@code len}*2−1 carry data; the rest stay 0 (white carpet).
     */
    public static byte[] encodeNibbles(byte[] compressed, int len) {
        if (len > MAX_CARPET_BYTES) {
            throw new IllegalArgumentException(
                    "Carpet channel overflow: " + len + " > " + MAX_CARPET_BYTES);
        }
        byte[] nibbles = new byte[MAP_PIXELS];
        for (int i = 0; i < len; i++) {
            int b = compressed[i] & 0xFF;
            nibbles[i * 2]     = (byte) (b >> 4);
            nibbles[i * 2 + 1] = (byte) (b & 0xF);
        }
        return nibbles;
    }

    /**
     * Decodes {@code carpetBytes} bytes from {@code mapColors} using nibble pairs.
     * Reads positions 0..{@code carpetBytes}*2−1.
     */
    public static byte[] decodeBytes(byte[] mapColors, int carpetBytes) {
        if (carpetBytes > MAX_CARPET_BYTES) carpetBytes = MAX_CARPET_BYTES;
        byte[] out = new byte[carpetBytes];
        for (int i = 0; i < carpetBytes; i++) {
            int hi = MAP_BYTE_TO_NIBBLE[mapColors[i * 2]     & 0xFF];
            int lo = MAP_BYTE_TO_NIBBLE[mapColors[i * 2 + 1] & 0xFF];
            if (hi < 0 || lo < 0) {
                throw new IllegalStateException(
                        "Non-carpet map color at nibble position " + (i * 2)
                        + ": bytes " + (mapColors[i * 2] & 0xFF)
                        + ", " + (mapColors[i * 2 + 1] & 0xFF));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
