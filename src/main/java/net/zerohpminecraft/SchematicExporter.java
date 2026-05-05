package net.zerohpminecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.DyeColor;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Exports a Loominary tile (a list of banner-name chunks) as a Litematica
 * schematic file (.litematic) — a gzipped NBT containing a small region of
 * named white banners arranged in a grid.
 *
 * Layout: 16 columns wide, ceil(count/16) rows deep, all banners at y=0
 * facing south (rotation 8). Each banner has its custom name set in the
 * block entity NBT, so Litematica's ghost overlay can show you exactly
 * which named banner goes where.
 *
 * Limitations: Litematica's printer mode places banners from your inventory
 * by block type alone — it can't auto-match named banners to specific
 * positions. So this output is a layout guide for manual placement, not a
 * fully automated placer. Still saves you from miscounting or misplacing
 * banners across a 200-banner grid.
 */
public class SchematicExporter {

    // Litematica format constants
    private static final int LITEMATICA_VERSION = 6;
    private static final int LITEMATICA_SUBVERSION = 1;
    private static final int MC_DATA_VERSION_1_21_4 = 4189;

    // Layout constants
    private static final int GRID_WIDTH = 16;
    private static final String BANNER_BLOCK = "minecraft:white_banner";
    private static final String BANNER_ROTATION = "8"; // south-facing

    /** Litematica's default schematics directory (relative to game dir). */
    private static final String SCHEMATICS_DIR = "schematics";

    // ── Filename helpers ──────────────────────────────────────────────────

    /**
     * Returns a schematic filename base (no extension) derived from {@code title}
     * (slugified) or an auto-incremented {@code untitled_N} if title is null/blank.
     * Avoids collisions with existing files in the schematics directory.
     */
    public static String resolveSchematicName(String title) throws IOException {
        Path dir = schematicsDir();
        Files.createDirectories(dir);

        if (title != null && !title.isBlank()) {
            String slug = title.trim()
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (!slug.isEmpty()) return slug;
        }

        // untitled_N — find first unused
        for (int n = 1; n <= 9999; n++) {
            String name = "untitled_" + n;
            if (!Files.exists(dir.resolve(name + ".litematic"))) return name;
        }
        return "untitled";
    }

