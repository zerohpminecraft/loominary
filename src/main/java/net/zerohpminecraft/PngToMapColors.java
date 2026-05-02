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
 * Nearest-color matching uses Oklab perceptual distance rather than RGB Euclidean.
 *
 * Two encoding paths are available:
 *   convert()        — single-pass nearest-neighbor (fast, used for standard imports)
 *   convertTwoPass() — palette pre-selection + optional adaptive Floyd-Steinberg dithering
 */
public class PngToMapColors {

    public static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;
    private static final int MAX_COLOR_ID = 64;

    /** The unobtainable shade — id 3 (LOWEST brightness) never occurs naturally,
     *  even with staircasing. Skipping it limits us to ~186 reproducible colors. */
    private static final int UNOBTAINABLE_SHADE_ID = 3;

    /**
     * Error-floor for adaptive diffusion: squared Oklab distance below which
     * quantization error is considered perceptually insignificant and is NOT
     * diffused. Prevents spatial noise in well-matched regions.
     * 0.015 Oklab units squared ≈ a barely-noticeable colour shift.
     */
    private static final float ERROR_FLOOR_SQ = 0.015f * 0.015f;

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
     * Two-pass encode with optional adaptive Floyd-Steinberg dithering.
     *
     * <p><b>Pass 1</b> — nearest-neighbor quantization to discover the candidate
     * palette (which map-color bytes the image naturally uses).
     *
     * <p><b>Palette pre-selection</b> (when {@code targetColors > 0}) — the
     * candidate palette is pruned to N entries using the rarest-color-merge
     * algorithm, applied at the palette level before any pixel is committed.
     *
     * <p><b>Pass 2</b> — re-renders every source pixel against the restricted
     * palette. With {@code dither=true}, adaptive Floyd-Steinberg error diffusion
     * runs in Oklab space. Dithering strength per pixel is controlled by two
     * simultaneous conditions:
     * <ul>
     *   <li>Gradient suppression — local contrast (Oklab RMS distance to 4-connected
     *       neighbours) is computed for every pixel; Otsu's method finds the
     *       image-relative threshold that best separates smooth from edge regions;
     *       dithering fades to zero with a linear soft zone around that threshold.
     *       This preserves sharp edges while dithering smooth gradients fully.</li>
     *   <li>Error floor — pixels whose quantization error magnitude is below a
     *       small perceptual threshold are not diffused, preventing spatial noise
     *       in regions the palette already covers well.</li>
     * </ul>
     *
     * With {@code dither=false} the second pass is a plain palette-constrained
     * nearest-neighbor (palette pre-selection without the dithering effect).
     *
     * @param source       source image (need not be 128×128; scaled internally)
     * @param legalOnly    if true, restrict to ~186 obtainable colors
     * @param targetColors pre-select this many colors before the second pass;
     *                     ≤ 0 means use all first-pass colors
     * @param dither       if true, apply adaptive Floyd-Steinberg in the second pass
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

        // Optional palette reduction: merge rarest colors into neighbors.
        if (targetColors > 0 && countDistinct(firstPass) > targetColors) {
            reduceColorsInPlace(firstPass, targetColors, oklabLookup);
        }

        // Build the restricted palette mask.
        boolean[] palette = buildPalette(firstPass);

        // Pass 2: re-render against the restricted palette.
        if (!dither) return renderNearest(scaled, palette, oklabLookup);

        // Pre-compute per-pixel dithering strength for adaptive diffusion.
        float[] ditherStrength = computeDitherStrength(scaled);
        return renderDithered(scaled, palette, oklabLookup, ditherStrength);
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

    /** @return int[]{colorsRemoved, pixelsAffected} */
    private static int[] reduceColorsInPlace(byte[] mapColors, int targetColors,
                                              float[][] oklabLookup) {
        int colorsRemoved = 0;
        int pixelsAffected = 0;
        while (countDistinct(mapColors) > targetColors) {
            int[] freq = new int[256];
            for (byte b : mapColors) freq[b & 0xFF]++;
            int rarestColor = -1, rarestFreq = Integer.MAX_VALUE;
            for (int c = 1; c < 256; c++) {
                if (freq[c] > 0 && freq[c] < rarestFreq) { rarestFreq = freq[c]; rarestColor = c; }
            }
            if (rarestColor == -1) break;
            int bestNeighbor = findNearestNeighbor(rarestColor, freq, oklabLookup);
            if (bestNeighbor == rarestColor) break;
            byte from = (byte) rarestColor, to = (byte) bestNeighbor;
            for (int i = 0; i < mapColors.length; i++) {
                if (mapColors[i] == from) mapColors[i] = to;
            }
            colorsRemoved++;
            pixelsAffected += rarestFreq;
        }
        return new int[]{colorsRemoved, pixelsAffected};
    }

