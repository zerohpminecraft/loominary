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
 * In-world map pixel editor — Phases 1–4.
 *
 * Phase 1: viewer skeleton (pan / zoom / hover highlight)
 * Phase 2: undo/redo (Ctrl+Z/Y, 20-level snapshot stack) + left-click paint
 * Phase 3: palette panel (tile colours by frequency) + right-click eyedropper
 * Phase 4: variable brush radius ([/]) + fill bucket (F key)
 *
 * On close, if the image was modified, the active tile is re-encoded and
 * PayloadState is updated so the anvil handler picks up the new chunks.
 * Previously renamed banners for this tile become orphaned (their chunk
 * names changed), exactly as with /loominary resalt or /loominary dither.
 */
public class MapEditorScreen extends Screen {

    // ── Layout constants ────────────────────────────────────────────────
    private static final int MAP_SIZE       = PngToMapColors.MAP_SIZE; // 128
    private static final int MAP_BYTES      = MAP_SIZE * MAP_SIZE;
    private static final int STATUS_H       = 14;   // status-bar height
    private static final int PANEL_W        = 80;   // palette panel width
    private static final int SWATCH_SZ      = 12;   // palette swatch size
    private static final int SWATCH_GAP     = 2;
    private static final int SWATCH_STRIDE  = SWATCH_SZ + SWATCH_GAP; // 14
    private static final int PANEL_MARGIN   = 2;
    private static final int SWATCHES_PER_ROW =
            (PANEL_W - PANEL_MARGIN * 2) / SWATCH_STRIDE; // 5
    private static final int SWATCHES_START_Y = 70; // y inside panel
    private static final int ACTIVE_SWATCH_SZ = 20;
    private static final int CHUNK_SIZE     = 48;
    private static final int MIN_SCALE      = 1;
    private static final int MAX_SCALE      = 16;
    private static final int MAX_BRUSH      = 10;
    private static final int UNDO_DEPTH     = 20;

    // ── Palette colours ─────────────────────────────────────────────────
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
    private enum Tool { BRUSH, FILL }

    // ── State ───────────────────────────────────────────────────────────
    final  byte[] mapColors;      // working copy — package-private for Phase 5+ tools
    boolean dirty = false;

    private final int[]  colorLookup;  // map-color byte → 0xRRGGBB
    private final int    tileIndex;
    private final String tileLabel;

    // Navigation
    private int   scale;
    private float translateX, translateY;
    private boolean panning;
    private double  lastPanMouseX, lastPanMouseY;

    // Hover
    private int hoverMapX = -1, hoverMapY = -1;

    // Tools (Phase 2–4)
    private Tool    currentTool = Tool.BRUSH;
    private int     activeColor = 1;   // map-color byte value 0–255
    private int     brushRadius = 0;   // 0 = single pixel
    private boolean stroking    = false;

    private final EditHistory history = new EditHistory();

