package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.minecraft.block.MapColor;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;

/**
 * Converts a BufferedImage into the 128×128 byte array that MapState.colors uses,
 * and can progressively reduce color count to fit a compressed size budget.
 *
 * Nearest-color matching uses Oklab perceptual distance rather than RGB Euclidean.
 *
 * Two encoding paths are available:
 *
 *   convert()            — single-pass nearest-neighbor (fast, standard imports)
 *   convertTwoPassGrid() — primary two-pass path; operates on the ENTIRE image
 *                          grid at once so that palette pre-selection, the Otsu
 *                          dither-strength map, and Floyd-Steinberg error diffusion
 *                          are all globally consistent across tile seams.
 *   convertTwoPass()     — convenience wrapper: delegates to convertTwoPassGrid
 *                          with a 1×1 grid.
 */
public class PngToMapColors {

    public static final int MAP_SIZE  = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;
    private static final int MAX_COLOR_ID = 64;

    private static final int UNOBTAINABLE_SHADE_ID = 3;

    /**
     * Error-floor for adaptive diffusion: squared Oklab distance below which
     * quantization error is not diffused (perceptually insignificant).
     * 0.015 Oklab units ≈ a barely-noticeable colour shift.
     */
    private static final float ERROR_FLOOR_SQ = 0.015f * 0.015f;

    // ── Public encode entry points ────────────────────────────────────────

    public static byte[] convert(BufferedImage source) {
        return convert(source, false);
    }

    /**
     * Single-pass nearest-neighbor quantization against the full map palette.
     */
    public static byte[] convert(BufferedImage source, boolean legalOnly) {
        BufferedImage scaled = scale(source, MAP_SIZE, MAP_SIZE);
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
     * Two-pass encode for a grid of tiles processed as a SINGLE UNIT.
     *
     * <p>Processing the full image together ensures:
     * <ul>
     *   <li>Palette pre-selection chooses N colors globally, so the same colour
     *       is never quantized to different map entries in adjacent tiles.</li>
     *   <li>The Otsu dither-strength threshold is calibrated to the complete
     *       image's contrast distribution, not per-tile subsets, so dithering
     *       density is consistent across seams.</li>
     *   <li>Floyd-Steinberg error diffusion crosses tile boundaries, eliminating
     *       the hard reset artefact that would otherwise appear at each seam.</li>
     * </ul>
     *
     * @param fullImage    source image; will be scaled to {@code cols*128 × rows*128}
     * @param legalOnly    restrict to ~186 obtainable colours
     * @param targetColors pre-select this many colours globally (≤0 = unlimited)
     * @param dither       apply adaptive Floyd-Steinberg in the second pass
     * @param cols         grid columns
     * @param rows         grid rows
     * @return {@code byte[cols*rows][16384]} — one 128×128 map-colour array per tile,
     *         in row-major order (tile index = tileRow*cols + tileCol)
     */
    public static byte[][] convertTwoPassGrid(BufferedImage fullImage, boolean legalOnly,
                                               int targetColors, boolean dither,
                                               int cols, int rows) {
        int totalW = cols * MAP_SIZE;
        int totalH = rows * MAP_SIZE;

        BufferedImage scaled = scale(fullImage, totalW, totalH);
        float[][] oklabLookup = buildOklabLookup();

        // Pass 1: nearest-neighbor quantization of the entire grid.
        byte[] firstPass = new byte[totalW * totalH];
        for (int y = 0; y < totalH; y++) {
            for (int x = 0; x < totalW; x++) {
                firstPass[x + y * totalW] = findClosestMapColorByte(
                        scaled.getRGB(x, y), legalOnly, oklabLookup);
            }
        }

        // Global palette pre-selection — the same N colours apply to every tile.
        if (targetColors > 0 && countDistinct(firstPass) > targetColors) {
            reduceColorsInPlace(firstPass, targetColors, oklabLookup);
        }

        boolean[] palette = buildPalette(firstPass);

        // Pass 2: render the complete grid image.
        byte[] fullResult;
        if (dither) {
            float[] strength = computeDitherStrength(scaled, totalW, totalH);
            fullResult = renderDithered(scaled, palette, oklabLookup, strength, totalW, totalH);
        } else {
            fullResult = renderNearest(scaled, palette, oklabLookup, totalW, totalH);
        }

        // Split into per-tile 128×128 arrays.
        return splitIntoTiles(fullResult, totalW, cols, rows);
    }

    /**
     * Convenience wrapper: processes a single 128×128 tile image with the same
     * two-pass pipeline as {@link #convertTwoPassGrid}.
     */
    public static byte[] convertTwoPass(BufferedImage source, boolean legalOnly,
                                         int targetColors, boolean dither) {
        return convertTwoPassGrid(source, legalOnly, targetColors, dither, 1, 1)[0];
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

            compressed = compressCombined(prefix, mapColors);
            chunks = chunksNeeded(compressed, chunkSize);
        }

        return new FitResult(mapColors, compressed, colorsRemoved, originalDistinct, pixelsAffected);
    }

