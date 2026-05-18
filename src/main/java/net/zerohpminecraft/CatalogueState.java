package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Persistent library of mapart entries. Stored in config/loominary_catalogue.json.
 *
 * Each entry holds the full tile payload so it can be restored into PayloadState
 * for schematic export and placement — downloaded art is treated identically to
 * locally-imported art from the user's perspective.
 */
public class CatalogueState {

    private static final String TAG = "[Loominary]";
    private static final String FILE_NAME = "loominary_catalogue.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final List<CatalogueEntry> entries = new ArrayList<>();

    public static class CatalogueEntry {
        public String  id;              // UUID hex (no dashes)
        public String  title;           // display name
        public String  author;          // player name from manifest
        public String  dateAdded;       // ISO-8601 date added to this catalogue
        public String  sourcePeer;      // null = locally created; player name = received via P2P
        public String  sourceVersion;   // catalog version hash at download time
        public int     gridCols  = 1;
        public int     gridRows  = 1;
        public int     frameCount = 1;
        /** 10×10 mode-pooled downscale of tile-0 frame-0, zstd-compressed, base64-encoded. */
        public String  thumbnailB64;
        public boolean shareEnabled = false;
        public long    createdAt;
        public long    updatedAt;

        /** Full tile payloads — null for remote stubs received via catalog exchange. */
        public List<TileSnapshot> tiles;
    }

    public static class TileSnapshot {
        public boolean      carpetEncoded;
        public String       carpetCompressedB64; // zstd payload, base64
        public int          frameCount = 1;
        public List<String> chunks;              // banner-encoded tiles only
        public int          nonce;
    }

    // ── Persistence ────────────────────────────────────────────────────

    public static void save() {
        try {
            Wrapper w = new Wrapper();
            w.entries = entries;
            Files.writeString(configPath(), GSON.toJson(w));
        } catch (IOException e) {
            System.err.println(TAG + " Failed to save catalogue: " + e.getMessage());
        }
    }