    // Palette panel (Phase 3)
    private int[]   tileColors;     // distinct colours in tile, freq-descending
    private int[]   allMapColors;   // every valid map-colour byte
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
    public boolean shouldPause() {
        return false; // world stays running; player may be mid-batch
    }

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
                int argb = cb == 0
                        ? COL_TRANSPARENT
                        : (0xFF000000 | colorLookup[cb]);
                int sx = (int)(translateX + px * scale);
                int sy = (int)(translateY + py * scale);
                ctx.fill(sx, sy, sx + scale, sy + scale, argb);
            }
        }
    }

    /**
     * Brush: tinted footprint overlay showing every pixel that would be painted.
     * Fill: single-pixel outline on the hovered pixel.
     */
    private void renderToolOverlay(DrawContext ctx) {
        if (!inMapBounds(hoverMapX, hoverMapY)) return;

        if (currentTool == Tool.BRUSH) {
            int activeRgb = activeColor == 0
                    ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[activeColor];
            int overlay = (activeRgb & 0x00FFFFFF) | 0x99000000; // ~60% opacity
            int r2 = brushRadius * brushRadius;
            for (int dy = -brushRadius; dy <= brushRadius; dy++) {
                for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                    if (dx*dx + dy*dy > r2) continue;
                    int px = hoverMapX + dx, py = hoverMapY + dy;
                    if (!inMapBounds(px, py)) continue;
                    int sx = (int)(translateX + px * scale);
                    int sy = (int)(translateY + py * scale);
                    ctx.fill(sx, sy, sx + scale, sy + scale, overlay);
                }
            }
        } else { // FILL — single-pixel border
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

    private void renderMapBorder(DrawContext ctx) {
        int mx = (int) translateX, my = (int) translateY;
        int mw = MAP_SIZE * scale,  mh = MAP_SIZE * scale;
        ctx.fill(mx-1, my-1,   mx+mw+1, my,      COL_MAP_BORDER);
        ctx.fill(mx-1, my+mh,  mx+mw+1, my+mh+1, COL_MAP_BORDER);
        ctx.fill(mx-1, my,     mx,       my+mh,   COL_MAP_BORDER);
        ctx.fill(mx+mw, my,    mx+mw+1,  my+mh,   COL_MAP_BORDER);
    }

    private void renderPalettePanel(DrawContext ctx, int mouseX, int mouseY) {
        int panelX = width - PANEL_W;
        int panelH = height - STATUS_H;

        ctx.fill(panelX, 0, width, panelH, COL_PANEL_BG);
        ctx.fill(panelX, 0, panelX + 1, panelH, COL_PANEL_SEP);

        // Header
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7PALETTE"), panelX + 4, 3, 0xFFFFFF);
        ctx.fill(panelX, 13, width, 14, COL_PANEL_SEP);

        // Active colour swatch + info
        int activeRgb = activeColor == 0
                ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[activeColor];
        int asx = panelX + 4, asy = 17;
        ctx.fill(asx - 1, asy - 1, asx + ACTIVE_SWATCH_SZ + 1, asy + ACTIVE_SWATCH_SZ + 1,
                COL_RING);
        ctx.fill(asx, asy, asx + ACTIVE_SWATCH_SZ, asy + ACTIVE_SWATCH_SZ,
                0xFF000000 | activeRgb);
        String hexStr = activeColor == 0
                ? "trans"
                : String.format("#%06X", activeRgb & 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7" + hexStr), panelX + 28, 19, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8idx:" + activeColor), panelX + 28, 28, 0xFFFFFF);

        ctx.fill(panelX, 41, width, 42, COL_PANEL_SEP);

        // Tile / All toggle
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Show: "), panelX + 4, 45, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(paletteShowAll ? "§eTile§r" : "§7All"),
                panelX + 40, 45, 0xFFFFFF);

        ctx.fill(panelX, 57, width, 58, COL_PANEL_SEP);

        // Swatches
        int[] palette = paletteShowAll ? allMapColors : tileColors;
        for (int i = 0; i < palette.length; i++) {
            int row = i / SWATCHES_PER_ROW;
            int col = i % SWATCHES_PER_ROW;
            int sx  = panelX + PANEL_MARGIN + col * SWATCH_STRIDE;
            int sy  = SWATCHES_START_Y + row * SWATCH_STRIDE;
            if (sy + SWATCH_SZ > panelH) break;

            int c   = palette[i];
            int rgb = c == 0 ? (COL_TRANSPARENT & 0xFFFFFF) : colorLookup[c];

            boolean isActive  = (c == activeColor);
            boolean isHovered = mouseX >= sx && mouseX < sx + SWATCH_SZ
                             && mouseY >= sy && mouseY < sy + SWATCH_SZ;

            if (isActive || isHovered) {
                ctx.fill(sx-1, sy-1, sx+SWATCH_SZ+1, sy+SWATCH_SZ+1,
                        isActive ? COL_RING : COL_HOVER_RING);
            }
            ctx.fill(sx, sy, sx + SWATCH_SZ, sy + SWATCH_SZ, 0xFF000000 | rgb);
        }
    }

    private void renderStatusBar(DrawContext ctx) {
        int barY = height - STATUS_H;
        ctx.fill(0, barY, width, height, COL_STATUS_BG);
        ctx.fill(0, barY, width, barY + 1, COL_STATUS_SEP);

        // Left: label + active tool (highlighted) + inactive tool + undo hint
        String brushLabel  = currentTool == Tool.BRUSH ? "§a[B]Brush§r" : "§8[B]Brush";
        String fillLabel   = currentTool == Tool.FILL  ? "§a[F]Fill§r"  : "§8[F]Fill";
        String brushDetail = currentTool == Tool.BRUSH
                ? String.format(" r:%d  §8[/] size", brushRadius) : "";
        String undoHint    = history.canUndo() ? "  §8Ctrl+Z" : "";
        String redoHint    = history.canRedo() ? "  §8Ctrl+Y" : "";
        String left = String.format("§8%s  §r%s%s  %s%s%s",
                tileLabel, brushLabel, brushDetail, fillLabel, undoHint, redoHint);
        ctx.drawTextWithShadow(textRenderer, Text.literal(left), 4, barY + 3, 0xFFFFFF);

        // Right: hover info or controls hint
        String right = inMapBounds(hoverMapX, hoverMapY)
                ? hoverInfoString()
                : String.format("scroll=zoom  mid-drag=pan  %d×", scale);
        int rw = textRenderer.getWidth(right);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7" + right),
                width - PANEL_W - rw - 8, barY + 3, 0xFFFFFF);
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

        // ── Palette-panel clicks ─────────────────────────────────────────
        if (mouseX >= panelX) {
            int relX = (int) mouseX - panelX;
            int relY = (int) mouseY;
            // Toggle button row (y 44–56)
            if (relY >= 44 && relY < 57 && relX >= 40) {
                paletteShowAll = !paletteShowAll;
                return true;
            }
            // Swatch grid
            if (relY >= SWATCHES_START_Y) {
                int[] palette = paletteShowAll ? allMapColors : tileColors;
                int row = (relY - SWATCHES_START_Y) / SWATCH_STRIDE;
                int col = (relX - PANEL_MARGIN) / SWATCH_STRIDE;
                if (col >= 0 && col < SWATCHES_PER_ROW) {
                    int idx = row * SWATCHES_PER_ROW + col;
                    if (idx < palette.length) {
                        activeColor = palette[idx];
                        return true;
                    }
                }
            }
            return false;
        }

        // ── Canvas clicks ────────────────────────────────────────────────
        if (!inMapBounds(hoverMapX, hoverMapY))
            return super.mouseClicked(mouseX, mouseY, button);

        if (button == 0) {
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
        if (button == 2) { // middle-click = pan
            panning = true;
            lastPanMouseX = mouseX;
            lastPanMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && stroking) {
            stroking = false;
            rebuildTileColors();
            return true;
        }
        if (button == 2) { panning = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double dX, double dY) {
        if (button == 0 && stroking && currentTool == Tool.BRUSH
                && inMapBounds(hoverMapX, hoverMapY)) {
            applyBrushAt(hoverMapX, hoverMapY); // snapshot already taken at stroke start
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
        boolean ctrl = hasControlDown();
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) { history.undo(); return true; }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) { history.redo(); return true; }
        if (keyCode == GLFW.GLFW_KEY_B) { currentTool = Tool.BRUSH; return true; }
        if (keyCode == GLFW.GLFW_KEY_F) { currentTool = Tool.FILL;  return true; }
        if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET)  {
            brushRadius = Math.max(0, brushRadius - 1); return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
            brushRadius = Math.min(MAX_BRUSH, brushRadius + 1); return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Tool implementations ─────────────────────────────────────────────

    /** Paint the brush footprint centred on (cx, cy). Caller owns the snapshot. */
    private void applyBrushAt(int cx, int cy) {
        int r2 = brushRadius * brushRadius;
        for (int dy = -brushRadius; dy <= brushRadius; dy++) {
            for (int dx = -brushRadius; dx <= brushRadius; dx++) {
                if (dx*dx + dy*dy > r2) continue;
                int px = cx + dx, py = cy + dy;
                if (inMapBounds(px, py))
                    mapColors[px + py * MAP_SIZE] = (byte) activeColor;
            }
        }
        dirty = true;
    }

    /** 4-connected flood fill from (startX, startY) replacing target colour. */
    private void applyFillAt(int startX, int startY) {
        int target = mapColors[startX + startY * MAP_SIZE] & 0xFF;
        if (target == activeColor) return;
        history.snapshot();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{startX, startY});
        while (!queue.isEmpty()) {
            int[] p = queue.pop();
            int x = p[0], y = p[1];
            if (!inMapBounds(x, y)) continue;
            if ((mapColors[x + y * MAP_SIZE] & 0xFF) != target) continue;
            mapColors[x + y * MAP_SIZE] = (byte) activeColor;
            queue.push(new int[]{x+1, y});
            queue.push(new int[]{x-1, y});
            queue.push(new int[]{x, y+1});
            queue.push(new int[]{x, y-1});
        }
        dirty = true;
    }

    // ── Re-encode on close ───────────────────────────────────────────────

    /**
     * Re-encodes the edited mapColors back into the active tile's chunks.
     * Resets activeChunkIndex to 0 since all chunk names change with the content.
     */
    private void reEncode() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || PayloadState.tiles.isEmpty()) return;
        try {
            String playerName = client.player.getGameProfile().getName();
            int flags     = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
            int tileNonce = PayloadState.tiles.get(tileIndex).nonce;
            byte[] manifest = PayloadManifest.toBytes(
                    flags,
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
                    + b64.substring(start, Math.min(start + CHUNK_SIZE, b64.length())));
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
        return entries.stream().mapToInt(e -> e[0]).toArray();
    }

    private int[] computeAllMapColors() {
        List<Integer> all = new ArrayList<>();
        for (int c = 1; c < 256; c++) {
            if (colorLookup[c] != 0) all.add(c);
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Refresh tile colour list after edits. Called at end-of-stroke and after fill. */
    private void rebuildTileColors() {
        tileColors = computeTileColors();
    }

    // ── Coordinate helpers ───────────────────────────────────────────────

    private int screenToMap(float screenRelative) {
        return (int) Math.floor(screenRelative / scale);
    }

    private boolean inMapBounds(int mx, int my) {
        return mx >= 0 && mx < MAP_SIZE && my >= 0 && my < MAP_SIZE;
    }

    private void centerMap() {
        int canvasW = width  - PANEL_W;
        int canvasH = height - STATUS_H;
        translateX = (canvasW - MAP_SIZE * scale) / 2f;
        translateY = (canvasH - MAP_SIZE * scale) / 2f;
    }

    // ── EditHistory (Phase 2) ────────────────────────────────────────────

    /**
     * Snapshot-based undo/redo.  Caller must call {@link #snapshot()} BEFORE
     * any destructive operation; undo/redo copy back into the live array so the
     * mapColors reference stays stable.
     */
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
