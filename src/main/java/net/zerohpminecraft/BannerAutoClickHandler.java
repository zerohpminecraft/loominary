package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapDecoration;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.zerohpminecraft.command.LoominaryCommand;
import net.zerohpminecraft.mixin.MapStateAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BannerAutoClickHandler {

    private static final String TAG = "[Loominary]";
    private static final int  CLICK_COOLDOWN_TICKS = 5;
    private static final double REACH_SQ            = 4.5 * 4.5;
    private static final int  SCAN_RANGE            = 5;
    private static final long DEBOUNCE_TICKS        = 60;  // ticks before retrying a failed click
    private static final int  REPAINT_INTERVAL      = 100; // ticks between defensive repaints

    private static boolean active        = false;
    private static int     cooldown      = 0;
    private static int     repaintCounter = 0;

    // chunk name → world-time of last click; expires after DEBOUNCE_TICKS to allow retry
    private static final Map<String, Long>       clickDebounce   = new HashMap<>();
    // chunk name → banner block position, for marker rendering
    private static final Map<String, BlockPos>   chunkToPos      = new HashMap<>();
    // block pos → marker status, consumed by the world renderer
    private static final Map<BlockPos, Status>   clickedPositions = new HashMap<>();
    // decoded image cached at session start — used by the repaint guard
    private static byte[] cachedMapColors = null;

    public enum Status { CLICKED, CONFIRMED }

    // ── Public API ─────────────────────────────────────────────────────────

    public static boolean isActive() { return active; }

    /**
     * Starts the auto-clicker. Returns false (with a player message) on error.
     */
    public static boolean start(MinecraftClient client) {
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) return false;
        if (client.player != null) {
            MapIdComponent mapId = heldMapId(client);
            if (mapId != null && MapBannerDecoder.isClaimed(mapId.id())) {
                client.player.sendMessage(
                        Text.literal(TAG + " That map is already fully decoded."), false);
                return false;
            }
        }
        active = true;
        cooldown = 0;
        repaintCounter = REPAINT_INTERVAL; // fire a repaint on the very first tick
        clickDebounce.clear();
        chunkToPos.clear();
        clickedPositions.clear();
        try {
            cachedMapColors = MapBannerDecoder.reassemblePayload(
                    new ArrayList<>(PayloadState.ACTIVE_CHUNKS));
        } catch (Exception ignored) {
            cachedMapColors = null;
        }
        return true;
    }

    public static void stop() {
        active = false;
        clickDebounce.clear();
        chunkToPos.clear();
        clickedPositions.clear();
        cachedMapColors = null;
    }

    /** Called by /loominary clear to remove any lingering markers. */
    public static void clearMarkers() {
        chunkToPos.clear();
        clickedPositions.clear();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BannerAutoClickHandler::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(BannerAutoClickHandler::renderMarkers);
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    private static void tick(MinecraftClient client) {
        if (!active || client.world == null || client.player == null) return;
        if (client.currentScreen != null) return;

        repaintCounter++;

        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) { stop(); return; }

        Hand mapHand = heldMapHand(client);
        if (mapHand == null) {
            if (cooldown <= 0) {
                client.inGameHud.setOverlayMessage(
                        Text.literal("§7" + TAG + " Hold your map to continue."), false);
                cooldown = CLICK_COOLDOWN_TICKS;
            } else {
                cooldown--;
            }
            return;
        }

        ItemStack mapStack = client.player.getStackInHand(mapHand);
        MapIdComponent mapId = mapStack.get(DataComponentTypes.MAP_ID);
        if (mapId == null) return;

        if (MapBannerDecoder.isClaimed(mapId.id())) {
            active = false;
            clickDebounce.clear();
            client.player.sendMessage(
                    Text.literal("§a" + TAG + " Map decoded — auto-click complete."), false);
            return;
        }

        MapState mapState = FilledMapItem.getMapState(mapId, client.world);
        if (mapState == null) return;

        // Defensive repaint: server sometimes sends blank map data on the first banner
        // interaction, overwriting a locally-painted preview. Repaint every REPAINT_INTERVAL
        // ticks if the user already called /loominary preview for this map.
        if (repaintCounter >= REPAINT_INTERVAL && cachedMapColors != null
                && LoominaryCommand.hasPreviewFor(mapId.id())) {
            repaintCounter = 0;
            MapBannerDecoder.paintMap(client, mapId, mapState, cachedMapColors);
        }

        Set<String> registered = registeredChunkNames(mapState);

        // Promote any now-confirmed markers.
        for (Map.Entry<String, BlockPos> e : chunkToPos.entrySet()) {
            if (registered.contains(e.getKey())) {
                clickedPositions.put(e.getValue(), Status.CONFIRMED);
            }
        }

        // Done when all chunks confirmed on the map.
        if (!PayloadState.ACTIVE_CHUNKS.isEmpty()
                && registered.containsAll(PayloadState.ACTIVE_CHUNKS)) {
            active = false;
            clickDebounce.clear();
            client.player.sendMessage(
                    Text.literal("§a" + TAG
                            + " All banners registered — place the map in an item frame to render."),
                    false);
            return;
        }

        if (cooldown > 0) { cooldown--; return; }

        long now = client.world.getTime();

        // Expire debounce: confirmed entries removed immediately; timed-out → retry.
        clickDebounce.entrySet().removeIf(e ->
                registered.contains(e.getKey()) || now - e.getValue() > DEBOUNCE_TICKS);

        // pending = unregistered and not within the debounce window
        Set<String> pending = new HashSet<>(PayloadState.ACTIVE_CHUNKS);
        pending.removeAll(registered);
        pending.removeAll(clickDebounce.keySet());

        if (pending.isEmpty()) {
            // Clicks sent but not yet confirmed — waiting for server.
            Set<String> inFlight = new HashSet<>(PayloadState.ACTIVE_CHUNKS);
            inFlight.removeAll(registered);
            client.inGameHud.setOverlayMessage(
                    Text.literal(String.format("§7" + TAG + " Waiting for server... (%d pending)",
                            inFlight.size())),
                    false);
            cooldown = CLICK_COOLDOWN_TICKS;
            return;
        }

        // Find the closest matching, unregistered banner within reach.
        BlockPos bestPos  = null;
        String   bestName = null;
        double   bestDistSq = Double.MAX_VALUE;

        BlockPos origin = client.player.getBlockPos();
        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
                for (int dz = -SCAN_RANGE; dz <= SCAN_RANGE; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    double distSq = client.player.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq > REACH_SQ || distSq >= bestDistSq) continue;

                    if (!(client.world.getBlockEntity(pos) instanceof BannerBlockEntity banner))
                        continue;
                    Text nameText = banner.getCustomName();
                    if (nameText == null) continue;
                    String name = nameText.getString();
                    if (!pending.contains(name)) continue;

                    bestPos    = pos;
                    bestName   = name;
                    bestDistSq = distSq;
                }
            }
        }

        if (bestPos == null) {
            client.inGameHud.setOverlayMessage(
                    Text.literal(String.format("§7" + TAG + " Move closer to banners (%d remaining)",
                            pending.size())),
                    false);
            cooldown = CLICK_COOLDOWN_TICKS;
            return;
        }

        Vec3d center = new Vec3d(bestPos.getX() + 0.5, bestPos.getY() + 0.5, bestPos.getZ() + 0.5);
        Vec3d diff   = client.player.getEyePos().subtract(center);
        Direction face = Direction.getFacing((float) diff.x, (float) diff.y, (float) diff.z);
        Vec3d hitPos = new Vec3d(
                bestPos.getX() + 0.5 + face.getOffsetX() * 0.5,
                bestPos.getY() + 0.5 + face.getOffsetY() * 0.5,
                bestPos.getZ() + 0.5 + face.getOffsetZ() * 0.5);

        BlockHitResult hit = new BlockHitResult(hitPos, face, bestPos, false);
        client.interactionManager.interactBlock(client.player, mapHand, hit);

        clickDebounce.put(bestName, now);
        chunkToPos.put(bestName, bestPos);
        clickedPositions.put(bestPos, Status.CLICKED);

        client.inGameHud.setOverlayMessage(
                Text.literal(String.format("§a" + TAG + " Clicking banners... (%d remaining)",
                        pending.size() - 1)),
                false);

        cooldown = CLICK_COOLDOWN_TICKS;
    }

    // ── World-space markers ────────────────────────────────────────────────

    private static void renderMarkers(WorldRenderContext context) {
        if (!active || clickedPositions.isEmpty()) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        MatrixStack  matrices = context.matrixStack();
        Vec3d        camPos   = context.camera().getPos();
        VertexConsumer lines  = consumers.getBuffer(RenderLayer.getLines());

        for (Map.Entry<BlockPos, Status> entry : clickedPositions.entrySet()) {
            BlockPos pos       = entry.getKey();
            boolean  confirmed = entry.getValue() == Status.CONFIRMED;

            double ox = pos.getX() - camPos.x;
            double oy = pos.getY() - camPos.y;
            double oz = pos.getZ() - camPos.z;

            // Green when server confirmed, yellow while waiting.
            float r = confirmed ? 0.2f : 1.0f;
            float g = confirmed ? 0.9f : 0.8f;
            float b = confirmed ? 0.2f : 0.0f;

            double s = 0.15; // half-width of the box
            VertexRendering.drawBox(matrices, lines,
                    ox + 0.5 - s, oy + 0.9,       oz + 0.5 - s,
                    ox + 0.5 + s, oy + 0.9 + s*2, oz + 0.5 + s,
                    r, g, b, 0.9f);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Hand heldMapHand(MinecraftClient client) {
        if (client.player.getMainHandStack().getItem() instanceof FilledMapItem) return Hand.MAIN_HAND;
        if (client.player.getOffHandStack().getItem() instanceof FilledMapItem) return Hand.OFF_HAND;
        return null;
    }

    private static MapIdComponent heldMapId(MinecraftClient client) {
        Hand hand = heldMapHand(client);
        if (hand == null) return null;
        return client.player.getStackInHand(hand).get(DataComponentTypes.MAP_ID);
    }

    private static Set<String> registeredChunkNames(MapState mapState) {
        Map<String, MapDecoration> decorations = ((MapStateAccessor) mapState).getDecorations();
        Set<String> names = new HashSet<>();
        for (MapDecoration dec : decorations.values()) {
            Text nameText = dec.name().orElse(null);
            if (nameText == null) continue;
            String s = nameText.getString();
            if (s.length() < 2) continue;
            try {
                Integer.parseInt(s.substring(0, 2), 16);
                names.add(s);
            } catch (NumberFormatException ignored) {}
        }
        return names;
    }
}