    public static FitResult reduceToFit(byte[] mapColors, int chunkSize, int maxChunks) {
        return reduceToFit(mapColors, new byte[0], chunkSize, maxChunks);
    }

    // ── Reduction: color-count target ────────────────────────────────────

    public static FitResult reduceToColorCount(byte[] mapColors, byte[] prefix,
                                               int chunkSize, int targetColors) {
        float[][] oklabLookup = buildOklabLookup();
        int originalDistinct = countDistinct(mapColors);
        int[] stats = reduceColorsInPlace(mapColors, targetColors, oklabLookup);
        byte[] compressed = compressCombined(prefix, mapColors);
        return new FitResult(mapColors, compressed, stats[0], originalDistinct, stats[1]);
    }

    public static FitResult reduceToColorCount(byte[] mapColors, int chunkSize, int targetColors) {
        return reduceToColorCount(mapColors, new byte[0], chunkSize, targetColors);
    }

    // ── Shared core: in-place palette reduction ───────────────────────────

    /** @return int[]{colorsRemoved, pixelsAffected} */
    private static int[] reduceColorsInPlace(byte[] mapColors, int targetColors,
                                              float[][] oklabLookup) {
        int colorsRemoved = 0, pixelsAffected = 0;
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
     * Computes a per-pixel dithering strength in [0, 1] for an image of
     * arbitrary dimensions.
     *
     * <p><b>Gradient suppression:</b> for each pixel, local contrast is the RMS
     * Oklab distance to its four axis-aligned neighbours. Otsu's method then finds
     * the image-relative threshold that best separates smooth and edge populations,
     * adapting to the contrast distribution of this specific image rather than a
     * fixed percentile. A linear soft zone spanning [0.5T, 1.5T] gives a gradual
     * transition rather than a binary cut.
     *
     * <p>The error-floor gate (applied in {@link #renderDithered}) handles the
     * complementary condition: pixels the palette already matches well receive no
     * diffusion regardless of their gradient strength.
     *
     * <p>Operating on the full grid image (rather than per tile) ensures the Otsu
     * threshold is calibrated to the entire image's contrast distribution.
     */
    private static float[] computeDitherStrength(BufferedImage scaled, int width, int height) {
        int total = width * height;

        float[] okL = new float[total];
        float[] okA = new float[total];
        float[] okB = new float[total];
        boolean[] opaque = new boolean[total];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = scaled.getRGB(x, y);
                int i = x + y * width;
                if (((argb >>> 24) & 0xFF) >= 128) {
                    float[] ok = rgbToOklab(argb);
                    okL[i] = ok[0]; okA[i] = ok[1]; okB[i] = ok[2];
                    opaque[i] = true;
                }
            }
        }

        float[] contrast = new float[total];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = x + y * width;
                if (!opaque[i]) continue;
                float sumSq = 0f; int n = 0;
                if (x > 0         && opaque[i-1])     { float dL=okL[i]-okL[i-1],     da=okA[i]-okA[i-1],     db=okB[i]-okB[i-1];     sumSq+=dL*dL+da*da+db*db; n++; }
                if (x < width-1   && opaque[i+1])     { float dL=okL[i]-okL[i+1],     da=okA[i]-okA[i+1],     db=okB[i]-okB[i+1];     sumSq+=dL*dL+da*da+db*db; n++; }
                if (y > 0         && opaque[i-width]) { float dL=okL[i]-okL[i-width], da=okA[i]-okA[i-width], db=okB[i]-okB[i-width]; sumSq+=dL*dL+da*da+db*db; n++; }
                if (y < height-1  && opaque[i+width]) { float dL=okL[i]-okL[i+width], da=okA[i]-okA[i+width], db=okB[i]-okB[i+width]; sumSq+=dL*dL+da*da+db*db; n++; }
                contrast[i] = n > 0 ? (float) Math.sqrt(sumSq / n) : 0f;
            }
        }

