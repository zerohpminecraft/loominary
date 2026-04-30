package net.zerohpminecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

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

    private static final String EXPORT_DIR = "loominary_exports";

    /**
     * Writes a litematic schematic for the given chunks. Returns the path
     * the file was written to.
     */
    public static Path exportTile(List<String> chunks, String baseName, String description)
            throws IOException {

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No chunks to export");
        }

        NbtCompound root = buildSchematic(chunks, baseName, description);

        Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve(EXPORT_DIR);
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
}