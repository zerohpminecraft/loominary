package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

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
    private static final int MAX_STACK = 64;

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
            carpetCounts = readCarpetMaterials();
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
    private static int[] allocateProportionalSlots(List<Map.Entry<Item, Integer>> sorted, int n) {
        int k = sorted.size();
        int[] alloc = new int[k];
        if (k == 0 || n <= 0) return alloc;

        // Fewer slots than colors: the top-n colors each get one, rest get none.
        if (n < k) {
            for (int i = 0; i < n; i++) alloc[i] = 1;
            return alloc;
        }

        long total = 0;
        for (Map.Entry<Item, Integer> e : sorted) total += Math.max(0, e.getValue());

        if (total <= 0) {
            // Degenerate (no counts): spread as evenly as possible.
            for (int i = 0; i < n; i++) alloc[i % k]++;
            return alloc;
        }

        // Largest-remainder apportionment.
        double[] frac = new double[k];
        int assigned = 0;
        for (int i = 0; i < k; i++) {
            double quota = (double) n * Math.max(0, sorted.get(i).getValue()) / total;
            alloc[i] = (int) Math.floor(quota);
            frac[i] = quota - alloc[i];
            assigned += alloc[i];
        }
        Integer[] byFrac = new Integer[k];
        for (int i = 0; i < k; i++) byFrac[i] = i;
        java.util.Arrays.sort(byFrac, (a, b) -> Double.compare(frac[b], frac[a]));
        for (int r = 0; r < n - assigned; r++) alloc[byFrac[r]]++;

        // Guarantee every color at least one slot (n ≥ k here), stealing from the
        // largest allocation so the total stays exactly n.
        for (int i = 0; i < k; i++) {
            if (alloc[i] != 0) continue;
            int max = 0;
            for (int j = 1; j < k; j++) if (alloc[j] > alloc[max]) max = j;
            if (alloc[max] > 1) { alloc[max]--; alloc[i] = 1; }
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

    private static boolean isCarpet(Item item) {
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
    private static Map<Item, Integer> readCarpetMaterials() throws Exception {
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