        float T = otsuThreshold(contrast);
        float lower = T * 0.5f, upper = T * 1.5f, range = upper - lower;

        float[] strength = new float[total];
        for (int i = 0; i < total; i++) {
            if (!opaque[i]) continue;
            strength[i] = range > 1e-6f
                    ? 1.0f - Math.min(1.0f, Math.max(0.0f, (contrast[i] - lower) / range))
                    : (contrast[i] <= T ? 1.0f : 0.0f);
        }
        return strength;
    }

    /**
     * Otsu's method on a float array: finds the threshold maximising between-class
     * variance, operating on the sorted values (exact for continuous data).
     */
    private static float otsuThreshold(float[] values) {
        int N = values.length;
        float[] sorted = values.clone();
        java.util.Arrays.sort(sorted);

        if (sorted[0] == sorted[N - 1]) return sorted[N / 2];

        double totalSum = 0;
        for (float v : sorted) totalSum += v;

        double cumSum = 0;
        float bestVar = -1, threshold = sorted[N / 2];

        for (int k = 1; k < N; k++) {
            cumSum += sorted[k - 1];
            double w0 = (double) k / N, w1 = 1.0 - w0;
            double mu0 = cumSum / k, mu1 = (totalSum - cumSum) / (N - k);
            float betweenVar = (float) (w0 * w1 * (mu0 - mu1) * (mu0 - mu1));
            if (betweenVar > bestVar) { bestVar = betweenVar; threshold = sorted[k]; }
        }
        return threshold;
    }

    // ── Render passes (generalised to arbitrary image dimensions) ─────────

    private static byte[] renderNearest(BufferedImage scaled, boolean[] palette,
                                         float[][] oklabLookup, int width, int height) {
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = scaled.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) { out[x + y * width] = 0; continue; }
                float[] ok = rgbToOklab(argb);
                out[x + y * width] = findClosestInPalette(ok[0], ok[1], ok[2], palette, oklabLookup);
            }
        }
        return out;
    }

    /**
     * Floyd-Steinberg error diffusion restricted to {@code palette}, with
     * per-pixel adaptive strength.
     *
     * <p>When called on the full grid image (rather than individual tiles), error
     * propagates naturally across tile seams, eliminating the hard reset artefact
     * that would otherwise appear at each boundary.
     *
     * <p>Error is gated by two conditions (both must pass for diffusion to occur):
     * <ol>
     *   <li>{@code ditherStrength[i] > 0} — gradient-suppression map says this
     *       pixel is in a smooth region (not an edge or fine detail).</li>
     *   <li>{@code errMagSq > ERROR_FLOOR_SQ} — quantisation error is
     *       perceptually significant (palette does not already cover this colour
     *       well).</li>
     * </ol>
     */
    static byte[] renderDithered(BufferedImage scaled, boolean[] palette,
                                          float[][] oklabLookup, float[] ditherStrength,
                                          int width, int height) {
        byte[] out = new byte[width * height];

        float[] errCur = new float[width * 3];
        float[] errNxt = new float[width * 3];

        for (int y = 0; y < height; y++) {
            float[] tmp = errCur; errCur = errNxt; errNxt = tmp;
            java.util.Arrays.fill(errNxt, 0f);

            for (int x = 0; x < width; x++) {
                int argb = scaled.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    out[x + y * width] = 0;
                    continue;
                }

                float[] src = rgbToOklab(argb);
                int ei = x * 3;
                float L = src[0] + errCur[ei    ];
                float a = src[1] + errCur[ei + 1];
                float b = src[2] + errCur[ei + 2];

                byte chosen = findClosestInPalette(L, a, b, palette, oklabLookup);
                out[x + y * width] = chosen;

                float[] cl = oklabLookup[chosen & 0xFF];
                float eL = L - cl[0], ea = a - cl[1], eb = b - cl[2];

                float errMagSq = eL * eL + ea * ea + eb * eb;
                float s = (errMagSq > ERROR_FLOOR_SQ) ? ditherStrength[x + y * width] : 0f;

                if (s > 0f) {
                    float seL = eL * s, sea = ea * s, seb = eb * s;
                    if (x + 1 < width)  { int ri=(x+1)*3; errCur[ri]+=seL*(7f/16f); errCur[ri+1]+=sea*(7f/16f); errCur[ri+2]+=seb*(7f/16f); }
                    if (x - 1 >= 0)     { int li=(x-1)*3; errNxt[li]+=seL*(3f/16f); errNxt[li+1]+=sea*(3f/16f); errNxt[li+2]+=seb*(3f/16f); }
                    errNxt[ei]+=seL*(5f/16f); errNxt[ei+1]+=sea*(5f/16f); errNxt[ei+2]+=seb*(5f/16f);
                    if (x + 1 < width)  { int ri=(x+1)*3; errNxt[ri]+=seL*(1f/16f); errNxt[ri+1]+=sea*(1f/16f); errNxt[ri+2]+=seb*(1f/16f); }
                }
            }
        }
        return out;
    }

    // ── Tile splitting ────────────────────────────────────────────────────

    /**
     * Splits a flat pixel array of dimensions {@code totalW × (rows*128)} into
     * {@code cols*rows} individual 128×128 tile arrays in row-major order.
     */
    private static byte[][] splitIntoTiles(byte[] full, int totalW, int cols, int rows) {
        byte[][] tiles = new byte[cols * rows][MAP_BYTES];
        for (int tileRow = 0; tileRow < rows; tileRow++) {
            for (int tileCol = 0; tileCol < cols; tileCol++) {
                byte[] tile = tiles[tileRow * cols + tileCol];
                for (int ty = 0; ty < MAP_SIZE; ty++) {
                    System.arraycopy(full,
                            tileCol * MAP_SIZE + (tileRow * MAP_SIZE + ty) * totalW,
                            tile, ty * MAP_SIZE, MAP_SIZE);
                }
            }
        }
        return tiles;
    }

    // ── Scaling ───────────────────────────────────────────────────────────

    static BufferedImage scale(BufferedImage source, int targetW, int targetH) {
        if (source.getWidth() == targetW && source.getHeight() == targetH
                && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetW, targetH, null);
        g.dispose();
        return out;
    }

    // ── Palette helpers ───────────────────────────────────────────────────

    static boolean[] buildPalette(byte[] mapColors) {
        boolean[] palette = new boolean[256];
        for (byte b : mapColors) palette[b & 0xFF] = true;
        return palette;
    }

    private static byte findClosestInPalette(float L, float a, float b,
                                              boolean[] palette, float[][] oklabLookup) {
        float bestDist = Float.MAX_VALUE;
        byte bestByte  = 0;
        for (int c = 1; c < 256; c++) {
            if (!palette[c] || oklabLookup[c] == null) continue;
            float dL = L - oklabLookup[c][0], da = a - oklabLookup[c][1], db = b - oklabLookup[c][2];
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
            int colorId = (b >> 2) & 0x3F, shadeId = b & 0x3;
            if (colorId == 0) { rgb[b] = 0; continue; }
            MapColor mc = MapColor.get(colorId);
            if (mc == null || mc.color == 0) { rgb[b] = 0; continue; }
            for (MapColor.Brightness br : MapColor.Brightness.values()) {
                if (br.id == shadeId) { rgb[b] = mc.getRenderColor(br); break; }
            }
        }
        return rgb;
    }

    static float[][] buildOklabLookup() {
        float[][] oklab = new float[256][];
        for (int b = 1; b < 256; b++) {
            int colorId = (b >> 2) & 0x3F, shadeId = b & 0x3;
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

    static byte findClosestMapColorByte(int argb, boolean legalOnly,
                                                 float[][] oklabLookup) {
        if (((argb >>> 24) & 0xFF) < 128) return 0;
        float[] target = rgbToOklab(argb);
        float bestDist = Float.MAX_VALUE;
        byte bestByte = 0;
        for (int b = 1; b < 256; b++) {
            if (oklabLookup[b] == null) continue;
            if (legalOnly && (b & 0x3) == UNOBTAINABLE_SHADE_ID) continue;
            float dL = target[0]-oklabLookup[b][0], da = target[1]-oklabLookup[b][1], db = target[2]-oklabLookup[b][2];
            float dist = dL*dL + da*da + db*db;
            if (dist < bestDist) { bestDist = dist; bestByte = (byte) b; }
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
            float dL = target[0]-oklabLookup[c][0], da = target[1]-oklabLookup[c][1], db = target[2]-oklabLookup[c][2];
            float dist = dL*dL + da*da + db*db;
            if (dist < bestDist) { bestDist = dist; bestColor = c; }
        }
        return bestColor;
    }

    // ── GIF multi-frame encode ────────────────────────────────────────────

    /**
     * Result of encoding a GIF file.
     * {@code tileFrames[tileIndex][frameIndex]} is the 16,384-byte map-color array
     * for that spatial tile and animation frame. Tile index is row-major.
     */
    public static class GifResult {
        public final byte[][][] tileFrames;
        public final int[] frameDelays; // ms per frame, length == frameCount
        GifResult(byte[][][] tileFrames, int[] frameDelays) {
            this.tileFrames = tileFrames;
            this.frameDelays = frameDelays;
        }
        public int frameCount() { return frameDelays.length; }
    }

    private record GifFrameInfo(int left, int top, int delayMs, String disposal) {}

    /**
     * Reads a GIF, coalesces its frames (respecting disposal methods), and encodes
     * each frame as a grid of 128×128 map-color tiles using a shared palette.
     *
     * Shared palette: pass 1 nearest-neighbor on the union of all frames so that the
     * same region in different frames maps to the same map-color bytes, preventing
     * inter-frame color flicker and improving zstd compression of the combined payload.
     */
    public static GifResult convertGif(Path gifPath, boolean legalOnly, int targetColors,
                                        boolean dither, int cols, int rows) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(Files.newInputStream(gifPath))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType("image/gif");
            if (!readers.hasNext()) throw new IOException("No GIF image reader available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false);
                return readGif(reader, legalOnly, targetColors, dither, cols, rows);
            } finally {
                reader.dispose();
            }
        }
    }

    private static GifResult readGif(ImageReader reader, boolean legalOnly, int targetColors,
                                      boolean dither, int cols, int rows) throws IOException {
        // Screen dimensions from stream metadata (reliable; Java's GIF reader always provides it).
        int screenW = 0, screenH = 0;
        IIOMetadata streamMeta = reader.getStreamMetadata();
        if (streamMeta != null) {
            Node root = streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
            for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
                if ("LogicalScreenDescriptor".equals(c.getNodeName())) {
                    NamedNodeMap a = c.getAttributes();
                    screenW = intAttr(a, "logicalScreenWidth", 0);
                    screenH = intAttr(a, "logicalScreenHeight", 0);
                }
            }
        }
        if (screenW <= 0 || screenH <= 0) {
            throw new IOException("Could not read GIF logical screen dimensions from stream metadata");
        }

        int numFrames = reader.getNumImages(true);
        if (numFrames <= 0) throw new IOException("GIF has no frames");

        // Coalesce frames respecting disposal methods.
        BufferedImage canvas = new BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB);
        BufferedImage prevCanvas = null;
        BufferedImage[] coalesced = new BufferedImage[numFrames];
        int[] frameDelays = new int[numFrames];

        for (int f = 0; f < numFrames; f++) {
            BufferedImage subframe = reader.read(f);
            GifFrameInfo info = parseGifFrameMeta(reader.getImageMetadata(f));
            frameDelays[f] = info.delayMs();

            if ("restoreToPrevious".equals(info.disposal())) prevCanvas = deepCopy(canvas);

            Graphics2D g = canvas.createGraphics();
            g.drawImage(subframe, info.left(), info.top(), null);
            g.dispose();

            coalesced[f] = deepCopy(canvas);

            if ("restoreToBackground".equals(info.disposal())) {
                Graphics2D gc = canvas.createGraphics();
                gc.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR));
                gc.fillRect(info.left(), info.top(), subframe.getWidth(), subframe.getHeight());
                gc.dispose();
            } else if ("restoreToPrevious".equals(info.disposal()) && prevCanvas != null) {
                canvas = prevCanvas;
                prevCanvas = null;
            }
        }

        // Scale all frames to the target grid resolution.
        int totalW = cols * MAP_SIZE;
        int totalH = rows * MAP_SIZE;
        BufferedImage[] scaled = new BufferedImage[numFrames];
        for (int f = 0; f < numFrames; f++) scaled[f] = scale(coalesced[f], totalW, totalH);

        // Shared palette: pass 1 on the union of all frames.
        float[][] oklabLookup = buildOklabLookup();
        byte[] union = new byte[numFrames * totalW * totalH];
        for (int f = 0; f < numFrames; f++) {
            int base = f * totalW * totalH;
            for (int y = 0; y < totalH; y++) {
                for (int x = 0; x < totalW; x++) {
                    union[base + x + y * totalW] = findClosestMapColorByte(
                            scaled[f].getRGB(x, y), legalOnly, oklabLookup);
                }
            }
        }
        if (targetColors > 0 && countDistinct(union) > targetColors) {
            reduceColorsInPlace(union, targetColors, oklabLookup);
        }
        boolean[] sharedPalette = buildPalette(union);

        // Pass 2: render each frame against the shared palette, then split into tiles.
        int tileCount = cols * rows;
        byte[][][] tileFrames = new byte[tileCount][numFrames][MAP_BYTES];
        for (int f = 0; f < numFrames; f++) {
            byte[] fullResult;
            if (dither) {
                float[] strength = computeDitherStrength(scaled[f], totalW, totalH);
                fullResult = renderDithered(scaled[f], sharedPalette, oklabLookup, strength, totalW, totalH);
            } else {
                fullResult = renderNearest(scaled[f], sharedPalette, oklabLookup, totalW, totalH);
            }
            byte[][] split = splitIntoTiles(fullResult, totalW, cols, rows);
            for (int t = 0; t < tileCount; t++) tileFrames[t][f] = split[t];
        }

        return new GifResult(tileFrames, frameDelays);
    }

    private static GifFrameInfo parseGifFrameMeta(IIOMetadata meta) {
        int left = 0, top = 0, delayMs = 100;
        String disposal = "none";
        try {
            Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                NamedNodeMap a = child.getAttributes();
                switch (child.getNodeName()) {
                    case "ImageDescriptor":
                        left = intAttr(a, "imageLeftPosition", 0);
                        top  = intAttr(a, "imageTopPosition",  0);
                        break;
                    case "GraphicControlExtension":
                        int cs = intAttr(a, "delayTime", 10); // centiseconds
                        delayMs = Math.max(20, cs * 10);      // min 20ms per convention
                        disposal = strAttr(a, "disposalMethod", "none");
                        break;
                }
            }
        } catch (Exception ignored) {}
        return new GifFrameInfo(left, top, delayMs, disposal);
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static int intAttr(NamedNodeMap attrs, String name, int fallback) {
        if (attrs == null) return fallback;
        Node n = attrs.getNamedItem(name);
        if (n == null) return fallback;
        try { return Integer.parseInt(n.getNodeValue()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String strAttr(NamedNodeMap attrs, String name, String fallback) {
        if (attrs == null) return fallback;
        Node n = attrs.getNamedItem(name);
        return n == null ? fallback : n.getNodeValue();
    }
}
