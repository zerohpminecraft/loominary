package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.minecraft.block.MapColor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Base64;

/**
 * Converts a BufferedImage into the 128×128 byte array that MapState.colors uses,
 * and can progressively reduce color count to fit a compressed size budget.
 *
 * Nearest-color matching uses Oklab perceptual distance rather than RGB Euclidean,
 * which gives noticeably better results in both quantization and palette reduction.
 */
public class PngToMapColors {

    public static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;
    private static final int MAX_COLOR_ID = 64;

    /** The unobtainable shade — id 3 (LOWEST brightness) never occurs naturally,
     *  even with staircasing. Skipping it limits us to ~186 reproducible colors. */
    private static final int UNOBTAINABLE_SHADE_ID = 3;

    /** Legacy entry point: includes all 4 shades, even the unobtainable one. */
    public static byte[] convert(BufferedImage source) {
        return convert(source, false);
    }

    /**
     * @param legalOnly if true, restricts output to colors reachable via real
     *                  block placement (~186 colors via staircasing). If false,
     *                  uses all 248 valid map-color bytes including unobtainable
     *                  shade 3 entries.
     */
    public static byte[] convert(BufferedImage source, boolean legalOnly) {
        BufferedImage scaled = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, MAP_SIZE, MAP_SIZE, null);
        g.dispose();

