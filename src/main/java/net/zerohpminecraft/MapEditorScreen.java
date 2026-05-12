package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.zerohpminecraft.command.LoominaryCommand;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-world map pixel editor — Phases 1–8 + Eyedropper.
 *
 * Phase 1: viewer skeleton (pan / zoom / hover)
 * Phase 2: undo/redo (Ctrl+Z/Y, 20-level) + left-click paint
 * Phase 3: palette panel (tile colours by frequency) + right-click eyedropper
 * Phase 4: variable brush radius (Shift+scroll / [/]) + fill bucket (F)
 * Phase 5: rectangle selection (S) with marching-ants overlay;
 *          brush and fill constrained to active selection;
 *          Ctrl+A = select all, Ctrl+D / Esc = deselect
 * Phase 6: re-quantize selection from source (R);
 *          D toggles dither; Enter/Y commits preview, Esc cancels
 * Phase 7: dither-strength brush (T); paints float[16384] mask 0–1;
 *          M shows overlay; Shift+scroll adjusts paint strength (0–1);
 *          re-quantize uses mask instead of Otsu when it has been painted
 * Phase 8: lasso selection (L) and magic-wand selection (W);
 *          selection refactored from rect to boolean[16384] mask
 * Eyedropper: dedicated tool (E); left-click picks colour and auto-reverts;
 *             right-click eyedropper still works on all other tools
 *
 * On close, if dirty, the active tile is re-encoded and PayloadState updated.
 */
public class MapEditorScreen extends Screen {

    // ── Layout constants ────────────────────────────────────────────────
    private static final int MAP_SIZE        = PngToMapColors.MAP_SIZE;
    private static final int MAP_BYTES       = MAP_SIZE * MAP_SIZE;
    private static final int STATUS_H        = 14;
    private static final int PANEL_W         = 80;
    private static final int GUIDE_W         = 100;
    private static final int SWATCH_SZ       = 12;
    private static final int SWATCH_GAP      = 4;   // extra 2px used for frequency bar
    private static final int SWATCH_STRIDE   = SWATCH_SZ + SWATCH_GAP;
    private static final int PANEL_MARGIN    = 2;
    private static final int SWATCHES_PER_ROW = (PANEL_W - PANEL_MARGIN * 2) / SWATCH_STRIDE;
    private static final int SWATCHES_START_Y = 79;
    private static final int ACTIVE_SWATCH_SZ = 20;
    private static final int CHUNK_SIZE      = 48;
    private static final int MIN_SCALE       = 1;
    private static final int MAX_SCALE       = 16;
    private static final int MAX_BRUSH       = 10;
    private static final int UNDO_DEPTH      = 20;

    // ── Minimap constants ────────────────────────────────────────────────
    private static final int THUMB_SZ        = 32;   // px per thumbnail
    private static final int MINI_GAP        = 4;    // gap between thumbnails
    private static final int MINI_PAD        = 8;    // panel padding
    private static final int MINI_HEADER_H   = 14;   // header row height

    // ── UI colours ──────────────────────────────────────────────────────
    private static final int COL_BG          = 0xFF1A1A1A;
    private static final int COL_PANEL_BG    = 0xFF141414;
    private static final int COL_PANEL_SEP   = 0xFF333333;
    private static final int COL_STATUS_BG   = 0xFF121212;
    private static final int COL_STATUS_SEP  = 0xFF444444;
    private static final int COL_MAP_BORDER  = 0xFFAAAAAA;
    private static final int COL_TRANS_A     = 0xFFAAAAAA; // checkerboard light (transparent pixels)
    private static final int COL_TRANS_B     = 0xFF555555; // checkerboard dark  (transparent pixels)
    private static final int COL_RING        = 0xFFFFFFFF;
    private static final int COL_HOVER_RING  = 0xFF888888;
    private static final int COL_MERGE_RING  = 0xFFFF7722;  // orange — merge source

    // ── Tool enum ───────────────────────────────────────────────────────
    private enum Tool { BRUSH, FILL, SELECT, EYEDROPPER, DITHER_BRUSH, LASSO, MAGIC_WAND }
    private enum BrushShape { SQUARE, CIRCLE }

    // ── State ───────────────────────────────────────────────────────────
    byte[] mapColors;          // points into allFrames[activeFrame]
    boolean dirty = false;

    private byte[][] allFrames;    // one entry per animation frame (always ≥ 1)
    private int      activeFrame  = 0;
    private int      totalFrames  = 1;
    private int[]    frameDelayMs;  // per-frame delay in ms (length == totalFrames)

    private final int[]  colorLookup;
    private int          tileIndex;
    private String       tileLabel;

    // Minimap
    private boolean showMinimap   = false;
    private byte[][] thumbnails   = null;  // null = not yet computed

    // Navigation
    private int   scale;
    private float translateX, translateY;
    private boolean panning;
    private double  lastPanMouseX, lastPanMouseY;

    // Hover
    private int hoverMapX = -1, hoverMapY = -1;

    // Tools
    private Tool    currentTool   = Tool.BRUSH;
    private Tool    prevTool      = Tool.BRUSH;  // eyedropper auto-revert target
    private int     activeColor   = 1;
    private int     brushRadius   = 0;
    private BrushShape brushShape = BrushShape.SQUARE;
    private float   fillTolerance = 0f;
    private boolean stroking      = false;

    // Selection (Phase 5, refactored to boolean mask in Phase 8)
    private boolean[] selMask    = null;  // null = no selection
    private boolean   selecting  = false;
    private int       dragOriginX, dragOriginY;

    // Lasso (Phase 8)
    private List<int[]> lassoPoints = null;

    private final EditHistory history = new EditHistory();

    // Palette panel
    private int[]   tileColors;
    private int[]   tileColorFreqs = new int[0];  // parallel to tileColors: pixel count per color
    private int     maxTileFreq    = 0;
    private int[]   allMapColors;
    private boolean paletteShowAll = false;

    // Color-merge state: Ctrl+click swatches or canvas pixels to build the source set, C to commit
    private final Set<Integer> mergeSources = new LinkedHashSet<>();
    private enum MergeScope { FRAME, TILE, ALL_TILES }
    private MergeScope mergeScope = MergeScope.FRAME;

    // In-editor filter (Shift+P cycle type, P apply to current frame)
    private static PngToMapColors.FilterParams.FilterType editorFilterType =
            PngToMapColors.FilterParams.FilterType.SMOOTH;

    // Phase 6 — re-quantize from source (R key)
    private PngToMapColors.DitherAlgo editorDitherAlgo;
    private float     fsErrorStrength    = 1.0f;   // FS error diffusion amount (0.1–1.0)
    private boolean   requantizeTilePalette = false; // restrict R to colors already in tile
    private BufferedImage cachedRequantizeSrc  = null; // source image cached for live preview refresh
    private BufferedImage[] cachedGifFrames   = null; // all coalesced GIF frames for the active source
    private PngToMapColors.MatchMetric matchMetric = PngToMapColors.MatchMetric.OKLAB;

    // In-editor color reduction (K = one step, Shift+K = cycle strategy)
    private static PngToMapColors.Strategy editorReductionStrategy = PngToMapColors.Strategy.RAREST;

    // Requantize source frame — the GIF frame index passed to coalesceGifFrames().
    // Derived from tile.frameSourceIndices[activeFrame] when provenance exists;
    // can be overridden manually with Shift+[ / Shift+].
    private int requantizeSourceFrame = 0;
    // Total original GIF frames (max(frameSourceIndices)+1); -1 when unknown.
    private int gifSourceFrameCount   = -1;

    // Budget badge cache (recomputed in rebuildTileColors)
    private int  cachedDistinctCount = 0;
    private int  cachedBudgetUsed    = 0;
    private int  cachedBudgetMax     = 63;
    private boolean cachedIsCarpet   = false;

    private boolean   inPreview       = false;
    private byte[]    previewColors   = null;
    private boolean[] previewSelMask  = null;  // null = whole tile

    // Phase 7 — dither-strength brush (T key)
    // mask = null while unpainted; first stroke initialises all-1.0 then paints.
    private float[]  ditherMask     = null;
    private boolean  useCustomMask  = false;
    private float    ditherStrength = 1.0f;
    private boolean  showMask       = false;

    // Compression heatmap: [H] toggle — red=rarest color, blue=most-frequent
    private boolean       heatmapMode = false;
    private final int[]   heatLut     = new int[256]; // ARGB per color byte, rebuilt with palette

    // Temporary status message (errors / hints; expires after 3 s)
    private String statusMsg       = null;
    private long   statusMsgExpiry = 0;

    // Background-blur suppression — captured once in constructor
    private final int savedBlur;

    // ── Constructor ─────────────────────────────────────────────────────

    /** Single-frame constructor (static tiles and backward compatibility). */
    public MapEditorScreen(byte[] mapColors, int tileIndex, String tileLabel) {
        this(new byte[][]{mapColors.clone()}, tileIndex, tileLabel);
    }

    /** Multi-frame constructor for animated tiles. */
    public MapEditorScreen(byte[][] frames, int tileIndex, String tileLabel) {
        super(Text.literal("Loominary Editor"));
        this.allFrames    = frames;
        this.totalFrames  = frames.length;
        this.mapColors    = frames[0];
        this.tileIndex    = tileIndex;
        this.tileLabel    = tileLabel;
        this.colorLookup  = PngToMapColors.buildColorLookup();
        this.editorDitherAlgo = PayloadState.dither
                ? PngToMapColors.DitherAlgo.FLOYD_STEINBERG
                : PngToMapColors.DitherAlgo.NONE;
        // Load per-frame delays from tile state
        this.frameDelayMs = new int[frames.length];
        if (!PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size()) {
            PayloadState.TileData tile = PayloadState.tiles.get(tileIndex);
            List<Integer> stored = tile.frameDelays;
            if (stored != null && !stored.isEmpty()) {
                for (int i = 0; i < frames.length; i++) {
                    // pad with last stored delay if tile had fewer entries (e.g. after drop)
                    frameDelayMs[i] = stored.get(Math.min(i, stored.size() - 1));
                }
            } else {
                Arrays.fill(frameDelayMs, 100);
            }
            // Initialize requantize source frame from provenance.
            if (tile.frameSourceIndices != null && !tile.frameSourceIndices.isEmpty()) {
                requantizeSourceFrame = tile.frameSourceIndices.get(0);
                gifSourceFrameCount   = tile.frameSourceIndices.stream()
                        .mapToInt(Integer::intValue).max().orElse(0) + 1;
            } else {
                requantizeSourceFrame = 0;
                gifSourceFrameCount   = -1;
            }
        } else {
            Arrays.fill(frameDelayMs, 100);
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        this.savedBlur = mc.options.getMenuBackgroundBlurriness().getValue();
        mc.options.getMenuBackgroundBlurriness().setValue(0);
    }

    // ── Screen lifecycle ────────────────────────────────────────────────

    @Override
    protected void init() {
        int canvasW = width  - PANEL_W - GUIDE_W;
        int canvasH = height - STATUS_H;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                Math.min(canvasW, canvasH) / MAP_SIZE));
        centerMap();
        rebuildTileColors();
        allMapColors = computeAllMapColors();
        if (tileColors.length > 0) activeColor = tileColors[0];
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (dirty) reEncode();
        MinecraftClient.getInstance().options.getMenuBackgroundBlurriness().setValue(savedBlur);
        super.close();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int canvasW = width  - PANEL_W;
        int canvasH = height - STATUS_H;
        if (mouseX >= GUIDE_W && mouseX < canvasW && mouseY < canvasH) {
            hoverMapX = screenToMap((float) mouseX - translateX);
            hoverMapY = screenToMap((float) mouseY - translateY);
        } else {
            hoverMapX = hoverMapY = -1;
        }

