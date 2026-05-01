package net.zerohpminecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /** Optional title embedded in every tile's manifest; null if not set. */
    public static String currentTitle = null;

    // ── Per-tile data ──────────────────────────────────────────────────

    public static final List<TileData> tiles = new ArrayList<>();

    public static class TileData {
        public List<String> chunks = new ArrayList<>();
        public int currentIndex = 0;
    }

    private static class Snapshot {
        String sourceFilename;
        int columns;
        int rows;
        int activeTileIndex;
        List<TileData> tiles;
        boolean allShades;
        String title;
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
            snap.title = currentTitle;
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
            currentTitle = snap.title;

            tiles.clear();
            tiles.addAll(snap.tiles);

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
        currentTitle = null;
        tiles.clear();
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