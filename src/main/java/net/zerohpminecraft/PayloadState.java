package net.zerohpminecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the multi-tile banner-rename batch to disk.
 *
 * The "active tile" is the one currently loaded into {@link #ACTIVE_CHUNKS} and
 * {@link #activeChunkIndex}. Switching tiles saves the active tile's progress
 * first, then loads the new tile.
 */
public class PayloadState {

    private static final String TAG = "[Loominary]";
    private static final String FILE_NAME = "loominary_state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Active working set ─────────────────────────────────────────────
    // The chunks of whichever tile is currently active. The anvil handler
    // and command code read/write these directly.

    public static final List<String> ACTIVE_CHUNKS = new ArrayList<>();
    public static int activeChunkIndex = 0;

    // ── Grid metadata ──────────────────────────────────────────────────

    public static String currentSourceFilename = null;
    public static int gridColumns = 1;
    public static int gridRows = 1;
    public static int activeTileIndex = 0;

    // ── Manifest metadata ──────────────────────────────────────────────

    /** Whether the batch was encoded with allShades (FLAG_ALL_SHADES). */
    public static boolean allShades = false;
    /** Whether the batch was (last) encoded with Floyd-Steinberg dithering. */
    public static boolean dither = false;
    /** Optional title embedded in every tile's manifest; null if not set. */
    public static String currentTitle = null;

    // ── Per-tile data ──────────────────────────────────────────────────

    public static final List<TileData> tiles = new ArrayList<>();

    // ── Banner-rename whitelist ────────────────────────────────────────
    // Names of existing named banners (loose or inside bundles) that the
    // renamer is allowed to consume as raw material — extract them, run them
    // through the anvil with the current chunk name, and bundle the result.
    // Entries are removed as the renamer consumes them so that the renamer's
    // OWN OUTPUT (which may happen to carry a name that was once whitelisted)
    // is not later mistaken for further raw material.
    public static final Set<String> whitelistedBannerNames = new HashSet<>();

    public static class TileData {
        public List<String> chunks = new ArrayList<>();
        public int currentIndex = 0;
        public int nonce = 0;          // 0 = v1 encoding; non-zero = v2+ with this nonce
        public int frameCount = 1;     // 1 = static; >1 = animated (FLAG_ANIMATED set)
        public List<Integer> frameDelays = null; // null → single global 100ms default
        /**
         * Maps each editor frame index to the original GIF frame index it came from.
         * Null means a 1-to-1 mapping (never had stride/skip applied, or not a GIF).
         * Updated by stride/skip so requantize always targets the right source frame.
         */
        public List<Integer> frameSourceIndices = null;
        /** True when this tile uses the carpet-hybrid encoding (carpet platform + optional overflow banners). */
        public boolean carpetEncoded = false;
        /**
         * Full zstd-compressed payload as base64, present only when {@code carpetEncoded == true}.
         * Stored so the schematic can be re-exported and map-color preview works without the
         * physical carpet platform being scanned.  Typically 2–14 KB as base64 per tile.
         */
        public String carpetCompressedB64 = null;
        /** True when this tile participates in mux pooling (FLAG_MUX). */
        public boolean muxed = false;
        /** True when this tile is a mux receiver (its overflow payload is hosted by donor tiles). */
        public boolean muxReceiver = false;
        /**
         * Physical carpet cargo bytes (base64) after mux: ownSeg for receivers,
         * ownFrame+guestBytes for donors. Null when not muxed or mux was cleared.
         * {@code carpetCompressedB64} always holds the full logical payload regardless.
         */
        public String muxCargoB64 = null;
    }

    private static class Snapshot {
        String sourceFilename;
        int columns;
        int rows;
        int activeTileIndex;
        List<TileData> tiles;
        boolean allShades;
        boolean dither;
        String title;
        List<String> whitelist;
    }

    public static int totalTiles() {
        return gridColumns * gridRows;
    }

    public static int tileCol(int tileIndex) {
        return tileIndex % gridColumns;
    }

    public static int tileRow(int tileIndex) {
        return tileIndex / gridColumns;
    }

    public static String tileLabel(int tileIndex) {
        return String.format("tile %d (col %d, row %d)",
                tileIndex, tileCol(tileIndex), tileRow(tileIndex));
    }

    // ── Active tile sync ───────────────────────────────────────────────

    public static void syncToActiveTile() {
        if (activeTileIndex < 0 || activeTileIndex >= tiles.size()) return;
        TileData tile = tiles.get(activeTileIndex);
        tile.chunks.clear();
        tile.chunks.addAll(ACTIVE_CHUNKS);
        tile.currentIndex = activeChunkIndex;
    }

    public static void syncFromActiveTile() {
        if (activeTileIndex < 0 || activeTileIndex >= tiles.size()) return;
        TileData tile = tiles.get(activeTileIndex);
        ACTIVE_CHUNKS.clear();
        ACTIVE_CHUNKS.addAll(tile.chunks);
        activeChunkIndex = tile.currentIndex;
    }

    public static void switchTile(int newIndex) {
        syncToActiveTile();
        activeTileIndex = newIndex;
        syncFromActiveTile();
    }

    public static int findNextIncompleteTile() {
        for (int i = 0; i < tiles.size(); i++) {
            TileData tile = tiles.get(i);
            if (tile.currentIndex < tile.chunks.size()) {
                return i;
            }
        }
        return -1;
    }

    // ── Persistence ────────────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Serializes current state to an arbitrary path (used by the named-save system). */
    public static void saveToFile(Path path) {
        syncToActiveTile();
        try {
            Files.createDirectories(path.getParent());
            Snapshot snap = new Snapshot();
            snap.sourceFilename = currentSourceFilename;
            snap.columns = gridColumns;
            snap.rows = gridRows;
            snap.activeTileIndex = activeTileIndex;
            snap.tiles = new ArrayList<>(tiles);
            snap.allShades = allShades;
            snap.dither = dither;
            snap.title = currentTitle;
            snap.whitelist = new ArrayList<>(whitelistedBannerNames);
            Files.writeString(path, GSON.toJson(snap));
        } catch (IOException e) {
            System.err.println(TAG + " Failed to save to " + path.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Loads state from an arbitrary path, replacing the current state entirely.
     * @return a human-readable summary for feedback ("myimage.png (2×1 grid, 2 tiles)")
     * @throws IOException if the file cannot be read or is corrupt
     */
    public static String loadFromFile(Path path) throws IOException {
        String json = Files.readString(path);
        Snapshot snap = GSON.fromJson(json, Snapshot.class);
        if (snap == null || snap.tiles == null || snap.tiles.isEmpty())
            throw new IOException("Save file empty or corrupt.");
        currentSourceFilename = snap.sourceFilename;
        gridColumns = snap.columns > 0 ? snap.columns : 1;
        gridRows = snap.rows > 0 ? snap.rows : 1;
        activeTileIndex = Math.max(0, Math.min(snap.activeTileIndex, snap.tiles.size() - 1));
        allShades = snap.allShades;
        dither = snap.dither;
        currentTitle = snap.title;
        tiles.clear();
        tiles.addAll(snap.tiles);
        whitelistedBannerNames.clear();
        if (snap.whitelist != null) whitelistedBannerNames.addAll(snap.whitelist);
        syncFromActiveTile();
        String src = snap.sourceFilename != null ? snap.sourceFilename : "<unknown>";
        return src + " (" + gridColumns + "×" + gridRows + " grid, "
                + tiles.size() + " tile" + (tiles.size() == 1 ? "" : "s") + ")";
    }

    public static void save() {
        syncToActiveTile();
        try {
            Snapshot snap = new Snapshot();
            snap.sourceFilename = currentSourceFilename;
            snap.columns = gridColumns;
            snap.rows = gridRows;
            snap.activeTileIndex = activeTileIndex;
            snap.tiles = new ArrayList<>(tiles);
            snap.allShades = allShades;
            snap.dither = dither;
            snap.title = currentTitle;
            snap.whitelist = new ArrayList<>(whitelistedBannerNames);
            Files.writeString(stateFile(), GSON.toJson(snap));
        } catch (IOException e) {
            System.err.println(TAG + " Failed to save state: " + e.getMessage());
        }
    }

    public static void load() {
        Path path = stateFile();
        if (!Files.exists(path)) {
            System.out.println(TAG + " No saved state found — starting fresh.");
            return;
        }

        try {
            String json = Files.readString(path);
            Snapshot snap = GSON.fromJson(json, Snapshot.class);

            if (snap == null || snap.tiles == null || snap.tiles.isEmpty()) {
                System.out.println(TAG + " State file empty or corrupt — starting fresh.");
                return;
            }

            currentSourceFilename = snap.sourceFilename;
            gridColumns = snap.columns > 0 ? snap.columns : 1;
            gridRows = snap.rows > 0 ? snap.rows : 1;
            activeTileIndex = snap.activeTileIndex;
            allShades = snap.allShades;
            dither = snap.dither;
            currentTitle = snap.title;

            tiles.clear();
            tiles.addAll(snap.tiles);

            whitelistedBannerNames.clear();
            if (snap.whitelist != null) whitelistedBannerNames.addAll(snap.whitelist);

            syncFromActiveTile();

            String fname = snap.sourceFilename == null ? "<unknown>" : snap.sourceFilename;
            int totalDone = 0;
            int totalChunks = 0;
            for (TileData t : tiles) {
                totalDone += t.currentIndex;
                totalChunks += t.chunks.size();
            }

            System.out.println(TAG + " Resumed batch: " + fname
                    + " (" + gridColumns + "x" + gridRows + " grid, "
                    + totalDone + "/" + totalChunks + " banners total, "
                    + "active " + tileLabel(activeTileIndex) + ")");
        } catch (Exception e) {
            System.err.println(TAG + " Failed to load state: " + e.getMessage());
        }
    }

    public static void clearMemory() {
        currentSourceFilename = null;
        gridColumns = 1;
        gridRows = 1;
        activeTileIndex = 0;
        allShades = false;
        dither = false;
        currentTitle = null;
        tiles.clear();
        whitelistedBannerNames.clear();
        ACTIVE_CHUNKS.clear();
        activeChunkIndex = 0;
    }

    public static void clearDisk() {
        try {
            Files.deleteIfExists(stateFile());
        } catch (IOException e) {
            System.err.println(TAG + " Failed to delete state file: " + e.getMessage());
        }
    }

    public static void clear() {
        clearDisk();
        clearMemory();
    }
}