        float[][] oklabLookup = buildOklabLookup();
        byte[] out = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                out[x + y * MAP_SIZE] = findClosestMapColorByte(
                        scaled.getRGB(x, y), legalOnly, oklabLookup);
            }
        }
        return out;
    }

    /**
     * Result of a reduceToFit operation.
     */
    public static class FitResult {
        public final byte[] mapColors;
        public final byte[] compressed;
        public final int colorsRemoved;
        public final int originalDistinctColors;
        public final int pixelsAffected;

        FitResult(byte[] mapColors, byte[] compressed, int colorsRemoved,
                  int originalDistinctColors, int pixelsAffected) {
            this.mapColors = mapColors;
            this.compressed = compressed;
            this.colorsRemoved = colorsRemoved;
            this.originalDistinctColors = originalDistinctColors;
            this.pixelsAffected = pixelsAffected;
        }
    }

    /**
     * Progressively reduces the color palette of a map-color byte array until
     * the compressed+base64'd result fits within maxChunks banner chunks.
     *
     * The optional {@code prefix} (e.g. manifest bytes) is prepended to mapColors
     * before compression so that the size check includes its overhead. Pass an
     * empty array or use the 3-arg form when there is no prefix.
     *
     * Algorithm:
     *   1. Count frequency of each distinct color byte
     *   2. Find the rarest color (fewest pixels)
     *   3. Replace it with its nearest visual neighbor (by Oklab distance)
     *   4. Re-compress and check size
     *   5. Repeat until it fits
     *
     * @param mapColors  the 128×128 map-color byte array (will be modified in place)
     * @param prefix     bytes prepended before compression (manifest); may be empty
     * @param chunkSize  base64 chars per banner chunk
     * @param maxChunks  maximum number of chunks (banners) allowed
     * @return FitResult with the reduced map colors and compression stats
     */
    public static FitResult reduceToFit(byte[] mapColors, byte[] prefix, int chunkSize, int maxChunks) {
        float[][] oklabLookup = buildOklabLookup();

        int originalDistinct = countDistinct(mapColors);
        int colorsRemoved = 0;
        int pixelsAffected = 0;

        byte[] compressed = compressCombined(prefix, mapColors);
        int chunks = chunksNeeded(compressed, chunkSize);

        while (chunks > maxChunks) {
            int[] freq = new int[256];
            for (byte b : mapColors) freq[b & 0xFF]++;

            int rarestColor = -1;
            int rarestFreq = Integer.MAX_VALUE;
            for (int c = 1; c < 256; c++) {
                if (freq[c] > 0 && freq[c] < rarestFreq) {
                    rarestFreq = freq[c];
                    rarestColor = c;
                }
            }

            if (rarestColor == -1) break;

            int bestNeighbor = findNearestNeighbor(rarestColor, freq, oklabLookup);
            if (bestNeighbor == rarestColor) break;

            byte from = (byte) rarestColor;
            byte to   = (byte) bestNeighbor;
            for (int i = 0; i < mapColors.length; i++) {
                if (mapColors[i] == from) mapColors[i] = to;
            }
            colorsRemoved++;
            pixelsAffected += rarestFreq;

            compressed = compressCombined(prefix, mapColors);
            chunks = chunksNeeded(compressed, chunkSize);
        }

        return new FitResult(mapColors, compressed, colorsRemoved, originalDistinct, pixelsAffected);
    }

    /** Delegates to {@link #reduceToFit(byte[], byte[], int, int)} with no prefix. */
    public static FitResult reduceToFit(byte[] mapColors, int chunkSize, int maxChunks) {
        return reduceToFit(mapColors, new byte[0], chunkSize, maxChunks);
    }

    private static byte[] compressCombined(byte[] prefix, byte[] data) {
        if (prefix.length == 0) return Zstd.compress(data, Zstd.maxCompressionLevel());
        byte[] combined = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(data, 0, combined, prefix.length, data.length);
        return Zstd.compress(combined, Zstd.maxCompressionLevel());
    }

    /**
     * Builds an array mapping each possible map-color byte (0–255) to its
     * rendered RGB value. Used by the palette command for display; color matching
     * uses buildOklabLookup() instead.
     */
    public static int[] buildColorLookup() {
        int[] rgb = new int[256];
        for (int b = 0; b < 256; b++) {
            int colorId = (b >> 2) & 0x3F;
            int shadeId = b & 0x3;

            if (colorId == 0) {
                rgb[b] = 0;
                continue;
            }

            MapColor mc = MapColor.get(colorId);
            if (mc == null || mc.color == 0) {
                rgb[b] = 0;
                continue;
            }

            for (MapColor.Brightness br : MapColor.Brightness.values()) {
                if (br.id == shadeId) {
                    rgb[b] = mc.getRenderColor(br);
                    break;
                }
            }
        }
        return rgb;
    }

    /**
     * Builds an Oklab lookup table for all valid map-color bytes.
     * Entries for transparent or invalid bytes are null.
     */
    private static float[][] buildOklabLookup() {
        float[][] oklab = new float[256][];
        for (int b = 1; b < 256; b++) {
            int colorId = (b >> 2) & 0x3F;
            int shadeId = b & 0x3;
            if (colorId == 0) continue;

            MapColor mc = MapColor.get(colorId);
            if (mc == null || mc.color == 0) continue;

            for (MapColor.Brightness br : MapColor.Brightness.values()) {
                if (br.id == shadeId) {
                    oklab[b] = rgbToOklab(mc.getRenderColor(br));
                    break;
                }
            }
        }
        return oklab;
    }

    /**
     * Converts a packed sRGB int (0xRRGGBB) to Oklab float[]{L, a, b}.
     * Alpha channel is ignored.
     */
    static float[] rgbToOklab(int rgb) {
        float r  = srgbToLinear(((rgb >> 16) & 0xFF) / 255f);
        float g  = srgbToLinear(((rgb >>  8) & 0xFF) / 255f);
        float bl = srgbToLinear(( rgb        & 0xFF) / 255f);

        float lms0 = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * bl;
        float lms1 = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * bl;
        float lms2 = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * bl;

        float l_ = (float) Math.cbrt(lms0);
        float m_ = (float) Math.cbrt(lms1);
        float s_ = (float) Math.cbrt(lms2);

        return new float[] {
            0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        };
    }

    /** sRGB gamma expansion (IEC 61966-2-1). */
    static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    public static int countDistinct(byte[] mapColors) {
        boolean[] seen = new boolean[256];
        for (byte b : mapColors) seen[b & 0xFF] = true;
        int count = 0;
        for (int i = 1; i < 256; i++) if (seen[i]) count++;
        return count;
    }

    public static int chunksNeeded(byte[] compressed, int chunkSize) {
        String base64 = Base64.getEncoder().encodeToString(compressed);
        return (base64.length() + chunkSize - 1) / chunkSize;
    }

    private static byte findClosestMapColorByte(int argb, boolean legalOnly,
                                                 float[][] oklabLookup) {
        if (((argb >>> 24) & 0xFF) < 128) return 0;

        float[] target = rgbToOklab(argb);
        float bestDist = Float.MAX_VALUE;
        byte bestByte = 0;

        for (int b = 1; b < 256; b++) {
            if (oklabLookup[b] == null) continue;
            if (legalOnly && (b & 0x3) == UNOBTAINABLE_SHADE_ID) continue;

            float dL = target[0] - oklabLookup[b][0];
            float da = target[1] - oklabLookup[b][1];
            float db = target[2] - oklabLookup[b][2];
            float dist = dL * dL + da * da + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                bestByte = (byte) b;
            }
        }
        return bestByte;
    }

    private static int findNearestNeighbor(int color, int[] freq, float[][] oklabLookup) {
        float[] target = oklabLookup[color];
        if (target == null) return color;

        float bestDist = Float.MAX_VALUE;
        int bestColor = color;

        for (int c = 1; c < 256; c++) {
            if (c == color || freq[c] == 0 || oklabLookup[c] == null) continue;

            float dL = target[0] - oklabLookup[c][0];
            float da = target[1] - oklabLookup[c][1];
            float db = target[2] - oklabLookup[c][2];
            float dist = dL * dL + da * da + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                bestColor = c;
            }
        }
        return bestColor;
    }
}
