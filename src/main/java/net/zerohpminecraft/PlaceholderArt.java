package net.zerohpminecraft;

/**
 * Renders 128×128 map-colour placeholder screens for tiles that can't show their art yet:
 * a progress screen while AV1 streams decode, a waiting screen while a composite gathers its
 * sibling tiles, a lock screen for encrypted tiles without a matching password, and an error
 * screen for tiles whose decode failed.
 *
 * <p>Everything is drawn procedurally into map palette bytes (colours are nearest-matched
 * against {@link MapPalette#RGB_ENTRIES} once at class init), so a placeholder is just another
 * frame for {@code paintMap} — no textures, no render hooks.
 */
public final class PlaceholderArt {

    private static final int SIZE = 128;

    private PlaceholderArt() {}

    // ── Palette colours (resolved once against the real map palette) ──────

    private static final byte BG       = nearest(28, 28, 34);    // panel background
    private static final byte FRAME    = nearest(105, 105, 115); // panel border
    private static final byte TEXT     = nearest(255, 255, 255);
    private static final byte DIM      = nearest(160, 160, 160);
    private static final byte BAR_BG   = nearest(60, 60, 66);
    private static final byte BAR_FILL = nearest(90, 190, 90);
    private static final byte GOLD     = nearest(230, 180, 40);
    private static final byte RED      = nearest(215, 65, 45);

    /** Nearest valid map byte for an RGB colour (squared-distance over RGB_ENTRIES). */
    private static byte nearest(int r, int g, int b) {
        byte[] p = MapPalette.RGB_ENTRIES;
        byte best = 0;
        int bestSq = Integer.MAX_VALUE;
        for (int i = 0; i < p.length; i += 4) {
            int dr = (p[i + 1] & 0xFF) - r, dg = (p[i + 2] & 0xFF) - g, db = (p[i + 3] & 0xFF) - b;
            int sq = dr * dr + dg * dg + db * db;
            if (sq < bestSq) { bestSq = sq; best = p[i]; }
        }
        return best;
    }

    // ── Public screens ─────────────────────────────────────────────────────

    /** Composite tile waiting for its sibling tiles to be scanned. */
    public static byte[] waiting(int seen, int total) {
        byte[] c = panel();
        text(c, "WAITING", 30, TEXT);
        text(c, "TILES " + seen + "/" + total, 48, DIM);
        segmentBar(c, 16, 70, 96, 10, seen, total);
        text(c, "SCAN ALL TILES", 94, DIM);
        return c;
    }

    /** AV1 stream decoding — progress out of {@code total} frames. */
    public static byte[] decoding(int done, int total) {
        byte[] c = panel();
        text(c, "DECODING", 34, TEXT);
        float frac = total > 0 ? Math.min(1f, (float) done / total) : 0f;
        progressBar(c, 16, 60, 96, 12, frac);
        text(c, (int) (frac * 100) + "%", 82, DIM);
        return c;
    }

    /** Encrypted tile with no matching password. */
    public static byte[] locked() {
        byte[] c = panel();
        lockIcon(c, 64, 46);
        text(c, "PASSWORD", 96, TEXT);
        text(c, "REQUIRED", 110, DIM);
        return c;
    }

    /** Decode/processing failed — direct the user to the log. */
    public static byte[] error() {
        byte[] c = panel();
        warnIcon(c, 64, 44);
        text(c, "ERROR", 78, RED);
        text(c, "CHECK LOGS", 94, DIM);
        return c;
    }

    // ── Canvas helpers ─────────────────────────────────────────────────────

    private static byte[] panel() {
        byte[] c = new byte[SIZE * SIZE];
        fillRect(c, 0, 0, SIZE, SIZE, BG);
        rect(c, 2, 2, SIZE - 4, SIZE - 4, FRAME);
        return c;
    }

    private static void fillRect(byte[] c, int x, int y, int w, int h, byte col) {
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(SIZE, x + w), y2 = Math.min(SIZE, y + h);
        for (int py = y1; py < y2; py++)
            for (int px = x1; px < x2; px++)
                c[py * SIZE + px] = col;
    }

    /** 1-px outline rectangle. */
    private static void rect(byte[] c, int x, int y, int w, int h, byte col) {
        fillRect(c, x, y, w, 1, col);
        fillRect(c, x, y + h - 1, w, 1, col);
        fillRect(c, x, y, 1, h, col);
        fillRect(c, x + w - 1, y, 1, h, col);
    }

    private static void progressBar(byte[] c, int x, int y, int w, int h, float frac) {
        rect(c, x, y, w, h, FRAME);
        fillRect(c, x + 1, y + 1, w - 2, h - 2, BAR_BG);
        int fill = Math.round((w - 2) * Math.max(0f, Math.min(1f, frac)));
        if (fill > 0) fillRect(c, x + 1, y + 1, fill, h - 2, BAR_FILL);
    }

    /** One segment per expected item when they fit; a continuous bar otherwise. */
    private static void segmentBar(byte[] c, int x, int y, int w, int h, int done, int total) {
        if (total < 2 || total > 24) {
            progressBar(c, x, y, w, h, total > 0 ? (float) done / total : 0f);
            return;
        }
        int gap = 2;
        int segW = (w - gap * (total - 1)) / total;
        int used = segW * total + gap * (total - 1);
        int sx = x + (w - used) / 2;
        for (int i = 0; i < total; i++) {
            fillRect(c, sx, y, segW, h, i < done ? BAR_FILL : BAR_BG);
            rect(c, sx, y, segW, h, FRAME);
            sx += segW + gap;
        }
    }

