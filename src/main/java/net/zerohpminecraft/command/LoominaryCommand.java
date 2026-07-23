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
import net.zerohpminecraft.AutoPrintHandler;
import net.zerohpminecraft.AutoWalkHandler;
import net.zerohpminecraft.BannerAutoClickHandler;
import net.zerohpminecraft.CarpetBalanceHandler;
import net.zerohpminecraft.CarpetFillHandler;
import net.zerohpminecraft.CarpetChannel;
import net.zerohpminecraft.CodecMode;
import net.zerohpminecraft.LitematicaBridge;
import net.zerohpminecraft.MuxAllocator;
import net.zerohpminecraft.PrintVerifier;
import net.zerohpminecraft.WaypointMover;
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
 * /loominary click — toggle auto-right-click of banners while holding map
 * /loominary click stop — stop auto-clicking
 * /loominary whitelist — show how many named banners are whitelisted for reuse
 * /loominary whitelist add — scan inventory + bundle contents, mark every named banner reusable
 * /loominary whitelist clear — empty the whitelist
 * /loominary mux — append blank donor tiles to absorb overflow from over-budget art tiles
 * /loominary mux undo — remove all donor tiles and reset mux state (may leave batch over-budget)
 * /loominary export [name] — write a Litematica .litematic for active tile
 * /loominary clear [memory|disk] — clear state
 *
 * Image editing (edit/reduce/dither/filter/requantize/palette/sparse/stride/skip/resalt)
 * was removed in v2.0.0 — it lives in the web editor now. Those literals remain as
 * stubs that print a pointer (see {@link #removedCommand}).
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
        return MuxAllocator.budget(mode);
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

        int receiverOwnMax = MuxAllocator.receiverOwnMax(mode);

        // Budget threshold: payload bytes that exceed this make a tile a receiver.
        int budget = maxBytesForMode(mode);

        List<Integer> receivers = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++)
            if (payloads.get(i).length > budget) receivers.add(i);
        if (receivers.isEmpty()) return 0;

        // Pure allocation (extracted so MuxAllocationParityTest can lock web↔mod equivalence).
        int[] sizes = new int[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) sizes[i] = payloads.get(i).length;
        MuxAllocator.Allocation alloc = MuxAllocator.allocate(sizes, mode,
                d -> !donorOnlyMode || tiles.get(d).isDonorOnly);
        int unresolved = alloc.unresolved;
        List<int[]>[] guestMeta = alloc.guestMeta; // {targetIdx, offsetInPayload, len}

        @SuppressWarnings("unchecked")
        List<byte[]>[] guestData = new List[tiles.size()];
        for (int d = 0; d < tiles.size(); d++) {
            guestData[d] = new ArrayList<>();
            for (int[] m : guestMeta[d])
                guestData[d].add(Arrays.copyOfRange(payloads.get(m[0]), m[1], m[1] + m[2]));
        }
        for (int i = 0; i < tiles.size(); i++) {
            if (alloc.receiver[i]) {
                tiles.get(i).muxReceiver = true;
                tiles.get(i).muxed = true;
            }
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

    // ── Cache for preview / revert ─────────────────────────────────────

    private static final Map<Integer, byte[]> originalColors = new HashMap<>();
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

                            // ── stop ───────────────────────────────────────────
                            // Panic button: halt every Loominary automation at once
                            // (auto-print, carpet fill, catalogue, auto-walk). Also bindable
                            // to a key (Controls → Loominary → "Stop all").
                            .then(ClientCommandManager.literal("stop")
                                    .executes(ctx -> stopAll(ctx.getSource())))

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

                            // ── removed in v2.0.0 — editing moved to the web editor ──
                            .then(removedCommand("edit"))
                            .then(removedCommand("palette"))
                            .then(removedCommand("reduce"))
                            .then(removedCommand("dither"))
                            .then(removedCommand("filter"))
                            .then(removedCommand("requantize"))
                            .then(removedCommand("sparse"))
                            .then(removedCommand("stride"))
                            .then(removedCommand("skip"))
                            .then(removedCommand("resalt"))

                            // ── click ──────────────────────────────────────────
                            .then(ClientCommandManager.literal("click")
                                    .executes(ctx -> clickToggle(ctx.getSource()))
                                    .then(ClientCommandManager.literal("stop")
                                            .executes(ctx -> clickStop(ctx.getSource()))))

                            // ── carpets ────────────────────────────────────────
                            .then(ClientCommandManager.literal("carpets")
                                    .then(ClientCommandManager.literal("balance")
                                            .executes(ctx -> carpetBalance(ctx.getSource())))
                                    .then(ClientCommandManager.literal("catalogue")
                                            .executes(ctx -> carpetCatalogue(ctx.getSource())))
                                    .then(ClientCommandManager.literal("fill")
                                            .executes(ctx -> carpetFill(ctx.getSource()))
                                            .then(ClientCommandManager.argument("width", IntegerArgumentType.integer(1, 64))
                                                    .executes(ctx -> {
                                                        CarpetBalanceHandler.setBandWidth(
                                                                IntegerArgumentType.getInteger(ctx, "width"));
                                                        return carpetFill(ctx.getSource());
                                                    }))))

                            // ── walk ───────────────────────────────────────────
                            // No args: toggle (same as the hotkey). Two ints (forward ticks,
                            // pause ticks): set the timings the hotkey follows.
                            .then(ClientCommandManager.literal("walk")
                                    .executes(ctx -> autoWalkToggle(ctx.getSource()))
                                    .then(ClientCommandManager.literal("stop")
                                            .executes(ctx -> autoWalkStop(ctx.getSource())))
                                    .then(ClientCommandManager.literal("printer")
                                            .then(ClientCommandManager.literal("on")
                                                    .executes(ctx -> printerDebug(ctx.getSource(), true)))
                                            .then(ClientCommandManager.literal("off")
                                                    .executes(ctx -> printerDebug(ctx.getSource(), false))))
                                    .then(ClientCommandManager.literal("print")
                                            .executes(ctx -> autoPrintStart(ctx.getSource()))
                                            .then(ClientCommandManager.literal("stop")
                                                    .executes(ctx -> autoPrintStop(ctx.getSource())))
                                            .then(ClientCommandManager.argument("width", IntegerArgumentType.integer(1, 64))
                                                    .executes(ctx -> {
                                                        CarpetBalanceHandler.setBandWidth(
                                                                IntegerArgumentType.getInteger(ctx, "width"));
                                                        return autoPrintStart(ctx.getSource());
                                                    })))
                                    // Verify the printed floor would capture exactly (no empty map used);
                                    // highlights misplaced carpets/obstructions with boxes.
                                    .then(ClientCommandManager.literal("verify")
                                            .executes(ctx -> autoPrintVerify(ctx.getSource()))
                                            .then(ClientCommandManager.literal("clear")
                                                    .executes(ctx -> autoPrintVerifyClear(ctx.getSource()))))
                                    .then(ClientCommandManager.argument("on", IntegerArgumentType.integer(1, 12000))
                                            .then(ClientCommandManager.argument("off", IntegerArgumentType.integer(0, 12000))
                                                    .executes(ctx -> autoWalkSetTimings(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "on"),
                                                            IntegerArgumentType.getInteger(ctx, "off"))))))

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
                                            .executes(ctx -> clearDisk(ctx.getSource()))))

                            // ── dumppalette ─────────────────────────────────────
                            .then(ClientCommandManager.literal("dumppalette")
                                    .executes(ctx -> dumpPalette(ctx.getSource())))
                            // ── dumpcarpet ───────────────────────────────────────
                            .then(ClientCommandManager.literal("dumpcarpet")
                                    .executes(ctx -> dumpCarpet(ctx.getSource()))));
        });
    }

    /**
     * Stub for a command removed in v2.0.0 (image editing moved to the web editor).
     * The greedy tail swallows any old argument form, so muscle-memory invocations
     * like {@code /loominary reduce all colors 32} get the pointer instead of a
     * Brigadier parse error.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            removedCommand(String name) {
        com.mojang.brigadier.Command<FabricClientCommandSource> exec = ctx -> {
            ctx.getSource().sendError(Text.literal(
                    "§c/loominary " + name + " was removed in v2.0.0 — image editing now lives in "
                    + "the web editor: §fhttps://zerohpminecraft.github.io/loominary/"));
            return 0;
        };
        return ClientCommandManager.literal(name)
                .executes(exec)
                .then(ClientCommandManager.argument("rest", StringArgumentType.greedyString())
                        .executes(exec));
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
                        // v2: no in-game reduce — an import that can't fit is aborted.
                        if (finalAnyOverflow) {
                            source.sendError(Text.literal(String.format(
                                    "§cImport aborted — GIF is over budget on some tiles (%d frame%s).",
                                    finalFrameCount, finalFrameCount == 1 ? "" : "s")));
                            for (int i = 0; i < finalTileCount; i++) {
                                if (finalNotes.get(i) != null)
                                    source.sendError(Text.literal("§c  tile " + i + ": " + finalNotes.get(i)));
                            }
                            source.sendError(Text.literal(
                                    "§cUse the web editor — its AV1 animation modes fit far more frames: "
                                    + "§fhttps://zerohpminecraft.github.io/loominary/"));
                            return;
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
                            // Still over budget even after auto-reduce. Encode it anyway so the
                            // mux post-pass can try to redistribute; if that fails too, the
                            // import is aborted below.
                            byte[] combined = new byte[manifestBytes.length + mapColors.length];
                            System.arraycopy(manifestBytes, 0, combined, 0,                   manifestBytes.length);
                            System.arraycopy(mapColors,     0, combined, manifestBytes.length, mapColors.length);
                            byte[] compressed = compress(combined);
                            enc = encodeLoomFromCompressed(compressed, col, row, PayloadState.codecMode);
                            note = "over budget";
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

                // v2: no in-game reduce — an import that can't fit is aborted, not
                // committed over budget.
                boolean stillOverBudget = newTiles.stream().anyMatch(
                        tile2 -> carpetCompressedBytes(tile2) > maxBytesForTile(tile2));
                if (stillOverBudget) {
                    final boolean triedMux = mux;
                    client.execute(() -> {
                        try {
                            source.sendError(Text.literal(
                                    "§cImport aborted — the image doesn't fit the tile budget"
                                    + (triedMux ? " even after mux pooling."
                                                : " (adding §fmux§c may help on multi-tile grids).")));
                            source.sendError(Text.literal(
                                    "§cUse the web editor to shrink it (palette reduction, dithering, mux): "
                                    + "§fhttps://zerohpminecraft.github.io/loominary/"));
                        } finally {
                            importInProgress = false;
                        }
                    });
                    return;
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
                    tileNote = "over budget";
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
                    // v2: no in-game reduce — an import that can't fit is aborted.
                    if (finalAnyOverflow) {
                        source.sendError(Text.literal(String.format(
                                "§cImport aborted — GIF is over budget on some tiles (%d frame%s)"
                                + "%s.", finalFrameCount, finalFrameCount == 1 ? "" : "s",
                                finalMuxApplied ? " even after mux pooling" : "")));
                        source.sendError(Text.literal(
                                "§cUse the web editor — its AV1 animation modes fit far more frames: "
                                + "§fhttps://zerohpminecraft.github.io/loominary/"));
                        return;
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
                    int maxOvf = finalTiles.stream().mapToInt(ti -> overflowBannerCount(ti, ti.chunks)).max().orElse(0);
                    if (maxOvf > 0)
                        source.sendFeedback(Text.literal(String.format(
                                "§e⚠ %d overflow banner%s needed — rename at anvil and click with map.",
                                maxOvf, maxOvf == 1 ? "" : "s")));
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
        String budgetWarn = anyOverBudget ? " §c[some tiles over budget — re-export from the web editor]" : "";
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

                if (!originalColors.containsKey(cell.mapId().id())) {
                    originalColors.put(cell.mapId().id(), cell.mapState().colors.clone());
                }

                // AV1 payloads (lossless, lossy, sRGB v7, composite) carry a stream after the
                // header, not raw colours — a plain 16,384-byte copy paints garbage. Route them
                // through the real decode pipeline; everything else keeps the fast direct paint.
                byte[] full = resolveFullPayloadForTile(tile, tile.chunks);
                PayloadManifest previewMf = full.length == MAP_BYTES
                        ? null : PayloadManifest.fromBytes(full);
                if (previewMf != null && previewMf.av1()) {
                    MapBannerDecoder.paintFromDecompressed(
                            fm.client, cell.mapId(), cell.mapState(), cell.frame(), full);
                } else {
                    byte[] mapColors = resolveMapColorsForTile(tile, tile.chunks);
                    MapBannerDecoder.paintMap(fm.client, cell.mapId(), cell.mapState(), mapColors);
                    if (tile.frameCount > 1) {
                        try {
                            MapBannerDecoder.registerAnimatedFromDecompressed(
                                    fm.client, cell.mapId(), cell.frame().getBlockPos(), full);
                        } catch (Exception ignored) {}
                    }
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

    private static int carpetBalance(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        CarpetBalanceHandler.activate(client);
        return 1;
    }

    private static int carpetFill(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (CarpetFillHandler.isActive()) {
            CarpetFillHandler.stop();
            source.sendFeedback(Text.literal("§7Carpet fill stopped."));
            return 1;
        }
        CarpetFillHandler.start(client);
        return 1;
    }

    /** No-arg /loominary walk: toggle the duty-cycle auto-walk (same as the hotkey). */
    private static int autoWalkToggle(FabricClientCommandSource source) {
        AutoWalkHandler.toggle(MinecraftClient.getInstance());
        return 1;
    }

    /** /loominary walk <on> <off>: set the timings the hotkey follows (doesn't start it). */
    private static int autoWalkSetTimings(FabricClientCommandSource source, int onTicks, int offTicks) {
        AutoWalkHandler.setTimings(MinecraftClient.getInstance(), onTicks, offTicks);
        return 1;
    }

    private static int autoWalkStop(FabricClientCommandSource source) {
        AutoWalkHandler.stop(MinecraftClient.getInstance());
        return 1;
    }

    /** /loominary carpets catalogue: walk + open every nearby chest once to build chest memory. */
    private static int carpetCatalogue(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (CarpetFillHandler.isActive()) {
            CarpetFillHandler.stop();
            source.sendFeedback(Text.literal("§7Carpet catalogue stopped."));
            return 1;
        }
        CarpetFillHandler.startCatalogue(client);
        return 1;
    }

    /** /loominary stop: halt every Loominary automation at once (the panic button). */
    private static int stopAll(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean any = AutoPrintHandler.isActive() || CarpetFillHandler.isActive()
                || AutoWalkHandler.isActive() || PrintVerifier.isActive();
        AutoPrintHandler.stop(client);              // also stops a fill/catalogue it's driving
        if (CarpetFillHandler.isActive()) CarpetFillHandler.stop();   // a standalone fill/catalogue
        AutoWalkHandler.stop(client);
        WaypointMover.stop();
        PrintVerifier.clear();                      // cancel any scan + hide highlight boxes
        source.sendFeedback(Text.literal(any
                ? "§e§lLoominary§r §7stopped all automation."
                : "§7Loominary: nothing was running."));
        return 1;
    }

    /** /loominary walk print: autonomously walk the serpentine and print carpets with the printer. */
    private static int autoPrintStart(FabricClientCommandSource source) {
        AutoPrintHandler.start(MinecraftClient.getInstance());
        return 1;
    }

    private static int autoPrintStop(FabricClientCommandSource source) {
        AutoPrintHandler.stop(MinecraftClient.getInstance());
        return 1;
    }

    /** /loominary walk verify: check the printed floor would capture exactly, highlighting problems. */
    private static int autoPrintVerify(FabricClientCommandSource source) {
        PrintVerifier.start(MinecraftClient.getInstance());
        return 1;
    }

    /** /loominary walk verify clear: hide the verify highlight boxes. */
    private static int autoPrintVerifyClear(FabricClientCommandSource source) {
        PrintVerifier.clear();
        return 1;
    }

    /** Debug: toggle the Litematica printer directly, to confirm the reflection binding works. */
    private static int printerDebug(FabricClientCommandSource source, boolean on) {
        boolean ok = LitematicaBridge.setPrinter(on);
        if (ok) {
            source.sendFeedback(Text.literal("§a§lLoominary§r §7printer → " + (on ? "§aON" : "§eOFF")
                    + " §7(now reads " + LitematicaBridge.isPrinterOn() + ")"));
        } else {
            source.sendFeedback(Text.literal("§c§lLoominary§r §7couldn't toggle the printer — "
                    + "litematica-printer fork not found."));
        }
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
                        + "Use §f/loominary mux§c or re-export from the web editor."));
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
                if (over > 0) msg += String.format(" §e(%d tile%s over budget — re-export from the web editor.)",
                        over, over == 1 ? "" : "s");
                if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
            });
        }, "loominary-reencode");
        t.setDaemon(true);
        t.start();
    }

    // ════════════════════════════════════════════════════════════════════
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
                    "§c%d tile%s could not be resolved — shrink the image in the web editor first.",
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
            AnvilAutoFillHandler.clearHalt();
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
    // dumppalette — emit the full MC 1.21.4 map colour table for web port
    // ════════════════════════════════════════════════════════════════════

    private static int dumpPalette(FabricClientCommandSource source) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Minecraft 1.21.4 map colour table — generated by /loominary dumppalette\n");
        sb.append("// Index = (colorId << 2) | shadeId  (byte value used in MapState.colors)\n");
        sb.append("// Format: [index, R, G, B]\n");
        sb.append("export const MC_PALETTE: [number, number, number][] = new Array(256).fill(null);\n");
        for (int b = 4; b < 256; b++) {  // byte 0-3 are all shade variants of transparent (colorId=0)
            int colorId = (b >> 2) & 0x3F;
            int shadeId = b & 0x3;
            if (colorId < 1 || colorId > 63) continue;
            net.minecraft.block.MapColor mc = net.minecraft.block.MapColor.get(colorId);
            if (mc == null || mc == net.minecraft.block.MapColor.CLEAR) continue;
            for (net.minecraft.block.MapColor.Brightness br : net.minecraft.block.MapColor.Brightness.values()) {
                if (br.id == shadeId) {
                    int rgb = mc.getRenderColor(br);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >>  8) & 0xFF;
                    int bl = rgb        & 0xFF;
                    sb.append(String.format("MC_PALETTE[%3d] = [%3d, %3d, %3d]; // colorId=%d shade=%d\n",
                            b, r, g, bl, colorId, shadeId));
                    break;
                }
            }
        }
        // Write to loominary_data/palette_dump.ts
        try {
            java.nio.file.Path out = MinecraftClient.getInstance().runDirectory.toPath()
                    .resolve("loominary_data/palette_dump.ts");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, sb.toString());
            source.sendFeedback(Text.literal("§aPalette dumped to loominary_data/palette_dump.ts ("
                    + sb.toString().lines().count() + " lines)"));
        } catch (java.io.IOException e) {
            source.sendError(Text.literal("§cFailed to write palette dump: " + e.getMessage()));
        }
        return 1;
    }

    // dumpcarpet — emit the DyeColor→mapByte table so carpet.ts can be verified
    // ════════════════════════════════════════════════════════════════════════

    private static int dumpCarpet(FabricClientCommandSource source) {
        net.minecraft.util.DyeColor[] colors = net.minecraft.util.DyeColor.values();
        StringBuilder sb = new StringBuilder();
        sb.append("// Carpet map-byte table — generated by /loominary dumpcarpet\n");
        sb.append("// Paste the CARPET_DUMP line into the web editor's carpet dump input.\n");
        sb.append("//\n");
        sb.append("// nibble  DyeColor    mapByte  colorId  shade\n");
        sb.append("// ──────────────────────────────────────────────\n");

        int[] bytes = new int[16];
        for (int i = 0; i < 16 && i < colors.length; i++) {
            bytes[i] = net.zerohpminecraft.CarpetChannel.NIBBLE_TO_MAP_BYTE[i] & 0xFF;
            int colorId = bytes[i] >> 2;
            int shade   = bytes[i] & 3;
            sb.append(String.format("//  %2d     %-12s  %3d      %3d      %d\n",
                    i, colors[i].getName(), bytes[i], colorId, shade));
        }
        sb.append("//\n");
        // Emit the paste-ready line for the web editor.
        sb.append("CARPET_DUMP=[");
        for (int i = 0; i < 16; i++) sb.append(i > 0 ? "," : "").append(bytes[i]);
        sb.append("]\n");

        try {
            java.nio.file.Path out = MinecraftClient.getInstance().runDirectory.toPath()
                    .resolve("loominary_data/carpet_dump.txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, sb.toString());
            source.sendFeedback(Text.literal(
                    "§aCarpet table dumped to loominary_data/carpet_dump.txt\n"
                    + "§7Paste the CARPET_DUMP=[ ... ] line into the web editor."));
        } catch (java.io.IOException e) {
            source.sendError(Text.literal("§cFailed to write carpet dump: " + e.getMessage()));
        }
        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    // clear [memory|disk]
    // ════════════════════════════════════════════════════════════════════

    private static int clearAll(FabricClientCommandSource source) {
        PayloadState.clear();
        AnvilAutoFillHandler.clearHalt();
        originalColors.clear();
        titleIsUserSet = false;
        BannerAutoClickHandler.stop();
        BannerAutoClickHandler.clearMarkers();
        source.sendFeedback(Text.literal("§aCleared all state."));
        return 1;
    }

    private static int clearMemory(FabricClientCommandSource source) {
        PayloadState.clearMemory();
        AnvilAutoFillHandler.clearHalt();
        originalColors.clear();
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