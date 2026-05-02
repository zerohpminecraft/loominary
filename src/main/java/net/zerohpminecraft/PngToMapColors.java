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
 *
 * Two encoding paths are available:
 *   convert()        — single-pass nearest-neighbor (fast, used for standard imports)
 *   convertTwoPass() — palette pre-selection + optional Floyd-Steinberg dithering
 */
public class PngToMapColors {

    public static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;
    private static final int MAX_COLOR_ID = 64;

    /** The unobtainable shade — id 3 (LOWEST brightness) never occurs naturally,
     *  even with staircasing. Skipping it limits us to ~186 reproducible colors. */
    private static final int UNOBTAINABLE_SHADE_ID = 3;

    // ── Public encode entry points ────────────────────────────────────────

    /** Legacy entry point: includes all 4 shades, even the unobtainable one. */
    public static byte[] convert(BufferedImage source) {
        return convert(source, false);
    }

    /**
     * Single-pass nearest-neighbor quantization against the full map palette.
     *
     * @param legalOnly if true, restricts output to colors reachable via real
     *                  block placement (~186 colors via staircasing). If false,
     *                  uses all 248 valid map-color bytes including unobtainable
     *                  shade 3 entries.
     */
    public static byte[] convert(BufferedImage source, boolean legalOnly) {
        BufferedImage scaled = scale(source);
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
     * Two-pass encode: first-pass quantization to discover candidate map colors,
     * optional palette reduction to {@code targetColors}, then a second render pass
     * using only the selected colors — with or without Floyd-Steinberg dithering.
     *
     * <p>When {@code targetColors <= 0} no palette reduction is performed and the
     * second pass uses all colors the first pass produced. When {@code dither} is
     * false the second pass is a plain restricted nearest-neighbor (useful for
     * palette pre-selection without the dithering effect).
     *
     * @param source       source image (need not be 128×128; scaled internally)
     * @param legalOnly    if true, restrict to ~186 obtainable colors
     * @param targetColors pre-select this many colors before the second pass;
     *                     ≤ 0 means use all first-pass colors
     * @param dither       if true, apply Floyd-Steinberg error diffusion in the
     *                     second pass
     */
    public static byte[] convertTwoPass(BufferedImage source, boolean legalOnly,
                                         int targetColors, boolean dither) {
        BufferedImage scaled = scale(source);
        float[][] oklabLookup = buildOklabLookup();

        // Pass 1: full nearest-neighbor to discover candidate palette.
        byte[] firstPass = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                firstPass[x + y * MAP_SIZE] = findClosestMapColorByte(
                        scaled.getRGB(x, y), legalOnly, oklabLookup);
            }
        }

        // Optional palette reduction: merge rarest colors into neighbors until
        // at most targetColors distinct entries remain.
        if (targetColors > 0 && countDistinct(firstPass) > targetColors) {
            reduceColorsInPlace(firstPass, targetColors, oklabLookup);
        }

        // Build the restricted palette mask from whatever survived pass 1.
        boolean[] palette = buildPalette(firstPass);