    private static void lockIcon(byte[] c, int cx, int cy) {
        // Shackle: ring upper half, 4 px thick, radius 13.
        for (int py = cy - 18; py <= cy; py++) {
            for (int px = cx - 18; px <= cx + 18; px++) {
                double d = Math.hypot(px - cx, py - (cy - 2));
                if (d >= 9 && d <= 13 && py <= cy - 2)
                    set(c, px, py, GOLD);
            }
        }
        fillRect(c, cx - 13, cy - 2, 4, 8, GOLD);  // shackle legs down into the body
        fillRect(c, cx + 10, cy - 2, 4, 8, GOLD);
        // Body with keyhole.
        fillRect(c, cx - 19, cy + 4, 38, 26, GOLD);
        rect(c, cx - 19, cy + 4, 38, 26, FRAME);
        for (int py = cy + 9; py <= cy + 15; py++)
            for (int px = cx - 3; px <= cx + 3; px++)
                if (Math.hypot(px - cx, py - (cy + 12)) <= 3.2) set(c, px, py, BG);
        fillRect(c, cx - 1, cy + 14, 3, 10, BG);   // keyhole slit
    }

    private static void warnIcon(byte[] c, int cx, int cy) {
        // Filled triangle, apex up: height 40, base half-width 24.
        int top = cy - 22, bottom = cy + 18;
        for (int py = top; py <= bottom; py++) {
            int half = Math.round(24f * (py - top) / (bottom - top));
            fillRect(c, cx - half, py, half * 2 + 1, 1, RED);
        }
        // Exclamation mark in panel colour.
        fillRect(c, cx - 2, cy - 10, 4, 18, BG);
        fillRect(c, cx - 2, cy + 12, 4, 4, BG);
    }

    private static void set(byte[] c, int x, int y, byte col) {
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) c[y * SIZE + x] = col;
    }

    // ── Tiny 3×5 font, drawn at 2× (6×10 glyphs, 8 px advance) ────────────

    /** Draws {@code s} horizontally centred with the glyph top at {@code y}. */
    private static void text(byte[] c, String s, int y, byte col) {
        int w = s.length() * 8 - 2;
        int x = (SIZE - w) / 2;
        for (int i = 0; i < s.length(); i++) {
            glyph(c, s.charAt(i), x + i * 8, y, col);
        }
    }

    private static void glyph(byte[] c, char ch, int x, int y, byte col) {
        int bits = glyphBits(ch);
        for (int row = 0; row < 5; row++) {
            int r = (bits >> ((4 - row) * 3)) & 0b111;
            for (int colIdx = 0; colIdx < 3; colIdx++) {
                if ((r & (0b100 >> colIdx)) != 0)
                    fillRect(c, x + colIdx * 2, y + row * 2, 2, 2, col);
            }
        }
    }

    /** 15-bit glyph: 5 rows × 3 columns, MSB = top-left. */
    private static int glyphBits(char ch) {
        return switch (Character.toUpperCase(ch)) {
            case 'A' -> 0b010_101_111_101_101;
            case 'B' -> 0b110_101_110_101_110;
            case 'C' -> 0b011_100_100_100_011;
            case 'D' -> 0b110_101_101_101_110;
            case 'E' -> 0b111_100_110_100_111;
            case 'F' -> 0b111_100_110_100_100;
            case 'G' -> 0b011_100_101_101_011;
            case 'H' -> 0b101_101_111_101_101;
            case 'I' -> 0b111_010_010_010_111;
            case 'J' -> 0b001_001_001_101_010;
            case 'K' -> 0b101_101_110_101_101;
            case 'L' -> 0b100_100_100_100_111;
            case 'M' -> 0b101_111_111_101_101;
            case 'N' -> 0b110_101_101_101_101;
            case 'O' -> 0b010_101_101_101_010;
            case 'P' -> 0b110_101_110_100_100;
            case 'Q' -> 0b010_101_101_010_001;
            case 'R' -> 0b110_101_110_101_101;
            case 'S' -> 0b011_100_010_001_110;
            case 'T' -> 0b111_010_010_010_010;
            case 'U' -> 0b101_101_101_101_111;
            case 'V' -> 0b101_101_101_101_010;
            case 'W' -> 0b101_101_111_111_101;
            case 'X' -> 0b101_101_010_101_101;
            case 'Y' -> 0b101_101_010_010_010;
            case 'Z' -> 0b111_001_010_100_111;
            case '0' -> 0b111_101_101_101_111;
            case '1' -> 0b010_110_010_010_111;
            case '2' -> 0b111_001_111_100_111;
            case '3' -> 0b111_001_111_001_111;
            case '4' -> 0b101_101_111_001_001;
            case '5' -> 0b111_100_111_001_111;
            case '6' -> 0b111_100_111_101_111;
            case '7' -> 0b111_001_001_010_010;
            case '8' -> 0b111_101_111_101_111;
            case '9' -> 0b111_101_111_001_111;
            case '/' -> 0b001_001_010_100_100;
            case '%' -> 0b101_001_010_100_101;
            case '!' -> 0b010_010_010_000_010;
            case '-' -> 0b000_000_111_000_000;
            case '.' -> 0b000_000_000_000_010;
            default  -> 0;  // space and anything unknown
        };
    }
}
