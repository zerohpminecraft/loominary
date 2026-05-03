package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

/**
 * In-world map pixel editor — Phases 1–5.
 *
 * Phase 1: viewer skeleton (pan / zoom / hover)
 * Phase 2: undo/redo (Ctrl+Z/Y, 20-level) + left-click paint
 * Phase 3: palette panel (tile colours by frequency) + right-click eyedropper
 * Phase 4: variable brush radius (Shift+scroll / [/]) + fill bucket (F)
 * Phase 5: rectangle selection (S) with marching-ants overlay;
 *          brush and fill are constrained to the active selection;
 *          Ctrl+A = select all, Ctrl+D / Esc = deselect
 *
 * On close, if dirty, the active tile is re-encoded and PayloadState updated.
 */
public class MapEditorScreen extends Screen {

    // ── Layout constants ────────────────────────────────────────────────
    private static final int MAP_SIZE       = PngToMapColors.MAP_SIZE;
    private static final int MAP_BYTES      = MAP_SIZE * MAP_SIZE;
    private static final int STATUS_H       = 14;
    private static final int PANEL_W        = 80;
    private static final int SWATCH_SZ      = 12;
    private static final int SWATCH_GAP     = 2;
    private static final int SWATCH_STRIDE  = SWATCH_SZ + SWATCH_GAP;
    private static final int PANEL_MARGIN   = 2;
    private static final int SWATCHES_PER_ROW = (PANEL_W - PANEL_MARGIN * 2) / SWATCH_STRIDE;
    private static final int SWATCHES_START_Y = 70;
    private static final int ACTIVE_SWATCH_SZ = 20;
    private static final int CHUNK_SIZE     = 48;
    private static final int MIN_SCALE      = 1;
    private static final int MAX_SCALE      = 16;
    private static final int MAX_BRUSH      = 10;
    private static final int UNDO_DEPTH     = 20;

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

    // ── Tool enum ───────────────────────────────────────────────────────
    private enum Tool { BRUSH, FILL, SELECT }

    // ── State ───────────────────────────────────────────────────────────
    final  byte[] mapColors;      // working copy — package-private for Phase 6+ tools
    boolean dirty = false;

    private final int[]  colorLookup;
    private final int    tileIndex;
    private final String tileLabel;

    // Navigation
    private int   scale;
    private float translateX, translateY;
    private boolean panning;
    private double  lastPanMouseX, lastPanMouseY;

    // Hover
    private int hoverMapX = -1, hoverMapY = -1;

    // Tools
    private Tool    currentTool   = Tool.BRUSH;
    private int     activeColor   = 1;
    private int     brushRadius   = 0;
    private float   fillTolerance = 0f;
    private boolean stroking      = false;

    // Selection (Phase 5)
    private boolean hasSelection = false;
    private int     selX1, selY1, selX2, selY2;   // inclusive map-pixel bounds
    private boolean selecting    = false;
    private int     dragOriginX, dragOriginY;

    private final EditHistory history = new EditHistory();

    // Palette panel
    private int[]   tileColors;
    private int[]   allMapColors;
    private boolean paletteShowAll = false;

    // ── Constructor ─────────────────────────────────────────────────────

    public MapEditorScreen(byte[] mapColors, int tileIndex, String tileLabel) {
        super(Text.literal("Loominary Editor"));
        this.mapColors   = mapColors.clone();
        this.tileIndex   = tileIndex;
        this.tileLabel   = tileLabel;
        this.colorLookup = PngToMapColors.buildColorLookup();
    }

    // ── Screen lifecycle ────────────────────────────────────────────────

