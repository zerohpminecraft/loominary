package net.zerohpminecraft.command;

import com.github.luben.zstd.Zstd;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.zerohpminecraft.MapBannerDecoder;
import net.zerohpminecraft.PayloadState;
import net.zerohpminecraft.PngToMapColors;
import net.zerohpminecraft.SchematicExporter;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single Loominary command. All functionality is reachable as subcommands.
 *
 * /loominary — alias for status
 * /loominary import <file> [c] [r] [allshades]
 * — load image, optionally split into c×r grid
 * — add 'allshades' to use unobtainable shade 3 colors
 * /loominary import steal — append the framed map at crosshair as a tile
 * /loominary status — show batch overview & per-tile progress
 * /loominary seek <n> — set chunk index on active tile
 * /loominary tile <n> — switch to tile n
 * /loominary tile next — switch to next incomplete tile
 * /loominary preview — paint active tile onto crosshair map
 * /loominary revert — restore a previewed map to its original
 * /loominary palette — color stats for active tile
 * /loominary reduce [target] — reduce active tile to fit (default 255)
 * /loominary reduce undo — restore tile to pre-reduction state
 * /loominary export [name] — write a Litematica .litematic for active tile
 * /loominary clear [memory|disk] — clear state
 */
public class LoominaryCommand {

    private static final int CHUNK_SIZE = 48;
    private static final int MAX_CHUNKS = 255;
    private static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;

    // ── Caches for revert / reduce undo ────────────────────────────────

    private static final Map<Integer, byte[]> originalColors = new HashMap<>();
    private static final Map<Integer, List<String>> preReductionChunks = new HashMap<>();

    // ── Filename suggestions for tab-completion ────────────────────────