    public static void load() {
        Path p = configPath();
        if (!Files.exists(p)) return;
        try {
            Wrapper w = GSON.fromJson(Files.readString(p), Wrapper.class);
            entries.clear();
            if (w != null && w.entries != null) entries.addAll(w.entries);
        } catch (Exception e) {
            System.err.println(TAG + " Failed to load catalogue: " + e.getMessage());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static class Wrapper {
        List<CatalogueEntry> entries;
    }

    // ── Factory ────────────────────────────────────────────────────────

    /**
     * Creates a {@link CatalogueEntry} from the current {@link PayloadState} batch.
     * Does NOT add it to {@link #entries} — caller is responsible for that.
     */
    public static CatalogueEntry fromCurrentBatch() {
        PayloadState.syncToActiveTile();

        CatalogueEntry entry = new CatalogueEntry();
        entry.id        = UUID.randomUUID().toString().replace("-", "");
        entry.gridCols  = PayloadState.gridColumns;
        entry.gridRows  = PayloadState.gridRows;
        entry.createdAt = System.currentTimeMillis();
        entry.updatedAt = entry.createdAt;
        entry.dateAdded = LocalDate.now().toString();
        entry.sourcePeer = null;

        // Extract title/author/frameCount from the first tile's manifest
        String title = PayloadState.currentTitle;
        String author = null;
        int frameCount = 1;
        if (!PayloadState.tiles.isEmpty()) {
            PayloadState.TileData t0 = PayloadState.tiles.get(0);
            if (t0.carpetEncoded && t0.carpetCompressedB64 != null) {
                try {
                    byte[] compressed = Base64.getDecoder().decode(t0.carpetCompressedB64);
                    long fs = Zstd.getFrameContentSize(compressed);
                    if (fs > 0) {
                        byte[] full = Zstd.decompress(compressed, (int) fs);
                        if (full.length > 16384) {
                            PayloadManifest mf = PayloadManifest.fromBytes(full);
                            if (title == null && mf.title != null) title = mf.title;
                            author = mf.username;
                            frameCount = mf.frameCount;
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (frameCount == 1) frameCount = t0.frameCount;
        }
        if (author == null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) author = mc.player.getGameProfile().getName();
        }

        entry.title      = title != null ? title : "Untitled";
        entry.author     = author;
        entry.frameCount = frameCount;

        // Build tile snapshots
        entry.tiles = new ArrayList<>();
        for (PayloadState.TileData td : PayloadState.tiles) {
            TileSnapshot snap = new TileSnapshot();
            snap.carpetEncoded      = td.carpetEncoded;
            snap.carpetCompressedB64 = td.carpetCompressedB64;
            snap.frameCount         = td.frameCount;
            snap.nonce              = td.nonce;
            snap.chunks             = td.chunks.isEmpty() ? null : new ArrayList<>(td.chunks);
            entry.tiles.add(snap);
        }

        // Thumbnail from tile 0 frame 0
        entry.thumbnailB64 = buildThumbnail(entry);

        return entry;
    }

    /**
     * Restores a {@link CatalogueEntry} into {@link PayloadState}, replacing the
     * current batch. The entry's original title/author metadata is preserved as-is.
     */
    public static void loadIntoBatch(CatalogueEntry entry) {
        if (entry.tiles == null || entry.tiles.isEmpty()) return;

        PayloadState.tiles.clear();
        PayloadState.ACTIVE_CHUNKS.clear();
        PayloadState.activeChunkIndex = 0;
        PayloadState.activeTileIndex  = 0;

        for (TileSnapshot snap : entry.tiles) {
            PayloadState.TileData td = new PayloadState.TileData();
            td.carpetEncoded       = snap.carpetEncoded;
            td.carpetCompressedB64 = snap.carpetCompressedB64;
            td.frameCount          = snap.frameCount;
            td.nonce               = snap.nonce;
            if (snap.chunks != null) td.chunks.addAll(snap.chunks);
            PayloadState.tiles.add(td);
        }

        PayloadState.gridColumns           = entry.gridCols;
        PayloadState.gridRows              = entry.gridRows;
        PayloadState.currentTitle          = entry.title;
        PayloadState.currentSourceFilename = "catalogue: " + entry.title;
        PayloadState.activeTileIndex       = 0;
        PayloadState.syncFromActiveTile();
        PayloadState.save();
    }

    // ── Thumbnail ──────────────────────────────────────────────────────

    /**
     * Generates a 10×10 thumbnail for tile-0 frame-0 of an entry.
     * Each output pixel = mode (most frequent) map color ID in its 12.8×12.8 input region.
     * Returns base64-encoded zstd-compressed 100 bytes, or null on failure.
     */
    public static String buildThumbnail(CatalogueEntry entry) {
        if (entry.tiles == null || entry.tiles.isEmpty()) return null;
        TileSnapshot snap = entry.tiles.get(0);
        byte[] mapColors = extractFirstFrameColors(snap);
        if (mapColors == null) return null;
        return generateThumbnail(mapColors);
    }

    public static String generateThumbnail(byte[] mapColors) {
        if (mapColors == null || mapColors.length < 16384) return null;
        final int OUT = 10, IN = 128;
        byte[] thumb = new byte[OUT * OUT];
        for (int ty = 0; ty < OUT; ty++) {
            for (int tx = 0; tx < OUT; tx++) {
                // Source region: [tx*12 .. tx*12+12) x [ty*12 .. ty*12+12) (approx)
                int x0 = tx * IN / OUT, x1 = (tx + 1) * IN / OUT;
                int y0 = ty * IN / OUT, y1 = (ty + 1) * IN / OUT;
                // Count frequency of each map color ID in this block
                int[] freq = new int[256];
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        freq[mapColors[sy * IN + sx] & 0xFF]++;
                    }
                }
                // Find mode (exclude color ID 0 = transparent/void)
                int best = 0, bestFreq = -1;
                for (int c = 1; c < 256; c++) {
                    if (freq[c] > bestFreq) { bestFreq = freq[c]; best = c; }
                }
                thumb[ty * OUT + tx] = (byte) best;
            }
        }
        try {
            byte[] compressed = Zstd.compress(thumb, Zstd.maxCompressionLevel());
            return Base64.getEncoder().encodeToString(compressed);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes map colors for frame 0 from a TileSnapshot. Returns null on failure. */
    private static byte[] extractFirstFrameColors(TileSnapshot snap) {
        try {
            if (snap.carpetEncoded && snap.carpetCompressedB64 != null) {
                byte[] compressed = Base64.getDecoder().decode(snap.carpetCompressedB64);
                long fs = Zstd.getFrameContentSize(compressed);
                if (fs <= 0) return null;
                byte[] full = Zstd.decompress(compressed, (int) fs);
                if (full.length < 16384) return null;
                int offset = full.length == 16384 ? 0
                        : PayloadManifest.fromBytes(full).headerSize;
                if (offset + 16384 > full.length) return null;
                byte[] colors = new byte[16384];
                System.arraycopy(full, offset, colors, 0, 16384);
                return colors;
            }
            // Legacy banner tiles
            if (snap.chunks != null && !snap.chunks.isEmpty()) {
                byte[] mapColors = MapBannerDecoder.reassemblePayload(new ArrayList<>(snap.chunks));
                if (mapColors.length >= 16384) return java.util.Arrays.copyOf(mapColors, 16384);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