    @Override
    protected void init() {
        int canvasW = width  - PANEL_W;
        int canvasH = height - STATUS_H;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                Math.min(canvasW, canvasH) / MAP_SIZE));
        centerMap();
        tileColors   = computeTileColors();
        allMapColors = computeAllMapColors();
        if (tileColors.length > 0) activeColor = tileColors[0];
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (dirty) reEncode();
        super.close();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int canvasW = width - PANEL_W;
        if (mouseX < canvasW && mouseY < height - STATUS_H) {
            hoverMapX = screenToMap((float) mouseX - translateX);
            hoverMapY = screenToMap((float) mouseY - translateY);
        } else {
            hoverMapX = hoverMapY = -1;
        }

        ctx.fill(0, 0, width, height, COL_BG);
        renderMap(ctx);
        renderToolOverlay(ctx);
        renderSelectionOverlay(ctx);
        renderMapBorder(ctx);
        renderPalettePanel(ctx, mouseX, mouseY);
        renderStatusBar(ctx);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderMap(DrawContext ctx) {
        int canvasH = height - STATUS_H;
        int minPx = Math.max(0,         screenToMap(-translateX));
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width - PANEL_W - translateX));
        int minPy = Math.max(0,         screenToMap(-translateY));
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY));

        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                int cb   = mapColors[px + py * MAP_SIZE] & 0xFF;
                int argb = cb == 0 ? COL_TRANSPARENT : (0xFF000000 | colorLookup[cb]);
                int sx = (int)(translateX + px * scale);
                int sy = (int)(translateY + py * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, argb);
            }
        }
    }

    private void renderToolOverlay(DrawContext ctx) {
        if (currentTool == Tool.SELECT) return; // selection cursor handled separately
        if (!inMapBounds(hoverMapX, hoverMapY)) return;

        if (currentTool == Tool.BRUSH) {
            int activeRgb = activeColor == 0
                    ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[activeColor];
            int overlay = (activeRgb & 0x00FFFFFF) | 0x99000000;
            int r2 = brushRadius * brushRadius;
            for (int dy = -brushRadius; dy <= brushRadius; dy++) {
                for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                    if (dx*dx + dy*dy > r2) continue;
                    int px = hoverMapX + dx, py = hoverMapY + dy;
                    if (!inMapBounds(px, py)) continue;
                    if (hasSelection && !inSelection(px, py)) continue;
                    int sx = (int)(translateX + px * scale);
                    int sy = (int)(translateY + py * scale);
                    ctx.fill(sx, sy, sx + scale, sy + scale, overlay);
                }
            }
        } else { // FILL — single-pixel outline
            int sx = (int)(translateX + hoverMapX * scale);
            int sy = (int)(translateY + hoverMapY * scale);
            if (scale >= 3) {
                ctx.fill(sx,             sy,              sx + scale,  sy + 1,           0xFFFFFFFF);
                ctx.fill(sx,             sy + scale - 1,  sx + scale,  sy + scale,       0xFFFFFFFF);
                ctx.fill(sx,             sy + 1,          sx + 1,      sy + scale - 1,   0xFFFFFFFF);
                ctx.fill(sx + scale - 1, sy + 1,          sx + scale,  sy + scale - 1,   0xFFFFFFFF);
            } else {
                ctx.fill(sx, sy, sx + scale, sy + scale, 0x66FFFFFF);
            }
        }
    }

    /**
     * Draws a semi-transparent tint over the selected region and an animated
     * marching-ants border around it.  Also shows a crosshair-style cursor
     * when the SELECT tool is active.
     */
    private void renderSelectionOverlay(DrawContext ctx) {
        // SELECT tool cursor (crosshair at hover pixel)
        if (currentTool == Tool.SELECT && inMapBounds(hoverMapX, hoverMapY)) {
            int sx = (int)(translateX + hoverMapX * scale);
            int sy = (int)(translateY + hoverMapY * scale);
            // thin cross centred on the pixel
            int cx = sx + scale / 2, cy = sy + scale / 2;
            ctx.fill(cx - 3, cy, cx + 4, cy + 1, 0x99FFFFFF);
            ctx.fill(cx, cy - 3, cx + 1, cy + 4, 0x99FFFFFF);
        }

        if (!hasSelection) return;

        int sx1 = (int)(translateX + selX1 * scale);
        int sy1 = (int)(translateY + selY1 * scale);
        int sx2 = (int)(translateX + (selX2 + 1) * scale);
        int sy2 = (int)(translateY + (selY2 + 1) * scale);

        // Semi-transparent fill inside selection
        ctx.fill(sx1, sy1, sx2, sy2, 0x33FFFFFF);

        // Marching-ants border — offset cycles every ~120 ms
        int off = (int)((System.currentTimeMillis() / 120) % 8);
        marchingAntsH(ctx, sx1, sy1 - 1, sx2, off);
        marchingAntsH(ctx, sx1, sy2,     sx2, off);
        marchingAntsV(ctx, sx1 - 1, sy1, sy2, off);
        marchingAntsV(ctx, sx2,     sy1, sy2, off);
    }

    /**
     * Draws a horizontal 1-pixel dashed line with a [4px white / 4px dark]
     * repeating pattern animated by {@code off}.
     */
    private static void marchingAntsH(DrawContext ctx, int x1, int y, int x2, int off) {
        for (int x = x1; x < x2; ) {
            int phase = ((x - x1 + off) % 8 + 8) % 8;
            int run   = phase < 4 ? (4 - phase) : (8 - phase);
            int end   = Math.min(x2, x + run);
            ctx.fill(x, y, end, y + 1, phase < 4 ? 0xFFFFFFFF : 0xFF222222);
            x = end;
        }
    }

    /** Vertical counterpart of {@link #marchingAntsH}. */
    private static void marchingAntsV(DrawContext ctx, int x, int y1, int y2, int off) {
        for (int y = y1; y < y2; ) {
            int phase = ((y - y1 + off) % 8 + 8) % 8;
            int run   = phase < 4 ? (4 - phase) : (8 - phase);
            int end   = Math.min(y2, y + run);
            ctx.fill(x, y, x + 1, end, phase < 4 ? 0xFFFFFFFF : 0xFF222222);
            y = end;
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

        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7PALETTE"), panelX + 4, 3, 0xFFFFFF);
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
            int sx = panelX + PANEL_MARGIN + col * SWATCH_STRIDE;
            int sy = SWATCHES_START_Y + row * SWATCH_STRIDE;
            if (sy + SWATCH_SZ > panelH) break;

            int c   = palette[i];
            int rgb = c == 0 ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[c];
            boolean isActive  = (c == activeColor);
            boolean isHovered = mouseX >= sx && mouseX < sx+SWATCH_SZ
                             && mouseY >= sy && mouseY < sy+SWATCH_SZ;
            if (isActive || isHovered)
                ctx.fill(sx-1, sy-1, sx+SWATCH_SZ+1, sy+SWATCH_SZ+1,
                        isActive ? COL_RING : COL_HOVER_RING);
            ctx.fill(sx, sy, sx+SWATCH_SZ, sy+SWATCH_SZ, 0xFF000000|rgb);
        }
    }

    private void renderStatusBar(DrawContext ctx) {
        int barY = height - STATUS_H;
        ctx.fill(0, barY, width, height, COL_STATUS_BG);
        ctx.fill(0, barY, width, barY+1, COL_STATUS_SEP);

        String brushLabel  = currentTool == Tool.BRUSH  ? "§a[B]Brush§r"  : "§8[B]Brush";
        String fillLabel   = currentTool == Tool.FILL   ? "§a[F]Fill§r"   : "§8[F]Fill";
        String selectLabel = currentTool == Tool.SELECT ? "§a[S]Select§r" : "§8[S]Select";
        String toolDetail  = switch (currentTool) {
            case BRUSH  -> String.format(" r:%d", brushRadius);
            case FILL   -> String.format(" tol:%.3f", fillTolerance);
            case SELECT -> hasSelection
                    ? String.format(" %d×%d §8Ctrl+D: clear", selX2-selX1+1, selY2-selY1+1)
                    : " §8Ctrl+A: all";
        };
        String undoHint = history.canUndo() ? "  §8Ctrl+Z" : "";
        String redoHint = history.canRedo() ? "  §8Ctrl+Y" : "";
        String left = String.format("§8%s  §r%s%s §8(Sh+scroll)  %s  %s%s%s",
                tileLabel, activeToolLabel(brushLabel, fillLabel, selectLabel),
                toolDetail, otherToolLabels(brushLabel, fillLabel, selectLabel),
                undoHint, redoHint, hasSelection ? "  §e[sel]§r" : "");
        ctx.drawTextWithShadow(textRenderer, Text.literal(left), 4, barY+3, 0xFFFFFF);

        String right = inMapBounds(hoverMapX, hoverMapY)
                ? hoverInfoString() : String.format("scroll=zoom  mid-drag=pan  %d×", scale);
        int rw = textRenderer.getWidth(right);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7" + right), width - PANEL_W - rw - 8, barY+3, 0xFFFFFF);
    }

    private String activeToolLabel(String b, String f, String s) {
        return switch (currentTool) { case BRUSH -> b; case FILL -> f; case SELECT -> s; };
    }

    private String otherToolLabels(String b, String f, String s) {
        return switch (currentTool) {
            case BRUSH  -> f + "  " + s;
            case FILL   -> b + "  " + s;
            case SELECT -> b + "  " + f;
        };
    }

    private String hoverInfoString() {
        int cb  = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
        int rgb = colorLookup[cb];
        String hex = cb == 0 ? "transparent" : String.format("#%06X", rgb & 0xFFFFFF);
        return String.format("(%d, %d) %s  %d×", hoverMapX, hoverMapY, hex, scale);
    }

    // ── Input ────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double hAmt, double vAmt) {
        if (mouseX >= width - PANEL_W) return false;

        if (hasShiftDown()) {
            if (currentTool == Tool.BRUSH) {
                brushRadius = Math.max(0, Math.min(MAX_BRUSH,
                        brushRadius + (int) Math.signum(vAmt)));
            } else if (currentTool == Tool.FILL) {
                fillTolerance = Math.max(0f, Math.min(0.5f,
                        fillTolerance + (float) Math.signum(vAmt) * 0.025f));
                fillTolerance = Math.round(fillTolerance * 40) / 40f;
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
        int panelX = width - PANEL_W;

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
                    if (idx < palette.length) { activeColor = palette[idx]; return true; }
                }
            }
            return false;
        }

        // ── Canvas ───────────────────────────────────────────────────────
        if (!inMapBounds(hoverMapX, hoverMapY))
            return super.mouseClicked(mouseX, mouseY, button);

        if (button == 0) {
            if (currentTool == Tool.SELECT) {
                // Start rubber-band selection; deselect any existing one.
                hasSelection = false;
                selecting    = true;
                dragOriginX  = hoverMapX;
                dragOriginY  = hoverMapY;
                selX1 = selX2 = hoverMapX;
                selY1 = selY2 = hoverMapY;
                return true;
            }
            if (hasSelection && !inSelection(hoverMapX, hoverMapY)) {
                // Click outside selection with a non-select tool is a no-op —
                // the selection acts as a stencil, not a region-switch trigger.
                return true;
            }
            if (currentTool == Tool.BRUSH) {
                history.snapshot();
                stroking = true;
                applyBrushAt(hoverMapX, hoverMapY);
            } else {
                applyFillAt(hoverMapX, hoverMapY);
                rebuildTileColors();
            }
            return true;
        }
        if (button == 1) { // right-click = eyedropper
            activeColor = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
            return true;
        }
        if (button == 2) {
            panning = true;
            lastPanMouseX = mouseX;
            lastPanMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (stroking) {
                stroking = false;
                rebuildTileColors();
            }
            if (selecting) {
                selecting = false;
                // A click without any drag (origin == current) deselects.
                if (selX1 == dragOriginX && selX2 == dragOriginX
                        && selY1 == dragOriginY && selY2 == dragOriginY) {
                    hasSelection = false;
                }
            }
            return true;
        }
        if (button == 2) { panning = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double dX, double dY) {
        if (button == 0) {
            if (selecting) {
                // Update the rubber-band rectangle as the mouse moves.
                selX1 = Math.min(dragOriginX, hoverMapX);
                selY1 = Math.min(dragOriginY, hoverMapY);
                selX2 = Math.max(dragOriginX, hoverMapX);
                selY2 = Math.max(dragOriginY, hoverMapY);
                hasSelection = true; // show live rubber-band
                return true;
            }
            if (stroking && currentTool == Tool.BRUSH && inMapBounds(hoverMapX, hoverMapY)) {
                applyBrushAt(hoverMapX, hoverMapY);
                return true;
            }
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
        boolean ctrl = hasControlDown();

        // Escape: deselect first, close only when nothing is selected.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (hasSelection) { hasSelection = false; return true; }
            // fall through to super → close()
        }

        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) { history.undo(); return true; }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) { history.redo(); return true; }

        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            selX1 = 0; selY1 = 0; selX2 = MAP_SIZE-1; selY2 = MAP_SIZE-1;
            hasSelection = true;
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_D) { hasSelection = false; return true; }

        if (keyCode == GLFW.GLFW_KEY_B) { currentTool = Tool.BRUSH;  return true; }
        if (keyCode == GLFW.GLFW_KEY_F) { currentTool = Tool.FILL;   return true; }
        if (keyCode == GLFW.GLFW_KEY_S) { currentTool = Tool.SELECT; return true; }

        // [ / ] change brush size (US layout; Shift+Scroll works on all layouts).
        if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET)  { brushRadius = Math.max(0, brushRadius-1);        return true; }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) { brushRadius = Math.min(MAX_BRUSH, brushRadius+1); return true; }

        // = / - adjust fill tolerance (Shift+Scroll also works).
        if (keyCode == GLFW.GLFW_KEY_EQUAL && currentTool == Tool.FILL) {
            fillTolerance = Math.round(Math.min(0.5f, fillTolerance+0.025f)*40)/40f; return true;
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS && currentTool == Tool.FILL) {
            fillTolerance = Math.round(Math.max(0f, fillTolerance-0.025f)*40)/40f;  return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Tool implementations ─────────────────────────────────────────────

    private void applyBrushAt(int cx, int cy) {
        int r2 = brushRadius * brushRadius;
        for (int dy = -brushRadius; dy <= brushRadius; dy++) {
            for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                if (dx*dx + dy*dy > r2) continue;
                int px = cx + dx, py = cy + dy;
                if (!inMapBounds(px, py)) continue;
                if (hasSelection && !inSelection(px, py)) continue;
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
            if (hasSelection && !inSelection(x, y)) continue;
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

    // ── Re-encode on close ───────────────────────────────────────────────

    private void reEncode() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || PayloadState.tiles.isEmpty()) return;
        try {
            String playerName = client.player.getGameProfile().getName();
            int flags     = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
            int tileNonce = PayloadState.tiles.get(tileIndex).nonce;
            byte[] manifest = PayloadManifest.toBytes(flags,
                    PayloadState.gridColumns, PayloadState.gridRows,
                    PayloadState.tileCol(tileIndex), PayloadState.tileRow(tileIndex),
                    PayloadManifest.crc32(mapColors),
                    playerName, PayloadState.currentTitle, tileNonce);
            List<String> newChunks = buildChunks(manifest, mapColors);
            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(newChunks);
            PayloadState.activeChunkIndex = 0;
            PayloadState.save();
            System.out.println("[Loominary] Editor: saved "
                    + newChunks.size() + " chunks for " + tileLabel);
        } catch (Exception e) {
            System.err.println("[Loominary] Editor: re-encode failed: " + e.getMessage());
        }
    }

    private static List<String> buildChunks(byte[] manifestBytes, byte[] mapColors) {
        byte[] combined = new byte[manifestBytes.length + mapColors.length];
        System.arraycopy(manifestBytes, 0, combined, 0, manifestBytes.length);
        System.arraycopy(mapColors, 0, combined, manifestBytes.length, mapColors.length);
        byte[] compressed = Zstd.compress(combined, Zstd.maxCompressionLevel());
        String b64 = Base64.getEncoder().encodeToString(compressed);
        int total = (b64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        List<String> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_SIZE;
            chunks.add(String.format("%02x", i)
                    + b64.substring(start, Math.min(start+CHUNK_SIZE, b64.length())));
        }
        return chunks;
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
        int[] sorted = entries.stream().mapToInt(e -> e[0]).toArray();
        if (freq[0] == 0) return sorted;
        // Transparent pixels present — prepend color 0 regardless of frequency rank.
        int[] out = new int[sorted.length + 1];
        System.arraycopy(sorted, 0, out, 1, sorted.length);
        return out;
    }

    private int[] computeAllMapColors() {
        List<Integer> all = new ArrayList<>();
        all.add(0); // transparent / erase always first
        for (int c = 1; c < 256; c++) {
            if (colorLookup[c] != 0) all.add(c);
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    private void rebuildTileColors() { tileColors = computeTileColors(); }

    // ── Coordinate and selection helpers ─────────────────────────────────

    private int screenToMap(float screenRelative) {
        return (int) Math.floor(screenRelative / scale);
    }

    private boolean inMapBounds(int mx, int my) {
        return mx >= 0 && mx < MAP_SIZE && my >= 0 && my < MAP_SIZE;
    }

    private boolean inSelection(int mx, int my) {
        return mx >= selX1 && mx <= selX2 && my >= selY1 && my <= selY2;
    }

    private void centerMap() {
        translateX = (width  - PANEL_W - MAP_SIZE * scale) / 2f;
        translateY = (height - STATUS_H - MAP_SIZE * scale) / 2f;
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
            dirty = true;
            rebuildTileColors();
        }

        void redo() {
            if (redoStack.isEmpty()) return;
            undoStack.push(mapColors.clone());
            System.arraycopy(redoStack.pop(), 0, mapColors, 0, MAP_BYTES);
            dirty = true;
            rebuildTileColors();
        }

        boolean canUndo() { return !undoStack.isEmpty(); }
        boolean canRedo() { return !redoStack.isEmpty(); }
    }
}
