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
import net.zerohpminecraft.AnvilAutoFillHandler;
import net.zerohpminecraft.BannerAutoClickHandler;
import net.zerohpminecraft.CarpetChannel;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
 * /loominary tile prev — switch to previous tile (by index, wrapping)
 * /loominary tile pos <col> <row> — switch to tile at grid position
 * /loominary preview — paint active tile onto crosshair map
 * /loominary revert — restore a previewed map to its original
 * /loominary edit — open the map editor for the active tile
 * /loominary dither [all] [colors <n>] — re-encode from source with Floyd-Steinberg dithering
 * /loominary palette — color stats + rarity histogram for active tile
 * /loominary reduce [all] [<n>] — reduce tile(s) to n banners (default 255)
 * /loominary reduce [all] colors <n> — reduce tile(s) to n distinct colors
 * /loominary reduce undo — restore active tile to pre-reduction state
 * /loominary click — toggle auto-right-click of banners while holding map
 * /loominary click stop — stop auto-clicking
 * /loominary export [name] — write a Litematica .litematic for active tile
 * /loominary clear [memory|disk] — clear state
 */
public class LoominaryCommand {

    private static final int CHUNK_SIZE = 48;
    private static final int MAX_CHUNKS = 63; // hard limit observed on 2b2t
    // Carpet budget in "chunks" units so reduceToFit can use the same path:
    // MAX_TOTAL_BYTES compressed → ceil(MAX_TOTAL_BYTES * 4/3 / CHUNK_SIZE) chunks
    private static final int MAX_CHUNKS_CARPET =
            (int) Math.ceil(CarpetChannel.MAX_TOTAL_BYTES * 4.0 / 3.0 / CHUNK_SIZE); // ≈ 291
    private static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;

    // ── Encoding helpers ───────────────────────────────────────────────

    /**
     * Prepends the given manifest bytes to mapColors, compresses the combined
     * payload with zstd, base64-encodes it, and splits it into indexed chunks
     * ready to be stored as banner names (legacy banner encoding).
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

    /**
     * Result of carpet-hybrid encoding. Contains everything needed to populate
     * a {@code TileData}, write the schematic, and report stats to the player.
     */
    record CarpetEncoding(
            byte[] compressed,       // full zstd-compressed payload
            int carpetBytes,         // bytes going into the carpet channel (≤8192)
            byte[] nibbles,          // 16384 nibble array for schematic generation
            String lcBannerName,     // "LC<NNNN>[<overflow-b64>]"
            List<String> hexChunks   // hex-indexed overflow banners "00".."3D"
    ) {
        /** All banner names in order: LC first, then hex overflow. */
        List<String> allChunks() {
            List<String> all = new ArrayList<>(1 + hexChunks.size());
            all.add(lcBannerName);
            all.addAll(hexChunks);
            return all;
        }
    }

    /**
     * Builds a carpet-hybrid encoding for the given manifest + map-color payload.
     *
     * @throws IllegalStateException if the compressed payload exceeds the maximum
     *         carpet+overflow capacity ({@link CarpetChannel#MAX_TOTAL_BYTES} bytes)
     */
    private static CarpetEncoding buildCarpetEncoding(byte[] manifestBytes, byte[] mapColors) {
        byte[] combined = new byte[manifestBytes.length + mapColors.length];
        System.arraycopy(manifestBytes, 0, combined, 0, manifestBytes.length);
        System.arraycopy(mapColors, 0, combined, manifestBytes.length, mapColors.length);

        byte[] compressed = Zstd.compress(combined, Zstd.maxCompressionLevel());
        int total = compressed.length;

        int carpetBytes = Math.min(total, CarpetChannel.MAX_CARPET_BYTES);
        byte[] nibbles  = CarpetChannel.encodeNibbles(compressed, carpetBytes);

        // Build overflow base64 string (may be empty)
        String overflowB64 = "";
        if (total > carpetBytes) {
            byte[] overflow = new byte[total - carpetBytes];
            System.arraycopy(compressed, carpetBytes, overflow, 0, overflow.length);
            overflowB64 = Base64.getEncoder().encodeToString(overflow);
        }

        // LC banner: "LC" + 4-hex total + up to LC_PAYLOAD_CHARS b64 chars
        String lcPayload = overflowB64.isEmpty() ? ""
                : overflowB64.substring(0, Math.min(CarpetChannel.LC_PAYLOAD_CHARS, overflowB64.length()));
        String lcName = String.format("LC%04X", total) + lcPayload;

        // Remaining overflow → hex-indexed banners 00..3D
        List<String> hexChunks = new ArrayList<>();
        if (overflowB64.length() > CarpetChannel.LC_PAYLOAD_CHARS) {
            String rest = overflowB64.substring(CarpetChannel.LC_PAYLOAD_CHARS);
            int idx = 0;
            for (int pos = 0; pos < rest.length(); pos += CHUNK_SIZE) {
                hexChunks.add(String.format("%02x", idx++)
                        + rest.substring(pos, Math.min(pos + CHUNK_SIZE, rest.length())));
            }
        }

        return new CarpetEncoding(compressed, carpetBytes, nibbles, lcName, hexChunks);
    }

    /** Decodes map-color bytes for the active tile (carpet or banner). */
    private static byte[] resolveMapColors() {
        return resolveMapColorsForTile(PayloadState.tiles.get(PayloadState.activeTileIndex),
                PayloadState.ACTIVE_CHUNKS);
    }

    /**
     * Saves {@code mapColors} back into the active tile using carpet or banner encoding,
     * updating {@code ACTIVE_CHUNKS} and (for carpet) {@code carpetCompressedB64}.
     * Returns the new chunk count.
     */
    private static int saveActiveTile(byte[] manifestBytes, byte[] mapColors) {
        PayloadState.TileData tile = PayloadState.tiles.get(PayloadState.activeTileIndex);
        if (tile.carpetEncoded) {
            CarpetEncoding enc = buildCarpetEncoding(manifestBytes, mapColors);
            tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
            List<String> all = enc.allChunks();
            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(all);
            PayloadState.activeChunkIndex = 0;
            return all.size();
        } else {
            List<String> chunks = buildChunks(manifestBytes, mapColors);
            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(chunks);
            PayloadState.activeChunkIndex = 0;
            return chunks.size();
        }
    }

    /**
     * Saves {@code mapColors} into any tile. Also updates {@code ACTIVE_CHUNKS} when
     * {@code tileIdx == activeTileIndex}.
     */
    private static int saveTileData(int tileIdx, byte[] manifestBytes, byte[] mapColors) {
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        List<String> all;
        if (tile.carpetEncoded) {
            CarpetEncoding enc = buildCarpetEncoding(manifestBytes, mapColors);
            tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
            all = enc.allChunks();
        } else {
            all = buildChunks(manifestBytes, mapColors);
        }
        tile.chunks.clear();
        tile.chunks.addAll(all);
        tile.currentIndex = 0;
        if (tileIdx == PayloadState.activeTileIndex) {
            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(all);
            PayloadState.activeChunkIndex = 0;
        }
        return all.size();
    }