    // ── Adaptive dithering: strength map ─────────────────────────────────

    /**
     * Computes a per-pixel dithering strength in [0, 1] using two signals:
     *
     * <ol>
     *   <li><b>Local contrast</b> — RMS Oklab distance to 4-connected neighbours.
     *       High contrast = edge or fine detail = suppress dithering.
     *       Low contrast = smooth gradient = allow dithering.</li>
     *   <li><b>Otsu threshold</b> — the image-relative boundary that maximally
     *       separates the smooth-pixel and edge-pixel populations, so the
     *       suppression adapts to the contrast distribution of this specific image
     *       rather than using a fixed percentile.</li>
     * </ol>
     *
     * The soft zone spans [0.5 T, 1.5 T] around the Otsu threshold T, giving a
     * linear transition from full diffusion to none rather than a hard binary cut.
     *
     * The error-floor check (applied separately in {@link #renderDithered}) handles
     * the second condition: pixels whose quantization error is perceptually
     * imperceptible are not diffused regardless of their strength value.
     */
    private static float[] computeDitherStrength(BufferedImage scaled) {
        // Pre-compute per-pixel Oklab values (avoids redundant RGB→Oklab conversions
        // during the contrast pass).
        float[] okL = new float[MAP_BYTES];
        float[] okA = new float[MAP_BYTES];
        float[] okB = new float[MAP_BYTES];
        boolean[] opaque = new boolean[MAP_BYTES];

        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int argb = scaled.getRGB(x, y);
                int i = x + y * MAP_SIZE;
                if (((argb >>> 24) & 0xFF) >= 128) {
                    float[] ok = rgbToOklab(argb);
                    okL[i] = ok[0]; okA[i] = ok[1]; okB[i] = ok[2];
                    opaque[i] = true;
                }
            }
        }

        // Compute local contrast: RMS Oklab distance to 4-connected neighbours.
        float[] contrast = new float[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int i = x + y * MAP_SIZE;
                if (!opaque[i]) continue;
                float sumSq = 0f;
                int n = 0;
                // left
                if (x > 0           && opaque[i - 1])           { float dL=okL[i]-okL[i-1], da=okA[i]-okA[i-1], db=okB[i]-okB[i-1]; sumSq+=dL*dL+da*da+db*db; n++; }
                // right
                if (x < MAP_SIZE-1  && opaque[i + 1])           { float dL=okL[i]-okL[i+1], da=okA[i]-okA[i+1], db=okB[i]-okB[i+1]; sumSq+=dL*dL+da*da+db*db; n++; }
                // up
                if (y > 0           && opaque[i - MAP_SIZE])    { float dL=okL[i]-okL[i-MAP_SIZE], da=okA[i]-okA[i-MAP_SIZE], db=okB[i]-okB[i-MAP_SIZE]; sumSq+=dL*dL+da*da+db*db; n++; }
                // down
                if (y < MAP_SIZE-1  && opaque[i + MAP_SIZE])    { float dL=okL[i]-okL[i+MAP_SIZE], da=okA[i]-okA[i+MAP_SIZE], db=okB[i]-okB[i+MAP_SIZE]; sumSq+=dL*dL+da*da+db*db; n++; }
                contrast[i] = n > 0 ? (float) Math.sqrt(sumSq / n) : 0f;
            }
        }

        // Otsu's method on the contrast distribution to find the image-relative
        // smooth/edge boundary.
        float T = otsuThreshold(contrast);

        // Soft threshold: linear fade from full strength (contrast ≤ 0.5 T)
        // to zero (contrast ≥ 1.5 T).
        float lower = T * 0.5f;
        float upper = T * 1.5f;
        float range = upper - lower;

        float[] strength = new float[MAP_BYTES];
        for (int i = 0; i < MAP_BYTES; i++) {
            if (!opaque[i]) { strength[i] = 0f; continue; }
            strength[i] = range > 1e-6f
                    ? 1.0f - Math.min(1.0f, Math.max(0.0f, (contrast[i] - lower) / range))
                    : (contrast[i] <= T ? 1.0f : 0.0f);
        }
        return strength;
    }

    /**
     * Otsu's method on a float array: finds the threshold that maximises
     * between-class variance between the two populations on either side.
     *
     * Operates on the sorted array (O(N log N)) rather than binning, so it is
     * exact for continuous values without quantisation artefacts.
     *
     * @return the threshold value; falls back to the median if all values are equal
     */
    private static float otsuThreshold(float[] values) {
        int N = values.length;
        float[] sorted = values.clone();
        java.util.Arrays.sort(sorted);

        if (sorted[0] == sorted[N - 1]) return sorted[N / 2]; // all equal

        double totalSum = 0;
        for (float v : sorted) totalSum += v;

        double cumSum = 0;
        float bestVar = -1;
        float threshold = sorted[N / 2];

        for (int k = 1; k < N; k++) {
            cumSum += sorted[k - 1];
            double w0 = (double) k / N;
            double w1 = 1.0 - w0;
            double mu0 = cumSum / k;
            double mu1 = (totalSum - cumSum) / (N - k);
            float betweenVar = (float) (w0 * w1 * (mu0 - mu1) * (mu0 - mu1));
            if (betweenVar > bestVar) {
                bestVar = betweenVar;
                threshold = sorted[k];
            }
        }
        return threshold;
    }

    // ── Two-pass render helpers ───────────────────────────────────────────

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

    private static boolean[] buildPalette(byte[] mapColors) {
        boolean[] palette = new boolean[256];
        for (byte b : mapColors) palette[b & 0xFF] = true;
        return palette;
    }

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
     * Floyd-Steinberg error diffusion, restricted to {@code palette}, with
     * adaptive per-pixel diffusion strength.
     *
     * Error is accumulated in Oklab space using two alternating row buffers
     * (O(width) memory). For each pixel, the error is scaled by:
     *   strength[i]  — gradient-suppression factor (0 at edges, 1 in smooth areas)
     * and additionally gated by:
     *   errMagSq > ERROR_FLOOR_SQ — error-floor check, so well-matched pixels
     *                               (already covered by the palette) contribute
     *                               no spatial noise.
     *
     * Standard FS coefficients: right 7/16, lower-left 3/16, below 5/16,
     * lower-right 1/16.
     */
    private static byte[] renderDithered(BufferedImage scaled, boolean[] palette,
                                          float[][] oklabLookup, float[] ditherStrength) {
        byte[] out = new byte[MAP_BYTES];

        float[] errCur = new float[MAP_SIZE * 3];
        float[] errNxt = new float[MAP_SIZE * 3];

        for (int y = 0; y < MAP_SIZE; y++) {
            float[] tmp = errCur; errCur = errNxt; errNxt = tmp;
            java.util.Arrays.fill(errNxt, 0f);

            for (int x = 0; x < MAP_SIZE; x++) {
                int argb = scaled.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    out[x + y * MAP_SIZE] = 0;
                    continue;
                }

                float[] src = rgbToOklab(argb);
                int ei = x * 3;
                float L = src[0] + errCur[ei    ];
                float a = src[1] + errCur[ei + 1];
                float b = src[2] + errCur[ei + 2];

                byte chosen = findClosestInPalette(L, a, b, palette, oklabLookup);
                out[x + y * MAP_SIZE] = chosen;

                float[] cl = oklabLookup[chosen & 0xFF];
                float eL = L - cl[0];
                float ea = a - cl[1];
                float eb = b - cl[2];

                // Gate diffusion: skip if error is perceptually insignificant
                // OR the gradient-suppression map says we're at an edge.
                float errMagSq = eL * eL + ea * ea + eb * eb;
                float s = (errMagSq > ERROR_FLOOR_SQ)
                        ? ditherStrength[x + y * MAP_SIZE] : 0f;

                if (s > 0f) {
                    float seL = eL * s, sea = ea * s, seb = eb * s;
                    if (x + 1 < MAP_SIZE) {
                        int ri = (x + 1) * 3;
                        errCur[ri    ] += seL * (7f / 16f);
                        errCur[ri + 1] += sea * (7f / 16f);
                        errCur[ri + 2] += seb * (7f / 16f);
                    }
                    if (x - 1 >= 0) {
                        int li = (x - 1) * 3;
                        errNxt[li    ] += seL * (3f / 16f);
                        errNxt[li + 1] += sea * (3f / 16f);
                        errNxt[li + 2] += seb * (3f / 16f);
                    }
                    errNxt[ei    ] += seL * (5f / 16f);
                    errNxt[ei + 1] += sea * (5f / 16f);
                    errNxt[ei + 2] += seb * (5f / 16f);
                    if (x + 1 < MAP_SIZE) {
                        int ri = (x + 1) * 3;
                        errNxt[ri    ] += seL * (1f / 16f);
                        errNxt[ri + 1] += sea * (1f / 16f);
                        errNxt[ri + 2] += seb * (1f / 16f);
                    }
                }
            }
        }
        return out;
    }

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