        ctx.fill(0, 0, width, height, COL_BG);
        renderMap(ctx);
        renderDitherMaskOverlay(ctx);
        renderToolOverlay(ctx);
        renderSelectionOverlay(ctx);
        renderLassoPath(ctx);
        renderMapBorder(ctx);
        renderPalettePanel(ctx, mouseX, mouseY);
        renderStatusBar(ctx);
        renderGuidePanel(ctx);
        if (showMinimap) renderMinimap(ctx, mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderMap(DrawContext ctx) {
        int canvasH = height - STATUS_H;
        int minPx = Math.max(0,         screenToMap(-translateX));
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width - PANEL_W - translateX));
        int minPy = Math.max(0,         screenToMap(-translateY));
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY));

        boolean usePreview = inPreview && previewColors != null;
        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                int idx = px + py * MAP_SIZE;
                boolean showPrev = usePreview
                        && (previewSelMask == null || previewSelMask[idx]);
                int cb   = (showPrev ? previewColors : mapColors)[idx] & 0xFF;
                int argb = cb == 0 ? ((px + py) % 2 == 0 ? COL_TRANS_A : COL_TRANS_B)
                        : (heatmapMode ? heatLut[cb] : (0xFF000000 | colorLookup[cb]));
                int sx = (int)(translateX + px * scale);
                int sy = (int)(translateY + py * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, argb);
            }
        }
    }

    /** Yellow heat overlay showing the dither-strength mask (0 = invisible, 1 = opaque). */
    private void renderDitherMaskOverlay(DrawContext ctx) {
        if (ditherMask == null) return;
        if (!showMask && currentTool != Tool.DITHER_BRUSH) return;
        int canvasH = height - STATUS_H;
        int minPx = Math.max(0,         screenToMap(-translateX));
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width - PANEL_W - translateX));
        int minPy = Math.max(0,         screenToMap(-translateY));
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY));
        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                float s = ditherMask[px + py * MAP_SIZE];
                if (s <= 0f) continue;
                int alpha = (int)(s * 90);
                int sx = (int)(translateX + px * scale);
                int sy = (int)(translateY + py * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, (alpha << 24) | 0x00FFCC00);
            }
        }
    }

    private void renderToolOverlay(DrawContext ctx) {
        if (!inMapBounds(hoverMapX, hoverMapY)) return;
        int sx = (int)(translateX + hoverMapX * scale);
        int sy = (int)(translateY + hoverMapY * scale);

        switch (currentTool) {
            case BRUSH -> {
                int activeRgb = activeColor == 0
                        ? (COL_TRANS_A & 0xFFFFFF) : colorLookup[activeColor];
                int overlay = (activeRgb & 0x00FFFFFF) | 0x99000000;
                int r2 = brushRadius * brushRadius;
                for (int dy = -brushRadius; dy <= brushRadius; dy++) {
                    for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                        if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                        int px = hoverMapX + dx, py = hoverMapY + dy;
                        if (!inMapBounds(px, py)) continue;
                        if (selMask != null && !selMask[px + py * MAP_SIZE]) continue;
                        int bsx = (int)(translateX + px * scale);
                        int bsy = (int)(translateY + py * scale);
                        ctx.fill(bsx, bsy, bsx + scale, bsy + scale, overlay);
                    }
                }
            }
            case DITHER_BRUSH -> {
                int r2 = brushRadius * brushRadius;
                for (int dy = -brushRadius; dy <= brushRadius; dy++) {
                    for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                        if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                        int px = hoverMapX + dx, py = hoverMapY + dy;
                        if (!inMapBounds(px, py)) continue;
                        int bsx = (int)(translateX + px * scale);
                        int bsy = (int)(translateY + py * scale);
                        ctx.fill(bsx, bsy, bsx + scale, bsy + scale, 0x66FFCC00);
                    }
                }
            }
            case FILL -> {
                if (scale >= 3) {
                    ctx.fill(sx,             sy,              sx + scale,  sy + 1,         0xFFFFFFFF);
                    ctx.fill(sx,             sy + scale - 1,  sx + scale,  sy + scale,     0xFFFFFFFF);
                    ctx.fill(sx,             sy + 1,          sx + 1,      sy + scale - 1, 0xFFFFFFFF);
                    ctx.fill(sx + scale - 1, sy + 1,          sx + scale,  sy + scale - 1, 0xFFFFFFFF);
                } else {
                    ctx.fill(sx, sy, sx + scale, sy + scale, 0x66FFFFFF);
                }
            }
            case SELECT, LASSO, MAGIC_WAND, EYEDROPPER -> {
                int cx = sx + scale / 2, cy = sy + scale / 2;
                ctx.fill(cx - 3, cy, cx + 4, cy + 1, 0x99FFFFFF);
                ctx.fill(cx, cy - 3, cx + 1, cy + 4, 0x99FFFFFF);
            }
        }
    }

    /** Renders the selection mask as a tint + marching-ants border. */
    private void renderSelectionOverlay(DrawContext ctx) {
        if (selMask == null) return;
        int canvasH = height - STATUS_H;
        int minPx = Math.max(0,         screenToMap(-translateX));
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width - PANEL_W - translateX));
        int minPy = Math.max(0,         screenToMap(-translateY));
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY));

        // Tint — run-length-encoded by row for efficiency
        for (int py = minPy; py <= maxPy; py++) {
            int runStart = -1;
            for (int px = minPx; px <= maxPx + 1; px++) {
                boolean sel = px <= maxPx && selMask[px + py * MAP_SIZE];
                if (sel && runStart < 0) {
                    runStart = px;
                } else if (!sel && runStart >= 0) {
                    ctx.fill((int)(translateX + runStart * scale),
                             (int)(translateY + py * scale),
                             (int)(translateX + px * scale),
                             (int)(translateY + py * scale) + scale, 0x33FFFFFF);
                    runStart = -1;
                }
            }
        }

        // Marching-ants border — one segment per exposed edge
        int off = (int)((System.currentTimeMillis() / 120) % 8);
        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                if (!selMask[px + py * MAP_SIZE]) continue;
                int bsx = (int)(translateX + px * scale);
                int bsy = (int)(translateY + py * scale);
                if (py == 0          || !selMask[px + (py-1) * MAP_SIZE]) {
                    int p = ((px + off) % 8 + 8) % 8;
                    ctx.fill(bsx, bsy-1, bsx+scale, bsy, p < 4 ? 0xFFFFFFFF : 0xFF222222);
                }
                if (py == MAP_SIZE-1 || !selMask[px + (py+1) * MAP_SIZE]) {
                    int p = ((px + off) % 8 + 8) % 8;
                    ctx.fill(bsx, bsy+scale, bsx+scale, bsy+scale+1, p < 4 ? 0xFFFFFFFF : 0xFF222222);
                }
                if (px == 0          || !selMask[(px-1) + py * MAP_SIZE]) {
                    int p = ((py + off) % 8 + 8) % 8;
                    ctx.fill(bsx-1, bsy, bsx, bsy+scale, p < 4 ? 0xFFFFFFFF : 0xFF222222);
                }
                if (px == MAP_SIZE-1 || !selMask[(px+1) + py * MAP_SIZE]) {
                    int p = ((py + off) % 8 + 8) % 8;
                    ctx.fill(bsx+scale, bsy, bsx+scale+1, bsy+scale, p < 4 ? 0xFFFFFFFF : 0xFF222222);
                }
            }
        }
    }

    /** Draws the in-progress lasso path as yellow dots. */
    private void renderLassoPath(DrawContext ctx) {
        if (lassoPoints == null || lassoPoints.isEmpty()) return;
        for (int[] pt : lassoPoints) {
            if (!inMapBounds(pt[0], pt[1])) continue;
            int sx = (int)(translateX + pt[0] * scale);
            int sy = (int)(translateY + pt[1] * scale);
            int sz = Math.max(1, scale / 2);
            ctx.fill(sx, sy, sx + sz, sy + sz, 0xFFFFFF00);
        }
    }

    private void renderMapBorder(DrawContext ctx) {
        int mx = (int) translateX, my = (int) translateY;
        int mw = MAP_SIZE * scale,  mh = MAP_SIZE * scale;
        ctx.fill(mx-1, my-1,  mx+mw+1, my,      COL_MAP_BORDER);
        ctx.fill(mx-1, my+mh, mx+mw+1, my+mh+1, COL_MAP_BORDER);
        ctx.fill(mx-1, my,    mx,       my+mh,   COL_MAP_BORDER);
        ctx.fill(mx+mw, my,   mx+mw+1,  my+mh,   COL_MAP_BORDER);
    }

    private void renderPalettePanel(DrawContext ctx, int mouseX, int mouseY) {
        int panelX = width - PANEL_W;
        int panelH = height - STATUS_H;

        ctx.fill(panelX, 0, width, panelH, COL_PANEL_BG);
        ctx.fill(panelX, 0, panelX + 1, panelH, COL_PANEL_SEP);

        StringBuilder phBuilder = new StringBuilder("§7PALETTE");
        if (heatmapMode) phBuilder.append(" §c[HEAT]");
        if (!mergeSources.isEmpty())
            phBuilder.append(" §c[").append(mergeSources.size())
                    .append(" merge src").append(mergeSources.size() == 1 ? "" : "s").append("]");
        String paletteHeader = phBuilder.toString();
        ctx.drawTextWithShadow(textRenderer, Text.literal(paletteHeader), panelX + 4, 3, 0xFFFFFF);

        // Budget / color count badge
        String budgetColor = (cachedBudgetUsed > cachedBudgetMax) ? "§c" : "§a";
        String budgetBadge = budgetColor + cachedBudgetUsed + "§r§8/" + cachedBudgetMax;
        String badgeLine = "§8" + cachedDistinctCount + "c  " + budgetBadge;
        if (selMask != null) {
            boolean[] inSel = new boolean[256];
            for (int i = 0; i < MAP_BYTES; i++) if (selMask[i]) inSel[mapColors[i] & 0xFF] = true;
            int sd = 0; for (int c = 1; c < 256; c++) if (inSel[c]) sd++;
            badgeLine += "  §8sel:" + sd;
        }
        ctx.drawTextWithShadow(textRenderer, Text.literal(badgeLine), panelX + 4, 12, 0xFFFFFF);
        ctx.fill(panelX, 22, width, 23, COL_PANEL_SEP);

        int activeRgb = activeColor == 0
                ? (COL_TRANS_A & 0xFFFFFF) : colorLookup[activeColor];
        int asx = panelX + 4, asy = 26;
        ctx.fill(asx-1, asy-1, asx+ACTIVE_SWATCH_SZ+1, asy+ACTIVE_SWATCH_SZ+1, COL_RING);
        ctx.fill(asx, asy, asx+ACTIVE_SWATCH_SZ, asy+ACTIVE_SWATCH_SZ, 0xFF000000|activeRgb);
        String hexStr = activeColor == 0
                ? "trans" : String.format("#%06X", activeRgb & 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + hexStr), panelX+28, 28, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8idx:"+activeColor), panelX+28, 37, 0xFFFFFF);

        ctx.fill(panelX, 50, width, 51, COL_PANEL_SEP);

        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Show: "), panelX+4, 54, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(paletteShowAll ? "§eTile§r" : "§7All"),
                panelX+40, 54, 0xFFFFFF);

        ctx.fill(panelX, 66, width, 67, COL_PANEL_SEP);

        int[] palette = paletteShowAll ? allMapColors : tileColors;
        for (int i = 0; i < palette.length; i++) {
            int row = i / SWATCHES_PER_ROW, col = i % SWATCHES_PER_ROW;
            int swx = panelX + PANEL_MARGIN + col * SWATCH_STRIDE;
            int swy = SWATCHES_START_Y + row * SWATCH_STRIDE;
            if (swy + SWATCH_SZ > panelH) break;

            int c   = palette[i];
            int rgb = c == 0 ? (COL_TRANS_A & 0xFFFFFF) : colorLookup[c];
            boolean isActive  = (c == activeColor);
            boolean isMerge   = mergeSources.contains(c);
            boolean isHovered = mouseX >= swx && mouseX < swx+SWATCH_SZ
                             && mouseY >= swy && mouseY < swy+SWATCH_SZ;
            int ringCol = isMerge ? COL_MERGE_RING : (isActive ? COL_RING : COL_HOVER_RING);
            if (isActive || isHovered || isMerge)
                ctx.fill(swx-1, swy-1, swx+SWATCH_SZ+1, swy+SWATCH_SZ+1, ringCol);
            ctx.fill(swx, swy, swx+SWATCH_SZ, swy+SWATCH_SZ, 0xFF000000|rgb);

            // Frequency bar: 2px strip in the gap below the swatch (tile-color mode only)
            if (!paletteShowAll && i < tileColorFreqs.length && maxTileFreq > 0) {
                int barW = Math.max(1, SWATCH_SZ * tileColorFreqs[i] / maxTileFreq);
                ctx.fill(swx, swy + SWATCH_SZ + 1, swx + barW, swy + SWATCH_SZ + 3, 0xFF2A6644);
            }
        }
    }

    private void renderGuidePanel(DrawContext ctx) {
        int panelH = height - STATUS_H;
        ctx.fill(0, 0, GUIDE_W, panelH, COL_PANEL_BG);
        ctx.fill(GUIDE_W - 1, 0, GUIDE_W, panelH, COL_PANEL_SEP);

        int x = 4, y = 3;

        ctx.drawTextWithShadow(textRenderer, Text.literal("§7GUIDE"), x, y, 0xFFFFFF);
        y += 11;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;

        // Tool list — active tool highlighted green
        Tool[]   toolOrder  = { Tool.BRUSH, Tool.FILL, Tool.SELECT, Tool.EYEDROPPER,
                                 Tool.DITHER_BRUSH, Tool.LASSO, Tool.MAGIC_WAND };
        String[] toolKeys   = { "B", "F", "S", "E", "T", "L", "W" };
        String[] toolLabels = { "Brush", "Fill", "Rect Sel", "Eyedrop",
                                 "Dither", "Lasso", "Wand" };
        for (int i = 0; i < toolOrder.length; i++) {
            String col = currentTool == toolOrder[i] ? "§a" : "§8";
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(col + "[" + toolKeys[i] + "] " + toolLabels[i]), x, y, 0xFFFFFF);
            y += 9;
        }
        y += 2;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;

        // Contextual hints for the active tool
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Options"), x, y, 0xFFFFFF); y += 10;
        String sq = brushShape == BrushShape.SQUARE ? "§asq§r" : "§8sq";
        String ci = brushShape == BrushShape.CIRCLE ? "§aci§r" : "§8ci";
        switch (currentTool) {
            case BRUSH -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§7shape: " + sq + "§7|" + ci + " §8(X)"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(String.format("§7r:§f%d  §8[ ] sh+scrl", brushRadius)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8rt-click: pick col"), x, y, 0xFFFFFF); y += 9;
            }
            case FILL -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(String.format("§7tol:§f%.3f", fillTolerance)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8= - sh+sc §7ctrl:fine"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8rt-click: pick col"), x, y, 0xFFFFFF); y += 9;
            }
            case SELECT -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8drag: rect sel"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8click: deselect"), x, y, 0xFFFFFF); y += 9;
            }
            case EYEDROPPER -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8click: pick+revert"), x, y, 0xFFFFFF); y += 9;
            }
            case DITHER_BRUSH -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§7shape: " + sq + "§7|" + ci + " §8(X)"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(String.format("§7r:§f%d  §8[ ] sh+scrl", brushRadius)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(String.format("§7str:§f%.1f  §8= - scrl", ditherStrength)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8lt:paint  rt:erase"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8M: mask overlay"), x, y, 0xFFFFFF); y += 9;
            }
            case LASSO -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8drag: trace path"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8release: commit"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8Esc: cancel lasso"), x, y, 0xFFFFFF); y += 9;
            }
            case MAGIC_WAND -> {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(String.format("§7tol:§f%.3f", fillTolerance)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8= - sh+sc §7ctrl:fine"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8click: flood sel"), x, y, 0xFFFFFF); y += 9;
            }
        }
        y += 2;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;

        // Selection shortcuts — header turns yellow when a selection is active
        String selHdr = selMask != null ? "§eSelection" : "§7Selection";
        ctx.drawTextWithShadow(textRenderer, Text.literal(selHdr), x, y, 0xFFFFFF); y += 10;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+A  all"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+D  clear"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Esc  clear/close"), x, y, 0xFFFFFF); y += 9;
        y += 2;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;

        // Global shortcuts
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+Z  undo"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+Y  redo"), x, y, 0xFFFFFF); y += 9;
        // Show requantize source frame when tile is an animated GIF (may differ from active frame)
        boolean gifAnimated = totalFrames > 1 && PayloadState.currentSourceFilename != null
                && PayloadState.currentSourceFilename.toLowerCase().endsWith(".gif");
        if (gifAnimated) {
            String srcLabel = gifSourceFrameCount > 0
                    ? (requantizeSourceFrame + 1) + "/" + gifSourceFrameCount
                    : String.valueOf(requantizeSourceFrame + 1);
            boolean mismatch = requantizeSourceFrame != activeFrame;
            String rSrcLine = (mismatch ? "§e" : "§8") + "R  src:frm" + srcLabel
                    + (mismatch ? " §8Sh+[/]" : " §8Sh+[/]");
            ctx.drawTextWithShadow(textRenderer, Text.literal(rSrcLine), x, y, 0xFFFFFF); y += 9;
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8R  requantize"), x, y, 0xFFFFFF); y += 9;
        }
        String algoName = switch (editorDitherAlgo) {
            case NONE           -> "§8none";
            case FLOYD_STEINBERG -> "§aFS§r";
            case ATKINSON       -> "§aAtkinson§r";
            case BAYER_4X4      -> "§aBayer4§r";
        };
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8D  algo:" + algoName), x, y, 0xFFFFFF); y += 9;
        if (editorDitherAlgo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG) {
            String errLbl = inPreview ? "§7sh+sc" : "§8(in preview)";
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(String.format("§7FS err:§f%.1f %s", fsErrorStrength, errLbl)),
                    x, y, 0xFFFFFF); y += 9;
        }
        String palLbl = requantizeTilePalette
                ? (selMask != null ? "§asel§r" : "§atile§r") : "§8full";
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8Sh+R  pal:" + palLbl), x, y, 0xFFFFFF); y += 9;
        String metricName = switch (matchMetric) {
            case OKLAB        -> "§8oklab";
            case CHROMA_FIRST -> "§achroma§r";
            case LUMA_FIRST   -> "§aluma§r";
            case HUE_ONLY     -> "§ahue§r";
            case RGB          -> "§argb§r";
        };
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8Q  metric:" + metricName), x, y, 0xFFFFFF); y += 9;
        String reduceLbl = switch (editorReductionStrategy) {
            case RAREST   -> "§8rarest";
            case CLOSEST  -> "§aclosest§r";
            case WEIGHTED -> "§aweighted§r";
        };
        String kSel = selMask != null ? "§8(sel)" : "";
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8K  reduce:" + reduceLbl + kSel), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Sh+K  cycle strat"), x, y, 0xFFFFFF); y += 9;
        String heatLbl = heatmapMode ? "§cH  §r§8heat:on" : "§8H  heat:off";
        ctx.drawTextWithShadow(textRenderer, Text.literal(heatLbl), x, y, 0xFFFFFF); y += 9;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;
        String filterName = editorFilterType.name().toLowerCase();
        boolean nonDefaultFilter = editorFilterType != PngToMapColors.FilterParams.FilterType.SMOOTH;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Filter  §8[P]"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8Sh+P: [" + (nonDefaultFilter ? "§e" : "§8") + filterName + "§8]"),
                x, y, 0xFFFFFF); y += 9;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Merge colors"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+clk: queue src"), x, y, 0xFFFFFF); y += 9;
        MergeScope[] availScopes = availableMergeScopes();
        if (availScopes.length > 1) {
            String scopeLabel = switch (mergeScope) {
                case FRAME     -> "§8frame";
                case TILE      -> "§etile";
                case ALL_TILES -> "§call tiles";
            };
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8V: scope [" + scopeLabel + "§8]"), x, y, 0xFFFFFF); y += 9;
        }
        if (mergeSources.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8C: commit merge"), x, y, 0xFFFFFF);
        } else {
            String mergeTarget = activeColor == 0 ? "trans" : String.format("#%06X", colorLookup[activeColor] & 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(String.format("§c%d src  §7→ §f%s", mergeSources.size(), mergeTarget)),
                    x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§aC: commit  §8Esc: clear"), x, y, 0xFFFFFF);
        }
        if (totalFrames > 1) {
            y += 9;
            ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;
            int delay = frameDelayMs != null && activeFrame < frameDelayMs.length
                    ? frameDelayMs[activeFrame] : 100;
            String frmHdr = "§eFrames: " + (activeFrame + 1) + "/" + totalFrames + " §7(" + delay + "ms)";
            ctx.drawTextWithShadow(textRenderer, Text.literal(frmHdr), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+[  prev"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+]  next"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8,  delay -10ms"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8.  delay +10ms"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Del  drop frame"), x, y, 0xFFFFFF); y += 9;
            if (gifAnimated) {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ins  insert src frame"), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8Sh+[/]  R source"), x, y, 0xFFFFFF);
            }
        }
        if (PayloadState.tiles.size() > 1) {
            y += 9;
            ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;
            int totalTiles = PayloadState.tiles.size();
            String tileHdr = "§eTile " + (tileIndex + 1) + "/" + totalTiles
                    + " §7(" + PayloadState.tileCol(tileIndex) + "," + PayloadState.tileRow(tileIndex) + ")";
            ctx.drawTextWithShadow(textRenderer, Text.literal(tileHdr), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8G  minimap"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+Sh+[  prev"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+Sh+]  next"), x, y, 0xFFFFFF);
        }
    }

    // ── Minimap overlay ─────────────────────────────────────────────────

    private void computeMinimapThumbnails() {
        int count = PayloadState.tiles.size();
        thumbnails = new byte[count][];
        for (int i = 0; i < count; i++) {
            if (i == tileIndex) continue;  // rendered live from mapColors
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            try {
                byte[][] frames = LoominaryCommand.resolveAllFramesForTile(tile, tile.chunks);
                thumbnails[i] = downsample(frames[0]);
            } catch (Exception e) {
                thumbnails[i] = null;
            }
        }
    }

    private byte[] downsample(byte[] frame) {
        byte[] thumb = new byte[THUMB_SZ * THUMB_SZ];
        for (int ty = 0; ty < THUMB_SZ; ty++) {
            for (int tx = 0; tx < THUMB_SZ; tx++) {
                int mx = tx * MAP_SIZE / THUMB_SZ;
                int my = ty * MAP_SIZE / THUMB_SZ;
                thumb[tx + ty * THUMB_SZ] = frame[mx + my * MAP_SIZE];
            }
        }
        return thumb;
    }

    private void renderMinimap(DrawContext ctx, int mouseX, int mouseY) {
        if (PayloadState.tiles.isEmpty()) { showMinimap = false; return; }
        if (thumbnails == null) computeMinimapThumbnails();

        int cols = PayloadState.gridColumns;
        int rows = PayloadState.gridRows;
        int cellSz  = THUMB_SZ + MINI_GAP;
        int panelW  = MINI_PAD * 2 + cols * cellSz - MINI_GAP;
        int panelH  = MINI_HEADER_H + MINI_PAD + rows * cellSz - MINI_GAP + MINI_PAD;

        int cx = GUIDE_W + (width - PANEL_W - GUIDE_W) / 2;
        int cy = (height - STATUS_H) / 2;
        int panelX = Math.max(GUIDE_W + 2, Math.min(width - PANEL_W - panelW - 2, cx - panelW / 2));
        int panelY = Math.max(2, Math.min(height - STATUS_H - panelH - 2, cy - panelH / 2));

        // Drop-shadow
        ctx.fill(panelX + 3, panelY + 3, panelX + panelW + 3, panelY + panelH + 3, 0x88000000);
        // Border + background
        ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_MAP_BORDER);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF0D0D0D);

        // Header
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7TILES   §8G=close  Ctrl+Shift+[/]"), panelX + 4, panelY + 3, 0xFFFFFF);
        ctx.fill(panelX, panelY + MINI_HEADER_H - 1, panelX + panelW, panelY + MINI_HEADER_H, COL_PANEL_SEP);

        int gridOriginX = panelX + MINI_PAD;
        int gridOriginY = panelY + MINI_HEADER_H + MINI_PAD;

        byte[] activeThumb = downsample(mapColors);

        int totalTiles = PayloadState.tiles.size();
        for (int ti = 0; ti < totalTiles; ti++) {
            int tc = PayloadState.tileCol(ti);
            int tr = PayloadState.tileRow(ti);
            if (tc >= cols || tr >= rows) continue;
            int tx = gridOriginX + tc * cellSz;
            int ty = gridOriginY + tr * cellSz;

            boolean isActive  = (ti == tileIndex);
            boolean isHovered = mouseX >= tx && mouseX < tx + THUMB_SZ
                             && mouseY >= ty && mouseY < ty + THUMB_SZ;

            int borderColor = isActive ? COL_RING : (isHovered ? COL_HOVER_RING : COL_PANEL_SEP);
            ctx.fill(tx - 1, ty - 1, tx + THUMB_SZ + 1, ty + THUMB_SZ + 1, borderColor);

            byte[] thumb = isActive ? activeThumb : thumbnails[ti];
            if (thumb != null) {
                for (int py = 0; py < THUMB_SZ; py++) {
                    for (int px = 0; px < THUMB_SZ; px++) {
                        int cb   = thumb[px + py * THUMB_SZ] & 0xFF;
                        int argb = cb == 0 ? ((px + py) % 2 == 0 ? COL_TRANS_A : COL_TRANS_B)
                                : (0xFF000000 | colorLookup[cb]);
                        ctx.fill(tx + px, ty + py, tx + px + 1, ty + py + 1, argb);
                    }
                }
            } else {
                ctx.fill(tx, ty, tx + THUMB_SZ, ty + THUMB_SZ, 0xFF1A1A2A);
            }

            // Dim non-active tiles slightly
            if (!isActive) ctx.fill(tx, ty, tx + THUMB_SZ, ty + THUMB_SZ, 0x33000000);

            // Col,row label
            String posLabel = tc + "," + tr;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§0" + posLabel), tx + 2, ty + 2, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + posLabel), tx + 1, ty + 1, 0xFFFFFF);
        }
    }

    private void switchToTile(int newIdx) {
        if (newIdx == tileIndex) { showMinimap = false; return; }
        if (PayloadState.tiles.isEmpty() || newIdx < 0 || newIdx >= PayloadState.tiles.size()) return;

        if (dirty) {
            reEncode();
            dirty = false;
        }

        PayloadState.switchTile(newIdx);
        tileIndex = newIdx;
        tileLabel = PayloadState.tileLabel(newIdx);

        PayloadState.TileData tile = PayloadState.tiles.get(newIdx);
        try {
            allFrames = LoominaryCommand.resolveAllFramesForTile(tile, PayloadState.ACTIVE_CHUNKS);
        } catch (Exception e) {
            setStatusMsg("Failed to load tile " + newIdx + ": " + e.getMessage());
            return;
        }

        totalFrames = allFrames.length;
        activeFrame = 0;
        mapColors   = allFrames[0];

        frameDelayMs = new int[totalFrames];
        List<Integer> stored = tile.frameDelays;
        if (stored != null && !stored.isEmpty()) {
            for (int i = 0; i < totalFrames; i++)
                frameDelayMs[i] = stored.get(Math.min(i, stored.size() - 1));
        } else {
            Arrays.fill(frameDelayMs, 100);
        }

        history.clear();
        inPreview          = false;
        previewColors      = null;
        previewSelMask     = null;
        cachedRequantizeSrc = null;
        cachedGifFrames    = null;
        selMask            = null;
        lassoPoints        = null;
        ditherMask         = null;
        useCustomMask      = false;
        mergeSources.clear();
        thumbnails         = null;  // force thumbnail recompute
        showMinimap        = false;

        rebuildTileColors();
        setStatusMsg("Switched to " + tileLabel);
    }

    private void renderStatusBar(DrawContext ctx) {
        int barY = height - STATUS_H;
        ctx.fill(0, barY, width, height, COL_STATUS_BG);
        ctx.fill(0, barY, width, barY+1, COL_STATUS_SEP);

        if (inPreview) {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§e[PREVIEW]§r  Enter=apply  §8Esc=cancel"),
                    4, barY + 3, 0xFFFFFF);
            return;
        }

        String left;
        if (statusMsg != null && System.currentTimeMillis() < statusMsgExpiry) {
            left = "§c" + statusMsg;
        } else {
            String toolStr = switch (currentTool) {
                case BRUSH        -> String.format("§a[B]Brush§r r:%d §8Sh+sc/[]", brushRadius);
                case FILL         -> String.format("§a[F]Fill§r tol:%.3f §8Sh+sc/Ct+fine", fillTolerance);
                case SELECT       -> "§a[S]Rect-sel§r §8Ctrl+A  Ctrl+D";
                case EYEDROPPER   -> "§a[E]Eyedrop§r";
                case DITHER_BRUSH -> String.format("§a[T]Dither§r s:%.1f §8Sh+sc  [M]mask", ditherStrength);
                case LASSO        -> "§a[L]Lasso§r §8drag";
                case MAGIC_WAND   -> String.format("§a[W]Wand§r tol:%.3f §8Sh+sc/Ct+fine", fillTolerance);
            };
            String undoHint  = history.canUndo() ? " §8Ctrl+Z" : "";
            String redoHint  = history.canRedo() ? " §8Ctrl+Y" : "";
            String ditherHint = "  " + switch (editorDitherAlgo) {
                case NONE            -> "§8[D]none";
                case FLOYD_STEINBERG -> "§a[D]FS§r";
                case ATKINSON        -> "§a[D]atk§r";
                case BAYER_4X4       -> "§a[D]bayer§r";
            };
            String reqHint   = PayloadState.currentSourceFilename != null ? "  §8[R]req" : "";
            String selHint   = selMask != null ? "  §e[sel]§r" : "";
            int frameDelay = frameDelayMs != null && activeFrame < frameDelayMs.length
                    ? frameDelayMs[activeFrame] : 100;
            String frameHint = totalFrames > 1
                    ? "  §e" + (activeFrame + 1) + "/" + totalFrames + " " + frameDelay + "ms§r §8Ctrl+[/]" : "";
            String minimapHint = PayloadState.tiles.size() > 1 ? "  §8[G]tiles" : "";
            String scopeSuffix = mergeScope != MergeScope.FRAME
                    ? (mergeScope == MergeScope.TILE ? "/tile" : "/all") : "";
            String mergeHint   = mergeSources.isEmpty() ? ""
                    : "  §c[" + mergeSources.size() + " merge" + scopeSuffix + "]§r §8C=commit";
            left = "§8" + tileLabel + "  §r" + toolStr + undoHint + redoHint
                    + ditherHint + reqHint + selHint + frameHint + minimapHint + mergeHint;
        }
        ctx.drawTextWithShadow(textRenderer, Text.literal(left), 4, barY + 3, 0xFFFFFF);

        String right = inMapBounds(hoverMapX, hoverMapY)
                ? hoverInfoString() : String.format("scroll=zoom  mid-drag=pan  %d×", scale);
        int rw = textRenderer.getWidth(right);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7" + right), width - PANEL_W - rw - 8, barY + 3, 0xFFFFFF);
    }

    private String hoverInfoString() {
        int cb  = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
        int rgb = colorLookup[cb];
        String hex = cb == 0 ? "transparent" : String.format("#%06X", rgb & 0xFFFFFF);
        return String.format("(%d,%d) %s  %d×", hoverMapX, hoverMapY, hex, scale);
    }

    // ── Input ────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double hAmt, double vAmt) {
        // In preview: Shift+scroll adjusts FS error diffusion strength and live-refreshes
        if (inPreview) {
            if (hasShiftDown() && editorDitherAlgo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG) {
                fsErrorStrength = Math.round(Math.max(0.1f, Math.min(1.0f,
                        fsErrorStrength + (float) Math.signum(vAmt) * 0.1f)) * 10) / 10f;
                refreshRequantizePreview();
            }
            return true;
        }
        if (mouseX < GUIDE_W || mouseX >= width - PANEL_W) return false;

        if (hasShiftDown()) {
            switch (currentTool) {
                case BRUSH ->
                    brushRadius = Math.max(0, Math.min(MAX_BRUSH,
                            brushRadius + (int) Math.signum(vAmt)));
                case DITHER_BRUSH -> {
                    ditherStrength = Math.max(0f, Math.min(1f,
                            ditherStrength + (float) Math.signum(vAmt) * 0.1f));
                    ditherStrength = Math.round(ditherStrength * 10) / 10f;
                }
                case FILL, MAGIC_WAND -> {
                    boolean fine  = hasControlDown();
                    float tStep   = fine ? 0.005f : 0.025f;
                    int   tScale  = fine ? 200    : 40;
                    fillTolerance = Math.round(Math.max(0f, Math.min(0.5f,
                            fillTolerance + (float) Math.signum(vAmt) * tStep)) * tScale) / (float) tScale;
                }
                default -> {}
            }
            return true;
        }

        int old = scale;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                scale + (int) Math.signum(vAmt)));
        if (scale != old) {
            translateX = (float)(mouseX - (mouseX - translateX) * scale / (float) old);
            translateY = (float)(mouseY - (mouseY - translateY) * scale / (float) old);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inPreview) return true;
        int panelX = width - PANEL_W;

        // ── Minimap: intercept left-clicks on tile thumbnails ────────────
        if (showMinimap && button == 0) {
            int cols = PayloadState.gridColumns;
            int rows = PayloadState.gridRows;
            int cellSz  = THUMB_SZ + MINI_GAP;
            int panelW  = MINI_PAD * 2 + cols * cellSz - MINI_GAP;
            int panelH  = MINI_HEADER_H + MINI_PAD + rows * cellSz - MINI_GAP + MINI_PAD;
            int cx = GUIDE_W + (width - PANEL_W - GUIDE_W) / 2;
            int cy = (height - STATUS_H) / 2;
            int mpx = Math.max(GUIDE_W + 2, Math.min(width - PANEL_W - panelW - 2, cx - panelW / 2));
            int mpy = Math.max(2, Math.min(height - STATUS_H - panelH - 2, cy - panelH / 2));
            int gridX0 = mpx + MINI_PAD;
            int gridY0 = mpy + MINI_HEADER_H + MINI_PAD;

            int mx = (int) mouseX, my = (int) mouseY;
            if (mx >= mpx && mx < mpx + panelW && my >= mpy && my < mpy + panelH) {
                // Within the panel — check thumbnail cells
                int relX = mx - gridX0, relY = my - gridY0;
                if (relX >= 0 && relY >= 0) {
                    int tc = relX / cellSz, tr = relY / cellSz;
                    int offX = relX % cellSz, offY = relY % cellSz;
                    if (offX < THUMB_SZ && offY < THUMB_SZ && tc < cols && tr < rows) {
                        int ti = tr * cols + tc;
                        if (ti < PayloadState.tiles.size()) {
                            switchToTile(ti);
                            return true;
                        }
                    }
                }
                // Click inside the panel but not on a tile — still consume the event
                return true;
            } else {
                // Click outside the panel dismisses it
                showMinimap = false;
                return true;
            }
        }

        if (mouseX < GUIDE_W) return false;

        // ── Palette panel ────────────────────────────────────────────────
        if (mouseX >= panelX) {
            int relX = (int) mouseX - panelX, relY = (int) mouseY;
            if (relY >= 53 && relY < 66 && relX >= 40) {
                paletteShowAll = !paletteShowAll;
                return true;
            }
            if (relY >= SWATCHES_START_Y) {
                int[] palette = paletteShowAll ? allMapColors : tileColors;
                int row = (relY - SWATCHES_START_Y) / SWATCH_STRIDE;
                int col = (relX - PANEL_MARGIN) / SWATCH_STRIDE;
                if (col >= 0 && col < SWATCHES_PER_ROW) {
                    int idx = row * SWATCHES_PER_ROW + col;
                    if (idx < palette.length) {
                        int c = palette[idx];
                        if (button == 0 && hasControlDown()) {
                            // Ctrl+click: toggle color in merge-source set
                            if (!mergeSources.remove(c)) mergeSources.add(c);
                        } else {
                            activeColor = c;
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        // ── Canvas ───────────────────────────────────────────────────────
        if (!inMapBounds(hoverMapX, hoverMapY))
            return super.mouseClicked(mouseX, mouseY, button);

        // Ctrl+left-click on canvas: toggle color under cursor in merge-source set
        if (button == 0 && hasControlDown()) {
            int c = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
            if (c != 0) {
                if (!mergeSources.remove(c)) mergeSources.add(c);
            }
            return true;
        }

        // Eyedropper: left-click picks colour and reverts to previous tool
        if (button == 0 && currentTool == Tool.EYEDROPPER) {
            activeColor = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
            currentTool = prevTool;
            return true;
        }

        // Dither brush: left=paint strength, right=erase (set to 0)
        if (currentTool == Tool.DITHER_BRUSH && (button == 0 || button == 1)) {
            ensureDitherMask();
            applyDitherBrushAt(hoverMapX, hoverMapY, button == 0 ? ditherStrength : 0f);
            stroking = true;
            return true;
        }

        // Right-click = eyedropper on all destructive tools
        if (button == 1 && (currentTool == Tool.BRUSH || currentTool == Tool.FILL)) {
            activeColor = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
            return true;
        }

        if (button == 0) {
            switch (currentTool) {
                case SELECT -> {
                    selMask = new boolean[MAP_BYTES];
                    selMask[hoverMapX + hoverMapY * MAP_SIZE] = true;
                    selecting   = true;
                    dragOriginX = hoverMapX;
                    dragOriginY = hoverMapY;
                }
                case LASSO -> {
                    lassoPoints = new ArrayList<>();
                    lassoPoints.add(new int[]{hoverMapX, hoverMapY});
                }
                case MAGIC_WAND -> applyMagicWand(hoverMapX, hoverMapY);
                case BRUSH -> {
                    if (selMask != null && !selMask[hoverMapX + hoverMapY * MAP_SIZE])
                        return true;
                    history.snapshot();
                    stroking = true;
                    applyBrushAt(hoverMapX, hoverMapY);
                }
                case FILL -> {
                    applyFillAt(hoverMapX, hoverMapY);
                    rebuildTileColors();
                }
                default -> {}
            }
            return true;
        }
        if (button == 2) {
            panning = true; lastPanMouseX = mouseX; lastPanMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (inPreview) return true;
        if (button == 0 || (button == 1 && currentTool == Tool.DITHER_BRUSH)) {
            if (stroking) { stroking = false; rebuildTileColors(); }
            if (selecting) {
                selecting = false;
                // Single click with no drag → deselect
                if (hoverMapX == dragOriginX && hoverMapY == dragOriginY) selMask = null;
            }
            if (button == 0 && lassoPoints != null) commitLasso();
        }
        if (button == 2) { panning = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double dX, double dY) {
        if (inPreview) return true;
        if (button == 0) {
            if (selecting && inMapBounds(hoverMapX, hoverMapY)) {
                int x1 = Math.min(dragOriginX, hoverMapX);
                int y1 = Math.min(dragOriginY, hoverMapY);
                int x2 = Math.max(dragOriginX, hoverMapX);
                int y2 = Math.max(dragOriginY, hoverMapY);
                if (selMask == null) selMask = new boolean[MAP_BYTES];
                for (int py = 0; py < MAP_SIZE; py++)
                    for (int px = 0; px < MAP_SIZE; px++)
                        selMask[px + py * MAP_SIZE] =
                                (px >= x1 && px <= x2 && py >= y1 && py <= y2);
                return true;
            }
            if (stroking && currentTool == Tool.BRUSH && inMapBounds(hoverMapX, hoverMapY)) {
                applyBrushAt(hoverMapX, hoverMapY);
                return true;
            }
            if (currentTool == Tool.LASSO && inMapBounds(hoverMapX, hoverMapY)) {
                if (lassoPoints == null) lassoPoints = new ArrayList<>();
                int[] last = lassoPoints.isEmpty() ? null
                        : lassoPoints.get(lassoPoints.size() - 1);
                if (last == null || last[0] != hoverMapX || last[1] != hoverMapY)
                    lassoPoints.add(new int[]{hoverMapX, hoverMapY});
                return true;
            }
        }
        if ((button == 0 || button == 1) && stroking
                && currentTool == Tool.DITHER_BRUSH && inMapBounds(hoverMapX, hoverMapY)) {
            ensureDitherMask();
            applyDitherBrushAt(hoverMapX, hoverMapY, button == 0 ? ditherStrength : 0f);
            return true;
        }
        if (button == 2 && panning) {
            translateX += (float) dX;
            translateY += (float) dY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dX, dY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl  = hasControlDown();
        boolean shift = hasShiftDown();

        // Preview mode is modal — only Enter/Y/Esc/D/Shift+R act
        if (inPreview) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                    || keyCode == GLFW.GLFW_KEY_Y) {
                commitPreview();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                inPreview = false; previewColors = null; previewSelMask = null; cachedRequantizeSrc = null;
            } else if (keyCode == GLFW.GLFW_KEY_D) {
                cycleEditorDither();
                refreshRequantizePreview();
            } else if (keyCode == GLFW.GLFW_KEY_Q) {
                cycleMatchMetric();
                refreshRequantizePreview();
            } else if (keyCode == GLFW.GLFW_KEY_R && shift) {
                requantizeTilePalette = !requantizeTilePalette;
                String pal = requantizeTilePalette
                        ? (selMask != null ? "selection palette" : "tile palette") : "full palette";
                setStatusMsg("Requantize palette: " + pal);
                refreshRequantizePreview();
            } else if (keyCode == GLFW.GLFW_KEY_K) {
                if (shift) cycleReductionStrategy();
                else applyColorReduction();
            } else if (shift && totalFrames > 1 && PayloadState.currentSourceFilename != null
                    && PayloadState.currentSourceFilename.toLowerCase().endsWith(".gif")) {
                int maxSrc = gifSourceFrameCount > 0 ? gifSourceFrameCount - 1 : requantizeSourceFrame + 32;
                if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) {
                    requantizeSourceFrame = Math.max(0, requantizeSourceFrame - 1);
                    updateCachedSrcFromGifFrames();
                    refreshRequantizePreview();
                    setStatusMsg("R source: GIF frame " + (requantizeSourceFrame + 1)
                            + (gifSourceFrameCount > 0 ? "/" + gifSourceFrameCount : ""));
                } else if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
                    requantizeSourceFrame = Math.min(maxSrc, requantizeSourceFrame + 1);
                    updateCachedSrcFromGifFrames();
                    refreshRequantizePreview();
                    setStatusMsg("R source: GIF frame " + (requantizeSourceFrame + 1)
                            + (gifSourceFrameCount > 0 ? "/" + gifSourceFrameCount : ""));
                }
            }
            return true;
        }

        // Escape: clear merge sources → cancel lasso → deselect → close
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (!mergeSources.isEmpty()) { mergeSources.clear(); return true; }
            if (lassoPoints != null) { lassoPoints = null; return true; }
            if (selMask != null)     { selMask = null;     return true; }
            // fall through to super → close()
        }

        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) { history.undo(); return true; }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) { history.redo(); return true; }

        // Tile navigation (Ctrl+Shift+[ / Ctrl+Shift+]) — across tiles in the batch
        if (ctrl && shift && PayloadState.tiles.size() > 1) {
            if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) {
                int prev = (tileIndex - 1 + PayloadState.tiles.size()) % PayloadState.tiles.size();
                switchToTile(prev); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
                int next = (tileIndex + 1) % PayloadState.tiles.size();
                switchToTile(next); return true;
            }
        }

        // Frame navigation for animated tiles (Ctrl+[ / Ctrl+])
        if (ctrl && !shift && totalFrames > 1) {
            if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) {
                switchFrame((activeFrame - 1 + totalFrames) % totalFrames); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
                switchFrame((activeFrame + 1) % totalFrames); return true;
            }
        }

        // Frame delay editing (,/. = -10ms/+10ms; Shift = ×10)
        if (totalFrames > 1 && frameDelayMs != null) {
            int delta = shift ? 100 : 10;
            if (keyCode == GLFW.GLFW_KEY_COMMA) {
                frameDelayMs[activeFrame] = Math.max(10, frameDelayMs[activeFrame] - delta);
                dirty = true;
                setStatusMsg("Frame " + (activeFrame + 1) + " delay: " + frameDelayMs[activeFrame] + "ms");
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PERIOD) {
                frameDelayMs[activeFrame] = Math.min(10000, frameDelayMs[activeFrame] + delta);
                dirty = true;
                setStatusMsg("Frame " + (activeFrame + 1) + " delay: " + frameDelayMs[activeFrame] + "ms");
                return true;
            }
        }

        // Drop current frame (Delete) — blocked if only one frame
        if (keyCode == GLFW.GLFW_KEY_DELETE && totalFrames > 1) {
            dropCurrentFrame(); return true;
        }
        // Insert a frame from the current GIF source frame (animated GIF tiles only)
        if (keyCode == GLFW.GLFW_KEY_INSERT && totalFrames > 1
                && PayloadState.currentSourceFilename != null
                && PayloadState.currentSourceFilename.toLowerCase().endsWith(".gif")) {
            insertFrameFromSource(); return true;
        }

        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            selMask = new boolean[MAP_BYTES]; Arrays.fill(selMask, true); return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_D) { selMask = null; return true; }

        // Tool keys — track prevTool for eyedropper revert
        if (keyCode == GLFW.GLFW_KEY_B) { setTool(Tool.BRUSH);        return true; }
        if (keyCode == GLFW.GLFW_KEY_F) { setTool(Tool.FILL);         return true; }
        if (keyCode == GLFW.GLFW_KEY_S) { setTool(Tool.SELECT);       return true; }
        if (keyCode == GLFW.GLFW_KEY_E) { setTool(Tool.EYEDROPPER);   return true; }
        if (keyCode == GLFW.GLFW_KEY_T) { setTool(Tool.DITHER_BRUSH); return true; }
        if (keyCode == GLFW.GLFW_KEY_L) { setTool(Tool.LASSO);        return true; }
        if (keyCode == GLFW.GLFW_KEY_W) { setTool(Tool.MAGIC_WAND);   return true; }
        if (keyCode == GLFW.GLFW_KEY_X) {
            brushShape = brushShape == BrushShape.SQUARE ? BrushShape.CIRCLE : BrushShape.SQUARE;
            return true;
        }

        // Mode toggles (no prevTool update)
        if (keyCode == GLFW.GLFW_KEY_Q) { cycleMatchMetric(); return true; }
        if (keyCode == GLFW.GLFW_KEY_D) { cycleEditorDither(); return true; }
        if (keyCode == GLFW.GLFW_KEY_R) { applyRequantize(); return true; }
        if (keyCode == GLFW.GLFW_KEY_M) { showMask = !showMask; return true; }
        if (keyCode == GLFW.GLFW_KEY_H) {
            heatmapMode = !heatmapMode;
            setStatusMsg(heatmapMode
                    ? "Heatmap on: §cred§r=rarest (costly), §9blue§r=most-frequent (cheap). [H] to toggle."
                    : "Heatmap off.");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G) {
            showMinimap = !showMinimap;
            if (showMinimap && thumbnails == null) computeMinimapThumbnails();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C) {
            if (mergeSources.isEmpty()) {
                setStatusMsg("Ctrl+click palette swatches or image pixels to queue colors for merging, then press C.");
            } else {
                commitMerge();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_V) {
            MergeScope[] available = availableMergeScopes();
            if (available.length > 1) {
                int idx = 0;
                for (int i = 0; i < available.length; i++) { if (available[i] == mergeScope) { idx = i; break; } }
                mergeScope = available[(idx + 1) % available.length];
                String label = switch (mergeScope) {
                    case FRAME     -> "frame";
                    case TILE      -> "tile (all frames)";
                    case ALL_TILES -> "all tiles";
                };
                setStatusMsg("Merge scope: " + label);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_P) {
            if (shift) {
                PngToMapColors.FilterParams.FilterType[] types = PngToMapColors.FilterParams.FilterType.values();
                int idx = 0;
                for (int i = 0; i < types.length; i++) { if (types[i] == editorFilterType) { idx = i; break; } }
                editorFilterType = types[(idx + 1) % types.length];
                String name = editorFilterType.name().toLowerCase();
                setStatusMsg("Filter: " + name + " (P to apply)");
            } else {
                applyEditorFilter();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_K) {
            if (shift) cycleReductionStrategy();
            else applyColorReduction();
            return true;
        }

        // Shift+[ / Shift+] — step requantize source frame independently (GIF tiles only)
        if (!ctrl && shift && totalFrames > 1 && PayloadState.currentSourceFilename != null
                && PayloadState.currentSourceFilename.toLowerCase().endsWith(".gif")) {
            int maxSrc = gifSourceFrameCount > 0 ? gifSourceFrameCount - 1 : requantizeSourceFrame + 32;
            if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) {
                requantizeSourceFrame = Math.max(0, requantizeSourceFrame - 1);
                updateCachedSrcFromGifFrames();
                if (inPreview) refreshRequantizePreview();
                setStatusMsg("R source: GIF frame " + (requantizeSourceFrame + 1)
                        + (gifSourceFrameCount > 0 ? "/" + gifSourceFrameCount : ""));
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
                requantizeSourceFrame = Math.min(maxSrc, requantizeSourceFrame + 1);
                updateCachedSrcFromGifFrames();
                if (inPreview) refreshRequantizePreview();
                setStatusMsg("R source: GIF frame " + (requantizeSourceFrame + 1)
                        + (gifSourceFrameCount > 0 ? "/" + gifSourceFrameCount : ""));
                return true;
            }
        }

        // Brush / dither-brush radius via [ ] (not when Ctrl held — that nav frames/tiles)
        if (!ctrl && !shift && keyCode == GLFW.GLFW_KEY_LEFT_BRACKET
                && (currentTool == Tool.BRUSH || currentTool == Tool.DITHER_BRUSH)) {
            brushRadius = Math.max(0, brushRadius - 1); return true;
        }
        if (!ctrl && !shift && keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET
                && (currentTool == Tool.BRUSH || currentTool == Tool.DITHER_BRUSH)) {
            brushRadius = Math.min(MAX_BRUSH, brushRadius + 1); return true;
        }

        // Fill / magic-wand tolerance via = and - (Ctrl = fine 0.005 step, plain = coarse 0.025)
        if ((currentTool == Tool.FILL || currentTool == Tool.MAGIC_WAND)) {
            float tolStep  = ctrl ? 0.005f : 0.025f;
            int   tolScale = ctrl ? 200    : 40;
            if (keyCode == GLFW.GLFW_KEY_EQUAL) {
                fillTolerance = Math.round(Math.min(0.5f, fillTolerance + tolStep) * tolScale) / (float) tolScale; return true;
            }
            if (keyCode == GLFW.GLFW_KEY_MINUS) {
                fillTolerance = Math.round(Math.max(0f,   fillTolerance - tolStep) * tolScale) / (float) tolScale; return true;
            }
        }

        // Dither-brush strength via = and -
        if (currentTool == Tool.DITHER_BRUSH) {
            if (keyCode == GLFW.GLFW_KEY_EQUAL) {
                ditherStrength = Math.round(Math.min(1f, ditherStrength+0.1f)*10)/10f; return true;
            }
            if (keyCode == GLFW.GLFW_KEY_MINUS) {
                ditherStrength = Math.round(Math.max(0f, ditherStrength-0.1f)*10)/10f; return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Switches tool, updating prevTool for eyedropper revert. */
    private void setTool(Tool t) {
        if (currentTool != Tool.EYEDROPPER) prevTool = currentTool;
        currentTool = t;
        if (t != Tool.LASSO) lassoPoints = null;
    }

    // ── Tool implementations ──────────────────────────────────────────────

    private void applyBrushAt(int cx, int cy) {
        int r2 = brushRadius * brushRadius;
        for (int dy = -brushRadius; dy <= brushRadius; dy++) {
            for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                int px = cx + dx, py = cy + dy;
                if (!inMapBounds(px, py)) continue;
                if (selMask != null && !selMask[px + py * MAP_SIZE]) continue;
                mapColors[px + py * MAP_SIZE] = (byte) activeColor;
            }
        }
        dirty = true;
    }

    private void applyFillAt(int startX, int startY) {
        int target = mapColors[startX + startY * MAP_SIZE] & 0xFF;
        if (target == activeColor) return;
        float[] targetOklab = fillTolerance > 0f ? getMapColorOklab(target) : null;
        history.snapshot();
        boolean[] visited = new boolean[MAP_BYTES];
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{startX, startY});
        while (!queue.isEmpty()) {
            int[] p = queue.pop();
            int x = p[0], y = p[1];
            if (!inMapBounds(x, y)) continue;
            if (selMask != null && !selMask[x + y * MAP_SIZE]) continue;
            int idx = x + y * MAP_SIZE;
            if (visited[idx]) continue;
            visited[idx] = true;
            if (!colorMatches(mapColors[idx] & 0xFF, target, targetOklab)) continue;
            mapColors[idx] = (byte) activeColor;
            queue.push(new int[]{x+1, y});
            queue.push(new int[]{x-1, y});
            queue.push(new int[]{x, y+1});
            queue.push(new int[]{x, y-1});
        }
        dirty = true;
    }

    private void applyDitherBrushAt(int cx, int cy, float value) {
        int r2 = brushRadius * brushRadius;
        for (int dy = -brushRadius; dy <= brushRadius; dy++) {
            for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                int px = cx + dx, py = cy + dy;
                if (!inMapBounds(px, py)) continue;
                ditherMask[px + py * MAP_SIZE] = value;
            }
        }
    }

    private void applyMagicWand(int startX, int startY) {
        int target = mapColors[startX + startY * MAP_SIZE] & 0xFF;
        float[] targetOklab = fillTolerance > 0f ? getMapColorOklab(target) : null;
        boolean[] newMask = new boolean[MAP_BYTES];
        boolean[] visited = new boolean[MAP_BYTES];
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{startX, startY});
        while (!queue.isEmpty()) {
            int[] p = queue.pop();
            int x = p[0], y = p[1];
            if (!inMapBounds(x, y)) continue;
            int idx = x + y * MAP_SIZE;
            if (visited[idx]) continue;
            visited[idx] = true;
            if (!colorMatches(mapColors[idx] & 0xFF, target, targetOklab)) continue;
            newMask[idx] = true;
            queue.push(new int[]{x+1, y});
            queue.push(new int[]{x-1, y});
            queue.push(new int[]{x, y+1});
            queue.push(new int[]{x, y-1});
        }
        selMask = newMask;
    }

    private void commitLasso() {
        if (lassoPoints == null || lassoPoints.size() < 3) {
            lassoPoints = null;
            return;
        }
        Path2D.Float path = new Path2D.Float();
        path.moveTo(lassoPoints.get(0)[0] + 0.5, lassoPoints.get(0)[1] + 0.5);
        for (int i = 1; i < lassoPoints.size(); i++)
            path.lineTo(lassoPoints.get(i)[0] + 0.5, lassoPoints.get(i)[1] + 0.5);
        path.closePath();
        boolean[] newMask = new boolean[MAP_BYTES];
        for (int py = 0; py < MAP_SIZE; py++)
            for (int px = 0; px < MAP_SIZE; px++)
                newMask[px + py * MAP_SIZE] = path.contains(px + 0.5, py + 0.5);
        selMask = newMask;
        lassoPoints = null;
    }

    private boolean colorMatches(int color, int target, float[] targetOklab) {
        if (color == target) return true;
        if (targetOklab == null) return false;
        float[] colorOklab = getMapColorOklab(color);
        if (colorOklab == null) return false;
        float dL = targetOklab[0]-colorOklab[0], da = targetOklab[1]-colorOklab[1],
              db = targetOklab[2]-colorOklab[2];
        return dL*dL + da*da + db*db <= fillTolerance * fillTolerance;
    }

    private float[] getMapColorOklab(int colorByte) {
        if (colorByte == 0) return null;
        int rgb = colorLookup[colorByte];
        return rgb == 0 ? null : PngToMapColors.rgbToOklab(rgb);
    }

    // ── Phase 6 — re-quantize + preview ─────────────────────────────────

    private void applyRequantize() {
        if (inPreview) return;
        String fname = PayloadState.currentSourceFilename;
        if (fname == null) { setStatusMsg("No source file — use /loominary import first"); return; }
        Path sourcePath = FabricLoader.getInstance().getGameDir()
                .resolve("loominary_data").resolve(fname);
        if (!Files.exists(sourcePath)) {
            setStatusMsg("Source not found: loominary_data/" + fname); return;
        }
        try {
            if (totalFrames > 1 && fname.toLowerCase().endsWith(".gif")) {
                // Lazily load all coalesced GIF frames so Shift+[/] can switch source
                // frames without a disk round-trip.
                if (cachedGifFrames == null)
                    cachedGifFrames = PngToMapColors.coalesceGifFrames(sourcePath);
                cachedRequantizeSrc = cachedGifFrames[Math.min(requantizeSourceFrame, cachedGifFrames.length - 1)];
            } else {
                cachedRequantizeSrc = ImageIO.read(sourcePath.toFile());
            }
            if (cachedRequantizeSrc == null) { setStatusMsg("Could not decode source image"); return; }
            inPreview = true;
            computeRequantizePreview();
        } catch (IOException e) {
            setStatusMsg("Re-quantize failed: " + e.getMessage());
        }
    }

    private void refreshRequantizePreview() {
        if (!inPreview || cachedRequantizeSrc == null) return;
        computeRequantizePreview();
    }

    private void computeRequantizePreview() {
        byte[] fullTile = computeRequantizeTile(cachedRequantizeSrc);
        byte[] preview  = mapColors.clone();
        boolean[] pMask = null;
        if (selMask != null) {
            pMask = selMask.clone();
            for (int i = 0; i < MAP_BYTES; i++)
                if (selMask[i]) preview[i] = fullTile[i];
        } else {
            System.arraycopy(fullTile, 0, preview, 0, MAP_BYTES);
        }
        previewColors  = preview;
        previewSelMask = pMask;
    }

    private byte[] computeRequantizeTile(BufferedImage src) {
        boolean legalOnly = !PayloadState.allShades;
        PngToMapColors.DitherAlgo algo = editorDitherAlgo;

        // Custom-mask FS: per-tile path with painted dither mask and fsErrorStrength
        if (algo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG && useCustomMask && ditherMask != null) {
            return requantizePerTile(src, ditherMask, fsErrorStrength);
        }

        // Standard cross-tile FS: uses Otsu strength map and cross-seam propagation
        if (algo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG && !requantizeTilePalette
                && matchMetric == PngToMapColors.MatchMetric.OKLAB) {
            byte[][] tiles = PngToMapColors.convertTwoPassGrid(
                    src, legalOnly, 0, true, PayloadState.gridColumns, PayloadState.gridRows);
            return tiles[tileIndex];
        }

        // Per-tile path for NONE, ATKINSON, BAYER_4X4, FS+tile-palette, and non-OKLAB metrics
        int totalW = PayloadState.gridColumns * MAP_SIZE;
        int totalH = PayloadState.gridRows * MAP_SIZE;
        BufferedImage scaledFull = PngToMapColors.scale(src, totalW, totalH);
        int tileCol = PayloadState.tileCol(tileIndex);
        int tileRow = PayloadState.tileRow(tileIndex);
        BufferedImage tileImg = scaledFull.getSubimage(
                tileCol * MAP_SIZE, tileRow * MAP_SIZE, MAP_SIZE, MAP_SIZE);
        float[][] oklabLookup = PngToMapColors.buildOklabLookup();
        float[][] rgbLookup = (matchMetric == PngToMapColors.MatchMetric.RGB)
                ? PngToMapColors.buildLinearRgbLookup() : null;

        // Pass 1: nearest-color (OKLAB) to establish source-derived palette
        byte[] firstPass = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++)
            for (int x = 0; x < MAP_SIZE; x++)
                firstPass[x + y * MAP_SIZE] = PngToMapColors.findClosestMapColorByte(
                        tileImg.getRGB(x, y), legalOnly, oklabLookup);

        boolean[] palette = requantizeTilePalette
                ? PngToMapColors.buildPalette(selectionFilteredColors(selMask))
                : PngToMapColors.buildPalette(firstPass);

        return switch (algo) {
            case NONE -> {
                if (matchMetric == PngToMapColors.MatchMetric.OKLAB && !requantizeTilePalette) yield firstPass;
                byte[] out = new byte[MAP_BYTES];
                float[] palHues = matchMetric == PngToMapColors.MatchMetric.HUE_ONLY
                        ? PngToMapColors.buildPaletteHues(palette, oklabLookup) : null;
                for (int y = 0; y < MAP_SIZE; y++)
                    for (int x = 0; x < MAP_SIZE; x++) {
                        int argb = tileImg.getRGB(x, y);
                        if (((argb >>> 24) & 0xFF) < 128) { out[x + y * MAP_SIZE] = 0; continue; }
                        float[] lab = PngToMapColors.rgbToOklab(argb);
                        out[x + y * MAP_SIZE] = PngToMapColors.findClosestInPalette(
                                lab[0], lab[1], lab[2], palette, oklabLookup, matchMetric, rgbLookup, palHues);
                    }
                yield out;
            }
            case FLOYD_STEINBERG -> {
                float[] flat = new float[MAP_BYTES];
                Arrays.fill(flat, 1.0f);
                yield PngToMapColors.renderDithered(tileImg, palette, oklabLookup, flat, MAP_SIZE, MAP_SIZE,
                        fsErrorStrength, matchMetric, rgbLookup);
            }
            case ATKINSON -> PngToMapColors.renderAtkinson(tileImg, palette, oklabLookup, MAP_SIZE, MAP_SIZE,
                    matchMetric, rgbLookup);
            case BAYER_4X4 -> PngToMapColors.renderBayer4x4(tileImg, palette, oklabLookup, MAP_SIZE, MAP_SIZE,
                    matchMetric, rgbLookup);
        };
    }

    /** Per-tile FS path with explicit dither-strength array and error diffusion scalar. */
    private byte[] requantizePerTile(BufferedImage src, float[] strengthMap, float diffuseAmount) {
        int totalW = PayloadState.gridColumns * MAP_SIZE;
        int totalH = PayloadState.gridRows * MAP_SIZE;
        BufferedImage scaledFull = PngToMapColors.scale(src, totalW, totalH);
        int tileCol = PayloadState.tileCol(tileIndex);
        int tileRow = PayloadState.tileRow(tileIndex);
        BufferedImage tileImg = scaledFull.getSubimage(
                tileCol * MAP_SIZE, tileRow * MAP_SIZE, MAP_SIZE, MAP_SIZE);
        float[][] oklabLookup = PngToMapColors.buildOklabLookup();
        boolean legalOnly = !PayloadState.allShades;
        byte[] firstPass = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++)
            for (int x = 0; x < MAP_SIZE; x++)
                firstPass[x + y * MAP_SIZE] = PngToMapColors.findClosestMapColorByte(
                        tileImg.getRGB(x, y), legalOnly, oklabLookup);
        boolean[] palette = requantizeTilePalette
                ? PngToMapColors.buildPalette(selectionFilteredColors(selMask))
                : PngToMapColors.buildPalette(firstPass);
        return PngToMapColors.renderDithered(tileImg, palette, oklabLookup, strengthMap, MAP_SIZE, MAP_SIZE, diffuseAmount);
    }

    private void commitPreview() {
        if (!inPreview || previewColors == null) return;
        history.snapshot();
        System.arraycopy(previewColors, 0, mapColors, 0, MAP_BYTES);
        dirty = true;
        rebuildTileColors();
        inPreview = false; previewColors = null; previewSelMask = null; cachedRequantizeSrc = null;
    }

    private void cycleEditorDither() {
        PngToMapColors.DitherAlgo[] values = PngToMapColors.DitherAlgo.values();
        editorDitherAlgo = values[(editorDitherAlgo.ordinal() + 1) % values.length];
        String name = switch (editorDitherAlgo) {
            case NONE -> "none";
            case FLOYD_STEINBERG -> "Floyd-Steinberg";
            case ATKINSON -> "Atkinson";
            case BAYER_4X4 -> "Bayer 4×4";
        };
        setStatusMsg("Dither: " + name);
    }

    /** Returns mapColors with unselected pixels zeroed out, or mapColors unchanged when no selection. */
    private byte[] selectionFilteredColors(boolean[] mask) {
        if (mask == null) return mapColors;
        byte[] filtered = new byte[MAP_BYTES];
        for (int i = 0; i < MAP_BYTES; i++) if (mask[i]) filtered[i] = mapColors[i];
        return filtered;
    }

    private void cycleMatchMetric() {
        PngToMapColors.MatchMetric[] values = PngToMapColors.MatchMetric.values();
        matchMetric = values[(matchMetric.ordinal() + 1) % values.length];
        String name = switch (matchMetric) {
            case OKLAB        -> "OKLab (perceptual)";
            case CHROMA_FIRST -> "Chroma-first";
            case LUMA_FIRST   -> "Luma-first";
            case HUE_ONLY     -> "Hue-only";
            case RGB          -> "Linear RGB";
        };
        setStatusMsg("Match metric: " + name);
    }

    private void applyColorReduction() {
        float[][] oklabLookup = PngToMapColors.buildOklabLookup();
        history.snapshot();
        int[] result = PngToMapColors.reduceOneStep(mapColors, selMask, oklabLookup, editorReductionStrategy);
        dirty = true;
        rebuildTileColors();
        int before = result[0], after = result[1], pixels = result[2];
        if (before == after) {
            setStatusMsg("No reduction possible" + (selMask != null ? " in selection." : "."));
        } else {
            String scope = selMask != null ? " (selection)" : "";
            setStatusMsg(String.format("Reduced: %d→%d colors%s, %d px. Ctrl+Z to undo.",
                    before, after, scope, pixels));
        }
    }

    private void cycleReductionStrategy() {
        PngToMapColors.Strategy[] values = PngToMapColors.Strategy.values();
        editorReductionStrategy = values[(editorReductionStrategy.ordinal() + 1) % values.length];
        String name = switch (editorReductionStrategy) {
            case RAREST   -> "rarest (removes least-frequent color)";
            case CLOSEST  -> "closest (merges most-similar pair)";
            case WEIGHTED -> "weighted (freq-scaled pair merge)";
        };
        setStatusMsg("Reduction strategy: " + name);
    }

    private void setStatusMsg(String msg) {
        statusMsg = msg;
        statusMsgExpiry = System.currentTimeMillis() + 3000;
    }

    private void ensureDitherMask() {
        if (ditherMask == null) {
            ditherMask = new float[MAP_BYTES];
            Arrays.fill(ditherMask, 1f);
        }
        useCustomMask = true;
    }

    // ── Color merge ──────────────────────────────────────────────────────

    private MergeScope[] availableMergeScopes() {
        if (PayloadState.tiles.size() > 1) return MergeScope.values();
        if (totalFrames > 1)              return new MergeScope[]{MergeScope.FRAME, MergeScope.TILE};
        return new MergeScope[]{MergeScope.FRAME};
    }

    /** Applies the pending merge to one frame. Respects selMask only for the active frame. */
    private int applyMergeToFrame(byte[] frame, boolean respectSel) {
        int replaced = 0;
        for (int i = 0; i < MAP_BYTES; i++) {
            if (respectSel && selMask != null && !selMask[i]) continue;
            if (mergeSources.contains(frame[i] & 0xFF)) {
                frame[i] = (byte) activeColor;
                replaced++;
            }
        }
        return replaced;
    }

    private void commitMerge() {
        if (mergeSources.isEmpty()) return;
        // Exclude the replacement color from the source set — it's a no-op and
        // would make the "N sources replaced" count inaccurate.
        mergeSources.remove(activeColor);
        if (mergeSources.isEmpty()) {
            setStatusMsg("All queued colors are already the target color.");
            return;
        }
        history.snapshot();

        switch (mergeScope) {
            case FRAME -> {
                int replaced = applyMergeToFrame(mapColors, true);
                String sel = selMask != null ? " (in selection)" : "";
                setStatusMsg(String.format("Merged %d color%s → idx %d: %d px replaced%s.",
                        mergeSources.size(), mergeSources.size() == 1 ? "" : "s",
                        activeColor, replaced, sel));
            }
            case TILE -> {
                int total = 0;
                for (byte[] frame : allFrames) total += applyMergeToFrame(frame, true);
                String tileSel = selMask != null ? " (in selection)" : "";
                setStatusMsg(String.format("Merged %d color%s → idx %d across %d frame%s: %d px replaced%s.",
                        mergeSources.size(), mergeSources.size() == 1 ? "" : "s",
                        activeColor, totalFrames, totalFrames == 1 ? "" : "s", total, tileSel));
            }
            case ALL_TILES -> {
                commitMergeAllTiles();
                return; // commitMergeAllTiles handles dirty/rebuildTileColors/clear
            }
        }

        dirty = true;
        rebuildTileColors();
        mergeSources.clear();
    }

    private void commitMergeAllTiles() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String playerName = mc.player != null ? mc.player.getGameProfile().getName() : "Player";
        int totalTiles = PayloadState.tiles.size();
        int totalReplaced = 0;

        // Capture undo state for all other tiles before modifying them
        for (int i = 0; i < totalTiles; i++) {
            if (i != tileIndex) LoominaryCommand.capturePreReductionState(i);
        }

        // Apply to current tile (all frames) — respect active selection
        for (byte[] frame : allFrames) totalReplaced += applyMergeToFrame(frame, true);
        dirty = true;

        // Apply to all other tiles: decode → merge → re-encode
        List<Integer> overBudget = new ArrayList<>();
        for (int i = 0; i < totalTiles; i++) {
            if (i == tileIndex) continue;
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            if (tile.chunks.isEmpty()) continue;
            try {
                byte[][] frames = LoominaryCommand.resolveAllFramesForTile(tile, tile.chunks);
                int[] delays;
                if (tile.frameDelays != null && !tile.frameDelays.isEmpty()) {
                    delays = tile.frameDelays.stream().mapToInt(Integer::intValue).toArray();
                } else {
                    delays = new int[frames.length];
                    Arrays.fill(delays, 100);
                }
                for (byte[] frame : frames) {
                    for (int j = 0; j < MAP_BYTES; j++) {
                        if (mergeSources.contains(frame[j] & 0xFF)) {
                            frame[j] = (byte) activeColor;
                            totalReplaced++;
                        }
                    }
                }
                LoominaryCommand.saveEditorChanges(frames, delays, i, playerName);
                if (LoominaryCommand.isTileOverBudget(i)) overBudget.add(i + 1);
            } catch (Exception e) {
                setStatusMsg("Error on tile " + (i + 1) + ": " + e.getMessage());
                mergeSources.clear();
                rebuildTileColors();
                return;
            }
        }

        rebuildTileColors();
        String budgetWarn = overBudget.isEmpty() ? ""
                : " §cTiles " + overBudget + " over budget.";
        setStatusMsg(String.format("Merged %d color%s → idx %d across all %d tiles: %d px replaced.%s "
                + "§8/loominary reduce undo all to revert.",
                mergeSources.size(), mergeSources.size() == 1 ? "" : "s",
                activeColor, totalTiles, totalReplaced, budgetWarn));
        mergeSources.clear();
    }

    // ── In-editor filter ────────────────────────────────────────────────

    private void applyEditorFilter() {
        PngToMapColors.FilterParams params = switch (editorFilterType) {
            case SMOOTH    -> PngToMapColors.FilterParams.smooth(1.0f);
            case MEDIAN    -> PngToMapColors.FilterParams.median(1);
            case SHARPEN   -> PngToMapColors.FilterParams.sharpen(0.8f);
            case POSTERIZE -> PngToMapColors.FilterParams.posterize(4);
        };

        // Build a BufferedImage from the full frame (spatial filter needs correct edge context).
        BufferedImage img = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int cb = mapColors[x + y * MAP_SIZE] & 0xFF;
                img.setRGB(x, y, cb == 0 ? 0x00000000 : (0xFF000000 | colorLookup[cb]));
            }
        }
        BufferedImage filtered = PngToMapColors.applyFilter(img, params);

        float[][] oklabLookup = PngToMapColors.buildOklabLookup();

        // Remap candidate set: selection-scoped when selMask is active.
        // Computed before the loop so we don't read stale post-edit values.
        int[] candidateColors;
        if (selMask != null) {
            boolean[] inSel = new boolean[256];
            for (int i = 0; i < MAP_BYTES; i++)
                if (selMask[i]) inSel[mapColors[i] & 0xFF] = true;
            inSel[0] = false;
            List<Integer> list = new ArrayList<>();
            for (int c = 1; c < 256; c++) if (inSel[c]) list.add(c);
            candidateColors = list.stream().mapToInt(Integer::intValue).toArray();
        } else {
            candidateColors = tileColors;
        }

        history.snapshot();
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int idx = x + y * MAP_SIZE;
                if (selMask != null && !selMask[idx]) continue;  // write-back only to selection
                if (mapColors[idx] == 0) continue;               // keep transparent
                int rgb = filtered.getRGB(x, y);
                if (((rgb >> 24) & 0xFF) < 128) { mapColors[idx] = 0; continue; }
                float[] target = PngToMapColors.rgbToOklab(rgb);
                int bestColor = mapColors[idx] & 0xFF;
                float bestDist = Float.MAX_VALUE;
                for (int c : candidateColors) {
                    if (c == 0 || oklabLookup[c] == null) continue;
                    float dL = target[0]-oklabLookup[c][0], da = target[1]-oklabLookup[c][1],
                          db = target[2]-oklabLookup[c][2];
                    float d = dL*dL + da*da + db*db;
                    if (d < bestDist) { bestDist = d; bestColor = c; }
                }
                mapColors[idx] = (byte) bestColor;
            }
        }
        dirty = true;
        rebuildTileColors();
        String scope = selMask != null ? "selection" : "frame";
        boolean weakInEditor = editorFilterType == PngToMapColors.FilterParams.FilterType.POSTERIZE
                || editorFilterType == PngToMapColors.FilterParams.FilterType.SHARPEN;
        String hint = weakInEditor ? " (use /loominary filter for full effect)" : "";
        setStatusMsg(String.format("Applied %s to %s. Ctrl+Z to undo.%s",
                editorFilterType.name().toLowerCase(), scope, hint));
    }

    // ── Re-encode on close ───────────────────────────────────────────────

    private void reEncode() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || PayloadState.tiles.isEmpty()) return;
        String playerName = client.player.getGameProfile().getName();
        net.zerohpminecraft.command.LoominaryCommand.saveEditorChanges(allFrames, frameDelayMs, tileIndex, playerName);
        System.out.println("[Loominary] Editor: saved changes for " + tileLabel
                + (totalFrames > 1 ? " (" + totalFrames + " frames)" : ""));
    }

    // ── Palette helpers ──────────────────────────────────────────────────

    private int[] computeTileColors() {
        int[] freq = new int[256];
        for (byte b : mapColors) freq[b & 0xFF]++;

        // Group colors by ramp (same base color id = c >> 2). Within each group,
        // order shades 0→3 (lightest to darkest) for a visual gradient. Order groups
        // by their rarest member's frequency ascending so removal candidates appear first.
        Map<Integer, List<Integer>> rampGroups = new java.util.LinkedHashMap<>();
        for (int c = 1; c < 256; c++) {
            if (freq[c] > 0) rampGroups.computeIfAbsent(c >> 2, k -> new ArrayList<>()).add(c);
        }
        // Sort groups: ascending by minimum frequency in the group (rarest group first).
        List<Map.Entry<Integer, List<Integer>>> groupList = new ArrayList<>(rampGroups.entrySet());
        groupList.sort((ga, gb) -> {
            int minA = ga.getValue().stream().mapToInt(c -> freq[c]).min().orElse(0);
            int minB = gb.getValue().stream().mapToInt(c -> freq[c]).min().orElse(0);
            return Integer.compare(minA, minB);
        });
        // Within each group, sort shades in ascending order (shade = c & 3, 0=light→3=dark).
        for (Map.Entry<Integer, List<Integer>> e : groupList)
            e.getValue().sort(Comparator.comparingInt(c -> c & 3));

        boolean hasTransparent = freq[0] > 0;
        int offset = hasTransparent ? 1 : 0;
        List<int[]> ordered = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : groupList)
            for (int c : e.getValue()) ordered.add(new int[]{c, freq[c]});

        int sz = ordered.size() + offset;
        int[] out   = new int[sz];
        int[] freqs = new int[sz];
        if (hasTransparent) { out[0] = 0; freqs[0] = freq[0]; }
        for (int i = 0; i < ordered.size(); i++) {
            out[offset + i]   = ordered.get(i)[0];
            freqs[offset + i] = ordered.get(i)[1];
        }
        tileColorFreqs = freqs;
        maxTileFreq = ordered.stream().mapToInt(e -> e[1]).max().orElse(0);
        return out;
    }

    private int[] computeAllMapColors() {
        List<Integer> all = new ArrayList<>();
        all.add(0);
        for (int c = 1; c < 256; c++) {
            if (colorLookup[c] != 0) all.add(c);
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    private void rebuildTileColors() {
        tileColors = computeTileColors();
        rebuildHeatLut();
        rebuildBudgetCache();
    }

    private void rebuildBudgetCache() {
        cachedDistinctCount = PngToMapColors.countDistinct(mapColors);
        cachedIsCarpet = !PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size()
                && PayloadState.tiles.get(tileIndex).carpetEncoded;
        byte[] compressed = Zstd.compress(mapColors, Zstd.maxCompressionLevel());
        if (cachedIsCarpet) {
            cachedBudgetUsed = compressed.length;
            cachedBudgetMax  = CarpetChannel.MAX_TOTAL_BYTES;
        } else {
            cachedBudgetUsed = CjkCodec.chunksNeeded(compressed);
            cachedBudgetMax  = 63;
        }
    }

    /** Rebuild the per-color heat LUT from the current frame's frequency distribution. */
    private void rebuildHeatLut() {
        int[] f = new int[256];
        for (byte b : mapColors) f[b & 0xFF]++;
        List<Integer> colors = new ArrayList<>();
        for (int c = 1; c < 256; c++) if (f[c] > 0) colors.add(c);
        colors.sort(Comparator.comparingInt(c -> f[c])); // ascending = rarest first
        int n = colors.size();
        for (int i = 0; i < n; i++) heatLut[colors.get(i)] = heatColor(i, n);
    }

    /** rank=0 → red (rarest/costly), rank=total-1 → blue (most-frequent/cheap). */
    private static int heatColor(int rank, int total) {
        if (total <= 1) return 0xFFFF2020;
        return 0xFF000000 | hslToRgb(240f * rank / (total - 1), 0.9f, 0.55f);
    }

    private static int hslToRgb(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = l - c / 2f;
        float r, g, b;
        if      (h < 60f)  { r = c; g = x; b = 0; }
        else if (h < 120f) { r = x; g = c; b = 0; }
        else if (h < 180f) { r = 0; g = c; b = x; }
        else if (h < 240f) { r = 0; g = x; b = c; }
        else if (h < 300f) { r = x; g = 0; b = c; }
        else               { r = c; g = 0; b = x; }
        return ((int)((r + m) * 255) << 16) | ((int)((g + m) * 255) << 8) | (int)((b + m) * 255);
    }

    // ── Coordinate helpers ────────────────────────────────────────────────

    private int screenToMap(float screenRelative) {
        return (int) Math.floor(screenRelative / scale);
    }

    private boolean inMapBounds(int mx, int my) {
        return mx >= 0 && mx < MAP_SIZE && my >= 0 && my < MAP_SIZE;
    }

    private void centerMap() {
        int canvasW = width - PANEL_W - GUIDE_W;
        translateX = GUIDE_W + (canvasW - MAP_SIZE * scale) / 2f;
        translateY = (height - STATUS_H - MAP_SIZE * scale) / 2f;
    }

    private void switchFrame(int newFrame) {
        if (newFrame == activeFrame || newFrame < 0 || newFrame >= totalFrames) return;
        activeFrame = newFrame;
        mapColors   = allFrames[newFrame];
        history.clear();
        inPreview = false; previewColors = null; previewSelMask = null; cachedRequantizeSrc = null;
        // Sync requantize source frame from provenance for this new editor frame.
        if (!PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size()) {
            List<Integer> src = PayloadState.tiles.get(tileIndex).frameSourceIndices;
            if (src != null && newFrame < src.size())
                requantizeSourceFrame = src.get(newFrame);
        }
        rebuildTileColors();
    }

    /** Updates cachedRequantizeSrc from cachedGifFrames at the current requantizeSourceFrame. */
    private void updateCachedSrcFromGifFrames() {
        if (cachedGifFrames != null)
            cachedRequantizeSrc = cachedGifFrames[Math.min(requantizeSourceFrame, cachedGifFrames.length - 1)];
        else
            cachedRequantizeSrc = null;
    }

    /**
     * Inserts a new frame after activeFrame, derived from cachedGifFrames[requantizeSourceFrame].
     * Loads cachedGifFrames from disk if not yet cached.
     */
    private void insertFrameFromSource() {
        String fname = PayloadState.currentSourceFilename;
        if (fname == null) return;
        Path sourcePath = FabricLoader.getInstance().getGameDir()
                .resolve("loominary_data").resolve(fname);
        try {
            if (cachedGifFrames == null)
                cachedGifFrames = PngToMapColors.coalesceGifFrames(sourcePath);
            BufferedImage src = cachedGifFrames[Math.min(requantizeSourceFrame, cachedGifFrames.length - 1)];
            byte[] newFrame = computeRequantizeTile(src);

            int insertAt = activeFrame + 1;
            byte[][] newFrames  = new byte[totalFrames + 1][];
            int[]    newDelays  = new int[totalFrames + 1];
            List<Integer> oldSrc = (!PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size())
                    ? PayloadState.tiles.get(tileIndex).frameSourceIndices : null;
            List<Integer> newSrc = (oldSrc != null) ? new ArrayList<>() : null;

            for (int i = 0; i < insertAt; i++) {
                newFrames[i] = allFrames[i];
                newDelays[i] = frameDelayMs[i];
                if (newSrc != null) newSrc.add(oldSrc.get(i));
            }
            newFrames[insertAt] = newFrame;
            newDelays[insertAt] = frameDelayMs[activeFrame];
            if (newSrc != null) newSrc.add(requantizeSourceFrame);
            for (int i = insertAt; i < totalFrames; i++) {
                newFrames[i + 1] = allFrames[i];
                newDelays[i + 1] = frameDelayMs[i];
                if (newSrc != null) newSrc.add(oldSrc.get(i));
            }

            allFrames    = newFrames;
            frameDelayMs = newDelays;
            totalFrames  = newFrames.length;
            activeFrame  = insertAt;
            mapColors    = allFrames[activeFrame];
            if (newSrc != null && !PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size())
                PayloadState.tiles.get(tileIndex).frameSourceIndices = newSrc;
            history.clear();
            inPreview = false; previewColors = null; previewSelMask = null;
            rebuildTileColors();
            dirty = true;
            setStatusMsg("Inserted frame " + (insertAt + 1) + " from GIF frame "
                    + (requantizeSourceFrame + 1) + ". " + totalFrames + " frames total.");
        } catch (IOException e) {
            setStatusMsg("Insert failed: " + e.getMessage());
        }
    }

    private void dropCurrentFrame() {
        if (totalFrames <= 1) {
            setStatusMsg("Cannot drop the last frame.");
            return;
        }
        int drop = activeFrame;
        byte[][] newFrames = new byte[totalFrames - 1][];
        int[] newDelays = new int[totalFrames - 1];
        // Prune provenance list in parallel with frame list.
        List<Integer> oldSrc = (!PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size())
                ? PayloadState.tiles.get(tileIndex).frameSourceIndices : null;
        List<Integer> newSrc = (oldSrc != null) ? new ArrayList<>() : null;
        int dst = 0;
        for (int i = 0; i < totalFrames; i++) {
            if (i == drop) continue;
            newFrames[dst] = allFrames[i];
            newDelays[dst] = frameDelayMs != null && i < frameDelayMs.length ? frameDelayMs[i] : 100;
            if (newSrc != null) newSrc.add(oldSrc.get(Math.min(i, oldSrc.size() - 1)));
            dst++;
        }
        allFrames    = newFrames;
        frameDelayMs = newDelays;
        totalFrames  = newFrames.length;
        activeFrame  = Math.min(activeFrame, totalFrames - 1);
        mapColors    = allFrames[activeFrame];
        if (newSrc != null && !PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size()) {
            PayloadState.tiles.get(tileIndex).frameSourceIndices = newSrc;
            requantizeSourceFrame = newSrc.get(Math.min(activeFrame, newSrc.size() - 1));
        }
        history.clear();
        inPreview = false; previewColors = null; previewSelMask = null; cachedRequantizeSrc = null;
        rebuildTileColors();
        dirty = true;
        setStatusMsg("Dropped frame " + (drop + 1) + ". " + totalFrames + " frame" + (totalFrames == 1 ? "" : "s") + " remaining.");
    }

    // ── EditHistory ──────────────────────────────────────────────────────

    private class EditHistory {
        private final Deque<byte[][]> undoStack = new ArrayDeque<>();
        private final Deque<byte[][]> redoStack = new ArrayDeque<>();

        void snapshot() {
            if (undoStack.size() >= UNDO_DEPTH) undoStack.removeLast();
            undoStack.push(cloneAllFrames());
            redoStack.clear();
        }

        void undo() {
            if (undoStack.isEmpty()) return;
            redoStack.push(cloneAllFrames());
            restoreAllFrames(undoStack.pop());
            dirty = true; rebuildTileColors();
        }

        void redo() {
            if (redoStack.isEmpty()) return;
            undoStack.push(cloneAllFrames());
            restoreAllFrames(redoStack.pop());
            dirty = true; rebuildTileColors();
        }

        boolean canUndo() { return !undoStack.isEmpty(); }
        boolean canRedo() { return !redoStack.isEmpty(); }
        void clear() { undoStack.clear(); redoStack.clear(); }

        private byte[][] cloneAllFrames() {
            byte[][] snap = new byte[allFrames.length][];
            for (int f = 0; f < allFrames.length; f++) snap[f] = allFrames[f].clone();
            return snap;
        }

        private void restoreAllFrames(byte[][] snap) {
            for (int f = 0; f < Math.min(snap.length, allFrames.length); f++)
                System.arraycopy(snap[f], 0, allFrames[f], 0, MAP_BYTES);
        }
    }
}
