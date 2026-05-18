package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Base64;

/**
 * GUI for browsing and managing the local mapart Catalogue.
 *
 * Left panel:   scrollable entry list with 30×30 thumbnails
 * Right panel:  entry details (title, author, date, source, share toggle)
 * Bottom bar:   Add Current Batch / Load to Batch / Delete
 */
public class CatalogueScreen extends Screen {

    private static final int COL_BG         = 0xFF1A1A1A;
    private static final int COL_PANEL_BG   = 0xFF141414;
    private static final int COL_SEP        = 0xFF333333;
    private static final int COL_SELECTED   = 0xFF2A4A6A;
    private static final int COL_HOVER      = 0xFF222222;
    private static final int COL_TEXT       = 0xFFDDDDDD;
    private static final int COL_DIM        = 0xFF888888;
    private static final int COL_GREEN      = 0xFF44CC44;
    private static final int COL_ORANGE     = 0xFFCC7722;

    private static final int LIST_W       = 220;  // left list panel width
    private static final int THUMB_PX     = 30;   // thumbnail display size (3× scale of 10px)
    private static final int ENTRY_H      = 40;   // list row height
    private static final int ENTRY_PAD    = 5;    // padding inside row
    private static final int BTN_H        = 20;
    private static final int BTN_BAR_H    = 28;   // bottom action bar height

    private static final int[] COLOR_LOOKUP = PngToMapColors.buildColorLookup();

    private int selectedIndex = -1;
    private int scrollOffset  = 0;      // first visible entry index
    private int hoveredIndex  = -1;

    private ButtonWidget btnAddBatch;
    private ButtonWidget btnLoadBatch;
    private ButtonWidget btnDelete;
    private ButtonWidget btnShare;

    public CatalogueScreen() {
        super(Text.literal("Loominary Catalogue"));
    }

    @Override
    protected void init() {
        int bw = (width - LIST_W - 16) / 3;  // button width in right panel area
        int by = height - BTN_BAR_H + 4;

        btnAddBatch = ButtonWidget.builder(Text.literal("Add Current Batch"), b -> addCurrentBatch())
                .dimensions(4, by, 100, BTN_H).build();
        btnLoadBatch = ButtonWidget.builder(Text.literal("Load to Batch"), b -> loadToBatch())
                .dimensions(108, by, 90, BTN_H).build();
        btnDelete = ButtonWidget.builder(Text.literal("Delete"), b -> deleteSelected())
                .dimensions(202, by, 60, BTN_H).build();
        btnShare = ButtonWidget.builder(Text.literal("Share: OFF"), b -> toggleShare())
                .dimensions(LIST_W + 8, by, 90, BTN_H).build();

        addDrawableChild(btnAddBatch);
        addDrawableChild(btnLoadBatch);
        addDrawableChild(btnDelete);
        addDrawableChild(btnShare);

        updateButtons();
    }

