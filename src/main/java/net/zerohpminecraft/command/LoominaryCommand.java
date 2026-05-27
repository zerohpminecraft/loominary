package net.zerohpminecraft.command;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BannerItem;
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
import net.zerohpminecraft.CodecMode;
import net.zerohpminecraft.MapBannerDecoder;
import net.zerohpminecraft.MapEncryption;
import net.zerohpminecraft.PayloadManifest;
import net.zerohpminecraft.PayloadState;
import net.zerohpminecraft.PngToMapColors;
import net.zerohpminecraft.SchematicExporter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import net.zerohpminecraft.CjkCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

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
 * /loominary filter smooth [all] [radius <r>]   — Gaussian blur applied in-place (default r=1.5)
 * /loominary filter median [all] [radius <r>]   — edge-preserving median in-place (default r=1)
 * /loominary filter sharpen [all] [amount <a>]  — unsharp mask in-place (default a=0.8)
 * /loominary filter posterize [all] <levels>    — posterize tones in-place (2–16)
 * /loominary palette [all] — color stats + rarity histogram for active tile (or all tiles)
 * /loominary reduce [all] [<n>] — reduce tile(s) to n banners (default 255)
 * /loominary reduce [all] colors <n> — reduce tile(s) to n distinct colors
 * /loominary reduce strategy <rarest|closest|weighted> — set reduction algorithm
 * /loominary reduce undo — restore active tile to pre-reduction state
 * /loominary reduce undo all — restore all tiles to pre-reduction state
 * /loominary click — toggle auto-right-click of banners while holding map
 * /loominary click stop — stop auto-clicking
 * /loominary whitelist — show how many named banners are whitelisted for reuse
 * /loominary whitelist add — scan inventory + bundle contents, mark every named banner reusable
 * /loominary whitelist clear — empty the whitelist
 * /loominary mux — append blank donor tiles to absorb overflow from over-budget art tiles
 * /loominary mux undo — remove all donor tiles and reset mux state (may leave batch over-budget)
 * /loominary export [name] — write a Litematica .litematic for active tile
 * /loominary clear [memory|disk] — clear state
 */
public class LoominaryCommand {

    private static final int CHUNK_SIZE = 48;
    private static final int MAX_CHUNKS = 63; // hard limit observed on 2b2t

    /**
     * Chooses a zstd compression level appropriate for the payload size.
     *
     * <p>zstd level 22 requires native working memory proportional to the window size
     * (which scales with input size), easily reaching 500 MB–2 GB for large animated
     * payloads.  When that native allocation fails inside JNI, zstd-jni cannot raise a
     * Java exception — the native thread crashes and takes the LWJGL window with it.
     *
     * <p>Level 9 and below use a fixed-size window (≤8 MB) regardless of input size,
     * so native memory stays bounded.  The compression-ratio loss vs. level 22 on map
     * color data is typically &lt;5%.
     *
     * <ul>
     *   <li>&lt; 256 KB → level 22 (max; safe for small payloads)</li>
     *   <li>256 KB – 4 MB → level 9</li>
     *   <li>&gt; 4 MB → level 3 (fast; bounded native memory)</li>
     * </ul>
     */
    private static int compressionLevel(int payloadBytes) {
        if (payloadBytes < 256_000)  return Zstd.maxCompressionLevel(); // safe
        if (payloadBytes < 4_000_000) return 9;
        return 3;
    }