        // Pass 2: re-render against the restricted palette.
        return dither
                ? renderDithered(scaled, palette, oklabLookup)
                : renderNearest(scaled, palette, oklabLookup);
    }

    // ── FitResult ────────────────────────────────────────────────────────

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

    // ── Reduction: banner-count target ───────────────────────────────────

    /**
     * Progressively reduces the color palette of a map-color byte array until
     * the compressed+base64'd result fits within maxChunks banner chunks.
     *
     * The optional {@code prefix} (e.g. manifest bytes) is prepended to mapColors
     * before compression so that the size check includes its overhead.
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

    // ── Reduction: color-count target ────────────────────────────────────

    /**
     * Progressively reduces the color palette until at most {@code targetColors}
     * distinct map-color values remain, merging each rarest color into its nearest
     * visual neighbor.
     *
     * @param mapColors    the 128×128 map-color byte array (modified in place)
     * @param prefix       bytes prepended before compression (manifest); may be empty
     * @param chunkSize    base64 chars per banner chunk (used only to size the final result)
     * @param targetColors maximum distinct colors to retain
     */
    public static FitResult reduceToColorCount(byte[] mapColors, byte[] prefix,
                                               int chunkSize, int targetColors) {
        float[][] oklabLookup = buildOklabLookup();
        int originalDistinct = countDistinct(mapColors);
        int[] stats = reduceColorsInPlace(mapColors, targetColors, oklabLookup);
        byte[] compressed = compressCombined(prefix, mapColors);
        return new FitResult(mapColors, compressed, stats[0], originalDistinct, stats[1]);
    }

    /** Delegates to {@link #reduceToColorCount(byte[], byte[], int, int)} with no prefix. */
    public static FitResult reduceToColorCount(byte[] mapColors, int chunkSize, int targetColors) {
        return reduceToColorCount(mapColors, new byte[0], chunkSize, targetColors);
    }

    // ── Shared core: in-place palette reduction ───────────────────────────

    /**
     * Merges the rarest color into its nearest neighbor, repeatedly, until at most
     * {@code targetColors} distinct values remain. Modifies {@code mapColors} in place.
     *
     * @return int[]{colorsRemoved, pixelsAffected}
     */
    private static int[] reduceColorsInPlace(byte[] mapColors, int targetColors,
                                              float[][] oklabLookup) {
        int colorsRemoved = 0;
        int pixelsAffected = 0;

        while (countDistinct(mapColors) > targetColors) {
            int[] freq = new int[256];
            for (byte b : mapColors) freq[b & 0xFF]++;

            int rarestColor = -1;
            int rarestFreq  = Integer.MAX_VALUE;
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
        }
        return new int[]{colorsRemoved, pixelsAffected};
    }

    // ── Two-pass helpers ──────────────────────────────────────────────────

    /**
     * Scales {@code source} to 128×128 ARGB. Returns {@code source} unchanged when
     * it is already the right size and type (avoids a redundant copy).
     */
    private static BufferedImage scale(BufferedImage source) {
        if (source.getWidth() == MAP_SIZE && source.getHeight() == MAP_SIZE
                && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage out = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, MAP_SIZE, MAP_SIZE, null);
        g.dispose();
        return out;
    }

    /** Returns a boolean[256] mask of which map-color bytes are present in mapColors. */
    private static boolean[] buildPalette(byte[] mapColors) {
        boolean[] palette = new boolean[256];
        for (byte b : mapColors) palette[b & 0xFF] = true;
        return palette;
    }

    /**
     * Second pass — nearest-neighbor, restricted to {@code palette}.
     * Used when the caller wants palette pre-selection without dithering.
     */
    private static byte[] renderNearest(BufferedImage scaled, boolean[] palette,
                                         float[][] oklabLookup) {
        byte[] out = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int argb = scaled.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) { out[x + y * MAP_SIZE] = 0; continue; }
                float[] ok = rgbToOklab(argb);
                out[x + y * MAP_SIZE] = findClosestInPalette(ok[0], ok[1], ok[2],
                        palette, oklabLookup);
            }
        }
        return out;
    }

    /**
     * Second pass — Floyd-Steinberg error diffusion within {@code palette}.
     *
     * Error is accumulated in Oklab space (same space as our distance metric) using
     * two alternating row buffers so memory usage stays O(width) rather than O(area).
     * The standard coefficient pattern is:
     *   right       7/16
     *   lower-left  3/16
     *   below       5/16
     *   lower-right 1/16
     */
    private static byte[] renderDithered(BufferedImage scaled, boolean[] palette,
                                          float[][] oklabLookup) {
        byte[] out = new byte[MAP_BYTES];

        // Per-pixel Oklab error: [x*3], [x*3+1], [x*3+2] = ΔL, Δa, Δb
        float[] errCur = new float[MAP_SIZE * 3];
        float[] errNxt = new float[MAP_SIZE * 3];

        for (int y = 0; y < MAP_SIZE; y++) {
            // Swap row buffers and clear the "next" row.
            float[] tmp = errCur; errCur = errNxt; errNxt = tmp;
            java.util.Arrays.fill(errNxt, 0f);

            for (int x = 0; x < MAP_SIZE; x++) {
                int argb = scaled.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    out[x + y * MAP_SIZE] = 0;
                    continue;
                }

                // Source color in Oklab, shifted by accumulated error.
                float[] src = rgbToOklab(argb);
                int ei = x * 3;
                float L = src[0] + errCur[ei    ];
                float a = src[1] + errCur[ei + 1];
                float b = src[2] + errCur[ei + 2];

                byte chosen = findClosestInPalette(L, a, b, palette, oklabLookup);
                out[x + y * MAP_SIZE] = chosen;

                // Quantization error in Oklab.
                float[] cl = oklabLookup[chosen & 0xFF];
                float eL = L - cl[0];
                float ea = a - cl[1];
                float eb = b - cl[2];

                // Distribute: right (7/16), lower-left (3/16), below (5/16), lower-right (1/16).
                if (x + 1 < MAP_SIZE) {
                    int ri = (x + 1) * 3;
                    errCur[ri    ] += eL * (7f / 16f);
                    errCur[ri + 1] += ea * (7f / 16f);
                    errCur[ri + 2] += eb * (7f / 16f);
                }
                if (x - 1 >= 0) {
                    int li = (x - 1) * 3;
                    errNxt[li    ] += eL * (3f / 16f);
                    errNxt[li + 1] += ea * (3f / 16f);
                    errNxt[li + 2] += eb * (3f / 16f);
                }
                {
                    errNxt[ei    ] += eL * (5f / 16f);
                    errNxt[ei + 1] += ea * (5f / 16f);
                    errNxt[ei + 2] += eb * (5f / 16f);
                }
                if (x + 1 < MAP_SIZE) {
                    int ri = (x + 1) * 3;
                    errNxt[ri    ] += eL * (1f / 16f);
                    errNxt[ri + 1] += ea * (1f / 16f);
                    errNxt[ri + 2] += eb * (1f / 16f);
                }
            }
        }
        return out;
    }

    /** Nearest palette entry given already-accumulated Oklab coordinates. */
    private static byte findClosestInPalette(float L, float a, float b,
                                              boolean[] palette, float[][] oklabLookup) {
        float bestDist = Float.MAX_VALUE;
        byte bestByte  = 0;
        for (int c = 1; c < 256; c++) {
            if (!palette[c] || oklabLookup[c] == null) continue;
            float dL = L - oklabLookup[c][0];
            float da = a - oklabLookup[c][1];
            float db = b - oklabLookup[c][2];
            float dist = dL * dL + da * da + db * db;
            if (dist < bestDist) { bestDist = dist; bestByte = (byte) c; }
        }
        return bestByte;
    }

    // ── Compression helpers ───────────────────────────────────────────────

    private static byte[] compressCombined(byte[] prefix, byte[] data) {
        if (prefix.length == 0) return Zstd.compress(data, Zstd.maxCompressionLevel());
        byte[] combined = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(data, 0, combined, prefix.length, data.length);
        return Zstd.compress(combined, Zstd.maxCompressionLevel());
    }

    // ── Color-space helpers ───────────────────────────────────────────────

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
            if (colorId == 0) { rgb[b] = 0; continue; }
            MapColor mc = MapColor.get(colorId);
            if (mc == null || mc.color == 0) { rgb[b] = 0; continue; }
            for (MapColor.Brightness br : MapColor.Brightness.values()) {
                if (br.id == shadeId) { rgb[b] = mc.getRenderColor(br); break; }
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
                if (br.id == shadeId) { oklab[b] = rgbToOklab(mc.getRenderColor(br)); break; }
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

    // ── Utility ───────────────────────────────────────────────────────────

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