    private static Path schematicsDir() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve(SCHEMATICS_DIR);
    }

    /**
     * Writes a litematic schematic for the given banner chunks. Returns the path
     * the file was written to.
     */
    public static Path exportTile(List<String> chunks, String baseName, String description)
            throws IOException {

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No chunks to export");
        }

        NbtCompound root = buildSchematic(chunks, baseName, description);

        Path dir = schematicsDir();
        Files.createDirectories(dir);
        Path path = dir.resolve(baseName + ".litematic");

        // Litematic files are gzipped NBT
        try (OutputStream fos = Files.newOutputStream(path);
             GZIPOutputStream gz = new GZIPOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(gz)) {
            NbtIo.write(root, dos);
        }

        return path;
    }

    private static NbtCompound buildSchematic(List<String> chunks, String name, String description) {
        int count = chunks.size();
        int gridW = GRID_WIDTH;
        int gridH = (count + gridW - 1) / gridW;
        int totalSlots = gridW * gridH;

        NbtCompound root = new NbtCompound();
        root.putInt("Version", LITEMATICA_VERSION);
        root.putInt("SubVersion", LITEMATICA_SUBVERSION);
        root.putInt("MinecraftDataVersion", MC_DATA_VERSION_1_21_4);

        // ── Metadata ──────────────────────────────────────────────────
        NbtCompound metadata = new NbtCompound();
        metadata.putString("Name", name);
        metadata.putString("Author", "Loominary");
        metadata.putString("Description",
                description != null ? description : ("Banner placement for " + name));
        metadata.putInt("RegionCount", 1);
        long now = System.currentTimeMillis();
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);
        metadata.putInt("TotalBlocks", count);
        metadata.putInt("TotalVolume", totalSlots);

        NbtCompound enclosingSize = new NbtCompound();
        enclosingSize.putInt("x", gridW);
        enclosingSize.putInt("y", 1);
        enclosingSize.putInt("z", gridH);
        metadata.put("EnclosingSize", enclosingSize);

        root.put("Metadata", metadata);

        // ── Single region ─────────────────────────────────────────────
        NbtCompound region = new NbtCompound();

        NbtCompound position = new NbtCompound();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);

        NbtCompound size = new NbtCompound();
        size.putInt("x", gridW);
        size.putInt("y", 1);
        size.putInt("z", gridH);
        region.put("Size", size);

        // Block state palette: index 0 = air, index 1 = white_banner[rotation=8]
        NbtList palette = new NbtList();

        NbtCompound airState = new NbtCompound();
        airState.putString("Name", "minecraft:air");
        palette.add(airState);

        NbtCompound bannerState = new NbtCompound();
        bannerState.putString("Name", BANNER_BLOCK);
        NbtCompound props = new NbtCompound();
        props.putString("rotation", BANNER_ROTATION);
        bannerState.put("Properties", props);
        palette.add(bannerState);

        region.put("BlockStatePalette", palette);

        // Bit-packed block indices: 1 for the first `count` slots, 0 (air) after
        long[] blockStates = packBlockStates(count, totalSlots, 2);
        region.putLongArray("BlockStates", blockStates);

        // Block entities — one per banner with its custom name
        NbtList tileEntities = new NbtList();
        for (int i = 0; i < count; i++) {
            int x = i % gridW;
            int z = i / gridW;

            NbtCompound be = new NbtCompound();
            be.putInt("x", x);
            be.putInt("y", 0);
            be.putInt("z", z);
            be.putString("id", "minecraft:banner");
            // Custom name as a JSON text component string.
            // Banner-name chunks are hex digits + base64 — no chars need escaping.
            be.putString("CustomName", "{\"text\":\"" + chunks.get(i) + "\"}");
            tileEntities.add(be);
        }
        region.put("TileEntities", tileEntities);

        // Litematica expects these fields even if empty
        region.put("Entities", new NbtList());
        region.put("PendingBlockTicks", new NbtList());
        region.put("PendingFluidTicks", new NbtList());

        NbtCompound regions = new NbtCompound();
        regions.put(name, region);
        root.put("Regions", regions);

        return root;
    }

    /**
     * Packs block-state indices into a long array, using the post-1.16
     * Litematica/Minecraft convention where entries do NOT span across long
     * boundaries (padding bits at the top of each long are ignored).
     *
     * @param bannerCount  number of banner slots (filled with palette index 1)
     * @param totalBlocks  total slots in the region (rest are air = index 0)
     * @param bitsPerEntry minimum 2; with a 2-entry palette we use exactly 2
     */
    private static long[] packBlockStates(int bannerCount, int totalBlocks, int bitsPerEntry) {
        int entriesPerLong = 64 / bitsPerEntry;
        int numLongs = (totalBlocks + entriesPerLong - 1) / entriesPerLong;
        long[] longs = new long[numLongs];
        long mask = (1L << bitsPerEntry) - 1;

        for (int i = 0; i < totalBlocks; i++) {
            long value = (i < bannerCount) ? 1L : 0L;
            int longIndex = i / entriesPerLong;
            int bitIndex = (i % entriesPerLong) * bitsPerEntry;
            longs[longIndex] |= (value & mask) << bitIndex;
        }
        return longs;
    }

    /**
     * Packs block indices using Litematica's spanning format (LitematicaBitArray):
     * entries are stored at consecutive bit positions and MAY cross long boundaries.
     * This differs from Minecraft 1.16+'s non-spanning format where entries are
     * padded to long boundaries.
     */
    private static long[] packBlockIndices(int[] indices, int bitsPerEntry) {
        long totalBits = (long) indices.length * bitsPerEntry;
        int numLongs = (int) ((totalBits + 63) / 64);
        long[] longs = new long[numLongs];
        long mask = (1L << bitsPerEntry) - 1;

        for (int i = 0; i < indices.length; i++) {
            long bitIndex = (long) i * bitsPerEntry;
            int li = (int) (bitIndex >> 6);         // which long
            int bitOffset = (int) (bitIndex & 63);  // bit position within that long
            long value = (long) indices[i] & mask;

            longs[li] |= value << bitOffset;

            int bitsInFirstLong = 64 - bitOffset;
            if (bitsInFirstLong < bitsPerEntry) {
                // Entry spans two longs — store the high bits in the next long
                longs[li + 1] |= value >>> bitsInFirstLong;
            }
        }
        return longs;
    }

    // ── Carpet schematic ──────────────────────────────────────────────────

    /**
     * Exports a carpet-hybrid schematic: a 128×{@code carpetRows}×1 region of
     * pure carpet blocks, no guard row. Place on any flat surface within the
     * map area; the platform should extend north of the map boundary so that
     * all rows render at shade NORMAL.
     *
     * @param nibbles     16384-element array; nibble i selects the carpet color
     *                    for map pixel i; only 0..{@code carpetBytes}*2−1 carry data
     * @param carpetBytes number of bytes encoded in the carpet channel (≤ 8192)
     * @param baseName    schematic file base name (no extension)
     * @return path of the written .litematic file
     */
    public static Path exportCarpetTile(byte[] nibbles, int carpetBytes, String baseName)
            throws IOException {

        int carpetRows = (carpetBytes * 2 + 127) / 128;
        int totalBlocks = 128 * carpetRows;

        // Palette: 0=air, 1..16=carpets in DyeColor.ordinal() order
        NbtList palette = new NbtList();
        NbtCompound airEntry = new NbtCompound();
        airEntry.putString("Name", "minecraft:air");
        palette.add(airEntry);

        for (DyeColor color : DyeColor.values()) {
            NbtCompound carpetEntry = new NbtCompound();
            carpetEntry.putString("Name", "minecraft:" + color.getName() + "_carpet");
            palette.add(carpetEntry);
        }
        int paletteSize = palette.size(); // 17

        // Block index array: z varies slowest, x varies fastest
        int[] blockIndices = new int[totalBlocks];
        int nibblesUsed = carpetBytes * 2;
        for (int z = 0; z < carpetRows; z++) {
            for (int x = 0; x < 128; x++) {
                int nibbleIdx = z * 128 + x;
                if (nibbleIdx < nibblesUsed) {
                    blockIndices[z * 128 + x] = 1 + nibbles[nibbleIdx]; // carpet palette starts at 1
                }
                // else stays 0 = air (last partial row)
            }
        }

        int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1)); // 5 for 17-entry palette
        long[] blockStates = packBlockIndices(blockIndices, bitsPerEntry);

        // Build NBT root
        NbtCompound root = new NbtCompound();
        root.putInt("Version", LITEMATICA_VERSION);
        root.putInt("SubVersion", LITEMATICA_SUBVERSION);
        root.putInt("MinecraftDataVersion", MC_DATA_VERSION_1_21_4);

        NbtCompound metadata = new NbtCompound();
        metadata.putString("Name", baseName);
        metadata.putString("Author", "Loominary");
        metadata.putString("Description",
                "Loominary carpet data channel — " + carpetBytes + " compressed bytes, "
                + carpetRows + " rows.");
        metadata.putInt("RegionCount", 1);
        long now = System.currentTimeMillis();
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);
        metadata.putInt("TotalBlocks", nibblesUsed);
        metadata.putInt("TotalVolume", totalBlocks);

        NbtCompound enclosingSize = new NbtCompound();
        enclosingSize.putInt("x", 128);
        enclosingSize.putInt("y", 1);
        enclosingSize.putInt("z", carpetRows);
        metadata.put("EnclosingSize", enclosingSize);
        root.put("Metadata", metadata);

        NbtCompound region = new NbtCompound();

        NbtCompound position = new NbtCompound();
        position.putInt("x", 0); position.putInt("y", 0); position.putInt("z", 0);
        region.put("Position", position);

        NbtCompound size = new NbtCompound();
        size.putInt("x", 128); size.putInt("y", 1); size.putInt("z", carpetRows);
        region.put("Size", size);

        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", blockStates);
        region.put("TileEntities", new NbtList());
        region.put("Entities", new NbtList());
        region.put("PendingBlockTicks", new NbtList());
        region.put("PendingFluidTicks", new NbtList());

        NbtCompound regions = new NbtCompound();
        regions.put(baseName, region);
        root.put("Regions", regions);

        // Write to schematics directory
        Path dir = schematicsDir();
        Files.createDirectories(dir);
        Path path = dir.resolve(baseName + ".litematic");

        try (OutputStream fos = Files.newOutputStream(path);
             GZIPOutputStream gz = new GZIPOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(gz)) {
            NbtIo.write(root, dos);
        }

        return path;
    }
}