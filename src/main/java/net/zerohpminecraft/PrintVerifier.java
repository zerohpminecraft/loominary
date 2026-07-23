package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that the carpet floor printed by {@link AutoPrintHandler} would, <em>if captured</em>,
 * reproduce the Litematica schematic exactly — without spending a scarce empty map.
 *
 * <p>Minecraft's top-down map scan reads the highest non-transparent block in each column, and the
 * map's shade is the north-south height differential of those blocks. Exact capture therefore
 * reduces to one structural check per column: the highest non-{@link MapColor#CLEAR CLEAR} block in
 * the real world must be the same carpet, at the same Y, that the schematic wants. That single
 * invariant guarantees correct colour (the scanned block matches), correct shade (every column height
 * matches ⇒ every differential matches), and a clear air column above the carpet (nothing
 * non-transparent obstructs the scan — glass and the like are {@code CLEAR} and pass for free).
 *
 * <p>Findings land in three buckets, highlighted in-world with wireframe boxes like the chest markers
 * during a fill/catalogue:
 * <ul>
 *   <li><b>Errant</b> (red) — a carpet stacked above the intended top, or a stray carpet in a hole of
 *       the art. This is the only bucket {@link AutoPrintHandler} auto-breaks (in real time).</li>
 *   <li><b>Obstruction</b> (orange) — a non-carpet, non-transparent block above a carpet.
 *       <b>Reported only, never broken</b>: it may be an intentional structure.</li>
 *   <li><b>Missing/wrong</b> (magenta) — the intended carpet is absent, a different colour, or the
 *       top of an incomplete stack sits below where it should.</li>
 * </ul>
 *
 * <p>The footprint (whole schematic near the player, multi-tile compositions included) is scanned
 * one bounded batch of columns per tick so the full-height column checks never freeze the client.
 */
public final class PrintVerifier {

    private static final String TAG = "[Loominary]";

    /** ±blocks around the player to search the ghost world for the schematic's carpet footprint. */
    private static final int SCAN_RADIUS = 256;
    /** Columns fully verified per tick — bounds the per-tick block-read cost. */
    private static final int COLUMNS_PER_TICK = 256;
    /** Example coordinates listed per bucket in the chat summary. */
    private static final int SAMPLE_COUNT = 3;

    enum Finding { OK, ERRANT, OBSTRUCTION, MISSING }

    private static boolean active = false;     // a scan is in progress
    private static boolean hasResult = false;  // findings are ready to render

    private static World schematic;
    private static int minX, minZ, maxX, maxZ, floorY, worldTop;
    private static int curX, curZ;             // scan cursor

    private static final List<BlockPos> errant = new ArrayList<>();
    private static final List<BlockPos> obstruction = new ArrayList<>();
    private static final List<BlockPos> missing = new ArrayList<>();
    private static int okCount;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PrintVerifier::onTick);
        WorldRenderEvents.AFTER_ENTITIES.register(PrintVerifier::renderFindings);
    }

    public static boolean isActive() { return active; }

    /** Begin a fresh verify pass over the schematic footprint near the player. */
    public static void start(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        schematic = CarpetBalanceHandler.schematicWorld();
        if (schematic == null) {
            feedback(client, "§e" + TAG + " Verify: no Litematica schematic loaded.");
            return;
        }
        BlockPos me = client.player.getBlockPos();
        floorY = me.getY();
        int[] b = carpetBounds(me, floorY);
        if (b == null) {
            feedback(client, "§e" + TAG + " Verify: no schematic carpets found within "
                    + SCAN_RADIUS + " blocks.");
            return;
        }
        minX = b[0]; minZ = b[1]; maxX = b[2]; maxZ = b[3];
        worldTop = client.world.getBottomY() + client.world.getHeight();   // build-height limit
        curX = minX; curZ = minZ;
        errant.clear(); obstruction.clear(); missing.clear(); okCount = 0;
        active = true;
        hasResult = false;
        feedback(client, "§b" + TAG + " Verify: scanning " + (maxX - minX + 1) + "×"
                + (maxZ - minZ + 1) + " columns (no empty map used)…");
    }

    /** Hide any highlighted findings and cancel a scan in progress. */
    public static void clear() {
        active = false;
        hasResult = false;
        errant.clear();
        obstruction.clear();
        missing.clear();
    }

    // ── Scan ────────────────────────────────────────────────────────────────────

    private static void onTick(MinecraftClient client) {
        if (!active) return;
        if (client.player == null || client.world == null) { active = false; return; }
        int budget = COLUMNS_PER_TICK;
        while (budget-- > 0) {
            classifyColumn(client.world, curX, curZ);
            if (++curZ > maxZ) {
                curZ = minZ;
                if (++curX > maxX) { finish(client); return; }
            }
        }
    }

    /** Bounding rectangle of the schematic's carpets at {@code floorY}, or null if none nearby. */
    private static int[] carpetBounds(BlockPos center, int floorY) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        int nMinX = Integer.MAX_VALUE, nMaxX = Integer.MIN_VALUE;
        int nMinZ = Integer.MAX_VALUE, nMaxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (int x = center.getX() - SCAN_RADIUS; x <= center.getX() + SCAN_RADIUS; x++) {
            for (int z = center.getZ() - SCAN_RADIUS; z <= center.getZ() + SCAN_RADIUS; z++) {
                p.set(x, floorY, z);
                try {
                    if (CarpetBalanceHandler.isCarpet(schematic.getBlockState(p).getBlock().asItem())) {
                        if (x < nMinX) nMinX = x;
                        if (x > nMaxX) nMaxX = x;
                        if (z < nMinZ) nMinZ = z;
                        if (z > nMaxZ) nMaxZ = z;
                        found = true;
                    }
                } catch (Exception ignored) {
                    // unloaded schematic chunk — skip
                }
            }
        }
        return found ? new int[]{nMinX, nMinZ, nMaxX, nMaxZ} : null;
    }

    private static void classifyColumn(World world, int x, int z) {
        // Intended: top carpet the schematic wants in this column.
        int intendedTop = CarpetBalanceHandler.schematicTopCarpetY(schematic, x, z, floorY);
        int intendedColor = -1;
        if (intendedTop != Integer.MIN_VALUE) {
            intendedColor = rawIdAt(schematic, x, intendedTop, z);
        }

        // Real: highest non-CLEAR block at or above the floor — exactly what the map scan would read.
        BlockPos.Mutable p = new BlockPos.Mutable();
        int realTop = Integer.MIN_VALUE, realColor = -1;
        boolean realCarpet = false;
        for (int y = worldTop - 1; y >= floorY; y--) {
            p.set(x, y, z);
            BlockState st = world.getBlockState(p);
            if (st.getMapColor(world, p) == MapColor.CLEAR) continue;   // air, glass, … — map-transparent
            realTop = y;
            Item it = st.getBlock().asItem();
            realCarpet = CarpetBalanceHandler.isCarpet(it);
            realColor = Registries.ITEM.getRawId(it);
            break;
        }

        switch (classify(intendedTop, intendedColor, realTop, realColor, realCarpet, floorY)) {
            case OK -> okCount++;
            case ERRANT -> errant.add(new BlockPos(x, realTop, z));
            case OBSTRUCTION -> obstruction.add(new BlockPos(x, realTop, z));
            case MISSING -> missing.add(new BlockPos(x,
                    intendedTop == Integer.MIN_VALUE ? floorY : intendedTop, z));
        }
    }

    /**
     * Pure classification of one column (Minecraft-free, unit-tested). Colours are item raw ids;
     * {@code -1} means "none". {@code intendedColor < 0} ⇒ the schematic wants no carpet here.
     */
    static Finding classify(int intendedTop, int intendedColor,
                            int realTop, int realColor, boolean realIsCarpet, int floorY) {
        if (intendedColor < 0) {
            // Nothing intended: only a stray carpet at/above the floor is a problem (errant).
            return (realIsCarpet && realTop >= floorY) ? Finding.ERRANT : Finding.OK;
        }
        if (realTop == Integer.MIN_VALUE) return Finding.MISSING;      // carpet never placed
        if (realTop > intendedTop) {
            return realIsCarpet ? Finding.ERRANT : Finding.OBSTRUCTION; // carpet-on-carpet vs block above
        }
        if (realTop == intendedTop && realColor == intendedColor) return Finding.OK;
        return Finding.MISSING;                                         // wrong colour / short stack
    }

    private static int rawIdAt(World src, int x, int y, int z) {
        return Registries.ITEM.getRawId(src.getBlockState(new BlockPos(x, y, z)).getBlock().asItem());
    }

    private static void finish(MinecraftClient client) {
        active = false;
        hasResult = true;
        int e = errant.size(), o = obstruction.size(), m = missing.size();
        if (e == 0 && o == 0 && m == 0) {
            feedback(client, "§a" + TAG + " Verify: clean — all " + okCount
                    + " columns would capture exactly. No empty map needed.");
            return;
        }
        feedback(client, "§e" + TAG + " Verify found issues (no empty map used):");
        if (e > 0) feedback(client, "  §cErrant carpets above surface: §f" + e + samples(errant));
        if (o > 0) feedback(client, "  §6Obstructions above carpets: §f" + o + samples(obstruction));
        if (m > 0) feedback(client, "  §dMissing / wrong carpets: §f" + m + samples(missing));
        feedback(client, "§7  Boxes: §cred§7=errant §6orange§7=obstruction §dmagenta§7=missing/wrong. "
                + "§7Hide with §f/loominary walk verify clear§7.");
    }

    private static String samples(List<BlockPos> list) {
        StringBuilder sb = new StringBuilder(" §7(e.g. ");
        int n = Math.min(SAMPLE_COUNT, list.size());
        for (int i = 0; i < n; i++) {
            BlockPos p = list.get(i);
            if (i > 0) sb.append(", ");
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        if (list.size() > n) sb.append(", …");
        return sb.append(')').toString();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private static void renderFindings(WorldRenderContext context) {
        if (!hasResult) return;
        if (errant.isEmpty() && obstruction.isEmpty() && missing.isEmpty()) return;
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;
        MatrixStack matrices = context.matrixStack();
        Vec3d cam = context.camera().getPos();
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        drawBoxes(matrices, lines, cam, errant, 1.0f, 0.15f, 0.15f);       // red
        drawBoxes(matrices, lines, cam, obstruction, 1.0f, 0.6f, 0.1f);    // orange
        drawBoxes(matrices, lines, cam, missing, 0.85f, 0.2f, 0.9f);       // magenta
    }

    private static void drawBoxes(MatrixStack matrices, VertexConsumer lines, Vec3d cam,
                                  List<BlockPos> positions, float r, float g, float b) {
        for (BlockPos pos : positions) {
            double ox = pos.getX() - cam.x, oy = pos.getY() - cam.y, oz = pos.getZ() - cam.z;
            VertexRendering.drawBox(matrices, lines,
                    ox + 0.02, oy + 0.02, oz + 0.02,
                    ox + 1 - 0.02, oy + 1 - 0.02, oz + 1 - 0.02,
                    r, g, b, 0.9f);
        }
    }

    private static void feedback(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }

    private PrintVerifier() {}
}