    /**
     * Compresses {@code data} using zstd, automatically enabling long-range matching
     * for payloads ≥ 1 MB.  Long-range mode sets the back-reference window to cover
     * the entire payload (up to 128 MB), so all frames of a large animated tile can
     * reference each other — compared to the default ~4 MB window (≈ 256 frames) used
     * by level 3.  The compressed format is fully standard zstd; the decompressor
     * reads the window size from the frame header and handles any size automatically.
     *
     * <p>Each tile is compressed independently, so multi-tile murals remain independently
     * decodable without requiring sibling tiles to be present.
     */
    private static byte[] compress(byte[] data) {
        int len  = data.length;
        int level = compressionLevel(len);
        if (len >= 1 << 20) { // 1 MB+: enable long-range matching
            // Long-range mode allocates ~128 MB of native memory and blocks for several
            // seconds on large payloads.  Never run it on the render thread — it would
            // freeze the LWJGL window long enough for the OS to kill it.
            // Background threads (imports, reduce, dither) are safe.
            // Detect render/game thread by name — Minecraft's thread is "Render thread".
            boolean onRenderThread = Thread.currentThread().getName().startsWith("Render thread");
            if (!onRenderThread) {
                // windowLog = ceil(log2(len)), capped at 27 (128 MB).
                int windowLog = Math.min(27, 32 - Integer.numberOfLeadingZeros(len - 1));
                try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                    ctx.setLevel(level);
                    ctx.setLong(windowLog);
                    return ctx.compress(data);
                }
            }
        }
        return Zstd.compress(data, level);
    }

    private static final ExecutorService SAVE_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "loominary-save");
        t.setDaemon(true);
        return t;
    });
    /** Non-zero while async editor saves are in flight; blocks /loominary edit re-entry. */
    public static final AtomicInteger pendingSaves = new AtomicInteger(0);
    // Carpet budget in "chunks" units so reduceToFit can use the same path.
    // Legacy LC/LS format (MAX_TOTAL_BYTES):
    private static final int MAX_CHUNKS_CARPET =
            (int) Math.ceil(CarpetChannel.MAX_TOTAL_BYTES * 4.0 / 3.0 / CHUNK_SIZE); // ≈ 291
    // LOOM format (MAX_TOTAL_BYTES_LOOM = carpet+shade+overflow):
    private static final int MAX_CHUNKS_CARPET_LOOM =
            (int) Math.ceil(CarpetChannel.MAX_TOTAL_BYTES_LOOM * 4.0 / 3.0 / CHUNK_SIZE); // ≈ 293
    private static final int MAP_SIZE = 128;
    private static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;

    /** Returns the maximum compressed-payload bytes a tile can hold given the current codec mode. */
    private static int maxBytesForMode(CodecMode mode) {
        return switch (mode) {
            case BANNER               -> MAX_CHUNKS * 84 - 2;
            case CARPET               -> CarpetChannel.MAX_CARPET_PAYLOAD;
            case CARPET_SHADE         -> CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM;
            case CARPET_BANNERS       -> CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM;
            case CARPET_BANNERS_SHADE -> CarpetChannel.MAX_TOTAL_BYTES_LOOM;
        };
    }

    /** Returns the maximum compressed-payload bytes for a given tile. */
    public static int maxBytesForTile(PayloadState.TileData tile) {
        if (!tile.carpetEncoded) return MAX_CHUNKS * 84 - 2;
        if (!tile.loomEncoded)   return CarpetChannel.MAX_TOTAL_BYTES; // legacy LC/LS
        return maxBytesForMode(PayloadState.codecMode);
    }

    /**
     * Returns true if the active tile has payload data to work with.
     * Carpet tiles carry their data in {@code carpetCompressedB64}, not in chunks, so the
     * chunk list may be empty even when the tile has valid content (LOOM tiles with no overflow).
     */
    private static boolean activeTileHasContent() {
        if (PayloadState.tiles.isEmpty()) return false;
        PayloadState.TileData tile = PayloadState.tiles.get(PayloadState.activeTileIndex);
        return tileHasContent(tile);
    }

    private static boolean tileHasContent(PayloadState.TileData tile) {
        if (tile.carpetEncoded) return tile.carpetCompressedB64 != null;
        return !tile.chunks.isEmpty();
    }

    /**
     * Returns the compressed payload bytes for a tile regardless of encoding format.
     * For carpet tiles reads {@code carpetCompressedB64}; for banner tiles reassembles
     * from CJK chunks.  Returns null if the tile has no data.
     */
    private static byte[] getCompressedBytesForTile(PayloadState.TileData tile) {
        if (tile.carpetEncoded) {
            if (tile.carpetCompressedB64 == null) return null;
            return Base64.getDecoder().decode(tile.carpetCompressedB64);
        }
        if (tile.chunks.isEmpty()) return null;
        List<String> names = new ArrayList<>(tile.chunks);
        names.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
        if (names.get(0).length() > 2 && names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE) {
            StringBuilder sb = new StringBuilder();
            for (String s : names) sb.append(s.substring(2));
            return CjkCodec.decode(sb.toString());
        }
        StringBuilder b64 = new StringBuilder();
        for (String s : names) b64.append(s.substring(2));
        try { return Base64.getDecoder().decode(b64.toString()); }
        catch (Exception e) { return null; }
    }

    /** Number of overflow banner chunks for a tile (excluding any LC/LS or LR header banner). */
    private static int overflowBannerCount(PayloadState.TileData tile, List<String> chunks) {
        if (!tile.carpetEncoded) return chunks.size();
        if (tile.loomEncoded)    return chunks.size(); // no header banner in LOOM format
        return Math.max(0, chunks.size() - 1);         // legacy LC/LS: subtract header banner
    }

    // ── Encoding helpers ───────────────────────────────────────────────

    /**
     * Prepends the given manifest bytes to mapColors, compresses the combined
     * payload with zstd, and splits it into CJK-encoded indexed banner name chunks.
     * Encryption is never applied here — tiles are stored as plain compressed bytes
     * and encrypted only at output time (export or anvil placement).
     */
    private static List<String> buildChunks(byte[] manifestBytes, byte[] mapColors) {
        byte[] combined = new byte[manifestBytes.length + mapColors.length];
        System.arraycopy(manifestBytes, 0, combined, 0, manifestBytes.length);
        System.arraycopy(mapColors, 0, combined, manifestBytes.length, mapColors.length);
        return CjkCodec.buildChunks(compress(combined));
    }

    /**
     * Applies encryption at output time, embedding the given author and title as
     * plaintext metadata (v3 envelope). Either may be null.
     */
    public static List<String> buildEncryptedChunksForOutput(byte[] compressed,
                                                               String author, String title) {
        String pw = encryptPassword;
        if (pw == null || pw.isEmpty()) return CjkCodec.buildChunks(compressed);
        List<String> slots = buildEncryptPasswords(pw);
        try {
            return CjkCodec.buildChunks(MapEncryption.encrypt(compressed, slots, author, title));
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /** Overload with no metadata — produces a v2 envelope. */
    public static List<String> buildEncryptedChunksForOutput(byte[] compressed) {
        return buildEncryptedChunksForOutput(compressed, null, null);
    }

    /** Applies encryption with metadata at output time. Either string may be null. */
    public static byte[] encryptForOutput(byte[] compressed, String author, String title) {
        String pw = encryptPassword;
        if (pw == null || pw.isEmpty()) return compressed;
        List<String> slots = buildEncryptPasswords(pw);
        try {
            return MapEncryption.encrypt(compressed, slots, author, title);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /** Overload with no metadata — produces a v2 envelope. */
    public static byte[] encryptForOutput(byte[] compressed) {
        return encryptForOutput(compressed, null, null);
    }

    /**
     * Result of carpet-hybrid encoding. Contains everything needed to populate
     * a {@code TileData}, write the schematic, and report stats to the player.
     *
     * <p>{@code headerChunk} is {@code null} for LOOM-format tiles (no LC/LS banner);
     * it holds the {@code "LC…"} / {@code "LS…"} name for legacy tiles.
     */
    record CarpetEncoding(
            byte[] compressed,       // full zstd-compressed logical payload (no LOOM header)
            int carpetBytes,         // bytes written into the carpet channel (≤8192)
            int shadeBytes,          // bytes written into the shade channel (≤2016); 0 = flat
            byte[] nibbles,          // 16384 nibble array for schematic generation
            int[][] heights,         // [col][row] height map; null when shadeBytes==0
            String headerChunk,      // "LC…"/"LS…" for legacy tiles; null for LOOM tiles
            List<String> hexChunks   // hex-indexed overflow banners "00"…
    ) {
        /** All banner names: header chunk (if any) followed by hex overflow chunks. */
        List<String> allChunks() {
            if (headerChunk == null) return new ArrayList<>(hexChunks);
            List<String> all = new ArrayList<>(1 + hexChunks.size());
            all.add(headerChunk);
            all.addAll(hexChunks);
            return all;
        }
    }

    // ── Legacy LC/LS encoder (backward compat — used only for old-format tile re-encodes) ──

    /**
     * Encodes already-compressed bytes into the legacy LC/LS carpet+shade+overflow format.
     * Kept for backward compatibility when re-exporting pre-LOOM tiles.
     */
    private static CarpetEncoding encodeCarpetFromCompressed(byte[] compressed) {
        int total = compressed.length;
        int carpetBytes = Math.min(total, CarpetChannel.MAX_CARPET_BYTES);
        int shadeBytes = total > CarpetChannel.MAX_CARPET_BYTES + CarpetChannel.MAX_OVERFLOW_BYTES
                ? Math.min(total - carpetBytes, CarpetChannel.MAX_SHADE_BYTES) : 0;
        int overflowStart = carpetBytes + shadeBytes;
        byte[] nibbles = CarpetChannel.encodeNibbles(compressed, carpetBytes);
        int[][] heights = null;
        if (shadeBytes > 0) {
            byte[] shadeData = new byte[shadeBytes];
            System.arraycopy(compressed, carpetBytes, shadeData, 0, shadeBytes);
            heights = CarpetChannel.computeHeights(shadeData, shadeBytes);
        }
        String lcName = shadeBytes > 0
                ? String.format("LS%04X%04X", total, shadeBytes)
                : String.format("LC%04X",     total);
        List<String> hexChunks = new ArrayList<>();
        if (total > overflowStart) {
            byte[] overflow = Arrays.copyOfRange(compressed, overflowStart, total);
            hexChunks = CjkCodec.buildChunks(overflow);
        }
        return new CarpetEncoding(compressed, carpetBytes, shadeBytes, nibbles, heights, lcName, hexChunks);
    }

    // ── LOOM-format encoder ───────────────────────────────────────────────────

    /**
     * Encodes raw cargo bytes (LOOM header already prepended by the caller) into
     * the carpet + optional shade + optional overflow channels, producing a
     * {@code CarpetEncoding} with {@code headerChunk == null}.
     *
     * @param cargo     full cargo: LOOM header || payload (for non-mux) or
     *                  LOOM header || own frame || guest bytes (for mux donors)
     * @param useShadeBeyondCarpet  whether to spill into the shade channel when
     *                              cargo exceeds carpet capacity
     * @param useBanners  whether overflow banners are allowed
     */
    private static CarpetEncoding encodeLoomCargoToChannels(
            byte[] cargo, boolean useShadeBeyondCarpet, boolean useBanners) {
        int total = cargo.length;
        int carpetBytes = Math.min(total, CarpetChannel.MAX_CARPET_BYTES);

        int shadeBytes = 0;
        if (useShadeBeyondCarpet && total > CarpetChannel.MAX_CARPET_BYTES
                && !(useBanners && total <= CarpetChannel.MAX_CARPET_BYTES + CarpetChannel.MAX_OVERFLOW_BYTES_LOOM)) {
            shadeBytes = Math.min(total - carpetBytes, CarpetChannel.MAX_SHADE_BYTES);
        }
        int overflowStart = carpetBytes + shadeBytes;

        byte[] nibbles = CarpetChannel.encodeNibbles(cargo, carpetBytes);
        int[][] heights = null;
        if (shadeBytes > 0) {
            byte[] shadeData = Arrays.copyOfRange(cargo, carpetBytes, carpetBytes + shadeBytes);
            heights = CarpetChannel.computeHeights(shadeData, shadeBytes);
        }

        List<String> hexChunks = new ArrayList<>();
        if (useBanners && total > overflowStart) {
            byte[] overflow = Arrays.copyOfRange(cargo, overflowStart, total);
            hexChunks = CjkCodec.buildChunks(overflow);
        }

        // compressed field stores only the logical payload (without LOOM header)
        // for consistency with how carpetCompressedB64 is used elsewhere.
        // For LOOM tiles, the caller already has the payload separately.
        return new CarpetEncoding(cargo, carpetBytes, shadeBytes, nibbles, heights, null, hexChunks);
    }

    /**
     * Encodes a zstd-compressed logical payload into the LOOM carpet format for the
     * given codec mode.  Builds the LOOM header internally.
     *
     * @param compressed  the full zstd-compressed payload (manifest + colors)
     * @param col         tile column in the grid (for mux receiver identification)
     * @param row         tile row in the grid
     * @param mode        codec mode driving channel selection
     */
    private static CarpetEncoding encodeLoomFromCompressed(
            byte[] compressed, int col, int row, CodecMode mode) {
        boolean useShade   = (mode == CodecMode.CARPET_BANNERS_SHADE || mode == CodecMode.CARPET_SHADE);
        boolean useBanners = (mode == CodecMode.CARPET_BANNERS || mode == CodecMode.CARPET_BANNERS_SHADE);

        int flags = (useShade ? CarpetChannel.LOOM_FLAG_SHADE : 0)
                  | (useBanners ? CarpetChannel.LOOM_FLAG_BANNERS : 0);
        byte[] loomHeader = CarpetChannel.buildLoomHeader(
                flags, col, row, compressed.length, compressed.length, null);

        byte[] cargo = new byte[loomHeader.length + compressed.length];
        System.arraycopy(loomHeader,    0, cargo, 0,                loomHeader.length);
        System.arraycopy(compressed,    0, cargo, loomHeader.length, compressed.length);

        CarpetEncoding raw = encodeLoomCargoToChannels(cargo, useShade, useBanners);
        // Return with `compressed` field = logical payload (not cargo) for storage.
        return new CarpetEncoding(compressed, raw.carpetBytes(), raw.shadeBytes(),
                raw.nibbles(), raw.heights(), null, raw.hexChunks());
    }

    /**
     * Builds a carpet-hybrid encoding for the given manifest + map-color payload.
     * Uses the current {@link PayloadState#codecMode}; delegates to the LOOM encoder
     * for carpet modes and to {@link #buildChunks} for banner mode.
     */
    private static CarpetEncoding buildCarpetEncoding(byte[] manifestBytes, byte[] mapColors) {
        return buildCarpetEncoding(manifestBytes, mapColors, 0, 0);
    }

    private static CarpetEncoding buildCarpetEncoding(
            byte[] manifestBytes, byte[] mapColors, int col, int row) {
        byte[] combined = new byte[manifestBytes.length + mapColors.length];
        System.arraycopy(manifestBytes, 0, combined, 0,                 manifestBytes.length);
        System.arraycopy(mapColors,     0, combined, manifestBytes.length, mapColors.length);
        byte[] compressed = compress(combined);
        // Encryption is deferred to output time; store plain compressed bytes here.

        CodecMode mode = PayloadState.codecMode;
        int maxBytes = maxBytesForMode(mode);
        if (compressed.length > maxBytes) {
            // Over budget — encode anyway and let the tile be marked over-budget.
            // Mux pooling (/loominary mux) can distribute the overflow across donor tiles.
            System.out.println("[Loominary] buildCarpetEncoding: " + compressed.length
                    + " bytes exceeds " + mode.label() + " capacity " + maxBytes
                    + " — tile will be over-budget");
        }
        return encodeLoomFromCompressed(compressed, col, row, mode);
    }

    /**
     * Reconstructs banner name list from raw compressed bytes.
     * For LOOM tiles, uses the current codec mode; for legacy tiles, uses LC/LS.
     */
    private static List<String> rebuildCarpetChunks(
            byte[] compressed, int total, int carpetBytes, int shadeBytes) {
        // Legacy LC/LS path (called for old tiles during export).
        int overflowStart = carpetBytes + shadeBytes;
        String mainBanner = shadeBytes > 0
                ? String.format("LS%04X%04X", total, shadeBytes)
                : String.format("LC%04X",     total);
        List<String> chunks = new ArrayList<>();
        chunks.add(mainBanner);
        if (total > overflowStart) {
            chunks.addAll(CjkCodec.buildChunks(
                    Arrays.copyOfRange(compressed, overflowStart, total)));
        }
        return chunks;
    }

    /** Exports the correct schematic type (flat or staircase) for a carpet encoding. */
    private static Path exportCarpetSchematic(CarpetEncoding enc, String name) throws IOException {
        if (enc.shadeBytes() > 0) {
            return SchematicExporter.exportCarpetStaircase(
                    enc.nibbles(), enc.carpetBytes(), enc.heights(), name);
        }
        return SchematicExporter.exportCarpetTile(enc.nibbles(), enc.carpetBytes(), name);
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
        int tileIdx = PayloadState.activeTileIndex;
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        int col = PayloadState.tileCol(tileIdx), row = PayloadState.tileRow(tileIdx);
        if (tile.carpetEncoded) {
            CarpetEncoding enc = buildCarpetEncoding(manifestBytes, mapColors, col, row);
            tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
            tile.loomEncoded = (enc.headerChunk() == null);
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
        int col = PayloadState.tileCol(tileIdx), row = PayloadState.tileRow(tileIdx);
        List<String> all;
        if (tile.carpetEncoded) {
            CarpetEncoding enc = buildCarpetEncoding(manifestBytes, mapColors, col, row);
            tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
            tile.loomEncoded = (enc.headerChunk() == null);
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

            // Concatenate all frame bytes into the payload
            byte[] payloadBytes = new byte[frameCount * MAP_BYTES];
            for (int f = 0; f < frameCount; f++) {
                System.arraycopy(allFrames[f], 0, payloadBytes, f * MAP_BYTES, MAP_BYTES);
            }

            byte[] manifest;
            if (frameCount > 1) {
                flags |= PayloadManifest.FLAG_ANIMATED;
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
            int[] delaysForTrailing = (tile.frameDelays != null)
                    ? tile.frameDelays.stream().mapToInt(Integer::intValue).toArray()
                    : new int[0];
            saveTileData(tileIdx, manifest, PayloadManifest.withTrailing(manifest, delaysForTrailing, payloadBytes));

            // Editing any tile invalidates the mux allocation across the whole batch.
            boolean hadMux = PayloadState.tiles.stream().anyMatch(t -> t.muxCargoB64 != null);
            PayloadState.tiles.forEach(t -> { t.muxed = false; t.muxReceiver = false; t.muxCargoB64 = null; });
            PayloadState.save();

            if (hadMux) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(
                            "§eMux state cleared — run §f/loominary mux§e again after reducing over-budget tiles."),
                            false);
                }
            }

            // Warn in chat if the saved result is still over budget.
            if (tile.carpetEncoded && tile.carpetCompressedB64 != null) {
                int bytes = Base64.getDecoder().decode(tile.carpetCompressedB64).length;
                int cap = maxBytesForTile(tile);
                if (bytes > cap) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.player != null) {
                        mc.player.sendMessage(Text.literal(String.format(
                                "§e⚠ Changes saved but tile is over budget (%d/%d bytes). " +
                                "Use §f/loominary reduce§e to compress further.",
                                bytes, cap)), false);
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

    /**
     * Off-render-thread variant used by {@code MapEditorScreen.close()}.
     * Snapshots everything on the calling thread, dispatches compression to
     * {@link #SAVE_EXECUTOR}, then applies results back via {@code mc.execute()}.
     * Increments {@link #pendingSaves} before dispatch; decrements in the callback
     * and calls {@link PayloadState#save()} when it reaches zero.
     * The caller must clear mux state before calling this method.
     */
    public static void saveEditorChangesAsync(byte[][] allFrames, int[] editorDelays,
            int tileIdx, String playerName) {
        if (PayloadState.tiles.isEmpty() || tileIdx >= PayloadState.tiles.size()) return;
        MinecraftClient mc = MinecraftClient.getInstance();

        // Snapshot all main-thread state before going off-thread.
        int frameCount = allFrames.length;
        byte[][] framesCopy = new byte[frameCount][];
        for (int f = 0; f < frameCount; f++) framesCopy[f] = allFrames[f].clone();

        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        boolean carpetEncoded = tile.carpetEncoded;
        int nonce = tile.nonce;
        int flags = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
        int gridCols = PayloadState.gridColumns;
        int gridRows = PayloadState.gridRows;
        int col = PayloadState.tileCol(tileIdx);
        int row = PayloadState.tileRow(tileIdx);
        String title = PayloadState.currentTitle;
        int activeTileIdx = PayloadState.activeTileIndex;

        final int[] delays;
        if (editorDelays != null && editorDelays.length == frameCount) {
            delays = editorDelays.clone();
        } else if (tile.frameDelays != null && !tile.frameDelays.isEmpty()) {
            delays = tile.frameDelays.stream().mapToInt(Integer::intValue).toArray();
        } else {
            delays = new int[frameCount];
            Arrays.fill(delays, 100);
        }

        pendingSaves.incrementAndGet();

        SAVE_EXECUTOR.execute(() -> {
            try {
                byte[] payloadBytes = new byte[frameCount * MAP_BYTES];
                for (int f = 0; f < frameCount; f++)
                    System.arraycopy(framesCopy[f], 0, payloadBytes, f * MAP_BYTES, MAP_BYTES);

                final byte[] manifest;
                if (frameCount > 1) {
                    manifest = PayloadManifest.toBytes(flags | PayloadManifest.FLAG_ANIMATED,
                            gridCols, gridRows, col, row,
                            PayloadManifest.crc32(framesCopy[0]),
                            playerName, title, nonce, frameCount, 0, delays);
                } else {
                    manifest = PayloadManifest.toBytes(flags,
                            gridCols, gridRows, col, row,
                            PayloadManifest.crc32(framesCopy[0]),
                            playerName, title, nonce);
                }

                // Append v5 trailing delay table (empty for v4/global-delay manifests).
                byte[] effectivePayload = PayloadManifest.withTrailing(manifest, delays, payloadBytes);

                final List<String> allChunks;
                final String newCarpetB64;
                final boolean newLoomEncoded;
                if (carpetEncoded) {
                    CarpetEncoding enc = buildCarpetEncoding(manifest, effectivePayload, col, row);
                    allChunks = enc.allChunks();
                    newCarpetB64 = Base64.getEncoder().encodeToString(enc.compressed());
                    newLoomEncoded = (enc.headerChunk() == null);
                } else {
                    allChunks = buildChunks(manifest, effectivePayload);
                    newCarpetB64 = null;
                    newLoomEncoded = false;
                }

                mc.execute(() -> {
                    if (tileIdx >= PayloadState.tiles.size()) {
                        if (pendingSaves.decrementAndGet() == 0) PayloadState.save();
                        return;
                    }
                    PayloadState.TileData t = PayloadState.tiles.get(tileIdx);
                    t.chunks.clear();
                    t.chunks.addAll(allChunks);
                    t.currentIndex = 0;
                    if (frameCount > 1) {
                        t.frameCount = frameCount;
                        t.frameDelays = new ArrayList<>();
                        for (int d : delays) t.frameDelays.add(d);
                    } else {
                        t.frameCount = 1;
                        t.frameDelays = null;
                    }
                    if (carpetEncoded && newCarpetB64 != null) {
                        t.carpetCompressedB64 = newCarpetB64;
                        t.loomEncoded = newLoomEncoded;
                    }
                    if (tileIdx == activeTileIdx) {
                        PayloadState.ACTIVE_CHUNKS.clear();
                        PayloadState.ACTIVE_CHUNKS.addAll(allChunks);
                        PayloadState.activeChunkIndex = 0;
                    }
                    if (pendingSaves.decrementAndGet() == 0) PayloadState.save();

                    if (mc.player != null) {
                        String saveMsg;
                        if (carpetEncoded && newCarpetB64 != null) {
                            int bytes = Base64.getDecoder().decode(newCarpetB64).length;
                            saveMsg = String.format("§aSaved %s — %d bytes.",
                                    PayloadState.tileLabel(tileIdx), bytes);
                        } else {
                            List<String> saved = tileIdx == activeTileIdx
                                    ? PayloadState.ACTIVE_CHUNKS : PayloadState.tiles.get(tileIdx).chunks;
                            saveMsg = String.format("§aSaved %s — %d banner%s.",
                                    PayloadState.tileLabel(tileIdx),
                                    saved.size(), saved.size() == 1 ? "" : "s");
                        }
                        mc.player.sendMessage(Text.literal(saveMsg), false);
                    }

                    if (carpetEncoded && newCarpetB64 != null && mc.player != null) {
                        byte[] dec = Base64.getDecoder().decode(newCarpetB64);
                        int maxCap = newLoomEncoded
                                ? maxBytesForMode(PayloadState.codecMode)
                                : CarpetChannel.MAX_TOTAL_BYTES;
                        if (dec.length > maxCap) {
                            mc.player.sendMessage(Text.literal(String.format(
                                    "§e⚠ Changes saved but tile is over budget (%d/%d bytes). " +
                                    "Use §f/loominary reduce§e to compress further.",
                                    dec.length, maxCap)), false);
                        }
                    }
                });
            } catch (Exception e) {
                mc.execute(() -> {
                    if (mc.player != null)
                        mc.player.sendMessage(Text.literal("§cEditor save failed: " + e.getMessage()), false);
                    System.err.println("[Loominary] Editor save failed: " + e.getMessage());
                    if (pendingSaves.decrementAndGet() == 0) PayloadState.save();
                });
            }
        });
    }

    /** Decodes map-color bytes for any tile (carpet or banner). */
    /**
     * Decrypts {@code data} if it is an encrypted envelope, returning the
     * underlying compressed bytes. Returns {@code data} unchanged if not encrypted.
     * Used by tile-decode paths in commands that run synchronously (not on a tick thread).
     */
    private static byte[] decryptIfNeeded(byte[] data) {
        if (!MapEncryption.isEncrypted(data)) return data;
        byte[] dec = MapEncryption.tryDecrypt(data, -1);
        if (dec == null) throw new IllegalStateException(
                "Encrypted tile — add the decryption password with /loominary password add <pw>");
        return dec;
    }

    private static byte[] resolveMapColorsForTile(PayloadState.TileData tile, java.util.Collection<String> chunks) {
        if (tile.carpetEncoded) {
            if (tile.carpetCompressedB64 == null) {
                throw new IllegalStateException("Carpet tile has no stored compressed data — re-import.");
            }
            byte[] compressed = decryptIfNeeded(Base64.getDecoder().decode(tile.carpetCompressedB64));
            long size = Zstd.getFrameContentSize(compressed);
            if (size < 0) throw new IllegalStateException("Invalid compressed data in carpet tile.");
            byte[] full = Zstd.decompress(compressed, (int) size);
            if (full.length == MAP_BYTES) return full;
            PayloadManifest manifest = PayloadManifest.fromBytes(full);
            return java.util.Arrays.copyOfRange(full, manifest.headerSize, manifest.headerSize + MAP_BYTES);
        }
        return MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));
    }

    /**
     * Returns the compressed byte count for a carpet tile directly from the stored
     * base64 data. This is reliable for any payload size; reading from the LC banner
     * name header only works up to 65535 bytes (4 hex digits) and silently truncates
     * for large animated tiles.
     */
    private static int carpetCompressedBytes(PayloadState.TileData tile) {
        String b64 = tile.muxCargoB64 != null ? tile.muxCargoB64 : tile.carpetCompressedB64;
        if (b64 == null || b64.isEmpty()) return 0;
        int len = b64.length() * 3 / 4;
        if (b64.endsWith("==")) len -= 2;
        else if (b64.endsWith("=")) len -= 1;
        return len;
    }

    /**
     * Post-pass after carpet import: redistributes overflow bytes from over-budget tiles
     * into spare carpet capacity on under-budget donor tiles.
     *
     * <p>Wire format:
     * <ul>
     *   <li>Receiver tile chunks: {@code LR<col:2><row:2><ownLen:4><total:8>} banner + hex overflow
     *       banners for any bytes of the own segment that spill past carpet+shade.</li>
     *   <li>Donor tile chunks: normal LC/LS + hex overflow, then appended
     *       {@code MG<seqIdx:2><ownLen:4><tCol:2><tRow:2><tOffset:8><tLen:4>} banners (one per
     *       guest segment hosted).</li>
     * </ul>
     *
     * @return number of over-budget tiles that could not be redistributed (0 = fully resolved)
     */
    /**
     * Applies mux pooling across all tiles for all codec modes.
     *
     * <p>Codec-specific behaviour:
     * <ul>
     *   <li>BANNER: receiver uses LB banner + payload banners; donor uses
     *       CJK payload banners + MG routing banners.</li>
     *   <li>CARPET_BANNERS / CARPET_BANNERS_SHADE: receiver uses LOOM header (MUX_RX) in carpet;
     *       donor uses LOOM header in carpet + MG routing banners.</li>
     *   <li>CARPET / CARPET_SHADE: receiver uses LOOM header (MUX_RX) in carpet;
     *       donor uses LOOM header with guest descriptors in carpet (no MG banners).</li>
     * </ul>
     *
     * @return number of over-budget tiles that could not be resolved (0 = fully resolved)
     */
    private static int poolMuxTiles(List<PayloadState.TileData> tiles, int columns, int rows) {
        return poolMuxTiles(tiles, columns, rows, false);
    }

    /**
     * @param donorOnlyMode when {@code true}, only tiles with {@code isDonorOnly=true} may
     *                      serve as donors; art tiles are never used as donors even if they
     *                      have spare capacity.
     */
    private static int poolMuxTiles(List<PayloadState.TileData> tiles, int columns, int rows,
                                     boolean donorOnlyMode) {
        CodecMode mode = PayloadState.codecMode;

        // Reset every tile to plain (non-mux) encoding, clearing stale mux banners.
        for (int i = 0; i < tiles.size(); i++) {
            PayloadState.TileData tile = tiles.get(i);
            tile.muxed = false;
            tile.muxReceiver = false;
            tile.muxCargoB64 = null;
            if (tile.carpetEncoded && tile.carpetCompressedB64 != null) {
                byte[] compressed = Base64.getDecoder().decode(tile.carpetCompressedB64);
                CarpetEncoding enc = tile.loomEncoded
                        ? encodeLoomFromCompressed(compressed, i % columns, i / columns, mode)
                        : encodeCarpetFromCompressed(compressed);
                tile.chunks.clear();
                tile.chunks.addAll(enc.allChunks());
            }
            // Banner tiles: chunks already hold plain payload; nothing to reset.
        }

        // Collect compressed payloads for all tiles.
        List<byte[]> payloads = new ArrayList<>();
        for (PayloadState.TileData t : tiles) {
            if (t.carpetEncoded && t.carpetCompressedB64 != null)
                payloads.add(Base64.getDecoder().decode(t.carpetCompressedB64));
            else {
                // Banner tile: reassemble compressed bytes from chunks.
                List<String> names = new ArrayList<>(t.chunks);
                names.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
                byte[] compressed;
                if (!names.isEmpty() && names.get(0).length() > 2
                        && names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : names) sb.append(s.substring(2));
                    compressed = CjkCodec.decode(sb.toString());
                } else {
                    compressed = new byte[0]; // banner tile without CJK chunks — skip
                }
                payloads.add(compressed);
            }
        }

        // Max own-segment bytes a receiver can hold in its own tile channels.
        int receiverOwnMax = switch (mode) {
            case BANNER               -> (MAX_CHUNKS - 1) * 84; // 62 payload banners (1 LB slot)
            case CARPET               -> CarpetChannel.MAX_CARPET_PAYLOAD;
            case CARPET_SHADE         -> CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM;
            case CARPET_BANNERS       -> CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM;
            case CARPET_BANNERS_SHADE -> CarpetChannel.MAX_TOTAL_BYTES_LOOM;
        };

        // Budget threshold: payload bytes that exceed this make a tile a receiver.
        int budget = maxBytesForMode(mode);

        List<Integer> receivers = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++)
            if (payloads.get(i).length > budget) receivers.add(i);
        if (receivers.isEmpty()) return 0;

        int[] cargoSize = new int[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) cargoSize[i] = payloads.get(i).length;

        @SuppressWarnings("unchecked")
        List<int[]>[] guestMeta = new List[tiles.size()]; // {targetIdx, offsetInPayload, len}
        @SuppressWarnings("unchecked")
        List<byte[]>[] guestData = new List[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) {
            guestMeta[i] = new ArrayList<>();
            guestData[i] = new ArrayList<>();
        }

        int unresolved = 0;
        for (int rIdx : receivers) {
            byte[] payload = payloads.get(rIdx);
            int pos = receiverOwnMax;
            boolean fits = true;
            while (pos < payload.length) {
                List<int[]> viable = new ArrayList<>();
                for (int d = 0; d < tiles.size(); d++) {
                    if (tiles.get(d).muxReceiver) continue;
                    if (donorOnlyMode && !tiles.get(d).isDonorOnly) continue;
                    int spare = donorSpare(d, cargoSize, guestMeta, mode);
                    if (spare > 0) viable.add(new int[]{d, spare});
                }
                if (viable.isEmpty()) { fits = false; break; }

                int remaining = payload.length - pos;
                int share = (remaining + viable.size() - 1) / viable.size();
                for (int[] dv : viable) {
                    if (pos >= payload.length) break;
                    int take = Math.min(Math.min(dv[1], share), payload.length - pos);
                    if (take <= 0) continue;
                    guestMeta[dv[0]].add(new int[]{rIdx, pos, take});
                    guestData[dv[0]].add(Arrays.copyOfRange(payload, pos, pos + take));
                    cargoSize[dv[0]] += take;
                    pos += take;
                }
            }
            if (!fits) {
                final int rIdxFinal = rIdx;
                for (int d = 0; d < tiles.size(); d++) {
                    for (int g = guestMeta[d].size() - 1; g >= 0; g--) {
                        if (guestMeta[d].get(g)[0] == rIdxFinal) {
                            cargoSize[d] -= guestMeta[d].get(g)[2];
                            guestMeta[d].remove(g);
                            guestData[d].remove(g);
                        }
                    }
                }
                unresolved++;
                continue;
            }
            tiles.get(rIdx).muxReceiver = true;
            tiles.get(rIdx).muxed = true;
        }

        // ── Encode resolved receiver tiles ──────────────────────────────────
        for (int rIdx : receivers) {
            if (!tiles.get(rIdx).muxReceiver) continue;
            PayloadState.TileData tile = tiles.get(rIdx);
            byte[] payload = payloads.get(rIdx);
            int col = rIdx % columns, row = rIdx / columns;
            byte[] ownSeg = Arrays.copyOfRange(payload, 0, Math.min(receiverOwnMax, payload.length));

            if (mode == CodecMode.BANNER) {
                // LB banner + CJK payload banners for own segment.
                List<String> chunks = new ArrayList<>();
                chunks.add(String.format("LB%02X%02X%04X%08X",
                        col, row, ownSeg.length, payload.length));
                chunks.addAll(CjkCodec.buildChunks(ownSeg));
                tile.chunks.clear();
                tile.chunks.addAll(chunks);
                tile.muxCargoB64 = Base64.getEncoder().encodeToString(ownSeg);

            } else if (mode == CodecMode.CARPET || mode == CodecMode.CARPET_SHADE) {
                // No overflow banners; guest descriptors embedded in LOOM header.
                // CARPET: carpet channel only. CARPET_SHADE: carpet + shade when needed.
                boolean useShade = (mode == CodecMode.CARPET_SHADE)
                        && ownSeg.length > CarpetChannel.MAX_CARPET_PAYLOAD;
                int loomFlags = CarpetChannel.LOOM_FLAG_MUX_RX
                        | (useShade ? CarpetChannel.LOOM_FLAG_SHADE : 0);
                byte[] loomHeader = CarpetChannel.buildLoomHeader(
                        loomFlags, col, row, ownSeg.length, payload.length, null);
                byte[] cargo = new byte[loomHeader.length + ownSeg.length];
                System.arraycopy(loomHeader, 0, cargo, 0,               loomHeader.length);
                System.arraycopy(ownSeg,     0, cargo, loomHeader.length, ownSeg.length);
                CarpetEncoding enc = encodeLoomCargoToChannels(cargo, useShade, false);
                tile.chunks.clear();
                tile.chunks.addAll(enc.allChunks());
                tile.loomEncoded = true;
                tile.muxCargoB64 = Base64.getEncoder().encodeToString(ownSeg);

            } else {
                // CARPET_BANNERS / CARPET_BANNERS_SHADE: LOOM header (MUX_RX) + own segment
                // in carpet + optional shade + overflow banners.
                boolean needShade = (mode == CodecMode.CARPET_BANNERS_SHADE)
                        && ownSeg.length > CarpetChannel.MAX_CARPET_PAYLOAD + CarpetChannel.MAX_OVERFLOW_BYTES_LOOM;
                int loomFlags = CarpetChannel.LOOM_FLAG_BANNERS | CarpetChannel.LOOM_FLAG_MUX_RX
                        | (needShade ? CarpetChannel.LOOM_FLAG_SHADE : 0);
                byte[] loomHeader = CarpetChannel.buildLoomHeader(
                        loomFlags, col, row, ownSeg.length, payload.length, null);
                byte[] cargo = new byte[loomHeader.length + ownSeg.length];
                System.arraycopy(loomHeader, 0, cargo, 0,               loomHeader.length);
                System.arraycopy(ownSeg,     0, cargo, loomHeader.length, ownSeg.length);
                CarpetEncoding enc = encodeLoomCargoToChannels(cargo, needShade, true);
                tile.chunks.clear();
                tile.chunks.addAll(enc.allChunks());
                tile.loomEncoded = true;
                tile.muxCargoB64 = Base64.getEncoder().encodeToString(ownSeg);
            }
        }

        // ── Encode donor tiles ──────────────────────────────────────────────
        for (int dIdx = 0; dIdx < tiles.size(); dIdx++) {
            if (guestMeta[dIdx].isEmpty()) continue;
            PayloadState.TileData tile = tiles.get(dIdx);
            byte[] ownFrame = payloads.get(dIdx);
            int col = dIdx % columns, row = dIdx / columns;

            // Build linear cargo: own frame || guest0 || guest1 || …
            int totalLen = ownFrame.length;
            for (byte[] g : guestData[dIdx]) totalLen += g.length;
            byte[] cargoData = new byte[totalLen];
            System.arraycopy(ownFrame, 0, cargoData, 0, ownFrame.length);
            int pos = ownFrame.length;
            for (byte[] g : guestData[dIdx]) {
                System.arraycopy(g, 0, cargoData, pos, g.length);
                pos += g.length;
            }

            if (mode == CodecMode.BANNER) {
                // CJK payload banners for cargo + MG routing banners.
                List<String> chunks = new ArrayList<>(CjkCodec.buildChunks(cargoData));
                for (int g = 0; g < guestMeta[dIdx].size(); g++) {
                    int[] m = guestMeta[dIdx].get(g);
                    chunks.add(String.format("MG%02X%04X%02X%02X%08X%04X",
                            g, ownFrame.length, m[0] % columns, m[0] / columns, m[1], m[2]));
                }
                tile.chunks.clear();
                tile.chunks.addAll(chunks);
                tile.muxCargoB64 = Base64.getEncoder().encodeToString(cargoData);

            } else if (mode == CodecMode.CARPET || mode == CodecMode.CARPET_SHADE) {
                // No overflow banners; guest descriptors embedded in LOOM header.
                // CARPET: cargo in carpet only. CARPET_SHADE: spills into shade when needed.
                int[][] guestDescs = new int[guestMeta[dIdx].size()][4];
                for (int g = 0; g < guestMeta[dIdx].size(); g++) {
                    int[] m = guestMeta[dIdx].get(g);
                    guestDescs[g][0] = m[0] % columns;
                    guestDescs[g][1] = m[0] / columns;
                    guestDescs[g][2] = m[1];
                    guestDescs[g][3] = m[2];
                }
                boolean useShade = (mode == CodecMode.CARPET_SHADE)
                        && (cargoData.length + CarpetChannel.LOOM_FIXED_HEADER
                            + guestMeta[dIdx].size() * CarpetChannel.LOOM_GUEST_DESC
                            > CarpetChannel.MAX_CARPET_BYTES);
                int loomFlags = CarpetChannel.LOOM_FLAG_MUX_DONOR
                        | (useShade ? CarpetChannel.LOOM_FLAG_SHADE : 0);
                byte[] loomHeader = CarpetChannel.buildLoomHeader(
                        loomFlags, col, row, ownFrame.length, ownFrame.length, guestDescs);
                byte[] cargo = new byte[loomHeader.length + cargoData.length];
                System.arraycopy(loomHeader, 0, cargo, 0,               loomHeader.length);
                System.arraycopy(cargoData,  0, cargo, loomHeader.length, cargoData.length);
                CarpetEncoding enc = encodeLoomCargoToChannels(cargo, useShade, false);
                tile.chunks.clear();
                tile.chunks.addAll(enc.allChunks());
                tile.loomEncoded = true;
                tile.muxCargoB64 = Base64.getEncoder().encodeToString(cargoData);

            } else {
                // CARPET_BANNERS / CARPET_BANNERS_SHADE: LOOM header in cargo + MG routing banners.
                // ownLen in MG banner = LOOM_FIXED_HEADER + ownFrame.length (where guest data starts).
                int mgOwnLen = CarpetChannel.LOOM_FIXED_HEADER + ownFrame.length;
                int loomFlags = CarpetChannel.LOOM_FLAG_BANNERS
                        | ((mode == CodecMode.CARPET_BANNERS_SHADE) ? CarpetChannel.LOOM_FLAG_SHADE : 0);
                byte[] loomHeader = CarpetChannel.buildLoomHeader(
                        loomFlags, col, row, ownFrame.length, ownFrame.length, null);
                byte[] cargo = new byte[loomHeader.length + cargoData.length];
                System.arraycopy(loomHeader, 0, cargo, 0,               loomHeader.length);
                System.arraycopy(cargoData,  0, cargo, loomHeader.length, cargoData.length);
                boolean useShade = (mode == CodecMode.CARPET_BANNERS_SHADE)
                        && cargo.length > CarpetChannel.MAX_CARPET_BYTES + CarpetChannel.MAX_OVERFLOW_BYTES_LOOM;
                CarpetEncoding enc = encodeLoomCargoToChannels(cargo, useShade, true);
                List<String> chunks = new ArrayList<>(enc.allChunks());
                for (int g = 0; g < guestMeta[dIdx].size(); g++) {
                    int[] m = guestMeta[dIdx].get(g);
                    chunks.add(String.format("MG%02X%04X%02X%02X%08X%04X",
                            g, mgOwnLen, m[0] % columns, m[0] / columns, m[1], m[2]));
                }
                tile.chunks.clear();
                tile.chunks.addAll(chunks);
                tile.loomEncoded = true;
                tile.muxCargoB64 = Base64.getEncoder().encodeToString(cargoData);
            }

            tile.muxed = true;
        }
        return unresolved;
    }

    /** Computes spare guest-byte capacity for donor {@code d} under the given codec mode. */
    private static int donorSpare(int d, int[] cargoSize,
                                   List<int[]>[] guestMeta, CodecMode mode) {
        int gc = guestMeta[d].size(); // current guest count
        return switch (mode) {
            case BANNER ->
                // Spare = remaining banner slots (minus one new MG slot) × 84 bytes
                (MAX_CHUNKS - gc - 1) * 84 - cargoSize[d];
            case CARPET ->
                // Carpet only; guest descriptors in LOOM header (10 bytes each)
                CarpetChannel.MAX_CARPET_PAYLOAD
                        - (gc + 1) * CarpetChannel.LOOM_GUEST_DESC - cargoSize[d];
            case CARPET_SHADE ->
                // Carpet + shade; guest descriptors in LOOM header (10 bytes each)
                CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM
                        - (gc + 1) * CarpetChannel.LOOM_GUEST_DESC - cargoSize[d];
            case CARPET_BANNERS ->
                // Carpet + overflow banners; one MG routing banner (84 bytes) per guest
                CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM
                        - (gc + 1) * 84 - cargoSize[d];
            case CARPET_BANNERS_SHADE ->
                // Carpet + shade + overflow banners; one MG routing banner (84 bytes) per guest
                CarpetChannel.MAX_TOTAL_BYTES_LOOM
                        - (gc + 1) * 84 - cargoSize[d];
        };
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
            byte[] compressed = decryptIfNeeded(Base64.getDecoder().decode(tile.carpetCompressedB64));
            long size = Zstd.getFrameContentSize(compressed);
            if (size < 0) throw new IllegalStateException("Invalid compressed data in carpet tile.");
            return Zstd.decompress(compressed, (int) size);
        }
        // Banner tile: reassemble chunks → CJK or base64 decode → decompress
        List<String> names = new ArrayList<>(chunks);
        names.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
        byte[] compressed;
        if (!names.isEmpty() && names.get(0).length() > 2
                && names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE) {
            StringBuilder sb = new StringBuilder();
            for (String s : names) sb.append(s.substring(2));
            compressed = CjkCodec.decode(sb.toString());
        } else {
            StringBuilder b64 = new StringBuilder();
            for (String s : names) b64.append(s.substring(2));
            compressed = Base64.getDecoder().decode(b64.toString());
        }
        compressed = decryptIfNeeded(compressed);
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
        return MapBannerDecoder.extractFrames(full, manifest);
    }

    /**
     * Converts a flat array of raw animation frames into XOR-delta encoded frames.
     * Frame 0 is preserved raw; each subsequent frame stores {@code frame[n] XOR frame[n-1]}.
     * Returns {@code rawFrames} unchanged when {@code frameCount ≤ 1}.
     */
    public static byte[] toDeltaFrames(byte[] rawFrames, int frameCount) {
        if (frameCount <= 1) return rawFrames;
        byte[] out = new byte[rawFrames.length];
        System.arraycopy(rawFrames, 0, out, 0, MAP_BYTES); // frame 0 is raw
        for (int f = 1; f < frameCount; f++) {
            int off = f * MAP_BYTES;
            for (int p = 0; p < MAP_BYTES; p++)
                out[off + p] = (byte)(rawFrames[off + p] ^ rawFrames[off - MAP_BYTES + p]);
        }
        return out;
    }

    /**
     * Encodes raw animation frames as a sparse byte stream.
     * Frame 0 is stored raw (16,384 bytes).  Each subsequent frame is stored as a
     * sequence of {@code (pos: u16 BE, val: u8)} change records, prefixed by a
     * {@code changeCount: u16 BE} field.  Only pixels that differ from the previous
     * frame are recorded.  For animations with ≤ ~2% change per frame this is
     * 30–50× smaller than full frames before compression.
     *
     * @param rawFrames  array of raw absolute frames (may be static; must have ≥ 1 element)
     * @return           sparse payload bytes ready for prepending to the manifest and compressing
     */
    public static byte[] toSparseFramePayload(byte[][] rawFrames) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(
                rawFrames[0].length + rawFrames.length * 200);
        baos.write(rawFrames[0], 0, rawFrames[0].length); // frame 0: raw
        for (int f = 1; f < rawFrames.length; f++) {
            byte[] prev = rawFrames[f - 1];
            byte[] cur  = rawFrames[f];
            // Count changes first so we can write changeCount before the records.
            int changes = 0;
            for (int p = 0; p < MAP_BYTES; p++) if (cur[p] != prev[p]) changes++;
            baos.write((changes >> 8) & 0xFF);
            baos.write( changes       & 0xFF);
            for (int p = 0; p < MAP_BYTES; p++) {
                if (cur[p] != prev[p]) {
                    baos.write((p >> 8) & 0xFF);
                    baos.write( p       & 0xFF);
                    baos.write(cur[p]   & 0xFF);
                }
            }
        }
        return baos.toByteArray();
    }

    // ── Pre-encoding helper for background-thread safety ─────────────────
    //
    // saveTileData calls buildCarpetEncoding → compress(), which for large animated
    // payloads uses the long-range ZstdCompressCtx.  This MUST NOT run on the render
    // thread (it would block LWJGL for seconds and lose the window).
    //
    // Pattern: background thread calls preEncode() to produce a PreEncodedTile;
    // client.execute() calls applySaveTile() which only touches PayloadState fields.

    private record PreEncodedTile(List<String> chunks, String carpetB64, boolean loomEncoded) {}

    /**
     * Computes the encoding (compression + chunk building) for a tile on the calling thread.
     * Safe to call from any background thread.  Returns a {@link PreEncodedTile} that can
     * be applied to PayloadState on the main thread via {@link #applySaveTile}.
     */
    private static PreEncodedTile preEncode(int tileIdx, byte[] manifestBytes, byte[] payload,
                                             boolean isCarpetEncoded) {
        if (isCarpetEncoded) {
            int col = PayloadState.tileCol(tileIdx), row = PayloadState.tileRow(tileIdx);
            CarpetEncoding enc = buildCarpetEncoding(manifestBytes, payload, col, row);
            return new PreEncodedTile(enc.allChunks(),
                    Base64.getEncoder().encodeToString(enc.compressed()),
                    enc.headerChunk() == null);
        } else {
            return new PreEncodedTile(buildChunks(manifestBytes, payload), null, false);
        }
    }

    /**
     * Applies a pre-encoded tile result to PayloadState on the main thread.
     * No compression happens here.
     */
    private static int applySaveTile(int tileIdx, int frameCount, PreEncodedTile pre) {
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        tile.frameCount = frameCount;
        tile.chunks.clear();
        tile.chunks.addAll(pre.chunks());
        tile.currentIndex = 0;
        if (pre.carpetB64() != null) {
            tile.carpetCompressedB64 = pre.carpetB64();
            tile.loomEncoded = pre.loomEncoded();
        }
        if (tileIdx == PayloadState.activeTileIndex) {
            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(pre.chunks());
            PayloadState.activeChunkIndex = 0;
        }
        return pre.chunks().size();
    }

    // ── Caches for revert / reduce undo ────────────────────────────────

    private static final Map<Integer, byte[]> originalColors = new HashMap<>();
    private static final Map<Integer, List<String>> preReductionChunks = new HashMap<>();
    /** Parallel to preReductionChunks — saves carpetCompressedB64 for carpet tiles. */
    private static final Map<Integer, String> preReductionCarpetB64 = new HashMap<>();

    /** Active reduce strategy — persists for the session, defaults to RAREST. */
    private static PngToMapColors.Strategy reduceStrategy = PngToMapColors.Strategy.RAREST;

    /** Color metric used by /loominary requantize. */
    private static PngToMapColors.MatchMetric requantizeMetric = PngToMapColors.MatchMetric.OKLAB;
    /** Dither algorithm used by /loominary requantize. */
    private static PngToMapColors.DitherAlgo requantizeDitherAlgo = PngToMapColors.DitherAlgo.NONE;

    /** Returns true if the tile's current encoded data exceeds its channel's budget. */
    public static boolean isTileOverBudget(int tileIdx) {
        if (tileIdx >= PayloadState.tiles.size()) return false;
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        if (tile.carpetEncoded) {
            if (tile.carpetCompressedB64 == null) return false;
            return Base64.getDecoder().decode(tile.carpetCompressedB64).length
                    > maxBytesForTile(tile);
        }
        List<String> chunks = (tileIdx == PayloadState.activeTileIndex)
                ? PayloadState.ACTIVE_CHUNKS : tile.chunks;
        return chunks.size() > MAX_CHUNKS;
    }

    /**
     * Captures the current chunk state for a tile into the pre-reduction undo cache.
     * Uses putIfAbsent so a second reduce on the same tile doesn't clobber the original.
     */
    public static void capturePreReductionState(int tileIdx) {
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        preReductionChunks.putIfAbsent(tileIdx, new ArrayList<>(tile.chunks));
        if (tile.carpetEncoded && tile.carpetCompressedB64 != null)
            preReductionCarpetB64.putIfAbsent(tileIdx, tile.carpetCompressedB64);
    }

    /**
     * True when the user explicitly called /loominary title; false when the title is
     * auto-derived from a filename. Replacement imports overwrite the title only when
     * this flag is false (i.e., each import gets its own filename as the default title).
     */
    private static boolean titleIsUserSet = false;


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

    private static final SuggestionProvider<FabricClientCommandSource> SAVE_NAME_SUGGESTIONS = (ctx, builder) -> {
        Path dir = savesDir();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                List<String> names = stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> { String n = p.getFileName().toString(); return n.substring(0, n.length() - 5); })
                        .sorted()
                        .toList();
                return CommandSource.suggestMatching(names, builder);
            } catch (IOException ignored) {
            }
        }
        return builder.buildFuture();
    };

    private static Path savesDir() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("loominary_saves");
    }

    /** Returns the next monotonic counter for saves named {@code <stem>_NNN.json}. */
    private static int nextSaveCounter(String stem) {
        Path dir = savesDir();
        if (!Files.isDirectory(dir)) return 1;
        int max = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json") || !name.startsWith(stem + "_")) continue;
                String suffix = name.substring(stem.length() + 1, name.length() - 5);
                try { max = Math.max(max, Integer.parseInt(suffix)); } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
        return max + 1;
    }

    /** Writes an auto-save for a file import and reports the save name to the player. */
    private static void triggerAutoSave(String filename, FabricClientCommandSource source) {
        String stem = filenameStem(filename);
        int n = nextSaveCounter(stem);
        String saveName = String.format("%s_%03d", stem, n);
        PayloadState.saveToFile(savesDir().resolve(saveName + ".json"));
        source.sendFeedback(Text.literal("§7Saved as: §f" + saveName));
    }

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
                                    // header — reconstruct state from a map screenshot + LC/LS banner string
                                    .then(ClientCommandManager.literal("header")
                                            .then(ClientCommandManager.argument("banner", StringArgumentType.string())
                                                    .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                                            .suggests(FILENAME_SUGGESTIONS)
                                                            .executes(ctx -> importHeader(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "banner"),
                                                                    StringArgumentType.getString(ctx, "filename"))))))
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
                                            .then(ClientCommandManager.literal("mux")
                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, false, false, true))
                                                    .then(ClientCommandManager.literal("dither")
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, false, true, true)))
                                                    .then(ClientCommandManager.literal("allshades")
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, true, false, true))
                                                            .then(ClientCommandManager.literal("dither")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, true, true, true)))))
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
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), true, true))))
                                                            .then(ClientCommandManager.literal("mux")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                            IntegerArgumentType.getInteger(ctx, "rows"), false, false, true))
                                                                    .then(ClientCommandManager.literal("dither")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), false, true, true)))
                                                                    .then(ClientCommandManager.literal("allshades")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), true, false, true))
                                                                            .then(ClientCommandManager.literal("dither")
                                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                            IntegerArgumentType.getInteger(ctx, "rows"), true, true, true)))))))
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
                                                    .then(ClientCommandManager.literal("mux")
                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, false, false, true))
                                                            .then(ClientCommandManager.literal("dither")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, false, true, true)))
                                                            .then(ClientCommandManager.literal("allshades")
                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "filename"), 1, 1, true, false, true))
                                                                    .then(ClientCommandManager.literal("dither")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"), 1, 1, true, true, true)))))
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
                                                                                            IntegerArgumentType.getInteger(ctx, "rows"), true, true))))
                                                                    .then(ClientCommandManager.literal("mux")
                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), false, false, true))
                                                                            .then(ClientCommandManager.literal("dither")
                                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                            IntegerArgumentType.getInteger(ctx, "rows"), false, true, true)))
                                                                            .then(ClientCommandManager.literal("allshades")
                                                                                    .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                            StringArgumentType.getString(ctx, "filename"),
                                                                                            IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                            IntegerArgumentType.getInteger(ctx, "rows"), true, false, true))
                                                                                    .then(ClientCommandManager.literal("dither")
                                                                                            .executes(ctx -> importFileCarpet(ctx.getSource(),
                                                                                                    StringArgumentType.getString(ctx, "filename"),
                                                                                                    IntegerArgumentType.getInteger(ctx, "columns"),
                                                                                                    IntegerArgumentType.getInteger(ctx, "rows"), true, true, true))))))))
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
                                    .executes(ctx -> status(ctx.getSource()))
                                    .then(ClientCommandManager.literal("donors")
                                            .executes(ctx -> statusDonors(ctx.getSource(), 1))
                                            .then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
                                                    .executes(ctx -> statusDonors(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "page"))))))

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
                                    .executes(ctx -> revert(ctx.getSource()))
                                    .then(ClientCommandManager.literal("all")
                                            .executes(ctx -> revertAll(ctx.getSource()))))

                            // ── palette / reduce ───────────────────────────────
                            .then(ClientCommandManager.literal("palette")
                                    .executes(ctx -> palette(ctx.getSource(), false))
                                    .then(ClientCommandManager.literal("all")
                                            .executes(ctx -> palette(ctx.getSource(), true))))
                            .then(ClientCommandManager.literal("reduce")
                                    .executes(ctx -> reduceOne(ctx.getSource(), 255))
                                    .then(ClientCommandManager.literal("undo")
                                            .executes(ctx -> reduceUndo(ctx.getSource()))
                                            .then(ClientCommandManager.literal("all")
                                                    .executes(ctx -> reduceUndoAll(ctx.getSource()))))
                                    .then(ClientCommandManager.literal("strategy")
                                            .then(ClientCommandManager.literal("rarest")
                                                    .executes(ctx -> setReduceStrategy(ctx.getSource(),
                                                            PngToMapColors.Strategy.RAREST)))
                                            .then(ClientCommandManager.literal("closest")
                                                    .executes(ctx -> setReduceStrategy(ctx.getSource(),
                                                            PngToMapColors.Strategy.CLOSEST)))
                                            .then(ClientCommandManager.literal("weighted")
                                                    .executes(ctx -> setReduceStrategy(ctx.getSource(),
                                                            PngToMapColors.Strategy.WEIGHTED))))
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

                            // ── whitelist ──────────────────────────────────────
                            .then(ClientCommandManager.literal("whitelist")
                                    .executes(ctx -> whitelistStatus(ctx.getSource()))
                                    .then(ClientCommandManager.literal("add")
                                            .executes(ctx -> whitelistAdd(ctx.getSource())))
                                    .then(ClientCommandManager.literal("clear")
                                            .executes(ctx -> whitelistClear(ctx.getSource()))))

                            // ── title ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("title")
                                    .executes(ctx -> titleClear(ctx.getSource()))
                                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                            .executes(ctx -> titleSet(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "text")))))

                            // ── author ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("author")
                                    .executes(ctx -> authorShow(ctx.getSource()))
                                    .then(ClientCommandManager.literal("clear")
                                            .executes(ctx -> authorClear(ctx.getSource())))
                                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                            .executes(ctx -> authorSet(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name")))))

                            // ── stride ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("stride")
                                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(2, 100))
                                            .executes(ctx -> applyStrideTile(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "n")))))

                            // ── skip ───────────────────────────────────────────
                            .then(ClientCommandManager.literal("skip")
                                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(2, 100))
                                            .executes(ctx -> applySkipTile(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "n")))))

                            // ── save ───────────────────────────────────────────
                            .then(ClientCommandManager.literal("save")
                                    .executes(ctx -> saveState(ctx.getSource(), null))
                                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                                            .executes(ctx -> saveState(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name")))))

                            // ── load ───────────────────────────────────────────
                            .then(ClientCommandManager.literal("load")
                                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                                            .suggests(SAVE_NAME_SUGGESTIONS)
                                            .executes(ctx -> loadState(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name")))))

                            // ── export ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("export")
                                    .executes(ctx -> exportSchematic(ctx.getSource(), null))
                                    .then(ClientCommandManager.literal("image")
                                            .executes(ctx -> exportImage(ctx.getSource())))
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

                            // ── filter ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("filter")
                                    .then(ClientCommandManager.literal("smooth")
                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                    PngToMapColors.FilterParams.smooth(1.5f), false))
                                            .then(ClientCommandManager.literal("all")
                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                            PngToMapColors.FilterParams.smooth(1.5f), true))
                                                    .then(ClientCommandManager.literal("radius")
                                                            .then(ClientCommandManager.argument("r", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.5f, 5f))
                                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                            PngToMapColors.FilterParams.smooth(com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "r")), true)))))
                                            .then(ClientCommandManager.literal("radius")
                                                    .then(ClientCommandManager.argument("r", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.5f, 5f))
                                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                    PngToMapColors.FilterParams.smooth(com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "r")), false)))))
                                    .then(ClientCommandManager.literal("median")
                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                    PngToMapColors.FilterParams.median(1), false))
                                            .then(ClientCommandManager.literal("all")
                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                            PngToMapColors.FilterParams.median(1), true))
                                                    .then(ClientCommandManager.literal("radius")
                                                            .then(ClientCommandManager.argument("r", IntegerArgumentType.integer(1, 3))
                                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                            PngToMapColors.FilterParams.median(IntegerArgumentType.getInteger(ctx, "r")), true)))))
                                            .then(ClientCommandManager.literal("radius")
                                                    .then(ClientCommandManager.argument("r", IntegerArgumentType.integer(1, 3))
                                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                    PngToMapColors.FilterParams.median(IntegerArgumentType.getInteger(ctx, "r")), false)))))
                                    .then(ClientCommandManager.literal("sharpen")
                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                    PngToMapColors.FilterParams.sharpen(0.8f), false))
                                            .then(ClientCommandManager.literal("all")
                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                            PngToMapColors.FilterParams.sharpen(0.8f), true))
                                                    .then(ClientCommandManager.literal("amount")
                                                            .then(ClientCommandManager.argument("a", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 3f))
                                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                            PngToMapColors.FilterParams.sharpen(com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "a")), true)))))
                                            .then(ClientCommandManager.literal("amount")
                                                    .then(ClientCommandManager.argument("a", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 3f))
                                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                    PngToMapColors.FilterParams.sharpen(com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "a")), false)))))
                                    .then(ClientCommandManager.literal("posterize")
                                            .then(ClientCommandManager.literal("all")
                                                    .then(ClientCommandManager.argument("levels", IntegerArgumentType.integer(2, 16))
                                                            .executes(ctx -> filterInPlace(ctx.getSource(),
                                                                    PngToMapColors.FilterParams.posterize(IntegerArgumentType.getInteger(ctx, "levels")), true))))
                                            .then(ClientCommandManager.argument("levels", IntegerArgumentType.integer(2, 16))
                                                    .executes(ctx -> filterInPlace(ctx.getSource(),
                                                            PngToMapColors.FilterParams.posterize(IntegerArgumentType.getInteger(ctx, "levels")), false)))))

                            // ── resalt ─────────────────────────────────────────
                            .then(ClientCommandManager.literal("resalt")
                                    .executes(ctx -> resalt(ctx.getSource())))

                            // ── requantize ─────────────────────────────────────
                            .then(ClientCommandManager.literal("requantize")
                                    .executes(ctx -> requantizeAll(ctx.getSource(), false))
                                    .then(ClientCommandManager.literal("preserve-transparent")
                                            .executes(ctx -> requantizeAll(ctx.getSource(), true)))
                                    .then(ClientCommandManager.literal("metric")
                                            .then(ClientCommandManager.literal("oklab")
                                                    .executes(ctx -> setRequantizeMetric(ctx.getSource(), PngToMapColors.MatchMetric.OKLAB)))
                                            .then(ClientCommandManager.literal("chroma")
                                                    .executes(ctx -> setRequantizeMetric(ctx.getSource(), PngToMapColors.MatchMetric.CHROMA_FIRST)))
                                            .then(ClientCommandManager.literal("luma")
                                                    .executes(ctx -> setRequantizeMetric(ctx.getSource(), PngToMapColors.MatchMetric.LUMA_FIRST)))
                                            .then(ClientCommandManager.literal("hue")
                                                    .executes(ctx -> setRequantizeMetric(ctx.getSource(), PngToMapColors.MatchMetric.HUE_ONLY)))
                                            .then(ClientCommandManager.literal("rgb")
                                                    .executes(ctx -> setRequantizeMetric(ctx.getSource(), PngToMapColors.MatchMetric.RGB))))
                                    .then(ClientCommandManager.literal("dither")
                                            .then(ClientCommandManager.literal("none")
                                                    .executes(ctx -> setRequantizeDither(ctx.getSource(), PngToMapColors.DitherAlgo.NONE)))
                                            .then(ClientCommandManager.literal("fs")
                                                    .executes(ctx -> setRequantizeDither(ctx.getSource(), PngToMapColors.DitherAlgo.FLOYD_STEINBERG)))
                                            .then(ClientCommandManager.literal("atkinson")
                                                    .executes(ctx -> setRequantizeDither(ctx.getSource(), PngToMapColors.DitherAlgo.ATKINSON)))
                                            .then(ClientCommandManager.literal("bayer")
                                                    .executes(ctx -> setRequantizeDither(ctx.getSource(), PngToMapColors.DitherAlgo.BAYER_4X4)))))

                            // ── codec ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("codec")
                                    .executes(ctx -> showCodec(ctx.getSource()))
                                    .then(ClientCommandManager.literal("banner")
                                            .executes(ctx -> setCodec(ctx.getSource(), CodecMode.BANNER)))
                                    .then(ClientCommandManager.literal("carpet")
                                            .executes(ctx -> setCodec(ctx.getSource(), CodecMode.CARPET)))
                                    .then(ClientCommandManager.literal("carpet+shade")
                                            .executes(ctx -> setCodec(ctx.getSource(), CodecMode.CARPET_SHADE)))
                                    .then(ClientCommandManager.literal("carpet+banners")
                                            .executes(ctx -> setCodec(ctx.getSource(), CodecMode.CARPET_BANNERS)))
                                    .then(ClientCommandManager.literal("carpet+banners+shade")
                                            .executes(ctx -> setCodec(ctx.getSource(), CodecMode.CARPET_BANNERS_SHADE))))

                            // ── mux ────────────────────────────────────────────
                            .then(ClientCommandManager.literal("mux")
                                    .executes(ctx -> applyMux(ctx.getSource()))
                                    .then(ClientCommandManager.literal("undo")
                                            .executes(ctx -> unmuxTiles(ctx.getSource()))))

                            // ── sparse ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("sparse")
                                    .executes(ctx -> applySparse(ctx.getSource(), false))
                                    .then(ClientCommandManager.literal("all")
                                            .executes(ctx -> applySparse(ctx.getSource(), true))))

                            // ── password ────────────────────────────────────────
                            .then(ClientCommandManager.literal("password")
                                    .executes(ctx -> passwordList(ctx.getSource()))
                                    .then(ClientCommandManager.literal("list")
                                            .executes(ctx -> passwordList(ctx.getSource())))
                                    .then(ClientCommandManager.literal("clear")
                                            .executes(ctx -> passwordClearAll(ctx.getSource())))
                                    .then(ClientCommandManager.literal("add")
                                            .then(ClientCommandManager.argument("pw", StringArgumentType.string())
                                                    .executes(ctx -> passwordAdd(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "pw")))))
                                    .then(ClientCommandManager.literal("remove")
                                            .then(ClientCommandManager.argument("pw", StringArgumentType.string())
                                                    .executes(ctx -> passwordRemove(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "pw")))))
                                    .then(ClientCommandManager.literal("encrypt")
                                            .executes(ctx -> encryptOff(ctx.getSource()))
                                            .then(ClientCommandManager.literal("off")
                                                    .executes(ctx -> encryptOff(ctx.getSource())))
                                            .then(ClientCommandManager.argument("pw", StringArgumentType.string())
                                                    .executes(ctx -> encryptSet(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "pw")))))
                                    )

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
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
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
                        PngToMapColors.FitResult fit = PngToMapColors.reduceToFitKJ(
                                mapColors, manifestCheck, MAX_CHUNKS);
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
                        triggerAutoSave(filename, source);

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
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        final MinecraftClient client = MinecraftClient.getInstance();
        source.sendFeedback(Text.literal("§7Importing §f" + filename + "§7 in background..."));

        Thread t = new Thread(() -> {
            try {
                PngToMapColors.GifResult result = PngToMapColors.convertGif(
                        filePath, !allShades, 0, dither, columns, rows);

                int frameCount = result.frameCount();
                int[] rawDelays = result.frameDelays;
                boolean uniformDelay = true;
                for (int d : rawDelays) if (d != rawDelays[0]) { uniformDelay = false; break; }
                int[] manifestDelays = uniformDelay ? new int[]{rawDelays[0]} : rawDelays;

                int tileFlags = (frameCount > 1 ? PayloadManifest.FLAG_ANIMATED : 0)
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
                    byte[] allFramesBytes = new byte[frameCount * MAP_BYTES];
                    for (int f = 0; f < frameCount; f++)
                        System.arraycopy(result.tileFrames[tileIdx][f], 0, allFramesBytes, f * MAP_BYTES, MAP_BYTES);
                    long crc = PayloadManifest.crc32(result.tileFrames[tileIdx][0]);
                    byte[] manifest = PayloadManifest.toBytes(
                            tileFlags, columns, rows, col, row, crc, playerName, capturedTitle,
                            0, frameCount, 0, manifestDelays);
                    List<String> chunks = buildChunks(manifest,
                            PayloadManifest.withTrailing(manifest, manifestDelays, allFramesBytes));
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
                    tile.frameSourceIndices = new ArrayList<>();
                    for (int f = 0; f < frameCount; f++) tile.frameSourceIndices.add(f);
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
                        triggerAutoSave(filename, source);

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
    // import header <lc-ls-banner> <mapshot.png>
    // ════════════════════════════════════════════════════════════════════

    /**
     * Reconstructs a carpet-encoded PayloadState from a 128×128 map screenshot and
     * an LC/LS manifest banner string.
     *
     * <p>The image pixels are nearest-color matched to map color bytes.  The carpet
     * and shade channels are extracted from those bytes using the byte counts declared
     * in the header.  Any inline base64 overflow embedded in the header is appended.
     * The assembled payload is stored in {@code carpetCompressedB64} so the map-color
     * preview and schematic export work without additional banners.
     */
    private static int importHeader(FabricClientCommandSource source,
            String header, String filename) {
        // 1. Parse header
        boolean isLS = header.startsWith("LS");
        boolean isLC = header.startsWith("LC");
        if (!isLC && !isLS) {
            source.sendError(Text.literal("§cInvalid header — must start with LC or LS."));
            return 0;
        }
        if (isLC && header.length() < 6) {
            source.sendError(Text.literal("§cInvalid LC header — expected LC<4hex>[<b64>]."));
            return 0;
        }
        if (isLS && header.length() < 10) {
            source.sendError(Text.literal("§cInvalid LS header — expected LS<4hex><4hex>[<b64>]."));
            return 0;
        }
        int totalBytes, shadeBytes;
        String inlineB64;
        try {
            totalBytes = Integer.parseInt(header.substring(2, 6), 16);
            if (isLS) {
                shadeBytes = Integer.parseInt(header.substring(6, 10), 16);
                inlineB64  = header.length() > 10 ? header.substring(10) : "";
            } else {
                shadeBytes = 0;
                inlineB64  = header.length() > 6 ? header.substring(6) : "";
            }
        } catch (NumberFormatException e) {
            source.sendError(Text.literal("§cInvalid header — hex parse failed: " + e.getMessage()));
            return 0;
        }
        int carpetBytes = Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES);
        byte[] inlineOverflow;
        try {
            inlineOverflow = inlineB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(inlineB64);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("§cInvalid base64 in header inline overflow."));
            return 0;
        }

        // 2. Load the 128×128 image
        MinecraftClient client = MinecraftClient.getInstance();
        Path filePath = client.runDirectory.toPath().resolve("loominary_data").resolve(filename);
        if (!Files.exists(filePath)) {
            source.sendError(Text.literal("§cFile not found: loominary_data/" + filename));
            return 0;
        }
        final BufferedImage img;
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            img = ImageIO.read(new ByteArrayInputStream(fileBytes));
        } catch (IOException e) {
            source.sendError(Text.literal("§cError reading image: " + e.getMessage()));
            return 0;
        }
        if (img == null) {
            source.sendError(Text.literal("§cCouldn't decode image: " + filename));
            return 0;
        }
        if (img.getWidth() != MAP_SIZE || img.getHeight() != MAP_SIZE) {
            source.sendError(Text.literal(String.format(
                    "§cImage must be %d×%d pixels, got %d×%d.",
                    MAP_SIZE, MAP_SIZE, img.getWidth(), img.getHeight())));
            return 0;
        }

        // 3. Nearest-color match: image pixels → map color bytes (all shades)
        byte[] mapColors = PngToMapColors.convert(img, false);

        // 4. Extract carpet and shade channels
        byte[] carpetData;
        try {
            carpetData = CarpetChannel.decodeBytes(mapColors, carpetBytes);
        } catch (IllegalStateException e) {
            source.sendError(Text.literal(
                    "§cCarpet decode failed — image may not be a carpet map: " + e.getMessage()));
            return 0;
        }
        byte[] shadeData = shadeBytes > 0
                ? CarpetChannel.decodeShade(mapColors, shadeBytes)
                : new byte[0];

        // 5. Assemble compressed payload: carpet + shade + inline overflow
        int assembledLen = carpetBytes + shadeData.length + inlineOverflow.length;
        byte[] compressed = new byte[assembledLen];
        System.arraycopy(carpetData,     0, compressed, 0,                               carpetBytes);
        System.arraycopy(shadeData,      0, compressed, carpetBytes,                     shadeData.length);
        System.arraycopy(inlineOverflow, 0, compressed, carpetBytes + shadeData.length,  inlineOverflow.length);

        int missingBytes = totalBytes - assembledLen;

        // 6. Warn if the assembled data is not a valid zstd frame (only relevant when complete)
        if (missingBytes == 0 && Zstd.getFrameContentSize(compressed) < 0) {
            source.sendFeedback(Text.literal(
                    "§e⚠ Assembled payload is not a valid zstd frame — image colors may be inaccurate."));
        }

        // 7. Build PayloadState (single-tile carpet batch)
        PayloadState.clear();
        PayloadState.TileData tile = new PayloadState.TileData();
        tile.carpetEncoded = true;
        tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(compressed);
        tile.chunks.add(header); // chunk[0] = LC/LS manifest banner name
        tile.currentIndex = 0;

        PayloadState.tiles.add(tile);
        PayloadState.gridColumns = 1;
        PayloadState.gridRows = 1;
        PayloadState.activeTileIndex = 0;
        PayloadState.currentSourceFilename = filename;
        PayloadState.allShades = isLS;
        PayloadState.dither = false;
        PayloadState.syncFromActiveTile();
        PayloadState.save();

        // 8. Feedback
        source.sendFeedback(Text.literal(String.format(
                "§aReconstructed carpet state from §f%s §7(%d bytes: %d carpet + %d shade + %d inline).",
                filename, assembledLen, carpetBytes, shadeData.length, inlineOverflow.length)));
        if (missingBytes > 0) {
            source.sendFeedback(Text.literal(String.format(
                    "§e⚠ %d bytes from overflow banners not recoverable from image — "
                    + "map preview may be corrupt.",
                    missingBytes)));
        } else {
            source.sendFeedback(Text.literal(
                    "§aPayload complete. Run §f/loominary export§a to generate a schematic."));
        }
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // import <file> carpet
    // ════════════════════════════════════════════════════════════════════

    private static int importFileCarpet(FabricClientCommandSource source,
            String filename, int columns, int rows, boolean allShades, boolean dither) {
        return importFileCarpet(source, filename, columns, rows, allShades, dither, false);
    }

    private static int importFileCarpet(FabricClientCommandSource source,
            String filename, int columns, int rows, boolean allShades, boolean dither, boolean mux) {
        if (PayloadState.codecMode == CodecMode.BANNER) {
            // Codec is banner-only: carpet platform makes no sense — use the banner import path.
            return importFile(source, filename, columns, rows, allShades, dither);
        }
        if (filename.isEmpty()) {
            source.sendError(Text.literal("§cUsage: /loominary import <filename> carpet [cols] [rows] [mux] [allshades] [dither]"));
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
            return importGifCarpet(source, filename, filePath, columns, rows, allShades, dither, mux);
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
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
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
                        enc = buildCarpetEncoding(manifestBytes, mapColors, col, row);
                    } catch (IllegalStateException overflow) {
                        // Try to auto-reduce colors to fit.
                        try {
                            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                                    mapColors, manifestBytes, CHUNK_SIZE, MAX_CHUNKS_CARPET_LOOM);
                            mapColors = fit.mapColors;
                            manifestBytes = PayloadManifest.toBytes(tileFlags, columns, rows, col, row,
                                    PayloadManifest.crc32(mapColors), playerName, capturedTitle);
                            enc = buildCarpetEncoding(manifestBytes, mapColors, col, row);
                            note = String.format("reduced %d→%d colors to fit",
                                    fit.originalDistinctColors, fit.originalDistinctColors - fit.colorsRemoved);
                        } catch (Exception overflow2) {
                            // Still over budget — import anyway, user can reduce manually.
                            byte[] combined = new byte[manifestBytes.length + mapColors.length];
                            System.arraycopy(manifestBytes, 0, combined, 0,                   manifestBytes.length);
                            System.arraycopy(mapColors,     0, combined, manifestBytes.length, mapColors.length);
                            byte[] compressed = compress(combined);
                            enc = encodeLoomFromCompressed(compressed, col, row, PayloadState.codecMode);
                            note = "over budget — use /loominary reduce";
                        }
                    }
                    PayloadState.TileData tile = new PayloadState.TileData();
                    tile.chunks.addAll(enc.allChunks());
                    tile.currentIndex = 0;
                    tile.carpetEncoded = true;
                    tile.loomEncoded = (enc.headerChunk() == null);
                    tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
                    newTiles.add(tile);
                    tileNotes.add(note);
                }

                // Mux post-pass: redistribute overflow to spare capacity on under-budget tiles.
                boolean muxApplied = false, muxSuccess = true;
                boolean anyOverBudgetStatic = newTiles.stream().anyMatch(
                        tile2 -> carpetCompressedBytes(tile2) > maxBytesForTile(tile2));
                if (mux && anyOverBudgetStatic) {
                    muxApplied = true;
                    muxSuccess = poolMuxTiles(newTiles, columns, rows) == 0;
                }

                final List<PayloadState.TileData> finalTiles = newTiles;
                final List<String> finalNotes = tileNotes;
                final int finalTileCount = totalTiles;
                final boolean finalMuxApplied = muxApplied;
                final boolean finalMuxSuccess = muxSuccess;

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
                        triggerAutoSave(filename, source);

                        String modeTag = "carpet, " + (allShades ? "all shades" : "legal palette")
                                + (dither ? ", dithered" : "")
                                + (finalMuxApplied && finalMuxSuccess ? ", mux" : "");
                        source.sendFeedback(Text.literal(String.format(
                                "§aLoaded %s (%dx%d) as %dx%d grid (%d tile%s) §7[%s]",
                                filename, img.getWidth(), img.getHeight(),
                                columns, rows, finalTileCount, finalTileCount == 1 ? "" : "s", modeTag)));
                        for (int i = 0; i < finalTiles.size(); i++) {
                            PayloadState.TileData tile = finalTiles.get(i);
                            int totalBytes = carpetCompressedBytes(tile);
                            int cap = maxBytesForTile(tile);
                            boolean overBudget = totalBytes > cap;
                            int cb = Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES);
                            int sb = Math.min(totalBytes - cb, CarpetChannel.MAX_SHADE_BYTES);
                            int carpetRows = sb > 0 ? 128 : (cb * 2 + 127) / 128;
                            String channelInfo = sb > 0
                                    ? carpetRows + " carpet rows (staircase) + " + sb + " shade bytes"
                                    : carpetRows + " carpet rows";
                            String muxTag = tile.muxReceiver ? " §d[mux receiver]"
                                    : tile.muxed ? " §d[mux donor]" : "";
                            int ovfCount = overflowBannerCount(tile, tile.chunks);
                            String line = String.format("§7  %s: §f%s, %d overflow banner%s §7(%d bytes)%s%s",
                                    PayloadState.tileLabel(i), channelInfo, ovfCount,
                                    ovfCount == 1 ? "" : "s", totalBytes,
                                    overBudget ? " §c[OVER BUDGET]" : "", muxTag);
                            if (finalNotes.get(i) != null) line += " §e(" + finalNotes.get(i) + ")";
                            source.sendFeedback(Text.literal(line));
                        }
                        if (finalMuxApplied && !finalMuxSuccess) {
                            source.sendFeedback(Text.literal(
                                    "§c⚠ Mux pooling could not fit all overflow — some tiles still over budget."));
                        }
                        int maxOverflow = finalTiles.stream().mapToInt(ti -> overflowBannerCount(ti, ti.chunks)).max().orElse(0);
                        if (maxOverflow > 0)
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ %d overflow banner%s needed — rename at anvil and click with map.",
                                    maxOverflow, maxOverflow == 1 ? "" : "s")));
                        else
                            source.sendFeedback(Text.literal("§aNo overflow banners needed — just place the carpet schematic and scan with your map!"));
                        source.sendFeedback(Text.literal("§7Run §f/loominary export§7 when ready to generate the schematic."));
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
        return importGifCarpet(source, filename, filePath, columns, rows, allShades, dither, false);
    }

    private static int importGifCarpet(FabricClientCommandSource source, String filename,
            Path filePath, int columns, int rows, boolean allShades, boolean dither, boolean mux) {
        if (PayloadState.codecMode == CodecMode.BANNER) {
            return importGif(source, filename, filePath, columns, rows, allShades, dither);
        }
        // title + importInProgress already set by importFileCarpet caller
        importInProgress = true;
        final String capturedTitle = PayloadState.currentTitle;
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        final MinecraftClient client = MinecraftClient.getInstance();
        source.sendFeedback(Text.literal("§7Importing §f" + filename + "§7 in background..."));

        Thread t = new Thread(() -> {
          try {
            PngToMapColors.GifResult result = PngToMapColors.convertGif(
                    filePath, !allShades, 0, dither, columns, rows);

            int frameCount = result.frameCount();
            int[] rawDelays = result.frameDelays;

            boolean uniformDelay = true;
            for (int d : rawDelays) if (d != rawDelays[0]) { uniformDelay = false; break; }
            int[] manifestDelays = uniformDelay ? new int[]{rawDelays[0]} : rawDelays;

            int tileFlags = (frameCount > 1 ? PayloadManifest.FLAG_ANIMATED : 0)
                    | (allShades ? PayloadManifest.FLAG_ALL_SHADES : 0);

            int totalTiles = columns * rows;
            List<PayloadState.TileData> newTiles = new ArrayList<>();
            List<String> tileNotes = new ArrayList<>();
            boolean anyOverflow = false;

            for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                int col = tileIdx % columns;
                int row = tileIdx / columns;

                byte[] allFramesBytes = new byte[frameCount * MAP_BYTES];
                for (int f = 0; f < frameCount; f++)
                    System.arraycopy(result.tileFrames[tileIdx][f], 0, allFramesBytes, f * MAP_BYTES, MAP_BYTES);

                long crc = PayloadManifest.crc32(result.tileFrames[tileIdx][0]);
                byte[] manifestBytes = (frameCount > 1)
                        ? PayloadManifest.toBytes(tileFlags, columns, rows, col, row,
                                crc, playerName, capturedTitle, 0, frameCount, 0, manifestDelays)
                        : PayloadManifest.toBytes(tileFlags, columns, rows, col, row,
                                crc, playerName, capturedTitle);
                byte[] payloadBytes = (frameCount > 1)
                        ? PayloadManifest.withTrailing(manifestBytes, manifestDelays, allFramesBytes)
                        : allFramesBytes;

                CarpetEncoding enc;
                String tileNote = null;
                try {
                    enc = buildCarpetEncoding(manifestBytes, payloadBytes, col, row);
                } catch (Exception overflow) {
                    // Payload too large for codec — import anyway, user can reduce later.
                    byte[] combined = new byte[manifestBytes.length + payloadBytes.length];
                    System.arraycopy(manifestBytes, 0, combined, 0,                    manifestBytes.length);
                    System.arraycopy(payloadBytes,  0, combined, manifestBytes.length, payloadBytes.length);
                    byte[] compressed = compress(combined);
                    enc = encodeLoomFromCompressed(compressed, col, row, PayloadState.codecMode);
                    tileNote = "over budget — use /loominary reduce";
                }
                int encCap = maxBytesForMode(PayloadState.codecMode);
                if (enc.compressed().length > encCap) {
                    anyOverflow = true;
                }

                PayloadState.TileData tile = new PayloadState.TileData();
                tile.chunks.addAll(enc.allChunks());
                tile.currentIndex = 0;
                tile.carpetEncoded = true;
                tile.loomEncoded = (enc.headerChunk() == null);
                tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(enc.compressed());
                tile.frameCount = frameCount;
                tile.frameDelays = new ArrayList<>();
                for (int d : rawDelays) tile.frameDelays.add(d);
                tile.frameSourceIndices = new ArrayList<>();
                for (int f = 0; f < frameCount; f++) tile.frameSourceIndices.add(f);
                newTiles.add(tile);
                tileNotes.add(tileNote);
            }

            // Mux post-pass: redistribute overflow to spare capacity on under-budget tiles.
            boolean muxApplied = false, muxSuccess = true;
            if (mux && anyOverflow) {
                muxApplied = true;
                muxSuccess = poolMuxTiles(newTiles, columns, rows) == 0;
                // Recompute anyOverflow after pooling.
                anyOverflow = newTiles.stream().anyMatch(
                        tile2 -> carpetCompressedBytes(tile2) > maxBytesForTile(tile2));
            }

            final List<PayloadState.TileData> finalTiles = newTiles;
            final List<String> finalNotes = tileNotes;
            final int finalTileCount = totalTiles;
            final int finalFrameCount = frameCount;
            final boolean finalAnyOverflow = anyOverflow;
            final boolean finalUniform = uniformDelay;
            final int[] finalManifestDelays = manifestDelays;
            final int[] finalRawDelays = rawDelays;
            final boolean finalMuxApplied = muxApplied;
            final boolean finalMuxSuccess = muxSuccess;

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
                    triggerAutoSave(filename, source);

                    String modeTag = "carpet, " + (allShades ? "all shades" : "legal palette")
                            + (dither ? ", dithered" : "")
                            + (finalMuxApplied && finalMuxSuccess ? ", mux" : "")
                            + ", animated";
                    source.sendFeedback(Text.literal(String.format(
                            "§aLoaded %s as %dx%d grid (%d tile%s, §e%d frame%s§a) §7[%s]",
                            filename, columns, rows, finalTileCount, finalTileCount == 1 ? "" : "s",
                            finalFrameCount, finalFrameCount == 1 ? "" : "s", modeTag)));
                    for (int i = 0; i < finalTiles.size(); i++) {
                        PayloadState.TileData tile = finalTiles.get(i);
                        int overflowBanners = overflowBannerCount(tile, tile.chunks);
                        int totalBytes = carpetCompressedBytes(tile);
                        int tileCap = maxBytesForTile(tile);
                        boolean overBudget = totalBytes > tileCap;
                        int carpetRows = (Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES) * 2 + 127) / 128;
                        String budgetTag = overBudget
                                ? String.format(" §c[OVER BUDGET by %d bytes]", totalBytes - tileCap) : "";
                        String muxTag = tile.muxReceiver ? " §d[mux receiver]"
                                : tile.muxed ? " §d[mux donor]" : "";
                        source.sendFeedback(Text.literal(String.format(
                                "§7  %s: §f%d carpet rows, %d overflow banner%s §7(%d bytes)%s%s",
                                PayloadState.tileLabel(i), carpetRows, overflowBanners,
                                overflowBanners == 1 ? "" : "s", totalBytes, budgetTag, muxTag)));
                    }
                    String delayDesc = finalUniform
                            ? finalManifestDelays[0] + "ms/frame"
                            : "variable (" + finalRawDelays[0] + "–"
                                    + java.util.Arrays.stream(finalRawDelays).max().getAsInt() + "ms)";
                    source.sendFeedback(Text.literal("§7Delay: " + delayDesc));
                    if (finalMuxApplied && !finalMuxSuccess) {
                        source.sendFeedback(Text.literal(
                                "§c⚠ Mux pooling could not fit all overflow — some tiles still over budget."));
                    }
                    if (finalAnyOverflow) {
                        source.sendFeedback(Text.literal(
                                "§e⚠ Some tiles are over budget — use §f/loominary reduce§e or §f/loominary edit§e before placing."));
                    } else {
                        int maxOvf = finalTiles.stream().mapToInt(ti -> overflowBannerCount(ti, ti.chunks)).max().orElse(0);
                        if (maxOvf > 0)
                            source.sendFeedback(Text.literal(String.format(
                                    "§e⚠ %d overflow banner%s needed — rename at anvil and click with map.",
                                    maxOvf, maxOvf == 1 ? "" : "s")));
                        else
                            source.sendFeedback(Text.literal("§aNo overflow banners needed — just place the carpet schematic and scan with your map!"));
                    }
                    source.sendFeedback(Text.literal("§7Run §f/loominary export§7 when ready to generate the schematic."));
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
        String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        // Stolen tiles are standalone — use 1×1 grid, allShades=false (unknown origin)
        byte[] manifestForSizeCheck = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, 0L, playerName, PayloadState.currentTitle);
        String note = null;
        if (buildChunks(manifestForSizeCheck, mapColors).size() > MAX_CHUNKS) {
            PngToMapColors.FitResult fit = PngToMapColors.reduceToFitKJ(
                    mapColors, manifestForSizeCheck, MAX_CHUNKS);
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
        String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        byte[] manifestBytes = PayloadManifest.toBytes(
                0, 1, 1, 0, 0, PayloadManifest.crc32(mapColors), playerName,
                PayloadState.currentTitle);

        CarpetEncoding enc;
        String note = null;
        try {
            enc = buildCarpetEncoding(manifestBytes, mapColors);
        } catch (IllegalStateException overflow) {
            int stealBudget = (PayloadState.codecMode != CodecMode.BANNER)
                    ? MAX_CHUNKS_CARPET_LOOM : MAX_CHUNKS_CARPET;
            PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                    mapColors, manifestBytes, CHUNK_SIZE, stealBudget);
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

        int overflowBanners = overflowBannerCount(tile, tile.chunks);
        int totalBytes = enc.compressed().length;
        int carpetRows = (enc.carpetBytes() * 2 + 127) / 128;

        PayloadState.save();
        String msg = String.format(
                "§aStole map id=%d at %s → %s [carpet, %d rows, %d compressed bytes].",
                fm.mapId.id(), maskedPos(fm.frame.getBlockPos()),
                PayloadState.tileLabel(tileIdx), carpetRows, totalBytes);
        if (note != null) msg += " §e(" + note + ")";
        source.sendFeedback(Text.literal(msg));
        source.sendFeedback(Text.literal("§7Run §f/loominary export§7 when ready to generate the schematic."));

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

        // Per-tile status lines.  Carpet and banner tiles have very different progress models:
        //   Carpet: no per-banner placement to track — show encoding state and byte budget.
        //   Banner: each chunk is one anvil rename; show placement progress.
        int bannerDone = 0, bannerTotal = 0;
        boolean anyOverBudget = false;

        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            if (tile.isDonorOnly) continue; // shown in donor summary below
            boolean active = (i == PayloadState.activeTileIndex);
            String label = PayloadState.tileLabel(i);
            // For mux receivers show the full logical payload size (stored in carpetCompressedB64)
            // alongside the own-segment size (in muxCargoB64) so the user can see how much the
            // donor strand is carrying.
            String muxTag = "";
            if (tile.muxReceiver && tile.carpetCompressedB64 != null) {
                // muxCargoB64 = own segment; carpetCompressedB64 = full logical payload.
                String fullB64 = tile.carpetCompressedB64;
                int fullBytes  = fullB64.length() * 3 / 4
                        - (fullB64.endsWith("==") ? 2 : fullB64.endsWith("=") ? 1 : 0);
                muxTag = String.format(" §d[mux rx — full payload: %d bytes → donors]", fullBytes);
            }

            if (tile.carpetEncoded) {
                List<String> chunks = active ? PayloadState.ACTIVE_CHUNKS : tile.chunks;
                int bytes = carpetCompressedBytes(tile);
                int cap   = maxBytesForTile(tile);
                int ovf   = overflowBannerCount(tile, chunks);
                boolean over = !tile.muxed && bytes > cap;
                if (over) anyOverBudget = true;

                String encTag = tile.loomEncoded ? "loom" : "carpet";
                String bytesStr = over
                        ? String.format("§c%d/%d bytes [OVER BUDGET by %d]", bytes, cap, bytes - cap)
                        : String.format("§8%d bytes", bytes);
                String ovfStr = ovf > 0
                        ? String.format(", §f%d§8 overflow banner%s", ovf, ovf == 1 ? "" : "s")
                        : "";
                String activeTag = active ? " §7(active)" : "";
                String stateTag = over ? "§c✗" : "§a✓";
                source.sendFeedback(Text.literal(String.format(
                        "  %s: %s §8[%s] %s%s%s%s",
                        label, stateTag, encTag, bytesStr, ovfStr, muxTag, activeTag)));

            } else {
                int done, total;
                if (active) {
                    done  = PayloadState.activeChunkIndex;
                    total = PayloadState.ACTIVE_CHUNKS.size();
                } else {
                    done  = tile.currentIndex;
                    total = tile.chunks.size();
                }
                bannerDone  += done;
                bannerTotal += total;
                boolean over = total > MAX_CHUNKS;
                if (over) anyOverBudget = true;

                String statusStr;
                if (done >= total)
                    statusStr = "§a✓";
                else if (active)
                    statusStr = String.format("§e► %d/%d §7(active)", done, total);
                else
                    statusStr = String.format("§7  %d/%d", done, total);

                String budgetStr = over
                        ? String.format(" §c[%d/%d — OVER BUDGET by %d]", total, MAX_CHUNKS, total - MAX_CHUNKS)
                        : String.format(" §8[%d/%d banners]", total, MAX_CHUNKS);
                source.sendFeedback(Text.literal(String.format(
                        "  %s: %s%s%s", label, statusStr, budgetStr, muxTag)));
            }
        }

        // Donor-tile summary (shown above the overall line when donors exist).
        long donorCount = PayloadState.tiles.stream().filter(t -> t.isDonorOnly).count();
        if (donorCount > 0) {
            int donorBanners = PayloadState.tiles.stream()
                    .filter(t -> t.isDonorOnly)
                    .mapToInt(t -> overflowBannerCount(t, t.chunks))
                    .sum();
            source.sendFeedback(Text.literal(String.format(
                    "§d  + %d donor tile%s, %d overflow banner%s total"
                    + " §8— §f/loominary status donors§8 for per-tile progress"
                    + " · place within 32 blocks of art",
                    donorCount, donorCount == 1 ? "" : "s",
                    donorBanners, donorBanners == 1 ? "" : "s")));
        }

        // Summary line.
        boolean anyCarpet  = PayloadState.tiles.stream().anyMatch(t -> t.carpetEncoded);
        boolean anyBanners = PayloadState.tiles.stream().anyMatch(t -> !t.carpetEncoded);
        String budgetWarn = anyOverBudget ? " §c[some tiles over budget — /loominary reduce]" : "";
        if (anyCarpet && !anyBanners) {
            source.sendFeedback(Text.literal(String.format(
                    "§7%d carpet tile%s  §8[codec: %s]%s",
                    PayloadState.totalTiles(),
                    PayloadState.totalTiles() == 1 ? "" : "s",
                    PayloadState.codecMode.label(), budgetWarn)));
        } else if (anyBanners && !anyCarpet) {
            source.sendFeedback(Text.literal(String.format(
                    "§7Overall: §f%d§7/§f%d §7banners placed%s",
                    bannerDone, bannerTotal, budgetWarn)));
        } else {
            // Mixed batch
            source.sendFeedback(Text.literal(String.format(
                    "§7Overall: %d carpet + %d banner tile%s%s",
                    (int) PayloadState.tiles.stream().filter(t -> t.carpetEncoded).count(),
                    (int) PayloadState.tiles.stream().filter(t -> !t.carpetEncoded).count(),
                    PayloadState.totalTiles() == 1 ? "" : "s", budgetWarn)));
        }

        int whitelistCount = PayloadState.whitelistedBannerNames.size();
        if (whitelistCount > 0) {
            source.sendFeedback(Text.literal(String.format(
                    "§7Whitelist: §f%d§7 named banner%s reusable as raw material.",
                    whitelistCount, whitelistCount == 1 ? "" : "s")));
        }
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // status donors [page]
    // ════════════════════════════════════════════════════════════════════

    private static final int DONORS_PER_PAGE = 10;

    /**
     * Paged status for donor tiles.  Each page shows up to {@value #DONORS_PER_PAGE} donors
     * with their tile index, overflow banner count, and placement progress.
     */
    private static int statusDonors(FabricClientCommandSource source, int page) {
        List<Integer> donorIndices = new ArrayList<>();
        for (int i = 0; i < PayloadState.tiles.size(); i++)
            if (PayloadState.tiles.get(i).isDonorOnly) donorIndices.add(i);

        if (donorIndices.isEmpty()) {
            source.sendFeedback(Text.literal("§eNo donor tiles — run §f/loominary mux§e first."));
            return 1;
        }

        int totalPages = (donorIndices.size() + DONORS_PER_PAGE - 1) / DONORS_PER_PAGE;
        int p = Math.max(1, Math.min(page, totalPages));
        int start = (p - 1) * DONORS_PER_PAGE;
        int end   = Math.min(start + DONORS_PER_PAGE, donorIndices.size());

        source.sendFeedback(Text.literal(String.format(
                "§6=== Donor tiles (page %d/%d) ===", p, totalPages)));

        int donorNum = 0;
        for (int idx : donorIndices) {
            donorNum++;
            if (donorNum <= start || donorNum > end) continue;
            PayloadState.TileData t = PayloadState.tiles.get(idx);
            int done  = (idx == PayloadState.activeTileIndex)
                    ? PayloadState.activeChunkIndex : t.currentIndex;
            int total = (idx == PayloadState.activeTileIndex)
                    ? PayloadState.ACTIVE_CHUNKS.size() : t.chunks.size();

            String progress;
            if (done >= total && total > 0) progress = "§a✓ done";
            else if (idx == PayloadState.activeTileIndex)
                progress = String.format("§e► %d/%d §7(active)", done, total);
            else
                progress = String.format("§7%d/%d", done, total);

            source.sendFeedback(Text.literal(String.format(
                    "  §8donor %03d §7(tile %d): %s §8[%d banner%s]",
                    donorNum, idx, progress, total, total == 1 ? "" : "s")));
        }

        if (totalPages > 1) {
            String nav = "";
            if (p > 1) nav += "§f/loominary status donors " + (p - 1) + "§7 ← ";
            nav += String.format("§8page %d/%d", p, totalPages);
            if (p < totalPages) nav += " §7→ §f/loominary status donors " + (p + 1);
            source.sendFeedback(Text.literal("§7" + nav));
        }
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
        int count = PayloadState.tiles.size();
        int next = (PayloadState.activeTileIndex + 1) % count;
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
        if (!activeTileHasContent()) {
            source.sendError(Text.literal("§cActive tile has no data."));
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
                if (!tileHasContent(tile)) continue;

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

    private static int revertAll(FabricClientCommandSource source) {
        if (originalColors.isEmpty()) {
            source.sendFeedback(Text.literal("§7No previewed maps to revert."));
            return 1;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int count = 0;
        for (int id : new ArrayList<>(originalColors.keySet())) {
            byte[] saved = originalColors.remove(id);
            if (saved == null) continue;
            MapIdComponent mid = new MapIdComponent(id);
            MapState ms = FilledMapItem.getMapState(mid, client.world);
            if (ms != null) MapBannerDecoder.paintMap(client, mid, ms, saved);
            MapBannerDecoder.unregisterAnimated(id);
            count++;
        }
        source.sendFeedback(Text.literal(String.format(
                "§aReverted %d previewed map%s.", count, count == 1 ? "" : "s")));
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

    private static int palette(FabricClientCommandSource source, boolean all) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (!activeTileHasContent()) {
            source.sendError(Text.literal("§cActive tile has no data."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        // Snapshot all state needed before going off-thread.
        PayloadState.syncToActiveTile();
        final int activeTileIdx  = PayloadState.activeTileIndex;
        final int tileCount      = PayloadState.tiles.size();
        final List<PayloadState.TileData> snapTiles   = new ArrayList<>(tileCount);
        final List<List<String>>           snapChunks  = new ArrayList<>(tileCount);
        for (int i = 0; i < tileCount; i++) {
            snapTiles .add(snapshotTile(PayloadState.tiles.get(i)));
            snapChunks.add(i == activeTileIdx
                    ? new ArrayList<>(PayloadState.ACTIVE_CHUNKS)
                    : new ArrayList<>(PayloadState.tiles.get(i).chunks));
        }
        final PngToMapColors.Strategy snapStrategy = reduceStrategy;
        final boolean hasPreReduction = preReductionChunks.containsKey(activeTileIdx);

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Computing palette" + (all ? " (all tiles)" : "") + "…"));
        MinecraftClient client = MinecraftClient.getInstance();

        Thread t = new Thread(() -> {
            try {
                // ── Build union and collect budget info ──────────────────
                final byte[] union;
                final int fc;
                final String header;
                final String budgetLine;
                List<Integer> perTileColors = null;

                if (!all) {
                    PayloadState.TileData tileData = snapTiles.get(activeTileIdx);
                    byte[][] frames = resolveAllFramesForTile(tileData, snapChunks.get(activeTileIdx));
                    fc    = frames.length;
                    union = mergeFrames(frames);
                    header = PayloadState.tileLabel(activeTileIdx);

                    if (tileData.carpetEncoded) {
                        int cb  = carpetCompressedBytes(tileData);
                        int cap = maxBytesForTile(tileData);
                        boolean over = cb > cap;
                        budgetLine = String.format("§7Compressed:      §f%d §7/ §f%d bytes%s",
                                cb, cap,
                                over ? " §c(OVER BUDGET by " + (cb - cap) + ")" : " §a✓");
                    } else {
                        int bannerCount = snapChunks.get(activeTileIdx).size();
                        budgetLine = String.format("§7Banners needed:  §f%d §7/ §f%d%s",
                                bannerCount, MAX_CHUNKS,
                                bannerCount > MAX_CHUNKS
                                        ? " §c(OVER BUDGET by " + (bannerCount - MAX_CHUNKS) + ")" : " §a✓");
                    }
                } else {
                    int totalFrames = 0;
                    int overBudgetCount = 0, totalChunks2 = 0;
                    long totalCompressedBytes = 0;
                    boolean anyCarpet = false;
                    List<byte[]> allFramesList = new ArrayList<>();

                    for (int i = 0; i < tileCount; i++) {
                        PayloadState.TileData td = snapTiles.get(i);
                        List<String> chunks = snapChunks.get(i);
                        if (chunks.isEmpty()) continue;
                        byte[][] tileFrames = resolveAllFramesForTile(td, chunks);
                        for (byte[] f2 : tileFrames) allFramesList.add(f2);
                        totalFrames += tileFrames.length;
                        if (td.carpetEncoded) {
                            anyCarpet = true;
                            int cb = carpetCompressedBytes(td);
                            totalCompressedBytes += cb;
                            if (cb > maxBytesForTile(td)) overBudgetCount++;
                        } else {
                            totalChunks2 += chunks.size();
                            if (chunks.size() > MAX_CHUNKS) overBudgetCount++;
                        }
                    }

                    byte[] combined2 = new byte[allFramesList.size() * MAP_BYTES];
                    for (int i = 0; i < allFramesList.size(); i++)
                        System.arraycopy(allFramesList.get(i), 0, combined2, i * MAP_BYTES, MAP_BYTES);
                    union = combined2;
                    fc    = totalFrames;
                    header = String.format("all tiles (%d tile%s, %d frame%s)",
                            tileCount, tileCount == 1 ? "" : "s",
                            totalFrames, totalFrames == 1 ? "" : "s");

                    List<Integer> perTileColorsTmp = new ArrayList<>();
                    for (int i = 0; i < tileCount; i++) {
                        PayloadState.TileData td2 = snapTiles.get(i);
                        List<String> ch2 = snapChunks.get(i);
                        if (ch2.isEmpty()) { perTileColorsTmp.add(0); continue; }
                        byte[][] tf2 = resolveAllFramesForTile(td2, ch2);
                        perTileColorsTmp.add(PngToMapColors.countDistinct(mergeFrames(tf2)));
                    }
                    perTileColors = perTileColorsTmp;

                    if (anyCarpet) {
                        budgetLine = String.format("§7Compressed:      §f%d §7bytes total%s",
                                totalCompressedBytes,
                                overBudgetCount > 0 ? " §c(" + overBudgetCount + " tile" + (overBudgetCount == 1 ? "" : "s") + " OVER BUDGET)" : " §a✓");
                    } else {
                        budgetLine = String.format("§7Banners needed:  §f%d §7total%s",
                                totalChunks2,
                                overBudgetCount > 0 ? " §c(" + overBudgetCount + " tile" + (overBudgetCount == 1 ? "" : "s") + " OVER BUDGET)" : " §a✓");
                    }
                }

                // ── Statistics ───────────────────────────────────────────
                int[] freq = new int[256];
                for (byte b : union) freq[b & 0xFF]++;
                int distinctColors = PngToMapColors.countDistinct(union);
                int totalPixels = union.length - freq[0];

                List<int[]> colorsByFreq = new ArrayList<>();
                for (int c = 1; c < 256; c++)
                    if (freq[c] > 0) colorsByFreq.add(new int[]{c, freq[c]});
                colorsByFreq.sort((a, b2) -> Integer.compare(a[1], b2[1]));

                // ── Rarity histogram ─────────────────────────────────────
                double sumL = 0, sumL2 = 0;
                for (int[] e : colorsByFreq) {
                    double l = Math.log(Math.max(1, e[1]));
                    sumL += l; sumL2 += l * l;
                }
                int nC = colorsByFreq.size();
                double mL  = sumL / nC;
                double sL  = Math.sqrt(Math.max(1e-12, sumL2 / nC - mL * mL));
                double[] logits = {-1.3863, -0.4055, 0.4055, 1.3863};
                int[] th = new int[logits.length];
                for (int i = 0; i < logits.length; i++)
                    th[i] = Math.max(1, (int) Math.round(Math.exp(mL + sL * logits[i])));
                for (int i = 1; i < th.length; i++) th[i] = Math.max(th[i], th[i - 1] + 1);

                int nb = th.length + 1;
                int[] bc = new int[nb], bp = new int[nb];
                for (int[] e : colorsByFreq) {
                    int bk = nb - 1;
                    for (int i = 0; i < th.length; i++) if (e[1] <= th[i]) { bk = i; break; }
                    bc[bk]++; bp[bk] += e[1];
                }
                String[] lbls = new String[nb];
                lbls[0] = "1-" + th[0] + "px";
                for (int i = 1; i < th.length; i++) lbls[i] = (th[i - 1] + 1) + "-" + th[i] + "px";
                lbls[nb - 1] = (th[th.length - 1] + 1) + "+px";
                int maxLbl = 0;
                for (String l : lbls) maxLbl = Math.max(maxLbl, l.length());
                int maxBucket = 0;
                for (int v : bc) maxBucket = Math.max(maxBucket, v);

                // ── Reduction simulation ─────────────────────────────────
                List<String> simLines = new ArrayList<>();
                if (distinctColors > 2) {
                    int maxRemove = Math.min(distinctColors - 2, 40);
                    int step = maxRemove <= 10 ? 1 : 5;
                    List<Integer> threshList = new ArrayList<>();
                    for (int t2 = step; t2 <= maxRemove; t2 += step) threshList.add(t2);
                    int[] thresholds = threshList.stream().mapToInt(Integer::intValue).toArray();

                    int totalRows = union.length / 128;
                    boolean[][] colorInRow = new boolean[256][totalRows];
                    for (int p = 0; p < union.length; p++) {
                        int c = union[p] & 0xFF;
                        if (c > 0) colorInRow[c][p / 128] = true;
                    }

                    float[][] oklab   = PngToMapColors.buildOklabLookup();
                    int       baseSize = PngToMapColors.estimateCompressedSize(union);

                    String colHdr = all ? "pixels/fr · Δcolor · rows/fr · est.Δbytes"
                                        : "pixels · Δcolor · rows · est.Δbytes";
                    simLines.add("§7Removal cost — §8" + colHdr + "§7:");

                    PngToMapColors.Strategy[] strategies = {
                        PngToMapColors.Strategy.RAREST,
                        PngToMapColors.Strategy.CLOSEST,
                        PngToMapColors.Strategy.WEIGHTED
                    };
                    String[][] stratMeta = {
                        {"rarest",   "picks rarest color, merges to nearest neighbor"},
                        {"closest",  "merges the most perceptually similar pair"},
                        {"weighted", "similar pairs weighted by combined frequency"}
                    };
                    for (int si = 0; si < strategies.length; si++) {
                        List<int[]> rows = runReductionSim(
                                union, freq, colorInRow, totalRows,
                                oklab, colorsByFreq, strategies[si], baseSize, thresholds);
                        if (rows.isEmpty()) continue;
                        simLines.add(String.format("§8── §f%s §8— %s",
                                stratMeta[si][0], stratMeta[si][1]));
                        for (int[] row : rows) {
                            int   removed = row[0], cumPx = row[1];
                            float dist    = row[2] / 1000f;
                            int   cumR    = row[3], savedB = row[4];
                            String rowStr = all
                                    ? String.format("%drow/fr", cumR / Math.max(1, fc))
                                    : String.format("%drows", cumR);
                            simLines.add(String.format(
                                    "§7  remove %2d: §f%4d§7px (§f%.1f%%§7)  Δ≤§f%.2f  §7%s  §f~%+dB",
                                    removed, cumPx / fc,
                                    100.0 * cumPx / totalPixels, dist, rowStr, -savedB));
                        }
                    }
                }

                // Capture final values for lambda.
                final int    fDistinctColors = distinctColors;
                final int    fTotalPixels    = totalPixels;
                final int    fNb = nb;
                final int[]  fBc = bc, fBp = bp;
                final String[] fLbls = lbls;
                final int    fMaxLbl = maxLbl, fMaxBucket = maxBucket;
                final List<Integer> fPerTileColors = perTileColors;
                final List<String>  fSimLines      = simLines;
                final int    fFc = fc;

                client.execute(() -> {
                    try {
                        source.sendFeedback(Text.literal("§6=== Palette: " + header + " ==="));
                        String frameNote = fFc > 1 ? String.format(" §8(across %d frames)", fFc) : "";
                        source.sendFeedback(Text.literal(String.format(
                                "§7Distinct colors: §f%d%s", fDistinctColors, frameNote)));
                        if (all && fPerTileColors != null) {
                            int maxPerTile = 0;
                            for (int n : fPerTileColors) if (n > maxPerTile) maxPerTile = n;
                            StringBuilder sb2 = new StringBuilder(String.format(
                                    "§7Per-tile colors:  §fmax=%d §8(reduce all operates per-tile)§r\n", maxPerTile));
                            for (int i = 0; i < fPerTileColors.size(); i++)
                                sb2.append(String.format("§7  %s: §f%d\n",
                                        PayloadState.tileLabel(i), fPerTileColors.get(i)));
                            source.sendFeedback(Text.literal(sb2.toString().stripTrailing()));
                        }
                        source.sendFeedback(Text.literal(budgetLine));
                        if (!all && hasPreReduction)
                            source.sendFeedback(Text.literal("§7Reduction:       §eactive §7(undo available)"));

                        source.sendFeedback(Text.literal("§7Color rarity distribution:"));
                        final int BAR_WIDTH = 16;
                        for (int b3 = 0; b3 < fNb; b3++) {
                            if (fBc[b3] == 0) continue;
                            int bars = fMaxBucket > 0 ? Math.max(1, fBc[b3] * BAR_WIDTH / fMaxBucket) : 0;
                            String bar = "§a" + "█".repeat(bars) + "§8" + "░".repeat(BAR_WIDTH - bars);
                            String lbl = String.format("%" + fMaxLbl + "s", fLbls[b3]);
                            source.sendFeedback(Text.literal(String.format(
                                    "§7 %s §f%3d §7colors  %s §7%.1f%%",
                                    lbl, fBc[b3], bar, 100.0 * fBp[b3] / fTotalPixels)));
                        }
                        for (String line : fSimLines)
                            source.sendFeedback(Text.literal(line));
                    } finally {
                        importInProgress = false;
                    }
                });

            } catch (Exception e) {
                client.execute(() -> {
                    source.sendError(Text.literal("§cFailed to analyze palette: " + e.getMessage()));
                    importInProgress = false;
                });
            }
        }, "loominary-palette");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** Concatenates all frames into a single flat byte array for union-based reduction. */
    private static byte[] mergeFrames(byte[][] frames) {
        if (frames.length == 1) return frames[0];
        byte[] union = new byte[frames.length * MAP_BYTES];
        for (int f = 0; f < frames.length; f++)
            System.arraycopy(frames[f], 0, union, f * MAP_BYTES, MAP_BYTES);
        return union;
    }

    /**
     * Simulates palette reduction under one strategy and returns one result row per
     * threshold. Each row is {threshold, cumPixels, maxDistMillis, cumRows, savedBytes}.
     * RAREST iterates the pre-sorted colorsByFreq list. CLOSEST/WEIGHTED scan all
     * active pairs at each step (O(N²) per step, fine for N≤256).
     */
    private static List<int[]> runReductionSim(
            byte[] union, int[] origFreq, boolean[][] colorInRow, int totalRows,
            float[][] oklab, List<int[]> sortedRarest,
            PngToMapColors.Strategy strategy, int baseSize, int[] thresholds) {

        byte[] simUnion = union.clone();
        int[]  simFreq  = Arrays.copyOf(origFreq, 256);
        simFreq[0] = 0;

        float     maxDist   = 0f;
        boolean[] rowsSeen  = new boolean[totalRows];
        int       cumRows   = 0, cumPixels = 0, simRemoved = 0, threshIdx = 0;
        int       rarestIdx = 0; // cursor into sortedRarest for RAREST strategy

        List<int[]> results = new ArrayList<>();
        int maxThresh = thresholds[thresholds.length - 1];

        while (simRemoved < maxThresh && threshIdx < thresholds.length) {
            int victim = -1, survivor = -1;
            float distSq = 0f;

            if (strategy == PngToMapColors.Strategy.RAREST) {
                // Advance past already-removed entries.
                while (rarestIdx < sortedRarest.size() - 1
                        && simFreq[sortedRarest.get(rarestIdx)[0]] == 0) rarestIdx++;
                if (rarestIdx >= sortedRarest.size() - 1) break;
                victim = sortedRarest.get(rarestIdx++)[0];
                float[] vOk = oklab[victim];
                if (vOk == null) { simFreq[victim] = 0; simRemoved++; continue; }
                float best = Float.MAX_VALUE;
                for (int c = 1; c < 256; c++) {
                    if (c == victim || simFreq[c] == 0 || oklab[c] == null) continue;
                    float dL = vOk[0]-oklab[c][0], da = vOk[1]-oklab[c][1], db = vOk[2]-oklab[c][2];
                    float d2 = dL*dL + da*da + db*db;
                    if (d2 < best) { best = d2; survivor = c; }
                }
                if (survivor == -1) { simFreq[victim] = 0; simRemoved++; continue; }
                distSq = best;
            } else {
                // CLOSEST / WEIGHTED: scan all active pairs.
                float best = Float.MAX_VALUE;
                for (int a = 1; a < 256; a++) {
                    if (simFreq[a] == 0 || oklab[a] == null) continue;
                    for (int b = a + 1; b < 256; b++) {
                        if (simFreq[b] == 0 || oklab[b] == null) continue;
                        float dL = oklab[a][0]-oklab[b][0], da = oklab[a][1]-oklab[b][1],
                              db = oklab[a][2]-oklab[b][2];
                        float d2 = dL*dL + da*da + db*db;
                        float score = (strategy == PngToMapColors.Strategy.WEIGHTED)
                                ? d2 / (simFreq[a] + simFreq[b]) : d2;
                        if (score < best) {
                            best = score;
                            distSq = d2;
                            if (simFreq[a] <= simFreq[b]) { victim = a; survivor = b; }
                            else                          { victim = b; survivor = a; }
                        }
                    }
                }
                if (victim == -1) break;
            }

            maxDist    = Math.max(maxDist, (float) Math.sqrt(distSq));
            cumPixels += simFreq[victim];
            for (int r = 0; r < totalRows; r++)
                if (colorInRow[victim][r] && !rowsSeen[r]) { rowsSeen[r] = true; cumRows++; }

            byte fromB = (byte) victim, toB = (byte) survivor;
            for (int p = 0; p < simUnion.length; p++) if (simUnion[p] == fromB) simUnion[p] = toB;
            simFreq[survivor] += simFreq[victim];
            simFreq[victim]    = 0;
            simRemoved++;

            if (simRemoved == thresholds[threshIdx]) {
                int savedB = baseSize - PngToMapColors.estimateCompressedSize(simUnion);
                results.add(new int[]{simRemoved, cumPixels, (int)(maxDist * 1000), cumRows, savedB});
                threshIdx++;
            }
        }
        return results;
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
     * Builds a reduce manifest for a tile. Automatically adds FLAG_ANIMATED and
     * FLAG_DELTA_FRAMES when frameCount &gt; 1, and the per-frame delay table.
     */
    private static byte[] buildReduceManifest(int tileIdx, long crc, String playerName,
            int flags, int frameCount, int[] delays) {
        if (frameCount > 1) {
            return PayloadManifest.toBytes(
                    flags | PayloadManifest.FLAG_ANIMATED,
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
        snap.carpetEncoded       = tile.carpetEncoded;
        snap.carpetCompressedB64 = tile.carpetCompressedB64;
        snap.frameDelays         = tile.frameDelays != null ? new ArrayList<>(tile.frameDelays) : null;
        snap.frameCount          = tile.frameCount;
        snap.nonce               = tile.nonce;
        return snap;
    }

    private static int reduceOne(FabricClientCommandSource source, int target) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (!activeTileHasContent()) {
            source.sendError(Text.literal("§cActive tile has no data."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        int tileIdx = PayloadState.activeTileIndex;
        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        boolean isCarpet = tile.carpetEncoded;
        int effectiveTarget = isCarpet
                ? (tile.loomEncoded ? MAX_CHUNKS_CARPET_LOOM : MAX_CHUNKS_CARPET) : target;

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
        final String playerName        = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        final int flags                = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

        importInProgress = true;
        String bannerTag = reduceStrategy == PngToMapColors.Strategy.RAREST ? "rarest"
                : "rarest §8(banner target; use 'colors' for " + reduceStrategy.name().toLowerCase() + ")";
        source.sendFeedback(Text.literal("§7Reducing " + PayloadState.tileLabel(tileIdx)
                + " §8[" + bannerTag + "]§7..."));

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            try {
                byte[][] frames = resolveAllFramesForTile(tileSnap, chunksSnap);
                int fc      = frames.length;
                byte[] union = mergeFrames(frames);
                int[] delays = tileDelays(tileSnap, fc);
                byte[] prefix = buildReduceManifest(tileIdx, 0L, playerName, flags, fc, delays);

                // Banner-count targeting always uses RAREST: the selected strategy answers
                // "which colors look similar?" while RAREST answers "which merge saves the
                // most banners?" — CLOSEST/WEIGHTED can merge common colors in one step,
                // dropping the banner count far below the target unpredictably.
                PngToMapColors.FitResult fit = isCarpet
                        ? PngToMapColors.reduceToFit(union, prefix, CHUNK_SIZE,
                                tile.loomEncoded ? MAX_CHUNKS_CARPET_LOOM : MAX_CHUNKS_CARPET,
                                PngToMapColors.Strategy.RAREST, null)
                        : PngToMapColors.reduceToFitKJ(union, prefix, effectiveTarget, PngToMapColors.Strategy.RAREST, null);

                long crc      = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                byte[] manifest = buildReduceManifest(tileIdx, crc, playerName, flags, fc, delays);
                // Encode on background thread — never compress large payloads on render thread.
                byte[] payloadForSave = PayloadManifest.withTrailing(manifest, delays, fit.mapColors);
                final PreEncodedTile preEnc = preEncode(tileIdx, manifest, payloadForSave,
                        tileSnap.carpetEncoded);

                client.execute(() -> {
                    try {
                        PayloadState.TileData liveTile = PayloadState.tiles.get(tileIdx);
                        int newCount = applySaveTile(tileIdx, fc, preEnc);
                        liveTile.frameCount = fc;
                        PayloadState.syncFromActiveTile();
                        PayloadState.save();

                        int remaining = fit.originalDistinctColors - fit.colorsRemoved;
                        source.sendFeedback(Text.literal(String.format(
                                "§6=== Reduced %s%s ===", PayloadState.tileLabel(tileIdx),
                                fc > 1 ? " (" + fc + " frames)" : "")));
                        if (isCarpet) {
                            int newBytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            int tileCap2 = maxBytesForTile(liveTile);
                            boolean stillOver = newBytes > tileCap2;
                            int ovfBanners = overflowBannerCount(liveTile, liveTile.chunks);
                            source.sendFeedback(Text.literal(String.format(
                                    "§7Compressed: %s%d bytes §7(carpet, %d overflow banner%s)%s",
                                    stillOver ? "§c" : "§a", newBytes,
                                    ovfBanners, ovfBanners == 1 ? "" : "s",
                                    stillOver ? String.format(" §c[over budget by %d — try fewer colors]",
                                            newBytes - tileCap2) : "")));
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
        if (!activeTileHasContent()) {
            source.sendError(Text.literal("§cActive tile has no data."));
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
        final String playerName        = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        final int flags                = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Reducing " + PayloadState.tileLabel(tileIdx)
                + " §8[" + reduceStrategy.name().toLowerCase() + "]§7..."));

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

                int[] normFreq = buildNormFreq(frames, fc, reduceStrategy);
                PngToMapColors.FitResult fit = PngToMapColors.reduceToColorCount(
                        union, prefix, CHUNK_SIZE, targetColors, reduceStrategy, normFreq);

                long crc       = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                byte[] manifest = buildReduceManifest(tileIdx, crc, playerName, flags, fc, delays);
                byte[] payload2 = PayloadManifest.withTrailing(manifest, delays, fit.mapColors);
                final PreEncodedTile preEnc2 = preEncode(tileIdx, manifest, payload2,
                        tileSnap.carpetEncoded);

                client.execute(() -> {
                    try {
                        preReductionChunks.put(tileIdx, new ArrayList<>(PayloadState.ACTIVE_CHUNKS));
                        PayloadState.TileData liveTile = PayloadState.tiles.get(tileIdx);
                        if (liveTile.carpetEncoded && liveTile.carpetCompressedB64 != null)
                            preReductionCarpetB64.put(tileIdx, liveTile.carpetCompressedB64);

                        int newCount  = applySaveTile(tileIdx, fc, preEnc2);
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
                            int tileCap2 = maxBytesForTile(liveTile);
                            boolean stillOver = bytes > tileCap2;
                            source.sendFeedback(Text.literal(String.format(
                                    "§7Compressed: %s%d bytes%s",
                                    stillOver ? "§c" : "§a", bytes,
                                    stillOver ? String.format(" §c[over budget by %d]",
                                            bytes - tileCap2) : "")));
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
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
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
        String allBannerTag = reduceStrategy == PngToMapColors.Strategy.RAREST ? "rarest"
                : "rarest §8(banner target; use 'colors' for " + reduceStrategy.name().toLowerCase() + ")";
        source.sendFeedback(Text.literal("§7Reducing all tiles §8[" + allBannerTag + "]§7..."));

        // Per-tile results: null = skipped, non-null = [manifest, mapColors, fc, note]
        record TileResult(byte[] manifest, byte[] mapColors, int fc, String note,
                          PreEncodedTile preEnc) {}

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            List<TileResult> results = new ArrayList<>();
            int totalColorsRemoved = 0, totalPixelsAffected = 0, tilesReduced = 0;

            for (int i = 0; i < tileCount; i++) {
                PayloadState.TileData tileSnap = allSnaps.get(i);
                List<String> chunks = allChunks.get(i);
                if (chunks.isEmpty()) { results.add(null); continue; }
                int tileTarget = tileSnap.carpetEncoded
                        ? (tileSnap.loomEncoded ? MAX_CHUNKS_CARPET_LOOM : MAX_CHUNKS_CARPET)
                        : targetBanners;
                if (!tileSnap.carpetEncoded && chunks.size() <= targetBanners) {
                    results.add(null); continue;
                }
                try {
                    byte[][] frames = resolveAllFramesForTile(tileSnap, chunks);
                    int fc      = frames.length;
                    byte[] union = mergeFrames(frames);
                    int[] delays = tileDelays(tileSnap, fc);
                    byte[] prefix = buildReduceManifest(i, 0L, playerName, flags, fc, delays);
                    PngToMapColors.FitResult fit = tileSnap.carpetEncoded
                            ? PngToMapColors.reduceToFit(union, prefix, CHUNK_SIZE, tileTarget, PngToMapColors.Strategy.RAREST, null)
                            : PngToMapColors.reduceToFitKJ(union, prefix, tileTarget, PngToMapColors.Strategy.RAREST, null);
                    long crc      = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                    byte[] manifest = buildReduceManifest(i, crc, playerName, flags, fc, delays);
                    byte[] payload = PayloadManifest.withTrailing(manifest, delays, fit.mapColors);
                    PreEncodedTile pre = preEncode(i, manifest, payload, tileSnap.carpetEncoded);
                    int oldSz = oldSizes.get(i);
                    String note = tileSnap.carpetEncoded
                            ? String.format("carpet, %d colors merged (committing...)", fit.colorsRemoved)
                            : String.format("§e%d §7→ §a? §7banners, %d colors merged", oldSz, fit.colorsRemoved);
                    results.add(new TileResult(manifest, payload, fc, note, pre));
                    tilesReduced++;
                    totalColorsRemoved  += fit.colorsRemoved;
                    totalPixelsAffected += fit.pixelsAffected;
                } catch (Exception e) {
                    results.add(new TileResult(null, null, 1, "§cfailed: " + e.getMessage(), null));
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
                        int newCount = applySaveTile(i, r.fc(), r.preEnc());
                        liveTile.frameCount = r.fc();
                        String note;
                        if (liveTile.carpetEncoded) {
                            int bytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            int tileCap2 = maxBytesForTile(liveTile);
                            boolean still = bytes > tileCap2;
                            note = String.format("§a%d bytes%s", bytes,
                                    still ? String.format(" §c[still over budget by %d]",
                                            bytes - tileCap2) : "");
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
                            "§7Use §f/loominary reduce undo all§7 to restore all tiles, or "
                            + "§f/loominary tile <n>§7 + §f/loominary reduce undo§7 for one tile."));
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
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
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
        source.sendFeedback(Text.literal("§7Reducing all tiles §8[" + reduceStrategy.name().toLowerCase() + "]§7..."));

        record TileResult(byte[] manifest, byte[] mapColors, int fc,
                          int origColors, int newColors, String noteExtra,
                          PreEncodedTile preEnc) {}

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
                    int[] normFreq = buildNormFreq(frames, fc, reduceStrategy);
                    PngToMapColors.FitResult fit = PngToMapColors.reduceToColorCount(
                            union, prefix, CHUNK_SIZE, targetColors, reduceStrategy, normFreq);
                    long crc       = PayloadManifest.crc32(Arrays.copyOf(fit.mapColors, MAP_BYTES));
                    byte[] manifest = buildReduceManifest(i, crc, playerName, flags, fc, delays);
                    byte[] payload = PayloadManifest.withTrailing(manifest, delays, fit.mapColors);
                    PreEncodedTile pre = preEncode(i, manifest, payload, tileSnap.carpetEncoded);
                    results.add(new TileResult(manifest, payload,
                            fc, current, current - fit.colorsRemoved, null, pre));
                    tilesReduced++;
                    totalColorsRemoved  += fit.colorsRemoved;
                    totalPixelsAffected += fit.pixelsAffected;
                } catch (Exception e) {
                    results.add(new TileResult(null, null, 1, 0, 0, "§cfailed: " + e.getMessage(), null));
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
                        int newCount = applySaveTile(i, r.fc(), r.preEnc());
                        liveTile.frameCount = r.fc();
                        String note;
                        if (liveTile.carpetEncoded) {
                            int bytes = Base64.getDecoder().decode(liveTile.carpetCompressedB64).length;
                            int tileCap2 = maxBytesForTile(liveTile);
                            boolean still = bytes > tileCap2;
                            note = String.format("§e%d §7→ §a%d §7colors, %d bytes%s",
                                    r.origColors(), r.newColors(), bytes,
                                    still ? String.format(" §c[still over budget by %d]",
                                            bytes - tileCap2) : "");
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
                            "§7Use §f/loominary reduce undo all§7 to restore all tiles, or "
                            + "§f/loominary tile <n>§7 + §f/loominary reduce undo§7 for one tile."));
                } finally {
                    importInProgress = false;
                }
            });
        });
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /**
     * For WEIGHTED strategy on animated tiles: compute max per-frame frequency for each
     * color byte. This prevents union frequency inflation (a color in all N frames has
     * N× the union freq of a frame-specific color) from distorting WEIGHTED scoring.
     * Returns null for single-frame tiles or non-WEIGHTED strategies.
     */
    private static int[] buildNormFreq(byte[][] frames, int fc, PngToMapColors.Strategy strategy) {
        if (strategy != PngToMapColors.Strategy.WEIGHTED || fc <= 1) return null;
        int[] normFreq = new int[256];
        for (byte[] frame : frames) {
            int[] ff = new int[256];
            for (byte b : frame) ff[b & 0xFF]++;
            for (int c = 1; c < 256; c++)
                normFreq[c] = Math.max(normFreq[c], ff[c]);
        }
        return normFreq;
    }

    private static int setReduceStrategy(FabricClientCommandSource source,
                                          PngToMapColors.Strategy strategy) {
        reduceStrategy = strategy;
        String name = switch (strategy) {
            case RAREST   -> "rarest";
            case CLOSEST  -> "closest";
            case WEIGHTED -> "weighted";
        };
        String desc = switch (strategy) {
            case RAREST   -> "removes the least-frequent color each step, merging it to its nearest neighbor";
            case CLOSEST  -> "merges the globally closest color pair each step — targets similar-color clusters";
            case WEIGHTED -> "scores pairs by dist²/(freq_a+freq_b) — large similar-color clusters are eliminated first";
        };
        source.sendFeedback(Text.literal("§aReduce strategy: §f" + name));
        source.sendFeedback(Text.literal("§7" + desc));
        return 1;
    }

    private static int reduceUndoAll(FabricClientCommandSource source) {
        if (preReductionChunks.isEmpty()) {
            source.sendError(Text.literal("§cNothing to undo — no tiles have a saved pre-reduction state."));
            return 0;
        }
        List<Integer> tileIndices = new ArrayList<>(preReductionChunks.keySet());
        int restored = 0;
        for (int tileIdx : tileIndices) {
            List<String> saved = preReductionChunks.remove(tileIdx);
            if (saved == null || tileIdx >= PayloadState.tiles.size()) continue;
            PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
            String savedB64 = preReductionCarpetB64.remove(tileIdx);
            if (tile.carpetEncoded && savedB64 != null) tile.carpetCompressedB64 = savedB64;
            tile.chunks.clear();
            tile.chunks.addAll(saved);
            tile.currentIndex = 0;
            if (tileIdx == PayloadState.activeTileIndex) {
                PayloadState.ACTIVE_CHUNKS.clear();
                PayloadState.ACTIVE_CHUNKS.addAll(saved);
                PayloadState.activeChunkIndex = 0;
            }
            restored++;
        }
        PayloadState.save();
        source.sendFeedback(Text.literal(String.format(
                "§aRestored %d tile%s to pre-reduction state.",
                restored, restored == 1 ? "" : "s")));
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
    // whitelist [add|clear]
    // ════════════════════════════════════════════════════════════════════

    private static int whitelistStatus(FabricClientCommandSource source) {
        int n = PayloadState.whitelistedBannerNames.size();
        if (n == 0) {
            source.sendFeedback(Text.literal(
                    "§7Whitelist empty. Run §f/loominary whitelist add§7 "
                            + "to mark every named banner in your inventory and bundles as reusable raw material."));
        } else {
            source.sendFeedback(Text.literal(String.format(
                    "§a%d§7 named banner%s whitelisted for reuse. "
                            + "The renamer will pull them (from loose slots or from inside bundles) "
                            + "and overwrite the name with the current chunk name.",
                    n, n == 1 ? "" : "s")));
        }
        return 1;
    }

    private static int whitelistAdd(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            source.sendError(Text.literal("§cNot in a world."));
            return 0;
        }
        PlayerInventory inv = client.player.getInventory();
        int before = PayloadState.whitelistedBannerNames.size();
        int loose = 0;
        int bundled = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BannerItem && stack.getCustomName() != null) {
                String name = stack.getCustomName().getString();
                if (PayloadState.whitelistedBannerNames.add(name)) loose++;
                continue;
            }
            if (stack.getItem() == Items.BUNDLE) {
                BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (contents == null) continue;
                for (ItemStack inside : contents.iterate()) {
                    if (!(inside.getItem() instanceof BannerItem)) continue;
                    if (inside.getCustomName() == null) continue;
                    String name = inside.getCustomName().getString();
                    if (PayloadState.whitelistedBannerNames.add(name)) bundled++;
                }
            }
        }
        PayloadState.save();
        int added = PayloadState.whitelistedBannerNames.size() - before;
        source.sendFeedback(Text.literal(String.format(
                "§aWhitelisted §f%d§a new name%s §7(%d loose, %d in bundles; %d total now eligible).",
                added, added == 1 ? "" : "s",
                loose, bundled, PayloadState.whitelistedBannerNames.size())));
        if (bundled > 0) {
            source.sendFeedback(Text.literal(
                    "§7Reminder: the renamer will §fnot§7 insert renamed banners back into "
                            + "bundles that hold whitelisted entries. "
                            + "§7Add at least one §fempty bundle§7 to your inventory for output."));
        }
        return 1;
    }

    private static int whitelistClear(FabricClientCommandSource source) {
        int n = PayloadState.whitelistedBannerNames.size();
        PayloadState.whitelistedBannerNames.clear();
        PayloadState.save();
        source.sendFeedback(Text.literal(String.format(
                "§aCleared whitelist (%d name%s removed).",
                n, n == 1 ? "" : "s")));
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
        String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
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
                    // Extract frame bytes only (strip any old trailing delay table).
                    int frameEnd = manifest.headerSize + manifest.frameCount * MAP_BYTES;
                    payloadBytes = Arrays.copyOfRange(full, manifest.headerSize,
                            Math.min(frameEnd, full.length));
                    flags = manifest.flags;
                    long crc = PayloadManifest.crc32(Arrays.copyOf(payloadBytes, MAP_BYTES));
                    if (manifest.frameCount > 1) {
                        manifestBytes = PayloadManifest.toBytes(
                                flags, PayloadState.gridColumns, PayloadState.gridRows,
                                PayloadState.tileCol(i), PayloadState.tileRow(i),
                                crc, playerName, text, tile.nonce,
                                manifest.frameCount, manifest.loopCount, manifest.frameDelays);
                        payloadBytes = PayloadManifest.withTrailing(manifestBytes, manifest.frameDelays, payloadBytes);
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
    // author [name|clear]
    // ════════════════════════════════════════════════════════════════════

    private static int authorShow(FabricClientCommandSource source) {
        if (PayloadState.currentAuthor != null) {
            source.sendFeedback(Text.literal("§7Author override: §f" + PayloadState.currentAuthor
                    + " §8(use §f/loominary author clear§8 to revert to player IGN)"));
        } else {
            String ign = PayloadState.effectiveAuthor(
                    source.getPlayer().getGameProfile().getName());
            source.sendFeedback(Text.literal("§7Author: §f" + ign
                    + " §8(player IGN — use §f/loominary author <name>§8 to override)"));
        }
        return 1;
    }

    private static int authorSet(FabricClientCommandSource source, String name) {
        PayloadState.currentAuthor = name.isBlank() ? null : name;
        PayloadState.save();
        if (PayloadState.currentAuthor != null) {
            source.sendFeedback(Text.literal("§aAuthor set to: §f" + PayloadState.currentAuthor));
        } else {
            source.sendFeedback(Text.literal("§aAuthor cleared — will use player IGN."));
        }
        return 1;
    }

    private static int authorClear(FabricClientCommandSource source) {
        PayloadState.currentAuthor = null;
        PayloadState.save();
        source.sendFeedback(Text.literal("§aAuthor cleared — will use player IGN."));
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // /loominary password  — decrypt-password list management
    // ════════════════════════════════════════════════════════════════════

    /**
     * Password applied to all tiles encoded by future import commands.
     * Null means no encryption. Set by /loominary password encrypt <pw>.
     */
    public static String encryptPassword = null;

    /** Returns the full list of passwords to use for a new encrypted import:
     *  [encryptPassword] union [stored passwords], deduplicated, encryptPassword first. */
    static List<String> buildEncryptPasswords(String primary) {
        List<String> result = new ArrayList<>();
        if (primary != null && !primary.isEmpty()) result.add(primary);
        for (String p : MapEncryption.passwords) {
            if (!result.contains(p)) result.add(p);
        }
        return result;
    }

    private static int passwordList(FabricClientCommandSource source) {
        List<String> pws = MapEncryption.passwords;
        if (pws.isEmpty()) {
            source.sendFeedback(Text.literal("§7No passwords stored. Use §f/loominary password add <pw>§7 to add one."));
        } else {
            source.sendFeedback(Text.literal("§7Stored passwords (" + pws.size() + "):"));
            for (int i = 0; i < pws.size(); i++) {
                source.sendFeedback(Text.literal("§8  " + (i + 1) + ". §f" + pws.get(i)));
            }
        }
        if (encryptPassword != null) {
            source.sendFeedback(Text.literal("§7Import encryption: §f" + encryptPassword
                    + " §8(+ stored passwords as additional slots)"));
        } else {
            source.sendFeedback(Text.literal("§7Import encryption: §8off (use §f/loominary password encrypt <pw>§8 to enable)"));
        }
        return 1;
    }

    private static int passwordAdd(FabricClientCommandSource source, String pw) {
        if (pw.isBlank()) {
            source.sendError(Text.literal("§cPassword cannot be blank."));
            return 0;
        }
        if (MapEncryption.passwords.contains(pw)) {
            source.sendFeedback(Text.literal("§ePassword already in list."));
            return 1;
        }
        MapEncryption.passwords.add(pw);
        MapEncryption.savePasswords();
        // Clear per-map fail cache so the new password is tried on all known encrypted maps,
        // then kick the frame scanner so the retry happens on the very next tick.
        MapEncryption.clearCache();
        MapBannerDecoder.forceRescanOnNextTick();
        source.sendFeedback(Text.literal("§aPassword added. Total: " + MapEncryption.passwords.size() + " password(s)."));
        return 1;
    }

    private static int passwordRemove(FabricClientCommandSource source, String pw) {
        if (MapEncryption.passwords.remove(pw)) {
            MapEncryption.savePasswords();
            source.sendFeedback(Text.literal("§aPassword removed. Total: " + MapEncryption.passwords.size() + " password(s)."));
        } else {
            source.sendFeedback(Text.literal("§ePassword not found in list."));
        }
        return 1;
    }

    private static int passwordClearAll(FabricClientCommandSource source) {
        int n = MapEncryption.passwords.size();
        MapEncryption.passwords.clear();
        MapEncryption.savePasswords();
        source.sendFeedback(Text.literal("§aCleared " + n + " password(s)."));
        return 1;
    }

    private static int encryptSet(FabricClientCommandSource source, String pw) {
        if (pw.isBlank()) {
            source.sendError(Text.literal("§cPassword cannot be blank."));
            return 0;
        }
        encryptPassword = pw;
        List<String> slots = buildEncryptPasswords(pw);
        source.sendFeedback(Text.literal("§aImport encryption enabled: §f" + slots.size()
                + " slot(s) per tile §8(primary + " + (slots.size() - 1) + " stored)."));
        return 1;
    }

    private static int encryptOff(FabricClientCommandSource source) {
        encryptPassword = null;
        source.sendFeedback(Text.literal("§aImport encryption disabled — future imports will be unencrypted."));
        return 1;
    }


    // ════════════════════════════════════════════════════════════════════
    // stride <n> / skip <n>  — direct operations on the active tile
    // ════════════════════════════════════════════════════════════════════

    private static int applyStrideTile(FabricClientCommandSource source, int n) {
        return applyFrameTransform(source, n, true);
    }

    private static int applySkipTile(FabricClientCommandSource source, int n) {
        return applyFrameTransform(source, n, false);
    }

    private static int applyFrameTransform(FabricClientCommandSource source, int n, boolean isStride) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        PayloadState.syncToActiveTile();

        // Snapshot all animated tiles before releasing the game thread.
        final String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        final int tileCount = PayloadState.tiles.size();
        final List<List<String>> allChunks = new ArrayList<>();
        final List<PayloadState.TileData> allSnaps = new ArrayList<>();
        int animatedCount = 0;
        for (int i = 0; i < tileCount; i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            allChunks.add(new ArrayList<>(i == PayloadState.activeTileIndex
                    ? PayloadState.ACTIVE_CHUNKS : tile.chunks));
            allSnaps.add(snapshotTile(tile));
            if (tile.frameCount > 1) animatedCount++;
        }
        if (animatedCount == 0) {
            source.sendError(Text.literal("§cNo animated tiles in this batch."));
            return 0;
        }

        importInProgress = true;
        source.sendFeedback(Text.literal(String.format(
                "§7Applying %s %d to all %d animated tile%s...",
                isStride ? "stride" : "skip", n, animatedCount, animatedCount == 1 ? "" : "s")));

        record FS(byte[][] frames, int[] delays, List<Integer> sourceIndices) {}

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            // Per-tile results: null = skipped (static tile), non-null = transformed frames+delays
            List<FS> results = new ArrayList<>();
            for (int i = 0; i < tileCount; i++) {
                PayloadState.TileData snap = allSnaps.get(i);
                if (snap.frameCount <= 1) { results.add(null); continue; }
                try {
                    byte[][] frames = resolveAllFramesForTile(snap, allChunks.get(i));
                    int[] delays = tileDelays(snap, frames.length);
                    // Build the source-index list for this tile: use existing provenance or 1:1
                    List<Integer> srcIdx = new ArrayList<>();
                    for (int f = 0; f < frames.length; f++) {
                        srcIdx.add(snap.frameSourceIndices != null && f < snap.frameSourceIndices.size()
                                ? snap.frameSourceIndices.get(f) : f);
                    }
                    FS r;
                    if (isStride) {
                        int kept = (frames.length + n - 1) / n;
                        byte[][] kf = new byte[kept][];
                        int[]    kd = new int[kept];
                        List<Integer> ki = new ArrayList<>(kept);
                        for (int j = 0; j < kept; j++) {
                            int src = j * n;
                            kf[j] = frames[src];
                            ki.add(srcIdx.get(src));
                            int acc = 0;
                            for (int k = src; k < Math.min(src + n, frames.length); k++) acc += delays[k];
                            kd[j] = acc;
                        }
                        r = new FS(kf, kd, ki);
                    } else {
                        int keptCount = 0;
                        for (int j = 0; j < frames.length; j++)
                            if ((j + 1) % n != 0) keptCount++;
                        if (keptCount == 0) keptCount = 1;
                        byte[][] kf = new byte[keptCount][];
                        int[]    kd = new int[keptCount];
                        List<Integer> ki = new ArrayList<>(keptCount);
                        int dst = 0, pending = 0;
                        for (int j = 0; j < frames.length; j++) {
                            if ((j + 1) % n == 0) {
                                pending += delays[j];
                            } else {
                                kf[dst] = frames[j];
                                kd[dst] = delays[j] + pending;
                                ki.add(srcIdx.get(j));
                                pending = 0;
                                dst++;
                            }
                        }
                        if (pending > 0 && dst > 0) kd[dst - 1] += pending;
                        // If everything was dropped (only-frame case), keep first
                        if (ki.isEmpty()) ki.add(srcIdx.get(0));
                        r = new FS(kf, kd, ki);
                    }
                    results.add(r);
                } catch (Exception e) {
                    results.add(null);
                }
            }

            client.execute(() -> {
                try {
                    int saved = 0;
                    for (int i = 0; i < tileCount; i++) {
                        FS r = results.get(i);
                        if (r == null) continue;
                        saveEditorChanges(r.frames(), r.delays(), i, playerName);
                        // Update provenance after saveEditorChanges re-encodes the tile.
                        PayloadState.tiles.get(i).frameSourceIndices = r.sourceIndices();
                        saved++;
                    }
                    PayloadState.save();
                    source.sendFeedback(Text.literal(String.format(
                            "§a%s %d applied to %d tile%s.",
                            isStride ? "Stride" : "Skip", n, saved, saved == 1 ? "" : "s")));
                } finally {
                    importInProgress = false;
                }
            });
        });
        t.setDaemon(true);
        t.start();
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
        // Block export if any carpet tile is still over budget.
        PayloadState.syncToActiveTile();
        for (PayloadState.TileData t : PayloadState.tiles) {
            if (t.carpetEncoded && carpetCompressedBytes(t) > maxBytesForTile(t)) {
                source.sendError(Text.literal(
                        "§cCan't export: one or more tiles are over budget. "
                        + "Use §f/loominary reduce§c or §f/loominary mux§c first."));
                PayloadState.syncFromActiveTile();
                return 0;
            }
        }

        // Multi-art: more than one tile in the visual grid.
        boolean multiArt = PayloadState.totalTiles() > 1;
        // Any donor tiles present → they always get numbered names.
        int exported = 0;
        int donorExportNum = 0;
        try {
            // Resolve base name once; per-tile suffix appended below.
            String baseName = (nameArg != null && !nameArg.isEmpty()) ? nameArg
                    : SchematicExporter.resolveSchematicName(
                            PayloadState.currentTitle != null ? PayloadState.currentTitle : null);

            for (int tileIdx = 0; tileIdx < PayloadState.tiles.size(); tileIdx++) {
                PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
                if (!tileHasContent(tile)) continue;

                // Art tiles: numbered only when the visual grid has multiple tiles.
                // Donor tiles: always numbered sequentially with "_donor_NNN".
                String name;
                if (tile.isDonorOnly) {
                    donorExportNum++;
                    name = baseName + String.format("_donor_%03d", donorExportNum);
                } else {
                    name = multiArt ? baseName + "_tile" + tileIdx : baseName;
                }

                if (tile.carpetEncoded) {
                    if (tile.carpetCompressedB64 == null) {
                        source.sendError(Text.literal("§c" + PayloadState.tileLabel(tileIdx)
                                + ": no stored data — re-import."));
                        continue;
                    }
                    String exportB64 = tile.muxCargoB64 != null ? tile.muxCargoB64 : tile.carpetCompressedB64;
                    String exportAuthor = PayloadState.effectiveAuthor(
                            source.getPlayer().getGameProfile().getName());
                    byte[] toExport = encryptForOutput(
                            decryptIfNeeded(Base64.getDecoder().decode(exportB64)),
                            exportAuthor, PayloadState.currentTitle);

                    // For LOOM tiles: use encodeLoomFromCompressed so the schematic carries a
                    // proper LOOM header. This is essential when toExport is encrypted — the
                    // encryption magic starts with "LOOM" which peekLoomMagic would misread as
                    // a carpet LOOM header unless the real header precedes the payload.
                    // For legacy LC/LS tiles: fall back to the old manual nibble path.
                    CarpetEncoding encForExport;
                    if (tile.loomEncoded) {
                        encForExport = encodeLoomFromCompressed(toExport,
                                PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                                PayloadState.codecMode);
                    } else {
                        encForExport = encodeCarpetFromCompressed(toExport);
                    }

                    byte[] nibbles    = encForExport.nibbles();
                    int carpetBytes   = encForExport.carpetBytes();
                    int shadeBytes    = encForExport.shadeBytes();
                    int[][] heights   = encForExport.heights();
                    int totalBytes    = toExport.length;

                    Path output;
                    if (shadeBytes > 0) {
                        output = SchematicExporter.exportCarpetStaircase(nibbles, carpetBytes, heights, name);
                    } else {
                        output = SchematicExporter.exportCarpetTile(nibbles, carpetBytes, name);
                    }

                    // Migrate old LC banner names to LS if the tile now uses the shade channel.
                    boolean migrated = false;
                    String firstChunk = tile.chunks.isEmpty() ? "" : tile.chunks.get(0);
                    if (shadeBytes > 0 && firstChunk.startsWith("LC")) {
                        List<String> newChunks = encForExport.allChunks();
                        tile.chunks.clear();
                        tile.chunks.addAll(newChunks);
                        tile.currentIndex = 0;
                        if (tileIdx == PayloadState.activeTileIndex) PayloadState.syncFromActiveTile();
                        PayloadState.save();
                        migrated = true;
                    }

                    int carpetRows = shadeBytes > 0 ? 128 : (carpetBytes * 2 + 127) / 128;
                    int overflowBanners = overflowBannerCount(tile, tile.chunks);

                    source.sendFeedback(Text.literal(String.format(
                            "§aExported §f%s §a(%s, %d bytes):",
                            name + ".litematic",
                            shadeBytes > 0
                                    ? carpetRows + " carpet rows × 128 (staircase, " + shadeBytes + " shade bytes)"
                                    : carpetRows + " carpet rows × 128",
                            totalBytes)));
                    source.sendFeedback(Text.literal("§7  " + output.getFileName()));
                    if (migrated)
                        source.sendFeedback(Text.literal(
                                "§eBanner names updated from LC → LS format. Rename fresh banners at the anvil."));
                    if (overflowBanners > 0)
                        source.sendFeedback(Text.literal(String.format(
                                "§7  + %d overflow banner%s still needed (rename at anvil).",
                                overflowBanners, overflowBanners == 1 ? "" : "s")));
                } else {
                    // Banner schematic: apply encryption at export time if configured.
                    List<String> exportChunks;
                    if (encryptPassword != null && !encryptPassword.isEmpty()) {
                        // Reassemble plain compressed bytes, then encrypt, then rebuild chunks.
                        byte[] plain = decryptIfNeeded(MapBannerDecoder.assembleCompressedFromChunks(
                                new ArrayList<>(tile.chunks)));
                        exportChunks = buildEncryptedChunksForOutput(plain,
                                PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName()),
                                PayloadState.currentTitle);
                    } else {
                        exportChunks = new ArrayList<>(tile.chunks);
                    }
                    int count = exportChunks.size();
                    String description = String.format(
                            "Loominary tile %d (col %d, row %d) — %d named banners.",
                            tileIdx, PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx), count);
                    Path output = SchematicExporter.exportTile(exportChunks, name, description);
                    int gridH = (count + 15) / 16;
                    source.sendFeedback(Text.literal(String.format(
                            "§aExported §f%s §a(%d banners, 16×%d grid):",
                            name + ".litematic", count, gridH)));
                    source.sendFeedback(Text.literal("§7  " + output.getFileName()));
                    source.sendFeedback(Text.literal(
                            "§7Each ghost banner's custom name shows which renamed banner to place there."));
                }
                exported++;
            }
        } catch (IOException e) {
            source.sendError(Text.literal("§cExport failed: " + e.getMessage()));
            e.printStackTrace();
            PayloadState.syncFromActiveTile();
            return 0;
        }

        PayloadState.syncFromActiveTile();

        if (exported == 0) {
            source.sendError(Text.literal("§cNo tiles with data to export."));
            return 0;
        }
        if (exported > 1) {
            int artExported = exported - donorExportNum;
            String summary = donorExportNum > 0
                    ? String.format("§7%d art tile%s + %d donor tile%s exported.",
                            artExported,     artExported     == 1 ? "" : "s",
                            donorExportNum, donorExportNum == 1 ? "" : "s")
                    : String.format("§7%d/%d tiles exported.", exported, PayloadState.totalTiles());
            source.sendFeedback(Text.literal(summary));
        }
        return 1;
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
        if (!activeTileHasContent()) {
            source.sendError(Text.literal("§cActive tile has no data to edit."));
            return 0;
        }
        if (pendingSaves.get() > 0) {
            source.sendError(Text.literal("§cEditor save in progress — please wait a moment."));
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
    // filter <smooth|median|sharpen|posterize> [all] [param]
    // ════════════════════════════════════════════════════════════════════

    /**
     * Applies a filter to every frame of the current tile(s) in-place.
     * Decodes the existing encoded state, applies the spatial filter to each frame's
     * pixel data (via color-lookup reconstruction → filter → re-quantize to existing
     * palette), then re-encodes. Works on any tile regardless of source availability,
     * preserving skip/stride/reduce work the user has already done.
     */
    private static int filterInPlace(FabricClientCommandSource source,
                                     PngToMapColors.FilterParams filter, boolean all) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }

        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        String filterTag = switch (filter.type()) {
            case SMOOTH    -> String.format("smooth r=%.1f", filter.strength());
            case MEDIAN    -> String.format("median r=%d",   (int) filter.strength());
            case SHARPEN   -> String.format("sharpen a=%.1f", filter.strength());
            case POSTERIZE -> String.format("posterize %d",  (int) filter.strength());
        };

        PayloadState.syncToActiveTile();
        String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
        int first = all ? 0 : PayloadState.activeTileIndex;
        int last  = all ? PayloadState.tiles.size() - 1 : PayloadState.activeTileIndex;

        // Snapshot current state before going async; capture undo for affected tiles.
        List<List<String>> allChunks = new ArrayList<>();
        List<PayloadState.TileData> allSnaps = new ArrayList<>();
        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            allChunks.add(new ArrayList<>(i == PayloadState.activeTileIndex
                    ? PayloadState.ACTIVE_CHUNKS : tile.chunks));
            allSnaps.add(snapshotTile(tile));
            if (i >= first && i <= last) capturePreReductionState(i);
        }

        importInProgress = true;
        source.sendFeedback(Text.literal(String.format("§7Applying §f%s§7 filter to %s...",
                filterTag, all ? "all tiles" : PayloadState.tileLabel(PayloadState.activeTileIndex))));

        record FR(byte[][] frames, int[] delays) {}

        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            float[][] oklabLookup = PngToMapColors.buildOklabLookup();
            List<FR> results = new ArrayList<>();

            for (int i = 0; i < PayloadState.tiles.size(); i++) {
                if (i < first || i > last) { results.add(null); continue; }
                try {
                    byte[][] frames = resolveAllFramesForTile(allSnaps.get(i), allChunks.get(i));
                    int[] delays = tileDelays(allSnaps.get(i), frames.length);
                    PngToMapColors.applyFilterToFrames(frames, filter, oklabLookup);
                    results.add(new FR(frames, delays));
                } catch (Exception e) {
                    results.add(null);
                }
            }

            client.execute(() -> {
                try {
                    int saved = 0;
                    for (int i = 0; i < results.size(); i++) {
                        FR r = results.get(i);
                        if (r == null) continue;
                        saveEditorChanges(r.frames(), r.delays(), i, playerName);
                        int fc = r.frames().length;
                        int colors = PngToMapColors.countDistinct(r.frames()[0]);
                        source.sendFeedback(Text.literal(String.format("§7  %s: §f%d color%s, %d frame%s",
                                PayloadState.tileLabel(i), colors, colors == 1 ? "" : "s",
                                fc, fc == 1 ? "" : "s")));
                        saved++;
                    }
                    PayloadState.syncFromActiveTile();
                    source.sendFeedback(Text.literal(String.format(
                            "§6=== Filter [%s] applied to %d tile%s ===",
                            filterTag, saved, saved == 1 ? "" : "s")));
                    source.sendFeedback(Text.literal(
                            "§7Use §f/loominary preview§7 to inspect. "
                            + "§f/loominary reduce undo§7 reverts."));
                } finally { importInProgress = false; }
            });
        });
        t.setDaemon(true);
        t.start();
        return 1;
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

            String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
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

                int effectiveBudget = tile.carpetEncoded
                        ? (tile.loomEncoded ? MAX_CHUNKS_CARPET_LOOM : MAX_CHUNKS_CARPET) : MAX_CHUNKS;
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
                    try { buildCarpetEncoding(manifest0, mapColors, tCol, tRow); }
                    catch (IllegalStateException overflow) {
                        PngToMapColors.FitResult fit = PngToMapColors.reduceToFit(
                                mapColors, manifest0, CHUNK_SIZE, effectiveBudget);
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
    // codec
    // ════════════════════════════════════════════════════════════════════

    private static int showCodec(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(String.format(
                "§7Current codec: §f%s§r\n"
                + "§7  banner               §8— 63 CJK banner chunks, no carpet\n"
                + "§7  carpet               §8— LOOM carpet channel only\n"
                + "§7  carpet+shade         §8— LOOM carpet + shade channels\n"
                + "§7  carpet+banners       §8— LOOM carpet + overflow banners\n"
                + "§7  carpet+banners+shade §8— LOOM carpet + shade + overflow banners §a(default)",
                PayloadState.codecMode.label())));
        return 1;
    }

    private static int setCodec(FabricClientCommandSource source, CodecMode mode) {
        CodecMode previous = PayloadState.codecMode;
        PayloadState.codecMode = mode;
        PayloadState.save();
        source.sendFeedback(Text.literal("§aCodec set to: §f" + mode.label()));
        if (!PayloadState.tiles.isEmpty() && previous != mode) {
            reencodeAllTilesAsync(source, mode);
        }
        return 1;
    }

    /**
     * Re-encodes every tile in the current batch with {@code newMode} on a background thread.
     * Carpet ↔ banner conversions are supported; mux state is cleared since it is
     * invalidated by any re-encode.
     */
    private static void reencodeAllTilesAsync(FabricClientCommandSource source, CodecMode newMode) {
        PayloadState.syncToActiveTile();
        int tileCount = PayloadState.tiles.size();
        int columns = PayloadState.gridColumns;

        // Snapshot compressed bytes for every tile before releasing the game thread.
        List<byte[]> compressedSnapshots = new ArrayList<>(tileCount);
        for (PayloadState.TileData tile : PayloadState.tiles) {
            compressedSnapshots.add(getCompressedBytesForTile(tile));
        }

        source.sendFeedback(Text.literal(
                "§7Re-encoding " + tileCount + " tile(s) as §f" + newMode.label() + "§7..."));
        MinecraftClient mc = MinecraftClient.getInstance();

        Thread t = new Thread(() -> {
            record TileResult(List<String> chunks, String carpetB64, boolean carpetEncoded, boolean loomEncoded) {}
            List<TileResult> results = new ArrayList<>(tileCount);

            for (int i = 0; i < tileCount; i++) {
                byte[] compressed = compressedSnapshots.get(i);
                if (compressed == null) { results.add(null); continue; }
                int col = i % columns, row = i / columns;
                if (newMode == CodecMode.BANNER) {
                    List<String> chunks = CjkCodec.buildChunks(compressed);
                    results.add(new TileResult(chunks, null, false, false));
                } else {
                    CarpetEncoding enc = encodeLoomFromCompressed(compressed, col, row, newMode);
                    results.add(new TileResult(
                            enc.allChunks(),
                            Base64.getEncoder().encodeToString(compressed),
                            true, true));
                }
            }

            mc.execute(() -> {
                int ok = 0, over = 0;
                for (int i = 0; i < PayloadState.tiles.size() && i < results.size(); i++) {
                    TileResult r = results.get(i);
                    if (r == null) continue;
                    PayloadState.TileData tile = PayloadState.tiles.get(i);
                    tile.chunks.clear();
                    tile.chunks.addAll(r.chunks());
                    tile.currentIndex = 0;
                    tile.carpetEncoded = r.carpetEncoded();
                    tile.loomEncoded   = r.loomEncoded();
                    tile.carpetCompressedB64 = r.carpetB64();
                    tile.muxed = false; tile.muxReceiver = false; tile.muxCargoB64 = null;
                    ok++;
                    if (compressedSnapshots.get(i) != null
                            && compressedSnapshots.get(i).length > maxBytesForMode(newMode)) over++;
                }
                PayloadState.syncFromActiveTile();
                PayloadState.save();
                String msg = String.format("§aRe-encoded %d tile(s) as %s.", ok, newMode.label());
                if (over > 0) msg += String.format(" §e(%d tile%s over budget — use /loominary reduce.)",
                        over, over == 1 ? "" : "s");
                if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
            });
        }, "loominary-reencode");
        t.setDaemon(true);
        t.start();
    }

    // ════════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════════
    // sparse [all]
    // ════════════════════════════════════════════════════════════════════

    /**
     * Re-encodes the active tile (or all carpet tiles when {@code all=true}) using
     * sparse frame encoding.  Sparse encoding stores only the changed pixels for
     * each frame after frame 0, dramatically reducing payload size for animations
     * with low per-frame change rates (e.g., 2% change → ~30–50× smaller).
     *
     * <p>The command computes both the current compressed size and the sparse
     * compressed size before updating, then reports the comparison.  The tile is
     * updated only when sparse encoding is smaller (or force={@code all}).
     */
    private static int applySparse(FabricClientCommandSource source, boolean all) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (importInProgress) {
            source.sendError(Text.literal("§cA long operation is already in progress."));
            return 0;
        }

        PayloadState.syncToActiveTile();
        // Snapshot state for background thread.
        int first = all ? 0 : PayloadState.activeTileIndex;
        int last  = all ? PayloadState.tiles.size() - 1 : PayloadState.activeTileIndex;
        List<PayloadState.TileData> snapTiles  = new ArrayList<>();
        List<List<String>>          snapChunks = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            snapTiles .add(snapshotTile(PayloadState.tiles.get(i)));
            snapChunks.add(i == PayloadState.activeTileIndex
                    ? new ArrayList<>(PayloadState.ACTIVE_CHUNKS)
                    : new ArrayList<>(PayloadState.tiles.get(i).chunks));
        }

        boolean allCarpet = snapTiles.stream().allMatch(t -> t.carpetEncoded);
        if (!allCarpet) {
            source.sendError(Text.literal("§cSparse encoding requires carpet-encoded tiles."));
            return 0;
        }
        boolean allAnimated = snapTiles.stream().anyMatch(t -> t.frameCount > 1);
        if (!allAnimated) {
            source.sendError(Text.literal("§cNo animated tiles in selection — sparse encoding only applies to animations."));
            return 0;
        }

        importInProgress = true;
        source.sendFeedback(Text.literal("§7Computing sparse encoding"
                + (all ? " for all tiles" : "") + "…"));
        MinecraftClient client = MinecraftClient.getInstance();

        Thread t = new Thread(() -> {
            record TileResult(int idx, int oldBytes, int newBytes,
                              byte[] compressed, byte[] manifest, int fc, int[] delays) {}
            List<TileResult> results = new ArrayList<>();

            for (int ri = 0; ri < snapTiles.size(); ri++) {
                int tileIdx = first + ri;
                PayloadState.TileData tile = snapTiles.get(ri);
                if (tile.frameCount <= 1) continue; // static tile, skip
                try {
                    byte[][] rawFrames = resolveAllFramesForTile(tile, snapChunks.get(ri));
                    int fc = rawFrames.length;
                    if (fc <= 1) continue;

                    int[] delays = tileDelays(tile, fc);
                    // Build sparse payload.
                    byte[] sparsePayload = toSparseFramePayload(rawFrames);
                    // Manifest: FLAG_ANIMATED | FLAG_SPARSE_FRAMES; use inline delays.
                    int flags = (PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0)
                            | PayloadManifest.FLAG_ANIMATED | PayloadManifest.FLAG_SPARSE_FRAMES;
                    int col = PayloadState.tileCol(tileIdx), row = PayloadState.tileRow(tileIdx);
                    long crc = PayloadManifest.crc32(rawFrames[0]);
                    // Normalize delays for inline embedding (sparse tiles don't use trailing).
                    int[] inlineDelays = (delays.length == 1) ? delays : new int[]{medianDelay(delays)};
                    byte[] mfest = PayloadManifest.toBytes(flags,
                            PayloadState.gridColumns, PayloadState.gridRows, col, row,
                            crc, PayloadState.effectiveAuthor(tile.nonce > 0 ? null : ""),
                            PayloadState.currentTitle, tile.nonce, fc, 0, inlineDelays);

                    byte[] combined = new byte[mfest.length + sparsePayload.length];
                    System.arraycopy(mfest,          0, combined, 0,             mfest.length);
                    System.arraycopy(sparsePayload,   0, combined, mfest.length, sparsePayload.length);
                    byte[] newCompressed = compress(combined);

                    int oldBytes = carpetCompressedBytes(tile);
                    results.add(new TileResult(tileIdx, oldBytes, newCompressed.length,
                            newCompressed, mfest, fc, inlineDelays));
                } catch (Exception e) {
                    System.err.println("[Loominary] sparse failed for tile " + tileIdx
                            + ": " + e.getMessage());
                }
            }

            client.execute(() -> {
                try {
                    int improved = 0, skipped = 0;
                    long totalSaved = 0;
                    for (TileResult r : results) {
                        int tileIdx = r.idx();
                        if (tileIdx >= PayloadState.tiles.size()) continue;
                        PayloadState.TileData live = PayloadState.tiles.get(tileIdx);
                        boolean better = r.newBytes() < r.oldBytes();
                        String tag = better
                                ? String.format("§a%d → %d bytes (§a%+d§a)", r.oldBytes(), r.newBytes(), r.newBytes() - r.oldBytes())
                                : String.format("§e%d → %d bytes (§eno improvement§e)", r.oldBytes(), r.newBytes());
                        source.sendFeedback(Text.literal("  " + PayloadState.tileLabel(tileIdx) + ": " + tag));

                        if (better) {
                            live.carpetCompressedB64 = Base64.getEncoder().encodeToString(r.compressed());
                            live.loomEncoded = true;
                            // Rebuild LOOM chunks from the new compressed data.
                            CarpetEncoding enc = encodeLoomFromCompressed(r.compressed(),
                                    PayloadState.tileCol(tileIdx), PayloadState.tileRow(tileIdx),
                                    PayloadState.codecMode);
                            live.chunks.clear();
                            live.chunks.addAll(enc.allChunks());
                            live.currentIndex = 0;
                            live.muxed = false; live.muxReceiver = false; live.muxCargoB64 = null;
                            if (tileIdx == PayloadState.activeTileIndex) {
                                PayloadState.ACTIVE_CHUNKS.clear();
                                PayloadState.ACTIVE_CHUNKS.addAll(live.chunks);
                                PayloadState.activeChunkIndex = 0;
                            }
                            improved++;
                            totalSaved += (long) r.oldBytes() - r.newBytes();
                        } else {
                            skipped++;
                        }
                    }
                    PayloadState.save();
                    String summary = improved > 0
                            ? String.format("§aSparse applied to %d tile%s, saving %d bytes total.%s",
                                    improved, improved == 1 ? "" : "s", totalSaved,
                                    skipped > 0 ? " §e(" + skipped + " tile" + (skipped == 1 ? "" : "s") + " not improved — kept raw)" : "")
                            : "§eSparse encoding did not improve any tiles — all kept as-is.";
                    source.sendFeedback(Text.literal(summary));
                    if (improved > 0)
                        source.sendFeedback(Text.literal("§7Run §f/loominary export§7 to regenerate schematics."));
                } finally {
                    importInProgress = false;
                }
            });
        }, "loominary-sparse");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** Returns the median value of a delay array (used to normalise variable delays for sparse tiles). */
    private static int medianDelay(int[] delays) {
        int[] sorted = delays.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    // mux
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builds a minimal blank-map payload (all-zero colors, no art) for use as a donor tile.
     * Compresses very efficiently; the resulting tile carries only guest bytes.
     */
    private static PayloadState.TileData createBlankDonorTile() {
        byte[] blankColors  = new byte[MAP_BYTES]; // all zeros
        byte[] manifestBytes = PayloadManifest.toBytes(0, 1, 1, 0, 0, 0L, null, null);
        byte[] combined = new byte[manifestBytes.length + blankColors.length];
        System.arraycopy(manifestBytes, 0, combined, 0,                 manifestBytes.length);
        System.arraycopy(blankColors,   0, combined, manifestBytes.length, blankColors.length);
        byte[] compressed = compress(combined);

        PayloadState.TileData tile = new PayloadState.TileData();
        tile.isDonorOnly = true;

        if (PayloadState.codecMode == CodecMode.BANNER) {
            // Banner-only donor: CJK chunks, no carpet platform.
            tile.chunks.addAll(CjkCodec.buildChunks(compressed));
            tile.carpetEncoded = false;
            tile.loomEncoded   = false;
            // carpetCompressedB64 intentionally left null for banner tiles.
        } else {
            CarpetEncoding enc = encodeLoomFromCompressed(compressed, 0, 0, PayloadState.codecMode);
            tile.chunks.addAll(enc.allChunks());
            tile.carpetEncoded = true;
            tile.loomEncoded   = true;
            tile.carpetCompressedB64 = Base64.getEncoder().encodeToString(compressed);
        }
        return tile;
    }

    /**
     * Appends the minimum number of blank donor tiles required to absorb all overflow
     * from over-budget art tiles, then runs mux allocation using only those donor tiles.
     *
     * <p>Calling mux again (remux) removes existing donor tiles and recomputes the
     * minimum needed, which may be fewer if the art was reduced in the interim.
     * Use {@code /loominary mux undo} to remove donor tiles without adding new ones.
     */
    private static int applyMux(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        PayloadState.syncToActiveTile();

        // Remove existing donor tiles and reset mux state (remux support).
        PayloadState.tiles.removeIf(t -> t.isDonorOnly);
        PayloadState.tiles.forEach(t -> { t.muxed = false; t.muxReceiver = false; t.muxCargoB64 = null; });

        CodecMode mode = PayloadState.codecMode;
        int budget = maxBytesForMode(mode);

        // Collect compressed payloads for art tiles — handle both carpet and banner tiles.
        List<byte[]> payloads = new ArrayList<>();
        for (PayloadState.TileData t : PayloadState.tiles) {
            byte[] compressed;
            if (t.carpetEncoded && t.carpetCompressedB64 != null) {
                compressed = Base64.getDecoder().decode(t.carpetCompressedB64);
            } else {
                // Banner tile: reassemble compressed bytes from CJK chunks.
                List<String> names = new ArrayList<>(t.chunks);
                names.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
                if (!names.isEmpty() && names.get(0).length() > 2
                        && names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : names) sb.append(s.substring(2));
                    compressed = CjkCodec.decode(sb.toString());
                } else {
                    compressed = new byte[0];
                }
            }
            payloads.add(compressed);
        }

        int receiverOwnMax = switch (mode) {
            case BANNER               -> (MAX_CHUNKS - 1) * 84;
            case CARPET               -> CarpetChannel.MAX_CARPET_PAYLOAD;
            case CARPET_SHADE         -> CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM;
            case CARPET_BANNERS       -> CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM;
            case CARPET_BANNERS_SHADE -> CarpetChannel.MAX_TOTAL_BYTES_LOOM;
        };

        int totalOverflow = 0;
        int numReceivers = 0;
        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            int len = payloads.get(i).length;
            if (len > budget) { totalOverflow += len - receiverOwnMax; numReceivers++; }
        }
        if (totalOverflow <= 0) {
            source.sendFeedback(Text.literal("§eNo tiles are over budget — mux not needed."));
            return 1;
        }

        // Estimate how many bytes a single blank donor tile can absorb.
        // Overhead per guest: 84 bytes (MG banner) for BANNER/CARPET_BANNERS/CARPET_BANNERS_SHADE,
        // or LOOM_GUEST_DESC bytes (header descriptor) for CARPET/CARPET_SHADE.
        PayloadState.TileData sampleDonor = createBlankDonorTile();
        byte[] sampleCompressed = getCompressedBytesForTile(sampleDonor);
        int blankSize = sampleCompressed != null ? sampleCompressed.length : 0;
        int overheadPerGuest = (mode == CodecMode.CARPET || mode == CodecMode.CARPET_SHADE)
                ? CarpetChannel.LOOM_GUEST_DESC : 84;
        // Reserve overhead for at least 4 guests or as many receivers as exist, whichever is larger.
        int mgOverhead = Math.max(overheadPerGuest * 4, numReceivers * overheadPerGuest);
        int donorCapacity = budget - blankSize - mgOverhead;
        if (donorCapacity <= 0) {
            // Fall back: one donor per receiver.
            donorCapacity = Math.max(1, budget - blankSize - overheadPerGuest);
        }

        int numDonors = (totalOverflow + donorCapacity - 1) / donorCapacity;

        source.sendFeedback(Text.literal(String.format(
                "§7%d art tile%s over budget, %d bytes total overflow. "
                + "Appending %d donor tile%s [codec: %s]…",
                numReceivers, numReceivers == 1 ? "" : "s",
                totalOverflow, numDonors, numDonors == 1 ? "" : "s", mode.label())));

        for (int i = 0; i < numDonors; i++)
            PayloadState.tiles.add(createBlankDonorTile());

        // Run allocation: only blank donor tiles may serve as donors.
        int unresolved = poolMuxTiles(PayloadState.tiles,
                PayloadState.gridColumns, PayloadState.gridRows, /* donorOnlyMode= */ true);

        // If the initial donor estimate was too low, retry with extra donors (up to 3 attempts).
        for (int retry = 0; unresolved > 0 && retry < 3; retry++) {
            for (int i = 0; i < unresolved; i++)
                PayloadState.tiles.add(createBlankDonorTile());
            unresolved = poolMuxTiles(PayloadState.tiles,
                    PayloadState.gridColumns, PayloadState.gridRows, true);
        }

        // Remove any donor tiles that ended up carrying nothing (allocation used fewer than estimated).
        PayloadState.tiles.removeIf(t -> t.isDonorOnly && !t.muxed);

        // Clamp active tile index.
        if (PayloadState.activeTileIndex >= PayloadState.tiles.size())
            PayloadState.activeTileIndex = Math.max(0, PayloadState.tiles.size() - 1);
        PayloadState.syncFromActiveTile();
        PayloadState.save();

        int artCount   = (int) PayloadState.tiles.stream().filter(t -> !t.isDonorOnly).count();
        int donorCount = (int) PayloadState.tiles.stream().filter(t ->  t.isDonorOnly).count();
        source.sendFeedback(Text.literal(String.format(
                "§a%d art tile%s + %d donor tile%s.",
                artCount,   artCount   == 1 ? "" : "s",
                donorCount, donorCount == 1 ? "" : "s")));

        if (unresolved > 0) {
            source.sendFeedback(Text.literal(String.format(
                    "§c%d tile%s could not be resolved — try /loominary reduce first.",
                    unresolved, unresolved == 1 ? "" : "s")));
        } else {
            source.sendFeedback(Text.literal(
                    "§aMux applied. Place all tiles in item frames within 32 blocks of the art, "
                    + "then run §f/loominary export§a to generate schematics."));
        }
        return 1;
    }

    // mux undo
    // ════════════════════════════════════════════════════════════════════

    /**
     * Removes all donor-only tiles that were appended by {@code /loominary mux},
     * resets mux state on remaining art tiles, and re-encodes each art tile from
     * its stored {@code carpetCompressedB64}.  The batch may be left over-budget
     * — that is intentional; the user can re-mux or reduce as needed.
     */
    private static int unmuxTiles(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        PayloadState.syncToActiveTile();

        boolean hadDonors = PayloadState.tiles.removeIf(t -> t.isDonorOnly);
        if (!hadDonors) {
            source.sendFeedback(Text.literal("§eNo donor tiles to remove — batch was not muxed."));
            return 1;
        }

        int reset = 0;
        for (int i = 0; i < PayloadState.tiles.size(); i++) {
            PayloadState.TileData tile = PayloadState.tiles.get(i);
            tile.muxed = false;
            tile.muxReceiver = false;
            String savedMuxCargo = tile.muxCargoB64;
            tile.muxCargoB64 = null;
            if (tile.carpetEncoded && tile.carpetCompressedB64 != null) {
                byte[] compressed = Base64.getDecoder().decode(tile.carpetCompressedB64);
                int col = PayloadState.tileCol(i), row = PayloadState.tileRow(i);
                CarpetEncoding enc = encodeLoomFromCompressed(compressed, col, row, PayloadState.codecMode);
                tile.chunks.clear();
                tile.chunks.addAll(enc.allChunks());
                tile.loomEncoded = true;
                reset++;
            } else if (!tile.carpetEncoded && savedMuxCargo != null) {
                // BANNER mux receiver: restore plain CJK chunks from the stored own segment.
                // Note: only ownSeg is recoverable; the tile will be over-budget until re-imported.
                byte[] ownSeg = Base64.getDecoder().decode(savedMuxCargo);
                tile.chunks.clear();
                tile.chunks.addAll(CjkCodec.buildChunks(ownSeg));
                reset++;
            }
        }

        // Clamp active tile index in case we removed tiles at the end.
        if (PayloadState.activeTileIndex >= PayloadState.tiles.size())
            PayloadState.activeTileIndex = Math.max(0, PayloadState.tiles.size() - 1);
        PayloadState.syncFromActiveTile();
        PayloadState.save();

        long overBudget = PayloadState.tiles.stream()
                .filter(t -> t.carpetEncoded && carpetCompressedBytes(t) > maxBytesForTile(t))
                .count();
        String budgetNote = overBudget > 0
                ? String.format(" §c(%d tile%s still over budget)", overBudget, overBudget == 1 ? "" : "s")
                : "";
        source.sendFeedback(Text.literal(String.format(
                "§aDonor tiles removed; %d art tile%s reset.%s",
                reset, reset == 1 ? "" : "s", budgetNote)));
        return 1;
    }

        // requantize
    // ════════════════════════════════════════════════════════════════════

    private static int setRequantizeMetric(FabricClientCommandSource source,
            PngToMapColors.MatchMetric metric) {
        requantizeMetric = metric;
        String name = switch (metric) {
            case OKLAB        -> "OKLab (perceptual)";
            case CHROMA_FIRST -> "Chroma-first";
            case LUMA_FIRST   -> "Luma-first";
            case HUE_ONLY     -> "Hue-only";
            case RGB          -> "Linear RGB";
        };
        source.sendFeedback(Text.literal("§7Requantize metric: §f" + name
                + "§7 — run §f/loominary requantize§7 to apply."));
        return 1;
    }

    private static int setRequantizeDither(FabricClientCommandSource source,
            PngToMapColors.DitherAlgo algo) {
        requantizeDitherAlgo = algo;
        String name = switch (algo) {
            case NONE            -> "none";
            case FLOYD_STEINBERG -> "Floyd-Steinberg";
            case ATKINSON        -> "Atkinson";
            case BAYER_4X4       -> "Bayer 4×4";
        };
        source.sendFeedback(Text.literal("§7Requantize dither: §f" + name
                + "§7 — run §f/loominary requantize§7 to apply."));
        return 1;
    }

    private static int requantizeAll(FabricClientCommandSource source, boolean preserveTransparent) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (PayloadState.currentSourceFilename == null) {
            source.sendError(Text.literal(
                    "§cNo source file on record — requantize requires the original source image."));
            return 0;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Path filePath = client.runDirectory.toPath()
                .resolve("loominary_data").resolve(PayloadState.currentSourceFilename);
        if (!Files.exists(filePath)) {
            source.sendError(Text.literal("§cSource file not found: loominary_data/"
                    + PayloadState.currentSourceFilename));
            return 0;
        }

        try {
            boolean isGif = PayloadState.currentSourceFilename.toLowerCase().endsWith(".gif");
            boolean legalOnly = !PayloadState.allShades;
            int totalW = PayloadState.gridColumns * MAP_SIZE;
            int totalH = PayloadState.gridRows    * MAP_SIZE;

            // Load source frames (one element for static images, N for GIF).
            BufferedImage[] sourceFrames;
            if (isGif) {
                sourceFrames = PngToMapColors.coalesceGifFrames(filePath);
            } else {
                BufferedImage img = ImageIO.read(filePath.toFile());
                if (img == null) {
                    source.sendError(Text.literal("§cCould not decode source image."));
                    return 0;
                }
                sourceFrames = new BufferedImage[]{img};
            }

            float[][] oklabLookup = PngToMapColors.buildOklabLookup();
            float[][] rgbLookup   = (requantizeMetric == PngToMapColors.MatchMetric.RGB)
                    ? PngToMapColors.buildLinearRgbLookup() : null;

            // Quantize each GIF source frame across the full tile grid.
            // frameResults[srcGifFrame][tileIdx] = byte[MAP_BYTES]
            byte[][][] frameResults = new byte[sourceFrames.length][PayloadState.tiles.size()][];
            for (int gf = 0; gf < sourceFrames.length; gf++) {
                if (requantizeDitherAlgo == PngToMapColors.DitherAlgo.FLOYD_STEINBERG
                        && requantizeMetric == PngToMapColors.MatchMetric.OKLAB) {
                    // Cross-tile path: consistent palette + error propagation across seams.
                    byte[][] tiles = PngToMapColors.convertTwoPassGrid(
                            sourceFrames[gf], legalOnly, 0, true,
                            PayloadState.gridColumns, PayloadState.gridRows);
                    for (int t = 0; t < PayloadState.tiles.size(); t++)
                        frameResults[gf][t] = tiles[t];
                } else {
                    // Per-tile path: scale once, then extract each tile subimage.
                    BufferedImage scaled = PngToMapColors.scale(sourceFrames[gf], totalW, totalH);
                    for (int t = 0; t < PayloadState.tiles.size(); t++) {
                        int col = t % PayloadState.gridColumns;
                        int row = t / PayloadState.gridColumns;
                        BufferedImage tileImg = scaled.getSubimage(
                                col * MAP_SIZE, row * MAP_SIZE, MAP_SIZE, MAP_SIZE);
                        frameResults[gf][t] = quantizeTileImage(tileImg, legalOnly, oklabLookup, rgbLookup);
                    }
                }
            }

            PayloadState.syncToActiveTile();
            String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
            int flags = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;

            for (int t = 0; t < PayloadState.tiles.size(); t++) {
                PayloadState.TileData tile = PayloadState.tiles.get(t);
                List<String> chunks = (t == PayloadState.activeTileIndex)
                        ? PayloadState.ACTIVE_CHUNKS : tile.chunks;

                preReductionChunks.putIfAbsent(t, new ArrayList<>(chunks));
                if (tile.carpetEncoded && tile.carpetCompressedB64 != null)
                    preReductionCarpetB64.putIfAbsent(t, tile.carpetCompressedB64);

                int fc = tile.frameCount;

                // Read existing frames for transparency masking; fails gracefully for
                // mux receiver tiles whose stored data is a partial zstd fragment.
                byte[][] existing = null;
                if (preserveTransparent) {
                    try {
                        existing = resolveAllFramesForTile(tile, chunks);
                    } catch (Exception ignored) { }
                }

                byte[] allFramesBytes = new byte[fc * MAP_BYTES];
                for (int f = 0; f < fc; f++) {
                    int srcGif = (tile.frameSourceIndices != null && f < tile.frameSourceIndices.size())
                            ? tile.frameSourceIndices.get(f) : f;
                    srcGif = Math.min(srcGif, sourceFrames.length - 1);
                    byte[] newFrame = frameResults[srcGif][t].clone();
                    if (existing != null && f < existing.length) {
                        byte[] cur = existing[f];
                        for (int p = 0; p < MAP_BYTES; p++)
                            if (cur[p] == 0) newFrame[p] = 0;
                    }
                    System.arraycopy(newFrame, 0, allFramesBytes, f * MAP_BYTES, MAP_BYTES);
                }

                tile.muxed = false;
                tile.muxReceiver = false;
                tile.muxCargoB64 = null;

                int[] delays = tileDelays(tile, fc);
                long crc = PayloadManifest.crc32(Arrays.copyOf(allFramesBytes, MAP_BYTES));
                byte[] manifest = buildReduceManifest(t, crc, playerName, flags, fc, delays);
                saveTileData(t, manifest, PayloadManifest.withTrailing(manifest, delays, allFramesBytes));
            }

            PayloadState.syncFromActiveTile();
            PayloadState.save();

            String metricName = switch (requantizeMetric) {
                case OKLAB        -> "OKLab";
                case CHROMA_FIRST -> "Chroma";
                case LUMA_FIRST   -> "Luma";
                case HUE_ONLY     -> "Hue";
                case RGB          -> "RGB";
            };
            String ditherName = switch (requantizeDitherAlgo) {
                case NONE            -> "none";
                case FLOYD_STEINBERG -> "FS";
                case ATKINSON        -> "Atkinson";
                case BAYER_4X4       -> "Bayer 4×4";
            };
            source.sendFeedback(Text.literal(String.format(
                    "§6=== Re-quantized %d tile%s [%s, %s%s] ===",
                    PayloadState.tiles.size(), PayloadState.tiles.size() == 1 ? "" : "s",
                    metricName, ditherName,
                    preserveTransparent ? ", preserve-transparent" : "")));
            boolean anyOverBudget = false;
            for (int t = 0; t < PayloadState.tiles.size(); t++) {
                PayloadState.TileData tile = PayloadState.tiles.get(t);
                boolean overBudget = tile.carpetEncoded
                        ? carpetCompressedBytes(tile) > maxBytesForTile(tile)
                        : (t == PayloadState.activeTileIndex
                                ? PayloadState.ACTIVE_CHUNKS.size() > MAX_CHUNKS
                                : tile.chunks.size() > MAX_CHUNKS);
                if (overBudget) anyOverBudget = true;
                source.sendFeedback(Text.literal(String.format("§7  %s: §f%d frame%s%s",
                        PayloadState.tileLabel(t), tile.frameCount,
                        tile.frameCount == 1 ? "" : "s",
                        overBudget ? " §c[OVER BUDGET]" : "")));
            }
            if (anyOverBudget)
                source.sendFeedback(Text.literal(
                        "§e⚠ Some tiles over budget — use §f/loominary reduce§e or §f/loominary mux§e."));
            source.sendFeedback(Text.literal(
                    "§7Use §f/loominary preview§7 to inspect. §f/loominary reduce undo§7 reverts the active tile."));
            return 1;

        } catch (IOException e) {
            source.sendError(Text.literal("§cError reading source: " + e.getMessage()));
            return 0;
        }
    }

    private static byte[] quantizeTileImage(BufferedImage tileImg, boolean legalOnly,
            float[][] oklabLookup, float[][] rgbLookup) {
        // First pass: OKLAB nearest-color to derive the source-based palette.
        byte[] firstPass = new byte[MAP_BYTES];
        for (int y = 0; y < MAP_SIZE; y++)
            for (int x = 0; x < MAP_SIZE; x++)
                firstPass[x + y * MAP_SIZE] = PngToMapColors.findClosestMapColorByte(
                        tileImg.getRGB(x, y), legalOnly, oklabLookup);
        boolean[] palette = PngToMapColors.buildPalette(firstPass);

        return switch (requantizeDitherAlgo) {
            case NONE -> {
                if (requantizeMetric == PngToMapColors.MatchMetric.OKLAB) yield firstPass;
                byte[] out = new byte[MAP_BYTES];
                float[] palHues = (requantizeMetric == PngToMapColors.MatchMetric.HUE_ONLY)
                        ? PngToMapColors.buildPaletteHues(palette, oklabLookup) : null;
                for (int y = 0; y < MAP_SIZE; y++)
                    for (int x = 0; x < MAP_SIZE; x++) {
                        int argb = tileImg.getRGB(x, y);
                        if (((argb >>> 24) & 0xFF) < 128) { out[x + y * MAP_SIZE] = 0; continue; }
                        float[] lab = PngToMapColors.rgbToOklab(argb);
                        out[x + y * MAP_SIZE] = PngToMapColors.findClosestInPalette(
                                lab[0], lab[1], lab[2], palette, oklabLookup,
                                requantizeMetric, rgbLookup, palHues);
                    }
                yield out;
            }
            case FLOYD_STEINBERG -> {
                // Non-OKLAB FS: per-tile path (cross-tile OKLAB path handled above in requantizeAll).
                float[] flat = new float[MAP_BYTES];
                Arrays.fill(flat, 1.0f);
                yield PngToMapColors.renderDithered(tileImg, palette, oklabLookup, flat,
                        MAP_SIZE, MAP_SIZE, 1.0f, requantizeMetric, rgbLookup);
            }
            case ATKINSON -> PngToMapColors.renderAtkinson(tileImg, palette, oklabLookup,
                    MAP_SIZE, MAP_SIZE, requantizeMetric, rgbLookup);
            case BAYER_4X4 -> PngToMapColors.renderBayer4x4(tileImg, palette, oklabLookup,
                    MAP_SIZE, MAP_SIZE, requantizeMetric, rgbLookup);
        };
    }

    // resalt
    // ════════════════════════════════════════════════════════════════════

    private static int resalt(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }
        if (!activeTileHasContent()) {
            source.sendError(Text.literal("§cActive tile has no data."));
            return 0;
        }
        int tileIdx   = PayloadState.activeTileIndex;
        int doneCount = PayloadState.activeChunkIndex;

        PayloadState.TileData tile = PayloadState.tiles.get(tileIdx);
        boolean isCarpet = tile.carpetEncoded;
        try {
            byte[][] frames = resolveAllFramesForTile(tile, PayloadState.ACTIVE_CHUNKS);
            int fc = frames.length;
            byte[] fullData = mergeFrames(frames);

            int nonce;
            do { nonce = ThreadLocalRandom.current().nextInt(); } while (nonce == 0);

            String playerName = PayloadState.effectiveAuthor(source.getPlayer().getGameProfile().getName());
            int flags = PayloadState.allShades ? PayloadManifest.FLAG_ALL_SHADES : 0;
            int[] delays = tileDelays(tile, fc);
            long crc = PayloadManifest.crc32(Arrays.copyOf(fullData, MAP_BYTES));
            tile.nonce = nonce; // must be set before buildReduceManifest reads it
            byte[] manifestBytes = buildReduceManifest(tileIdx, crc, playerName, flags, fc, delays);
            int newCount = saveTileData(tileIdx, manifestBytes,
                    PayloadManifest.withTrailing(manifestBytes, delays, fullData));
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
    // save [name] / load <name>
    // ════════════════════════════════════════════════════════════════════

    private static int saveState(FabricClientCommandSource source, String nameArg) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch to save."));
            return 0;
        }
        try {
            Files.createDirectories(savesDir());
            Path path;
            String saveName;
            if (nameArg != null && !nameArg.isBlank()) {
                saveName = nameArg;
                path = savesDir().resolve(saveName + ".json");
            } else {
                String stem = PayloadState.currentSourceFilename != null
                        ? filenameStem(PayloadState.currentSourceFilename) : "save";
                int n = nextSaveCounter(stem);
                saveName = String.format("%s_%03d", stem, n);
                path = savesDir().resolve(saveName + ".json");
            }
            PayloadState.saveToFile(path);
            source.sendFeedback(Text.literal("§aSaved as: §f" + saveName));
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("§cSave failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int loadState(FabricClientCommandSource source, String name) {
        Path path = savesDir().resolve(name.endsWith(".json") ? name : name + ".json");
        if (!Files.exists(path)) {
            source.sendError(Text.literal("§cSave not found: " + name));
            return 0;
        }
        try {
            String summary = PayloadState.loadFromFile(path);
            PayloadState.save();
            source.sendFeedback(Text.literal("§aLoaded: §f" + summary));
            source.sendFeedback(Text.literal("§7Active: " + PayloadState.tileLabel(PayloadState.activeTileIndex)));
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("§cLoad failed: " + e.getMessage()));
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // export image
    // ════════════════════════════════════════════════════════════════════

    private static int exportImage(FabricClientCommandSource source) {
        if (PayloadState.tiles.isEmpty()) {
            source.sendError(Text.literal("§cNo active batch."));
            return 0;
        }

        // Flush the active tile's working set to TileData before reading any tile.
        PayloadState.syncToActiveTile();

        int cols      = PayloadState.gridColumns;
        int rows      = PayloadState.gridRows;
        int totalTiles = PayloadState.tiles.size();
        int gridW     = cols * 128;
        int gridH     = rows * 128;

        try {
            // Resolve every tile's frames.
            byte[][][] tileFrames = new byte[totalTiles][][];
            for (int ti = 0; ti < totalTiles; ti++) {
                PayloadState.TileData td = PayloadState.tiles.get(ti);
                List<String> chunks = (ti == PayloadState.activeTileIndex)
                        ? PayloadState.ACTIVE_CHUNKS : td.chunks;
                tileFrames[ti] = resolveAllFramesForTile(td, chunks);
            }

            // All tiles in a mural should have the same frame count; take the max.
            int maxFrames = 1;
            for (byte[][] tf : tileFrames) maxFrames = Math.max(maxFrames, tf.length);
            boolean isAnimated = maxFrames > 1;

            // Stitch each frame across the grid.
            byte[][] stitched = new byte[maxFrames][gridW * gridH];
            for (int f = 0; f < maxFrames; f++) {
                for (int ti = 0; ti < totalTiles; ti++) {
                    int tc  = PayloadState.tileCol(ti);
                    int tr  = PayloadState.tileRow(ti);
                    byte[] src = tileFrames[ti][Math.min(f, tileFrames[ti].length - 1)];
                    for (int py = 0; py < 128; py++) {
                        System.arraycopy(src, py * 128,
                                stitched[f], (tr * 128 + py) * gridW + tc * 128, 128);
                    }
                }
            }

            Path exportDir = MinecraftClient.getInstance().runDirectory.toPath()
                    .resolve("loominary_exports");
            Files.createDirectories(exportDir);
            String stem = PayloadState.currentSourceFilename != null
                    ? filenameStem(PayloadState.currentSourceFilename) : "export";

            if (!isAnimated) {
                BufferedImage img = mapColorsToImage(stitched[0], gridW, gridH);
                String outName = stem + ".png";
                ImageIO.write(img, "PNG", exportDir.resolve(outName).toFile());
                source.sendFeedback(Text.literal("§aExported image: §f" + outName));
            } else {
                // Collect delays from the first tile that has per-frame delay data.
                int[] delays = new int[maxFrames];
                Arrays.fill(delays, 100);
                for (int ti = 0; ti < totalTiles; ti++) {
                    List<Integer> fd = PayloadState.tiles.get(ti).frameDelays;
                    if (fd != null && !fd.isEmpty()) {
                        for (int f = 0; f < maxFrames; f++)
                            delays[f] = fd.get(Math.min(f, fd.size() - 1));
                        break;
                    }
                }
                String outName = stem + ".gif";
                writeAnimatedGif(stitched, gridW, gridH, delays, exportDir.resolve(outName));
                source.sendFeedback(Text.literal(String.format(
                        "§aExported animated GIF: §f%s §7(%d frames, %dx%d)",
                        outName, maxFrames, gridW, gridH)));
            }
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§cImage export failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Converts a map-color byte array to an ARGB BufferedImage.
     * Map color byte 0 (void/empty) is exported as fully transparent.
     */
    private static BufferedImage mapColorsToImage(byte[] mapColors, int w, int h) {
        int[] colorLookup = PngToMapColors.buildColorLookup();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < w * h; i++) {
            int b = mapColors[i] & 0xFF;
            int argb = (b == 0) ? 0x00000000 : (0xFF000000 | colorLookup[b]);
            img.setRGB(i % w, i / w, argb);
        }
        return img;
    }

    /** Writes frames as a looping animated GIF with per-frame delays (in ms). */
    private static void writeAnimatedGif(byte[][] frames, int w, int h, int[] delaysMs, Path path)
            throws IOException {
        // Build a global IndexColorModel from all map color bytes used across all frames.
        // Palette index 0 is reserved for map byte 0 (void/transparent).
        int[] colorLookup = PngToMapColors.buildColorLookup();
        boolean[] used = new boolean[256];
        for (byte[] frame : frames) for (byte b : frame) used[b & 0xFF] = true;

        int[] mapByteToIdx = new int[256]; // default 0 = transparent index
        byte[] palR = new byte[256], palG = new byte[256], palB = new byte[256];
        int palSize = 1;
        for (int b = 1; b < 256; b++) {
            if (!used[b]) continue;
            int rgb = colorLookup[b];
            mapByteToIdx[b] = palSize;
            palR[palSize] = (byte)((rgb >> 16) & 0xFF);
            palG[palSize] = (byte)((rgb >>  8) & 0xFF);
            palB[palSize] = (byte)( rgb         & 0xFF);
            palSize++;
        }
        // Index 0 = transparent.
        IndexColorModel icm = new IndexColorModel(8, palSize, palR, palG, palB, 0);

        ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (int f = 0; f < frames.length; f++) {
                BufferedImage indexed = new BufferedImage(w, h,
                        BufferedImage.TYPE_BYTE_INDEXED, icm);
                byte[] raster = ((DataBufferByte) indexed.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < w * h; i++)
                    raster[i] = (byte) mapByteToIdx[frames[f][i] & 0xFF];

                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(indexed), null);
                String formatName = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(formatName);

                IIOMetadataNode gce = getOrCreateChild(root, "GraphicControlExtension");
                int delayCentiseconds = Math.max(1,
                        (f < delaysMs.length ? delaysMs[f] : delaysMs[delaysMs.length - 1]) / 10);
                gce.setAttribute("delayTime", String.valueOf(delayCentiseconds));
                gce.setAttribute("disposalMethod", "restoreToBackgroundColor");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "TRUE");
                gce.setAttribute("transparentColorIndex", "0");

                if (f == 0) {
                    IIOMetadataNode appExts = getOrCreateChild(root, "ApplicationExtensions");
                    IIOMetadataNode appExt = new IIOMetadataNode("ApplicationExtension");
                    appExt.setAttribute("applicationID", "NETSCAPE");
                    appExt.setAttribute("authenticationCode", "2.0");
                    appExt.setUserObject(new byte[]{0x1, 0x0, 0x0}); // loop forever
                    appExts.appendChild(appExt);
                }

                meta.setFromTree(formatName, root);
                writer.writeToSequence(new IIOImage(indexed, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static IIOMetadataNode getOrCreateChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equals(name))
                return (IIOMetadataNode) parent.item(i);
        }
        IIOMetadataNode child = new IIOMetadataNode(name);
        parent.appendChild(child);
        return child;
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