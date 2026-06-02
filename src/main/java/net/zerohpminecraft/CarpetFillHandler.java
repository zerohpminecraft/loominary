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

    private static final int    DETECT_RANGE       = 24;       // how far we look for chests
    private static final double REACH_SQ           = 4.5 * 4.5; // how close to actually open one
    private static final int    LAST_INV_SLOT      = 35;   // 0–8 hotbar, 9–35 main; 36+ armor/offhand
    private static final int    MAX_STACK          = CarpetBalanceHandler.MAX_STACK;

    private static final int    ACTION_COOLDOWN_TICKS  = 4;
    private static final int    SETTLE_TICKS           = 10;   // wait after open for contents to sync
    private static final int    OPEN_TIMEOUT_TICKS     = 60;
    private static final int    OPEN_RETRY_INTERVAL    = 15;   // re-send interact this often while waiting
    private static final int    MAX_OPEN_ATTEMPTS      = 3;
    private static final int    APPROACH_TIMEOUT_TICKS = 1200;  // give up guiding to one chest after ~60s
    private static final int    BETWEEN_CHESTS_TICKS   = 10;
    private static final int    WATCHDOG_TICKS         = 36000;

    private static final int LEFT_CLICK = 0;

    private enum State { BALANCE_WAIT, SCAN, APPROACH, WAIT_OPEN, GRAB, COOLDOWN }

    // ── Run state ──────────────────────────────────────────────────────────
    private static boolean active = false;
    private static State   state  = State.SCAN;
    private static int     cooldown   = 0;
    private static int     openWait   = 0;
    private static int     openAttempts = 0;
    private static int     totalTicks = 0;
    private static int     approachTimer = 0;
    private static int     initialNeed = 0;

    /** carpet Item → its goal slots, as PlayerInventory indices. */
    private static final Map<Item, List<Integer>> goal = new LinkedHashMap<>();
    /** Chests already opened this run (never reopened). */
    private static final Set<BlockPos> visited = new HashSet<>();
    /** Chests still worth visiting this run (unknown or known-needed), for in-world markers. */
    private static final Set<BlockPos> markerChests = new HashSet<>();
    /**
     * Memory of each chest's carpet contents (what's left after our last visit), keyed
     * by position, for the CURRENT world only. Lets later runs skip chests known to hold
     * nothing we need. Persisted to disk per server+dimension (see {@link #diskModel}).
     */
    private static final Map<BlockPos, Map<Item, Integer>> chestMemory = new HashMap<>();
    /** The chest opened in the current cycle (for recording memory on close). */
    private static BlockPos currentChestPos = null;

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
        loadDisk();
        // Bind chest memory to the joined world; flush + unbind on disconnect.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ensureWorld(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            chestMemory.clear();
            worldKey = null;
        });
    }

    public static boolean isActive() {
        return active;
    }

    public static void stop() {
        active = false;
        markerChests.clear();
    }

    /**
     * Runs a balance first (so the proportional layout is in place and the right
     * proportions are gathered every time), then walks nearby chests to top the
     * stacks up. Returns false (with a chat message) if there's nothing to do.
     */
    public static boolean start(MinecraftClient client) {
        if (client.player == null) return false;
        if (active) {
            feedback(client, "§e" + TAG + " Carpet fill already running.");
            return false;
        }
        ensureWorld(client);   // bind chest memory to the current world (handles dimension changes)

        // Validate we have demand before opening anything (the goal itself is
        // (re)built after the balance pass, since balance rearranges inventory).
        Map<Item, Integer> materials;
        try {
            materials = CarpetBalanceHandler.carpetDemand(client);
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

        active     = true;
        state      = State.BALANCE_WAIT;
        cooldown   = 0;
        openWait   = 0;
        totalTicks = 0;
        visited.clear();
        CarpetBalanceHandler.activate(client);   // arrange the proportional layout first
        feedback(client, "§a" + TAG + " Balancing, then filling from nearby chests…");
        return true;
    }

    /** Builds the fill goal from current (post-balance) inventory; false if nothing to fill. */
    private static boolean computeFillGoal(MinecraftClient client) {
        Map<Item, Integer> materials;
        try {
            materials = CarpetBalanceHandler.carpetDemand(client);
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

        switch (state) {
            case BALANCE_WAIT -> balanceWaitStep(client);
            case SCAN -> {
                if (cooldown > 0) { cooldown--; return; }
                scanStep(client);
            }
            case APPROACH -> approachStep(client);
            case WAIT_OPEN -> waitOpenStep(client);
            case GRAB -> {
                if (cooldown > 0) { cooldown--; return; }
                grabStep(client);
            }
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
        // Stop as soon as the inventory can't hold any more of what we need — don't
        // keep opening (even unknown) chests once every goal slot is full.
        if (totalNeed(client.player.getInventory()) == 0) {
            finish(client, "§a" + TAG + " Carpet fill done — inventory full of the needed "
                    + "carpets (" + visited.size() + " chest" + (visited.size() == 1 ? "" : "s") + " opened).");
            return;
        }
        BlockPos chest = findNextChest(client);   // also refreshes markerChests
        if (chest == null) {
            finish(client, "§a" + TAG + " Carpet fill done — filled "
                    + (initialNeed - totalNeed(client.player.getInventory())) + " carpets"
                    + " (" + visited.size() + " chest" + (visited.size() == 1 ? "" : "s") + " opened)."
                    + (totalNeed(client.player.getInventory()) > 0
                        ? " §7No more reachable chests with what's needed." : ""));
            return;
        }
        currentChestPos = chest;
        approachTimer = 0;
        state = State.APPROACH;   // walk into reach (or open immediately if already close)
    }

    private static void approachStep(MinecraftClient client) {
        if (totalNeed(client.player.getInventory()) == 0) {
            finish(client, "§a" + TAG + " Carpet fill done — inventory full of the needed carpets.");
            return;
        }
        if (currentChestPos == null
                || !(client.world.getBlockEntity(currentChestPos) instanceof ChestBlockEntity)) {
            state = State.COOLDOWN;   // chest gone / invalid — rescan
            cooldown = BETWEEN_CHESTS_TICKS;
            return;
        }
        double distSq = client.player.squaredDistanceTo(
                currentChestPos.getX() + 0.5, currentChestPos.getY() + 0.5, currentChestPos.getZ() + 0.5);
        if (distSq <= REACH_SQ) {
            visited.add(currentChestPos);
            openWait = 0;
            openAttempts = 1;
            openChest(client, currentChestPos);
            state = State.WAIT_OPEN;
            return;
        }
        // Out of reach — guide the player to it and wait (don't skip).
        client.inGameHud.setOverlayMessage(Text.literal(String.format(
                "§e%s Walk to chest §f%d,%d,%d§e — §f%.0f§e blocks (need %d)",
                TAG, currentChestPos.getX(), currentChestPos.getY(), currentChestPos.getZ(),
                Math.sqrt(distSq), totalNeed(client.player.getInventory()))), false);
        if (++approachTimer > APPROACH_TIMEOUT_TICKS) {
            System.out.println(TAG + " Gave up walking to chest at " + currentChestPos
                    + " after ~" + (APPROACH_TIMEOUT_TICKS / 20) + "s — skipping.");
            visited.add(currentChestPos);   // don't keep re-selecting it this run
            state = State.COOLDOWN;
            cooldown = BETWEEN_CHESTS_TICKS;
        }
    }

    private static void waitOpenStep(MinecraftClient client) {
        if (isContainerOpen(client)) {
            // Wait a beat before reading: the container's contents arrive a tick or
            // more after the open packet, so reading immediately can see an empty
            // chest and wrongly record it as empty.
            state = State.GRAB;
            cooldown = SETTLE_TICKS;
            return;
        }
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
        ItemStack cursor = h.getCursorStack();

        if (cursor.isEmpty()) {
            if (totalNeed(inv) == 0) { closeAndCooldown(client); return; }
            int cs = firstNeededChestSlot(h, inv);
            if (cs == -1) { closeAndCooldown(client); return; }  // nothing useful in this chest
            click(client, h, cs);                                 // pick up the whole chest stack
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
        recordChestMemory(client, client.player.currentScreenHandler, client.player.getInventory());
        client.player.closeHandledScreen();   // cursor is always empty at this point
        state = State.COOLDOWN;
        cooldown = BETWEEN_CHESTS_TICKS;
    }

    /** Remembers the chest's remaining carpet contents (after our grab) for this session. */
    private static void recordChestMemory(MinecraftClient client, ScreenHandler h, PlayerInventory inv) {
        if (currentChestPos == null) return;
        Map<Item, Integer> contents = new HashMap<>();
        int containerSlots = 0;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot slot = h.getSlot(i);
            if (slot.inventory == inv) continue;                 // chest slots only
            containerSlots++;
            ItemStack s = slot.getStack();
            if (s.isEmpty() || !CarpetBalanceHandler.isCarpet(s.getItem())) continue;
            contents.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        chestMemory.put(currentChestPos, contents);

        // A double chest is two block entities sharing one inventory (54 slots). Mark
        // the connected half visited and remember it too, so we don't reopen the same
        // storage as if it were a separate chest.
        if (containerSlots > 27 && client.world != null) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos n = currentChestPos.offset(d);
                BlockState ns = client.world.getBlockState(n);
                if (ns.getBlock() instanceof ChestBlock && ns.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                        && client.world.getBlockEntity(n) instanceof ChestBlockEntity) {
                    visited.add(n);
                    chestMemory.put(n, contents);
                }
            }
        }
        saveDisk();
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
            chestMemory.put(p, contents);
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
     * Picks the next chest to head for, searching out to {@link #DETECT_RANGE} (not just
     * arm's reach — the APPROACH state walks the player in). Unknown chests are preferred
     * — nearest first — so memory gets built; once none remain, the nearest known chest
     * that still holds a needed color is chosen. Known chests with nothing we need are
     * skipped. Also refreshes {@link #markerChests} (every candidate worth visiting) for
     * the in-world markers.
     */
    private static BlockPos findNextChest(MinecraftClient client) {
        PlayerInventory inv = client.player.getInventory();
        BlockPos origin = client.player.getBlockPos();
        BlockPos bestUnknown = null; double bestUnknownDist = Double.MAX_VALUE;
        BlockPos bestKnown   = null; double bestKnownDist   = Double.MAX_VALUE;
        markerChests.clear();

        for (int dx = -DETECT_RANGE; dx <= DETECT_RANGE; dx++) {
            for (int dy = -DETECT_RANGE; dy <= DETECT_RANGE; dy++) {
                for (int dz = -DETECT_RANGE; dz <= DETECT_RANGE; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (visited.contains(pos)) continue;
                    if (!(client.world.getBlockEntity(pos) instanceof ChestBlockEntity)) continue;
                    double distSq = client.player.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

                    Map<Item, Integer> known = chestMemory.get(pos);
                    boolean worth = (known == null) || chestHasNeeded(known, inv);
                    if (!worth) continue;
                    markerChests.add(pos.toImmutable());
                    if (known == null) {
                        if (distSq < bestUnknownDist) { bestUnknownDist = distSq; bestUnknown = pos.toImmutable(); }
                    } else if (distSq < bestKnownDist) {
                        bestKnownDist = distSq; bestKnown = pos.toImmutable();
                    }
                }
            }
        }
        return bestUnknown != null ? bestUnknown : bestKnown;
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
        active = false;
        markerChests.clear();
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
}
