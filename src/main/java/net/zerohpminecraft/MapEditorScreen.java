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
    private static final int PANEL_W         = 160;
    private static final int SCROLL_W        = 6;
    private static final int GUIDE_W         = 133;
    private static final int SWATCH_SZ       = 12;
    private static final int SWATCH_GAP      = 4;   // extra 2px used for frequency bar
    private static final int SWATCH_STRIDE   = SWATCH_SZ + SWATCH_GAP;
    private static final int PANEL_MARGIN    = 2;
    private static final int SWATCHES_PER_ROW = 8;
    private static final int SWATCHES_START_Y = 84;
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

    // Magic wand drag-extend + hover preview
    private boolean   wandDragging         = false;
    private boolean[] wandPreview          = null;
    private int       wandPreviewX         = -1, wandPreviewY = -1;
    private float     wandPreviewTolerance = -1f;

    // Palette swatch hover highlight (-1 = none)
    private int paletteHoverColor = -1;

    private final EditHistory history = new EditHistory();

    // Palette panel
    private int[]   tileColors;
    private int[]   tileColorFreqs = new int[0];  // parallel to tileColors: pixel count per color
    private int     maxTileFreq    = 0;
    private int[]   allMapColors;
    private enum PaletteMode { TILE, ALL, SEL }
    private PaletteMode paletteMode    = PaletteMode.TILE;
    private int         paletteScrollRow = 0;

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
    private float     atkErrorStrength   = 1.0f;   // Atkinson diffusion amount (0.1–1.0)
    private float     bayerScale         = 0.08f;  // Bayer threshold amplitude (0.02–0.20)
    private float     chromaBoost        = 1.0f;   // OKLab a,b multiplier before quantization
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

    private boolean   inPreview            = false;
    private byte[]    previewColors        = null;
    private boolean[] previewSelMask       = null;  // null = whole tile
    private byte[][]  allTilePreviewColors = null;  // non-active tile previews; null when not in preview
    // Cross-tile grid requantize (Shift+R): full result for every tile, pending commit
    private byte[][] pendingGridResult = null;

    // ── Clipboard / paste state ──────────────────────────────────────────────
    private int       clipboardW = 0, clipboardH = 0;
    private byte[]    clipboardColors = null; // flat [clipboardW * clipboardH], index into colorLookup
    private boolean[] clipboardMask   = null; // true = copied pixel; same layout as clipboardColors
    private boolean   inPasteMode     = false;

    // Tier 2 — all tile frames loaded into memory for multi-tile canvas
    private byte[][][] allTileFrames = null; // [tileIdx][frameIdx][pixelIdx]; null until init()
    private boolean multiTileMode    = false; // toggled with Shift+G; auto-enabled when tiles > 1
    // Tier 3 — per-tile dirty tracking for cross-tile edits; saved on close
    private boolean[] tileDirty      = null; // [tileIdx]; null until loadAllTileFrames

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
    private float prevHealth = -1f;

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
        boolean firstInit = (allTileFrames == null);
        if (firstInit) {
            loadAllTileFrames();
            multiTileMode = PayloadState.tiles.size() > 1;
        }
        recomputeScale();
        centerMap();
        rebuildTileColors();
        allMapColors = computeAllMapColors();
        if (tileColors.length > 0) activeColor = tileColors[0];
    }

    @Override
    public void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            float health = mc.player.getHealth();
            if (prevHealth >= 0 && health < prevHealth) close();
            prevHealth = health;
        }
    }

    private void recomputeScale() {
        int canvasW = width  - PANEL_W - GUIDE_W;
        int canvasH = height - STATUS_H;
        if (multiTileMode && PayloadState.tiles.size() > 1) {
            int cols = gridCols(), rows = gridRows();
            int scaleX = cols > 0 ? canvasW / (cols * MAP_SIZE) : canvasW / MAP_SIZE;
            int scaleY = rows > 0 ? canvasH / (rows * MAP_SIZE) : canvasH / MAP_SIZE;
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(scaleX, scaleY)));
        } else {
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(canvasW, canvasH) / MAP_SIZE));
        }
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // We fill our own background; suppress Minecraft's dark gradient overlay
    }

    private boolean discardOnClose = false;

    @Override
    public void close() {
        if (!discardOnClose) saveAllDirtyTiles();
        MinecraftClient.getInstance().options.getMenuBackgroundBlurriness().setValue(savedBlur);
        super.close();
    }

    private void closeDiscarding() {
        discardOnClose = true;
        close();
    }

    /** Saves the active tile (if dirty) and any other tiles dirtied by cross-tile operations. */
    private void saveAllDirtyTiles() {
        if (dirty) { reEncode(); dirty = false; }
        if (tileDirty == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        String playerName = mc.player != null ? mc.player.getGameProfile().getName() : "Player";
        for (int i = 0; i < tileDirty.length; i++) {
            if (!tileDirty[i] || i == tileIndex) continue;
            if (allTileFrames == null || i >= allTileFrames.length || allTileFrames[i] == null) continue;
            if (i >= PayloadState.tiles.size()) continue;
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            int[] delays = tile.frameDelays != null && !tile.frameDelays.isEmpty()
                    ? tile.frameDelays.stream().mapToInt(Integer::intValue).toArray()
                    : new int[allTileFrames[i].length];
            if (tile.frameDelays == null || tile.frameDelays.isEmpty()) Arrays.fill(delays, 100);
            try {
                LoominaryCommand.saveEditorChanges(allTileFrames[i], delays, i, playerName);
            } catch (Exception e) {
                System.err.println("[Loominary] Failed to save tile " + (i+1) + " on close: " + e.getMessage());
            }
            tileDirty[i] = false;
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int canvasW = width  - PANEL_W;
        int canvasH = height - STATUS_H;
        if (mouseX >= GUIDE_W && mouseX < canvasW && mouseY < canvasH) {
            int gx = screenToMap((float) mouseX - translateX);
            int gy = screenToMap((float) mouseY - translateY);
            if (multiTileMode && PayloadState.tiles.size() > 1) {
                hoverMapX = (gx >= 0 && gx < gridW()) ? gx : -1;
                hoverMapY = (gy >= 0 && gy < gridH()) ? gy : -1;
            } else {
                int gx0 = activeTileGx0(), gy0 = activeTileGy0();
                hoverMapX = (gx >= gx0 && gx < gx0 + MAP_SIZE) ? gx : -1;
                hoverMapY = (gy >= gy0 && gy < gy0 + MAP_SIZE) ? gy : -1;
            }
            if (hoverMapX < 0 || hoverMapY < 0) { hoverMapX = hoverMapY = -1; }
        } else {
            hoverMapX = hoverMapY = -1;
        }

        // Update wand hover preview — in single-tile mode only over active tile; global in multi-tile
        boolean wandHoverOk = inMapBounds(hoverMapX, hoverMapY)
                && (multiTileMode || inActiveTile(hoverMapX, hoverMapY));
        if (!inPreview && !inPasteMode && currentTool == Tool.MAGIC_WAND && !wandDragging && wandHoverOk) {
            if (wandPreviewX != hoverMapX || wandPreviewY != hoverMapY || wandPreviewTolerance != fillTolerance) {
                wandPreview          = computeWandRegion(hoverMapX, hoverMapY);
                wandPreviewX         = hoverMapX;
                wandPreviewY         = hoverMapY;
                wandPreviewTolerance = fillTolerance;
            }
        } else if (currentTool != Tool.MAGIC_WAND || inPreview) {
            wandPreview  = null;
            wandPreviewX = -1; wandPreviewY = -1;
        }

        ctx.fill(0, 0, width, height, COL_BG);
        renderMap(ctx);
        renderDitherMaskOverlay(ctx);
        renderToolOverlay(ctx);
        renderWandPreview(ctx);
        renderSelectionOverlay(ctx);
        renderLassoPath(ctx);
        renderPasteOverlay(ctx);
        renderMapBorder(ctx);
        renderPalettePanel(ctx, mouseX, mouseY);
        renderPaletteHoverOverlay(ctx);
        renderStatusBar(ctx);
        renderGuidePanel(ctx);
        if (showMinimap) renderMinimap(ctx, mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderMap(DrawContext ctx) {
        if (allTileFrames == null) return;
        if (!multiTileMode || PayloadState.tiles.size() <= 1) {
            renderActiveTile(ctx);
            return;
        }
        int cols    = PayloadState.gridColumns;
        int totalW  = cols * MAP_SIZE;
        int totalH  = PayloadState.gridRows * MAP_SIZE;
        int canvasH = height - STATUS_H;
        int minGx = Math.max(0,         screenToMap(-translateX));
        int maxGx = Math.min(totalW - 1, screenToMap(width - PANEL_W - translateX));
        int minGy = Math.max(0,         screenToMap(-translateY));
        int maxGy = Math.min(totalH - 1, screenToMap(canvasH - translateY));

        boolean usePreview = inPreview && previewColors != null;

        for (int gy = minGy; gy <= maxGy; gy++) {
            for (int gx = minGx; gx <= maxGx; gx++) {
                int tc = gx / MAP_SIZE, tr = gy / MAP_SIZE;
                int ti = tr * cols + tc;
                int lx = gx % MAP_SIZE, ly = gy % MAP_SIZE;
                int localIdx = lx + ly * MAP_SIZE;
                boolean isActive = (ti == tileIndex);

                byte[] frame;
                if (isActive) {
                    frame = mapColors;
                } else if (ti < allTileFrames.length && allTileFrames[ti] != null) {
                    int f = Math.min(activeFrame, allTileFrames[ti].length - 1);
                    frame = allTileFrames[ti][f];
                } else {
                    int sx = (int)(translateX + gx * scale), sy = (int)(translateY + gy * scale);
                    ctx.fill(sx, sy, sx + scale, sy + scale, 0xFF111111);
                    continue;
                }

                byte[] tilePreview = !isActive && allTilePreviewColors != null
                        && ti < allTilePreviewColors.length ? allTilePreviewColors[ti] : null;
                boolean showPrev = usePreview && (isActive
                        ? (previewSelMask == null || previewSelMask[selIdx(gx, gy)])
                        : tilePreview != null);
                byte[] displayFrame = showPrev ? (isActive ? previewColors : tilePreview) : frame;
                int cb = displayFrame[localIdx] & 0xFF;
                int argb = cb == 0 ? ((gx + gy) % 2 == 0 ? COL_TRANS_A : COL_TRANS_B)
                        : (heatmapMode && isActive ? heatLut[cb] : (0xFF000000 | colorLookup[cb]));

                int sx = (int)(translateX + gx * scale), sy = (int)(translateY + gy * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, argb);
                // no dim on non-active tiles — white border is sufficient active-tile signal
            }
        }
    }

    /** Renders only the active tile (single-tile mode). */
    private void renderActiveTile(DrawContext ctx) {
        int gx0 = activeTileGx0(), gy0 = activeTileGy0();
        int canvasH = height - STATUS_H;
        int minPx = Math.max(0,         screenToMap(-translateX) - gx0);
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width - PANEL_W - translateX) - gx0);
        int minPy = Math.max(0,         screenToMap(-translateY) - gy0);
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY) - gy0);
        if (minPx > maxPx || minPy > maxPy) return;

        boolean usePreview = inPreview && previewColors != null;
        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                int idx = px + py * MAP_SIZE;
                boolean showPrev = usePreview && (previewSelMask == null || previewSelMask[activeSelIdx(px, py)]);
                int cb = (showPrev ? previewColors : mapColors)[idx] & 0xFF;
                int argb = cb == 0 ? ((px + py) % 2 == 0 ? COL_TRANS_A : COL_TRANS_B)
                        : (heatmapMode ? heatLut[cb] : (0xFF000000 | colorLookup[cb]));
                int sx = (int)(translateX + (gx0 + px) * scale);
                int sy = (int)(translateY + (gy0 + py) * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, argb);
            }
        }
    }

    /** Yellow heat overlay showing the dither-strength mask (0 = invisible, 1 = opaque). */
    private void renderDitherMaskOverlay(DrawContext ctx) {
        if (ditherMask == null) return;
        if (!showMask && currentTool != Tool.DITHER_BRUSH) return;
        int gx0 = activeTileGx0(), gy0 = activeTileGy0();
        int canvasH = height - STATUS_H;
        int minPx = Math.max(0,         screenToMap(-translateX) - gx0);
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width - PANEL_W - translateX) - gx0);
        int minPy = Math.max(0,         screenToMap(-translateY) - gy0);
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY) - gy0);
        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                float s = ditherMask[px + py * MAP_SIZE];
                if (s <= 0f) continue;
                int alpha = (int)(s * 90);
                int sx = (int)(translateX + (gx0 + px) * scale);
                int sy = (int)(translateY + (gy0 + py) * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, (alpha << 24) | 0x00FFCC00);
            }
        }
    }

    private void renderToolOverlay(DrawContext ctx) {
        if (!inMapBounds(hoverMapX, hoverMapY)) return;
        // For paint tools, only show overlay when hovering the active tile
        boolean onActive = inActiveTile(hoverMapX, hoverMapY);
        int sx = (int)(translateX + hoverMapX * scale);
        int sy = (int)(translateY + hoverMapY * scale);

        switch (currentTool) {
            case BRUSH -> {
                if (!onActive && !multiTileMode) break;
                int activeRgb = activeColor == 0
                        ? (COL_TRANS_A & 0xFFFFFF) : colorLookup[activeColor];
                int overlay = (activeRgb & 0x00FFFFFF) | 0x99000000;
                int r2 = brushRadius * brushRadius;
                for (int dy = -brushRadius; dy <= brushRadius; dy++) {
                    for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                        if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                        int px = hoverMapX + dx, py = hoverMapY + dy;
                        if (!inMapBounds(px, py)) continue;
                        if (!multiTileMode && !inActiveTile(px, py)) continue;
                        if (selMask != null && !selMask[selIdx(px, py)]) continue;
                        int bsx = (int)(translateX + px * scale);
                        int bsy = (int)(translateY + py * scale);
                        ctx.fill(bsx, bsy, bsx + scale, bsy + scale, overlay);
                    }
                }
            }
            case DITHER_BRUSH -> {
                if (!onActive) break;
                int r2 = brushRadius * brushRadius;
                int gx0 = activeTileGx0(), gy0 = activeTileGy0();
                int lhx = hoverMapX - gx0, lhy = hoverMapY - gy0;
                for (int dy = -brushRadius; dy <= brushRadius; dy++) {
                    for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                        if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                        int lpx = lhx + dx, lpy = lhy + dy;
                        if (!inLocalBounds(lpx, lpy)) continue;
                        int bsx = (int)(translateX + (gx0 + lpx) * scale);
                        int bsy = (int)(translateY + (gy0 + lpy) * scale);
                        ctx.fill(bsx, bsy, bsx + scale, bsy + scale, 0x66FFCC00);
                    }
                }
            }
            case FILL -> {
                if (!onActive) break;
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

    /** Renders the selection mask as a tint + marching-ants border (global coords). */
    private void renderSelectionOverlay(DrawContext ctx) {
        if (selMask == null) return;
        int gW = gridW(), gH = gridH();
        int canvasH = height - STATUS_H;
        int minGx = Math.max(0,     screenToMap(-translateX));
        int maxGx = Math.min(gW-1,  screenToMap(width - PANEL_W - translateX));
        int minGy = Math.max(0,     screenToMap(-translateY));
        int maxGy = Math.min(gH-1,  screenToMap(canvasH - translateY));
        if (minGx > maxGx || minGy > maxGy) return;
        // In single-tile mode, clamp to active tile region
        if (!multiTileMode || PayloadState.tiles.size() <= 1) {
            int gx0 = activeTileGx0(), gy0 = activeTileGy0();
            minGx = Math.max(minGx, gx0); maxGx = Math.min(maxGx, gx0 + MAP_SIZE - 1);
            minGy = Math.max(minGy, gy0); maxGy = Math.min(maxGy, gy0 + MAP_SIZE - 1);
            if (minGx > maxGx || minGy > maxGy) return;
        }

        // Tint — run-length-encoded by row
        for (int gy = minGy; gy <= maxGy; gy++) {
            int runStart = -1;
            for (int gx = minGx; gx <= maxGx + 1; gx++) {
                boolean sel = gx <= maxGx && selMask[selIdx(gx, gy)];
                if (sel && runStart < 0) { runStart = gx; }
                else if (!sel && runStart >= 0) {
                    ctx.fill((int)(translateX + runStart * scale), (int)(translateY + gy * scale),
                             (int)(translateX + gx * scale),       (int)(translateY + gy * scale) + scale,
                             0x12FFFFFF);
                    runStart = -1;
                }
            }
        }

        // Marching-ants border
        int off = (int)((System.currentTimeMillis() / 120) % 8);
        for (int gy = minGy; gy <= maxGy; gy++) {
            for (int gx = minGx; gx <= maxGx; gx++) {
                if (!selMask[selIdx(gx, gy)]) continue;
                int bsx = (int)(translateX + gx * scale);
                int bsy = (int)(translateY + gy * scale);
                if (gy == 0    || !selMask[selIdx(gx, gy-1)]) { int p=((gx+off)%8+8)%8; ctx.fill(bsx,bsy-1,bsx+scale,bsy,p<4?0xFFFFFFFF:0xFF222222); }
                if (gy == gH-1 || !selMask[selIdx(gx, gy+1)]) { int p=((gx+off)%8+8)%8; ctx.fill(bsx,bsy+scale,bsx+scale,bsy+scale+1,p<4?0xFFFFFFFF:0xFF222222); }
                if (gx == 0    || !selMask[selIdx(gx-1, gy)]) { int p=((gy+off)%8+8)%8; ctx.fill(bsx-1,bsy,bsx,bsy+scale,p<4?0xFFFFFFFF:0xFF222222); }
                if (gx == gW-1 || !selMask[selIdx(gx+1, gy)]) { int p=((gy+off)%8+8)%8; ctx.fill(bsx+scale,bsy,bsx+scale+1,bsy+scale,p<4?0xFFFFFFFF:0xFF222222); }
            }
        }
    }

    /** Draws the in-progress lasso path as yellow dots (lasso pts are global grid coords). */
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

    private void renderPasteOverlay(DrawContext ctx) {
        if (!inPasteMode || clipboardColors == null) return;
        int ox = hoverMapX - clipboardW / 2;
        int oy = hoverMapY - clipboardH / 2;
        for (int py = 0; py < clipboardH; py++) {
            for (int px = 0; px < clipboardW; px++) {
                int ci = py * clipboardW + px;
                if (!clipboardMask[ci]) continue;
                int gx = ox + px, gy = oy + py;
                int sx = (int)(translateX + gx * scale);
                int sy = (int)(translateY + gy * scale);
                int cb = clipboardColors[ci] & 0xFF;
                int rgb = cb == 0 ? (COL_TRANS_A & 0xFFFFFF) : colorLookup[cb];
                int col = inMapBounds(gx, gy)
                        ? ((0xCC000000) | rgb)
                        : (0x66FF0000);  // red tint for out-of-bounds
                ctx.fill(sx, sy, sx + scale, sy + scale, col);
            }
        }
        // thin white border around paste bounding box
        int bx = (int)(translateX + ox * scale), by = (int)(translateY + oy * scale);
        int bw = clipboardW * scale, bh = clipboardH * scale;
        ctx.fill(bx - 1, by - 1, bx + bw + 1, by,      0xCCFFFFFF);
        ctx.fill(bx - 1, by + bh, bx + bw + 1, by + bh + 1, 0xCCFFFFFF);
        ctx.fill(bx - 1, by,     bx,      by + bh, 0xCCFFFFFF);
        ctx.fill(bx + bw, by,    bx + bw + 1, by + bh, 0xCCFFFFFF);
    }

    private void renderMapBorder(DrawContext ctx) {
        int gx0 = activeTileGx0(), gy0 = activeTileGy0();
        int mx = (int)(translateX + gx0 * scale), my = (int)(translateY + gy0 * scale);
        int aw = MAP_SIZE * scale, ah = MAP_SIZE * scale;

        if (!multiTileMode || PayloadState.tiles.size() <= 1) {
            // Single-tile mode: simple border around active tile
            ctx.fill(mx-1, my-1, mx+aw+1, my,      COL_MAP_BORDER);
            ctx.fill(mx-1, my+ah, mx+aw+1, my+ah+1, COL_MAP_BORDER);
            ctx.fill(mx-1, my,   mx,       my+ah,   COL_MAP_BORDER);
            ctx.fill(mx+aw, my,  mx+aw+1,  my+ah,   COL_MAP_BORDER);
            return;
        }

        int cols = PayloadState.gridColumns, rows = PayloadState.gridRows;
        int ox = (int) translateX, oy = (int) translateY;
        int totalW = cols * MAP_SIZE * scale, totalH = rows * MAP_SIZE * scale;

        // Outer border
        ctx.fill(ox-1, oy-1, ox+totalW+1, oy,          COL_MAP_BORDER);
        ctx.fill(ox-1, oy+totalH, ox+totalW+1, oy+totalH+1, COL_MAP_BORDER);
        ctx.fill(ox-1, oy, ox,            oy+totalH,   COL_MAP_BORDER);
        ctx.fill(ox+totalW, oy, ox+totalW+1, oy+totalH, COL_MAP_BORDER);

        // Interior grid lines
        for (int c = 1; c < cols; c++) {
            int lx = ox + c * MAP_SIZE * scale;
            ctx.fill(lx - 1, oy, lx, oy + totalH, 0xFF2A2A2A);
        }
        for (int r = 1; r < rows; r++) {
            int ly = oy + r * MAP_SIZE * scale;
            ctx.fill(ox, ly - 1, ox + totalW, ly, 0xFF2A2A2A);
        }

        // Active tile highlight
        ctx.fill(mx-1, my-1, mx+aw+1, my,      0xFFFFFFFF);
        ctx.fill(mx-1, my+ah, mx+aw+1, my+ah+1, 0xFFFFFFFF);
        ctx.fill(mx-1, my,   mx,       my+ah,   0xFFFFFFFF);
        ctx.fill(mx+aw, my,  mx+aw+1,  my+ah,   0xFFFFFFFF);
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
        ctx.drawTextWithShadow(textRenderer, Text.literal(phBuilder.toString()), panelX + 4, 3, 0xFFFFFF);

        // Budget / color count badge
        String budgetColor = (cachedBudgetUsed > cachedBudgetMax) ? "§c" : "§a";
        String budgetBadge = budgetColor + cachedBudgetUsed + "§r§8/" + cachedBudgetMax;
        String badgeLine = "§8" + cachedDistinctCount + "c  " + budgetBadge;
        if (selMask != null) {
            boolean[] inSel = new boolean[256];
            for (int i = 0; i < MAP_BYTES; i++) if (selMaskAt(i)) inSel[mapColors[i] & 0xFF] = true;
            if (multiTileMode && allTileFrames != null) {
                for (int ti = 0; ti < PayloadState.tiles.size(); ti++) {
                    if (ti == tileIndex || ti >= allTileFrames.length || allTileFrames[ti] == null) continue;
                    boolean[] ts = localSelMaskForTile(ti);
                    if (ts == null) continue;
                    byte[] tf = allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)];
                    for (int j = 0; j < MAP_BYTES; j++) if (ts[j]) inSel[tf[j] & 0xFF] = true;
                }
            }
            int sd = 0; for (int c = 1; c < 256; c++) if (inSel[c]) sd++;
            badgeLine += "  §8sel:" + sd;
        }
        ctx.drawTextWithShadow(textRenderer, Text.literal(badgeLine), panelX + 4, 12, 0xFFFFFF);
        ctx.fill(panelX, 22, width, 23, COL_PANEL_SEP);

        // ── Active swatch ──────────────────────────────────────────────
        int asx = panelX + 4, asy = 26;
        ctx.fill(asx-1, asy-1, asx+ACTIVE_SWATCH_SZ+1, asy+ACTIVE_SWATCH_SZ+1, COL_RING);
        if (activeColor == 0) {
            for (int ty = 0; ty < ACTIVE_SWATCH_SZ; ty += 2)
                for (int tx = 0; tx < ACTIVE_SWATCH_SZ; tx += 2) {
                    int col = (((tx / 2) + (ty / 2)) & 1) == 0 ? COL_TRANS_A : COL_TRANS_B;
                    ctx.fill(asx + tx, asy + ty, asx + tx + 2, asy + ty + 2, col);
                }
        } else {
            ctx.fill(asx, asy, asx+ACTIVE_SWATCH_SZ, asy+ACTIVE_SWATCH_SZ,
                    0xFF000000 | colorLookup[activeColor]);
        }
        String hexStr = activeColor == 0
                ? "trans" : String.format("#%06X", colorLookup[activeColor] & 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + hexStr), panelX+28, 28, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8idx:"+activeColor), panelX+28, 37, 0xFFFFFF);

        ctx.fill(panelX, 50, width, 51, COL_PANEL_SEP);

        // ── Show: tabs (Tile | All | Sel) ─────────────────────────────
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Show: "), panelX+4, 54, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(paletteMode == PaletteMode.TILE ? "§eTile" : "§7Tile"),
                panelX+40, 54, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8|"), panelX+62, 54, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(paletteMode == PaletteMode.ALL ? "§eAll" : "§7All"),
                panelX+68, 54, 0xFFFFFF);
        if (selMask != null) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8|"), panelX+84, 54, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(paletteMode == PaletteMode.SEL ? "§eSel" : "§7Sel"),
                    panelX+90, 54, 0xFFFFFF);
        }

        ctx.fill(panelX, 66, width, 67, COL_PANEL_SEP);

        // ── Dedicated transparency row ─────────────────────────────────
        boolean tileHasTrans = tileColors.length > 0 && tileColors[0] == 0;
        boolean showTransRow = paletteMode == PaletteMode.TILE ? tileHasTrans
                : paletteMode == PaletteMode.ALL || hasTransInSel();
        int tsx = panelX + 4, tsy = 69;
        // Checkerboard swatch
        boolean transActive  = (activeColor == 0);
        boolean transHovered = (mouseX >= tsx && mouseX < tsx + SWATCH_SZ
                             && mouseY >= tsy && mouseY < tsy + SWATCH_SZ);
        if (transActive || transHovered)
            ctx.fill(tsx-1, tsy-1, tsx+SWATCH_SZ+1, tsy+SWATCH_SZ+1,
                    transActive ? COL_RING : COL_HOVER_RING);
        for (int ty = 0; ty < SWATCH_SZ; ty += 2)
            for (int tx = 0; tx < SWATCH_SZ; tx += 2) {
                int col = (((tx / 2) + (ty / 2)) & 1) == 0 ? COL_TRANS_A : COL_TRANS_B;
                ctx.fill(tsx + tx, tsy + ty, tsx + tx + 2, tsy + ty + 2, col);
            }
        String transLabel = showTransRow ? "§7trans" : "§8trans";
        ctx.drawTextWithShadow(textRenderer, Text.literal(transLabel), panelX+20, tsy+2, 0xFFFFFF);
        // Frequency bar for transparency (TILE mode)
        if (tileHasTrans && paletteMode == PaletteMode.TILE && maxTileFreq > 0) {
            int barW = Math.max(1, SWATCH_SZ * tileColorFreqs[0] / maxTileFreq);
            ctx.fill(tsx, tsy + SWATCH_SZ + 1, tsx + barW, tsy + SWATCH_SZ + 3, 0xFF2A6644);
        }

        ctx.fill(panelX, 82, width, 83, COL_PANEL_SEP);

        // ── Swatch grid ────────────────────────────────────────────────
        int[] palette = activePaletteColors();

        // Compute sel freqs inline if in SEL mode
        int[] selFreqs = null; int maxSelFreq = 0;
        if (paletteMode == PaletteMode.SEL && selMask != null) {
            selFreqs = new int[256];
            for (int i = 0; i < MAP_BYTES; i++)
                if (selMaskAt(i)) selFreqs[mapColors[i] & 0xFF]++;
            for (int f : selFreqs) if (f > maxSelFreq) maxSelFreq = f;
        }

        int[][] layout   = buildPaletteLayout(palette);
        int totalRows    = layout.length > 0 ? layout[layout.length - 1][0] + 1 : 0;
        int visibleRows  = (panelH - SWATCHES_START_Y) / SWATCH_STRIDE;
        paletteScrollRow = Math.max(0, Math.min(paletteScrollRow,
                Math.max(0, totalRows - visibleRows)));

        int hoveredSwatchColor = transHovered ? 0 : -1;
        for (int i = 0; i < palette.length; i++) {
            int absRow = layout[i][0];
            int visRow = absRow - paletteScrollRow;
            if (visRow < 0) continue;
            int col = layout[i][1];
            int swx = panelX + PANEL_MARGIN + col * SWATCH_STRIDE;
            int swy = SWATCHES_START_Y + visRow * SWATCH_STRIDE;
            if (swy + SWATCH_SZ > panelH) break;

            int c = palette[i];
            boolean isActive  = (c == activeColor);
            boolean isMerge   = mergeSources.contains(c);
            boolean isHovered = mouseX >= swx && mouseX < swx+SWATCH_SZ
                             && mouseY >= swy && mouseY < swy+SWATCH_SZ;
            if (isHovered) hoveredSwatchColor = c;
            int ringCol = isMerge ? COL_MERGE_RING : (isActive ? COL_RING : COL_HOVER_RING);
            if (isActive || isHovered || isMerge)
                ctx.fill(swx-1, swy-1, swx+SWATCH_SZ+1, swy+SWATCH_SZ+1, ringCol);
            ctx.fill(swx, swy, swx+SWATCH_SZ, swy+SWATCH_SZ, 0xFF000000 | colorLookup[c]);

            // Frequency bar below swatch
            if (paletteMode == PaletteMode.TILE) {
                int fi = indexOf(tileColors, c);
                if (fi >= 0 && fi < tileColorFreqs.length && maxTileFreq > 0) {
                    int barW = Math.max(1, SWATCH_SZ * tileColorFreqs[fi] / maxTileFreq);
                    ctx.fill(swx, swy + SWATCH_SZ + 1, swx + barW, swy + SWATCH_SZ + 3, 0xFF2A6644);
                }
            } else if (paletteMode == PaletteMode.SEL && selFreqs != null && maxSelFreq > 0) {
                int barW = Math.max(1, SWATCH_SZ * selFreqs[c] / maxSelFreq);
                ctx.fill(swx, swy + SWATCH_SZ + 1, swx + barW, swy + SWATCH_SZ + 3, 0xFF2A6644);
            }
        }
        paletteHoverColor = hoveredSwatchColor;

        // ── Scrollbar ──────────────────────────────────────────────────
        if (totalRows > visibleRows) {
            int scrollX = width - SCROLL_W;
            int trackY  = SWATCHES_START_Y;
            int trackH  = panelH - SWATCHES_START_Y;
            ctx.fill(scrollX, trackY, scrollX + SCROLL_W, trackY + trackH, 0xFF222222);
            int thumbH = Math.max(4, trackH * visibleRows / totalRows);
            int thumbY = trackY + (trackH - thumbH) * paletteScrollRow
                    / Math.max(1, totalRows - visibleRows);
            ctx.fill(scrollX + 1, thumbY, scrollX + SCROLL_W - 1, thumbY + thumbH, 0xFF666666);
        }
    }

    private boolean hasTransInSel() {
        if (selMask == null) return false;
        for (int i = 0; i < MAP_BYTES; i++)
            if (selMaskAt(i) && (mapColors[i] & 0xFF) == 0) return true;
        if (multiTileMode && allTileFrames != null) {
            for (int ti = 0; ti < PayloadState.tiles.size(); ti++) {
                if (ti == tileIndex || ti >= allTileFrames.length || allTileFrames[ti] == null) continue;
                boolean[] ts = localSelMaskForTile(ti);
                if (ts == null) continue;
                byte[] frame = allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)];
                for (int j = 0; j < MAP_BYTES; j++) if (ts[j] && (frame[j] & 0xFF) == 0) return true;
            }
        }
        return false;
    }

    private int[] activePaletteColors() {
        int[] raw = switch (paletteMode) {
            case TILE -> tileColors;
            case ALL  -> allMapColors;
            case SEL  -> selColors();
        };
        // Transparent (index 0) is shown in its own dedicated row — exclude from grid
        if (raw.length > 0 && raw[0] == 0) return Arrays.copyOfRange(raw, 1, raw.length);
        return raw;
    }

    /**
     * Assigns each palette entry a (row, col) position.
     * Starts a new row when the map-color family (byte >> 2) changes and the
     * current row already holds more than 4 swatches, keeping related shades
     * together while still capping rows at SWATCHES_PER_ROW.
     */
    private static int[][] buildPaletteLayout(int[] palette) {
        int[][] layout = new int[palette.length][2];
        int row = 0, col = 0, prevFamily = -1;
        for (int i = 0; i < palette.length; i++) {
            int family = palette[i] >> 2;
            if (family != prevFamily && col > 4) {
                row++;
                col = 0;
            }
            layout[i][0] = row;
            layout[i][1] = col;
            if (++col >= SWATCHES_PER_ROW) { row++; col = 0; }
            prevFamily = family;
        }
        return layout;
    }

    private int[] selColors() {
        if (selMask == null) return new int[0];
        boolean[] inSel = new boolean[256];
        for (int i = 0; i < MAP_BYTES; i++)
            if (selMaskAt(i)) inSel[mapColors[i] & 0xFF] = true;
        if (multiTileMode && allTileFrames != null) {
            for (int ti = 0; ti < PayloadState.tiles.size(); ti++) {
                if (ti == tileIndex || ti >= allTileFrames.length || allTileFrames[ti] == null) continue;
                boolean[] ts = localSelMaskForTile(ti);
                if (ts == null) continue;
                byte[] frame = allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)];
                for (int j = 0; j < MAP_BYTES; j++) if (ts[j]) inSel[frame[j] & 0xFF] = true;
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int c = 1; c < 256; c++) if (inSel[c]) result.add(c);
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    private void setSelMask(boolean[] newMask) {
        selMask = newMask;
        if (newMask != null) {
            paletteMode = PaletteMode.SEL;
            paletteScrollRow = 0;
        } else if (paletteMode == PaletteMode.SEL) {
            paletteMode = PaletteMode.TILE;
            paletteScrollRow = 0;
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
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8=/-  grow/shrink"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Esc  clear/close"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§c[Sh+Esc] discard+exit"), x, y, 0xFFFFFF); y += 9;
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
        } else if (editorDitherAlgo == PngToMapColors.DitherAlgo.ATKINSON) {
            String errLbl = inPreview ? "§7sh+sc" : "§8(in preview)";
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(String.format("§7Atk err:§f%.1f %s", atkErrorStrength, errLbl)),
                    x, y, 0xFFFFFF); y += 9;
        } else if (editorDitherAlgo == PngToMapColors.DitherAlgo.BAYER_4X4) {
            String errLbl = inPreview ? "§7sh+sc" : "§8(in preview)";
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(String.format("§7Bayer sc:§f%.2f %s", bayerScale, errLbl)),
                    x, y, 0xFFFFFF); y += 9;
        }
        if (chromaBoost != 1.0f) {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(String.format("§aN chroma:§f%.2fx", chromaBoost)),
                    x, y, 0xFFFFFF); y += 9;
        } else {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8N  chroma:1.0x"), x, y, 0xFFFFFF); y += 9;
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
            String modeLabel = multiTileMode ? "§amulti§r" : "§8multi";
            String singleLabel = multiTileMode ? "§8single" : "§asingle§r";
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8Sh+G [" + singleLabel + "§8|" + modeLabel + "§8]"),
                    x, y, 0xFFFFFF); y += 9;
            if (!multiTileMode)
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8G  minimap"), x, y, 0xFFFFFF);
            else
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8click tile: switch"), x, y, 0xFFFFFF);
            y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+Sh+[  prev"), x, y, 0xFFFFFF); y += 9;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+Sh+]  next"), x, y, 0xFFFFFF);
        }
    }

    // ── Minimap overlay ─────────────────────────────────────────────────

    private void computeMinimapThumbnails() {
        int count = PayloadState.tiles.size();
        thumbnails = new byte[count][];
        for (int i = 0; i < count; i++) {
            if (i == tileIndex) continue; // rendered live from mapColors
            if (allTileFrames != null && i < allTileFrames.length && allTileFrames[i] != null) {
                thumbnails[i] = downsample(allTileFrames[i][0]);
            } else {
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
        if (allTileFrames == null) return;

        // Defer re-encode: mark dirty instead and save everything on close
        if (dirty && tileDirty != null && tileIndex < tileDirty.length) tileDirty[tileIndex] = true;
        dirty = false;

        PayloadState.switchTile(newIdx);
        tileIndex = newIdx;
        tileLabel = PayloadState.tileLabel(newIdx);

        // Lightweight: data already in allTileFrames — no disk I/O
        allFrames   = allTileFrames[newIdx];
        totalFrames = allFrames.length;
        activeFrame = Math.min(activeFrame, totalFrames - 1);
        mapColors   = allFrames[activeFrame];

        PayloadState.TileData tile = PayloadState.tiles.get(newIdx);
        frameDelayMs = new int[totalFrames];
        List<Integer> stored = tile.frameDelays;
        if (stored != null && !stored.isEmpty()) {
            for (int i = 0; i < totalFrames; i++)
                frameDelayMs[i] = stored.get(Math.min(i, stored.size() - 1));
        } else {
            Arrays.fill(frameDelayMs, 100);
        }
        if (tile.frameSourceIndices != null && !tile.frameSourceIndices.isEmpty()) {
            requantizeSourceFrame = tile.frameSourceIndices.get(Math.min(activeFrame, tile.frameSourceIndices.size()-1));
            gifSourceFrameCount = tile.frameSourceIndices.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        } else {
            requantizeSourceFrame = 0;
            gifSourceFrameCount = -1;
        }

        history.clear();
        inPreview            = false;
        previewColors        = null;
        previewSelMask       = null;
        allTilePreviewColors = null;
        cachedRequantizeSrc  = null;
        cachedGifFrames      = null;
        pendingGridResult     = null;
        selMask            = null;
        lassoPoints        = null;
        ditherMask         = null;
        useCustomMask      = false;
        mergeSources.clear();
        thumbnails         = null;
        showMinimap        = false;
        paletteMode        = PaletteMode.TILE;
        paletteScrollRow   = 0;
        wandDragging         = false;
        wandPreview          = null;
        wandPreviewX         = -1; wandPreviewY = -1;
        wandPreviewTolerance = -1f;

        if (!multiTileMode) centerMap(); // recenter viewport on the newly active tile
        rebuildTileColors();
        setStatusMsg("Switched to " + tileLabel);
    }

    private void renderStatusBar(DrawContext ctx) {
        int barY = height - STATUS_H;
        ctx.fill(0, barY, width, height, COL_STATUS_BG);
        ctx.fill(0, barY, width, barY+1, COL_STATUS_SEP);

        if (inPreview) {
            String previewLabel = pendingGridResult != null
                    ? "§e[GRID PREVIEW]§r  Enter=apply all " + PayloadState.tiles.size()
                            + " tiles  §8Esc=cancel"
                    : "§e[PREVIEW]§r  Enter=apply  §8Esc=cancel";
            ctx.drawTextWithShadow(textRenderer, Text.literal(previewLabel), 4, barY + 3, 0xFFFFFF);
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
            String minimapHint = PayloadState.tiles.size() > 1
                    ? (multiTileMode ? "  §8[Sh+G]single" : "  §8[G]minimap [Sh+G]multi") : "";
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
        int lx = localX(hoverMapX), ly = localY(hoverMapY);
        if (inActiveTile(hoverMapX, hoverMapY)) {
            int cb  = mapColors[lx + ly * MAP_SIZE] & 0xFF;
            String hex = cb == 0 ? "transparent" : String.format("#%06X", colorLookup[cb] & 0xFFFFFF);
            return String.format("(%d,%d) %s  %d×", lx, ly, hex, scale);
        }
        // Hovering a non-active tile — show its color and tile info
        int ti = tileAt(hoverMapX, hoverMapY);
        String tileInfo = "[tile " + (ti + 1) + "]";
        if (allTileFrames != null && ti < allTileFrames.length && allTileFrames[ti] != null) {
            int f = Math.min(activeFrame, allTileFrames[ti].length - 1);
            int cb = allTileFrames[ti][f][lx + ly * MAP_SIZE] & 0xFF;
            String hex = cb == 0 ? "trans" : String.format("#%06X", colorLookup[cb] & 0xFFFFFF);
            return String.format("(%d,%d) %s %s  %d×", lx, ly, hex, tileInfo, scale);
        }
        return String.format("(%d,%d) %s  %d×", lx, ly, tileInfo, scale);
    }

    // ── Input ────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double hAmt, double vAmt) {
        // In preview: Shift+scroll adjusts the algo-specific strength parameter and live-refreshes
        if (inPreview) {
            if (hasShiftDown()) {
                switch (editorDitherAlgo) {
                    case FLOYD_STEINBERG -> {
                        fsErrorStrength = Math.round(Math.max(0.1f, Math.min(1.0f,
                                fsErrorStrength + (float) Math.signum(vAmt) * 0.1f)) * 10) / 10f;
                        refreshRequantizePreview();
                    }
                    case ATKINSON -> {
                        atkErrorStrength = Math.round(Math.max(0.1f, Math.min(1.0f,
                                atkErrorStrength + (float) Math.signum(vAmt) * 0.1f)) * 10) / 10f;
                        refreshRequantizePreview();
                    }
                    case BAYER_4X4 -> {
                        bayerScale = Math.round(Math.max(0.02f, Math.min(0.20f,
                                bayerScale + (float) Math.signum(vAmt) * 0.02f)) * 100) / 100f;
                        refreshRequantizePreview();
                    }
                    default -> {}
                }
            }
            return true;
        }
        if (mouseX < GUIDE_W) return false;
        if (mouseX >= width - PANEL_W) {
            if (mouseY >= SWATCHES_START_Y) {
                int[] palette   = activePaletteColors();
                int[][] layout  = buildPaletteLayout(palette);
                int totalRows   = layout.length > 0 ? layout[layout.length - 1][0] + 1 : 0;
                int visibleRows = (height - STATUS_H - SWATCHES_START_Y) / SWATCH_STRIDE;
                if (totalRows > visibleRows) {
                    paletteScrollRow = Math.max(0, Math.min(totalRows - visibleRows,
                            paletteScrollRow - (int) Math.signum(vAmt)));
                }
            }
            return true;
        }

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
        if (inPasteMode) {
            if (button == 0) commitPaste();
            else inPasteMode = false;
            return true;
        }
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
            // Show: tabs row
            if (relY >= 53 && relY < 66) {
                if (relX >= 38 && relX < 64) { paletteMode = PaletteMode.TILE; paletteScrollRow = 0; return true; }
                if (relX >= 64 && relX < 86) { paletteMode = PaletteMode.ALL;  paletteScrollRow = 0; return true; }
                if (relX >= 86 && selMask != null) { paletteMode = PaletteMode.SEL; paletteScrollRow = 0; return true; }
                return true;
            }
            // Transparency dedicated row
            if (relY >= 68 && relY < 82 && relX >= 2 && relX < 2 + SWATCH_SZ + 18) {
                if (button == 0 && hasShiftDown()
                        && paletteMode == PaletteMode.SEL && selMask != null) {
                    int gW = gridW(), gH = gridH();
                    for (int gy = 0; gy < gH; gy++)
                        for (int gx = 0; gx < gW; gx++) {
                            int si = selIdx(gx, gy);
                            if (selMask[si] && getGlobalPixel(gx, gy) == 0) selMask[si] = false;
                        }
                    boolean empty = true;
                    for (boolean b : selMask) { if (b) { empty = false; break; } }
                    setSelMask(empty ? null : selMask);
                    setStatusMsg("Deselected transparent pixels from selection.");
                } else {
                    activeColor = 0;
                }
                return true;
            }
            // Swatch grid
            if (relY >= SWATCHES_START_Y) {
                int[] palette  = activePaletteColors();
                int[][] layout = buildPaletteLayout(palette);
                int visRow = (relY - SWATCHES_START_Y) / SWATCH_STRIDE;
                int absRow = visRow + paletteScrollRow;
                int col    = (relX - PANEL_MARGIN) / SWATCH_STRIDE;
                if (col >= 0 && col < SWATCHES_PER_ROW) {
                    int idx = -1;
                    for (int li = 0; li < layout.length; li++) {
                        if (layout[li][0] == absRow && layout[li][1] == col) { idx = li; break; }
                    }
                    if (idx >= 0) {
                        int c = palette[idx];
                        if (button == 0 && hasControlDown()) {
                            if (!mergeSources.remove(c)) mergeSources.add(c);
                        } else if (button == 0 && hasShiftDown()
                                && paletteMode == PaletteMode.SEL && selMask != null) {
                            // Deselect all pixels of this color
                            int gW = gridW(), gH = gridH();
                            for (int gy = 0; gy < gH; gy++)
                                for (int gx = 0; gx < gW; gx++) {
                                    int si = selIdx(gx, gy);
                                    if (selMask[si] && getGlobalPixel(gx, gy) == c) selMask[si] = false;
                                }
                            boolean empty = true;
                            for (boolean b : selMask) { if (b) { empty = false; break; } }
                            setSelMask(empty ? null : selMask);
                            setStatusMsg("Deselected color #" + String.format("%06X", colorLookup[c] & 0xFFFFFF) + " from selection.");
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

        // In multi-tile mode: clicking a non-active tile switches to it
        if (multiTileMode && !inActiveTile(hoverMapX, hoverMapY) && button != 2) {
            if (button == 0 && currentTool == Tool.EYEDROPPER) {
                int ti = tileAt(hoverMapX, hoverMapY);
                int lx = localX(hoverMapX), ly = localY(hoverMapY);
                if (allTileFrames != null && ti < allTileFrames.length && allTileFrames[ti] != null) {
                    int f = Math.min(activeFrame, allTileFrames[ti].length - 1);
                    activeColor = allTileFrames[ti][f][lx + ly * MAP_SIZE] & 0xFF;
                }
                currentTool = prevTool;
                return true;
            }
            if (button == 0 && hasControlDown()) {
                int ti = tileAt(hoverMapX, hoverMapY);
                int lx = localX(hoverMapX), ly = localY(hoverMapY);
                if (allTileFrames != null && ti < allTileFrames.length && allTileFrames[ti] != null) {
                    int f = Math.min(activeFrame, allTileFrames[ti].length - 1);
                    int c = allTileFrames[ti][f][lx + ly * MAP_SIZE] & 0xFF;
                    if (c != 0) { if (!mergeSources.remove(c)) mergeSources.add(c); }
                }
                return true;
            }
            if (button == 0) {
                int newTile = tileAt(hoverMapX, hoverMapY);
                if (newTile >= 0 && newTile < PayloadState.tiles.size()) switchToTile(newTile);
            }
            return true;
        }

        int lhx = localHoverX(), lhy = localHoverY();

        // Ctrl+left-click on canvas: toggle color under cursor in merge-source set
        if (button == 0 && hasControlDown()) {
            int c = mapColors[lhx + lhy * MAP_SIZE] & 0xFF;
            if (c != 0) {
                if (!mergeSources.remove(c)) mergeSources.add(c);
            }
            return true;
        }

        // Eyedropper: left-click picks colour and reverts to previous tool
        if (button == 0 && currentTool == Tool.EYEDROPPER) {
            activeColor = mapColors[lhx + lhy * MAP_SIZE] & 0xFF;
            currentTool = prevTool;
            return true;
        }

        // Dither brush: left=paint strength, right=erase (set to 0)
        if (currentTool == Tool.DITHER_BRUSH && (button == 0 || button == 1)) {
            ensureDitherMask();
            applyDitherBrushAt(lhx, lhy, button == 0 ? ditherStrength : 0f);
            stroking = true;
            return true;
        }

        // Right-click = eyedropper (picks from active tile, or any tile)
        if (button == 1 && (currentTool == Tool.BRUSH || currentTool == Tool.FILL)) {
            activeColor = mapColors[lhx + lhy * MAP_SIZE] & 0xFF;
            return true;
        }

        if (button == 0) {
            switch (currentTool) {
                case SELECT -> {
                    selMask = new boolean[selSize()];
                    selMask[activeSelIdx(lhx, lhy)] = true;
                    selecting   = true;
                    dragOriginX = lhx;
                    dragOriginY = lhy;
                }
                case LASSO -> {
                    lassoPoints = new ArrayList<>();
                    lassoPoints.add(new int[]{hoverMapX, hoverMapY});
                }
                case MAGIC_WAND -> {
                    selMask = null;
                    addWandRegionAt(hoverMapX, hoverMapY);
                    wandDragging = true;
                }
                case BRUSH -> {
                    if (selMask != null && !selMask[activeSelIdx(lhx, lhy)])
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
        if (inPasteMode) return true;
        if (inPreview) return true;
        if (button == 0 || (button == 1 && currentTool == Tool.DITHER_BRUSH)) {
            if (stroking) { stroking = false; rebuildTileColors(); }
            if (selecting) {
                selecting = false;
                int lhx = localHoverX(), lhy = localHoverY();
                // Single click with no drag → deselect; otherwise commit via setSelMask
                setSelMask(lhx == dragOriginX && lhy == dragOriginY ? null : selMask);
            }
            if (wandDragging) {
                wandDragging = false;
                setSelMask(selMask);  // trigger palette mode switch now that drag is done
            }
            if (button == 0 && lassoPoints != null) commitLasso();
        }
        if (button == 2) { panning = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double dX, double dY) {
        if (inPasteMode) return true;
        if (inPreview) return true;
        if (button == 0) {
            if (selecting && inMapBounds(hoverMapX, hoverMapY) && inHoverActiveLocal()) {
                int lhx = localHoverX(), lhy = localHoverY();
                int x1 = Math.min(dragOriginX, lhx), y1 = Math.min(dragOriginY, lhy);
                int x2 = Math.max(dragOriginX, lhx), y2 = Math.max(dragOriginY, lhy);
                if (selMask == null) selMask = new boolean[selSize()];
                else Arrays.fill(selMask, false);
                for (int ly = y1; ly <= y2; ly++)
                    for (int lx = x1; lx <= x2; lx++)
                        selMask[activeSelIdx(lx, ly)] = true;
                return true;
            }
            if (wandDragging && inMapBounds(hoverMapX, hoverMapY)) {
                if (multiTileMode || inHoverActiveLocal()) addWandRegionAt(hoverMapX, hoverMapY);
                return true;
            }
            if (stroking && currentTool == Tool.BRUSH && inMapBounds(hoverMapX, hoverMapY)
                    && (multiTileMode || inHoverActiveLocal())) {
                applyBrushAt(hoverMapX, hoverMapY);
                return true;
            }
            if (currentTool == Tool.LASSO && inMapBounds(hoverMapX, hoverMapY)
                    && (multiTileMode || inHoverActiveLocal())) {
                if (lassoPoints == null) lassoPoints = new ArrayList<>();
                int[] last = lassoPoints.isEmpty() ? null
                        : lassoPoints.get(lassoPoints.size() - 1);
                if (last == null || last[0] != hoverMapX || last[1] != hoverMapY)
                    lassoPoints.add(new int[]{hoverMapX, hoverMapY});
                return true;
            }
        }
        if ((button == 0 || button == 1) && stroking
                && currentTool == Tool.DITHER_BRUSH && inMapBounds(hoverMapX, hoverMapY)
                && inHoverActiveLocal()) {
            ensureDitherMask();
            applyDitherBrushAt(localHoverX(), localHoverY(), button == 0 ? ditherStrength : 0f);
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
                pendingGridResult = null;
                inPreview = false; previewColors = null; previewSelMask = null; allTilePreviewColors = null; cachedRequantizeSrc = null;
            } else if (keyCode == GLFW.GLFW_KEY_D) {
                cycleEditorDither();
                refreshRequantizePreview();
            } else if (keyCode == GLFW.GLFW_KEY_Q) {
                cycleMatchMetric();
                refreshRequantizePreview();
            } else if (keyCode == GLFW.GLFW_KEY_N) {
                float step = 0.25f;
                chromaBoost = Math.round(Math.max(0.25f, Math.min(4.0f,
                        chromaBoost + (shift ? -step : step))) / step) * step;
                setStatusMsg(String.format("Chroma boost: %.2fx", chromaBoost));
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

        // Paste mode is modal — only Enter/Y/Esc act; left-click (mouseClicked) commits
        if (inPasteMode) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                    || keyCode == GLFW.GLFW_KEY_Y) {
                commitPaste();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                inPasteMode = false;
            }
            return true;
        }

        // Shift+Escape: discard all changes and exit immediately
        if (shift && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeDiscarding();
            return true;
        }

        // Escape: cancel paste → clear merge sources → cancel lasso → deselect → close (saves)
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (!mergeSources.isEmpty()) { mergeSources.clear(); return true; }
            if (lassoPoints != null) { lassoPoints = null; return true; }
            if (selMask != null)     { setSelMask(null);   return true; }
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
            boolean[] all = new boolean[selSize()];
            if (multiTileMode && PayloadState.tiles.size() > 1) {
                Arrays.fill(all, true);
            } else {
                for (int ly = 0; ly < MAP_SIZE; ly++)
                    for (int lx = 0; lx < MAP_SIZE; lx++)
                        all[activeSelIdx(lx, ly)] = true;
            }
            setSelMask(all); return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_D) { setSelMask(null); return true; }
        if (selMask != null) {
            if (keyCode == GLFW.GLFW_KEY_EQUAL) { growOrShrinkSelection(shift ? 5 : 1);  return true; }
            if (keyCode == GLFW.GLFW_KEY_MINUS) { growOrShrinkSelection(shift ? -5 : -1); return true; }
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) { copySelection();  return true; }
        if (ctrl && keyCode == GLFW.GLFW_KEY_X) { cutSelection();   return true; }
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) { startPaste();     return true; }

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
        if (keyCode == GLFW.GLFW_KEY_R) {
            if (shift && PayloadState.tiles.size() > 1) applyGridRequantize();
            else applyRequantize();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_M) { showMask = !showMask; return true; }
        if (keyCode == GLFW.GLFW_KEY_N) {
            float step = 0.25f;
            chromaBoost = Math.round(Math.max(0.25f, Math.min(4.0f,
                    chromaBoost + (shift ? -step : step))) / step) * step;
            if (inPreview) refreshRequantizePreview();
            setStatusMsg(String.format("Chroma boost: %.2fx  (Sh+N to decrease, N to increase)", chromaBoost));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_H) {
            heatmapMode = !heatmapMode;
            setStatusMsg(heatmapMode
                    ? "Heatmap on: §cred§r=rarest (costly), §9blue§r=most-frequent (cheap). [H] to toggle."
                    : "Heatmap off.");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G) {
            if (shift && PayloadState.tiles.size() > 1) {
                multiTileMode = !multiTileMode;
                showMinimap = false;
                selMask = null; wandPreview = null; // selSize() changes between modes
                recomputeScale();
                centerMap();
                setStatusMsg(multiTileMode ? "Multi-tile canvas — click tile to switch active"
                        : "Single-tile canvas — G=minimap  Sh+G=multi-tile");
            } else if (!multiTileMode) {
                showMinimap = !showMinimap;
                if (showMinimap && thumbnails == null) computeMinimapThumbnails();
            } else {
                setStatusMsg("Minimap hidden in multi-tile mode — Sh+G to switch");
            }
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

    private void applyBrushAt(int gx, int gy) {
        int r2 = brushRadius * brushRadius;
        for (int dy = -brushRadius; dy <= brushRadius; dy++) {
            for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                int px = gx + dx, py = gy + dy;
                if (!inMapBounds(px, py)) continue;
                if (!multiTileMode && !inActiveTile(px, py)) continue;
                if (selMask != null && !selMask[selIdx(px, py)]) continue;
                setGlobalPixel(px, py, activeColor);
            }
        }
    }

    private void applyFillAt(int startGx, int startGy) {
        int target = getGlobalPixel(startGx, startGy);
        if (target == activeColor) return;
        float[] targetOklab = fillTolerance > 0f ? getMapColorOklab(target) : null;
        history.snapshot();
        boolean[] visited = new boolean[selSize()];
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{startGx, startGy});
        while (!queue.isEmpty()) {
            int[] p = queue.pop();
            int x = p[0], y = p[1];
            if (!inMapBounds(x, y)) continue;
            if (!multiTileMode && !inActiveTile(x, y)) continue;
            if (selMask != null && !selMask[selIdx(x, y)]) continue;
            int idx = selIdx(x, y);
            if (visited[idx]) continue;
            visited[idx] = true;
            if (!colorMatches(getGlobalPixel(x, y), target, targetOklab)) continue;
            setGlobalPixel(x, y, activeColor);
            queue.push(new int[]{x+1, y}); queue.push(new int[]{x-1, y});
            queue.push(new int[]{x, y+1}); queue.push(new int[]{x, y-1});
        }
    }

    private void applyDitherBrushAt(int cx, int cy, float value) {
        int r2 = brushRadius * brushRadius;
        for (int dy = -brushRadius; dy <= brushRadius; dy++) {
            for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                if (brushShape == BrushShape.CIRCLE && dx*dx + dy*dy > r2) continue;
                int px = cx + dx, py = cy + dy;
                if (!inLocalBounds(px, py)) continue;
                ditherMask[px + py * MAP_SIZE] = value;
            }
        }
    }

    private boolean[] computeWandRegion(int startGx, int startGy) {
        int target = getGlobalPixel(startGx, startGy);
        float[] targetOklab = fillTolerance > 0f ? getMapColorOklab(target) : null;
        int size = selSize();
        boolean[] mask    = new boolean[size];
        boolean[] visited = new boolean[size];
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{startGx, startGy});
        while (!queue.isEmpty()) {
            int[] p = queue.pop();
            int x = p[0], y = p[1];
            if (!inMapBounds(x, y)) continue;
            if (!multiTileMode && !inActiveTile(x, y)) continue;
            int idx = selIdx(x, y);
            if (visited[idx]) continue;
            visited[idx] = true;
            if (!colorMatches(getGlobalPixel(x, y), target, targetOklab)) continue;
            mask[idx] = true;
            queue.push(new int[]{x+1, y}); queue.push(new int[]{x-1, y});
            queue.push(new int[]{x, y+1}); queue.push(new int[]{x, y-1});
        }
        return mask;
    }

    /** Flood-fills from global (gx,gy) and ORs the result into selMask. */
    private boolean[] dilateSelection(boolean[] mask) {
        int gW = gridW(), gH = gridH();
        boolean[] result = new boolean[mask.length];
        for (int gy = 0; gy < gH; gy++) {
            for (int gx = 0; gx < gW; gx++) {
                if (mask[selIdx(gx, gy)]
                        || (gx > 0    && mask[selIdx(gx-1, gy)])
                        || (gx < gW-1 && mask[selIdx(gx+1, gy)])
                        || (gy > 0    && mask[selIdx(gx, gy-1)])
                        || (gy < gH-1 && mask[selIdx(gx, gy+1)]))
                    result[selIdx(gx, gy)] = true;
            }
        }
        return result;
    }

    private boolean[] erodeSelection(boolean[] mask) {
        int gW = gridW(), gH = gridH();
        boolean[] result = new boolean[mask.length];
        for (int gy = 0; gy < gH; gy++) {
            for (int gx = 0; gx < gW; gx++) {
                if (!mask[selIdx(gx, gy)]) continue;
                if (gx == 0    || !mask[selIdx(gx-1, gy)]) continue;
                if (gx == gW-1 || !mask[selIdx(gx+1, gy)]) continue;
                if (gy == 0    || !mask[selIdx(gx, gy-1)]) continue;
                if (gy == gH-1 || !mask[selIdx(gx, gy+1)]) continue;
                result[selIdx(gx, gy)] = true;
            }
        }
        return result;
    }

    private void growOrShrinkSelection(int steps) {
        if (selMask == null) { setStatusMsg("No selection to grow/shrink."); return; }
        boolean[] mask = selMask;
        if (steps > 0) {
            for (int i = 0; i < steps; i++) mask = dilateSelection(mask);
        } else {
            for (int i = 0; i < -steps; i++) mask = erodeSelection(mask);
        }
        setSelMask(mask);
        setStatusMsg((steps > 0 ? "Grew" : "Shrunk") + " selection by " + Math.abs(steps) + "px.");
    }

    private void addWandRegionAt(int gx, int gy) {
        if (!inMapBounds(gx, gy)) return;
        if (!multiTileMode && !inActiveTile(gx, gy)) return;
        int si = selIdx(gx, gy);
        if (selMask != null && selMask[si]) return; // already covered
        boolean[] region = computeWandRegion(gx, gy);
        if (selMask == null) selMask = new boolean[selSize()];
        for (int i = 0; i < region.length; i++) if (region[i]) selMask[i] = true;
    }

    private void renderWandPreview(DrawContext ctx) {
        if (wandPreview == null || wandDragging) return;
        int gW = gridW(), gH = gridH();
        int canvasH = height - STATUS_H;
        for (int gy = 0; gy < gH; gy++) {
            for (int gx = 0; gx < gW; gx++) {
                int idx = selIdx(gx, gy);
                if (!wandPreview[idx]) continue;
                if (selMask != null && selMask[idx]) continue;
                if (!multiTileMode && !inActiveTile(gx, gy)) continue;
                int sx = (int)(translateX + gx * scale);
                int sy = (int)(translateY + gy * scale);
                if (sx >= width - PANEL_W || sy >= canvasH || sx + scale <= GUIDE_W || sy + scale <= 0) continue;
                ctx.fill(sx, sy, sx + scale, sy + scale, 0x500088FF);
            }
        }
    }

    private void renderPaletteHoverOverlay(DrawContext ctx) {
        if (paletteHoverColor < 0) return;
        long t = System.currentTimeMillis();
        double phase = (t % 700) / 700.0;
        int alpha = (int)(0x78 * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI)));
        if (alpha < 4) return;
        int overlay = (alpha << 24) | 0xFFFFFF;
        int gx0 = activeTileGx0(), gy0 = activeTileGy0();
        int canvasH = height - STATUS_H;
        byte target = (byte) paletteHoverColor;
        for (int py = 0; py < MAP_SIZE; py++) {
            for (int px = 0; px < MAP_SIZE; px++) {
                if (mapColors[px + py * MAP_SIZE] != target) continue;
                int sx = (int)(translateX + (gx0 + px) * scale);
                int sy = (int)(translateY + (gy0 + py) * scale);
                if (sx >= width - PANEL_W || sy >= canvasH || sx + scale <= GUIDE_W || sy + scale <= 0) continue;
                ctx.fill(sx, sy, sx + scale, sy + scale, overlay);
            }
        }
    }

    private void commitLasso() {
        if (lassoPoints == null || lassoPoints.size() < 3) {
            lassoPoints = null;
            return;
        }
        // lassoPoints store global hoverMapX/Y coords; test global grid points against the path
        Path2D.Float path = new Path2D.Float();
        path.moveTo(lassoPoints.get(0)[0] + 0.5, lassoPoints.get(0)[1] + 0.5);
        for (int i = 1; i < lassoPoints.size(); i++)
            path.lineTo(lassoPoints.get(i)[0] + 0.5, lassoPoints.get(i)[1] + 0.5);
        path.closePath();
        boolean[] newMask = new boolean[selSize()];
        for (int gy = 0; gy < gridH(); gy++)
            for (int gx = 0; gx < gridW(); gx++)
                if (path.contains(gx + 0.5, gy + 0.5))
                    newMask[selIdx(gx, gy)] = true;
        setSelMask(newMask);
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

    private void applyGridRequantize() {
        if (inPreview) return;
        String fname = PayloadState.currentSourceFilename;
        if (fname == null) { setStatusMsg("No source file — use /loominary import first"); return; }
        Path sourcePath = FabricLoader.getInstance().getGameDir()
                .resolve("loominary_data").resolve(fname);
        if (!Files.exists(sourcePath)) {
            setStatusMsg("Source not found: loominary_data/" + fname); return;
        }
        try {
            BufferedImage src;
            if (totalFrames > 1 && fname.toLowerCase().endsWith(".gif")) {
                if (cachedGifFrames == null)
                    cachedGifFrames = PngToMapColors.coalesceGifFrames(sourcePath);
                src = cachedGifFrames[Math.min(requantizeSourceFrame, cachedGifFrames.length - 1)];
            } else {
                if (cachedRequantizeSrc == null)
                    cachedRequantizeSrc = ImageIO.read(sourcePath.toFile());
                src = cachedRequantizeSrc;
            }
            if (src == null) { setStatusMsg("Could not decode source image"); return; }
            boolean legalOnly = !PayloadState.allShades;
            byte[][] result = PngToMapColors.convertTwoPassGrid(
                    withChromaBoost(src), legalOnly, 0, true,
                    PayloadState.gridColumns, PayloadState.gridRows);
            pendingGridResult     = result;
            previewColors        = result[tileIndex].clone();
            previewSelMask       = null;
            allTilePreviewColors = new byte[result.length][];
            for (int i = 0; i < result.length; i++)
                if (i != tileIndex) allTilePreviewColors[i] = result[i].clone();
            inPreview            = true;
        } catch (Exception e) {
            setStatusMsg("Grid requantize failed: " + e.getMessage());
        }
    }

    private void refreshRequantizePreview() {
        if (!inPreview || cachedRequantizeSrc == null) return;
        if (pendingGridResult != null) return; // grid preview is locked; no per-parameter refresh
        computeRequantizePreview();
    }

    private void computeRequantizePreview() {
        // Active tile preview
        byte[] fullTile = computeRequantizeTile(tileIndex, cachedRequantizeSrc);
        byte[] preview  = mapColors.clone();
        boolean[] pMask = null;
        if (selMask != null) {
            pMask = selMask.clone();
            for (int i = 0; i < MAP_BYTES; i++)
                if (selMaskAt(i)) preview[i] = fullTile[i];
        } else {
            System.arraycopy(fullTile, 0, preview, 0, MAP_BYTES);
        }
        previewColors  = preview;
        previewSelMask = pMask;

        // Non-active tile previews in multi-tile mode
        allTilePreviewColors = null;
        if (multiTileMode && allTileFrames != null) {
            int numTiles = PayloadState.tiles.size();
            // For standard cross-tile FS, run convertTwoPassGrid once rather than per-tile
            byte[][] gridResult = null;
            if (editorDitherAlgo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG && !requantizeTilePalette
                    && matchMetric == PngToMapColors.MatchMetric.OKLAB
                    && !(useCustomMask && ditherMask != null)) {
                try {
                    boolean legalOnly = !PayloadState.allShades;
                    gridResult = PngToMapColors.convertTwoPassGrid(
                            withChromaBoost(cachedRequantizeSrc), legalOnly, 0, true,
                            PayloadState.gridColumns, PayloadState.gridRows);
                } catch (Exception ignored) {}
            }
            allTilePreviewColors = new byte[numTiles][];
            int crossCount = 0;
            for (int i = 0; i < numTiles; i++) {
                if (i == tileIndex || i >= allTileFrames.length || allTileFrames[i] == null) continue;
                boolean[] ts = (selMask != null) ? localSelMaskForTile(i) : null;
                if (selMask != null && ts == null) continue;  // selection exists but not on this tile
                try {
                    byte[] tileResult = (gridResult != null) ? gridResult[i]
                            : computeRequantizeTile(i, cachedRequantizeSrc);
                    int f = Math.min(activeFrame, allTileFrames[i].length - 1);
                    byte[] tilePreview = allTileFrames[i][f].clone();
                    if (ts != null) {
                        for (int j = 0; j < MAP_BYTES; j++) if (ts[j]) tilePreview[j] = tileResult[j];
                    } else {
                        System.arraycopy(tileResult, 0, tilePreview, 0, MAP_BYTES);
                    }
                    allTilePreviewColors[i] = tilePreview;
                    crossCount++;
                } catch (Exception ignored) {}
            }
            if (crossCount > 0)
                setStatusMsg("Preview: " + (crossCount + 1) + " tiles — Enter to commit.");
        }
    }

    private BufferedImage withChromaBoost(BufferedImage src) {
        return chromaBoost != 1.0f ? PngToMapColors.boostChroma(src, chromaBoost) : src;
    }

    /** Compute requantize result for tile `ti` (pass tileIndex for the active tile). */
    private byte[] computeRequantizeTile(int ti, BufferedImage src) {
        src = withChromaBoost(src);
        boolean legalOnly = !PayloadState.allShades;
        PngToMapColors.DitherAlgo algo = editorDitherAlgo;

        // Custom-mask FS: painted dither mask applies to active tile; uniform mask for others
        if (algo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG && useCustomMask && ditherMask != null) {
            float[] strengthMap = (ti == tileIndex) ? ditherMask : new float[MAP_BYTES];
            if (ti != tileIndex) Arrays.fill(strengthMap, 1.0f);
            return requantizePerTile(ti, src, strengthMap, fsErrorStrength);
        }

        // Standard cross-tile FS: uses Otsu strength map and cross-seam propagation
        if (algo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG && !requantizeTilePalette
                && matchMetric == PngToMapColors.MatchMetric.OKLAB) {
            byte[][] tiles = PngToMapColors.convertTwoPassGrid(
                    src, legalOnly, 0, true, PayloadState.gridColumns, PayloadState.gridRows);
            return tiles[ti];
        }

        // Per-tile path for NONE, ATKINSON, BAYER_4X4, FS+tile-palette, and non-OKLAB metrics
        int totalW = PayloadState.gridColumns * MAP_SIZE;
        int totalH = PayloadState.gridRows * MAP_SIZE;
        BufferedImage scaledFull = PngToMapColors.scale(src, totalW, totalH);
        int tileCol = PayloadState.tileCol(ti);
        int tileRow = PayloadState.tileRow(ti);
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

        // For tile palette: restrict to colors currently selected in that tile's frame
        byte[] tileFrame = (ti == tileIndex) ? mapColors
                : (allTileFrames != null && ti < allTileFrames.length && allTileFrames[ti] != null)
                  ? allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)]
                  : firstPass;
        boolean[] palette = requantizeTilePalette
                ? PngToMapColors.buildPalette(selectionFilteredColors(tileFrame, localSelMaskForTile(ti)))
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
                    matchMetric, rgbLookup, atkErrorStrength);
            case BAYER_4X4 -> PngToMapColors.renderBayer4x4(tileImg, palette, oklabLookup, MAP_SIZE, MAP_SIZE,
                    matchMetric, rgbLookup, bayerScale);
        };
    }

    /** Per-tile FS path with explicit dither-strength array and error diffusion scalar. */
    private byte[] requantizePerTile(int ti, BufferedImage src, float[] strengthMap, float diffuseAmount) {
        int totalW = PayloadState.gridColumns * MAP_SIZE;
        int totalH = PayloadState.gridRows * MAP_SIZE;
        BufferedImage scaledFull = PngToMapColors.scale(src, totalW, totalH);
        int tileCol = PayloadState.tileCol(ti);
        int tileRow = PayloadState.tileRow(ti);
        BufferedImage tileImg = scaledFull.getSubimage(
                tileCol * MAP_SIZE, tileRow * MAP_SIZE, MAP_SIZE, MAP_SIZE);
        float[][] oklabLookup = PngToMapColors.buildOklabLookup();
        boolean legalOnly = !PayloadState.allShades;
        byte[] firstPass = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++)
            for (int x = 0; x < MAP_SIZE; x++)
                firstPass[x + y * MAP_SIZE] = PngToMapColors.findClosestMapColorByte(
                        tileImg.getRGB(x, y), legalOnly, oklabLookup);
        byte[] tileFrame = (ti == tileIndex) ? mapColors
                : (allTileFrames != null && ti < allTileFrames.length && allTileFrames[ti] != null)
                  ? allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)]
                  : firstPass;
        boolean[] palette = requantizeTilePalette
                ? PngToMapColors.buildPalette(selectionFilteredColors(tileFrame, localSelMaskForTile(ti)))
                : PngToMapColors.buildPalette(firstPass);
        return PngToMapColors.renderDithered(tileImg, palette, oklabLookup, strengthMap, MAP_SIZE, MAP_SIZE, diffuseAmount);
    }

    private void commitPreview() {
        if (!inPreview || previewColors == null) return;

        if (pendingGridResult != null) {
            commitGridRequantize();
            pendingGridResult = null;
            inPreview = false; previewColors = null; previewSelMask = null; allTilePreviewColors = null; cachedRequantizeSrc = null;
            return;
        }

        history.snapshot();
        System.arraycopy(previewColors, 0, mapColors, 0, MAP_BYTES);
        dirty = true;
        rebuildTileColors();

        // In multi-tile mode: apply the already-computed preview results to all non-active tiles.
        int crossTileCount = 0;
        if (multiTileMode && allTileFrames != null && allTilePreviewColors != null) {
            for (int i = 0; i < PayloadState.tiles.size(); i++) {
                if (i == tileIndex || i >= allTileFrames.length || allTileFrames[i] == null) continue;
                if (allTilePreviewColors[i] == null) continue;
                boolean[] ts = (selMask != null) ? localSelMaskForTile(i) : null;
                if (selMask != null && ts == null) continue; // selection exists but not on this tile
                try {
                    byte[] result = allTilePreviewColors[i];
                    byte[] frame = allTileFrames[i][Math.min(activeFrame, allTileFrames[i].length - 1)];
                    if (ts != null) {
                        for (int j = 0; j < MAP_BYTES; j++) if (ts[j]) frame[j] = result[j];
                    } else {
                        System.arraycopy(result, 0, frame, 0, MAP_BYTES);
                    }
                    if (tileDirty != null && i < tileDirty.length) tileDirty[i] = true;
                    crossTileCount++;
                } catch (Exception ignored) {}
            }
        }

        BufferedImage savedSrc = cachedRequantizeSrc;
        inPreview = false; previewColors = null; previewSelMask = null; allTilePreviewColors = null; cachedRequantizeSrc = null;

        if (crossTileCount > 0) {
            boolean usedCustomMask = useCustomMask && ditherMask != null;
            String maskNote = usedCustomMask ? " (uniform mask on non-active tiles)" : "";
            setStatusMsg("Requantize applied to " + (crossTileCount + 1) + " tiles' selections." + maskNote
                    + " Ctrl+Z reverts active tile only.");
        }
    }

    private void commitGridRequantize() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String playerName = mc.player != null ? mc.player.getGameProfile().getName() : "Player";
        int total = PayloadState.tiles.size();

        // Apply to active tile in-memory (saved on close as usual)
        history.snapshot();
        System.arraycopy(pendingGridResult[tileIndex], 0, mapColors, 0, MAP_BYTES);
        dirty = true;
        rebuildTileColors();

        // Save all other tiles immediately and update in-memory allTileFrames
        List<Integer> failed = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (i == tileIndex) continue;
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            try {
                byte[][] frames = allTileFrames != null && i < allTileFrames.length && allTileFrames[i] != null
                        ? allTileFrames[i]
                        : LoominaryCommand.resolveAllFramesForTile(tile, tile.chunks);
                int targetFrame = Math.min(activeFrame, frames.length - 1);
                System.arraycopy(pendingGridResult[i], 0, frames[targetFrame], 0, MAP_BYTES);
                int[] delays = tile.frameDelays != null && !tile.frameDelays.isEmpty()
                        ? tile.frameDelays.stream().mapToInt(Integer::intValue).toArray()
                        : new int[frames.length];
                if (tile.frameDelays == null || tile.frameDelays.isEmpty())
                    Arrays.fill(delays, 100);
                LoominaryCommand.saveEditorChanges(frames, delays, i, playerName);
                if (allTileFrames != null && i < allTileFrames.length) allTileFrames[i] = frames;
            } catch (Exception e) {
                failed.add(i + 1);
            }
        }

        if (!failed.isEmpty())
            setStatusMsg("Grid requantize: errors on tiles " + failed);
        else
            setStatusMsg("Grid requantize applied to all " + total + " tiles.");
    }

    private void cycleEditorDither() {
        PngToMapColors.DitherAlgo[] values = PngToMapColors.DitherAlgo.values();
        editorDitherAlgo = values[(editorDitherAlgo.ordinal() + 1) % values.length];
        String name = switch (editorDitherAlgo) {
            case NONE            -> "none";
            case FLOYD_STEINBERG -> String.format("Floyd-Steinberg (err:%.1f — Sh+scroll in preview)", fsErrorStrength);
            case ATKINSON        -> String.format("Atkinson (err:%.1f — Sh+scroll in preview)", atkErrorStrength);
            case BAYER_4X4       -> String.format("Bayer 4×4 (scale:%.2f — Sh+scroll in preview)", bayerScale);
        };
        setStatusMsg("Dither: " + name);
    }

    /** Returns frame with unselected pixels zeroed out, or frame unchanged when no selection. */
    private static byte[] selectionFilteredColors(byte[] frame, boolean[] mask) {
        if (mask == null) return frame;
        byte[] filtered = new byte[MAP_BYTES];
        for (int i = 0; i < MAP_BYTES; i++) if (mask[i]) filtered[i] = frame[i];
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
        int[] result = PngToMapColors.reduceOneStep(mapColors, localSelMask(), oklabLookup, editorReductionStrategy);
        dirty = true;

        int crossTileCount = 0;
        if (multiTileMode && selMask != null && allTileFrames != null) {
            for (int i = 0; i < PayloadState.tiles.size(); i++) {
                if (i == tileIndex || i >= allTileFrames.length || allTileFrames[i] == null) continue;
                boolean[] ts = localSelMaskForTile(i);
                if (ts == null) continue;
                byte[] frame = allTileFrames[i][Math.min(activeFrame, allTileFrames[i].length - 1)];
                PngToMapColors.reduceOneStep(frame, ts, oklabLookup, editorReductionStrategy);
                if (tileDirty != null && i < tileDirty.length) tileDirty[i] = true;
                crossTileCount++;
            }
        }

        rebuildTileColors();
        int before = result[0], after = result[1], pixels = result[2];
        if (before == after) {
            setStatusMsg("No reduction possible" + (selMask != null ? " in selection." : "."));
        } else {
            String scope = selMask != null
                    ? (crossTileCount > 0 ? " (cross-tile selection, " + (crossTileCount + 1) + " tiles)" : " (selection)")
                    : "";
            String undoNote = crossTileCount > 0 ? " Ctrl+Z reverts active tile only." : " Ctrl+Z to undo.";
            setStatusMsg(String.format("Reduced: %d→%d colors%s, %d px.%s", before, after, scope, pixels, undoNote));
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
            if (respectSel && selMask != null && !selMaskAt(i)) continue;
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
                int crossTileCount = 0;
                if (multiTileMode && selMask != null && allTileFrames != null) {
                    for (int i = 0; i < PayloadState.tiles.size(); i++) {
                        if (i == tileIndex || i >= allTileFrames.length || allTileFrames[i] == null) continue;
                        boolean[] ts = localSelMaskForTile(i);
                        if (ts == null) continue;
                        byte[] frame = allTileFrames[i][Math.min(activeFrame, allTileFrames[i].length - 1)];
                        for (int j = 0; j < MAP_BYTES; j++) {
                            if (!ts[j]) continue;
                            if (mergeSources.contains(frame[j] & 0xFF)) { frame[j] = (byte) activeColor; replaced++; }
                        }
                        if (tileDirty != null && i < tileDirty.length) tileDirty[i] = true;
                        crossTileCount++;
                    }
                }
                String sel = selMask != null
                        ? (crossTileCount > 0 ? " (cross-tile selection, " + (crossTileCount + 1) + " tiles)" : " (in selection)")
                        : "";
                String undoNote = crossTileCount > 0 ? " Ctrl+Z reverts active tile only." : "";
                setStatusMsg(String.format("Merged %d color%s → idx %d: %d px replaced%s.%s",
                        mergeSources.size(), mergeSources.size() == 1 ? "" : "s",
                        activeColor, replaced, sel, undoNote));
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

        // Apply to all other tiles: modify in-memory allTileFrames, then re-encode
        List<Integer> overBudget = new ArrayList<>();
        for (int i = 0; i < totalTiles; i++) {
            if (i == tileIndex) continue;
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            if (tile.chunks.isEmpty()) continue;
            try {
                byte[][] frames = allTileFrames != null && i < allTileFrames.length && allTileFrames[i] != null
                        ? allTileFrames[i]
                        : LoominaryCommand.resolveAllFramesForTile(tile, tile.chunks);
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
                if (allTileFrames != null && i < allTileFrames.length) allTileFrames[i] = frames;
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
        float[][] oklabLookup = PngToMapColors.buildOklabLookup();

        // Active tile: candidate colors scoped to selection when active
        int[] activeCandidates;
        if (selMask != null) {
            boolean[] inSel = new boolean[256];
            for (int i = 0; i < MAP_BYTES; i++)
                if (selMaskAt(i)) inSel[mapColors[i] & 0xFF] = true;
            inSel[0] = false;
            List<Integer> list = new ArrayList<>();
            for (int c = 1; c < 256; c++) if (inSel[c]) list.add(c);
            activeCandidates = list.stream().mapToInt(Integer::intValue).toArray();
        } else {
            activeCandidates = tileColors;
        }

        history.snapshot();
        applyEditorFilterToFrame(mapColors, selMask != null ? localSelMask() : null,
                oklabLookup, activeCandidates, params);
        dirty = true;

        int crossTileCount = 0;
        if (multiTileMode && selMask != null && allTileFrames != null) {
            for (int i = 0; i < PayloadState.tiles.size(); i++) {
                if (i == tileIndex || i >= allTileFrames.length || allTileFrames[i] == null) continue;
                boolean[] ts = localSelMaskForTile(i);
                if (ts == null) continue;
                byte[] frame = allTileFrames[i][Math.min(activeFrame, allTileFrames[i].length - 1)];
                // Candidate colors derived from that tile's selected pixels
                boolean[] inSel2 = new boolean[256];
                for (int j = 0; j < MAP_BYTES; j++) if (ts[j]) inSel2[frame[j] & 0xFF] = true;
                inSel2[0] = false;
                List<Integer> list2 = new ArrayList<>();
                for (int c = 1; c < 256; c++) if (inSel2[c]) list2.add(c);
                int[] cands = list2.stream().mapToInt(Integer::intValue).toArray();
                if (cands.length > 0) {
                    applyEditorFilterToFrame(frame, ts, oklabLookup, cands, params);
                    if (tileDirty != null && i < tileDirty.length) tileDirty[i] = true;
                    crossTileCount++;
                }
            }
        }

        rebuildTileColors();
        String scope = selMask != null
                ? (crossTileCount > 0 ? "cross-tile selection (" + (crossTileCount + 1) + " tiles)" : "selection")
                : "frame";
        boolean weakInEditor = editorFilterType == PngToMapColors.FilterParams.FilterType.POSTERIZE
                || editorFilterType == PngToMapColors.FilterParams.FilterType.SHARPEN;
        String hint = weakInEditor ? " (use /loominary filter for full effect)" : "";
        String undoNote = crossTileCount > 0 ? " Ctrl+Z reverts active tile only." : " Ctrl+Z to undo.";
        setStatusMsg(String.format("Applied %s to %s.%s%s",
                editorFilterType.name().toLowerCase(), scope, undoNote, hint));
    }

    /** Apply spatial filter to one frame, remapping only selected pixels to the given candidate colors. */
    private void applyEditorFilterToFrame(byte[] frame, boolean[] tileSel, float[][] oklabLookup,
                                          int[] candidateColors, PngToMapColors.FilterParams params) {
        BufferedImage img = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MAP_SIZE; y++)
            for (int x = 0; x < MAP_SIZE; x++) {
                int cb = frame[x + y * MAP_SIZE] & 0xFF;
                img.setRGB(x, y, cb == 0 ? 0x00000000 : (0xFF000000 | colorLookup[cb]));
            }
        BufferedImage filtered = PngToMapColors.applyFilter(img, params);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int idx = x + y * MAP_SIZE;
                if (tileSel != null && !tileSel[idx]) continue;
                if (frame[idx] == 0) continue;
                int rgb = filtered.getRGB(x, y);
                if (((rgb >> 24) & 0xFF) < 128) { frame[idx] = 0; continue; }
                float[] target = PngToMapColors.rgbToOklab(rgb);
                int bestColor = frame[idx] & 0xFF;
                float bestDist = Float.MAX_VALUE;
                for (int c : candidateColors) {
                    if (c == 0 || oklabLookup[c] == null) continue;
                    float dL = target[0]-oklabLookup[c][0], da = target[1]-oklabLookup[c][1],
                          db = target[2]-oklabLookup[c][2];
                    float d = dL*dL + da*da + db*db;
                    if (d < bestDist) { bestDist = d; bestColor = c; }
                }
                frame[idx] = (byte) bestColor;
            }
        }
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

    // ── Clipboard operations ─────────────────────────────────────────────────

    private void copySelection() {
        if (selMask == null) {
            setStatusMsg("No selection to copy — make a selection first (S/L/W).");
            return;
        }
        int gW = gridW(), gH = gridH();
        int minGx = gW, minGy = gH, maxGx = -1, maxGy = -1;
        for (int gy = 0; gy < gH; gy++)
            for (int gx = 0; gx < gW; gx++)
                if (selMask[selIdx(gx, gy)]) {
                    if (gx < minGx) minGx = gx;
                    if (gy < minGy) minGy = gy;
                    if (gx > maxGx) maxGx = gx;
                    if (gy > maxGy) maxGy = gy;
                }
        if (maxGx < 0) {
            setStatusMsg("Selection is empty.");
            return;
        }
        clipboardW = maxGx - minGx + 1;
        clipboardH = maxGy - minGy + 1;
        clipboardColors = new byte[clipboardW * clipboardH];
        clipboardMask   = new boolean[clipboardW * clipboardH];
        for (int gy = minGy; gy <= maxGy; gy++)
            for (int gx = minGx; gx <= maxGx; gx++)
                if (selMask[selIdx(gx, gy)]) {
                    int ci = (gy - minGy) * clipboardW + (gx - minGx);
                    clipboardColors[ci] = (byte) getGlobalPixel(gx, gy);
                    clipboardMask[ci]   = true;
                }
        setStatusMsg("Copied " + clipboardW + "×" + clipboardH + " selection. Ctrl+V to paste.");
    }

    private void cutSelection() {
        if (selMask == null) {
            setStatusMsg("No selection to cut.");
            return;
        }
        copySelection();
        if (clipboardColors == null) return;
        history.snapshot();
        int gW = gridW(), gH = gridH();
        for (int gy = 0; gy < gH; gy++)
            for (int gx = 0; gx < gW; gx++)
                if (selMask[selIdx(gx, gy)])
                    setGlobalPixel(gx, gy, 0);
        dirty = true;
        rebuildTileColors();
        setStatusMsg("Cut " + clipboardW + "×" + clipboardH + " selection. Ctrl+V to paste.");
    }

    private void startPaste() {
        if (clipboardColors == null) {
            setStatusMsg("Nothing to paste — copy or cut a selection first.");
            return;
        }
        // Cancel other modal states
        inPreview = false; previewColors = null; previewSelMask = null;
        allTilePreviewColors = null; cachedRequantizeSrc = null; pendingGridResult = null;
        lassoPoints = null;
        inPasteMode = true;
        setStatusMsg("Paste: move cursor to position, click or Enter to stamp. Esc/right-click cancels.");
    }

    private void commitPaste() {
        if (!inPasteMode || clipboardColors == null) return;
        int ox = hoverMapX - clipboardW / 2;
        int oy = hoverMapY - clipboardH / 2;
        history.snapshot();
        int placed = 0, clipped = 0;
        boolean[] newSel = new boolean[selSize()];
        for (int py = 0; py < clipboardH; py++) {
            for (int px = 0; px < clipboardW; px++) {
                int ci = py * clipboardW + px;
                if (!clipboardMask[ci]) continue;
                int gx = ox + px, gy = oy + py;
                if (!inMapBounds(gx, gy)) { clipped++; continue; }
                setGlobalPixel(gx, gy, clipboardColors[ci] & 0xFF);
                newSel[selIdx(gx, gy)] = true;
                placed++;
            }
        }
        dirty = true;
        rebuildTileColors();
        inPasteMode = false;
        setSelMask(newSel);
        String msg = "Pasted " + placed + " pixel" + (placed == 1 ? "" : "s");
        if (clipped > 0) msg += " (" + clipped + " clipped outside canvas)";
        setStatusMsg(msg + ".");
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

    /** Checks that (mx, my) is within the full multi-tile grid (global coordinates). */
    private boolean inMapBounds(int mx, int my) {
        return mx >= 0 && mx < gridW() && my >= 0 && my < gridH();
    }

    /** Checks that (lx, ly) is within a single tile (local coordinates). */
    private boolean inLocalBounds(int lx, int ly) {
        return lx >= 0 && lx < MAP_SIZE && ly >= 0 && ly < MAP_SIZE;
    }

    // ── Tier 3 global selection / pixel helpers ───────────────────────────

    /** Total pixels in the global selection mask (≥ MAP_BYTES). */
    private int selSize() { return gridW() * gridH(); }
    /** Flat index into the global selMask / wandPreview for global pixel (gx, gy). */
    private int selIdx(int gx, int gy) { return gx + gy * gridW(); }
    /** Flat index into the global selMask for local pixel (lx, ly) in the active tile. */
    private int activeSelIdx(int lx, int ly) { return selIdx(activeTileGx0() + lx, activeTileGy0() + ly); }
    /** Whether local pixel (lx, ly) of the active tile is in the current selection.
     *  Returns false (unselected) if selMask is null — callers usually guard with `selMask != null`. */
    private boolean selMaskAt(int lx, int ly) {
        return selMask != null && selMask[activeSelIdx(lx, ly)];
    }
    /** Same but accepts a flat local index (lx + ly*MAP_SIZE). */
    private boolean selMaskAt(int localIdx) {
        return selMask != null && selMask[activeSelIdx(localIdx % MAP_SIZE, localIdx / MAP_SIZE)];
    }
    /** Returns a MAP_BYTES-sized boolean array containing just the active tile's selection slice.
     *  Used when passing the selection to functions that expect local-sized masks. */
    private boolean[] localSelMask() {
        if (selMask == null) return null;
        boolean[] local = new boolean[MAP_BYTES];
        for (int ly = 0; ly < MAP_SIZE; ly++)
            for (int lx = 0; lx < MAP_SIZE; lx++)
                local[lx + ly * MAP_SIZE] = selMask[activeSelIdx(lx, ly)];
        return local;
    }
    /** Returns a MAP_BYTES-sized selection slice for tile `ti`, or null if no pixels are selected in that tile. */
    private boolean[] localSelMaskForTile(int ti) {
        if (selMask == null) return null;
        int gx0 = PayloadState.tileCol(ti) * MAP_SIZE;
        int gy0 = PayloadState.tileRow(ti) * MAP_SIZE;
        boolean[] local = new boolean[MAP_BYTES];
        boolean any = false;
        for (int ly = 0; ly < MAP_SIZE; ly++)
            for (int lx = 0; lx < MAP_SIZE; lx++)
                if ((local[lx + ly * MAP_SIZE] = selMask[selIdx(gx0 + lx, gy0 + ly)])) any = true;
        return any ? local : null;
    }
    /** Read pixel color (0–255) from global coords, reading across tiles. */
    private int getGlobalPixel(int gx, int gy) {
        int lx = localX(gx), ly = localY(gy), ti = tileAt(gx, gy);
        if (ti == tileIndex) return mapColors[lx + ly * MAP_SIZE] & 0xFF;
        if (allTileFrames == null || ti >= allTileFrames.length || allTileFrames[ti] == null) return 0;
        return allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)][lx + ly * MAP_SIZE] & 0xFF;
    }
    /** Write pixel color to global coords.  Cross-tile writes only occur in multiTileMode. */
    private void setGlobalPixel(int gx, int gy, int color) {
        int lx = localX(gx), ly = localY(gy), ti = tileAt(gx, gy);
        byte b = (byte) color;
        if (ti == tileIndex) { mapColors[lx + ly * MAP_SIZE] = b; dirty = true; return; }
        if (!multiTileMode || allTileFrames == null || ti >= allTileFrames.length || allTileFrames[ti] == null) return;
        allTileFrames[ti][Math.min(activeFrame, allTileFrames[ti].length - 1)][lx + ly * MAP_SIZE] = b;
        if (tileDirty != null && ti < tileDirty.length) tileDirty[ti] = true;
    }

    // ── Multi-tile coordinate helpers ────────────────────────────────────

    private int gridCols() { return Math.max(1, PayloadState.gridColumns); }
    private int gridRows() { return Math.max(1, PayloadState.gridRows); }
    private int gridW()    { return gridCols() * MAP_SIZE; }
    private int gridH()    { return gridRows() * MAP_SIZE; }

    /** Global tile index owning global pixel (gx, gy). */
    private int tileAt(int gx, int gy) { return (gy / MAP_SIZE) * gridCols() + (gx / MAP_SIZE); }

    /** Local x within tile for a global x. */
    private int localX(int gx) { return gx % MAP_SIZE; }
    /** Local y within tile for a global y. */
    private int localY(int gy) { return gy % MAP_SIZE; }

    /** Screen-space left edge of the active tile (in global grid pixels). */
    private int activeTileGx0() { return PayloadState.tileCol(tileIndex) * MAP_SIZE; }
    /** Screen-space top edge of the active tile (in global grid pixels). */
    private int activeTileGy0() { return PayloadState.tileRow(tileIndex) * MAP_SIZE; }

    /** True iff global pixel (gx, gy) belongs to the active tile. */
    private boolean inActiveTile(int gx, int gy) {
        int x0 = activeTileGx0(), y0 = activeTileGy0();
        return gx >= x0 && gx < x0 + MAP_SIZE && gy >= y0 && gy < y0 + MAP_SIZE;
    }

    /** Local x of current hover within active tile, or -1 if not on active tile. */
    private int localHoverX() {
        if (hoverMapX < 0) return -1;
        int lx = hoverMapX - activeTileGx0();
        return (lx >= 0 && lx < MAP_SIZE) ? lx : -1;
    }

    /** Local y of current hover within active tile, or -1 if not on active tile. */
    private int localHoverY() {
        if (hoverMapY < 0) return -1;
        int ly = hoverMapY - activeTileGy0();
        return (ly >= 0 && ly < MAP_SIZE) ? ly : -1;
    }

    /** True iff the hover position is within the active tile (both local coords ≥ 0). */
    private boolean inHoverActiveLocal() { return localHoverX() >= 0 && localHoverY() >= 0; }

    private void centerMap() {
        int canvasW = width - PANEL_W - GUIDE_W;
        if (multiTileMode && PayloadState.tiles.size() > 1) {
            translateX = GUIDE_W + (canvasW - gridCols() * MAP_SIZE * scale) / 2f;
            translateY = (height - STATUS_H - gridRows() * MAP_SIZE * scale) / 2f;
        } else {
            // Single-tile mode: center the active tile; offset so its global origin lands correctly
            translateX = GUIDE_W + (canvasW - MAP_SIZE * scale) / 2f - activeTileGx0() * scale;
            translateY = (height - STATUS_H - MAP_SIZE * scale) / 2f - activeTileGy0() * scale;
        }
    }

    /** Loads all tile frames from PayloadState into allTileFrames (called once from init). */
    private void loadAllTileFrames() {
        int count = PayloadState.tiles.size();
        if (count == 0) {
            allTileFrames = new byte[1][][];
            allTileFrames[0] = allFrames;
            tileDirty = new boolean[1];
            return;
        }
        allTileFrames = new byte[count][][];
        tileDirty     = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (i == tileIndex) {
                allTileFrames[i] = allFrames; // frames passed to constructor
            } else {
                PayloadState.TileData tile = PayloadState.tiles.get(i);
                try {
                    allTileFrames[i] = LoominaryCommand.resolveAllFramesForTile(tile, tile.chunks);
                } catch (Exception e) {
                    allTileFrames[i] = new byte[][]{new byte[MAP_BYTES]};
                }
            }
        }
    }

    private void switchFrame(int newFrame) {
        if (newFrame == activeFrame || newFrame < 0 || newFrame >= totalFrames) return;
        activeFrame = newFrame;
        mapColors   = allFrames[newFrame];
        history.clear();
        inPreview = false; previewColors = null; previewSelMask = null; allTilePreviewColors = null; cachedRequantizeSrc = null;
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
            byte[] newFrame = computeRequantizeTile(tileIndex, src);

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
            if (allTileFrames != null && tileIndex < allTileFrames.length)
                allTileFrames[tileIndex] = newFrames;
            frameDelayMs = newDelays;
            totalFrames  = newFrames.length;
            activeFrame  = insertAt;
            mapColors    = allFrames[activeFrame];
            if (newSrc != null && !PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size())
                PayloadState.tiles.get(tileIndex).frameSourceIndices = newSrc;
            history.clear();
            inPreview = false; previewColors = null; previewSelMask = null; allTilePreviewColors = null;
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
        if (allTileFrames != null && tileIndex < allTileFrames.length)
            allTileFrames[tileIndex] = newFrames;
        frameDelayMs = newDelays;
        totalFrames  = newFrames.length;
        activeFrame  = Math.min(activeFrame, totalFrames - 1);
        mapColors    = allFrames[activeFrame];
        if (newSrc != null && !PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size()) {
            PayloadState.tiles.get(tileIndex).frameSourceIndices = newSrc;
            requantizeSourceFrame = newSrc.get(Math.min(activeFrame, newSrc.size() - 1));
        }
        history.clear();
        inPreview = false; previewColors = null; previewSelMask = null; allTilePreviewColors = null; cachedRequantizeSrc = null;
        rebuildTileColors();
        dirty = true;
        setStatusMsg("Dropped frame " + (drop + 1) + ". " + totalFrames + " frame" + (totalFrames == 1 ? "" : "s") + " remaining.");
    }

    // ── EditHistory ──────────────────────────────────────────────────────

    private class EditHistory {
        // Snapshots are byte[tileCount][frameCount][MAP_BYTES].
        // In single-tile mode tileCount == 1 (only allFrames is snapped).
        private final Deque<byte[][][]> undoStack = new ArrayDeque<>();
        private final Deque<byte[][][]> redoStack = new ArrayDeque<>();

        void snapshot() {
            if (undoStack.size() >= UNDO_DEPTH) undoStack.removeLast();
            undoStack.push(takeFullSnapshot());
            redoStack.clear();
        }

        void undo() {
            if (undoStack.isEmpty()) return;
            redoStack.push(takeFullSnapshot());
            applySnapshot(undoStack.pop());
            dirty = true; rebuildTileColors();
        }

        void redo() {
            if (redoStack.isEmpty()) return;
            undoStack.push(takeFullSnapshot());
            applySnapshot(redoStack.pop());
            dirty = true; rebuildTileColors();
        }

        boolean canUndo() { return !undoStack.isEmpty(); }
        boolean canRedo() { return !redoStack.isEmpty(); }
        void clear() { undoStack.clear(); redoStack.clear(); }

        private byte[][][] takeFullSnapshot() {
            if (multiTileMode && allTileFrames != null) {
                byte[][][] snap = new byte[allTileFrames.length][][];
                for (int ti = 0; ti < allTileFrames.length; ti++) {
                    if (allTileFrames[ti] == null) continue;
                    snap[ti] = new byte[allTileFrames[ti].length][];
                    for (int f = 0; f < allTileFrames[ti].length; f++)
                        snap[ti][f] = allTileFrames[ti][f].clone();
                }
                return snap;
            }
            byte[][][] snap = new byte[1][][];
            snap[0] = new byte[allFrames.length][];
            for (int f = 0; f < allFrames.length; f++) snap[0][f] = allFrames[f].clone();
            return snap;
        }

        private void applySnapshot(byte[][][] snap) {
            if (multiTileMode && allTileFrames != null && snap.length > 1) {
                for (int ti = 0; ti < Math.min(snap.length, allTileFrames.length); ti++) {
                    if (snap[ti] == null || allTileFrames[ti] == null) continue;
                    for (int f = 0; f < Math.min(snap[ti].length, allTileFrames[ti].length); f++)
                        System.arraycopy(snap[ti][f], 0, allTileFrames[ti][f], 0, MAP_BYTES);
                    if (ti != tileIndex && tileDirty != null && ti < tileDirty.length)
                        tileDirty[ti] = true;
                }
                // allFrames == allTileFrames[tileIndex], already restored above
            } else {
                byte[][] tileSnap = snap[0];
                for (int f = 0; f < Math.min(tileSnap.length, allFrames.length); f++)
                    System.arraycopy(tileSnap[f], 0, allFrames[f], 0, MAP_BYTES);
            }
        }
    }
}