    private void updateButtons() {
        boolean hasSelected = selectedIndex >= 0 && selectedIndex < CatalogueState.entries.size();
        btnLoadBatch.active = hasSelected
                && CatalogueState.entries.get(selectedIndex).tiles != null;
        btnDelete.active = hasSelected;
        btnShare.active  = hasSelected;

        if (hasSelected) {
            boolean shared = CatalogueState.entries.get(selectedIndex).shareEnabled;
            btnShare.setMessage(Text.literal("Share: " + (shared ? "ON" : "OFF")));
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Background
        ctx.fill(0, 0, width, height, COL_BG);

        // Left panel background
        ctx.fill(0, 0, LIST_W, height - BTN_BAR_H, COL_PANEL_BG);
        ctx.fill(LIST_W, 0, LIST_W + 1, height - BTN_BAR_H, COL_SEP);

        // Title header
        ctx.fill(0, 0, width, 14, 0xFF0D0D0D);
        ctx.drawText(textRenderer, "Loominary Catalogue", 4, 3, COL_TEXT, false);

        // Separator under header
        ctx.fill(0, 14, width, 15, COL_SEP);

        // Entry list
        int listHeight = height - BTN_BAR_H - 15;
        int visibleRows = listHeight / ENTRY_H;
        hoveredIndex = -1;

        for (int i = 0; i < visibleRows; i++) {
            int entryIdx = scrollOffset + i;
            if (entryIdx >= CatalogueState.entries.size()) break;
            CatalogueState.CatalogueEntry entry = CatalogueState.entries.get(entryIdx);

            int y = 15 + i * ENTRY_H;
            boolean isSelected = entryIdx == selectedIndex;
            boolean isHovered  = mouseX >= 0 && mouseX < LIST_W
                    && mouseY >= y && mouseY < y + ENTRY_H;
            if (isHovered) hoveredIndex = entryIdx;

            ctx.fill(0, y, LIST_W, y + ENTRY_H,
                    isSelected ? COL_SELECTED : (isHovered ? COL_HOVER : COL_PANEL_BG));

            // Thumbnail
            renderThumbnail(ctx, entry, ENTRY_PAD, y + ENTRY_PAD, THUMB_PX);

            // Title
            String title = entry.title != null ? entry.title : "Untitled";
            if (title.length() > 18) title = title.substring(0, 16) + "…";
            ctx.drawText(textRenderer, title,
                    ENTRY_PAD + THUMB_PX + 4, y + 6, COL_TEXT, false);

            // Grid dims + share indicator
            String meta = entry.gridCols + "×" + entry.gridRows;
            if (entry.sourcePeer != null && entry.tiles == null) meta += " ☁";
            if (entry.shareEnabled) meta += " ✓";
            ctx.drawText(textRenderer, meta,
                    ENTRY_PAD + THUMB_PX + 4, y + 17, COL_DIM, false);

            // Row separator
            ctx.fill(0, y + ENTRY_H - 1, LIST_W, y + ENTRY_H, COL_SEP);
        }

        // Bottom button bar background
        ctx.fill(0, height - BTN_BAR_H, width, height, 0xFF0D0D0D);
        ctx.fill(0, height - BTN_BAR_H, width, height - BTN_BAR_H + 1, COL_SEP);

        // Right detail panel
        if (selectedIndex >= 0 && selectedIndex < CatalogueState.entries.size()) {
            renderDetailPanel(ctx, CatalogueState.entries.get(selectedIndex));
        } else {
            ctx.drawText(textRenderer, "Select an entry",
                    LIST_W + 10, 30, COL_DIM, false);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderDetailPanel(DrawContext ctx, CatalogueState.CatalogueEntry entry) {
        int x = LIST_W + 8;
        int y = 18;
        int lineH = 12;

        ctx.drawText(textRenderer, "§f" + (entry.title != null ? entry.title : "Untitled"),
                x, y, COL_TEXT, false);
        y += lineH + 2;
        ctx.drawText(textRenderer, "Author: " + (entry.author != null ? entry.author : "—"),
                x, y, COL_DIM, false);
        y += lineH;
        ctx.drawText(textRenderer, "Date: "   + (entry.dateAdded != null ? entry.dateAdded : "—"),
                x, y, COL_DIM, false);
        y += lineH;
        ctx.drawText(textRenderer, "Grid: "   + entry.gridCols + "×" + entry.gridRows,
                x, y, COL_DIM, false);
        y += lineH;
        if (entry.frameCount > 1)
            ctx.drawText(textRenderer, "Frames: " + entry.frameCount, x, y, COL_DIM, false);
        y += lineH;
        if (entry.sourcePeer != null) {
            ctx.drawText(textRenderer, "From: " + entry.sourcePeer, x, y, COL_ORANGE, false);
            y += lineH;
        }
        if (entry.tiles == null) {
            ctx.drawText(textRenderer, "§e☁ Metadata only — download to build",
                    x, y, 0xFFEECC44, false);
        }

        // Large thumbnail
        int thumbX = LIST_W + 8, thumbY = height - BTN_BAR_H - 70;
        if (entry.thumbnailB64 != null) {
            renderThumbnail(ctx, entry, thumbX, thumbY, 60);
        }
    }

    private void renderThumbnail(DrawContext ctx, CatalogueState.CatalogueEntry entry,
            int x, int y, int displaySize) {
        if (entry.thumbnailB64 == null) {
            ctx.fill(x, y, x + displaySize, y + displaySize, 0xFF333333);
            return;
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(entry.thumbnailB64);
            long fs = Zstd.getFrameContentSize(compressed);
            if (fs <= 0 || fs > 1024) { ctx.fill(x, y, x + displaySize, y + displaySize, 0xFF333333); return; }
            byte[] thumb = Zstd.decompress(compressed, (int) fs);
            if (thumb.length < 100) { ctx.fill(x, y, x + displaySize, y + displaySize, 0xFF333333); return; }
            // thumb is 10×10 map color bytes; scale to displaySize×displaySize
            int scale = displaySize / 10;
            if (scale < 1) scale = 1;
            for (int ty = 0; ty < 10; ty++) {
                for (int tx = 0; tx < 10; tx++) {
                    int colorId = thumb[ty * 10 + tx] & 0xFF;
                    int rgb = colorId == 0 ? 0xFF333333 : (0xFF000000 | COLOR_LOOKUP[colorId]);
                    ctx.fill(x + tx * scale, y + ty * scale,
                             x + (tx + 1) * scale, y + (ty + 1) * scale, rgb);
                }
            }
        } catch (Exception e) {
            ctx.fill(x, y, x + displaySize, y + displaySize, 0xFF333333);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listHeight = height - BTN_BAR_H - 15;
        if (mouseX >= 0 && mouseX < LIST_W && mouseY >= 15 && mouseY < 15 + listHeight) {
            int row = (int) (mouseY - 15) / ENTRY_H;
            int idx = scrollOffset + row;
            if (idx < CatalogueState.entries.size()) {
                selectedIndex = idx;
                updateButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
            double verticalAmount) {
        if (mouseX < LIST_W) {
            scrollOffset -= (int) verticalAmount;
            scrollOffset = Math.max(0, Math.min(scrollOffset,
                    Math.max(0, CatalogueState.entries.size() - visibleRows())));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int visibleRows() {
        return (height - BTN_BAR_H - 15) / ENTRY_H;
    }

    // ── Actions ────────────────────────────────────────────────────────

    private void addCurrentBatch() {
        if (PayloadState.tiles.isEmpty()) {
            // No batch — show feedback
            if (client != null && client.player != null)
                client.player.sendMessage(Text.literal("§c[Loominary] No active batch to add."), false);
            return;
        }
        CatalogueState.CatalogueEntry entry = CatalogueState.fromCurrentBatch();
        CatalogueState.entries.add(0, entry);
        CatalogueState.save();
        selectedIndex = 0;
        scrollOffset  = 0;
        updateButtons();
        if (client != null && client.player != null)
            client.player.sendMessage(
                    Text.literal("§a[Loominary] Added \"" + entry.title + "\" to catalogue."), false);
    }

    private void loadToBatch() {
        if (selectedIndex < 0 || selectedIndex >= CatalogueState.entries.size()) return;
        CatalogueState.CatalogueEntry entry = CatalogueState.entries.get(selectedIndex);
        if (entry.tiles == null) return;
        CatalogueState.loadIntoBatch(entry);
        close();
        if (client != null && client.player != null)
            client.player.sendMessage(
                    Text.literal("§a[Loominary] Loaded \"" + entry.title + "\" into batch. Run /loominary export to build."), false);
    }

    private void deleteSelected() {
        if (selectedIndex < 0 || selectedIndex >= CatalogueState.entries.size()) return;
        CatalogueState.entries.remove(selectedIndex);
        CatalogueState.save();
        selectedIndex = Math.min(selectedIndex, CatalogueState.entries.size() - 1);
        updateButtons();
    }

    private void toggleShare() {
        if (selectedIndex < 0 || selectedIndex >= CatalogueState.entries.size()) return;
        CatalogueState.CatalogueEntry entry = CatalogueState.entries.get(selectedIndex);
        entry.shareEnabled = !entry.shareEnabled;
        entry.updatedAt = System.currentTimeMillis();
        CatalogueState.save();
        updateButtons();
    }

    @Override
    public boolean shouldPause() { return false; }
}
