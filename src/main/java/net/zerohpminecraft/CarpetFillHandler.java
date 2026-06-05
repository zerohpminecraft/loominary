package net.zerohpminecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tops up the carpet-balance layout from nearby chests.
 *
 * <p>On {@link #start}, it recomputes the same proportional goal as
 * {@link CarpetBalanceHandler} (so each color owns a number of inventory slots
 * proportional to its Litematica material count), then walks the chests within
 * reach one at a time: open → take exactly the carpet needed to fill the goal
 * slots up to a full 64 (returning any excess) → close → pause → next. It stops
 * when every goal slot is full or no unopened chest remains in range.
 *
 * <p>Block interaction mirrors {@link BannerAutoClickHandler}; the open-chest
 * slot moves mirror {@link CarpetBalanceHandler}'s cursor-carry mechanic. Goal
 * slots are tracked as PlayerInventory indices (0–35 = main + hotbar) so they're
 * valid in both the player and chest screen handlers.
 */
public class CarpetFillHandler {

    private static final String TAG = "[Loominary]";

    private static final double REACH_SQ           = 4.5 * 4.5; // how close to actually open a chest
    private static final double WALK_LIMIT_SQ      = 16.0 * 16.0; // never auto-guide farther than this
    private static final int    PATH_RADIUS        = 96;   // A* search box half-width for chest approach
    private static final int    LAST_INV_SLOT      = 35;   // 0–8 hotbar, 9–35 main; 36+ armor/offhand
    private static final int    MAX_STACK          = CarpetBalanceHandler.MAX_STACK;

    private static final int    ACTION_COOLDOWN_TICKS  = 2;    // between clicks (was 4) — fill cadence
    private static final int    SETTLE_TICKS           = 5;    // initial wait after open before reading (was 10)
    private static final int    SYNC_WAIT_MAX          = 60;   // keep waiting (up to this) while the chest reads empty
    private static final int    SYNC_STABLE_TICKS      = 3;    // record only after the carpet total holds steady this long
    private static final int    OPEN_TIMEOUT_TICKS     = 60;
    private static final int    OPEN_RETRY_INTERVAL    = 8;    // re-send interact this often while waiting (was 15)
    private static final int    MAX_OPEN_ATTEMPTS      = 3;
    private static final int    APPROACH_TIMEOUT_TICKS = 1200;  // give up guiding to one chest after ~60s
    private static final int    BETWEEN_CHESTS_TICKS   = 5;    // pause between chests (was 10)
    private static final int    WATCHDOG_TICKS         = 36000;

    private static final int LEFT_CLICK = 0;

    private enum State { BALANCE_WAIT, SCAN, APPROACH, WAIT_OPEN, COOLDOWN }

    /** When driven by {@link AutoPrintHandler}: walk the player to chests ourselves and
     *  range across the whole storage room, instead of only guiding within {@link #WALK_LIMIT_SQ}. */
    private static final double AUTO_WALK_LIMIT_SQ = 128.0 * 128.0;

    /** Catalogue scan reach: how far around the player we look for chests to open + record. */
    private static final int CATALOGUE_RADIUS_H = 48;
    private static final int CATALOGUE_RADIUS_V = 6;

    // ── Run state ──────────────────────────────────────────────────────────
    private static boolean active = false;
    private static boolean autonomous = false;
    /** Catalogue mode: visit + record every chest in range (no grabbing), to build fresh memory. */
    private static boolean cataloguing = false;
    /** When set (autonomous print), the exact load to gather, instead of recomputing local demand. */
    private static Map<Item, Integer> forcedDemand = null;
    private static final List<BlockPos> catalogueQueue = new ArrayList<>();
    private static int catalogued = 0;
    private static State   state  = State.SCAN;
    private static int     cooldown   = 0;
    private static int     openWait   = 0;
    private static int     syncWait   = 0;
    private static int     lastSyncCarpet = -1;   // container carpet total last tick (sync-stability)
    private static int     syncStableTicks = 0;
    private static int     openAttempts = 0;
    private static int     totalTicks = 0;
    private static int     approachTimer = 0;
    private static double  lastApproachDistSq = Double.MAX_VALUE;
    /** A* route to the current chest (cells to walk to), or null when steering straight-line. */
    private static List<BlockPos> approachPath = null;
    private static int     approachPathIdx = 0;
    private static boolean approachRepathed = false;
    private static int     initialNeed = 0;

    /** carpet Item → its goal slots, as PlayerInventory indices. */
    private static final Map<Item, List<Integer>> goal = new LinkedHashMap<>();
    /** Chests already opened this run (never reopened). */
    private static final Set<BlockPos> visited = new HashSet<>();
    /** Chests still worth visiting this run (unknown or known-needed), for in-world markers. */
    private static final Set<BlockPos> markerChests = new HashSet<>();
    /**
     * Outline rectangles of the region the last completed fill is meant to print —
     * at most two ({minX,minZ,maxX,maxZ}, inclusive): the full bands and the partial
     * cut-off band. Painted 4 blocks tall, persists after the run until the next fill
     * completes or the world is left. Not gated on {@link #active}.
     */
    private static final List<int[]> coveredRegions = new ArrayList<>();
    /** The Y layer {@link #coveredRegions} sit on. */
    private static int coveredY;
    /**
     * Memory of each chest's carpet contents (what's left after our last visit), keyed
     * by position, for the CURRENT world only. Lets later runs skip chests known to hold
     * nothing we need. Persisted to disk per server+dimension (see {@link #diskModel}).
     */
    private static final Map<BlockPos, Map<Item, Integer>> chestMemory = new HashMap<>();
    /** The chest the tool is currently targeting/guiding to (for markers + auto-open). */
    private static BlockPos currentChestPos = null;
    /** Whether the tool itself opened the currently-open chest (vs the player opening one). */
    private static boolean toolOpened = false;
    /** Per-open bookkeeping for whatever chest is currently open (player- or tool-opened). */
    private static boolean inOpenChest = false;
    private static boolean recordedThisOpen = false;
    private static boolean tookFromThisOpen = false;
    private static BlockPos openChestPos = null;

    // ── Persistence ──────────────────────────────────────────────────────────
    private static final String MEMORY_FILE = "loominary_chest_memory.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** All worlds: worldKey → ("x,y,z" → (itemId → count)). The on-disk model. */
    private static final Map<String, Map<String, Map<String, Integer>>> diskModel = new HashMap<>();
    /** Key for the world {@link #chestMemory} currently reflects ("server@dimension"). */
    private static String worldKey = null;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CarpetFillHandler::onTick);
        WorldRenderEvents.AFTER_ENTITIES.register(CarpetFillHandler::renderMarkers);
        WorldRenderEvents.AFTER_ENTITIES.register(CarpetFillHandler::renderCoveredRegions);
        loadDisk();
        // Bind chest memory to the joined world; flush + unbind on disconnect.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ensureWorld(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            chestMemory.clear();
            coveredRegions.clear();   // don't leak fill outlines across worlds
            worldKey = null;
        });
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * True if remembered chests (loaded from disk for the current world) already hold every color
     * in {@code demand} — so a restock can find them without a fresh catalogue pass. Lets the
     * auto-printer skip re-cataloguing on every run once the storage has been catalogued once.
     */
    public static boolean chestMemoryCovers(MinecraftClient client, Map<Item, Integer> demand) {
        ensureWorld(client);                       // make sure this world's memory is loaded first
        if (demand == null || demand.isEmpty()) return true;
        if (chestMemory.isEmpty()) return false;   // nothing remembered → catalogue
        for (Item color : demand.keySet()) {
            boolean known = false;
            for (Map<Item, Integer> contents : chestMemory.values()) {
                if (contents.getOrDefault(color, 0) > 0) { known = true; break; }
            }
            if (!known) return false;              // a needed color isn't in any known chest
        }
        return true;
    }

    public static void stop() {
        active = false;
        autonomous = false;
        cataloguing = false;
        forcedDemand = null;
        catalogueQueue.clear();
        approachPath = null;
        markerChests.clear();
        WaypointMover.stop();
    }

    /** How far we'll walk to a chest: across the whole storage room when autonomous. */
    private static double walkLimitSq() {
        return autonomous ? AUTO_WALK_LIMIT_SQ : WALK_LIMIT_SQ;
    }

    /**
     * Runs a balance first (so the proportional layout is in place and the right
     * proportions are gathered every time), then walks nearby chests to top the
     * stacks up. Returns false (with a chat message) if there's nothing to do.
     */
    public static boolean start(MinecraftClient client) {
        return start(client, false, null);
    }

    public static boolean start(MinecraftClient client, boolean auto) {
        return start(client, auto, null);
    }

    /**
     * @param auto   when true (driven by {@link AutoPrintHandler}), the tool walks the player to
     *               chests itself ({@link WaypointMover}) and ranges across the whole storage room,
     *               rather than only guiding the player within {@link #WALK_LIMIT_SQ}.
     * @param demand the exact per-colour load to gather (the auto-printer's planned load). When
     *               non-null we gather precisely this, instead of recomputing the local demand —
     *               which would key off the player's <em>current</em> position (e.g. at the chests
     *               after cataloguing) and gather the wrong mix, so the orchestrator's coverage
     *               check could never pass. Null = recompute (the standalone command).
     */
    public static boolean start(MinecraftClient client, boolean auto, Map<Item, Integer> demand) {
        if (client.player == null) return false;
        if (active) {
            feedback(client, "§e" + TAG + " Carpet fill already running.");
            return false;
        }
        autonomous = auto;
        forcedDemand = demand;
        ensureWorld(client);   // bind chest memory to the current world (handles dimension changes)

        // Validate we have demand before opening anything (the goal itself is
        // (re)built after the balance pass, since balance rearranges inventory).
        Map<Item, Integer> materials;
        try {
            materials = forcedDemand != null ? forcedDemand : CarpetBalanceHandler.carpetDemand(client);
        } catch (ClassNotFoundException e) {
            feedback(client, "§c" + TAG + " Litematica is not installed.");
            return false;
        } catch (Exception e) {
            feedback(client, "§c" + TAG + " Couldn't read Litematica material list: " + e.getMessage());
            return false;
        }
        if (materials == null || materials.isEmpty()) {
            feedback(client, "§c" + TAG + " No carpet materials found. Select your Litematica "
                    + "placement first.");
            return false;
        }

        active      = true;
        cataloguing = false;
        state       = State.BALANCE_WAIT;
        cooldown    = 0;
        openWait    = 0;
        totalTicks  = 0;
        visited.clear();
        CarpetBalanceHandler.activate(client, autonomous);   // arrange the layout; keep surplus when autonomous
        feedback(client, "§a" + TAG + " Balancing, then filling from nearby chests… §7(serpentine band "
                + CarpetBalanceHandler.getBandWidth() + " wide)");
        return true;
    }

    /**
     * Catalogue pass: walk to and open every chest within range once, recording its carpet
     * contents, to build authoritative {@link #chestMemory}. This is what lets autonomous
     * restock always know where each color lives, so the "open a chest with X" hint never
     * needs the player to step in. Always autonomous (walks the player itself).
     */
    public static boolean startCatalogue(MinecraftClient client) {
        if (client.player == null) return false;
        if (active) {
            feedback(client, "§e" + TAG + " Carpet fill already running.");
            return false;
        }
        ensureWorld(client);
        List<BlockPos> chests = scanChestsAround(client);
        if (chests.isEmpty()) {
            feedback(client, "§e" + TAG + " Catalogue: no chests found within "
                    + CATALOGUE_RADIUS_H + " blocks.");
            return false;
        }
        active      = true;
        autonomous  = true;
        cataloguing = true;
        forcedDemand = null;
        catalogued  = 0;
        catalogueQueue.clear();
        catalogueQueue.addAll(chests);
        state       = State.SCAN;
        cooldown    = 0;
        openWait    = 0;
        totalTicks  = 0;
        visited.clear();
        feedback(client, "§a" + TAG + " Cataloguing " + chests.size() + " chest(s) nearby…");
        return true;
    }

    /** All chest-block positions in a box around the player, nearest-first. */
    private static List<BlockPos> scanChestsAround(MinecraftClient client) {
        List<BlockPos> out = new ArrayList<>();
        if (client.world == null) return out;
        BlockPos origin = client.player.getBlockPos();
        for (int dx = -CATALOGUE_RADIUS_H; dx <= CATALOGUE_RADIUS_H; dx++) {
            for (int dz = -CATALOGUE_RADIUS_H; dz <= CATALOGUE_RADIUS_H; dz++) {
                for (int dy = -CATALOGUE_RADIUS_V; dy <= CATALOGUE_RADIUS_V; dy++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (client.world.getBlockEntity(pos) instanceof ChestBlockEntity) {
                        out.add(pos.toImmutable());
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> client.player.squaredDistanceTo(
                p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
        return out;
    }

    /** Builds the fill goal from current (post-balance) inventory; false if nothing to fill. */
    private static boolean computeFillGoal(MinecraftClient client) {
        Map<Item, Integer> materials;
        try {
            materials = forcedDemand != null ? forcedDemand : CarpetBalanceHandler.carpetDemand(client);
        } catch (Exception e) {
            return false;
        }
        if (materials == null || materials.isEmpty()) return false;
        buildGoal(client, materials);
        if (goal.isEmpty()) return false;
        initialNeed = totalNeed(client.player.getInventory());
        if (initialNeed == 0) {
            feedback(client, "§a" + TAG + " Carpet stacks already full — nothing to fill.");
            return false;
        }
        feedback(client, "§a" + TAG + " Filling carpets from nearby chests (need "
                + initialNeed + ")…");
        return true;
    }

    // ── Goal construction (proportional, over PlayerInventory indices) ───────

    private static void buildGoal(MinecraftClient client, Map<Item, Integer> materials) {
        goal.clear();
        PlayerInventory inv = client.player.getInventory();

        // Usable slots = main + hotbar (0–35), excluding any holding a non-carpet item.
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i <= LAST_INV_SLOT; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || CarpetBalanceHandler.isCarpet(s.getItem())) candidates.add(i);
        }

        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(materials.entrySet());
        sorted.sort(Comparator
                .<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> Registries.ITEM.getId(e.getKey()).getPath()));

        int[] want = CarpetBalanceHandler.allocateProportionalSlots(sorted, candidates.size());

        Set<Integer> used = new HashSet<>();
        for (int c = 0; c < sorted.size(); c++) {
            Item color = sorted.get(c).getKey();
            if (want[c] <= 0) continue;
            List<Integer> slots = new ArrayList<>();
            // Pass 1: reuse slots this color already occupies.
            for (int idx : candidates) {
                if (slots.size() >= want[c]) break;
                if (used.contains(idx)) continue;
                ItemStack s = inv.getStack(idx);
                if (!s.isEmpty() && s.getItem() == color) slots.add(idx);
            }
            // Pass 2: fill the rest from any free candidate slot.
            for (int idx : candidates) {
                if (slots.size() >= want[c]) break;
                if (used.contains(idx) || slots.contains(idx)) continue;
                slots.add(idx);
            }
            for (int idx : slots) used.add(idx);
            if (!slots.isEmpty()) goal.put(color, slots);
        }
    }

    // ── Tick loop ────────────────────────────────────────────────────────────

    private static void onTick(MinecraftClient client) {
        if (!active) return;
        if (client.player == null || client.interactionManager == null || client.world == null) {
            stop();
            return;
        }
        if (++totalTicks > WATCHDOG_TICKS) {
            finish(client, "§e" + TAG + " Carpet fill timed out.");
            return;
        }

        // Any open chest — whether the tool opened it or you did — is handled here:
        // grab the carpet we need from it and remember its contents. This lets you help
        // by simply opening a chest you know holds what's wanted.
        if (isContainerOpen(client)) {
            if (!inOpenChest) {                       // a chest just opened
                inOpenChest = true;
                syncWait = 0;
                lastSyncCarpet = -1;
                syncStableTicks = 0;
                recordedThisOpen = false;
                tookFromThisOpen = false;
                WaypointMover.stop();                          // don't walk while looting
                openChestPos = openedChestPos(client);         // the chest actually opened
                // The open registered, so the WAIT_OPEN gap is over: drop to COOLDOWN. If the
                // chest now closes (you close it, or we do), we re-scan instead of falling back
                // into waitOpenStep, which would re-send the interact and reopen the chest.
                state = State.COOLDOWN;
                // Hold the container open for a settle window before grabbing/closing. This
                // also keeps us compatible with mods (e.g. Ev's) that cache the open
                // container's slots lazily on a steady tick and dereference them on close —
                // opening and closing within one tick would leave that cache null (NPE).
                cooldown = SETTLE_TICKS;
            }
            if (cooldown > 0) { cooldown--; return; }
            grabStep(client);
            return;
        }
        inOpenChest = false;

        switch (state) {
            case BALANCE_WAIT -> balanceWaitStep(client);
            case SCAN -> {
                if (cooldown > 0) { cooldown--; return; }
                scanStep(client);
            }
            case APPROACH -> approachStep(client);
            case WAIT_OPEN -> waitOpenStep(client);
            case COOLDOWN -> {
                if (cooldown > 0) { cooldown--; return; }
                state = State.SCAN;
            }
        }
    }

    private static void balanceWaitStep(MinecraftClient client) {
        if (CarpetBalanceHandler.isActive()) return;     // wait for the balance pass to finish
        // Balance done — close the inventory it opened, then plan the fill.
        if (client.currentScreen instanceof InventoryScreen) client.player.closeHandledScreen();
        if (!computeFillGoal(client)) { active = false; return; }
        state = State.COOLDOWN;                          // brief settle before opening chests
        cooldown = BETWEEN_CHESTS_TICKS;
    }

    private static void scanStep(MinecraftClient client) {
        if (cataloguing) { catalogueScan(client); return; }
        PlayerInventory inv = client.player.getInventory();
        if (totalNeed(inv) == 0) {
            finish(client, "§a" + TAG + " Carpet fill done — inventory full of the needed carpets.", true);
            return;
        }
        BlockPos chest = findNextChest(client);   // nearest known-needed chest; refreshes markers
        currentChestPos = chest;                  // marker target (may be far / null)

        // Walk to a known-needed chest within the walk limit (a few blocks when guiding the
        // player, the whole storage room when autonomous). Farther — or unknown — chests:
        // manual mode just says what's wanted and waits; autonomous mode gives up so the
        // orchestrator can stop (Phase 3's catalogue pass is what makes a chest known).
        if (chest != null) {
            double distSq = client.player.squaredDistanceTo(
                    chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
            if (distSq <= walkLimitSq()) {
                toolOpened = false;
                if (autonomous) {
                    beginApproach(client, chest);   // plan a route + walk ourselves there
                } else {
                    approachTimer = 0;
                    lastApproachDistSq = Double.MAX_VALUE;
                    state = State.APPROACH;
                }
                return;
            }
        }
        if (autonomous) {
            finish(client, "§e" + TAG + " Carpet fill: no reachable chest with the carpet still "
                    + "needed — stopping. §7(catalogue the storage so chests are known)");
            return;
        }
        client.inGameHud.setOverlayMessage(Text.literal(needHint(client, inv, chest)), false);
        state = State.COOLDOWN;
        cooldown = 5;    // re-check shortly; grabs immediately if you open a chest meanwhile
    }

    /** Catalogue mode: head to the next un-opened chest in the queue, recording each in turn. */
    private static void catalogueScan(MinecraftClient client) {
        // Greedy nearest-neighbour tour: from where we are NOW, go to the closest chest we
        // haven't opened yet. A fixed distance-from-start order interleaves chests on different
        // walls and ping-pongs across the room; re-picking the nearest each time follows the
        // walls. (A full TSP isn't worth it for a few hundred chests along walls.)
        BlockPos next = null;
        double bestSq = Double.MAX_VALUE;
        for (var it = catalogueQueue.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (visited.contains(p)) { it.remove(); continue; }   // already opened (e.g. a partner half)
            double d = client.player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            if (d < bestSq) { bestSq = d; next = p; }
        }
        if (next == null) {
            finish(client, "§a" + TAG + " Catalogue done — recorded " + catalogued + " chest(s).");
            return;
        }
        catalogueQueue.remove(next);
        toolOpened = false;
        beginApproach(client, next);
        client.inGameHud.setOverlayMessage(Text.literal(String.format(
                "§e%s Cataloguing… (%d left)", TAG, catalogueQueue.size())), false);
    }

    // ── Autonomous chest approach: A* route around walls, straight-line fallback ────────────

    /** Plan a route to a stand-spot beside {@code chest} and start walking it. */
    private static void beginApproach(MinecraftClient client, BlockPos chest) {
        currentChestPos = chest;
        approachTimer = 0;
        lastApproachDistSq = Double.MAX_VALUE;
        approachRepathed = false;
        planApproachPath(client, chest);
        state = State.APPROACH;
    }

    /** (Re)compute the A* route from the player's current cell; fall back to straight-line. */
    private static void planApproachPath(MinecraftClient client, BlockPos chest) {
        BlockPos stand = standPositionFor(client, chest);
        approachPath = (stand == null) ? null
                : Pathfinder.findPath(client.world, client.player.getBlockPos(), stand, PATH_RADIUS);
        approachPathIdx = 0;
        if (approachPath != null && !approachPath.isEmpty()) {
            WaypointMover.setWaypoint(approachPath.get(0));
        } else {
            approachPath = null;                       // no route → steer straight at the chest
            WaypointMover.setWaypoint(chest);
        }
    }

    /** A walkable cell beside the chest to open it from — the one nearest the player. */
    private static BlockPos standPositionFor(MinecraftClient client, BlockPos chest) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.Type.HORIZONTAL) {
            for (int dy = 0; dy >= -1; dy--) {              // same level, then one step down
                BlockPos cand = chest.offset(d).up(dy);
                if (!Pathfinder.walkable(client.world, cand)) continue;
                double dist = client.player.squaredDistanceTo(
                        cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
                if (dist < bestDist) { bestDist = dist; best = cand; }
            }
        }
        return best;
    }

    /** "Need 192 brown — open a chest with it (nearest known: x,y,z, N blocks)". */
    private static String needHint(MinecraftClient client, PlayerInventory inv, BlockPos nearestKnown) {
        Item top = null; int topNeed = 0;
        for (Item color : goal.keySet()) {
            int n = need(inv, color);
            if (n > topNeed) { topNeed = n; top = color; }
        }
        String want = top == null ? totalNeed(inv) + " carpet"
                : topNeed + " " + Registries.ITEM.getId(top).getPath().replace("_carpet", "");
        String hint = "§e" + TAG + " Need §f" + want + "§e — open a chest with it";
        if (nearestKnown != null) {
            double d = Math.sqrt(client.player.squaredDistanceTo(
                    nearestKnown.getX() + 0.5, nearestKnown.getY() + 0.5, nearestKnown.getZ() + 0.5));
            hint += String.format(" §7(nearest known: %d,%d,%d, %.0f blocks)",
                    nearestKnown.getX(), nearestKnown.getY(), nearestKnown.getZ(), d);
        }
        return hint;
    }

    private static void approachStep(MinecraftClient client) {
        if (totalNeed(client.player.getInventory()) == 0) {
            finish(client, "§a" + TAG + " Carpet fill done — inventory full of the needed carpets.", true);
            return;
        }
        if (currentChestPos == null) {
            state = State.COOLDOWN;
            cooldown = BETWEEN_CHESTS_TICKS;
            return;
        }
        double distSq = client.player.squaredDistanceTo(
                currentChestPos.getX() + 0.5, currentChestPos.getY() + 0.5, currentChestPos.getZ() + 0.5);

        // If you've moved past the auto-walk cap, stop guiding to this one and re-evaluate
        // (you may be heading to a closer chest, or opening one yourself).
        if (distSq > walkLimitSq()) {
            WaypointMover.stop();
            approachPath = null;
            state = State.COOLDOWN;
            cooldown = BETWEEN_CHESTS_TICKS;
            return;
        }

        if (distSq <= REACH_SQ) {
            WaypointMover.stop();   // arrived — stop walking before we open and loot
            approachPath = null;
            // In reach now (so the chunk is loaded) — verify a chest is actually there.
            if (!(client.world.getBlockEntity(currentChestPos) instanceof ChestBlockEntity)) {
                System.out.println(TAG + " Chest at " + currentChestPos + " is gone — forgetting it.");
                visited.add(currentChestPos);
                if (chestMemory.remove(currentChestPos) != null) saveDisk();
                state = State.COOLDOWN;
                cooldown = BETWEEN_CHESTS_TICKS;
                return;
            }
            visited.add(currentChestPos);
            openWait = 0;
            openAttempts = 1;
            toolOpened = true;
            openChest(client, currentChestPos);
            state = State.WAIT_OPEN;
            return;
        }

        // Out of reach. Autonomous: walk ourselves there along the planned A* route (or straight
        // line if none), re-routing once around an obstacle if the mover gets stuck. Manual:
        // guide the player with an overlay and wait. The give-up timer only counts while NOT
        // getting closer, so a long approach never times out while progress is being made.
        if (autonomous) {
            if (WaypointMover.stuck()) {
                if (!approachRepathed) {
                    approachRepathed = true;
                    System.out.println(TAG + " Mover stuck approaching " + currentChestPos
                            + " — re-routing around the obstacle.");
                    planApproachPath(client, currentChestPos);   // re-plan from here; reissues a waypoint
                } else {
                    System.out.println(TAG + " Couldn't walk to chest at " + currentChestPos
                            + " (stuck after re-route) — skipping.");
                    WaypointMover.stop();
                    visited.add(currentChestPos);
                    approachPath = null;
                    state = State.COOLDOWN;
                    cooldown = BETWEEN_CHESTS_TICKS;
                    lastApproachDistSq = Double.MAX_VALUE;
                    return;
                }
            } else if (approachPath != null) {
                // Following a route — advance through its waypoints; progress resets the timer.
                if (WaypointMover.arrived()) {
                    approachTimer = 0;
                    approachPathIdx++;
                    if (approachPathIdx < approachPath.size()) {
                        WaypointMover.setWaypoint(approachPath.get(approachPathIdx));
                    } else {
                        approachPath = null;                     // route consumed — close the last gap
                        WaypointMover.setWaypoint(currentChestPos);
                    }
                } else if (!WaypointMover.isActive()) {
                    WaypointMover.setWaypoint(approachPath.get(approachPathIdx));
                }
            } else if (!WaypointMover.isActive()) {
                WaypointMover.setWaypoint(currentChestPos);      // straight-line fallback
            }
        } else {
            client.inGameHud.setOverlayMessage(Text.literal(String.format(
                    "§e%s Walk to chest §f%d,%d,%d§e — §f%.0f§e blocks (need %d)",
                    TAG, currentChestPos.getX(), currentChestPos.getY(), currentChestPos.getZ(),
                    Math.sqrt(distSq), totalNeed(client.player.getInventory()))), false);
        }
        if (distSq < lastApproachDistSq - 0.25) {
            approachTimer = 0;            // made progress this tick
        } else if (++approachTimer > APPROACH_TIMEOUT_TICKS) {
            System.out.println(TAG + " Gave up walking to chest at " + currentChestPos
                    + " after ~" + (APPROACH_TIMEOUT_TICKS / 20) + "s with no progress — skipping.");
            visited.add(currentChestPos);   // don't keep re-selecting it this run
            WaypointMover.stop();
            approachPath = null;
            state = State.COOLDOWN;
            cooldown = BETWEEN_CHESTS_TICKS;
            lastApproachDistSq = Double.MAX_VALUE;
            return;
        }
        lastApproachDistSq = distSq;
    }

    private static void waitOpenStep(MinecraftClient client) {
        // (Once the chest actually opens, the open-chest handler at the top of onTick
        // takes over — this state only covers the gap before the open registers.)
        openWait++;
        // Re-send the interact a couple of times in case the first packet was dropped/rejected.
        if (openWait % OPEN_RETRY_INTERVAL == 0 && openAttempts < MAX_OPEN_ATTEMPTS) {
            openAttempts++;
            openChest(client, currentChestPos);
        }
        if (openWait > OPEN_TIMEOUT_TICKS) {
            System.out.println(TAG + " Chest at " + currentChestPos + " did not open after "
                    + openAttempts + " attempt(s) — skipping (out of reach or blocked?).");
            state = State.COOLDOWN;
            cooldown = BETWEEN_CHESTS_TICKS;
        }
    }

    private static void grabStep(MinecraftClient client) {
        if (!isContainerOpen(client)) {
            // Screen closed unexpectedly — move on.
            state = State.COOLDOWN;
            cooldown = BETWEEN_CHESTS_TICKS;
            return;
        }

        ScreenHandler h = client.player.currentScreenHandler;
        PlayerInventory inv = client.player.getInventory();

        // Wait for the chest's contents to FULLY sync before recording. On a laggy server the
        // slots arrive over several ticks, so don't trust the first non-empty read — keep waiting
        // until the carpet total holds steady for SYNC_STABLE_TICKS (or we hit SYNC_WAIT_MAX).
        // Recording mid-sync is what produced undercounts (a full double chest remembered as a
        // couple hundred) and false empties (a stocked chest hidden).
        if (!recordedThisOpen) {
            int carpetNow = containerCarpetCount(h, inv);
            if (carpetNow != lastSyncCarpet) { lastSyncCarpet = carpetNow; syncStableTicks = 0; }
            else syncStableTicks++;
            boolean nothingYet = carpetNow == 0 && !containerHasItem(h, inv);
            if (syncWait < SYNC_WAIT_MAX && (nothingYet || syncStableTicks < SYNC_STABLE_TICKS)) {
                syncWait++;
                cooldown = 1;
                return;
            }
            recordChestMemory(client, h, inv, openChestPos);
            recordedThisOpen = true;
            if (cataloguing) catalogued++;
        }

        ItemStack cursor = h.getCursorStack();

        // Catalogue mode only records; it never takes carpet. Close and move to the next chest.
        if (cataloguing) { closeAndCooldown(client); return; }

        if (cursor.isEmpty()) {
            if (totalNeed(inv) == 0) { closeAndCooldown(client); return; }
            int cs = firstNeededChestSlot(h, inv);
            if (cs != -1) {
                click(client, h, cs);          // pick up a needed stack
                tookFromThisOpen = true;
                return;
            }
            // Nothing (more) we need in this chest. If we opened it, or took from it,
            // close and move on. If you opened it and it had nothing for us, leave it be.
            if (toolOpened || tookFromThisOpen) {
                closeAndCooldown(client);
            } else {
                client.inGameHud.setOverlayMessage(Text.literal(needHint(client, inv, currentChestPos)), false);
            }
            return;
        }

        if (CarpetBalanceHandler.isCarpet(cursor.getItem())) {
            Item color = cursor.getItem();
            int dst = goalHandlerSlotNeeding(h, inv, color);      // a goal slot still under 64
            if (dst != -1) { click(client, h, dst); return; }     // deposit, carry remainder
            int back = chestReturnSlot(h, inv, color);            // need met → return excess
            if (back != -1) { click(client, h, back); return; }
        }
        // Carpet we can't place/return, or a stray non-carpet on the cursor: park it
        // somewhere safe rather than risk dropping it on close.
        int park = chestReturnSlot(h, inv, cursor.getItem());
        if (park == -1) park = anyEmptyContainerSlot(h, inv);
        if (park != -1) { click(client, h, park); return; }
        // Truly nowhere to put it — stop without closing so the cursor stack isn't dropped.
        stop();
        feedback(client, "§e" + TAG + " Carpet fill stopped (no room to set down a stack).");
    }

    private static void closeAndCooldown(MinecraftClient client) {
        // Re-record what's actually left after grabbing, so a chest we just drained isn't
        // remembered as still full (which would send us back to an empty chest next run).
        // The open-time record reflected pre-grab contents; this overwrites it with the
        // post-grab truth, or forgets the chest entirely if we cleared its carpet.
        if (tookFromThisOpen && isContainerOpen(client)) {
            recordChestMemory(client, client.player.currentScreenHandler,
                    client.player.getInventory(), openChestPos);
        }
        client.player.closeHandledScreen();   // cursor is always empty at this point
        toolOpened = false;
        currentChestPos = null;
        approachPath = null;
        state = State.COOLDOWN;
        cooldown = BETWEEN_CHESTS_TICKS;
    }

    /** Remembers a chest's carpet-only contents (counts) for this session, at {@code pos}. */
    private static void recordChestMemory(MinecraftClient client, ScreenHandler h, PlayerInventory inv, BlockPos pos) {
        if (pos == null) return;
        Map<Item, Integer> contents = new HashMap<>();
        int containerSlots = 0;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv) continue;                 // chest slots only
            containerSlots++;
            ItemStack s = slot.getStack();
            if (s.isEmpty() || !CarpetBalanceHandler.isCarpet(s.getItem())) continue;  // carpet only
            contents.merge(s.getItem(), s.getCount(), Integer::sum);
        }

        // Never persist an "empty" reading: an empty container is ambiguous (truly empty
        // vs. contents not yet synced), and a false empty would permanently hide a stocked
        // chest. Forget it instead, so it's treated as unknown and re-checked later.
        if (contents.isEmpty()) {
            if (chestMemory.remove(pos) != null) saveDisk();
            return;
        }
        chestMemory.put(pos, contents);

        // A double chest is two block entities sharing one inventory (54 slots). Mark the
        // connected half visited and remember it too, so we don't reopen the same storage as a
        // separate chest. Resolve the ONE true partner from the chest's own geometry — NOT by
        // scanning all neighbours: carpet stations pack double chests wall-to-wall, so a half of
        // this chest sits beside a half of a *different-colour* chest. Clobbering that neighbour
        // (or marking it visited) is what smeared memory and sent restock across stations.
        if (client.world != null) {
            BlockPos partner = doubleChestPartner(client, pos);
            if (partner != null && client.world.getBlockEntity(partner) instanceof ChestBlockEntity) {
                visited.add(partner);
                chestMemory.put(partner, contents);
            }
        }
        saveDisk();
    }

    /**
     * The position of the other half of the double chest at {@code pos}, or null if it's a single
     * chest. A double chest's two halves face the same way and connect perpendicular to that
     * facing: the LEFT half's partner is clockwise of facing, the RIGHT half's counter-clockwise
     * (vanilla {@code ChestBlock.getFacing}). This is exact — unlike scanning neighbours, it never
     * mistakes an adjacent unrelated chest for the partner.
     */
    private static BlockPos doubleChestPartner(MinecraftClient client, BlockPos pos) {
        BlockState st = client.world.getBlockState(pos);
        if (!(st.getBlock() instanceof ChestBlock)) return null;
        ChestType type = st.get(ChestBlock.CHEST_TYPE);
        if (type == ChestType.SINGLE) return null;
        Direction facing = st.get(ChestBlock.FACING);
        Direction toPartner = (type == ChestType.LEFT)
                ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
        return pos.offset(toPartner);
    }

    /**
     * The position of the chest that just opened. Attribution must be exact: filing contents
     * under the wrong block (e.g. the chest nearest your feet rather than the one you opened)
     * smears one chest's contents across a whole storage wall and wrecks navigation. So:
     * <ol>
     *   <li>If the tool opened it, we know the exact block we interacted with ({@link #currentChestPos}).</li>
     *   <li>Otherwise it's the block under the crosshair — what you actually clicked to open.</li>
     *   <li>Only as a last resort fall back to the nearest chest in reach.</li>
     * </ol>
     */
    private static BlockPos openedChestPos(MinecraftClient client) {
        if (toolOpened && currentChestPos != null
                && client.world.getBlockEntity(currentChestPos) instanceof ChestBlockEntity) {
            return currentChestPos;
        }
        if (client.crosshairTarget instanceof BlockHitResult bhr
                && client.world.getBlockEntity(bhr.getBlockPos()) instanceof ChestBlockEntity) {
            return bhr.getBlockPos().toImmutable();
        }
        return nearestChestInReach(client);
    }

    /** Nearest chest block within reach — last-resort attribution when we can't tell exactly. */
    private static BlockPos nearestChestInReach(MinecraftClient client) {
        if (client.world == null) return null;
        BlockPos origin = client.player.getBlockPos();
        int r = (int) Math.ceil(Math.sqrt(REACH_SQ)) + 1;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    double distSq = client.player.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq > REACH_SQ || distSq >= bestDistSq) continue;
                    if (!(client.world.getBlockEntity(pos) instanceof ChestBlockEntity)) continue;
                    best = pos.toImmutable();
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    /** True if the open chest has any item at all — our signal that its contents have synced. */
    private static boolean containerHasItem(ScreenHandler h, PlayerInventory inv) {
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv) continue;     // chest slots only
            if (!slot.getStack().isEmpty()) return true;
        }
        return false;
    }

    /** Total carpet items in the open container's slots (not the player's) — the sync-stability signal. */
    private static int containerCarpetCount(ScreenHandler h, PlayerInventory inv) {
        int n = 0;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv) continue;     // chest slots only
            ItemStack s = slot.getStack();
            if (!s.isEmpty() && CarpetBalanceHandler.isCarpet(s.getItem())) n += s.getCount();
        }
        return n;
    }

    // ── Persistence (per server + dimension) ─────────────────────────────────

    private static Path memoryFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(MEMORY_FILE);
    }

    /** Stable id for the current world: "<server-or-singleplayer>@<dimension>". */
    private static String worldKey(MinecraftClient client) {
        var entry = client.getCurrentServerEntry();
        String place = (entry != null && entry.address != null) ? entry.address : "singleplayer";
        String dim = client.world != null ? client.world.getRegistryKey().getValue().toString() : "unknown";
        return place + "@" + dim;
    }

    /** Binds {@link #chestMemory} to the current world, loading its remembered chests. */
    private static void ensureWorld(MinecraftClient client) {
        String wk = worldKey(client);
        if (wk.equals(worldKey)) return;
        worldKey = wk;
        chestMemory.clear();
        Map<String, Map<String, Integer>> section = diskModel.get(wk);
        if (section == null) return;
        for (Map.Entry<String, Map<String, Integer>> e : section.entrySet()) {
            BlockPos p = parsePos(e.getKey());
            if (p == null) continue;
            Map<Item, Integer> contents = new HashMap<>();
            for (Map.Entry<String, Integer> ce : e.getValue().entrySet()) {
                Identifier id = Identifier.tryParse(ce.getKey());
                if (id == null) continue;
                Item it = Registries.ITEM.get(id);
                if (CarpetBalanceHandler.isCarpet(it)) contents.put(it, ce.getValue());
            }
            // Skip empty entries: a chest remembered as empty (possibly a stale false
            // empty) is treated as unknown so it gets re-checked rather than ignored.
            if (!contents.isEmpty()) chestMemory.put(p, contents);
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadDisk() {
        diskModel.clear();
        Path path = memoryFile();
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path);
            var type = new TypeToken<Map<String, Map<String, Map<String, Integer>>>>() {}.getType();
            Map<String, Map<String, Map<String, Integer>>> parsed = GSON.fromJson(json, type);
            if (parsed != null) diskModel.putAll(parsed);
        } catch (Exception e) {
            System.err.println(TAG + " Couldn't read chest memory: " + e.getMessage());
        }
    }

    /** Serializes {@link #chestMemory} into {@link #diskModel} for the current world and writes the file. */
    private static void saveDisk() {
        if (worldKey == null) return;
        Map<String, Map<String, Integer>> section = new HashMap<>();
        for (Map.Entry<BlockPos, Map<Item, Integer>> e : chestMemory.entrySet()) {
            BlockPos p = e.getKey();
            Map<String, Integer> m = new HashMap<>();
            for (Map.Entry<Item, Integer> ce : e.getValue().entrySet()) {
                m.put(Registries.ITEM.getId(ce.getKey()).toString(), ce.getValue());
            }
            section.put(p.getX() + "," + p.getY() + "," + p.getZ(), m);
        }
        diskModel.put(worldKey, section);
        try {
            Files.writeString(memoryFile(), GSON.toJson(diskModel));
        } catch (Exception e) {
            System.err.println(TAG + " Couldn't write chest memory: " + e.getMessage());
        }
    }

    private static BlockPos parsePos(String key) {
        String[] xyz = key.split(",");
        if (xyz.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Need accounting (live, from PlayerInventory) ─────────────────────────

    /** Carpet of {@code color} still needed to bring its goal slots up to 64. */
    private static int need(PlayerInventory inv, Item color) {
        List<Integer> slots = goal.get(color);
        if (slots == null) return 0;
        int n = 0;
        for (int idx : slots) {
            ItemStack s = inv.getStack(idx);
            if (s.isEmpty()) n += MAX_STACK;
            else if (s.getItem() == color && s.getCount() < MAX_STACK) n += MAX_STACK - s.getCount();
        }
        return n;
    }

    private static int totalNeed(PlayerInventory inv) {
        int n = 0;
        for (Item color : goal.keySet()) n += need(inv, color);
        return n;
    }

    // ── Slot queries on the open chest handler ───────────────────────────────

    private static int firstNeededChestSlot(ScreenHandler h, PlayerInventory inv) {
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv) continue;                 // player slot, not the chest
            ItemStack s = slot.getStack();
            if (s.isEmpty() || !CarpetBalanceHandler.isCarpet(s.getItem())) continue;
            if (need(inv, s.getItem()) > 0) return i;
        }
        return -1;
    }

    /** Handler slot id for the first goal slot of {@code color} that still has room. */
    private static int goalHandlerSlotNeeding(ScreenHandler h, PlayerInventory inv, Item color) {
        List<Integer> slots = goal.get(color);
        if (slots == null) return -1;
        for (int invIdx : slots) {
            ItemStack s = inv.getStack(invIdx);
            boolean room = s.isEmpty() || (s.getItem() == color && s.getCount() < MAX_STACK);
            if (room) return findHandlerSlot(h, inv, invIdx);
        }
        return -1;
    }

    private static int findHandlerSlot(ScreenHandler h, PlayerInventory inv, int invIdx) {
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv && slot.getIndex() == invIdx) return i;
        }
        return -1;
    }

    /** A chest slot to drop the cursor remainder into: merge into a like stack, else an empty slot. */
    private static int chestReturnSlot(ScreenHandler h, PlayerInventory inv, Item item) {
        int empty = -1;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv) continue;                 // must stay in the chest
            ItemStack s = slot.getStack();
            if (s.isEmpty()) { if (empty == -1) empty = i; continue; }
            if (s.getItem() == item && s.getCount() < s.getMaxCount()) return i;
        }
        return empty;
    }

    private static int anyEmptyContainerSlot(ScreenHandler h, PlayerInventory inv) {
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory != inv && slot.getStack().isEmpty()) return i;
        }
        return -1;
    }

    // ── World interaction ────────────────────────────────────────────────────

    /**
     * Picks the next chest to head for. KNOWN chests come from {@link #chestMemory} by
     * their stored positions, at <em>any</em> distance — so a needed chest across a big
     * storage room is still found and the APPROACH state walks the player to it. UNKNOWN
     * (uncatalogued) chests are only opened when you're <em>already next to one</em>: we
     * never walk you across the room to a chest whose contents we don't know (which led to
     * trekking to chests that turned out to hold carpet you don't need). Chests known to
     * hold nothing we need are skipped. Also refreshes {@link #markerChests}.
     */
    private static BlockPos findNextChest(MinecraftClient client) {
        PlayerInventory inv = client.player.getInventory();
        markerChests.clear();

        // Known chests with a needed color — from memory, no distance limit.
        BlockPos bestKnown = null; double bestKnownDist = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Map<Item, Integer>> e : chestMemory.entrySet()) {
            BlockPos pos = e.getKey();
            if (visited.contains(pos)) continue;
            if (!chestHasNeeded(e.getValue(), inv)) continue;
            markerChests.add(pos);
            double distSq = client.player.squaredDistanceTo(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSq < bestKnownDist) { bestKnownDist = distSq; bestKnown = pos; }
        }

        // Unknown chests — only catalogue ones already within reach; never walk to them.
        BlockPos bestUnknown = null; double bestUnknownDist = Double.MAX_VALUE;
        BlockPos origin = client.player.getBlockPos();
        int r = (int) Math.ceil(Math.sqrt(REACH_SQ)) + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (visited.contains(pos) || chestMemory.containsKey(pos)) continue;
                    double distSq = client.player.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq > REACH_SQ) continue;   // only chests you're already standing at
                    if (!(client.world.getBlockEntity(pos) instanceof ChestBlockEntity)) continue;
                    BlockPos ip = pos.toImmutable();
                    markerChests.add(ip);
                    if (distSq < bestUnknownDist) { bestUnknownDist = distSq; bestUnknown = ip; }
                }
            }
        }
        // Go to whichever is actually nearer. Standing in front of a chest that holds what
        // you need opens it, rather than detouring to an uncatalogued chest across the room.
        if (bestKnown != null && (bestUnknown == null || bestKnownDist <= bestUnknownDist)) return bestKnown;
        return bestUnknown;
    }

    /** True if a remembered chest still holds carpet of a color we currently need. */
    private static boolean chestHasNeeded(Map<Item, Integer> known, PlayerInventory inv) {
        for (Map.Entry<Item, Integer> e : known.entrySet()) {
            if (e.getValue() > 0 && need(inv, e.getKey()) > 0) return true;
        }
        return false;
    }

    private static void openChest(MinecraftClient client, BlockPos pos) {
        // Turn to look at the chest first — some server anticheat requires the
        // interaction's look direction to actually point at the block.
        faceBlock(client, pos);

        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d diff = client.player.getEyePos().subtract(center);
        Direction face = Direction.getFacing((float) diff.x, (float) diff.y, (float) diff.z);
        Vec3d hitPos = new Vec3d(
                pos.getX() + 0.5 + face.getOffsetX() * 0.5,
                pos.getY() + 0.5 + face.getOffsetY() * 0.5,
                pos.getZ() + 0.5 + face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, face, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        double dist = Math.sqrt(client.player.squaredDistanceTo(center));
        System.out.println(TAG + " Opening chest at " + pos + " (dist "
                + String.format("%.1f", dist) + ", attempt " + openAttempts + ")");
    }

    /** Points the player's view at the centre of the block. */
    private static void faceBlock(MinecraftClient client, BlockPos pos) {
        Vec3d eye = client.player.getEyePos();
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        client.player.setYaw((float) yaw);
        client.player.setPitch((float) pitch);
    }

    private static boolean isContainerOpen(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen
                && client.player.currentScreenHandler != client.player.playerScreenHandler;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void click(MinecraftClient client, ScreenHandler h, int slot) {
        client.interactionManager.clickSlot(h.syncId, slot, LEFT_CLICK, SlotActionType.PICKUP, client.player);
        cooldown = ACTION_COOLDOWN_TICKS;
    }

    private static void finish(MinecraftClient client, String msg) {
        finish(client, msg, false);
    }

    /**
     * Ends the run. When {@code paintRegion} (a real completion, not a timeout), replaces
     * the persistent outline with the rectangles the just-gathered load is meant to print.
     */
    private static void finish(MinecraftClient client, String msg, boolean paintRegion) {
        active = false;
        autonomous = false;
        cataloguing = false;
        forcedDemand = null;
        catalogueQueue.clear();
        approachPath = null;
        markerChests.clear();
        WaypointMover.stop();
        if (paintRegion) {
            coveredRegions.clear();
            for (int[] r : CarpetBalanceHandler.lastLoadRegions) coveredRegions.add(r.clone());
            coveredY = CarpetBalanceHandler.lastLoadY;
        }
        feedback(client, msg);
    }

    private static void feedback(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }

    // ── In-world markers ───────────────────────────────────────────────────────

    /** Draws a box over every chest still worth visiting; the active target is brighter. */
    private static void renderMarkers(WorldRenderContext context) {
        if (!active || markerChests.isEmpty()) return;
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        MatrixStack matrices = context.matrixStack();
        Vec3d camPos = context.camera().getPos();
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        for (BlockPos pos : markerChests) {
            boolean target = pos.equals(currentChestPos);
            double ox = pos.getX() - camPos.x;
            double oy = pos.getY() - camPos.y;
            double oz = pos.getZ() - camPos.z;
            // Target chest: bright yellow and full-block; others: dim blue and small.
            float r = target ? 1.0f : 0.3f;
            float g = target ? 0.9f : 0.6f;
            float b = target ? 0.1f : 1.0f;
            double pad = target ? 0.02 : 0.15;
            VertexRendering.drawBox(matrices, lines,
                    ox + pad, oy + pad, oz + pad,
                    ox + 1 - pad, oy + 1 - pad, oz + 1 - pad,
                    r, g, b, 0.9f);
        }
    }

    /**
     * Draws a 4-block-tall wireframe over the region the last completed fill is meant to
     * print: the full-bands rectangle in green, the partial cut-off band in orange.
     * Persists after the run (not gated on {@link #active}).
     */
    private static void renderCoveredRegions(WorldRenderContext context) {
        if (coveredRegions.isEmpty()) return;
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        MatrixStack matrices = context.matrixStack();
        Vec3d camPos = context.camera().getPos();
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        for (int[] r : coveredRegions) {   // {minX, minZ, maxX, maxZ, full}
            // full-bands rectangle in green, partial cut-off band in orange.
            boolean full = r[4] == 1;
            float cr = full ? 0.2f : 1.0f;
            float cg = full ? 0.9f : 0.6f;
            float cb = full ? 0.2f : 0.1f;
            double x1 = r[0]     - camPos.x;
            double z1 = r[1]     - camPos.z;
            double x2 = r[2] + 1 - camPos.x;
            double z2 = r[3] + 1 - camPos.z;
            double y1 = coveredY     - camPos.y;
            double y2 = coveredY + 4 - camPos.y;
            VertexRendering.drawBox(matrices, lines, x1, y1, z1, x2, y2, z2, cr, cg, cb, 0.9f);
        }
    }
}
