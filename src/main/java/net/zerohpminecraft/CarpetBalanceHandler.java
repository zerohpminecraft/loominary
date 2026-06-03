package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Balances the player's carpet stacks to match a Litematica schematic's
 * material list.
 *
 * <p>On {@link #activate}, it reads the currently open Litematica material list
 * (by reflection — Litematica is an optional soft dependency, not a compile
 * dependency), finds every carpet entry, and sorts them by required count. The
 * two highest-count colors ("mains", usually white + one other) get a target of
 * 3 full stacks (192); every other listed carpet color gets 2 full stacks (128).
 *
 * <p>It then opens the survival inventory and, tick by tick, rearranges the
 * player's carpets into a canonical layout:
 * <ul>
 *   <li>Each needed color is consolidated into its allotted slots (3 or 2),
 *       filled to 64 each (or fewer if the player has less — partial is fine).</li>
 *   <li>Surplus carpet (beyond the target) and carpet colors not in the material
 *       list are thrown on the ground.</li>
 *   <li>Non-carpet items (firework rockets, food, anything else) are relocated
 *       out of the way but <b>never dropped</b>.</li>
 * </ul>
 *
 * <p>Operates on {@link net.minecraft.entity.player.PlayerEntity#playerScreenHandler}
 * (syncId 0), which is always valid while the inventory screen is open. Usable
 * slots are indices 9–44 (27 main inventory + 9 hotbar); the offhand (45),
 * armor (5–8), and crafting (0–4) slots are left untouched.
 *
 * <p>Like {@link AnvilAutoFillHandler}, slot interactions are spread one click
 * per cooldown and the live inventory is re-evaluated every tick, so a desync
 * with the server self-corrects rather than corrupting the run.
 */
public class CarpetBalanceHandler {

    private static final String TAG = "[Loominary]";

    /** Usable player-inventory slot range (inclusive) in PlayerScreenHandler. */
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int LAST_HOTBAR_SLOT = 44;   // 45 = offhand (excluded)
    static final int MAX_STACK = 64;

    private static final int ACTION_COOLDOWN_TICKS = 3;
    private static final int WATCHDOG_TICKS = 400;    // abort if not done in time

    private static final int LEFT_CLICK = 0;
    private static final int RIGHT_CLICK = 1;
    private static final int OUTSIDE_SLOT = -999;     // click here to drop the cursor

    // ── Run state ──────────────────────────────────────────────────────────
    private static boolean active = false;
    private static int cooldown = 0;
    private static int watchdog = 0;

    /** slot index → the carpet Item that slot should hold. */
    private static final Map<Integer, Item> slotGoal = new HashMap<>();
    /** carpet Item → its allotted slots, in fill order. */
    private static final Map<Item, List<Integer>> colorSlots = new LinkedHashMap<>();
    /** carpet Item → how many of it the player started with (for the summary). */
    private static int droppedTotal = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CarpetBalanceHandler::onTick);
    }

    /**
     * Entry point for {@code /loominary carpets balance} and the hotkey. Reads
     * the Litematica material list, opens the inventory, and starts balancing.
     */
    public static void activate(MinecraftClient client) {
        if (client.player == null) return;

        if (active) {
            feedback(client, "§e" + TAG + " Carpet balance already running.");
            return;
        }

        Map<Item, Integer> carpetCounts;
        try {
            carpetCounts = carpetDemand(client);
        } catch (ClassNotFoundException e) {
            feedback(client, "§c" + TAG + " Litematica is not installed.");
            return;
        } catch (Exception e) {
            feedback(client, "§c" + TAG + " Couldn't read Litematica material list: "
                    + e.getMessage());
            return;
        }

        if (carpetCounts == null || carpetCounts.isEmpty()) {
            feedback(client, "§c" + TAG + " No carpet materials found. Open Litematica's "
                    + "Material List for your placement first.");
            return;
        }

        buildGoal(carpetCounts, client.player.playerScreenHandler);

        if (colorSlots.isEmpty()) {
            feedback(client, "§c" + TAG + " No free slots to lay out carpets (inventory full "
                    + "of non-carpet items?).");
            return;
        }

        // Open the survival inventory so we operate inside an open screen
        // (the hotkey fires in-game; the command closes chat — either way the
        // inventory may not be open yet).
        if (!(client.currentScreen instanceof InventoryScreen)) {
            client.setScreen(new InventoryScreen(client.player));
        }

        active = true;
        cooldown = ACTION_COOLDOWN_TICKS;
        watchdog = 0;
        droppedTotal = 0;

        int totalSlots = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, List<Integer>> e : colorSlots.entrySet()) {
            int n = e.getValue().size();
            totalSlots += n;
            sb.append("§7").append(itemName(e.getKey())).append("§8×").append(n).append(" ");
        }
        System.out.println(TAG + " Carpet balance: " + colorSlots.size() + " colors across "
                + totalSlots + " slots (proportional) — " + sb);
        feedback(client, "§a" + TAG + " Balancing " + colorSlots.size()
                + " carpet colors to the material list…");
    }

    public static boolean isActive() {
        return active;
    }

    // ── Goal construction ───────────────────────────────────────────────────

    /**
     * Scans the current inventory and assigns each carpet color a fixed set of
     * goal slots, sized <b>proportionally to its Litematica material count</b>:
     * a color that's half the total demand gets about half the usable slots.
     * Rules that keep the layout stable and non-destructive:
     * <ul>
     *   <li>Slots holding non-carpet items (rockets, food, …) are <b>off-limits</b>
     *       — they're never chosen as goal slots, so those items never have to
     *       move and can never be dropped.</li>
     *   <li>Every needed color gets <b>at least one</b> slot (when they fit), so
     *       no color is starved.</li>
     *   <li>A color is assigned, where possible, the slots it <b>already</b>
     *       occupies, so carpet that's already in place stays put and we only
     *       move what we must.</li>
     * </ul>
     */
    private static void buildGoal(Map<Item, Integer> carpetCounts, ScreenHandler handler) {
        slotGoal.clear();
        colorSlots.clear();

        // Sort by required count (desc), tie-break by registry path for determinism.
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(carpetCounts.entrySet());
        sorted.sort(Comparator
                .<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> itemId(e.getKey())));

        // Candidate slots, hotbar first (36–44) then main inv (9–35), excluding
        // any slot currently holding a non-carpet item.
        List<Integer> candidates = new ArrayList<>();
        for (int i = 36; i <= LAST_HOTBAR_SLOT; i++) addIfAssignable(handler, i, candidates);
        for (int i = FIRST_MAIN_SLOT; i <= 35; i++) addIfAssignable(handler, i, candidates);

        // How many slots each color gets, proportional to its material count.
        int[] want = allocateProportionalSlots(sorted, candidates.size());

        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int c = 0; c < sorted.size(); c++) {
            Item color = sorted.get(c).getKey();
            if (want[c] <= 0) continue;
            List<Integer> slots = new ArrayList<>();

            // Pass 1: reuse slots this color already sits in.
            for (int slot : candidates) {
                if (slots.size() >= want[c]) break;
                if (used.contains(slot)) continue;
                ItemStack s = handler.getSlot(slot).getStack();
                if (!s.isEmpty() && s.getItem() == color) slots.add(slot);
            }
            // Pass 2: fill the rest from any free candidate slot.
            for (int slot : candidates) {
                if (slots.size() >= want[c]) break;
                if (used.contains(slot) || slots.contains(slot)) continue;
                slots.add(slot);
            }

            for (int slot : slots) {
                used.add(slot);
                slotGoal.put(slot, color);
            }
            if (!slots.isEmpty()) colorSlots.put(color, slots);
        }
    }

    /**
     * Distributes {@code n} slots across the colors (already sorted by count
     * desc) proportionally to their material counts, using the largest-remainder
     * method so the parts sum to exactly {@code n}. Every color is guaranteed at
     * least one slot when there's room ({@code n} ≥ color count); if there are
     * more colors than slots, only the {@code n} highest-count colors get one.
     *
     * @return an array of slot counts aligned with {@code sorted}
     */
    static int[] allocateProportionalSlots(List<Map.Entry<Item, Integer>> sorted, int n) {
        int k = sorted.size();
        int[] alloc = new int[k];
        if (k == 0 || n <= 0) return alloc;

        // Per-color cap: never allocate more slots than the color actually needs
        // (a color needing 64 carpets gets at most one slot, regardless of how its
        // count compares to others). Total demand bounds how many slots we use.
        int[] cap = new int[k];
        long total = 0;
        int sumCap = 0;
        int withDemand = 0;
        for (int i = 0; i < k; i++) {
            int c = Math.max(0, sorted.get(i).getValue());
            total += c;
            cap[i] = (c + MAX_STACK - 1) / MAX_STACK;   // ceil(c / 64); 0 when c == 0
            sumCap += cap[i];
            if (c > 0) withDemand++;
        }
        if (total <= 0) return alloc;

        // Use only as many slots as are both available and demanded.
        int target = Math.min(n, sumCap);

        // More demanded colors than slots to give: the highest-count ones get one each
        // (sorted is count-desc).
        if (target < withDemand) {
            for (int i = 0, given = 0; i < k && given < target; i++) {
                if (cap[i] > 0) { alloc[i] = 1; given++; }
            }
            return alloc;
        }

        // Water-fill `target` slots: each goes to the color furthest below its
        // proportional ideal that hasn't hit its cap. Naturally proportional for
        // large demand, and exactly "one stack each" when demand is small.
        for (int placed = 0; placed < target; placed++) {
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < k; i++) {
                if (cap[i] == 0 || alloc[i] >= cap[i]) continue;
                double ideal = (double) target * Math.max(0, sorted.get(i).getValue()) / total;
                double score = ideal - alloc[i];
                if (score > bestScore) { bestScore = score; best = i; }
            }
            if (best == -1) break;
            alloc[best]++;
        }

        // Guarantee every demanded color at least one slot (target ≥ withDemand here),
        // stealing from the largest allocation so the total stays the same.
        for (int i = 0; i < k; i++) {
            if (cap[i] == 0 || alloc[i] > 0) continue;
            int max = -1;
            for (int j = 0; j < k; j++) if (alloc[j] > 1 && (max == -1 || alloc[j] > alloc[max])) max = j;
            if (max != -1) { alloc[max]--; alloc[i] = 1; }
        }
        return alloc;
    }

    private static void addIfAssignable(ScreenHandler handler, int slot, List<Integer> out) {
        ItemStack s = handler.getSlot(slot).getStack();
        if (s.isEmpty() || isCarpet(s)) out.add(slot); // empty or carpet → usable
    }

    // ── Tick loop ────────────────────────────────────────────────────────────

    private static void onTick(MinecraftClient client) {
        if (!active) return;

        if (client.player == null || client.interactionManager == null) {
            stop(client, null);
            return;
        }
        // Bail if the user navigated away from the inventory screen.
        if (!(client.currentScreen instanceof InventoryScreen)) {
            stop(client, "§e" + TAG + " Carpet balance cancelled (inventory closed).");
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (++watchdog > WATCHDOG_TICKS) {
            stop(client, "§e" + TAG + " Carpet balance timed out — inventory left as-is.");
            return;
        }

        ScreenHandler handler = client.player.playerScreenHandler;
        ItemStack cursor = handler.getCursorStack();

        // ── Cursor occupied: route whatever we're holding ──────────────────
        if (!cursor.isEmpty()) {
            routeCursor(client, handler, cursor);
            return;
        }

        // ── 1. Evict a wrong item sitting in a goal slot ───────────────────
        for (Map.Entry<Integer, Item> g : slotGoal.entrySet()) {
            int slot = g.getKey();
            ItemStack s = handler.getSlot(slot).getStack();
            if (s.isEmpty()) continue;
            if (s.getItem() == g.getValue()) continue; // correct color already here
            // Wrong item (foreign carpet or a non-carpet) — pick it up; the
            // cursor router will re-home or drop it next tick.
            click(client, handler, slot, LEFT_CLICK, SlotActionType.PICKUP);
            return;
        }

        // ── 2. Seed: claim every allotted slot with ≥1 carpet ──────────────
        // Each allotted slot must hold at least one carpet so the slot is
        // "reserved" for that color — only matching carpets stack into a
        // non-empty slot, so a later run through a pile fills these slots and
        // can't have another colour intrude. We seed from the loose pile when
        // possible; if a colour's carpet is all bunched into one of its own
        // goal slots (e.g. 7 brown sitting as [7, empty]), we pick that stack
        // up and let routeCursor redistribute it across the empties ([6, 1]).
        for (Item color : colorSlots.keySet()) {
            if (firstEmptyGoalSlot(handler, color) == -1) continue;
            int source = findSource(handler, color);              // loose pile first
            if (source == -1) source = findGoalDonor(handler, color); // else rebalance
            if (source != -1) {
                click(client, handler, source, LEFT_CLICK, SlotActionType.PICKUP);
                return;
            }
        }

        // ── 3. Fill: top up seeded slots toward 64 from the loose pile ─────
        for (Item color : colorSlots.keySet()) {
            if (!hasRoom(handler, color)) continue;
            int source = findSource(handler, color);
            if (source != -1) {
                click(client, handler, source, LEFT_CLICK, SlotActionType.PICKUP);
                return;
            }
        }

        // ── 4. Drop surplus / foreign carpet from non-goal slots ───────────
        // Safe by counting: drop a whole non-goal stack only if doing so still
        // leaves at least the keep amount (slots × 64) of that color in slots.
        // Foreign colors have keep 0, so they drop entirely. This guarantees we
        // never discard carpet we still need, regardless of fill ordering.
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_HOTBAR_SLOT; slot++) {
            if (slotGoal.containsKey(slot)) continue;
            ItemStack s = handler.getSlot(slot).getStack();
            if (s.isEmpty() || !isCarpet(s)) continue;
            Item color = s.getItem();
            if (totalInSlots(handler, color) - s.getCount() < keep(color)) continue;
            droppedTotal += s.getCount();
            click(client, handler, slot, RIGHT_CLICK, SlotActionType.THROW); // whole stack
            return;
        }

        // Nothing left to do.
        finish(client);
    }

    /** Places/drops the stack currently on the cursor toward the goal. */
    private static void routeCursor(MinecraftClient client, ScreenHandler handler, ItemStack cursor) {
        if (isCarpet(cursor)) {
            Item color = cursor.getItem();
            if (colorSlots.containsKey(color)) {
                // Seed any still-empty allotted slot before topping up. Holding
                // ≥2: drop a single carpet (right-click) into an empty slot and
                // keep the rest, so every empty slot ends up claimed. Holding
                // exactly 1: deposit it (it's the last carpet — claim a slot).
                int empty = firstEmptyGoalSlot(handler, color);
                if (empty != -1) {
                    int button = cursor.getCount() == 1 ? LEFT_CLICK : RIGHT_CLICK;
                    click(client, handler, empty, button, SlotActionType.PICKUP);
                    return;
                }
                // All allotted slots seeded — dump the rest into the first one
                // with room, carrying any remainder to the next tick.
                int dest = firstGoalSlotWithRoom(handler, color);
                if (dest != -1) {
                    click(client, handler, dest, LEFT_CLICK, SlotActionType.PICKUP);
                    return;
                }
                // No empty, no room. A goal slot is blocked by a different
                // carpet. Swap ours in (claiming the slot) and lift the blocker
                // onto the cursor to re-home next tick — this resolves mutual
                // blocks without needing any free slot, so we never have to
                // drop carpet we still need.
                int blocked = firstBlockedGoalSlot(handler, color);
                if (blocked != -1) {
                    click(client, handler, blocked, LEFT_CLICK, SlotActionType.PICKUP);
                    return;
                }
                // Otherwise every goal slot is full of this color — the rest is
                // genuine surplus and falls through to the safe drop below.
            }
            // Drop only when it's provably safe: we already hold at least the
            // keep amount (slots × 64) of this color in slots, so the cursor is
            // pure surplus. Foreign colors have keep 0 and always drop. If it
            // isn't safe to drop, the carpet is still needed — park it (or, as a
            // last resort, leave it on the cursor for the watchdog) rather than
            // discard it.
            if (totalInSlots(handler, color) >= keep(color)) {
                droppedTotal += cursor.getCount();
                click(client, handler, OUTSIDE_SLOT, LEFT_CLICK, SlotActionType.PICKUP);
                return;
            }
            int park = findEmptyNonGoalSlot(handler);
            if (park != -1) {
                click(client, handler, park, LEFT_CLICK, SlotActionType.PICKUP);
            }
            return;
        }
        // Non-carpet (rockets/food/etc.): never drop — park in an empty non-goal slot.
        int empty = findEmptyNonGoalSlot(handler);
        if (empty == -1) empty = findAnyEmptySlot(handler); // last resort, avoids deadlock
        if (empty != -1) {
            click(client, handler, empty, LEFT_CLICK, SlotActionType.PICKUP);
            return;
        }
        // Truly no space — abort rather than risk losing the item.
        stop(client, "§e" + TAG + " No room to set aside non-carpet items — left as-is.");
    }

    // ── Slot queries ─────────────────────────────────────────────────────────

    /** True if any allotted slot for this color holds fewer than 64. */
    private static boolean hasRoom(ScreenHandler handler, Item color) {
        return firstGoalSlotWithRoom(handler, color) != -1;
    }

    /** How many of this color we intend to keep: its allotted slot count × 64 (0 if unlisted). */
    private static int keep(Item color) {
        List<Integer> slots = colorSlots.get(color);
        return slots == null ? 0 : slots.size() * MAX_STACK;
    }

    /** Total count of this color across all usable slots (excludes the cursor). */
    private static int totalInSlots(ScreenHandler handler, Item color) {
        int n = 0;
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_HOTBAR_SLOT; slot++) {
            ItemStack s = handler.getSlot(slot).getStack();
            if (s.getItem() == color) n += s.getCount();
        }
        return n;
    }

    /** First allotted slot for this color that is blocked by a different carpet, or -1. */
    private static int firstBlockedGoalSlot(ScreenHandler handler, Item color) {
        List<Integer> slots = colorSlots.get(color);
        if (slots == null) return -1;
        for (int slot : slots) {
            ItemStack s = handler.getSlot(slot).getStack();
            if (!s.isEmpty() && s.getItem() != color) return slot;
        }
        return -1;
    }

    private static int firstGoalSlotWithRoom(ScreenHandler handler, Item color) {
        List<Integer> slots = colorSlots.get(color);
        if (slots == null) return -1;
        for (int slot : slots) {
            ItemStack s = handler.getSlot(slot).getStack();
            if (s.isEmpty()) return slot;
            if (s.getItem() == color && s.getCount() < MAX_STACK) return slot;
        }
        return -1;
    }

    /** First allotted slot for this color that is currently empty, or -1. */
    private static int firstEmptyGoalSlot(ScreenHandler handler, Item color) {
        List<Integer> slots = colorSlots.get(color);
        if (slots == null) return -1;
        for (int slot : slots) {
            if (handler.getSlot(slot).getStack().isEmpty()) return slot;
        }
        return -1;
    }

    /**
     * An allotted slot of {@code color} holding ≥2 carpets — used as a donor to
     * seed a sibling empty slot when the color's carpet is all bunched into one
     * goal slot and no loose pile remains. Picking the donor up empties it; the
     * cursor router then spreads it across the empties.
     */
    private static int findGoalDonor(ScreenHandler handler, Item color) {
        List<Integer> slots = colorSlots.get(color);
        if (slots == null) return -1;
        for (int slot : slots) {
            ItemStack s = handler.getSlot(slot).getStack();
            if (s.getItem() == color && s.getCount() >= 2) return slot;
        }
        return -1;
    }

    /**
     * A non-goal usable slot holding {@code color} that we can pull from. We
     * never pull from the color's own goal slots — carpet already sitting in a
     * goal slot is left where it is (consolidation past "occupies the slot" is
     * unnecessary, since partial stacks are acceptable).
     */
    private static int findSource(ScreenHandler handler, Item color) {
        List<Integer> goals = colorSlots.get(color);
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_HOTBAR_SLOT; slot++) {
            ItemStack s = handler.getSlot(slot).getStack();
            if (s.isEmpty() || s.getItem() != color) continue;
            if (goals != null && goals.contains(slot)) continue;
            return slot;
        }
        return -1;
    }

    private static int findEmptyNonGoalSlot(ScreenHandler handler) {
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_HOTBAR_SLOT; slot++) {
            if (slotGoal.containsKey(slot)) continue;
            if (handler.getSlot(slot).getStack().isEmpty()) return slot;
        }
        return -1;
    }

    private static int findAnyEmptySlot(ScreenHandler handler) {
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_HOTBAR_SLOT; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) return slot;
        }
        return -1;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void click(MinecraftClient client, ScreenHandler handler,
                              int slot, int button, SlotActionType type) {
        client.interactionManager.clickSlot(handler.syncId, slot, button, type, client.player);
        cooldown = ACTION_COOLDOWN_TICKS;
    }

    private static boolean isCarpet(ItemStack stack) {
        return !stack.isEmpty() && isCarpet(stack.getItem());
    }

    static boolean isCarpet(Item item) {
        return Registries.ITEM.getId(item).getPath().endsWith("_carpet");
    }

    private static String itemId(Item item) {
        return Registries.ITEM.getId(item).getPath();
    }

    private static String itemName(Item item) {
        String p = itemId(item);
        if (p.endsWith("_carpet")) p = p.substring(0, p.length() - "_carpet".length());
        return p;
    }

    private static void feedback(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }

    private static void finish(MinecraftClient client) {
        active = false;
        feedback(client, "§a" + TAG + " Carpet balance complete — dropped "
                + droppedTotal + " surplus carpet" + (droppedTotal == 1 ? "" : "s") + ".");
        System.out.println(TAG + " Carpet balance complete (dropped " + droppedTotal + ").");
    }

    private static void stop(MinecraftClient client, String msg) {
        active = false;
        if (msg != null && client != null) feedback(client, msg);
    }

    // ── Litematica material list (reflection) ─────────────────────────────────

    /**
     * Reads the currently active Litematica material list and returns the total
     * required count for each carpet item.
     *
     * @throws ClassNotFoundException if Litematica isn't on the classpath
     */
    @SuppressWarnings("unchecked")
    /**
     * The carpet demand to balance/fill against: the nearest *unplaced* carpets in
     * the schematic (capped at one inventory-load) when the placement is loaded near
     * the player, otherwise the whole-build material totals.
     */
    static Map<Item, Integer> carpetDemand(MinecraftClient client) throws Exception {
        Map<Item, Integer> local = localCarpetDemand(client);
        if (local != null && !local.isEmpty()) return local;
        return readCarpetMaterials();
    }

    private static final int LOCAL_SCAN_MAX_RADIUS = 256;

    /**
     * Counts the carpets the schematic still needs near the player — positions where
     * Litematica's schematic world has a carpet but the real world doesn't match yet —
     * capped at one inventory-load, so the gathered mix maps to a predictable build
     * frontier. Each column is swept north→south; the column order depends on which side
     * of the *unbuilt* region's east-west midpoint the player stands: on the west side it
     * sweeps west→east (NW→SE), on the east side east→west (NE→SW), so you always gather the
     * unbuilt columns nearest you first. Returns null when the schematic world isn't
     * available (Litematica absent / nothing loaded), so callers fall back to whole-build
     * totals.
     */
    private static Map<Item, Integer> localCarpetDemand(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;

        World schematic;
        try {
            Object sw = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler")
                    .getMethod("getSchematicWorld").invoke(null);
            if (!(sw instanceof World w)) return null;
            schematic = w;
        } catch (Throwable t) {
            return null;
        }

        // One inventory-load = usable carpet slots × 64.
        PlayerInventory inv = client.player.getInventory();
        int usable = 0;
        for (int i = 0; i <= 35; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || isCarpet(s.getItem())) usable++;
        }
        int cap = usable * MAX_STACK;
        if (cap <= 0) return null;

        // First, find every unbuilt carpet in the player's Y layer — positions where the
        // schematic wants a carpet the real world doesn't have yet — grouped by column (x),
        // each column kept north→south. We also track the east-west extent of that
        // *remaining* work so the band direction can key off the player's side of it.
        BlockPos origin = client.player.getBlockPos();
        int py = origin.getY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int R = LOCAL_SCAN_MAX_RADIUS;
        Map<Integer, List<Item>> byColumn = new HashMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (int x = origin.getX() - R; x <= origin.getX() + R; x++) {
            for (int z = origin.getZ() - R; z <= origin.getZ() + R; z++) {   // north → south
                pos.set(x, py, z);
                try {
                    BlockState exp = schematic.getBlockState(pos);
                    Item carpet = exp.getBlock().asItem();
                    if (!isCarpet(carpet)) continue;
                    if (client.world.getBlockState(pos).getBlock() == exp.getBlock()) continue; // already placed
                    byColumn.computeIfAbsent(x, k -> new ArrayList<>()).add(carpet);
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                } catch (Exception ignored) {
                    // unloaded schematic chunk etc. — skip
                }
            }
        }
        if (byColumn.isEmpty()) return null;

        // Band from the player's side of the *unbuilt* region: east of its midpoint sweeps
        // east→west (NE→SW), otherwise west→east (NW→SE). Each column is already north→south,
        // so we gather complete columns nearest your working edge first, up to one
        // inventory-load. Keying off the live extent (not Litematica's selected-placement
        // bounds) keeps this working without a placement selected and tracks the frontier as
        // the build fills in.
        boolean eastSide = client.player.getX() > (minX + maxX) / 2.0;
        System.out.println(TAG + " local demand: unbuilt X=[" + minX + ".." + maxX + "], playerX="
                + String.format("%.1f", client.player.getX()) + " → "
                + (eastSide ? "east side (NE→SW)" : "west side (NW→SE)"));

        Map<Item, Integer> demand = new HashMap<>();
        int taken = 0;
        for (int i = 0; i <= maxX - minX; i++) {
            int x = eastSide ? (maxX - i) : (minX + i);   // east→west on the east half, else west→east
            List<Item> column = byColumn.get(x);
            if (column == null) continue;
            for (Item carpet : column) {
                demand.merge(carpet, 1, Integer::sum);
                if (++taken >= cap) return demand;
            }
        }
        return demand.isEmpty() ? null : demand;
    }

    static Map<Item, Integer> readCarpetMaterials() throws Exception {
        Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");

        // Preferred path: compute totals fresh from the currently SELECTED
        // schematic placement. MaterialListUtils.createMaterialListFor reads the
        // schematic data directly and returns synchronously, so the counts always
        // reflect the schematic you have selected right now — no stale numbers
        // from a previously generated list, and no need to open or refresh
        // Litematica's Material List GUI by hand.
        try {
            Object spm = dataManager.getMethod("getSchematicPlacementManager").invoke(null);
            Object placement = spm.getClass().getMethod("getSelectedSchematicPlacement").invoke(spm);
            if (placement != null) {
                Object schematic = placement.getClass().getMethod("getSchematic").invoke(placement);
                if (schematic != null) {
                    Class<?> utils = Class.forName("fi.dy.masa.litematica.materials.MaterialListUtils");
                    Class<?> schemClass = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
                    Object entries = utils.getMethod("createMaterialListFor", schemClass)
                            .invoke(null, schematic);
                    Map<Item, Integer> fresh = parseCarpetCounts(entries);
                    if (fresh != null && !fresh.isEmpty()) return fresh;
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + " Couldn't build a fresh material list ("
                    + t.getMessage() + "); falling back to the loaded one.");
        }

        // Fallback: whatever material list is already loaded in Litematica
        // (e.g. the user has no placement selected but generated one manually).
        Object materialList = dataManager.getMethod("getMaterialList").invoke(null);
        if (materialList == null) return null;
        Object allObj = materialList.getClass().getMethod("getMaterialsAll").invoke(materialList);
        return parseCarpetCounts(allObj);
    }

    /** Sums total required counts per carpet item from a list of MaterialListEntry. */
    private static Map<Item, Integer> parseCarpetCounts(Object listObj) throws Exception {
        if (!(listObj instanceof List<?> entries) || entries.isEmpty()) return null;
        Map<Item, Integer> counts = new HashMap<>();
        for (Object entry : entries) {
            ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
            int total = (int) entry.getClass().getMethod("getCountTotal").invoke(entry);
            if (stack == null || stack.isEmpty() || total <= 0) continue;
            Item item = stack.getItem();
            if (!isCarpet(item)) continue;
            counts.merge(item, total, Integer::sum);
        }
        return counts;
    }
}
