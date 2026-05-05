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
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final int SWATCHES_START_Y = 70;
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
    private static final int COL_TRANSPARENT = 0xFF111122;
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

    // Color-merge state: Ctrl+click swatches to build the source set, C to commit
    private final Set<Integer> mergeSources = new LinkedHashSet<>();

    // Phase 6 — re-quantize from source (R key)
    private boolean   editorDither;
    private boolean   inPreview       = false;
    private byte[]    previewColors   = null;
    private boolean[] previewSelMask  = null;  // null = whole tile

    // Phase 7 — dither-strength brush (T key)
    // mask = null while unpainted; first stroke initialises all-1.0 then paints.
    private float[]  ditherMask     = null;
    private boolean  useCustomMask  = false;
    private float    ditherStrength = 1.0f;
    private boolean  showMask       = false;

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
        this.editorDither = PayloadState.dither;
        // Load per-frame delays from tile state
        this.frameDelayMs = new int[frames.length];
        if (!PayloadState.tiles.isEmpty() && tileIndex < PayloadState.tiles.size()) {
            List<Integer> stored = PayloadState.tiles.get(tileIndex).frameDelays;
            if (stored != null && !stored.isEmpty()) {
                for (int i = 0; i < frames.length; i++) {
                    // pad with last stored delay if tile had fewer entries (e.g. after drop)
                    frameDelayMs[i] = stored.get(Math.min(i, stored.size() - 1));
                }
            } else {
                Arrays.fill(frameDelayMs, 100);
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
        tileColors   = computeTileColors();
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
                int argb = cb == 0 ? COL_TRANSPARENT : (0xFF000000 | colorLookup[cb]);
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
                        ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[activeColor];
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

        String paletteHeader = mergeSources.isEmpty()
                ? "§7PALETTE"
                : "§7PALETTE §c[" + mergeSources.size() + " merge src" + (mergeSources.size() == 1 ? "" : "s") + "]";
        ctx.drawTextWithShadow(textRenderer, Text.literal(paletteHeader), panelX + 4, 3, 0xFFFFFF);
        ctx.fill(panelX, 13, width, 14, COL_PANEL_SEP);

        int activeRgb = activeColor == 0
                ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[activeColor];
        int asx = panelX + 4, asy = 17;
        ctx.fill(asx-1, asy-1, asx+ACTIVE_SWATCH_SZ+1, asy+ACTIVE_SWATCH_SZ+1, COL_RING);
        ctx.fill(asx, asy, asx+ACTIVE_SWATCH_SZ, asy+ACTIVE_SWATCH_SZ, 0xFF000000|activeRgb);
        String hexStr = activeColor == 0
                ? "trans" : String.format("#%06X", activeRgb & 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + hexStr), panelX+28, 19, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8idx:"+activeColor), panelX+28, 28, 0xFFFFFF);

        ctx.fill(panelX, 41, width, 42, COL_PANEL_SEP);

        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Show: "), panelX+4, 45, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(paletteShowAll ? "§eTile§r" : "§7All"),
                panelX+40, 45, 0xFFFFFF);

        ctx.fill(panelX, 57, width, 58, COL_PANEL_SEP);

        int[] palette = paletteShowAll ? allMapColors : tileColors;
        for (int i = 0; i < palette.length; i++) {
            int row = i / SWATCHES_PER_ROW, col = i % SWATCHES_PER_ROW;
            int swx = panelX + PANEL_MARGIN + col * SWATCH_STRIDE;
            int swy = SWATCHES_START_Y + row * SWATCH_STRIDE;
            if (swy + SWATCH_SZ > panelH) break;

            int c   = palette[i];
            int rgb = c == 0 ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[c];
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
                        Text.literal(String.format("§7tol:§f%.2f", fillTolerance)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8= -  sh+scroll"), x, y, 0xFFFFFF); y += 9;
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
                        Text.literal(String.format("§7tol:§f%.2f", fillTolerance)), x, y, 0xFFFFFF); y += 9;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8= -  sh+scroll"), x, y, 0xFFFFFF); y += 9;
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
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8R  requantize"), x, y, 0xFFFFFF); y += 9;
        String ditherLbl = editorDither ? "§aD  §r§8dither:on" : "§8D  dither:off";
        ctx.drawTextWithShadow(textRenderer, Text.literal(ditherLbl), x, y, 0xFFFFFF); y += 9;
        ctx.fill(0, y, GUIDE_W, y + 1, COL_PANEL_SEP); y += 3;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Merge colors"), x, y, 0xFFFFFF); y += 9;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Ctrl+clk: queue src"), x, y, 0xFFFFFF); y += 9;
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
            ctx.drawTextWithShadow(textRenderer, Text.literal("§8Del  drop frame"), x, y, 0xFFFFFF);
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
                        int argb = cb == 0 ? COL_TRANSPARENT : (0xFF000000 | colorLookup[cb]);
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
        inPreview      = false;
        previewColors  = null;
        previewSelMask = null;
        selMask        = null;
        lassoPoints    = null;
        ditherMask     = null;
        useCustomMask  = false;
        mergeSources.clear();
        thumbnails     = null;  // force thumbnail recompute
        showMinimap    = false;

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
                case FILL         -> String.format("§a[F]Fill§r tol:%.2f §8Sh+sc", fillTolerance);
                case SELECT       -> "§a[S]Rect-sel§r §8Ctrl+A  Ctrl+D";
                case EYEDROPPER   -> "§a[E]Eyedrop§r";
                case DITHER_BRUSH -> String.format("§a[T]Dither§r s:%.1f §8Sh+sc  [M]mask", ditherStrength);
                case LASSO        -> "§a[L]Lasso§r §8drag";
                case MAGIC_WAND   -> String.format("§a[W]Wand§r tol:%.2f §8Sh+sc", fillTolerance);
            };
            String undoHint  = history.canUndo() ? " §8Ctrl+Z" : "";
            String redoHint  = history.canRedo() ? " §8Ctrl+Y" : "";
            String ditherHint = "  " + (editorDither ? "§a[D]dither§r" : "§8[D]dither");
            String reqHint   = PayloadState.currentSourceFilename != null ? "  §8[R]req" : "";
            String selHint   = selMask != null ? "  §e[sel]§r" : "";
            int frameDelay = frameDelayMs != null && activeFrame < frameDelayMs.length
                    ? frameDelayMs[activeFrame] : 100;
            String frameHint = totalFrames > 1
                    ? "  §e" + (activeFrame + 1) + "/" + totalFrames + " " + frameDelay + "ms§r §8Ctrl+[/]" : "";
            String minimapHint = PayloadState.tiles.size() > 1 ? "  §8[G]tiles" : "";
            String mergeHint   = mergeSources.isEmpty() ? ""
                    : "  §c[" + mergeSources.size() + " merge]§r §8C=commit";
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
        if (inPreview) return true;
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
                    fillTolerance = Math.max(0f, Math.min(0.5f,
                            fillTolerance + (float) Math.signum(vAmt) * 0.025f));
                    fillTolerance = Math.round(fillTolerance * 40) / 40f;
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
            if (relY >= 44 && relY < 57 && relX >= 40) {
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

        // Preview mode is modal — only Enter/Y/Esc act
        if (inPreview) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                    || keyCode == GLFW.GLFW_KEY_Y) {
                commitPreview();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                inPreview = false; previewColors = null; previewSelMask = null;
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
        if (keyCode == GLFW.GLFW_KEY_D) { editorDither = !editorDither; return true; }
        if (keyCode == GLFW.GLFW_KEY_R) { applyRequantize(); return true; }
        if (keyCode == GLFW.GLFW_KEY_M) { showMask = !showMask; return true; }
        if (keyCode == GLFW.GLFW_KEY_G) {
            showMinimap = !showMinimap;
            if (showMinimap && thumbnails == null) computeMinimapThumbnails();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C) {
            if (mergeSources.isEmpty()) {
                setStatusMsg("Ctrl+click palette swatches to queue colors for merging, then press C.");
            } else {
                commitMerge();
            }
            return true;
        }

        // Brush / dither-brush radius via [ ] (not when Ctrl held — that nav frames/tiles)
        if (!ctrl && keyCode == GLFW.GLFW_KEY_LEFT_BRACKET
                && (currentTool == Tool.BRUSH || currentTool == Tool.DITHER_BRUSH)) {
            brushRadius = Math.max(0, brushRadius - 1); return true;
        }
        if (!ctrl && keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET
                && (currentTool == Tool.BRUSH || currentTool == Tool.DITHER_BRUSH)) {
            brushRadius = Math.min(MAX_BRUSH, brushRadius + 1); return true;
        }

        // Fill / magic-wand tolerance via = and -
        if ((currentTool == Tool.FILL || currentTool == Tool.MAGIC_WAND)) {
            if (keyCode == GLFW.GLFW_KEY_EQUAL) {
                fillTolerance = Math.round(Math.min(0.5f, fillTolerance+0.025f)*40)/40f; return true;
            }
            if (keyCode == GLFW.GLFW_KEY_MINUS) {
                fillTolerance = Math.round(Math.max(0f, fillTolerance-0.025f)*40)/40f;  return true;
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
            BufferedImage src = ImageIO.read(sourcePath.toFile());
            if (src == null) { setStatusMsg("Could not decode source image"); return; }

            byte[] fullTile;
            if (editorDither && useCustomMask && ditherMask != null) {
                fullTile = requantizeWithCustomMask(src);
            } else {
                byte[][] tiles = PngToMapColors.convertTwoPassGrid(
                        src, !PayloadState.allShades, 0, editorDither,
                        PayloadState.gridColumns, PayloadState.gridRows);
                fullTile = tiles[tileIndex];
            }

            byte[] preview = mapColors.clone();
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
            inPreview      = true;
        } catch (IOException e) {
            setStatusMsg("Re-quantize failed: " + e.getMessage());
        }
    }

    /** Custom-mask path: scales source, extracts this tile, runs two-pass with the painted mask. */
    private byte[] requantizeWithCustomMask(BufferedImage src) {
        int totalW = PayloadState.gridColumns * MAP_SIZE;
        int totalH = PayloadState.gridRows * MAP_SIZE;
        BufferedImage scaledFull = PngToMapColors.scale(src, totalW, totalH);
        int tileCol = PayloadState.tileCol(tileIndex);
        int tileRow = PayloadState.tileRow(tileIndex);
        BufferedImage tileImg = scaledFull.getSubimage(
                tileCol * MAP_SIZE, tileRow * MAP_SIZE, MAP_SIZE, MAP_SIZE);
        float[][] oklabLookup = PngToMapColors.buildOklabLookup();
        // Pass 1: nearest-neighbor to establish the palette
        byte[] firstPass = new byte[MAP_BYTES];
        boolean legalOnly = !PayloadState.allShades;
        for (int y = 0; y < MAP_SIZE; y++)
            for (int x = 0; x < MAP_SIZE; x++)
                firstPass[x + y * MAP_SIZE] = PngToMapColors.findClosestMapColorByte(
                        tileImg.getRGB(x, y), legalOnly, oklabLookup);
        boolean[] palette = PngToMapColors.buildPalette(firstPass);
        return PngToMapColors.renderDithered(tileImg, palette, oklabLookup, ditherMask, MAP_SIZE, MAP_SIZE);
    }

    private void commitPreview() {
        if (!inPreview || previewColors == null) return;
        history.snapshot();
        System.arraycopy(previewColors, 0, mapColors, 0, MAP_BYTES);
        dirty = true;
        rebuildTileColors();
        inPreview = false; previewColors = null; previewSelMask = null;
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

    private void commitMerge() {
        if (mergeSources.isEmpty()) return;
        // Exclude the replacement color itself from the source set (it's a no-op anyway,
        // but excluding it keeps the "N sources replaced" count accurate).
        mergeSources.remove(activeColor);
        if (mergeSources.isEmpty()) {
            setStatusMsg("All queued colors are already the target color.");
            return;
        }
        history.snapshot();
        int replaced = 0;
        for (int i = 0; i < MAP_BYTES; i++) {
            if (selMask != null && !selMask[i]) continue;
            if (mergeSources.contains(mapColors[i] & 0xFF)) {
                mapColors[i] = (byte) activeColor;
                replaced++;
            }
        }
        dirty = true;
        rebuildTileColors();
        String sel = selMask != null ? " (in selection)" : "";
        setStatusMsg(String.format("Merged %d color%s → idx %d: %d pixel%s replaced%s.",
                mergeSources.size(), mergeSources.size() == 1 ? "" : "s",
                activeColor, replaced, replaced == 1 ? "" : "s", sel));
        mergeSources.clear();
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
        List<int[]> entries = new ArrayList<>();
        for (int c = 1; c < 256; c++) {
            if (freq[c] > 0) entries.add(new int[]{c, freq[c]});
        }
        entries.sort((a, b) -> b[1] - a[1]);

        boolean hasTransparent = freq[0] > 0;
        int offset = hasTransparent ? 1 : 0;
        int sz = entries.size() + offset;
        int[] out   = new int[sz];
        int[] freqs = new int[sz];
        if (hasTransparent) { out[0] = 0; freqs[0] = freq[0]; }
        for (int i = 0; i < entries.size(); i++) {
            out[offset + i]   = entries.get(i)[0];
            freqs[offset + i] = entries.get(i)[1];
        }
        tileColorFreqs = freqs;
        maxTileFreq = entries.isEmpty() ? 0 : entries.get(0)[1];
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

    private void rebuildTileColors() { tileColors = computeTileColors(); }

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
        inPreview = false; previewColors = null; previewSelMask = null;
        rebuildTileColors();
    }

    private void dropCurrentFrame() {
        if (totalFrames <= 1) {
            setStatusMsg("Cannot drop the last frame.");
            return;
        }
        int drop = activeFrame;
        byte[][] newFrames = new byte[totalFrames - 1][];
        int[] newDelays = new int[totalFrames - 1];
        int dst = 0;
        for (int i = 0; i < totalFrames; i++) {
            if (i == drop) continue;
            newFrames[dst] = allFrames[i];
            newDelays[dst] = frameDelayMs != null && i < frameDelayMs.length ? frameDelayMs[i] : 100;
            dst++;
        }
        allFrames    = newFrames;
        frameDelayMs = newDelays;
        totalFrames  = newFrames.length;
        activeFrame  = Math.min(activeFrame, totalFrames - 1);
        mapColors    = allFrames[activeFrame];
        history.clear();
        inPreview = false; previewColors = null; previewSelMask = null;
        rebuildTileColors();
        dirty = true;
        setStatusMsg("Dropped frame " + (drop + 1) + ". " + totalFrames + " frame" + (totalFrames == 1 ? "" : "s") + " remaining.");
    }

    // ── EditHistory ──────────────────────────────────────────────────────

    private class EditHistory {
        private final Deque<byte[]> undoStack = new ArrayDeque<>();
        private final Deque<byte[]> redoStack = new ArrayDeque<>();

        void snapshot() {
            if (undoStack.size() >= UNDO_DEPTH) undoStack.removeLast();
            undoStack.push(mapColors.clone());
            redoStack.clear();
        }

        void undo() {
            if (undoStack.isEmpty()) return;
            redoStack.push(mapColors.clone());
            System.arraycopy(undoStack.pop(), 0, mapColors, 0, MAP_BYTES);
            dirty = true; rebuildTileColors();
        }

        void redo() {
            if (redoStack.isEmpty()) return;
            undoStack.push(mapColors.clone());
            System.arraycopy(redoStack.pop(), 0, mapColors, 0, MAP_BYTES);
            dirty = true; rebuildTileColors();
        }

        boolean canUndo() { return !undoStack.isEmpty(); }
        boolean canRedo() { return !redoStack.isEmpty(); }
        void clear() { undoStack.clear(); redoStack.clear(); }
    }
}