    /**
     * Called by {@code MapEditorScreen} on close when the tile is dirty.
     * Handles both carpet and banner encoding, and multi-frame animated tiles.
     * {@code editorDelays} may be null to use the delays stored in the tile.
     */
    public static void saveEditorChanges(byte[][] allFrames, int[] editorDelays, int tileIdx, String playerName) {
        if (PayloadState.tiles.isEmpty() || tileIdx >= PayloadState.tiles.size()) return;
        try {
            int frameCount = allFrames.length;
            PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
            int flags = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

            // Concatenate frame bytes sequentially, then interleave for better compression.
            byte[] sequential = new byte[frameCount * MAP_BYTES];
            for (int f = 0; f < frameCount; f++) {
                System.arraycopy(allFrames[f], 0, sequential, f * MAP_BYTES, MAP_BYTES);
            }
            byte[] payloadBytes = frameCount > 1
                    ? PayloadManifest.interleaveFlat(sequential, frameCount)
                    : sequential;

            byte[] manifest;
            if (frameCount > 1) {
                flags |= PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_INTERLEAVED;
                int[] delays;
                if (editorDelays != null && editorDelays.length == frameCount) {
                    delays = editorDelays;
                } else if (tile.frameDelays != null && !tile.frameDelays.isEmpty()) {
                    delays = tile.frameDelays.stream().mapToInt(Integer::intValue).toArray();
                } else {
                    delays = new int[]{100};
                }
                manifest = PayloadManifest.toBytes(flags,
                        PayloadState.gridColumns, PayloadState.gridRows,
                        PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                        PayloadManifest.crc32(allFrames[0]), playerName,
                        PayloadState.currentTitle, tile.nonce,
                        frameCount, 0, delays);
                tile.frameCount = frameCount;
                tile.frameDelays = new ArrayList<>();
                for (int d : delays) tile.frameDelays.add(d);
            } else {
                manifest = PayloadManifest.toBytes(flags,
                        PayloadState.gridColumns, PayloadState.gridRows,
                        PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                        PayloadManifest.crc32(allFrames[0]), playerName,
                        PayloadState.currentTitle, tile.nonce);
                tile.frameCount = 1;
                tile.frameDelays = null;
            }
            saveTileData(tileIdx, manifest, payloadBytes);
            PayloadState.save();

            // Warn in chat if the saved result is still over budget.
            if (tile.carpetEncoded && tile.carpetCompressedB64 != null) {
                int bytes = Base64.getDecoder().decode(tile.carpetCompressedB64).length;
                if (bytes > CarpetChannel.MAX_TOTAL_BYTES) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.player != null) {
                        mc.player.sendMessage(Text.literal(String.format(
                                "§e⚠ Changes saved but tile is over budget (%d/%d bytes). " +
                                "Use §f/loominary reduce§e to compress further.",
                                bytes, CarpetChannel.MAX_TOTAL_BYTES)), false);
                    }
                }
            }
        } catch (Exception e) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§cEditor save failed: " + e.getMessage()), false);
            }
            System.err.println("[Loominary] Editor save failed: " + e.getMessage());
        }
    }

    /** Backwards-compatible overloads. */
    public static void saveEditorChanges(byte[][] allFrames, int tileIdx, String playerName) {
        saveEditorChanges(allFrames, null, tileIdx, playerName);
    }

    public static void saveEditorChanges(byte[] mapColors, int tileIdx, String playerName) {
        saveEditorChanges(new byte[][]{mapColors}, null, tileIdx, playerName);
    }

    /** Decodes map-color bytes for any tile (carpet or banner). */
    private static byte[] resolveMapColorsForTile(PayloadState.TileData tile, java.util.Collection<String> chunks) {
        if (tile.carpetEncoded) {
            if (tile.carpetCompressedB64 == null) {
                throw new IllegalStateException("Carpet tile has no stored compressed data — re-import.");
            }
            byte[] compressed = Base64.getDecoder().decode(tile.carpetCompressedB64);
            long size = Zstd.getFrameContentSize(compressed);
            if (size < 0) throw new IllegalStateException("Invalid compressed data in carpet tile.");
            byte[] full = Zstd.decompress(compressed, (int) size);
            if (full.length == MAP_BYTES) return full;
            PayloadManifest manifest = PayloadManifest.fromBytes(full);
            return java.util.Arrays.copyOfRange(full, manifest.headerSize, manifest.headerSize + MAP_BYTES);
        }
        return MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));
    }

    /** Strips the file extension from a filename (e.g. "myimage.png" → "myimage"). */
    private static String filenameStem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Returns the full decompressed payload for a tile — manifest header + all frame bytes.
     * For static tiles the payload is manifest + 16384 bytes. For animated tiles it is
     * manifest + frameCount × 16384 bytes. For v0 payloads (no manifest) it is 16384 bytes.
     */
    private static byte[] resolveFullPayloadForTile(PayloadState.TileData tile,
            java.util.Collection<String> chunks) {
        if (tile.carpetEncoded) {
            if (tile.carpetCompressedB64 == null)
                throw new IllegalStateException("Carpet tile has no stored compressed data — re-import.");
            byte[] compressed = Base64.getDecoder().decode(tile.carpetCompressedB64);
            long size = Zstd.getFrameContentSize(compressed);
            if (size < 0) throw new IllegalStateException("Invalid compressed data in carpet tile.");
            return Zstd.decompress(compressed, (int) size);
        }
        // Banner tile: reassemble chunks → base64 decode → decompress
        List<String> names = new ArrayList<>(chunks);
        names.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
        StringBuilder b64 = new StringBuilder();
        for (String s : names) b64.append(s.substring(2));
        byte[] compressed = Base64.getDecoder().decode(b64.toString());
        long originalSize = Zstd.getFrameContentSize(compressed);
        if (originalSize < 0) throw new IllegalStateException("Missing zstd frame size in banner payload.");
        return Zstd.decompress(compressed, (int) originalSize);
    }

    /**
     * Returns all animation frames for a tile as separate byte[16384] arrays.
     * Static tiles return a one-element array containing the single frame.
     */
    public static byte[][] resolveAllFramesForTile(PayloadState.TileData tile,
            java.util.Collection<String> chunks) {
        byte[] full = resolveFullPayloadForTile(tile, chunks);
        if (full.length == MAP_BYTES) {
            return new byte[][]{full.clone()};
        }
        PayloadManifest manifest = PayloadManifest.fromBytes(full);
        int fc = Math.max(1, manifest.frameCount);
        int offset = manifest.headerSize;

        // De-interleave frame region when FLAG_INTERLEAVED is set so the extraction
        // loop below always works with sequential frame data.
        if (manifest.interleaved() && fc > 1 && offset + fc * MAP_BYTES <= full.length) {
            byte[] sequential = PayloadManifest.deinterleaveFlat(
                    Arrays.copyOfRange(full, offset, offset + fc * MAP_BYTES), fc);
            byte[] newFull = Arrays.copyOf(full, full.length);
            System.arraycopy(sequential, 0, newFull, offset, sequential.length);
            full = newFull;
        }

        byte[][] frames = new byte[fc][MAP_BYTES];
        for (int f = 0; f < fc; f++) {
            int start = offset + f * MAP_BYTES;
            if (start + MAP_BYTES > full.length) {
                frames = Arrays.copyOf(frames, f == 0 ? 1 : f);
                if (f == 0) frames[0] = Arrays.copyOf(full, MAP_BYTES);
                break;
            }
            System.arraycopy(full, start, frames[f], 0, MAP_BYTES);
        }
        return frames;
    }

    // ── Caches for revert / reduce undo ────────────────────────────────

    private static final Map<Integer, byte[]> originalColors = new HashMap<>();
    private static final Map<Integer, List<String>> preReductionChunks = new HashMap<>();
    /** Parallel to preReductionChunks — saves carpetCompressedB64 for carpet tiles. */
    private static final Map<Integer, String> preReductionCarpetB64 = new HashMap<>();

    /**
     * True when the user explicitly called /loominary title; false when the title is
     * auto-derived from a filename. Replacement imports overwrite the title only when
     * this flag is false (i.e., each import gets its own filename as the default title).
     */
    private static boolean titleIsUserSet = false;

    /** Frame stride for the next GIF import. 1 = all frames; N = every Nth frame. */
    private static int importStride = 1;

    /** Set while any import is running on the background thread. */
    private static volatile boolean importInProgress = false;

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
                            // Default: carpet encoding. Add "banners" for legacy mode.
                            .then(ClientCommandManager.literal("import")
                                    // steal — default = carpet; "banners" = legacy
                                    .then(ClientCommandManager.literal("steal")
                                            .executes(ctx -> importStealCarpet(ctx.getSource()))
                                            .then(ClientCommandManager.literal("banners")
                                                    .executes(ctx -> importSteal(ctx.getSource()))))
                                    .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                            .suggests(FILENAME_SUGGESTIONS)
                                            // Default (carpet) — bare filename
                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, false, false))
                                            .then(ClientCommandManager.literal("dither")
                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, false, true)))
                                            .then(ClientCommandManager.literal("allshades")
                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, true, false))
                                                    .then(ClientCommandManager.literal("dither")
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, true, true))))
                                            // Default (carpet) + grid
                                            .then(ClientCommandManager
                                                    .argument("columns", IntegerArgumentType.integer(1, 16))
                                                    .then(ClientCommandManager
                                                            .argument("rows", IntegerArgumentType.integer(1, 16))
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                    IntegerArgumentType.getInteger(ctx, "rows"), false, false))
                                                            .then(ClientCommandManager.literal("dither")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                            IntegerArgumentType.getInteger(ctx, "rows"), false, true)))
                                                            .then(ClientCommandManager.literal("allshades")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                            IntegerArgumentType.getInteger(ctx, "rows"), true, false))
                                                                    .then(ClientCommandManager.literal("dither")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), true, true))))))
                                            // Back-compat alias: "carpet" keyword
                                            .then(ClientCommandManager.literal("carpet")
                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, false, false))
                                                    .then(ClientCommandManager.literal("dither")
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, false, true)))
                                                    .then(ClientCommandManager.literal("allshades")
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, true, false))
                                                            .then(ClientCommandManager.literal("dither")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, true, true))))
                                                    .then(ClientCommandManager
                                                            .argument("columns", IntegerArgumentType.integer(1, 16))
                                                            .then(ClientCommandManager
                                                                    .argument("rows", IntegerArgumentType.integer(1, 16))
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                            IntegerArgumentType.getInteger(ctx, "rows"), false, false))
                                                                    .then(ClientCommandManager.literal("dither")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), false, true)))
                                                                    .then(ClientCommandManager.literal("allshades")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), true, false))
                                                                            .then(ClientCommandManager.literal("dither")
                                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                            IntegerArgumentType.getInteger(ctx, "rows"), true, true)))))))
                                            // Explicit legacy: "banners" keyword
                                            .then(ClientCommandManager.literal("banners")
                                                    .executes(ctx -> importFile(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, false, false))
                                                    .then(ClientCommandManager.literal("dither")
                                                            .executes(ctx -> importFile(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, false, true)))
                                                    .then(ClientCommandManager.literal("allshades")
                                                            .executes(ctx -> importFile(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, true, false))
                                                            .then(ClientCommandManager.literal("dither")
                                                                    .executes(ctx -> importFile(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, true, true))))
                                                    .then(ClientCommandManager
                                                            .argument("columns", IntegerArgumentType.integer(1, 16))
                                                            .then(ClientCommandManager
                                                                    .argument("rows", IntegerArgumentType.integer(1, 16))
                                                                    .executes(ctx -> importFile(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                            IntegerArgumentType.getInteger(ctx, "rows"), false, false))
                                                                    .then(ClientCommandManager.literal("dither")
                                                                            .executes(ctx -> importFile(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), false, true)))
                                                                    .then(ClientCommandManager.literal("allshades")
                                                                            .executes(ctx -> importFile(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), true, false))
                                                                            .then(ClientCommandManager.literal("dither")
                                                                                    .executes(ctx -> importFile(ctx.getSource(),
                                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                            IntegerArgumentType.getInteger(ctx, "rows"), true, true)))))))))

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
                                    .then(ClientCommandManager.literal("prev")
                                            .executes(ctx -> tilePrev(ctx.getSource())))
                                    .then(ClientCommandManager.literal("pos")
                                            .then(ClientCommandManager.argument("col", IntegerArgumentType.integer(0))
                                                    .then(ClientCommandManager.argument("row", IntegerArgumentType.integer(0))
                                                            .executes(ctx -> tileSwitchPos(ctx.getSource(),
                                                                    IntegerArgumentType.getInteger(ctx, "col"),
                                                                    IntegerArgumentType.getInteger(ctx, "row"))))))
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
                                    .executes(ctx -> reduceOne(ctx.getSource(), 255))
                                    .then(ClientCommandManager.literal("undo")
                                            .executes(ctx -> reduceUndo(ctx.getSource())))
                                    .then(ClientCommandManager.literal("all")
                                            .executes(ctx -> reduceAll(ctx.getSource(), 255))
                                            .then(ClientCommandManager.literal("colors")
                                                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 248))
                                                            .executes(ctx -> reduceAllColors(ctx.getSource(),
                                                                    IntegerArgumentType.getInteger(ctx, "n")))))
                                            .then(ClientCommandManager.argument("target", IntegerArgumentType.integer(1, 255))
                                                    .executes(ctx -> reduceAll(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "target")))))
                                    .then(ClientCommandManager.literal("colors")
                                            .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 248))
                                                    .executes(ctx -> reduceOneColors(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "n")))))
                                    .then(ClientCommandManager.argument("target", IntegerArgumentType.integer(1, 255))
                                            .executes(ctx -> reduceOne(ctx.getSource(),
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

                            // ── stride ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("stride")
                                    .executes(ctx -> strideReset(ctx.getSource()))
                                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 100))
                                            .executes(ctx -> strideSet(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "n")))))

                            // ── export ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("export")
                                    .executes(ctx -> exportSchematic(ctx.getSource(), null))
                                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                                            .executes(ctx -> exportSchematic(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name")))))

                            // ── edit ───────────────────────────────────────────
                            .then(ClientCommandManager.literal("edit")
                                    .executes(ctx -> edit(ctx.getSource())))

                            // ── dither ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("dither")
                                    .executes(ctx -> dither(ctx.getSource(), false, 0))
                                    .then(ClientCommandManager.literal("all")
                                            .executes(ctx -> dither(ctx.getSource(), true, 0))
                                            .then(ClientCommandManager.literal("colors")
                                                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 248))
                                                            .executes(ctx -> dither(ctx.getSource(), true,
                                                                    IntegerArgumentType.getInteger(ctx, "n"))))))
                                    .then(ClientCommandManager.literal("colors")
                                            .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 248))
                                                    .executes(ctx -> dither(ctx.getSource(), false,
                                                            IntegerArgumentType.getInteger(ctx, "n"))))))

                            // ── resalt ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("resalt")
                                    .executes(ctx -> resalt(ctx.getSource())))

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
            String filename, int columns, int rows, boolean allShades, boolean dither) {
        if (filename.isEmpty()) {
            source.sendError(Text.literal("§cUsage: /loominary import <filename> banners [cols] [rows] [allshades]"));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cAn import is already in progress."));
            return 0;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Path filePath = client.runDirectory.toPath().resolve("loominary_data").resolve(filename);
        if (!Files.exists(filePath)) {
            source.sendError(Text.literal("§cFile not found: loominary_data/" + filename));
            return 0;
        }
        if (!titleIsUserSet) PayloadState.currentTitle = filenameStem(filename);

        if (filename.toLowerCase().endsWith(".gif")) {
            return importGif(source, filename, filePath, columns, rows, allShades, dither);
        }

        // Read image synchronously (fast I/O validation before going async)
        final BufferedImage img;
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            img = ImageIO.read(new ByteArrayInputStream(fileBytes));
        } catch (IOException e) {
            source.sendError(Text.literal("§cError reading file: " + e.getMessage()));
            return 0;
        }
        if (img == null) {
            source.sendError(Text.literal("§cCouldn't decode image: " + filename));
            return 0;
        }

        importInProgress = true;
        final String capturedTitle = PayloadState.currentTitle;
        final String playerName = source.getPlayer().getGameProfile().getName();
        source.sendFeedback(Text.literal("§7Encoding §f" + filename + "§7..."));

        Thread t = new Thread(() -> {
            try {
                int totalPixelsW = columns * MAP_SIZE;
                int totalPixelsH = rows * MAP_SIZE;
                BufferedImage scaled = new BufferedImage(totalPixelsW, totalPixelsH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, 0, 0, totalPixelsW, totalPixelsH, null);
                g.dispose();

                int totalTiles = columns * rows;
                int tileFlags = allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
                byte[][] ditheredTiles = dither
                        ? PngToMapColors.convertTwoPassGrid(scaled, !allShades, 0, true, columns, rows)
                        : null;

                List<PayloadState.TileData> newTiles = new ArrayList<>();
                List<String> tileNotes = new ArrayList<>();
                int totalBannersNeeded = 0;
                int maxBannersPerTile = 0;

                for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                    int col = tileIdx % columns;
                    int row = tileIdx / columns;
                    byte[] mapColors = dither
                            ? ditheredTiles[tileIdx]
                            : PngToMapColors.convert(
                                    scaled.getSubimage(col * MAP_SIZE, row * MAP_SIZE, MAP_SIZE, MAP_SIZE),
                                    !allShades);
                    byte[] manifestCheck = PayloadManifest.toBytes(
                            tileFlags, columns, rows, col, row, 0L, playerName, capturedTitle);
                    String note = null;
                    if (buildChunks(manifestCheck, mapColors).size() > MAX_CHUNKS) {
                        PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                                mapColors, manifestCheck, CHUNK_SIZE, MAX_CHUNKS);
                        mapColors = fit.mapColors;
                        note = String.format("reduced %d→%d colors",
                                fit.originalDistinctColors,
                                fit.originalDistinctColors - fit.colorsRemoved);
                    }
                    byte[] manifest = PayloadManifest.toBytes(
                            tileFlags, columns, rows, col, row,
                            PayloadManifest.crc32(mapColors), playerName, capturedTitle);
                    List<String> chunks = buildChunks(manifest, mapColors);
                    PayloadState.TileData tile = new PayloadState.TileData();
                    tile.chunks.addAll(chunks);
                    tile.currentIndex = 0;
                    newTiles.add(tile);
                    tileNotes.add(note);
                    totalBannersNeeded += chunks.size();
                    maxBannersPerTile = Math.max(maxBannersPerTile, chunks.size());
                }

                final List<PayloadState.TileData> finalTiles = newTiles;
                final List<String> finalNotes = tileNotes;
                final int finalTotalBanners = totalBannersNeeded;
                final int finalMaxPerTile = maxBannersPerTile;
                final int finalTotalTiles = totalTiles;

                client.execute(() -> {
                    try {
                        if (client.player == null) return;
                        PayloadState.tiles.clear();
                        PayloadState.tiles.addAll(finalTiles);
                        PayloadState.currentSourceFilename = filename;
                        PayloadState.currentTitle = capturedTitle;
                        PayloadState.gridColumns = columns;
                        PayloadState.gridRows = rows;
                        PayloadState.allShades = allShades;
                        PayloadState.dither = dither;
                        PayloadState.activeTileIndex = 0;
                        PayloadState.syncFromActiveTile();
                        PayloadState.save();

                        String modeTag = (allShades ? "all shades" : "legal palette")
                                + (dither ? ", dithered" : "");
                        source.sendFeedback(Text.literal(String.format(
                                "§aLoaded %s (%dx%d) as %dx%d grid (%d tile%s) §7[%s]",
                                filename, img.getWidth(), img.getHeight(),
                                columns, rows, finalTotalTiles, finalTotalTiles == 1 ? "" : "s", modeTag)));
                        for (int i = 0; i < finalTiles.size(); i++) {
                            String line = String.format("§7  %s: §f%d banners",
                                    PayloadState.tileLabel(i), finalTiles.get(i).chunks.size());
                            if (finalNotes.get(i) != null) line += " §e(" + finalNotes.get(i) + ")";
                            source.sendFeedback(Text.literal(line));
                        }
                        source.sendFeedback(Text.literal(String.format(
                                "§aTotal: %d banners across %d tile%s (max %d per tile).",
                                finalTotalBanners, finalTotalTiles, finalTotalTiles == 1 ? "" : "s", finalMaxPerTile)));

                        int totalBanners = 0, totalBundles = 0;
                        for (ItemStack stack : client.player.getInventory().main) {
                            if (stack.getItem().toString().contains("banner") && stack.getCustomName() == null)
                                totalBanners += stack.getCount();
                            if (stack.getItem() == Items.BUNDLE) totalBundles++;
                        }
                        if (totalBanners < finalMaxPerTile)
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ Only %d unnamed banners — largest tile needs %d.", totalBanners, finalMaxPerTile)));
                        int bundlesNeeded = (finalMaxPerTile + 15) / 16;
                        if (totalBundles < bundlesNeeded)
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ Only %d bundles — largest tile needs %d.", totalBundles, bundlesNeeded)));
                        source.sendFeedback(Text.literal("§e§lActive: " + PayloadState.tileLabel(0)
                                + ". Try §f/loominary preview§e or head to an anvil."));
                    } finally {
                        importInProgress = false;
                    }
                });
            } catch (Exception e) {
                client.execute(() -> {
                    try {
                        if (client.player != null)
                            source.sendError(Text.literal("§cImport failed: " + e.getMessage()));
                    } finally {
                        importInProgress = false;
                    }
                });
            }
        }, "loominary-import");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // import <file.gif>
    // ════════════════════════════════════════════════════════════════════

    private static int importGif(FabricClientCommandSource source, String filename,
            Path filePath, int columns, int rows, boolean allShades, boolean dither) {
        // title already set by importFile caller
        importInProgress = true;
        final String capturedTitle = PayloadState.currentTitle;
        final String playerName = source.getPlayer().getGameProfile().getName();
        final int capturedStride = importStride;
        final MinecraftClient client = MinecraftClient.getInstance();
        source.sendFeedback(Text.literal("§7Importing §f" + filename + "§7 in background..."));

        Thread t = new Thread(() -> {
            try {
                PngToMapColors.GifResult result = PngToMapColors.convertGif(
                        filePath, !allShades, 0, dither, columns, rows, capturedStride);

                int frameCount = result.frameCount();
                int[] rawDelays = result.frameDelays;
                boolean uniformDelay = true;
                for (int d : rawDelays) if (d != rawDelays[0]) { uniformDelay = false; break; }
                int[] manifestDelays = uniformDelay ? new int[]{rawDelays[0]} : rawDelays;

                int tileFlags = (frameCount > 1
                        ? PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_INTERLEAVED : 0)
                        | (allShades ? PayloadManifest.FLAG_ALL_SHADES : 0);
                int totalTiles = columns * rows;
                List<PayloadState.TileData> newTiles = new ArrayList<>();
                List<String> tileNotes = new ArrayList<>();
                int totalBannersNeeded = 0;
                int maxBannersPerTile = 0;
                boolean anyOverflow = false;

                for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                    int col = tileIdx % columns;
                    int row = tileIdx / columns;
                    byte[] sequential = new byte[frameCount * MAP_BYTES];
                    for (int f = 0; f < frameCount; f++)
                        System.arraycopy(result.tileFrames[tileIdx][f], 0, sequential, f * MAP_BYTES, MAP_BYTES);
                    byte[] allFramesBytes = frameCount > 1
                            ? PayloadManifest.interleaveFlat(sequential, frameCount) : sequential;
                    long crc = PayloadManifest.crc32(result.tileFrames[tileIdx][0]);
                    byte[] manifest = PayloadManifest.toBytes(
                            tileFlags, columns, rows, col, row, crc, playerName, capturedTitle,
                            0, frameCount, 0, manifestDelays);
                    List<String> chunks = buildChunks(manifest, allFramesBytes);
                    String note = null;
                    if (chunks.size() > MAX_CHUNKS) {
                        note = String.format("OVERFLOW: %d chunks for %d frames (max %d)", chunks.size(), frameCount, MAX_CHUNKS);
                        anyOverflow = true;
                    }
                    PayloadState.TileData tile = new PayloadState.TileData();
                    tile.chunks.addAll(chunks);
                    tile.currentIndex = 0;
                    tile.frameCount = frameCount;
                    tile.frameDelays = new ArrayList<>();
                    for (int d : rawDelays) tile.frameDelays.add(d);
                    newTiles.add(tile);
                    tileNotes.add(note);
                    totalBannersNeeded += chunks.size();
                    maxBannersPerTile = Math.max(maxBannersPerTile, chunks.size());
                }

                final List<PayloadState.TileData> finalTiles = newTiles;
                final List<String> finalNotes = tileNotes;
                final int finalTotal = totalBannersNeeded;
                final int finalMax = maxBannersPerTile;
                final int finalTileCount = totalTiles;
                final int finalFrameCount = frameCount;
                final boolean finalAnyOverflow = anyOverflow;
                final boolean finalUniform = uniformDelay;
                final int[] finalManifestDelays = manifestDelays;
                final int[] finalRawDelays = rawDelays;

                client.execute(() -> {
                    try {
                        if (client.player == null) return;
                        if (finalAnyOverflow) {
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ GIF over budget on some tiles (%d frame%s). Imported anyway — use /loominary reduce or /loominary edit.",
                                    finalFrameCount, finalFrameCount == 1 ? "" : "s")));
                            for (int i = 0; i < finalTileCount; i++) {
                                if (finalNotes.get(i) != null)
                                    source.sendFeedback(Text.literal("§e  " + PayloadState.tileLabel(i) + ": " + finalNotes.get(i)));
                            }
                        }
                        PayloadState.tiles.clear();
                        PayloadState.tiles.addAll(finalTiles);
                        PayloadState.currentSourceFilename = filename;
                        PayloadState.currentTitle = capturedTitle;
                        PayloadState.gridColumns = columns;
                        PayloadState.gridRows = rows;
                        PayloadState.allShades = allShades;
                        PayloadState.dither = dither;
                        PayloadState.activeTileIndex = 0;
                        PayloadState.syncFromActiveTile();
                        PayloadState.save();

                        String modeTag = (allShades ? "all shades" : "legal palette")
                                + (dither ? ", dithered" : "") + ", animated";
                        source.sendFeedback(Text.literal(String.format(
                                "§aLoaded %s as %dx%d grid (%d tile%s, §e%d frame%s§a) §7[%s]",
                                filename, columns, rows, finalTileCount, finalTileCount == 1 ? "" : "s",
                                finalFrameCount, finalFrameCount == 1 ? "" : "s", modeTag)));
                        for (int i = 0; i < finalTiles.size(); i++)
                            source.sendFeedback(Text.literal(String.format("§7  %s: §f%d banners",
                                    PayloadState.tileLabel(i), finalTiles.get(i).chunks.size())));
                        String delayDesc = finalUniform
                                ? finalManifestDelays[0] + "ms/frame"
                                : "variable (" + finalRawDelays[0] + "–"
                                        + java.util.Arrays.stream(finalRawDelays).max().getAsInt() + "ms)";
                        source.sendFeedback(Text.literal(String.format(
                                "§aTotal: %d banners across %d tile%s (max %d per tile). Delay: %s.",
                                finalTotal, finalTileCount, finalTileCount == 1 ? "" : "s", finalMax, delayDesc)));
                        source.sendFeedback(Text.literal("§e§lActive: " + PayloadState.tileLabel(0)
                                + ". Head to an anvil to start placing banners."));
                    } finally {
                        importInProgress = false;
                    }
                });
            } catch (Exception e) {
                client.execute(() -> {
                    try {
                        if (client.player != null)
                            source.sendError(Text.literal("§cGIF import failed: " + e.getMessage()));
                    } finally {
                        importInProgress = false;
                    }
                });
            }
        }, "loominary-import");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // import <file> carpet
    // ════════════════════════════════════════════════════════════════════

    private static int importFileCarpet(FabricClientCommandSource source,
            String filename, int columns, int rows, boolean allShades, boolean dither) {
        if (filename.isEmpty()) {
            source.sendError(Text.literal("§cUsage: /loominary import <filename> carpet [cols] [rows] [allshades] [dither]"));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cAn import is already in progress."));
            return 0;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Path filePath = client.runDirectory.toPath().resolve("loominary_data").resolve(filename);
        if (!Files.exists(filePath)) {
            source.sendError(Text.literal("§cFile not found: loominary_data/" + filename));
            return 0;
        }
        if (!titleIsUserSet) PayloadState.currentTitle = filenameStem(filename);

        if (filename.toLowerCase().endsWith(".gif")) {
            return importGifCarpet(source, filename, filePath, columns, rows, allShades, dither);
        }

        final BufferedImage img;
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            img = ImageIO.read(new ByteArrayInputStream(fileBytes));
        } catch (IOException e) {
            source.sendError(Text.literal("§cError reading file: " + e.getMessage()));
            return 0;
        }
        if (img == null) {
            source.sendError(Text.literal("§cCouldn't decode image: " + filename));
            return 0;
        }

        importInProgress = true;
        final String capturedTitle = PayloadState.currentTitle;
        final String playerName = source.getPlayer().getGameProfile().getName();
        source.sendFeedback(Text.literal("§7Encoding §f" + filename + "§7..."));

        Thread t = new Thread(() -> {
            try {
                int totalPixelsW = columns * MAP_SIZE;
                int totalPixelsH = rows * MAP_SIZE;
                BufferedImage scaled = new BufferedImage(totalPixelsW, totalPixelsH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, 0, 0, totalPixelsW, totalPixelsH, null);
                g.dispose();

                int totalTiles = columns * rows;
                int tileFlags = allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
                byte[][] ditheredTiles = dither
                        ? PngToMapColors.convertTwoPassGrid(scaled, !allShades, 0, true, columns, rows)
                        : null;

                List<PayloadState.TileData> newTiles = new ArrayList<>();
                List<String> tileNotes = new ArrayList<>();
                List<Path> schematicPaths = new ArrayList<>();

                for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                    int col = tileIdx % columns;
                    int row = tileIdx / columns;
                    byte[] mapColors = dither
                            ? ditheredTiles[tileIdx]
                            : PngToMapColors.convert(
                                    scaled.getSubimage(col * MAP_SIZE, row * MAP_SIZE, MAP_SIZE, MAP_SIZE),
                                    !allShades);
                    byte[] manifestBytes = PayloadManifest.toBytes(
                            tileFlags, columns, rows, col, row,
                            PayloadManifest.crc32(mapColors), playerName, capturedTitle);
                    CarpetEncoding enc;
                    String note = null;
                    try {
                        enc = buildCarpetEncoding(manifestBytes, mapColors);
                    } catch (IllegalStateException overflow) {
                        PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                                mapColors, manifestBytes, CHUNK_SIZE, MAX_CHUNKS_CARPET);
                        mapColors = fit.mapColors;
                        manifestBytes = PayloadManifest.toBytes(tileFlags, columns, rows, col, row,
                                PayloadManifest.crc32(mapColors), playerName, capturedTitle);
                        enc = buildCarpetEncoding(manifestBytes, mapColors);
                        note = String.format("reduced %d→%d colors to fit",
                                fit.originalDistinctColors, fit.originalDistinctColors - fit.colorsRemoved);
                    }
                    String schName = SchematicExporter.resolveSchematicName(
                            capturedTitle != null ? capturedTitle + (totalTiles > 1 ? "_tile" + tileIdx : "") : null);
                    schematicPaths.add(SchematicExporter.exportCarpetTile(enc.nibbles(), enc.carpetBytes(), schName));
                    PayloadState.TileData tile = new PayloadState.TileData();
                    tile.chunks.addAll(enc.allChunks());
                    tile.currentIndex = 0;
                    tile.carpetEncoded = true;
                    tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
                    newTiles.add(tile);
                    tileNotes.add(note);
                }

                final List<PayloadState.TileData> finalTiles = newTiles;
                final List<String> finalNotes = tileNotes;
                final List<Path> finalSchematics = schematicPaths;
                final int finalTileCount = totalTiles;

                client.execute(() -> {
                    try {
                        if (client.player == null) return;
                        PayloadState.tiles.clear();
                        PayloadState.tiles.addAll(finalTiles);
                        PayloadState.currentSourceFilename = filename;
                        PayloadState.currentTitle = capturedTitle;
                        PayloadState.gridColumns = columns;
                        PayloadState.gridRows = rows;
                        PayloadState.allShades = allShades;
                        PayloadState.dither = dither;
                        PayloadState.activeTileIndex = 0;
                        PayloadState.syncFromActiveTile();
                        PayloadState.save();

                        String modeTag = "carpet, " + (allShades ? "all shades" : "legal palette")
                                + (dither ? ", dithered" : "");
                        source.sendFeedback(Text.literal(String.format(
                                "§aLoaded %s (%dx%d) as %dx%d grid (%d tile%s) §7[%s]",
                                filename, img.getWidth(), img.getHeight(),
                                columns, rows, finalTileCount, finalTileCount == 1 ? "" : "s", modeTag)));
                        for (int i = 0; i < finalTiles.size(); i++) {
                            PayloadState.TileData tile = finalTiles.get(i);
                            String lcName = tile.chunks.isEmpty() ? "" : tile.chunks.get(0);
                            int totalBytes = lcName.length() >= 6 ? Integer.parseInt(lcName.substring(2, 6), 16) : 0;
                            boolean overBudget = totalBytes > CarpetChannel.MAX_TOTAL_BYTES;
                            int carpetRows = (Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES) * 2 + 127) / 128;
                            String line = String.format("§7  %s: §f%d carpet rows, %d overflow banner%s §7(%d bytes)%s",
                                    PayloadState.tileLabel(i), carpetRows, tile.chunks.size() - 1,
                                    tile.chunks.size() == 2 ? "" : "s", totalBytes,
                                    overBudget ? " §c[OVER BUDGET]" : "");
                            if (finalNotes.get(i) != null) line += " §e(" + finalNotes.get(i) + ")";
                            source.sendFeedback(Text.literal(line));
                        }
                        source.sendFeedback(Text.literal("§aSchematics written to schematics/:"));
                        for (Path p : finalSchematics)
                            source.sendFeedback(Text.literal("§7  " + p.getFileName()));
                        int maxOverflow = finalTiles.stream().mapToInt(ti -> ti.chunks.size() - 1).max().orElse(0);
                        if (maxOverflow > 0)
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ %d overflow banner%s needed — rename at anvil and click with map.",
                                    maxOverflow, maxOverflow == 1 ? "" : "s")));
                        else
                            source.sendFeedback(Text.literal("§aNo overflow banners needed — just place the carpet schematic and scan with your map!"));
                    } finally {
                        importInProgress = false;
                    }
                });
            } catch (Exception e) {
                client.execute(() -> {
                    try {
                        if (client.player != null)
                            source.sendError(Text.literal("§cImport failed: " + e.getMessage()));
                    } finally {
                        importInProgress = false;
                    }
                });
            }
        }, "loominary-import");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // import <file.gif> carpet
    // ════════════════════════════════════════════════════════════════════

    private static int importGifCarpet(FabricClientCommandSource source, String filename,
            Path filePath, int columns, int rows, boolean allShades, boolean dither) {
        // title + importInProgress already set by importFileCarpet caller
        importInProgress = true;
        final String capturedTitle = PayloadState.currentTitle;
        final String playerName = source.getPlayer().getGameProfile().getName();
        final int capturedStride = importStride;
        final MinecraftClient client = MinecraftClient.getInstance();
        source.sendFeedback(Text.literal("§7Importing §f" + filename + "§7 in background..."));

        Thread t = new Thread(() -> {
          try {
            PngToMapColors.GifResult result = PngToMapColors.convertGif(
                    filePath, !allShades, 0, dither, columns, rows, capturedStride);

            int frameCount = result.frameCount();
            int[] rawDelays = result.frameDelays;

            boolean uniformDelay = true;
            for (int d : rawDelays) if (d != rawDelays[0]) { uniformDelay = false; break; }
            int[] manifestDelays = uniformDelay ? new int[]{rawDelays[0]} : rawDelays;

            int tileFlags = (frameCount > 1
                    ? PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_INTERLEAVED : 0)
                    | (allShades ? PayloadManifest.FLAG_ALL_SHADES : 0);

            int totalTiles = columns * rows;
            List<PayloadState.TileData> newTiles = new ArrayList<>();
            List<Path> schematicPaths = new ArrayList<>();
            List<String> tileNotes = new ArrayList<>();
            boolean anyOverflow = false;

            for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                int col = tileIdx % columns;
                int row = tileIdx / columns;

                byte[] sequential = new byte[frameCount * MAP_BYTES];
                for (int f = 0; f < frameCount; f++)
                    System.arraycopy(result.tileFrames[tileIdx][f], 0, sequential, f * MAP_BYTES, MAP_BYTES);
                byte[] allFramesBytes = frameCount > 1
                        ? PayloadManifest.interleaveFlat(sequential, frameCount) : sequential;

                long crc = PayloadManifest.crc32(result.tileFrames[tileIdx][0]);
                byte[] manifestBytes = (frameCount > 1)
                        ? PayloadManifest.toBytes(tileFlags, columns, rows, col, row,
                                crc, playerName, capturedTitle, 0, frameCount, 0, manifestDelays)
                        : PayloadManifest.toBytes(tileFlags, columns, rows, col, row,
                                crc, playerName, capturedTitle);

                CarpetEncoding enc = buildCarpetEncoding(manifestBytes, allFramesBytes);
                String tileNote = null;
                if (enc.compressed().length > CarpetChannel.MAX_TOTAL_BYTES) {
                    tileNote = String.format("OVER BUDGET: %d bytes (max %d)",
                            enc.compressed().length, CarpetChannel.MAX_TOTAL_BYTES);
                    anyOverflow = true;
                }

                String schName = SchematicExporter.resolveSchematicName(
                        capturedTitle + (totalTiles > 1 ? "_tile" + tileIdx : ""));
                schematicPaths.add(SchematicExporter.exportCarpetTile(enc.nibbles(), enc.carpetBytes(), schName));

                PayloadState.TileData tile = new PayloadState.TileData();
                tile.chunks.addAll(enc.allChunks());
                tile.currentIndex = 0;
                tile.carpetEncoded = true;
                tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
                tile.frameCount = frameCount;
                tile.frameDelays = new ArrayList<>();
                for (int d : rawDelays) tile.frameDelays.add(d);
                newTiles.add(tile);
                tileNotes.add(tileNote);
            }

            final List<PayloadState.TileData> finalTiles = newTiles;
            final List<Path> finalSchematics = schematicPaths;
            final List<String> finalNotes = tileNotes;
            final int finalTileCount = totalTiles;
            final int finalFrameCount = frameCount;
            final boolean finalAnyOverflow = anyOverflow;
            final boolean finalUniform = uniformDelay;
            final int[] finalManifestDelays = manifestDelays;
            final int[] finalRawDelays = rawDelays;

            client.execute(() -> {
                try {
                    if (client.player == null) return;
                    PayloadState.tiles.clear();
                    PayloadState.tiles.addAll(finalTiles);
                    PayloadState.currentSourceFilename = filename;
                    PayloadState.currentTitle = capturedTitle;
                    PayloadState.gridColumns = columns;
                    PayloadState.gridRows = rows;
                    PayloadState.allShades = allShades;
                    PayloadState.dither = dither;
                    PayloadState.activeTileIndex = 0;
                    PayloadState.syncFromActiveTile();
                    PayloadState.save();

                    String modeTag = "carpet, " + (allShades ? "all shades" : "legal palette")
                            + (dither ? ", dithered" : "") + ", animated";
                    source.sendFeedback(Text.literal(String.format(
                            "§aLoaded %s as %dx%d grid (%d tile%s, §e%d frame%s§a) §7[%s]",
                            filename, columns, rows, finalTileCount, finalTileCount == 1 ? "" : "s",
                            finalFrameCount, finalFrameCount == 1 ? "" : "s", modeTag)));
                    for (int i = 0; i < finalTiles.size(); i++) {
                        PayloadState.TileData tile = finalTiles.get(i);
                        int overflowBanners = tile.chunks.size() - 1;
                        String lcName = tile.chunks.isEmpty() ? "" : tile.chunks.get(0);
                        int totalBytes = lcName.length() >= 6 ? Integer.parseInt(lcName.substring(2, 6), 16) : 0;
                        boolean overBudget = totalBytes > CarpetChannel.MAX_TOTAL_BYTES;
                        int carpetRows = (Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES) * 2 + 127) / 128;
                        String budgetTag = overBudget
                                ? String.format(" §c[OVER BUDGET by %d bytes]", totalBytes - CarpetChannel.MAX_TOTAL_BYTES) : "";
                        source.sendFeedback(Text.literal(String.format(
                                "§7  %s: §f%d carpet rows, %d overflow banner%s §7(%d bytes)%s",
                                PayloadState.tileLabel(i), carpetRows, overflowBanners,
                                overflowBanners == 1 ? "" : "s", totalBytes, budgetTag)));
                        if (finalNotes.get(i) != null)
                            source.sendFeedback(Text.literal("§e    ↳ " + finalNotes.get(i)));
                    }
                    source.sendFeedback(Text.literal("§aSchematics written to schematics/:"));
                    for (Path p : finalSchematics) source.sendFeedback(Text.literal("§7  " + p.getFileName()));
                    String delayDesc = finalUniform
                            ? finalManifestDelays[0] + "ms/frame"
                            : "variable (" + finalRawDelays[0] + "–"
                                    + java.util.Arrays.stream(finalRawDelays).max().getAsInt() + "ms)";
                    source.sendFeedback(Text.literal("§7Delay: " + delayDesc));
                    if (finalAnyOverflow) {
                        source.sendFeedback(Text.literal(
                                "§e⚠ Some tiles are over budget — use §f/loominary reduce§e or §f/loominary edit§e before placing."));
                    } else {
                        int maxOvf = finalTiles.stream().mapToInt(ti -> ti.chunks.size() - 1).max().orElse(0);
                        if (maxOvf > 0)
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ %d overflow banner%s needed — rename at anvil and click with map.",
                                    maxOvf, maxOvf == 1 ? "" : "s")));
                        else
                            source.sendFeedback(Text.literal("§aNo overflow banners needed — just place the carpet schematic and scan with your map!"));
                    }
                } finally {
                    importInProgress = false;
                }
            });
          } catch (Exception e) {
            client.execute(() -> {
                try {
                    if (client.player != null)
                        source.sendError(Text.literal("§cGIF import failed: " + e.getMessage()));
                } finally {
                    importInProgress = false;
                }
            });
          }
        }, "loominary-import");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // import steal
    // ════════════════════════════════════════════════════════════════════

    private static int importSteal(FabricClientCommandSource source) {
        FramedMap fm = resolveCrosshairMap(source);
        if (fm == null)
            return 0;

        if (PayloadState.currentTitle == null) {
            PayloadState.currentTitle = "map_" + fm.mapId.id();
        }

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

    private static int importStealCarpet(FabricClientCommandSource source) {
        FramedMap fm = resolveCrosshairMap(source);
        if (fm == null)
            return 0;

        if (PayloadState.currentTitle == null) {
            PayloadState.currentTitle = "map_" + fm.mapId.id();
        }

        byte[] mapColors = fm.mapState.colors.clone();
        String playerName = source.getPlayer().getGameProfile().getName();
        byte[] manifestBytes = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, PayloadManifest.crc32(mapColors), playerName,
                PayloadState.currentTitle);

        CarpetEncoding enc;
        String note = null;
        try {
            enc = buildCarpetEncoding(manifestBytes, mapColors);
        } catch (IllegalStateException overflow) {
            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                    mapColors, manifestBytes, CHUNK_SIZE, MAX_CHUNKS_CARPET);
            mapColors = fit.mapColors;
            manifestBytes = PayloadManifest.toBytes(
                    0, 1, 1, 0, 0, PayloadManifest.crc32(mapColors), playerName,
                    PayloadState.currentTitle);
            enc = buildCarpetEncoding(manifestBytes, mapColors);
            note = String.format("reduced %d→%d colors",
                    fit.originalDistinctColors, fit.originalDistinctColors - fit.colorsRemoved);
        }

        if (!PayloadState.tiles.isEmpty()) {
            PayloadState.syncToActiveTile();
        }

        PayloadState.TileData tile = new PayloadState.TileData();
        tile.chunks.addAll(enc.allChunks());
        tile.currentIndex = 0;
        tile.carpetEncoded = true;
        tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
        PayloadState.tiles.add(tile);

        int tileIdx = PayloadState.tiles.size() - 1;
        PayloadState.currentSourceFilename = "stolen (x" + PayloadState.tiles.size() + ")";
        PayloadState.gridColumns = PayloadState.tiles.size();
        PayloadState.gridRows = 1;
        PayloadState.activeTileIndex = tileIdx;
        PayloadState.syncFromActiveTile();

        int overflowBanners = tile.chunks.size() - 1;
        int totalBytes = enc.compressed().length;
        int carpetRows = (enc.carpetBytes() * 2 + 127) / 128;

        try {
            String schematicName = SchematicExporter.resolveSchematicName(
                    PayloadState.currentTitle + (PayloadState.tiles.size() > 1 ? "_tile" + tileIdx : ""));
            Path schPath = SchematicExporter.exportCarpetTile(enc.nibbles(), enc.carpetBytes(), schematicName);
            PayloadState.save();
            String msg = String.format(
                    "§aStole map id=%d at %s → %s [carpet, %d rows, %d compressed bytes].",
                    fm.mapId.id(), maskedPos(fm.frame.getBlockPos()),
                    PayloadState.tileLabel(tileIdx), carpetRows, totalBytes);
            if (note != null) msg += " §e(" + note + ")";
            source.sendFeedback(Text.literal(msg));
            source.sendFeedback(Text.literal("§7Schematic: " + schPath.getFileName()));
        } catch (IOException e) {
            PayloadState.save();
            source.sendFeedback(Text.literal(String.format(
                    "§aStole map id=%d → %s [carpet]. §eSchematic export failed: %s",
                    fm.mapId.id(), PayloadState.tileLabel(tileIdx), e.getMessage())));
        }

        if (overflowBanners > 0) {
            source.sendFeedback(Text.literal(String.format(
                    "§e⚠ %d overflow banner%s needed — rename at anvil and click with map.",
                    overflowBanners, overflowBanners == 1 ? "" : "s")));
        }
        source.sendFeedback(Text.literal(String.format(
                "§7%d tile%s total. Steal more maps or place the schematic.",
                PayloadState.tiles.size(), PayloadState.tiles.size() == 1 ? "" : "s")));
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
        if (importStride != 1) {
            source.sendFeedback(Text.literal(String.format(
                    "§7Stride: §e%d §7(next GIF import keeps every %dth frame — use /loominary stride to reset)",
                    importStride, importStride)));
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

            String carpetTag = tile.carpetEncoded ? " §8[carpet]" : "";
            String statusStr;
            if (done >= total)
                statusStr = "§a✓ done" + carpetTag;
            else if (i == PayloadState.activeTileIndex)
                statusStr = String.format("§e► %d/%d §7(active)%s", done, total, carpetTag);
            else
                statusStr = String.format("§7  %d/%d%s", done, total, carpetTag);

            // Payload size / budget info
            String budgetStr;
            if (tile.carpetEncoded) {
                List<String> chunks = (i == PayloadState.activeTileIndex)
                        ? PayloadState.ACTIVE_CHUNKS : tile.chunks;
                String lcName = chunks.isEmpty() ? "" : chunks.get(0);
                int compressedBytes = lcName.length() >= 6
                        ? Integer.parseInt(lcName.substring(2, 6), 16) : 0;
                int overflowBanners = chunks.size() - 1;
                boolean overBudget = compressedBytes > CarpetChannel.MAX_TOTAL_BYTES;
                if (overBudget) {
                    budgetStr = String.format(" §c[%d bytes, %d overflow — OVER BUDGET by %d]",
                            compressedBytes, overflowBanners,
                            compressedBytes - CarpetChannel.MAX_TOTAL_BYTES);
                } else {
                    budgetStr = String.format(" §8[%d bytes, %d overflow]",
                            compressedBytes, overflowBanners);
                }
            } else {
                boolean overBudget = total > MAX_CHUNKS;
                if (overBudget) {
                    budgetStr = String.format(" §c[%d/%d banners — OVER BUDGET by %d]",
                            total, MAX_CHUNKS, total - MAX_CHUNKS);
                } else {
                    budgetStr = String.format(" §8[%d/%d banners]", total, MAX_CHUNKS);
                }
            }

            source.sendFeedback(Text.literal(String.format("  %s: %s%s",
                    PayloadState.tileLabel(i), statusStr, budgetStr)));
        }

        boolean anyCarpet = PayloadState.tiles.stream().anyMatch(t -> t.carpetEncoded);
        boolean anyOverBudget = false;
        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            PayloadState.TileData t = PayloadState.tiles.get(i);
            List<String> chunks = (i == PayloadState.activeTileIndex)
                    ? PayloadState.ACTIVE_CHUNKS : t.chunks;
            if (t.carpetEncoded) {
                String lc = chunks.isEmpty() ? "" : chunks.get(0);
                int bytes = lc.length() >= 6 ? Integer.parseInt(lc.substring(2, 6), 16) : 0;
                if (bytes > CarpetChannel.MAX_TOTAL_BYTES) { anyOverBudget = true; break; }
            } else if (chunks.size() > MAX_CHUNKS) {
                anyOverBudget = true; break;
            }
        }
        String overallBudget = anyOverBudget ? " §c[some tiles OVER BUDGET — use /loominary reduce]" : "";
        source.sendFeedback(Text.literal(String.format(
                "§7Overall: §f%d§7/§f%d §7banner%s%s",
                totalDone, totalChunks, anyCarpet ? "s/LC chunks" : "s", overallBudget)));
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

    private static int tilePrev(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        int count = PayloadState.tiles.size();
        int prev = (PayloadState.activeTileIndex - 1 + count) % count;
        PayloadState.switchTile(prev);
        PayloadState.save();
        PayloadState.TileData tile = PayloadState.tiles.get(prev);
        source.sendFeedback(Text.literal(String.format(
                "§aSwitched to %s §7(%d/%d banners done)",
                PayloadState.tileLabel(prev),
                tile.currentIndex, tile.chunks.size())));
        return 1;
    }

    private static int tileSwitchPos(FabricClientCommandSource source, int col, int row) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (col >= PayloadState.gridColumns || row >= PayloadState.gridRows) {
            source.sendError(Text.literal(String.format(
                    "§cPosition (%d,%d) out of range for %d×%d grid.",
                    col, row, PayloadState.gridColumns, PayloadState.gridRows)));
            return 0;
        }
        int index = row * PayloadState.gridColumns + col;
        return tileSwitch(source, index);
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

                byte[] mapColors = resolveMapColorsForTile(tile, tile.chunks);

                if (!originalColors.containsKey(cell.mapId().id())) {
                    originalColors.put(cell.mapId().id(), cell.mapState().colors.clone());
                }
                MapBannerDecoder.paintMap(fm.client, cell.mapId(), cell.mapState(), mapColors);
                if (tile.frameCount > 1) {
                    try {
                        byte[] full = resolveFullPayloadForTile(tile, tile.chunks);
                        MapBannerDecoder.registerAnimatedFromDecompressed(
                                fm.client, cell.mapId(), cell.frame().getBlockPos(), full);
                    } catch (Exception ignored) {}
                }
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
        MapBannerDecoder.unregisterAnimated(fm.mapId.id());
        source.sendFeedback(Text.literal(String.format(
                "§aReverted map id=%d at %s to its original state.",
                fm.mapId.id(), maskedPos(fm.frame.getBlockPos()))));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // palette / reduce / reduce undo
    // ════════════════════════════════════════════════════════════════════
    //
    // Four reduce paths:
    //   reduceOne(banners)      — active tile, banner-count target
    //   reduceOneColors(n)      — active tile, color-count target
    //   reduceAll(banners)      — every tile, banner-count target
    //   reduceAllColors(n)      — every tile, color-count target
    //
    // All four save pre-reduction chunks in preReductionChunks so that
    // "reduce undo" can restore the active tile.
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
            byte[] mapColors = resolveMapColors();

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

            // ── Rarity distribution histogram ──────────────────────────────
            int[] bucketBounds  = {1, 5, 20, 100, Integer.MAX_VALUE};
            String[] bucketLbls = {"    1px", " 2- 5px", " 6-20px", "21-100px", "  101+px"};
            int[] bucketColors  = new int[5];
            int[] bucketPixels  = new int[5];
            for (int c = 1; c < 256; c++) {
                if (freq[c] == 0) continue;
                for (int b = 0; b < bucketBounds.length; b++) {
                    if (freq[c] <= bucketBounds[b]) {
                        bucketColors[b]++;
                        bucketPixels[b] += freq[c];
                        break;
                    }
                }
            }
            int maxBucket = 0;
            for (int cnt : bucketColors) maxBucket = Math.max(maxBucket, cnt);

            source.sendFeedback(Text.literal("§7Color rarity distribution:"));
            final int BAR_WIDTH = 16;
            for (int b = 0; b < 5; b++) {
                if (bucketColors[b] == 0) continue;
                int bars = maxBucket > 0
                        ? Math.max(1, bucketColors[b] * BAR_WIDTH / maxBucket) : 0;
                String bar = "§a" + "█".repeat(bars) + "§8" + "░".repeat(BAR_WIDTH - bars);
                source.sendFeedback(Text.literal(String.format(
                        "§7 %s §f%3d §7colors  %s §7%.1f%%",
                        bucketLbls[b], bucketColors[b], bar, 100.0 * bucketPixels[b] / MAP_BYTES)));
            }

            // ── Cumulative removal cost at fixed thresholds ────────────────
            if (distinctColors > 2) {
                int[] thresholds = {5, 10, 20, 50};
                source.sendFeedback(Text.literal("§7Removal cost (rarest-first):"));
                int cumPixels = 0, threshIdx = 0;
                for (int i = 0; i < colorsByFreq.size() - 1 && threshIdx < thresholds.length; i++) {
                    if (thresholds[threshIdx] >= distinctColors) break;
                    cumPixels += colorsByFreq.get(i)[1];
                    if (i + 1 == thresholds[threshIdx]) {
                        source.sendFeedback(Text.literal(String.format(
                                "§7  remove %2d: §f%5d §7px (§f%.1f%%§7 of tile)",
                                thresholds[threshIdx], cumPixels,
                                100.0 * cumPixels / MAP_BYTES)));
                        threshIdx++;
                    }
                }
            }

            // ── Rarest individual colors ───────────────────────────────────
            int showCount = Math.min(10, colorsByFreq.size());
            source.sendFeedback(Text.literal(String.format(
                    "§7Rarest %d colors:", showCount)));
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

    /** Concatenates all frames into a single flat byte array for union-based reduction. */
    private static byte[] mergeFrames(byte[][] frames) {
        if (frames.length == 1) return frames[0];
        byte[] union = new byte[frames.length * MAP_BYTES];
        for (int f = 0; f < frames.length; f++)
            System.arraycopy(frames[f], 0, union, f * MAP_BYTES, MAP_BYTES);
        return union;
    }

    /** Returns the stored per-frame delays for a tile, padded/truncated to frameCount. */
    private static int[] tileDelays(PayloadState.TileData tile, int frameCount) {
        if (tile.frameDelays != null && !tile.frameDelays.isEmpty()) {
            int[] stored = tile.frameDelays.stream().mapToInt(Integer::intValue).toArray();
            return Arrays.copyOf(stored, frameCount); // pads with 0 if shorter (rare)
        }
        int[] d = new int[frameCount];
        Arrays.fill(d, 100);
        return d;
    }

    /**
     * Builds a reduce manifest for a tile. Automatically adds FLAG_ANIMATED and the
     * per-frame delay table when frameCount > 1.
     */
    private static byte[] buildReduceManifest(int tileIdx, long crc, String playerName,
            int flags, int frameCount, int[] delays) {
        if (frameCount > 1) {
            return PayloadManifest.toBytes(
                    flags | PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_INTERLEAVED,
                    PayloadState.gridColumns, PayloadState.gridRows,
                    PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                    crc, playerName, PayloadState.currentTitle,
                    PayloadState.tiles.get(tileIdx).nonce,
                    frameCount, 0, delays);
        }
        return PayloadManifest.toBytes(flags,
                PayloadState.gridColumns, PayloadState.gridRows,
                PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                crc, playerName, PayloadState.currentTitle,
                PayloadState.tiles.get(tileIdx).nonce);
    }

    /** Creates a minimal TileData snapshot safe to pass to the background thread. */
    private static PayloadState.TileData snapshotTile(PayloadState.TileData tile) {
        PayloadState.TileData snap = new PayloadState.TileData();
        snap.carpetEncoded      = tile.carpetEncoded;
        snap.carpetCompressedB64 = tile.carpetCompressedB64;
        snap.frameDelays        = tile.frameDelays != null ? new ArrayList<>(tile.frameDelays) : null;
        snap.nonce              = tile.nonce;
        return snap;
    }

    private static int reduceOne(FabricClientCommandSource source, int target) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        int tileIdx = PayloadState.activeTileIndex;
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        boolean isCarpet = tile.carpetEncoded;
        int effectiveTarget = isCarpet ? MAX_CHUNKS_CARPET : target;

        if (!isCarpet) {
            int currentBanners = PayloadState.ACTIVE_CHUNKS.size();
            if (currentBanners <= target) {
                source.sendFeedback(Text.literal(String.format(
                        "§aTile already fits in %d banners (currently %d). No reduction needed.",
                        target, currentBanners)));
                return 1;
            }
        }

        // Snapshot everything the background thread needs before releasing the game thread.
        final int oldCount = PayloadState.ACTIVE_CHUNKS.size();
        preReductionChunks.put(tileIdx, new ArrayList<>(PayloadState.ACTIVE_CHUNKS));
        if (isCarpet && tile.carpetCompressedB64 != null)
            preReductionCarpetB64.put(tileIdx, tile.carpetCompressedB64);

        final List<String> chunksSnap  = new ArrayList<>(PayloadState.ACTIVE_CHUNKS);
        final PayloadState.TileData tileSnap = snapshotTile(tile);
        final String playerName        = source.getPlayer().getGameProfile().getName();
        final int flags                = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Reducing " + PayloadState.tileLabel(tileIdx) + "..."));

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            try {
                byte[][] frames = resolveAllFramesForTile(tileSnap, chunksSnap);
                int fc      = frames.length;
                byte[] union = mergeFrames(frames);
                int[] delays = tileDelays(tileSnap, fc);
                byte[] prefix = buildReduceManifest(tileIdx, 0L, playerName, flags, fc, delays);

                PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                        union, prefix, CHUNK_SIZE, effectiveTarget);

                long crc      = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                byte[] manifest = buildReduceManifest(tileIdx, crc, playerName, flags, fc, delays);
                byte[] payload  = fc > 1
                        ? PayloadManifest.interleaveFlat(fit.mapColors, fc) : fit.mapColors;

                client.execute(() -> {
                    try {
                        PayloadState.TileData liveTile = PayloadState.tiles.get(tileIdx);
                        int newCount = saveTileData(tileIdx, manifest, payload);
                        liveTile.frameCount = fc;
                        PayloadState.syncFromActiveTile();
                        PayloadState.save();

                        int remaining = fit.originalDistinctColors - fit.colorsRemoved;
                        source.sendFeedback(Text.literal(String.format(
                                "§6=== Reduced %s%s ===", PayloadState.tileLabel(tileIdx),
                                fc > 1 ? " (" + fc + " frames)" : "")));
                        if (isCarpet) {
                            int newBytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            boolean stillOver = newBytes > CarpetChannel.MAX_TOTAL_BYTES;
                            source.sendFeedback(Text.literal(String.format(
                                    "§7Compressed: %s%d bytes §7(carpet, %d overflow banner%s)%s",
                                    stillOver ? "§c" : "§a", newBytes,
                                    newCount - 1, newCount == 2 ? "" : "s",
                                    stillOver ? String.format(" §c[over budget by %d — try fewer colors]",
                                            newBytes - CarpetChannel.MAX_TOTAL_BYTES) : "")));
                        } else {
                            source.sendFeedback(Text.literal(String.format(
                                    "§7Banners: §e%d §7→ §a%d", oldCount, newCount)));
                        }
                        source.sendFeedback(Text.literal(String.format(
                                "§7Colors:  §e%d §7→ §a%d §7(%d removed, across all frames)",
                                fit.originalDistinctColors, remaining, fit.colorsRemoved)));
                        source.sendFeedback(Text.literal(String.format(
                                "§7Pixels affected: §f%d §7(%.1f%% per frame)",
                                fit.pixelsAffected / fc, 100.0 * fit.pixelsAffected / fc / MAP_BYTES)));
                        source.sendFeedback(Text.literal(
                                "§7Use §f/loominary preview§7 to inspect. "
                                        + "§f/loominary reduce undo§7 to revert."));
                    } finally {
                        importInProgress = false;
                    }
                });
            } catch (Exception e) {
                client.execute(() -> {
                    source.sendError(Text.literal("§cReduction failed: " + e.getMessage()));
                    e.printStackTrace();
                    importInProgress = false;
                });
            }
        });
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int reduceOneColors(FabricClientCommandSource source, int targetColors) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        int tileIdx = PayloadState.activeTileIndex;
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);

        // Snapshot before going async.
        final List<String> chunksSnap  = new ArrayList<>(PayloadState.ACTIVE_CHUNKS);
        final PayloadState.TileData tileSnap = snapshotTile(tile);
        final String playerName        = source.getPlayer().getGameProfile().getName();
        final int flags                = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Reducing " + PayloadState.tileLabel(tileIdx) + "..."));

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            try {
                byte[][] frames = resolveAllFramesForTile(tileSnap, chunksSnap);
                int fc       = frames.length;
                byte[] union = mergeFrames(frames);
                int currentColors = PngToMapColors.countDistinct(union);

                if (currentColors <= targetColors) {
                    client.execute(() -> {
                        source.sendFeedback(Text.literal(String.format(
                                "§aTile already has %d distinct colors (≤ %d). No reduction needed.",
                                currentColors, targetColors)));
                        importInProgress = false;
                    });
                    return;
                }

                // Save undo state (on game thread these were already snapshotted synchronously above,
                // but the preReduction maps need updating before the background result is committed).
                int[] delays  = tileDelays(tileSnap, fc);
                byte[] prefix = buildReduceManifest(tileIdx, 0L, playerName, flags, fc, delays);

                PngToMapColors.FitResult fit = PngToMapColors.reduceToColorCount(
                        union, prefix, CHUNK_SIZE, targetColors);

                long crc       = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                byte[] manifest = buildReduceManifest(tileIdx, crc, playerName, flags, fc, delays);
                byte[] payload  = fc > 1
                        ? PayloadManifest.interleaveFlat(fit.mapColors, fc) : fit.mapColors;

                client.execute(() -> {
                    try {
                        // Save undo snapshot here so it's only written if we actually reduce.
                        preReductionChunks.put(tileIdx, new ArrayList<>(PayloadState.ACTIVE_CHUNKS));
                        PayloadState.TileData liveTile = PayloadState.tiles.get(tileIdx);
                        if (liveTile.carpetEncoded && liveTile.carpetCompressedB64 != null)
                            preReductionCarpetB64.put(tileIdx, liveTile.carpetCompressedB64);

                        int newCount  = saveTileData(tileIdx, manifest, payload);
                        liveTile.frameCount = fc;
                        int newColors = fit.originalDistinctColors - fit.colorsRemoved;
                        PayloadState.syncFromActiveTile();
                        PayloadState.save();

                        source.sendFeedback(Text.literal(String.format(
                                "§6=== Reduced %s%s ===", PayloadState.tileLabel(tileIdx),
                                fc > 1 ? " (" + fc + " frames)" : "")));
                        source.sendFeedback(Text.literal(String.format(
                                "§7Colors:  §e%d §7→ §a%d §7(%d removed, across all frames)",
                                fit.originalDistinctColors, newColors, fit.colorsRemoved)));
                        if (liveTile.carpetEncoded) {
                            int bytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            boolean stillOver = bytes > CarpetChannel.MAX_TOTAL_BYTES;
                            source.sendFeedback(Text.literal(String.format(
                                    "§7Compressed: %s%d bytes%s",
                                    stillOver ? "§c" : "§a", bytes,
                                    stillOver ? String.format(" §c[over budget by %d]",
                                            bytes - CarpetChannel.MAX_TOTAL_BYTES) : "")));
                        } else {
                            source.sendFeedback(Text.literal(String.format("§7Banners: §f%d", newCount)));
                        }
                        source.sendFeedback(Text.literal(String.format(
                                "§7Pixels affected: §f%d §7(%.1f%% per frame)",
                                fit.pixelsAffected / fc, 100.0 * fit.pixelsAffected / fc / MAP_BYTES)));
                        source.sendFeedback(Text.literal(
                                "§7Use §f/loominary preview§7 to inspect. "
                                        + "§f/loominary reduce undo§7 to revert."));
                    } finally {
                        importInProgress = false;
                    }
                });
            } catch (Exception e) {
                client.execute(() -> {
                    source.sendError(Text.literal("§cReduction failed: " + e.getMessage()));
                    importInProgress = false;
                });
            }
        });
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int reduceAll(FabricClientCommandSource source, int targetBanners) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        PayloadState.syncToActiveTile();

        // Snapshot all tile data before releasing the game thread.
        final String playerName = source.getPlayer().getGameProfile().getName();
        final int flags         = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
        final int tileCount     = PayloadState.tiles.size();
        final List<List<String>> allChunks   = new ArrayList<>();
        final List<PayloadState.TileData> allSnaps = new ArrayList<>();
        final List<Integer> oldSizes         = new ArrayList<>();
        for (int i = 0; i < tileCount; i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            allChunks.add(new ArrayList<>(tile.chunks));
            allSnaps.add(snapshotTile(tile));
            oldSizes.add(tile.chunks.size());
            preReductionChunks.putIfAbsent(i, new ArrayList<>(tile.chunks));
            if (tile.carpetEncoded && tile.carpetCompressedB64 != null)
                preReductionCarpetB64.putIfAbsent(i, tile.carpetCompressedB64);
        }

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Reducing all tiles..."));

        // Per-tile results: null = skipped, non-null = [manifest, mapColors, fc, note]
        record TileResult(byte[] manifest, byte[] mapColors, int fc, String note) {}

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            List<TileResult> results = new ArrayList<>();
            int totalColorsRemoved = 0, totalPixelsAffected = 0, tilesReduced = 0;

            for (int i = 0; i < tileCount; i++) {
                PayloadState.TileData tileSnap = allSnaps.get(i);
                List<String> chunks = allChunks.get(i);
                if (chunks.isEmpty()) { results.add(null); continue; }
                int tileTarget = tileSnap.carpetEncoded ? MAX_CHUNKS_CARPET : targetBanners;
                if (!tileSnap.carpetEncoded && chunks.size() <= targetBanners) {
                    results.add(null); continue;
                }
                try {
                    byte[][] frames = resolveAllFramesForTile(tileSnap, chunks);
                    int fc      = frames.length;
                    byte[] union = mergeFrames(frames);
                    int[] delays = tileDelays(tileSnap, fc);
                    byte[] prefix = buildReduceManifest(i, 0L, playerName, flags, fc, delays);
                    PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                            union, prefix, CHUNK_SIZE, tileTarget);
                    long crc      = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                    byte[] manifest = buildReduceManifest(i, crc, playerName, flags, fc, delays);
                    byte[] payload  = fc > 1
                            ? PayloadManifest.interleaveFlat(fit.mapColors, fc) : fit.mapColors;
                    int oldSz = oldSizes.get(i);
                    String note = tileSnap.carpetEncoded
                            ? String.format("carpet, %d colors merged (committing...)", fit.colorsRemoved)
                            : String.format("§e%d §7→ §a? §7banners, %d colors merged", oldSz, fit.colorsRemoved);
                    results.add(new TileResult(manifest, payload, fc, note));
                    tilesReduced++;
                    totalColorsRemoved  += fit.colorsRemoved;
                    totalPixelsAffected += fit.pixelsAffected;
                } catch (Exception e) {
                    results.add(new TileResult(null, null, 1, "§cfailed: " + e.getMessage()));
                }
            }

            final int finalReduced = tilesReduced;
            final int finalColors  = totalColorsRemoved;
            final int finalPixels  = totalPixelsAffected;

            client.execute(() -> {
                try {
                    if (finalReduced == 0) {
                        source.sendFeedback(Text.literal(String.format(
                                "§aAll tiles already fit in %d banners. No reduction needed.",
                                targetBanners)));
                        return;
                    }

                    source.sendFeedback(Text.literal(String.format(
                            "§6=== Reduced All Tiles (target: %d banners / carpet capacity) ===",
                            targetBanners)));

                    for (int i = 0; i < results.size(); i++) {
                        TileResult r = results.get(i);
                        if (r == null || r.manifest() == null) continue;
                        PayloadState.TileData liveTile = PayloadState.tiles.get(i);
                        int oldSz = oldSizes.get(i);
                        int newCount = saveTileData(i, r.manifest(), r.mapColors());
                        liveTile.frameCount = r.fc();
                        String note;
                        if (liveTile.carpetEncoded) {
                            int bytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            boolean still = bytes > CarpetChannel.MAX_TOTAL_BYTES;
                            note = String.format("§a%d bytes%s", bytes,
                                    still ? String.format(" §c[still over budget by %d]",
                                            bytes - CarpetChannel.MAX_TOTAL_BYTES) : "");
                        } else {
                            note = String.format("§e%d §7→ §a%d §7banners", oldSz, newCount);
                        }
                        source.sendFeedback(Text.literal(String.format("§7  %s: %s",
                                PayloadState.tileLabel(i), note)));
                    }
                    // Show failed tiles
                    for (int i = 0; i < results.size(); i++) {
                        TileResult r = results.get(i);
                        if (r != null && r.manifest() == null)
                            source.sendFeedback(Text.literal(String.format("§7  %s: %s",
                                    PayloadState.tileLabel(i), r.note())));
                    }

                    PayloadState.syncFromActiveTile();
                    PayloadState.save();

                    source.sendFeedback(Text.literal(String.format(
                            "§7%d tile%s reduced. Total: %d colors merged, %d pixels affected.",
                            finalReduced, finalReduced == 1 ? "" : "s", finalColors, finalPixels)));
                    source.sendFeedback(Text.literal(
                            "§7Use §f/loominary tile <n>§7 + §f/loominary reduce undo§7 to restore individual tiles."));
                } finally {
                    importInProgress = false;
                }
            });
        });
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int reduceAllColors(FabricClientCommandSource source, int targetColors) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        PayloadState.syncToActiveTile();

        // Snapshot all tile data before releasing the game thread.
        final String playerName = source.getPlayer().getGameProfile().getName();
        final int flags         = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
        final int tileCount     = PayloadState.tiles.size();
        final List<List<String>> allChunks   = new ArrayList<>();
        final List<PayloadState.TileData> allSnaps = new ArrayList<>();
        for (int i = 0; i < tileCount; i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            allChunks.add(new ArrayList<>(tile.chunks));
            allSnaps.add(snapshotTile(tile));
            preReductionChunks.putIfAbsent(i, new ArrayList<>(tile.chunks));
            if (tile.carpetEncoded && tile.carpetCompressedB64 != null)
                preReductionCarpetB64.putIfAbsent(i, tile.carpetCompressedB64);
        }

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Reducing all tiles (by color count)..."));

        record TileResult(byte[] manifest, byte[] mapColors, int fc,
                          int origColors, int newColors, String noteExtra) {}

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            List<TileResult> results = new ArrayList<>();
            int totalColorsRemoved = 0, totalPixelsAffected = 0, tilesReduced = 0;

            for (int i = 0; i < tileCount; i++) {
                PayloadState.TileData tileSnap = allSnaps.get(i);
                List<String> chunks = allChunks.get(i);
                if (chunks.isEmpty()) { results.add(null); continue; }
                try {
                    byte[][] frames = resolveAllFramesForTile(tileSnap, chunks);
                    int fc       = frames.length;
                    byte[] union = mergeFrames(frames);
                    int current  = PngToMapColors.countDistinct(union);
                    if (current <= targetColors) { results.add(null); continue; }
                    int[] delays  = tileDelays(tileSnap, fc);
                    byte[] prefix = buildReduceManifest(i, 0L, playerName, flags, fc, delays);
                    PngToMapColors.FitResult fit = PngToMapColors.reduceToColorCount(
                            union, prefix, CHUNK_SIZE, targetColors);
                    long crc       = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                    byte[] manifest = buildReduceManifest(i, crc, playerName, flags, fc, delays);
                    byte[] payload  = fc > 1
                            ? PayloadManifest.interleaveFlat(fit.mapColors, fc) : fit.mapColors;
                    results.add(new TileResult(manifest, payload, fc,
                            current, current - fit.colorsRemoved, null));
                    tilesReduced++;
                    totalColorsRemoved  += fit.colorsRemoved;
                    totalPixelsAffected += fit.pixelsAffected;
                } catch (Exception e) {
                    results.add(new TileResult(null, null, 1, 0, 0, "§cfailed: " + e.getMessage()));
                }
            }

            final int finalReduced = tilesReduced;
            final int finalColors  = totalColorsRemoved;
            final int finalPixels  = totalPixelsAffected;

            client.execute(() -> {
                try {
                    if (finalReduced == 0) {
                        source.sendFeedback(Text.literal(String.format(
                                "§aAll tiles already at or below %d distinct colors. No reduction needed.",
                                targetColors)));
                        return;
                    }

                    source.sendFeedback(Text.literal(String.format(
                            "§6=== Reduced All Tiles (target: %d colors) ===", targetColors)));

                    for (int i = 0; i < results.size(); i++) {
                        TileResult r = results.get(i);
                        if (r == null) continue;
                        if (r.manifest() == null) {
                            source.sendFeedback(Text.literal(String.format("§7  %s: %s",
                                    PayloadState.tileLabel(i), r.noteExtra())));
                            continue;
                        }
                        PayloadState.TileData liveTile = PayloadState.tiles.get(i);
                        int newCount = saveTileData(i, r.manifest(), r.mapColors());
                        liveTile.frameCount = r.fc();
                        String note;
                        if (liveTile.carpetEncoded) {
                            int bytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            boolean still = bytes > CarpetChannel.MAX_TOTAL_BYTES;
                            note = String.format("§e%d §7→ §a%d §7colors, %d bytes%s",
                                    r.origColors(), r.newColors(), bytes,
                                    still ? String.format(" §c[still over budget by %d]",
                                            bytes - CarpetChannel.MAX_TOTAL_BYTES) : "");
                        } else {
                            note = String.format("§e%d §7→ §a%d §7colors, %d banners",
                                    r.origColors(), r.newColors(), newCount);
                        }
                        source.sendFeedback(Text.literal(String.format("§7  %s: %s",
                                PayloadState.tileLabel(i), note)));
                    }

                    PayloadState.syncFromActiveTile();
                    PayloadState.save();

                    source.sendFeedback(Text.literal(String.format(
                            "§7%d tile%s reduced. Total: %d colors merged, %d pixels affected.",
                            finalReduced, finalReduced == 1 ? "" : "s", finalColors, finalPixels)));
                    source.sendFeedback(Text.literal(
                            "§7Use §f/loominary tile <n>§7 + §f/loominary reduce undo§7 to restore individual tiles."));
                } finally {
                    importInProgress = false;
                }
            });
        });
        t.setDaemon(true);
        t.start();
        return 1;
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

        int reducedSize = PayloadState.ACTIVE_CHUNKS.size();

        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        String savedB64 = preReductionCarpetB64.remove(tileIdx);
        if (tile.carpetEncoded && savedB64 != null) tile.carpetCompressedB64 = savedB64;

        PayloadState.ACTIVE_CHUNKS.clear();
        PayloadState.ACTIVE_CHUNKS.addAll(saved);
        PayloadState.activeChunkIndex = 0;
        PayloadState.save();

        source.sendFeedback(Text.literal(String.format(
                "§aUndid reduction on %s: §e%d §7→ §f%d §7chunks restored.",
                PayloadState.tileLabel(tileIdx),
                reducedSize, saved.size())));
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
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64) {
            source.sendFeedback(Text.literal(
                    "§e⚠ Title exceeds 64 UTF-8 bytes and will be truncated in the manifest."));
        }

        PayloadState.currentTitle = text;
        titleIsUserSet = true;

        if (PayloadState.tiles.isEmpty()) {
            PayloadState.save();
            source.sendFeedback(Text.literal("§aTitle set: §f" + text
                    + " §7(will be embedded on next import)"));
            return 1;
        }

        // Re-encode all existing tiles with the new title.
        String playerName = source.getPlayer().getGameProfile().getName();
        PayloadState.syncToActiveTile();
        int reEncoded = 0;
        boolean anyCarpet = false;

        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            if (tile.chunks.isEmpty()) continue;
            try {
                byte[] full = resolveFullPayloadForTile(tile, tile.chunks);
                int flags;
                byte[] payloadBytes;
                byte[] manifestBytes;

                if (full.length == MAP_BYTES) {
                    // v0 static tile — no manifest, wrap with one
                    payloadBytes = full;
                    flags = 0;
                    manifestBytes = PayloadManifest.toBytes(
                            flags, PayloadState.gridColumns, PayloadState.gridRows,
                            PayloadState.tileCol(i), PayloadState.tileRow(i),
                            PayloadManifest.crc32(payloadBytes), playerName, text);
                } else {
                    PayloadManifest manifest = PayloadManifest.fromBytes(full);
                    payloadBytes = Arrays.copyOfRange(full, manifest.headerSize, full.length);
                    flags = manifest.flags;
                    long crc = PayloadManifest.crc32(Arrays.copyOf(payloadBytes, MAP_BYTES));
                    if (manifest.frameCount > 1) {
                        manifestBytes = PayloadManifest.toBytes(
                                flags, PayloadState.gridColumns, PayloadState.gridRows,
                                PayloadState.tileCol(i), PayloadState.tileRow(i),
                                crc, playerName, text, tile.nonce,
                                manifest.frameCount, manifest.loopCount, manifest.frameDelays);
                    } else {
                        manifestBytes = PayloadManifest.toBytes(
                                flags, PayloadState.gridColumns, PayloadState.gridRows,
                                PayloadState.tileCol(i), PayloadState.tileRow(i),
                                crc, playerName, text, tile.nonce);
                    }
                }
                saveTileData(i, manifestBytes, payloadBytes);
                reEncoded++;
                if (tile.carpetEncoded) anyCarpet = true;
            } catch (Exception e) {
                source.sendFeedback(Text.literal("§e⚠ Tile " + i + " re-encode failed: " + e.getMessage()));
            }
        }

        PayloadState.syncFromActiveTile();
        PayloadState.save();

        source.sendFeedback(Text.literal("§aTitle set: §f" + text));
        source.sendFeedback(Text.literal(String.format(
                "§7Re-encoded %d tile%s with new title.", reEncoded, reEncoded == 1 ? "" : "s")));
        if (anyCarpet) {
            source.sendFeedback(Text.literal(
                    "§e⚠ Carpet tiles changed — run §f/loominary export§e to update schematics."));
        }
        int doneCount = PayloadState.activeChunkIndex;
        if (doneCount > 0) {
            source.sendFeedback(Text.literal(String.format(
                    "§e⚠ %d chunk%s already placed for the active tile are now orphaned.",
                    doneCount, doneCount == 1 ? " is" : "s are")));
        }
        return 1;
    }

    private static int titleClear(FabricClientCommandSource source) {
        PayloadState.currentTitle = null;
        titleIsUserSet = false;
        PayloadState.save();
        source.sendFeedback(Text.literal("§aTitle cleared."));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // stride [n]
    // ════════════════════════════════════════════════════════════════════

    private static int strideSet(FabricClientCommandSource source, int n) {
        importStride = n;
        if (n == 1) {
            source.sendFeedback(Text.literal("§aFrame stride set to 1 (all frames)."));
        } else {
            source.sendFeedback(Text.literal(String.format(
                    "§aFrame stride set to §f%d §7— next GIF import will keep every %dth frame.", n, n)));
        }
        return 1;
    }

    private static int strideReset(FabricClientCommandSource source) {
        importStride = 1;
        source.sendFeedback(Text.literal("§aFrame stride reset to 1 (all frames)."));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // export [name]
    // ════════════════════════════════════════════════════════════════════

    private static int exportSchematic(FabricClientCommandSource source, String nameArg) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }

        int tileIdx = PayloadState.activeTileIndex;
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);

        try {
            // Resolve the schematic base name.
            String name = (nameArg != null && !nameArg.isEmpty()) ? nameArg
                    : SchematicExporter.resolveSchematicName(
                            PayloadState.currentTitle != null ? PayloadState.currentTitle
                                    + (PayloadState.totalTiles() > 1 ? "_tile" + tileIdx : "")
                                    : null);

            if (tile.carpetEncoded) {
                // Re-export the carpet schematic from stored compressed data.
                if (tile.carpetCompressedB64 == null) {
                    source.sendError(Text.literal("§cCarpet tile has no stored data — re-import."));
                    return 0;
                }
                byte[] compressed = Base64.getDecoder().decode(tile.carpetCompressedB64);
                int totalBytes = compressed.length;
                int carpetBytes = Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES);
                byte[] nibbles = CarpetChannel.encodeNibbles(compressed, carpetBytes);

                Path output = SchematicExporter.exportCarpetTile(nibbles, carpetBytes, name);
                int carpetRows = (carpetBytes * 2 + 127) / 128;
                int overflowBanners = tile.chunks.size() - 1;

                source.sendFeedback(Text.literal(String.format(
                        "§aExported §f%s §a(%d carpet rows × 128, %d compressed bytes):",
                        name + ".litematic", carpetRows, totalBytes)));
                source.sendFeedback(Text.literal("§7  " + output.getFileName()));
                if (overflowBanners > 0) {
                    source.sendFeedback(Text.literal(String.format(
                            "§7  + %d overflow banner%s still needed (rename at anvil).",
                            overflowBanners, overflowBanners == 1 ? "" : "s")));
                }
                return 1;
            }

            // Legacy banner schematic.
            int count = PayloadState.ACTIVE_CHUNKS.size();
            String description = String.format(
                    "Loominary tile %d (col %d, row %d) — %d named banners.",
                    tileIdx, PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx), count);

            Path output = SchematicExporter.exportTile(
                    new ArrayList<>(PayloadState.ACTIVE_CHUNKS), name, description);

            int gridH = (count + 15) / 16;
            source.sendFeedback(Text.literal(String.format(
                    "§aExported §f%s §a(%d banners, 16×%d grid):",
                    name + ".litematic", count, gridH)));
            source.sendFeedback(Text.literal("§7  " + output.getFileName()));
            source.sendFeedback(Text.literal(
                    "§7Each ghost banner's custom name shows which renamed banner to place there."));
            return 1;

        } catch (IOException e) {
            source.sendError(Text.literal("§cExport failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // edit
    // ════════════════════════════════════════════════════════════════════

    private static int edit(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal(
                    "§cNo active batch. Run §f/loominary import§c first."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks to edit."));
            return 0;
        }
        try {
            int tileIdx = PayloadState.activeTileIndex;
            PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
            byte[][] frames = resolveAllFramesForTile(tile, PayloadState.ACTIVE_CHUNKS);
            String label = PayloadState.tileLabel(tileIdx);

            MinecraftClient.getInstance().send(() ->
                    MinecraftClient.getInstance().setScreen(
                            new net.zerohpminecraft.MapEditorScreen(frames, tileIdx, label)));
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§cFailed to decode tile: " + e.getMessage()));
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // dither [all] [colors <n>]
    // ════════════════════════════════════════════════════════════════════

    private static int dither(FabricClientCommandSource source, boolean all, int targetColors) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.currentSourceFilename == null) {
            source.sendError(Text.literal(
                    "§cNo source file on record. Stolen tiles cannot be re-encoded with dithering."));
            return 0;
        }
        // Carpet tiles can be re-dithered; the loop below handles both modes.

        MinecraftClient client = MinecraftClient.getInstance();
        Path filePath = client.runDirectory.toPath()
                .resolve("loominary_data")
                .resolve(PayloadState.currentSourceFilename);

        if (!Files.exists(filePath)) {
            source.sendError(Text.literal("§cSource file not found: loominary_data/"
                    + PayloadState.currentSourceFilename
                    + "\n§cMove it back to re-encode with dithering."));
            return 0;
        }

        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (img == null) {
                source.sendError(Text.literal("§cCouldn't decode source image."));
                return 0;
            }

            int totalW = PayloadState.gridColumns * MAP_SIZE;
            int totalH = PayloadState.gridRows    * MAP_SIZE;
            BufferedImage scaled = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, totalW, totalH, null);
            g.dispose();

            PayloadState.syncToActiveTile();

            String playerName = source.getPlayer().getGameProfile().getName();
            int flags     = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
            boolean legal = !PayloadState.allShades;

            int first = all ? 0 : PayloadState.activeTileIndex;
            int last  = all ? PayloadState.tiles.size() - 1 : PayloadState.activeTileIndex;

            // Process all affected tiles as a single image so palette selection,
            // the Otsu dithering-strength map, and error diffusion are consistent
            // across tile boundaries. For a single active tile use the 1×1 path.
            byte[][] ditheredTiles;
            if (all) {
                ditheredTiles = PngToMapColors.convertTwoPassGrid(
                        scaled, legal, targetColors, true,
                        PayloadState.gridColumns, PayloadState.gridRows);
            } else {
                int col = PayloadState.tileCol(PayloadState.activeTileIndex);
                int row = PayloadState.tileRow(PayloadState.activeTileIndex);
                ditheredTiles = new byte[PayloadState.tiles.size()][];
                ditheredTiles[PayloadState.activeTileIndex] = PngToMapColors.convertTwoPass(
                        scaled.getSubimage(col * MAP_SIZE, row * MAP_SIZE, MAP_SIZE, MAP_SIZE),
                        legal, targetColors, true);
            }

            int tilesEncoded = 0;
            List<String> notes = new ArrayList<>();

            for (int i = 0; i < PayloadState.tiles.size(); i++) {
                if (i < first || i > last) { notes.add(null); continue; }
                PayloadState.TileData tile = PayloadState.tiles.get(i);

                preReductionChunks.putIfAbsent(i, new ArrayList<>(tile.chunks));
                if (tile.carpetEncoded && tile.carpetCompressedB64 != null)
                    preReductionCarpetB64.putIfAbsent(i, tile.carpetCompressedB64);

                byte[] mapColors = ditheredTiles[i];
                int tCol = PayloadState.tileCol(i);
                int tRow = PayloadState.tileRow(i);

                int effectiveBudget = tile.carpetEncoded ? MAX_CHUNKS_CARPET : MAX_CHUNKS;
                byte[] manifest0 = PayloadManifest.toBytes(flags,
                        PayloadState.gridColumns, PayloadState.gridRows,
                        tCol, tRow, 0L, playerName, PayloadState.currentTitle, tile.nonce);
                String budgetNote = "";
                // For banner tiles check banner count; for carpet tiles check compressed size.
                if (!tile.carpetEncoded && buildChunks(manifest0, mapColors).size() > MAX_CHUNKS) {
                    PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                            mapColors, manifest0, CHUNK_SIZE, MAX_CHUNKS);
                    mapColors = fit.mapColors;
                    budgetNote = String.format(", §ebudget reduced %d colors", fit.colorsRemoved);
                } else if (tile.carpetEncoded) {
                    try { buildCarpetEncoding(manifest0, mapColors); }
                    catch (IllegalStateException overflow) {
                        PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                                mapColors, manifest0, CHUNK_SIZE, MAX_CHUNKS_CARPET);
                        mapColors = fit.mapColors;
                        budgetNote = String.format(", §ebudget reduced %d colors", fit.colorsRemoved);
                    }
                }

                byte[] manifest = PayloadManifest.toBytes(flags,
                        PayloadState.gridColumns, PayloadState.gridRows,
                        tCol, tRow, PayloadManifest.crc32(mapColors),
                        playerName, PayloadState.currentTitle, tile.nonce);
                int newCount = saveTileData(i, manifest, mapColors);
                tilesEncoded++;

                notes.add(tile.carpetEncoded
                        ? String.format("%d bytes, %d colors%s",
                                Base64.getDecoder().decode(tile.carpetCompressedB64).length,
                                PngToMapColors.countDistinct(mapColors), budgetNote)
                        : String.format("%d banners, %d colors%s",
                                newCount, PngToMapColors.countDistinct(mapColors), budgetNote));
            }

            PayloadState.dither = true;
            PayloadState.syncFromActiveTile();
            PayloadState.save();

            String colorNote = targetColors > 0
                    ? String.format(" (palette: %d colors)", targetColors) : "";
            source.sendFeedback(Text.literal(String.format(
                    "§6=== Dithered %d tile%s%s ===",
                    tilesEncoded, tilesEncoded == 1 ? "" : "s", colorNote)));
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i) != null)
                    source.sendFeedback(Text.literal(String.format("§7  %s: §f%s",
                            PayloadState.tileLabel(i), notes.get(i))));
            }
            source.sendFeedback(Text.literal(
                    "§7Use §f/loominary preview§7 to inspect. "
                            + "§f/loominary reduce undo§7 reverts the active tile."));
            return tilesEncoded > 0 ? 1 : 0;

        } catch (IOException e) {
            source.sendError(Text.literal("§cError reading source file: " + e.getMessage()));
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // resalt
    // ════════════════════════════════════════════════════════════════════

    private static int resalt(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) {
            source.sendError(Text.literal("§cActive tile has no chunks."));
            return 0;
        }
        int tileIdx   = PayloadState.activeTileIndex;
        int doneCount = PayloadState.activeChunkIndex;

        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        boolean isCarpet = tile.carpetEncoded;
        try {
            byte[][] frames = resolveAllFramesForTile(tile, PayloadState.ACTIVE_CHUNKS);
            int fc = frames.length;
            byte[] sequential = mergeFrames(frames);
            byte[] fullData = fc > 1 ? PayloadManifest.interleaveFlat(sequential, fc) : sequential;

            int nonce;
            do { nonce = ThreadLocalRandom.current().nextInt(); } while (nonce == 0);

            String playerName = source.getPlayer().getGameProfile().getName();
            int flags = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
            int[] delays = tileDelays(tile, fc);
            long crc = PayloadManifest.crc32(Arrays.copyOf(sequential, MAP_BYTES));
            tile.nonce = nonce; // must be set before buildReduceManifest reads it
            byte[] manifestBytes = buildReduceManifest(tileIdx, crc, playerName, flags, fc, delays);
            int newCount = saveTileData(tileIdx, manifestBytes, fullData);
            tile.frameCount = fc;
            PayloadState.syncFromActiveTile();
            PayloadState.save();

            AnvilAutoFillHandler.clearHalt();

            source.sendFeedback(Text.literal(String.format(
                    "§a%s re-encoded with new %s (%d %s).",
                    PayloadState.tileLabel(tileIdx),
                    isCarpet ? "carpet pattern and LC banner" : "chunk names",
                    newCount,
                    isCarpet ? "chunks" : "banners")));
            if (isCarpet) {
                source.sendFeedback(Text.literal(
                        "§e⚠ Carpet pattern has changed — run §f/loominary export§e to get the updated schematic."));
            }
            if (doneCount > 0) {
                source.sendFeedback(Text.literal(String.format(
                        "§e⚠ %d %s already renamed for this tile are now orphaned — discard them before placing the maps.",
                        doneCount, doneCount == 1 ? "banner/chunk is" : "banners/chunks are")));
            }
            if (preReductionChunks.containsKey(tileIdx)) {
                preReductionChunks.remove(tileIdx);
                preReductionCarpetB64.remove(tileIdx);
                source.sendFeedback(Text.literal("§e⚠ Reduction undo state for this tile was cleared."));
            }
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§cResalt failed: " + e.getMessage()));
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
        preReductionCarpetB64.clear();
        titleIsUserSet = false;
        BannerAutoClickHandler.stop();
        BannerAutoClickHandler.clearMarkers();
        source.sendFeedback(Text.literal("§aCleared all state."));
        return 1;
    }

    private static int clearMemory(FabricClientCommandSource source) {
        PayloadState.clearMemory();
        originalColors.clear();
        preReductionChunks.clear();
        preReductionCarpetB64.clear();
        titleIsUserSet = false;
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