    private static final SuggestionProvider<FabricClientCommandSource> FILENAME_SUGGESTIONS = (ctx, builder) -> {
        Path dir = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("loominary_data");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                List<String> names = stream
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .toList();
                return CommandSource.suggestMatching(names, builder);
            } catch (IOException ignored) {
            }
        }
        return builder.buildFuture();
    };

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommandManager.literal("loominary")
                            .executes(ctx -> status(ctx.getSource()))

                            // ── import ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("import")
                                    .then(ClientCommandManager.literal("steal")
                                            .executes(ctx -> importSteal(ctx.getSource())))
                                    .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                            .suggests(FILENAME_SUGGESTIONS)
                                            .executes(ctx -> importFile(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, false))
                                            .then(ClientCommandManager.literal("allshades")
                                                    .executes(ctx -> importFile(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, true)))
                                            .then(ClientCommandManager
                                                    .argument("columns", IntegerArgumentType.integer(1, 16))
                                                    .then(ClientCommandManager
                                                            .argument("rows", IntegerArgumentType.integer(1, 16))
                                                            .executes(ctx -> importFile(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                    IntegerArgumentType.getInteger(ctx, "rows"), false))
                                                            .then(ClientCommandManager.literal("allshades")
                                                                    .executes(ctx -> importFile(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx,
                                                                                    "filename"),
                                                                            IntegerArgumentType.getInteger(ctx,
                                                                                    "columns"),
                                                                            IntegerArgumentType.getInteger(ctx, "rows"),
                                                                            true)))))))

                            // ── status ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("status")
                                    .executes(ctx -> status(ctx.getSource())))

                            // ── seek ───────────────────────────────────────────
                            .then(ClientCommandManager.literal("seek")
                                    .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(0))
                                            .executes(ctx -> seek(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "index")))))

                            // ── tile ───────────────────────────────────────────
                            .then(ClientCommandManager.literal("tile")
                                    .then(ClientCommandManager.literal("next")
                                            .executes(ctx -> tileNext(ctx.getSource())))
                                    .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(0))
                                            .executes(ctx -> tileSwitch(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "index")))))

                            // ── preview / revert ───────────────────────────────
                            .then(ClientCommandManager.literal("preview")
                                    .executes(ctx -> preview(ctx.getSource())))
                            .then(ClientCommandManager.literal("revert")
                                    .executes(ctx -> revert(ctx.getSource())))

                            // ── palette / reduce ───────────────────────────────
                            .then(ClientCommandManager.literal("palette")
                                    .executes(ctx -> palette(ctx.getSource())))
                            .then(ClientCommandManager.literal("reduce")
                                    .executes(ctx -> reduce(ctx.getSource(), 255))
                                    .then(ClientCommandManager.literal("undo")
                                            .executes(ctx -> reduceUndo(ctx.getSource())))
                                    .then(ClientCommandManager.argument("target", IntegerArgumentType.integer(1, 255))
                                            .executes(ctx -> reduce(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "target")))))

                            // ── export ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("export")
                                    .executes(ctx -> exportSchematic(ctx.getSource(), null))
                                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                                            .executes(ctx -> exportSchematic(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name")))))

                            // ── clear ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("clear")
                                    .executes(ctx -> clearAll(ctx.getSource()))
                                    .then(ClientCommandManager.literal("memory")
                                            .executes(ctx -> clearMemory(ctx.getSource())))
                                    .then(ClientCommandManager.literal("disk")
                                            .executes(ctx -> clearDisk(ctx.getSource())))));
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    private static String maskedPos(BlockPos pos) {
        return String.format("...%03d, ...%03d, ...%03d",
                Math.abs(pos.getX()) % 1000,
                Math.abs(pos.getY()) % 1000,
                Math.abs(pos.getZ()) % 1000);
    }

    /**
     * Resolve the framed map at the player's crosshair, or null with an error sent.
     */
    private static FramedMap resolveCrosshairMap(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            source.sendError(Text.literal("§cNot in a world."));
            return null;
        }

        if (!(client.crosshairTarget instanceof EntityHitResult entityHit)) {
            source.sendError(Text.literal(
                    "§cLook at an item frame containing a map, then run this command."));
            return null;
        }

        if (!(entityHit.getEntity() instanceof ItemFrameEntity frame)) {
            source.sendError(Text.literal(
                    "§cYou're looking at an entity, but it's not an item frame."));
            return null;
        }

        ItemStack stack = frame.getHeldItemStack();
        if (!(stack.getItem() instanceof FilledMapItem)) {
            source.sendError(Text.literal("§cThat item frame doesn't contain a map."));
            return null;
        }

        MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
        if (mapId == null) {
            source.sendError(Text.literal("§cThat map has no ID."));
            return null;
        }

        MapState mapState = FilledMapItem.getMapState(mapId, client.world);
        if (mapState == null) {
            source.sendError(Text.literal("§cCouldn't load map state."));
            return null;
        }

        return new FramedMap(client, frame, mapId, mapState);
    }

    private record FramedMap(MinecraftClient client,
            ItemFrameEntity frame,
            MapIdComponent mapId,
            MapState mapState) {
    }

    // ════════════════════════════════════════════════════════════════════
    // import <file> [cols] [rows] [allshades]
    // ════════════════════════════════════════════════════════════════════

    private static int importFile(FabricClientCommandSource source,
            String filename, int columns, int rows, boolean allShades) {
        if (filename.isEmpty()) {
            source.sendError(Text.literal("§cUsage: /loominary import <filename> [cols] [rows] [allshades]"));
            return 0;
        }

        try {
            Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
            Path filePath = gameDir.resolve("loominary_data").resolve(filename);

            if (!Files.exists(filePath)) {
                source.sendError(Text.literal("§cFile not found: loominary_data/" + filename));
                return 0;
            }

            byte[] fileBytes = Files.readAllBytes(filePath);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (img == null) {
                source.sendError(Text.literal("§cCouldn't decode image: " + filename));
                return 0;
            }

            int totalPixelsW = columns * MAP_SIZE;
            int totalPixelsH = rows * MAP_SIZE;

            BufferedImage scaled = new BufferedImage(totalPixelsW, totalPixelsH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, totalPixelsW, totalPixelsH, null);
            g.dispose();

            int totalTiles = columns * rows;
            int totalBannersNeeded = 0;
            int maxBannersPerTile = 0;

            PayloadState.tiles.clear();
            List<String> tileNotes = new ArrayList<>();
            for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                int col = tileIdx % columns;
                int row = tileIdx / columns;

                BufferedImage tileImg = scaled.getSubimage(
                        col * MAP_SIZE, row * MAP_SIZE, MAP_SIZE, MAP_SIZE);

                byte[] mapColors = PngToMapColors.convert(tileImg, !allShades);
                byte[] compressed = Zstd.compress(mapColors, Zstd.maxCompressionLevel());
                String base64 = Base64.getEncoder().encodeToString(compressed);
                int tileChunks = (base64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;

                String note = null;
                if (tileChunks > MAX_CHUNKS) {
                    PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                            mapColors, CHUNK_SIZE, MAX_CHUNKS);
                    compressed = fit.compressed;
                    base64 = Base64.getEncoder().encodeToString(compressed);
                    tileChunks = (base64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
                    note = String.format("reduced %d→%d colors",
                            fit.originalDistinctColors,
                            fit.originalDistinctColors - fit.colorsRemoved);
                }

                PayloadState.TileData tile = new PayloadState.TileData();
                for (int i = 0; i < tileChunks; i++) {
                    int start = i * CHUNK_SIZE;
                    int end = Math.min(start + CHUNK_SIZE, base64.length());
                    String chunk = base64.substring(start, end);
                    tile.chunks.add(String.format("%02x", i) + chunk);
                }
                tile.currentIndex = 0;
                PayloadState.tiles.add(tile);
                tileNotes.add(note);

                totalBannersNeeded += tileChunks;
                maxBannersPerTile = Math.max(maxBannersPerTile, tileChunks);
            }

            PayloadState.currentSourceFilename = filename;
            PayloadState.gridColumns = columns;
            PayloadState.gridRows = rows;
            PayloadState.activeTileIndex = 0;
            PayloadState.syncFromActiveTile();
            PayloadState.save();

            int totalBanners = 0, totalBundles = 0;
            for (ItemStack stack : source.getPlayer().getInventory().main) {
                if (stack.getItem().toString().contains("banner") && stack.getCustomName() == null) {
                    totalBanners += stack.getCount();
                }
                if (stack.getItem() == Items.BUNDLE)
                    totalBundles++;
            }

            source.sendFeedback(Text.literal(String.format(
                    "§aLoaded %s (%dx%d) as %dx%d grid (%d tile%s) §7[%s]",
                    filename, img.getWidth(), img.getHeight(),
                    columns, rows, totalTiles, totalTiles == 1 ? "" : "s",
                    allShades ? "all shades" : "legal palette")));

            for (int i = 0; i < PayloadState.tiles.size(); i++) {
                PayloadState.TileData tile = PayloadState.tiles.get(i);
                String note = tileNotes.get(i);
                String line = String.format("§7  %s: §f%d banners",
                        PayloadState.tileLabel(i), tile.chunks.size());
                if (note != null)
                    line += " §e(" + note + ")";
                source.sendFeedback(Text.literal(line));
            }

            source.sendFeedback(Text.literal(String.format(
                    "§aTotal: %d banners across %d tile%s (max %d per tile).",
                    totalBannersNeeded, totalTiles, totalTiles == 1 ? "" : "s", maxBannersPerTile)));

            if (totalBanners < maxBannersPerTile) {
                source.sendFeedback(Text.literal(String.format(
                        "§e⚠ Only %d unnamed banners — largest tile needs %d.",
                        totalBanners, maxBannersPerTile)));
            }
            int bundlesNeeded = (maxBannersPerTile + 15) / 16;
            if (totalBundles < bundlesNeeded) {
                source.sendFeedback(Text.literal(String.format(
                        "§e⚠ Only %d bundles — largest tile needs %d.",
                        totalBundles, bundlesNeeded)));
            }

            source.sendFeedback(Text.literal("§e§lActive: " + PayloadState.tileLabel(0)
                    + ". Try §f/loominary preview§e or head to an anvil."));
            return 1;

        } catch (IOException e) {
            source.sendError(Text.literal("§cError reading file: " + e.getMessage()));
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // import steal
    // ════════════════════════════════════════════════════════════════════

    private static int importSteal(FabricClientCommandSource source) {
        FramedMap fm = resolveCrosshairMap(source);
        if (fm == null)
            return 0;

        byte[] mapColors = fm.mapState.colors.clone();
        byte[] compressed = Zstd.compress(mapColors, Zstd.maxCompressionLevel());
        String base64 = Base64.getEncoder().encodeToString(compressed);
        int totalChunks = (base64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;

        String note = null;
        if (totalChunks > MAX_CHUNKS) {
            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                    mapColors, CHUNK_SIZE, MAX_CHUNKS);
            compressed = fit.compressed;
            base64 = Base64.getEncoder().encodeToString(compressed);
            totalChunks = (base64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            note = String.format("reduced %d→%d colors",
                    fit.originalDistinctColors,
                    fit.originalDistinctColors - fit.colorsRemoved);
        }

        // Append as a new tile
        if (!PayloadState.tiles.isEmpty()) {
            PayloadState.syncToActiveTile();
        }

        PayloadState.TileData tile = new PayloadState.TileData();
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, base64.length());
            String chunk = base64.substring(start, end);
            tile.chunks.add(String.format("%02x", i) + chunk);
        }
        tile.currentIndex = 0;
        PayloadState.tiles.add(tile);

        int tileIdx = PayloadState.tiles.size() - 1;
        PayloadState.currentSourceFilename = "stolen (x" + PayloadState.tiles.size() + ")";
        PayloadState.gridColumns = PayloadState.tiles.size();
        PayloadState.gridRows = 1;
        PayloadState.activeTileIndex = tileIdx;
        PayloadState.syncFromActiveTile();
        PayloadState.save();

        double ratio = 100.0 * compressed.length / mapColors.length;
        String msg = String.format(
                "§aStole map id=%d at %s → %s: %d B compressed (%.1f%%), %d banners.",
                fm.mapId.id(), maskedPos(fm.frame.getBlockPos()),
                PayloadState.tileLabel(tileIdx),
                compressed.length, ratio, totalChunks);
        if (note != null)
            msg += " §e(" + note + ")";
        source.sendFeedback(Text.literal(msg));
        source.sendFeedback(Text.literal(String.format(
                "§7%d tile%s total. Steal more or head to an anvil.",
                PayloadState.tiles.size(),
                PayloadState.tiles.size() == 1 ? "" : "s")));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // status
    // ════════════════════════════════════════════════════════════════════

    private static int status(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendFeedback(Text.literal(
                    "§7No active batch. Run §f/loominary import <file>§7 or "
                            + "§f/loominary import steal§7 to start."));
            return 1;
        }

        String fname = PayloadState.currentSourceFilename == null
                ? "<unknown>"
                : PayloadState.currentSourceFilename;

        source.sendFeedback(Text.literal("§6=== Loominary Status ==="));
        source.sendFeedback(Text.literal(String.format(
                "§7File: §f%s §7(%dx%d grid, %d tile%s)",
                fname, PayloadState.gridColumns, PayloadState.gridRows,
                PayloadState.totalTiles(),
                PayloadState.totalTiles() == 1 ? "" : "s")));

        int totalDone = 0, totalChunks = 0;
        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);

            int done, total;
            if (i == PayloadState.activeTileIndex) {
                done = PayloadState.activeChunkIndex;
                total = PayloadState.ACTIVE_CHUNKS.size();
            } else {
                done = tile.currentIndex;
                total = tile.chunks.size();
            }

            totalDone += done;
            totalChunks += total;

            String statusStr;
            if (done >= total)
                statusStr = "§a✓ done";
            else if (i == PayloadState.activeTileIndex)
                statusStr = String.format("§e► %d/%d §7(active)", done, total);
            else
                statusStr = String.format("§7  %d/%d", done, total);

            source.sendFeedback(Text.literal(String.format("  %s: %s",
                    PayloadState.tileLabel(i), statusStr)));
        }

        source.sendFeedback(Text.literal(String.format(
                "§7Overall: §f%d§7/§f%d §7banners", totalDone, totalChunks)));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // seek <n>
    // ════════════════════════════════════════════════════════════════════

    private static int seek(FabricClientCommandSource source, int index) {
        int total = PayloadState.ACTIVE_CHUNKS.size();
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (index > total) {
            source.sendError(Text.literal(String.format(
                    "§cIndex %d exceeds tile size %d.", index, total)));
            return 0;
        }

        int oldIndex = PayloadState.activeChunkIndex;
        PayloadState.activeChunkIndex = index;
        PayloadState.save();

        source.sendFeedback(Text.literal(String.format(
                "§a%s index: §e%d §7→ §a%d §7(of %d)",
                PayloadState.tileLabel(PayloadState.activeTileIndex),
                oldIndex, index, total)));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // tile <n> | tile next
    // ════════════════════════════════════════════════════════════════════

    private static int tileSwitch(FabricClientCommandSource source, int index) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (index < 0 || index >= PayloadState.tiles.size()) {
            source.sendError(Text.literal(String.format(
                    "§cTile %d doesn't exist. Valid range: 0–%d.",
                    index, PayloadState.tiles.size() - 1)));
            return 0;
        }

        PayloadState.switchTile(index);
        PayloadState.save();

        PayloadState.TileData tile = PayloadState.tiles.get(index);
        source.sendFeedback(Text.literal(String.format(
                "§aSwitched to %s §7(%d/%d banners done)",
                PayloadState.tileLabel(index),
                tile.currentIndex, tile.chunks.size())));
        return 1;
    }

    private static int tileNext(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }

        PayloadState.syncToActiveTile();
        int next = PayloadState.findNextIncompleteTile();
        if (next == -1) {
            source.sendFeedback(Text.literal(
                    "§aAll tiles complete! Place your maps in item frames to view the result."));
            return 1;
        }

        PayloadState.switchTile(next);
        PayloadState.save();

        PayloadState.TileData tile = PayloadState.tiles.get(next);
        source.sendFeedback(Text.literal(String.format(
                "§aSwitched to %s §7(%d/%d banners done)",
                PayloadState.tileLabel(next),
                tile.currentIndex, tile.chunks.size())));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // preview / revert
    // ════════════════════════════════════════════════════════════════════

    private static int preview(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal(
                    "§cNo active batch. Run §f/loominary import§c first."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }

        FramedMap fm = resolveCrosshairMap(source);
        if (fm == null)
            return 0;

        try {
            if (!originalColors.containsKey(fm.mapId.id())) {
                originalColors.put(fm.mapId.id(), fm.mapState.colors.clone());
            }

            byte[] mapColors = MapBannerDecoder.reassemblePayload(
                    new ArrayList<>(PayloadState.ACTIVE_CHUNKS));
            MapBannerDecoder.paintMap(fm.client, fm.mapId, fm.mapState, mapColors);

            source.sendFeedback(Text.literal(String.format(
                    "§aPainted preview of %s onto map id=%d at %s",
                    PayloadState.tileLabel(PayloadState.activeTileIndex),
                    fm.mapId.id(),
                    maskedPos(fm.frame.getBlockPos()))));
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§cFailed to render preview: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static int revert(FabricClientCommandSource source) {
        FramedMap fm = resolveCrosshairMap(source);
        if (fm == null)
            return 0;

        byte[] saved = originalColors.remove(fm.mapId.id());
        if (saved == null) {
            source.sendError(Text.literal(
                    "§cNo saved original for map id=" + fm.mapId.id()
                            + ". Only previewed maps can be reverted."));
            return 0;
        }

        MapBannerDecoder.paintMap(fm.client, fm.mapId, fm.mapState, saved);
        source.sendFeedback(Text.literal(String.format(
                "§aReverted map id=%d at %s to its original state.",
                fm.mapId.id(), maskedPos(fm.frame.getBlockPos()))));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // palette / reduce / reduce undo
    // ════════════════════════════════════════════════════════════════════

    private static int palette(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }

        try {
            byte[] mapColors = MapBannerDecoder.reassemblePayload(
                    new ArrayList<>(PayloadState.ACTIVE_CHUNKS));

            int[] freq = new int[256];
            for (byte b : mapColors)
                freq[b & 0xFF]++;

            int distinctColors = PngToMapColors.countDistinct(mapColors);
            int bannerCount = PayloadState.ACTIVE_CHUNKS.size();

            int[] colorRgb = PngToMapColors.buildColorLookup();
            List<int[]> colorsByFreq = new ArrayList<>();
            for (int c = 1; c < 256; c++) {
                if (freq[c] > 0)
                    colorsByFreq.add(new int[] { c, freq[c] });
            }
            colorsByFreq.sort((a, b) -> Integer.compare(a[1], b[1]));

            source.sendFeedback(Text.literal(String.format(
                    "§6=== Palette: %s ===",
                    PayloadState.tileLabel(PayloadState.activeTileIndex))));
            source.sendFeedback(Text.literal(String.format(
                    "§7Distinct colors: §f%d", distinctColors)));
            source.sendFeedback(Text.literal(String.format(
                    "§7Banners needed:  §f%d §7/ §f%d%s",
                    bannerCount, MAX_CHUNKS,
                    bannerCount > MAX_CHUNKS
                            ? " §c(OVER BUDGET by " + (bannerCount - MAX_CHUNKS) + ")"
                            : " §a✓")));

            if (preReductionChunks.containsKey(PayloadState.activeTileIndex)) {
                source.sendFeedback(Text.literal("§7Reduction:       §eactive §7(undo available)"));
            }

            int showCount = Math.min(10, colorsByFreq.size());
            source.sendFeedback(Text.literal(String.format(
                    "§7Rarest %d colors (merge candidates):", showCount)));
            for (int i = 0; i < showCount; i++) {
                int[] entry = colorsByFreq.get(i);
                int rgb = colorRgb[entry[0]];
                String hex = String.format("#%06X", rgb & 0xFFFFFF);
                double pct = 100.0 * entry[1] / MAP_BYTES;
                source.sendFeedback(Text.literal(String.format(
                        "§7  %s §8— §f%d px §7(%.1f%%)", hex, entry[1], pct)));
            }
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§cFailed to analyze palette: " + e.getMessage()));
            return 0;
        }
    }

    private static int reduce(FabricClientCommandSource source, int target) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }

        int currentBanners = PayloadState.ACTIVE_CHUNKS.size();
        if (currentBanners <= target) {
            source.sendFeedback(Text.literal(String.format(
                    "§aTile already fits in %d banners (currently %d). No reduction needed.",
                    target, currentBanners)));
            return 1;
        }

        try {
            int tileIdx = PayloadState.activeTileIndex;
            preReductionChunks.put(tileIdx, new ArrayList<>(PayloadState.ACTIVE_CHUNKS));

            byte[] mapColors = MapBannerDecoder.reassemblePayload(
                    new ArrayList<>(PayloadState.ACTIVE_CHUNKS));
            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                    mapColors, CHUNK_SIZE, target);

            String base64 = Base64.getEncoder().encodeToString(fit.compressed);
            int newChunks = (base64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;

            PayloadState.ACTIVE_CHUNKS.clear();
            for (int i = 0; i < newChunks; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, base64.length());
                String chunk = base64.substring(start, end);
                PayloadState.ACTIVE_CHUNKS.add(String.format("%02x", i) + chunk);
            }
            PayloadState.activeChunkIndex = 0;
            PayloadState.save();

            double pctAffected = 100.0 * fit.pixelsAffected / MAP_BYTES;
            int remainingColors = fit.originalDistinctColors - fit.colorsRemoved;

            source.sendFeedback(Text.literal(String.format(
                    "§6=== Reduced %s ===", PayloadState.tileLabel(tileIdx))));
            source.sendFeedback(Text.literal(String.format(
                    "§7Banners: §e%d §7→ §a%d", currentBanners, newChunks)));
            source.sendFeedback(Text.literal(String.format(
                    "§7Colors:  §e%d §7→ §a%d §7(%d removed)",
                    fit.originalDistinctColors, remainingColors, fit.colorsRemoved)));
            source.sendFeedback(Text.literal(String.format(
                    "§7Pixels affected: §f%d §7(%.1f%% of tile)",
                    fit.pixelsAffected, pctAffected)));
            source.sendFeedback(Text.literal(
                    "§7Use §f/loominary preview§7 to inspect. "
                            + "§f/loominary reduce undo§7 to revert."));
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§cReduction failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static int reduceUndo(FabricClientCommandSource source) {
        int tileIdx = PayloadState.activeTileIndex;
        List<String> saved = preReductionChunks.remove(tileIdx);

        if (saved == null) {
            source.sendError(Text.literal(String.format(
                    "§cNo reduction to undo for %s.",
                    PayloadState.tileLabel(tileIdx))));
            return 0;
        }

        int reducedBanners = PayloadState.ACTIVE_CHUNKS.size();

        PayloadState.ACTIVE_CHUNKS.clear();
        PayloadState.ACTIVE_CHUNKS.addAll(saved);
        PayloadState.activeChunkIndex = 0;
        PayloadState.save();

        source.sendFeedback(Text.literal(String.format(
                "§aUndid reduction on %s: §e%d §7→ §f%d §7banners restored.",
                PayloadState.tileLabel(tileIdx),
                reducedBanners, saved.size())));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // export [name]
    // ════════════════════════════════════════════════════════════════════

    private static int exportSchematic(FabricClientCommandSource source, String name) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }

        // Auto-generate name from source filename if none given
        if (name == null || name.isEmpty()) {
            String src = PayloadState.currentSourceFilename;
            if (src == null)
                src = "loominary";
            // Strip extension and sanitize
            int dot = src.lastIndexOf('.');
            if (dot > 0)
                src = src.substring(0, dot);
            src = src.replaceAll("[^A-Za-z0-9_-]", "_");
            name = src + "_tile" + PayloadState.activeTileIndex;
        }

        try {
            int count = PayloadState.ACTIVE_CHUNKS.size();
            String description = String.format(
                    "Loominary tile %d (col %d, row %d) — %d named banners. "
                            + "Place white banners following the ghost layout, "
                            + "matching each banner's custom name.",
                    PayloadState.activeTileIndex,
                    PayloadState.tileCol(PayloadState.activeTileIndex),
                    PayloadState.tileRow(PayloadState.activeTileIndex),
                    count);

            Path output = SchematicExporter.exportTile(
                    new ArrayList<>(PayloadState.ACTIVE_CHUNKS), name, description);

            int gridH = (count + 15) / 16;
            source.sendFeedback(Text.literal(String.format(
                    "§aExported §f%s §a(%d banners, 16×%d grid) to:",
                    name + ".litematic", count, gridH)));
            source.sendFeedback(Text.literal("§7  " + output.toString()));
            source.sendFeedback(Text.literal(
                    "§7Copy to your Litematica schematics folder to load it. "
                            + "Each ghost banner's custom name shows which renamed banner to place there."));
            return 1;

        } catch (IOException e) {
            source.sendError(Text.literal("§cExport failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // clear [memory|disk]
    // ════════════════════════════════════════════════════════════════════

    private static int clearAll(FabricClientCommandSource source) {
        PayloadState.clear();
        originalColors.clear();
        preReductionChunks.clear();
        source.sendFeedback(Text.literal("§aCleared all state."));
        return 1;
    }

    private static int clearMemory(FabricClientCommandSource source) {
        PayloadState.clearMemory();
        originalColors.clear();
        preReductionChunks.clear();
        source.sendFeedback(Text.literal("§aCleared in-memory state. §7Disk file untouched."));
        return 1;
    }

    private static int clearDisk(FabricClientCommandSource source) {
        PayloadState.clearDisk();
        source.sendFeedback(Text.literal("§aDeleted state file. §7In-memory state untouched."));
        return 1;
    }
}