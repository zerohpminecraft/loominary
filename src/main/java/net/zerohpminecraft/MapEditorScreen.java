package net.zerohpminecraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Phase 1 — map viewer / editor skeleton.
 *
 * Opens on the active tile's decoded 128×128 map-color array.
 * Navigation: scroll wheel to zoom, middle-click-drag to pan.
 * The hovered pixel is highlighted and its colour shown in the status bar.
 *
 * The byte[] working copy is held here ready for Phase 2 edits.
 * On close the dirty-flag path re-encodes and saves (no-op until Phase 2
 * sets dirty = true).
 */
public class MapEditorScreen extends Screen {

    private static final int MAP_SIZE    = PngToMapColors.MAP_SIZE;  // 128
    private static final int STATUS_H    = 14;   // status bar height in GUI pixels
    private static final int MIN_SCALE   = 1;
    private static final int MAX_SCALE   = 16;

    // Colours used by the renderer
    private static final int COL_BG        = 0xFF1A1A1A;
    private static final int COL_BAR_BG    = 0xFF121212;
    private static final int COL_BAR_SEP   = 0xFF444444;
    private static final int COL_MAP_BORDER = 0xFFAAAAAA;
    private static final int COL_TRANSPARENT_PIXEL = 0xFF111122;

    final byte[] mapColors;   // working copy — package-private for Phase 2 tools
    boolean dirty = false;    // set by editing tools; triggers re-encode on close

    private final int[] colorLookup;  // map-color byte → packed 0xRRGGBB
    private final int tileIndex;
    private final String tileLabel;

    private int   scale;
    private float translateX;   // screen X of map pixel (0, 0)
    private float translateY;   // screen Y of map pixel (0, 0)

    private int hoverMapX = -1;
    private int hoverMapY = -1;

    private boolean panning;
    private double  lastPanMouseX;
    private double  lastPanMouseY;

    public MapEditorScreen(byte[] mapColors, int tileIndex, String tileLabel) {
        super(Text.literal("Loominary Editor"));
        this.mapColors  = mapColors.clone();
        this.tileIndex  = tileIndex;
        this.tileLabel  = tileLabel;
        this.colorLookup = PngToMapColors.buildColorLookup();
    }

    // ── Screen lifecycle ────────────────────────────────────────────────

    @Override
    protected void init() {
        int canvasH = height - STATUS_H;
        scale = Math.max(MIN_SCALE,
                Math.min(MAX_SCALE, Math.min(width, canvasH) / MAP_SIZE));
        centerMap();
    }

    @Override
    public boolean shouldPause() {
        // Don't pause single-player; the player may be mid-batch and wants
        // the game world to stay running.
        return false;
    }

