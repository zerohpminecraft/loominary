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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.zerohpminecraft.BannerAutoClickHandler;
import net.zerohpminecraft.MapBannerDecoder;
import net.zerohpminecraft.PayloadManifest;
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
import java.util.ArrayDeque;
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
 * /loominary click — toggle auto-right-click of banners while holding map
 * /loominary click stop — stop auto-clicking
 * /loominary export [name] — write a Litematica .litematic for active tile
 * /loominary clear [memory|disk] — clear state
 */
public class LoominaryCommand {

    private static final int CHUNK_SIZE = 48;
    private static final int MAX_CHUNKS = 255;
    private static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;

    // ── Encoding helpers ───────────────────────────────────────────────

    /**
     * Prepends the given manifest bytes to mapColors, compresses the combined
     * payload with zstd, base64-encodes it, and splits it into indexed chunks
     * ready to be stored as banner names.
     */
    private static List<String> buildChunks(byte[] manifestBytes, byte[] mapColors) {
        byte[] combined = new byte[manifestBytes.length + mapColors.length];
        System.arraycopy(manifestBytes, 0, combined, 0, manifestBytes.length);
        System.arraycopy(mapColors, 0, combined, manifestBytes.length, mapColors.length);

        byte[] compressed = Zstd.compress(combined, Zstd.maxCompressionLevel());
        String base64 = Base64.getEncoder().encodeToString(compressed);
        int total = (base64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;

        List<String> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, base64.length());
            chunks.add(String.format("%02x", i) + base64.substring(start, end));
        }
        return chunks;
    }

    // ── Caches for revert / reduce undo ────────────────────────────────

    private static final Map<Integer, byte[]> originalColors = new HashMap<>();
    private static final Map<Integer, List<String>> preReductionChunks = new HashMap<>();

    /** Used by BannerAutoClickHandler to decide whether to repaint a blanked map. */
    public static boolean hasPreviewFor(int mapId) {
        return originalColors.containsKey(mapId);
    }

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

                            // ── click ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("click")
                                    .executes(ctx -> clickToggle(ctx.getSource()))
                                    .then(ClientCommandManager.literal("stop")
                                            .executes(ctx -> clickStop(ctx.getSource()))))

                            // ── title ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("title")
                                    .executes(ctx -> titleClear(ctx.getSource()))
                                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                            .executes(ctx -> titleSet(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "text")))))

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

    // col/row coords are 0-based, (0,0) = top-left of the discovered wall
    private record GridCell(ItemFrameEntity frame,
            MapIdComponent mapId,
            MapState mapState,
            int col, int row) {
    }

    /**
     * Flood-fills from startFrame along the wall plane to discover all adjacent
     * item frames that hold filled maps and face the same direction.
     *
     * Returns cells with (col, row) normalised so (0,0) is the top-left corner.
     * Returns null if startFrame is on a floor or ceiling (UP/DOWN facing).
     *
     * Note: ItemFrameEntity.getBlockPos() returns the air block the frame sits
     * in (same as the wall block it is attached to), so adjacent frames on the
     * same wall differ by exactly 1 in the right/up axes.
     */
    private static List<GridCell> discoverMapGrid(MinecraftClient client,
            ItemFrameEntity startFrame) {
        Direction facing = startFrame.getHorizontalFacing();
        if (facing.getAxis() == Direction.Axis.Y) return null;

        Direction right = wallRightAxis(facing);
        int rx = right.getOffsetX(), rz = right.getOffsetZ();

        // Index every same-facing filled-map frame within reach by block position.
        Vec3d center = startFrame.getPos();
        Box box = new Box(center.x - 24, center.y - 24, center.z - 24,
                          center.x + 24, center.y + 24, center.z + 24);
        Map<BlockPos, ItemFrameEntity> byPos = new HashMap<>();
        for (ItemFrameEntity f : client.world.getEntitiesByClass(
                ItemFrameEntity.class, box, e -> true)) {
            if (f.getHorizontalFacing() == facing
                    && f.getHeldItemStack().getItem() instanceof FilledMapItem) {
                byPos.put(f.getBlockPos(), f);
            }
        }

        // BFS flood-fill. col increases rightward, row increases downward.
        Map<BlockPos, int[]> coords = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = startFrame.getBlockPos();
        coords.put(start, new int[]{0, 0});
        queue.add(start);

        int[][] deltas = {
            { rx, 0, rz,  1, 0},  // right  → col+1
            {-rx, 0,-rz, -1, 0},  // left   → col-1
            {0,   1, 0,   0,-1},  // up     → row-1
            {0,  -1, 0,   0, 1}   // down   → row+1
        };
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            int[] cc = coords.get(cur);
            for (int[] d : deltas) {
                BlockPos nb = cur.add(d[0], d[1], d[2]);
                if (coords.containsKey(nb) || !byPos.containsKey(nb)) continue;
                coords.put(nb, new int[]{cc[0] + d[3], cc[1] + d[4]});
                queue.add(nb);
            }
        }

        // Normalise so min col = 0, min row = 0 (top-left = 0,0).
        int minCol = Integer.MAX_VALUE, minRow = Integer.MAX_VALUE;
        for (int[] c : coords.values()) {
            if (c[0] < minCol) minCol = c[0];
            if (c[1] < minRow) minRow = c[1];
        }

        List<GridCell> cells = new ArrayList<>();
        for (Map.Entry<BlockPos, int[]> e : coords.entrySet()) {
            ItemFrameEntity f = byPos.get(e.getKey());
            if (f == null) continue;
            MapIdComponent mid = f.getHeldItemStack().get(DataComponentTypes.MAP_ID);
            if (mid == null) continue;
            MapState ms = FilledMapItem.getMapState(mid, client.world);
            if (ms == null) continue;
            cells.add(new GridCell(f, mid, ms,
                    e.getValue()[0] - minCol, e.getValue()[1] - minRow));
        }
        return cells;
    }

    private static Direction wallRightAxis(Direction facing) {
        return switch (facing) {
            case SOUTH -> Direction.EAST;
            case NORTH -> Direction.WEST;
            case EAST  -> Direction.NORTH;
            case WEST  -> Direction.SOUTH;
            default    -> Direction.EAST;
        };
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

            String playerName = source.getPlayer().getGameProfile().getName();
            int tileFlags = allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

            PayloadState.tiles.clear();
            List<String> tileNotes = new ArrayList<>();
            for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                int col = tileIdx % columns;
                int row = tileIdx / columns;

                BufferedImage tileImg = scaled.getSubimage(
                        col * MAP_SIZE, row * MAP_SIZE, MAP_SIZE, MAP_SIZE);

                byte[] mapColors = PngToMapColors.convert(tileImg, !allShades);
                byte[] manifestForSizeCheck = PayloadManifest.toBytes(
                        tileFlags, columns, rows, col, row, 0L, playerName, PayloadState.currentTitle);
                String note = null;
                if (buildChunks(manifestForSizeCheck, mapColors).size() > MAX_CHUNKS) {
                    PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                            mapColors, manifestForSizeCheck, CHUNK_SIZE, MAX_CHUNKS);
                    note = String.format("reduced %d→%d colors",
                            fit.originalDistinctColors,
                            fit.originalDistinctColors - fit.colorsRemoved);
                }
                byte[] manifestBytes = PayloadManifest.toBytes(
                        tileFlags, columns, rows, col, row,
                        PayloadManifest.crc32(mapColors), playerName, PayloadState.currentTitle);
                List<String> chunks = buildChunks(manifestBytes, mapColors);
                int tileChunks = chunks.size();

                PayloadState.TileData tile = new PayloadState.TileData();
                tile.chunks.addAll(chunks);
                tile.currentIndex = 0;
                PayloadState.tiles.add(tile);
                tileNotes.add(note);

                totalBannersNeeded += tileChunks;
                maxBannersPerTile = Math.max(maxBannersPerTile, tileChunks);
            }

            PayloadState.currentSourceFilename = filename;
            PayloadState.gridColumns = columns;
            PayloadState.gridRows = rows;
            PayloadState.allShades = allShades;
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
        String playerName = source.getPlayer().getGameProfile().getName();
        // Stolen tiles are standalone — use 1×1 grid, allShades=false (unknown origin)
        byte[] manifestForSizeCheck = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, 0L, playerName, PayloadState.currentTitle);
        String note = null;
        if (buildChunks(manifestForSizeCheck, mapColors).size() > MAX_CHUNKS) {
            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                    mapColors, manifestForSizeCheck, CHUNK_SIZE, MAX_CHUNKS);
            note = String.format("reduced %d→%d colors",
                    fit.originalDistinctColors,
                    fit.originalDistinctColors - fit.colorsRemoved);
        }
        byte[] manifestBytes = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, PayloadManifest.crc32(mapColors), playerName,
                PayloadState.currentTitle);
        List<String> chunks = buildChunks(manifestBytes, mapColors);

        // Append as a new tile
        if (!PayloadState.tiles.isEmpty()) {
            PayloadState.syncToActiveTile();
        }

        PayloadState.TileData tile = new PayloadState.TileData();
        tile.chunks.addAll(chunks);
        tile.currentIndex = 0;
        PayloadState.tiles.add(tile);

        int tileIdx = PayloadState.tiles.size() - 1;
        PayloadState.currentSourceFilename = "stolen (x" + PayloadState.tiles.size() + ")";
        PayloadState.gridColumns = PayloadState.tiles.size();
        PayloadState.gridRows = 1;
        PayloadState.activeTileIndex = tileIdx;
        PayloadState.syncFromActiveTile();
        PayloadState.save();

        String msg = String.format(
                "§aStole map id=%d at %s → %s: %d banners.",
                fm.mapId.id(), maskedPos(fm.frame.getBlockPos()),
                PayloadState.tileLabel(tileIdx),
                chunks.size());
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
        if (PayloadState.currentTitle != null) {
            source.sendFeedback(Text.literal("§7Title: §f" + PayloadState.currentTitle));
        }

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

        List<GridCell> cells = discoverMapGrid(fm.client, fm.frame);
        if (cells == null) {
            source.sendError(Text.literal(
                    "§cThat map is in a floor or ceiling frame — wall frames only."));
            return 0;
        }
        if (cells.isEmpty()) {
            source.sendError(Text.literal("§cCouldn't read map ID from that frame."));
            return 0;
        }

        int expectedCols = PayloadState.gridColumns;
        int expectedRows = PayloadState.gridRows;

        try {
            // Flush active-tile state so every tiles[i].chunks is current.
            PayloadState.syncToActiveTile();

            int painted = 0;
            int outOfBounds = 0;
            boolean[] tileWasPainted = new boolean[PayloadState.totalTiles()];

            for (GridCell cell : cells) {
                // Skip frames that fall outside the expected grid dimensions.
                if (cell.col() >= expectedCols || cell.row() >= expectedRows) {
                    outOfBounds++;
                    continue;
                }
                int tileIdx = cell.row() * expectedCols + cell.col();
                if (tileIdx >= PayloadState.tiles.size()) continue;
                PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
                if (tile.chunks.isEmpty()) continue;

                byte[] mapColors = MapBannerDecoder.reassemblePayload(
                        new ArrayList<>(tile.chunks));

                if (!originalColors.containsKey(cell.mapId().id())) {
                    originalColors.put(cell.mapId().id(), cell.mapState().colors.clone());
                }
                MapBannerDecoder.paintMap(fm.client, cell.mapId(), cell.mapState(), mapColors);
                tileWasPainted[tileIdx] = true;
                painted++;
            }

            // Collect tile indices that had no corresponding frame.
            List<Integer> missingTiles = new ArrayList<>();
            for (int i = 0; i < tileWasPainted.length; i++) {
                if (!tileWasPainted[i]) missingTiles.add(i);
            }

            if (expectedCols == 1 && expectedRows == 1) {
                source.sendFeedback(Text.literal(String.format(
                        "§aPainted preview of %s onto map id=%d at %s",
                        PayloadState.tileLabel(PayloadState.activeTileIndex),
                        fm.mapId.id(),
                        maskedPos(fm.frame.getBlockPos()))));
            } else {
                source.sendFeedback(Text.literal(String.format(
                        "§aPainted %d/%d tiles onto %d×%d grid.",
                        painted, PayloadState.totalTiles(), expectedCols, expectedRows)));
                if (!missingTiles.isEmpty()) {
                    StringBuilder sb = new StringBuilder("§eNo frame found for: ");
                    for (int i = 0; i < missingTiles.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(PayloadState.tileLabel(missingTiles.get(i)));
                    }
                    source.sendFeedback(Text.literal(sb.toString()));
                }
                if (outOfBounds > 0) {
                    source.sendFeedback(Text.literal(String.format(
                            "§e%d frame%s outside the expected %d×%d area (ignored).",
                            outOfBounds, outOfBounds == 1 ? "" : "s",
                            expectedCols, expectedRows)));
                }
                if (painted > 0) {
                    source.sendFeedback(Text.literal(
                            "§7Look at any painted map and run §f/loominary revert§7 to restore it."));
                }
            }
            return painted > 0 ? 1 : 0;

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

            // Build manifest for size-check (CRC unknown until after reduction, use 0)
            String playerName = source.getPlayer().getGameProfile().getName();
            int flags = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
            byte[] manifestForSizeCheck = PayloadManifest.toBytes(
                    flags,
                    PayloadState.gridColumns, PayloadState.gridRows,
                    PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                    0L, playerName, PayloadState.currentTitle);

            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                    mapColors, manifestForSizeCheck, CHUNK_SIZE, target);

            // Re-encode with manifest using real CRC of the reduced colors
            byte[] manifestBytes = PayloadManifest.toBytes(
                    flags,
                    PayloadState.gridColumns, PayloadState.gridRows,
                    PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                    PayloadManifest.crc32(fit.mapColors), playerName, PayloadState.currentTitle);
            List<String> newChunkList = buildChunks(manifestBytes, fit.mapColors);
            int newChunks = newChunkList.size();

            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(newChunkList);
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
    // click [stop]
    // ════════════════════════════════════════════════════════════════════

    private static int clickToggle(FabricClientCommandSource source) {
        if (BannerAutoClickHandler.isActive()) {
            BannerAutoClickHandler.stop();
            source.sendFeedback(Text.literal("§aAuto-click stopped."));
            return 1;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal(
                    "§cNo active batch. Run §f/loominary import§c first."));
            return 0;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        boolean started = BannerAutoClickHandler.start(client);
        if (!started) return 0;
        source.sendFeedback(Text.literal(
                "§aAuto-click started. Hold your map and walk near the banners."));
        source.sendFeedback(Text.literal(
                "§7Run §f/loominary click stop§7 to cancel. Active tile: "
                        + PayloadState.tileLabel(PayloadState.activeTileIndex)
                        + " (§f" + PayloadState.ACTIVE_CHUNKS.size() + "§7 banners)"));
        return 1;
    }

    private static int clickStop(FabricClientCommandSource source) {
        if (!BannerAutoClickHandler.isActive()) {
            source.sendFeedback(Text.literal("§7Auto-click is not running."));
            return 1;
        }
        BannerAutoClickHandler.stop();
        source.sendFeedback(Text.literal("§aAuto-click stopped."));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // title [<text>]
    // ════════════════════════════════════════════════════════════════════

    private static int titleSet(FabricClientCommandSource source, String text) {
        text = text.trim();
        if (text.isEmpty()) {
            return titleClear(source);
        }
        byte[] encoded = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length > 64) {
            source.sendFeedback(Text.literal(
                    "§e⚠ Title exceeds 64 UTF-8 bytes and will be truncated in the manifest."));
        }
        PayloadState.currentTitle = text;
        PayloadState.save();
        source.sendFeedback(Text.literal("§aTitle set: §f" + text
                + " §7(applies to the next encode, not existing tiles)"));
        return 1;
    }

    private static int titleClear(FabricClientCommandSource source) {
        PayloadState.currentTitle = null;
        PayloadState.save();
        source.sendFeedback(Text.literal("§aTitle cleared."));
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
        BannerAutoClickHandler.stop();
        BannerAutoClickHandler.clearMarkers();
        source.sendFeedback(Text.literal("§aCleared all state."));
        return 1;
    }

    private static int clearMemory(FabricClientCommandSource source) {
        PayloadState.clearMemory();
        originalColors.clear();
        preReductionChunks.clear();
        BannerAutoClickHandler.clearMarkers();
        source.sendFeedback(Text.literal("§aCleared in-memory state. §7Disk file untouched."));
        return 1;
    }

    private static int clearDisk(FabricClientCommandSource source) {
        PayloadState.clearDisk();
        source.sendFeedback(Text.literal("§aDeleted state file. §7In-memory state untouched."));
        return 1;
    }
}