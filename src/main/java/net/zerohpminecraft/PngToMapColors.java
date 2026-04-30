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

        byte[] out = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                out[x + y * MAP_SIZE] = findClosestMapColorByte(scaled.getRGB(x, y), legalOnly);
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
     * Algorithm:
     *   1. Count frequency of each distinct color byte
     *   2. Find the rarest color (fewest pixels)
     *   3. Replace it with its nearest visual neighbor (by RGB distance)
     *   4. Re-compress and check size
     *   5. Repeat until it fits
     *
     * This minimizes visual impact because the rarest colors affect the fewest
     * pixels. Convergence is guaranteed — in the degenerate case we reduce to
     * a single color, which compresses to nearly nothing.
     *
     * @param mapColors  the 128×128 map-color byte array (will be modified in place)
     * @param chunkSize  base64 chars per banner chunk
     * @param maxChunks  maximum number of chunks (banners) allowed
     * @return FitResult with the reduced map colors and compression stats
     */
    public static FitResult reduceToFit(byte[] mapColors, int chunkSize, int maxChunks) {
        // Pre-compute RGB for every possible map color byte (0–255)
        // so we don't re-derive it during distance calculations.
        int[] colorRgb = buildColorLookup();

        // Count original distinct colors (excluding transparent/0)
        int originalDistinct = countDistinct(mapColors);
        int colorsRemoved = 0;
        int pixelsAffected = 0;

        // Initial compression attempt
        byte[] compressed = Zstd.compress(mapColors, Zstd.maxCompressionLevel());
        int chunks = chunksNeeded(compressed, chunkSize);

        while (chunks > maxChunks) {
            // Count frequency of each color byte
            int[] freq = new int[256];
            for (byte b : mapColors) {
                freq[b & 0xFF]++;
            }

            // Find the rarest non-zero-frequency color (skip byte 0 = transparent)
            int rarestColor = -1;
            int rarestFreq = Integer.MAX_VALUE;
            for (int c = 1; c < 256; c++) {
                if (freq[c] > 0 && freq[c] < rarestFreq) {
                    rarestFreq = freq[c];
                    rarestColor = c;
                }
            }

            if (rarestColor == -1) break;

            // Find the nearest visual neighbor among colors still in use
            int bestNeighbor = findNearestNeighbor(rarestColor, freq, colorRgb);
            if (bestNeighbor == rarestColor) break;

            // Replace all pixels of the rarest color with its nearest neighbor
            byte from = (byte) rarestColor;
            byte to = (byte) bestNeighbor;
            for (int i = 0; i < mapColors.length; i++) {
                if (mapColors[i] == from) {
                    mapColors[i] = to;
                }
            }
            colorsRemoved++;
            pixelsAffected += rarestFreq;

            // Re-compress and check
            compressed = Zstd.compress(mapColors, Zstd.maxCompressionLevel());
            chunks = chunksNeeded(compressed, chunkSize);
        }

        return new FitResult(mapColors, compressed, colorsRemoved, originalDistinct, pixelsAffected);
    }

    /**
     * Builds an array mapping each possible map-color byte (0–255) to its
     * rendered RGB value, for fast distance lookups.
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

            // Find the Brightness with matching id
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
     * Finds the color byte (among those currently in use) that is visually
     * nearest to the given color, by squared RGB distance.
     */
    private static int findNearestNeighbor(int color, int[] freq, int[] colorRgb) {
        int cr = (colorRgb[color] >> 16) & 0xFF;
        int cg = (colorRgb[color] >>  8) & 0xFF;
        int cb =  colorRgb[color]        & 0xFF;

        int bestDist = Integer.MAX_VALUE;
        int bestColor = color;

        for (int c = 1; c < 256; c++) {
            if (c == color || freq[c] == 0) continue;
            if (colorRgb[c] == 0) continue;

            int nr = (colorRgb[c] >> 16) & 0xFF;
            int ng = (colorRgb[c] >>  8) & 0xFF;
            int nb =  colorRgb[c]        & 0xFF;

            int dr = cr - nr, dg = cg - ng, db = cb - nb;
            int dist = dr * dr + dg * dg + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                bestColor = c;
            }
        }
        return bestColor;
    }

    public static int countDistinct(byte[] mapColors) {
        boolean[] seen = new boolean[256];
        for (byte b : mapColors) {
            seen[b & 0xFF] = true;
        }
        int count = 0;
        for (int i = 1; i < 256; i++) { // skip 0 = transparent
            if (seen[i]) count++;
        }
        return count;
    }

    public static int chunksNeeded(byte[] compressed, int chunkSize) {
        String base64 = Base64.getEncoder().encodeToString(compressed);
        return (base64.length() + chunkSize - 1) / chunkSize;
    }

    private static byte findClosestMapColorByte(int argb, boolean legalOnly) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha < 128) return 0;

        int pr = (argb >> 16) & 0xFF;
        int pg = (argb >>  8) & 0xFF;
        int pb =  argb        & 0xFF;

        double bestDist = Double.MAX_VALUE;
        byte bestByte = 0;

        for (int id = 1; id < MAX_COLOR_ID; id++) {
            MapColor mc = MapColor.get(id);
            if (mc == null || mc.color == 0) continue;

            for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
                if (legalOnly && brightness.id == UNOBTAINABLE_SHADE_ID) continue;

                int rgb = mc.getRenderColor(brightness);
                int mr = (rgb >> 16) & 0xFF;
                int mg = (rgb >>  8) & 0xFF;
                int mb =  rgb        & 0xFF;

                int dr = pr - mr, dg = pg - mg, db = pb - mb;
                double dist = dr * dr + dg * dg + db * db;

                if (dist < bestDist) {
                    bestDist = dist;
                    bestByte = mc.getRenderColorByte(brightness);
                }
            }
        }
        return bestByte;
    }
}