    @Override
    public void close() {
        if (dirty) {
            // Phase 2+ will re-encode mapColors back into the active tile here.
            // For Phase 1 dirty is never set so this is a no-op.
        }
        super.close();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Update hover coordinates from raw mouse position
        hoverMapX = screenToMap((float) mouseX - translateX);
        hoverMapY = screenToMap((float) mouseY - translateY);

        // Background
        context.fill(0, 0, width, height, COL_BG);

        // Map pixels
        renderMap(context);

        // Hover highlight
        if (inMapBounds(hoverMapX, hoverMapY)) {
            renderHover(context, hoverMapX, hoverMapY);
        }

        // Thin border around the map canvas
        renderMapBorder(context);

        // Status bar at the bottom
        renderStatusBar(context, mouseX, mouseY);

        // Widgets (none in Phase 1, but super call is required)
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderMap(DrawContext context) {
        int canvasH = height - STATUS_H;

        // Compute the range of map pixels that are at least partially visible,
        // so we never iterate all 16 384 pixels when zoomed in.
        int minPx = Math.max(0,         screenToMap(-translateX));
        int maxPx = Math.min(MAP_SIZE-1, screenToMap(width  - translateX));
        int minPy = Math.max(0,         screenToMap(-translateY));
        int maxPy = Math.min(MAP_SIZE-1, screenToMap(canvasH - translateY));

        for (int py = minPy; py <= maxPy; py++) {
            for (int px = minPx; px <= maxPx; px++) {
                int cb   = mapColors[px + py * MAP_SIZE] & 0xFF;
                int argb = cb == 0
                        ? COL_TRANSPARENT_PIXEL
                        : (0xFF000000 | colorLookup[cb]);

                int sx = (int)(translateX + px * scale);
                int sy = (int)(translateY + py * scale);
                context.fill(sx, sy, sx + scale, sy + scale, argb);
            }
        }
    }

    private void renderHover(DrawContext context, int px, int py) {
        int sx = (int)(translateX + px * scale);
        int sy = (int)(translateY + py * scale);

        if (scale >= 3) {
            // 1-pixel white inner border
            context.fill(sx,            sy,            sx + scale, sy + 1,         0xFFFFFFFF);
            context.fill(sx,            sy + scale - 1, sx + scale, sy + scale,    0xFFFFFFFF);
            context.fill(sx,            sy + 1,         sx + 1,     sy + scale - 1, 0xFFFFFFFF);
            context.fill(sx + scale - 1, sy + 1,        sx + scale, sy + scale - 1, 0xFFFFFFFF);
        } else {
            // At small scale just lighten the pixel
            context.fill(sx, sy, sx + scale, sy + scale, 0x66FFFFFF);
        }
    }

    private void renderMapBorder(DrawContext context) {
        int mx = (int) translateX;
        int my = (int) translateY;
        int mw = MAP_SIZE * scale;
        int mh = MAP_SIZE * scale;
        context.fill(mx - 1,     my - 1,     mx + mw + 1, my,          COL_MAP_BORDER);
        context.fill(mx - 1,     my + mh,    mx + mw + 1, my + mh + 1, COL_MAP_BORDER);
        context.fill(mx - 1,     my,         mx,           my + mh,     COL_MAP_BORDER);
        context.fill(mx + mw,    my,         mx + mw + 1, my + mh,     COL_MAP_BORDER);
    }

    private void renderStatusBar(DrawContext context, int mouseX, int mouseY) {
        int barY = height - STATUS_H;
        context.fill(0, barY,     width, height, COL_BAR_BG);
        context.fill(0, barY,     width, barY + 1, COL_BAR_SEP);

        // Left: tile label
        context.drawTextWithShadow(textRenderer,
                Text.literal("§8" + tileLabel),
                4, barY + 3, 0xFFFFFF);

        // Right: hover info + controls hint
        String right;
        if (inMapBounds(hoverMapX, hoverMapY)) {
            int cb  = mapColors[hoverMapX + hoverMapY * MAP_SIZE] & 0xFF;
            int rgb = colorLookup[cb];
            String hex = cb == 0 ? "transparent" : String.format("#%06X", rgb & 0xFFFFFF);
            right = String.format("(%d, %d)  %s  index %d    scale %d×    scroll=zoom  mid-drag=pan",
                    hoverMapX, hoverMapY, hex, cb, scale);
        } else {
            right = String.format("scale %d×    scroll=zoom  mid-drag=pan", scale);
        }

        int tw = textRenderer.getWidth(right);
        context.drawTextWithShadow(textRenderer,
                Text.literal("§7" + right),
                width - tw - 4, barY + 3, 0xFFFFFF);
    }

    // ── Input ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        int oldScale = scale;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                scale + (int) Math.signum(verticalAmount)));
        if (scale != oldScale) {
            // Keep the map pixel under the cursor stationary as the scale changes.
            translateX = (float)(mouseX - (mouseX - translateX) * (float) scale / oldScale);
            translateY = (float)(mouseY - (mouseY - translateY) * (float) scale / oldScale);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 2) {   // middle mouse
            panning       = true;
            lastPanMouseX = mouseX;
            lastPanMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 2) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double deltaX, double deltaY) {
        if (panning) {
            translateX += (float) deltaX;
            translateY += (float) deltaY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    // ── Coordinate helpers ─────────────────────────────────────────────

    /** Convert screen-relative float distance to a map pixel coordinate. */
    private int screenToMap(float screenRelative) {
        return (int) Math.floor(screenRelative / scale);
    }

    private boolean inMapBounds(int mx, int my) {
        return mx >= 0 && mx < MAP_SIZE && my >= 0 && my < MAP_SIZE;
    }

    /** Re-center the map in the canvas area. */
    private void centerMap() {
        translateX = (width  - MAP_SIZE * scale) / 2f;
        translateY = ((height - STATUS_H) - MAP_SIZE * scale) / 2f;
    }
}
