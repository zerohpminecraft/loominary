package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Autonomously prints a Litematica carpet floor: walks the unbuilt region in serpentine
 * order with the Litematica printer on, laying carpets, notices when it's running low, walks
 * to the chests and restocks, and resumes — until the placement is done. No human in the loop.
 *
 * <p>Movement is straight-line steering ({@link WaypointMover}); placement is the continuous
 * Litematica printer ({@link LitematicaBridge#setPrinter}); the serpentine path + carpet
 * demand come from {@link CarpetBalanceHandler#computePrintPath}; restocking delegates to
 * {@link CarpetFillHandler} in autonomous mode (it walks the player to chests itself). The
 * printer is held on <em>only</em> while {@code PRINTING} — forced off in every other state
 * and on stop — so we never auto-place while navigating or restocking.
 */
public final class AutoPrintHandler {

    private static final String TAG = "[Loominary]";

    private static final int   WATCHDOG_TICKS       = 72000;  // ~1h hard stop
    private static final float PRINT_PITCH          = 35.0f;  // look down so floor cells are in front
    private static final int   MAX_RESTOCK_ATTEMPTS = 3;      // give up if we can't cover the load

    // Adaptive print pacing. Look at a forward window (LOOKAHEAD deep × band-width wide) and
    // scale the duty-cycle pause to the real cost of printing it:
    //   • placement cost — how many cells we can actually print there (cells whose colour we
    //     still have; cells we can't print get skipped, so we don't slow for them);
    //   • swap cost — distinct colours beyond the printer's hotbar capacity force item swaps to
    //     the hotbar, which steal placement ticks, so they slow us further.
    // off = maxOff · clamp(DENSITY_WEIGHT·density^DENSITY_EXP + COLOR_WEIGHT·colourScore). maxOff
    // is /loominary walk's off (the configured slowest); a dense, single-colour band stays a bit
    // faster than a dense, many-colour one.
    private static final int    LOOKAHEAD      = 5;     // blocks ahead to weigh
    private static final double DENSITY_EXP    = 1.0;   // 1 = linear; <1 slows sooner, >1 later
    private static final double DENSITY_WEIGHT = 0.85;  // density's share of full slowdown
    private static final double COLOR_WEIGHT   = 0.5;   // colour-swap share (pushes to full slow)
    private static final int    SWAP_RANGE     = 4;     // colours over capacity to reach full swap cost

    // Missed-cell recovery: catch a cell we've passed out of printer range that's still unbuilt and
    // printable, and dart back (full speed) to lay it — stops a few skipped edge cells compounding
    // into long diagonal strips that never get revisited.
    private static final int RECOVERY_RADIUS    = 12;   // how far back/aside to look for a miss
    private static final int MISS_SCAN_INTERVAL = 8;    // ticks between miss scans
    private static final int RECOVER_TIMEOUT    = 60;   // give up on one miss after this
    private static final int MAX_WRONG_BREAKS   = 3;    // break a wrong-coloured carpet at most this often

    private enum State { PLAN, RESTOCK_CHECK, CATALOGUE, RESTOCK, PRINTING, DONE }

    private static boolean active = false;
    private static State   state  = State.DONE;
    private static int     totalTicks = 0;

    private static List<BlockPos> waypoints = null;
    private static Map<Item, Integer> demand = null;
    private static Map<Long, Item> unbuilt = null;   // this load's unbuilt cells → colour, for pacing
    private static int  planY = 0;                    // the load's Y layer
    private static int  planMinX, planMaxX, planBandW;   // band geometry for recovery
    private static boolean planEastSide;
    private static int  wpIndex = 0;
    private static boolean printerEngaged = false;
    private static boolean positioning = false;   // travelling to the sweep start with the printer off

    // Window-focus pause suppression. Vanilla opens the pause menu ~500 ms after the window loses
    // focus (GameRenderer.render checks isWindowFocused() && options.pauseOnLostFocus), which halts
    // input and the printer. We clear pauseOnLostFocus for the session and restore it on stop.
    private static boolean pauseOverridden = false;
    private static boolean savedPauseOnLostFocus = false;
    private static boolean wasWindowFocused = true;   // for releasing the cursor grab on alt-tab

    // Missed-cell recovery state.
    private static boolean recovering = false;
    private static BlockPos recoverTarget = null;
    private static int      recoverTimer = 0;
    private static int      missScanCooldown = 0;
    private static int      wrongBreaks = 0;       // wrong carpets broken at the current target
    private static boolean  breakingWrong = false; // mid-break of a wrong carpet at the current target
    private static final Set<Long> recoverSkip = new HashSet<>();   // misses we gave up on this load

    private static boolean restockPending = false;
    private static int     restockAttempts = 0;
    private static boolean triedCatalogue = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoPrintHandler::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> hardStop());
    }

    public static boolean isActive() { return active; }

    public static boolean start(MinecraftClient client) {
        if (client.player == null) return false;
        if (active) { feedback(client, "§e" + TAG + " Auto-print already running."); return false; }
        if (!LitematicaBridge.litematicaPresent()) {
            feedback(client, "§c" + TAG + " Litematica is not installed.");
            return false;
        }
        if (!LitematicaBridge.printerAvailable()) {
            feedback(client, "§e" + TAG + " Warning: no Litematica printer found — carpets won't be "
                    + "placed. Install the litematica-printer fork. Walking anyway.");
        }
        active = true;
        state = State.PLAN;
        totalTicks = 0;
        waypoints = null;
        demand = null;
        wpIndex = 0;
        printerEngaged = false;
        positioning = false;
        // Don't let the game open the pause menu (and freeze the printer) when you tab away.
        if (!pauseOverridden) {
            savedPauseOnLostFocus = client.options.pauseOnLostFocus;
            pauseOverridden = true;
        }
        client.options.pauseOnLostFocus = false;
        wasWindowFocused = client.isWindowFocused();
        restockPending = false;
        restockAttempts = 0;
        triedCatalogue = false;
        feedback(client, "§a" + TAG + " Auto-print started — serpentine band "
                + CarpetBalanceHandler.getBandWidth() + " wide.");
        return true;
    }

    public static void stop(MinecraftClient client) {
        boolean wasActive = active;
        if (CarpetFillHandler.isActive()) CarpetFillHandler.stop();
        hardStop();
        if (wasActive && client != null) feedback(client, "§e" + TAG + " Auto-print stopped.");
    }

    /** Drop everything and make sure the printer/mover are off. Safe to call any time. */
    private static void hardStop() {
        active = false;
        state = State.DONE;
        disengagePrinter();
        WaypointMover.stop();
        WaypointMover.clearPitch();
        WaypointMover.clearDutyCycle();
        unbuilt = null;
        recovering = false;
        recoverTarget = null;
        positioning = false;
        if (pauseOverridden) {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c != null && c.options != null) c.options.pauseOnLostFocus = savedPauseOnLostFocus;
            pauseOverridden = false;
        }
    }

    private static void onTick(MinecraftClient client) {
        if (!active) return;
        if (client.player == null || client.world == null) { hardStop(); return; }
        if (++totalTicks > WATCHDOG_TICKS) {
            feedback(client, "§e" + TAG + " Auto-print timed out.");
            hardStop();
            return;
        }

        updateCursorFocus(client);

        switch (state) {
            case PLAN          -> planStep(client);
            case RESTOCK_CHECK -> restockCheckStep(client);
            case CATALOGUE     -> catalogueStep(client);
            case RESTOCK       -> restockStep(client);
            case PRINTING      -> printingStep(client);
            case DONE          -> hardStop();
        }
    }

    private static void planStep(MinecraftClient client) {
        disengagePrinter();   // never plan with the printer running
        CarpetBalanceHandler.PrintPlan plan = CarpetBalanceHandler.computePrintPath(client);
        if (plan == null || plan.waypoints.isEmpty()) {
            feedback(client, "§a" + TAG + " Auto-print done — nothing left to build nearby.");
            hardStop();
            return;
        }
        waypoints = plan.waypoints;
        demand = plan.demand;
        unbuilt = plan.unbuilt;
        planY = plan.y;
        planMinX = plan.minX; planMaxX = plan.maxX; planBandW = plan.bandW; planEastSide = plan.eastSide;
        wpIndex = 0;
        recovering = false;
        recoverTarget = null;
        recoverSkip.clear();
        state = State.RESTOCK_CHECK;
    }

    private static void restockCheckStep(MinecraftClient client) {
        if (inventoryCovers(client, demand)) {
            enterPrinting(client);
            return;
        }
        // First shortfall: catalogue the storage so every chest's contents are known — but ONLY if
        // the persisted memory doesn't already know a chest for every needed color. Once the
        // storage has been catalogued once (and saved to disk), later runs — including after a game
        // restart — load that memory and skip straight to restock instead of re-cataloguing.
        if (!triedCatalogue) {
            triedCatalogue = true;
            if (!CarpetFillHandler.chestMemoryCovers(client, demand)) {
                disengagePrinter();
                if (CarpetFillHandler.startCatalogue(client)) {
                    state = State.CATALOGUE;
                    return;
                }
            }
        }
        // Not enough carpet for this whole load — restock before laying any of it, so we never
        // run dry mid-load. Bail out if repeated restocks can't gather what's needed.
        if (++restockAttempts > MAX_RESTOCK_ATTEMPTS) {
            feedback(client, "§c" + TAG + " Auto-print: couldn't gather enough carpet (out of stock, "
                    + "or no chests nearby). Stopping.");
            hardStop();
            return;
        }
        restockPending = true;
        state = State.RESTOCK;
    }

    private static void catalogueStep(MinecraftClient client) {
        disengagePrinter();
        WaypointMover.clearDutyCycle();              // chest navigation runs at full speed
        if (CarpetFillHandler.isActive()) return;   // wait while it walks + opens every chest
        state = State.RESTOCK_CHECK;                 // memory populated — re-check the SAME load
    }

    private static void restockStep(MinecraftClient client) {
        disengagePrinter();
        WaypointMover.clearDutyCycle();              // chest navigation runs at full speed
        if (restockPending) {
            restockPending = false;
            CarpetFillHandler.start(client, true, demand);   // gather exactly the planned load
            // Whether or not a fill started, fall through to re-check the SAME load below; the
            // RESTOCK_CHECK attempt cap stops us if we genuinely can't gather it.
            return;
        }
        if (CarpetFillHandler.isActive()) return;   // wait for the fill to walk + loot
        // Re-check the SAME demand we restocked for — NOT a fresh plan. Recomputing here keyed
        // the serpentine off the player's post-restock position (now at the chests), producing a
        // different load the just-filled inventory couldn't cover → endless restock loop.
        state = State.RESTOCK_CHECK;
    }

    private static void enterPrinting(MinecraftClient client) {
        restockAttempts = 0;
        wpIndex = 0;
        state = State.PRINTING;
        // Travel to the sweep start with the printer OFF — after a restock the player is at the
        // chests, and printing the whole way back across unbuilt floor would corrupt the plan's
        // demand/unbuilt math. The printer engages only on arrival at waypoints.get(0).
        positioning = true;
        disengagePrinter();
        WaypointMover.setPitch(PRINT_PITCH);
        WaypointMover.clearDutyCycle();   // full speed to the start; the sweep sets its own pace
        WaypointMover.setWaypoint(waypoints.get(0));
    }

    private static void printingStep(MinecraftClient client) {
        if (waypoints == null || wpIndex >= waypoints.size()) {
            // Finished this load's path — replan; the frontier has advanced as the printer
            // filled in the floor, so the next plan covers fresh ground (or none).
            state = State.PLAN;
            return;
        }
        if (positioning) { positioningStep(client); return; }
        engagePrinter();   // idempotent; re-assert in case anything toggled it off

        if (recovering) { recoverStep(client); return; }

        // Did we leave an unbuilt cell in a band the sweep already passed? Dart back for it before
        // carrying on, so skipped edge cells don't pile up on the worked side and stall the frontier.
        if (--missScanCooldown <= 0) {
            missScanCooldown = MISS_SCAN_INTERVAL;
            BlockPos missed = findMissedCell(client);
            if (missed != null) {
                recovering = true;
                recoverTarget = missed;
                recoverTimer = 0;
                wrongBreaks = 0;
                breakingWrong = false;
                WaypointMover.clearDutyCycle();            // full speed back to it
                WaypointMover.setWaypoint(missed);
                return;
            }
        }

        WaypointMover.setDutyTimings(AutoWalkHandler.getOnTicks(), adaptiveOff(client));   // pace by workload ahead

        if (WaypointMover.stuck()) {
            // Straight-line steering gave up. Replan from the current position — the serpentine
            // re-keys off where we are, so a fresh path may route around the obstruction.
            System.out.println(TAG + " Auto-print mover stuck near " + waypoints.get(wpIndex)
                    + " — replanning.");
            WaypointMover.stop();
            state = State.PLAN;
            return;
        }
        if (WaypointMover.arrived()) {
            wpIndex++;
            if (wpIndex < waypoints.size()) WaypointMover.setWaypoint(waypoints.get(wpIndex));
            return;
        }
        if (!WaypointMover.isActive()) {
            // Mover idled without arriving (e.g. after a stop) — re-issue the current target.
            WaypointMover.setWaypoint(waypoints.get(wpIndex));
        }
    }

    /** Walk to the sweep start with the printer off; engage and begin printing only on arrival. */
    private static void positioningStep(MinecraftClient client) {
        disengagePrinter();   // stay off the whole way to the start — no printing across the floor
        if (WaypointMover.stuck()) {
            WaypointMover.stop();
            state = State.PLAN;            // couldn't reach the start — replan from here
            return;
        }
        if (WaypointMover.arrived()) {
            positioning = false;
            engagePrinter();
            // Pace the sweep on the shared /loominary walk duty cycle so the printer keeps up.
            WaypointMover.setDutyCycle(AutoWalkHandler.getOnTicks(), AutoWalkHandler.getOffTicks());
            if (waypoints.size() > 1) {
                wpIndex = 1;
                WaypointMover.setWaypoint(waypoints.get(1));   // sweep toward the band's far end
            } else {
                WaypointMover.setWaypoint(waypoints.get(0));
            }
            return;
        }
        if (!WaypointMover.isActive()) WaypointMover.setWaypoint(waypoints.get(0));
    }

    /**
     * Walk back to a missed cell until the printer lays it (or we time out). A cell already occupied
     * by the <em>wrong</em> carpet (a printer-fork glitch) is broken so the printer gets another go.
     * When the cell resolves, chain straight to the next nearby miss instead of resuming the sweep —
     * so a whole skipped patch is cleared in one excursion rather than thrashing back and forth.
     */
    private static void recoverStep(MinecraftClient client) {
        long key = CarpetBalanceHandler.cellKey(recoverTarget.getX(), recoverTarget.getZ());
        Item expected = unbuilt.get(key);
        boolean done = expected == null || isBuilt(client, recoverTarget, expected);
        boolean giveUp = ++recoverTimer > RECOVER_TIMEOUT || WaypointMover.stuck()
                || wrongBreaks >= MAX_WRONG_BREAKS;
        if (done || giveUp) {
            if (!done) recoverSkip.add(key);   // couldn't lay it — don't keep darting back forever
            advanceRecovery(client);
            return;
        }
        double reachSq = LitematicaBridge.printerRange() * LitematicaBridge.printerRange();
        double distSq = client.player.squaredDistanceTo(
                recoverTarget.getX() + 0.5, recoverTarget.getY() + 0.5, recoverTarget.getZ() + 0.5);
        if (distSq > reachSq) {
            if (!WaypointMover.isActive()) WaypointMover.setWaypoint(recoverTarget);
            return;
        }
        // In reach — hold position and let the printer lay it. If the cell is occupied by the wrong
        // carpet, break it first so the printer can try again on the now-empty cell.
        WaypointMover.stop();
        Item world = worldItemAt(client, recoverTarget);
        if (world != null && world != expected && CarpetBalanceHandler.isCarpet(world)) {
            breakBlockAt(client, recoverTarget);
            breakingWrong = true;
        } else if (breakingWrong) {
            wrongBreaks++;          // a wrong carpet we were breaking has just cleared
            breakingWrong = false;
        }
    }

    /** Chain to the next nearby miss (fixing the whole patch in one trip), else resume the sweep. */
    private static void advanceRecovery(MinecraftClient client) {
        wrongBreaks = 0;
        breakingWrong = false;
        BlockPos next = findMissedCell(client);
        if (next != null) {
            recoverTarget = next;
            recoverTimer = 0;
            WaypointMover.clearDutyCycle();   // full speed to the next miss
            WaypointMover.setWaypoint(next);
            return;
        }
        recovering = false;
        recoverTarget = null;
        missScanCooldown = 0;                 // re-scan promptly once we're back on the line
        if (waypoints != null && wpIndex < waypoints.size()) {
            WaypointMover.setWaypoint(waypoints.get(wpIndex));   // resume the serpentine
        }
    }

    private static Item worldItemAt(MinecraftClient client, BlockPos pos) {
        return client.world == null ? null : client.world.getBlockState(pos).getBlock().asItem();
    }

    /** Aim at and mine the carpet at {@code pos} (it breaks in a few ticks). */
    private static void breakBlockAt(MinecraftClient client, BlockPos pos) {
        if (client.interactionManager == null || client.player == null) return;
        double ex = client.player.getX(), ey = client.player.getEyeY(), ez = client.player.getZ();
        double dx = pos.getX() + 0.5 - ex, dy = pos.getY() + 0.5 - ey, dz = pos.getZ() + 0.5 - ez;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        client.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        client.player.setPitch((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
        // updateBlockBreakingProgress self-starts the break when not already mining this cell.
        client.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * The nearest genuinely-missed cell: one still unbuilt (a colour we hold) that the sweep has
     * <em>already gone past</em>. Two cases, both from the plan's geometry: a cell in a band below the
     * one the player is in (the sweep runs band 0 — the working edge — inward, so lower bands are on
     * the worked side and won't be revisited); or a cell in the <em>current</em> band that the player
     * has walked past out of printer range (its offset projects behind the travel direction by more
     * than the printer's reach) — this catches a strip skipped on the current pass immediately rather
     * than one band later, on the return. Cells ahead, within reach, or in not-yet-reached bands are
     * excluded (they get printed normally). No east/west assumption — it follows whichever way this
     * load's sweep actually goes. Null if none nearby.
     */
    private static BlockPos findMissedCell(MinecraftClient client) {
        if (unbuilt == null || unbuilt.isEmpty() || client.player == null) return null;
        Set<Item> have = availableCarpetColors(client);
        if (have.isEmpty()) return null;

        int bx = client.player.getBlockX(), bz = client.player.getBlockZ();
        int curBand = CarpetBalanceHandler.bandOf(bx, planMinX, planMaxX, planBandW, planEastSide);

        double px = client.player.getX(), pz = client.player.getZ();
        double[] fwd = forwardVector(client);
        double fx = fwd[0], fz = fwd[1];
        double range = LitematicaBridge.printerRange();
        double radSq = RECOVERY_RADIUS * RECOVERY_RADIUS;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -RECOVERY_RADIUS; dx <= RECOVERY_RADIUS; dx++) {
            for (int dz = -RECOVERY_RADIUS; dz <= RECOVERY_RADIUS; dz++) {
                int cx = bx + dx, cz = bz + dz;
                int cellBand = CarpetBalanceHandler.bandOf(cx, planMinX, planMaxX, planBandW, planEastSide);
                if (cellBand > curBand) continue;   // upcoming band — printed normally on a later sweep
                if (cellBand == curBand) {
                    // Same band the player is in: a miss only once we've walked past it out of printer
                    // range. Project the cell onto the travel direction — ahead or still within reach
                    // means it'll be printed normally, so don't dart back for it yet. This catches a
                    // skipped strip the moment it leaves range instead of waiting for the next band.
                    double along = (cx + 0.5 - px) * fx + (cz + 0.5 - pz) * fz;
                    if (along > -range) continue;
                }
                long key = CarpetBalanceHandler.cellKey(cx, cz);
                if (recoverSkip.contains(key)) continue;
                Item expected = unbuilt.get(key);
                if (expected == null || !have.contains(expected)) continue;
                double rx = (cx + 0.5) - px, rz = (cz + 0.5) - pz;
                double distSq = rx * rx + rz * rz;
                if (distSq > radSq) continue;
                BlockPos pos = new BlockPos(cx, planY, cz);
                if (isBuilt(client, pos, expected)) continue;   // already placed — not a miss
                if (distSq < bestSq) { bestSq = distSq; best = pos; }
            }
        }
        return best;
    }

    private static boolean isBuilt(MinecraftClient client, BlockPos pos, Item expected) {
        return client.world != null && client.world.getBlockState(pos).getBlock().asItem() == expected;
    }

    /** Unit vector of travel: toward the current waypoint, else the player's facing. */
    private static double[] forwardVector(MinecraftClient client) {
        double px = client.player.getX(), pz = client.player.getZ();
        double fx = 0, fz = 0;
        if (waypoints != null && wpIndex < waypoints.size()) {
            BlockPos wp = waypoints.get(wpIndex);
            fx = (wp.getX() + 0.5) - px;
            fz = (wp.getZ() + 0.5) - pz;
        }
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 0.1) {
            double yaw = Math.toRadians(client.player.getYaw());
            return new double[]{-Math.sin(yaw), Math.cos(yaw)};
        }
        return new double[]{fx / len, fz / len};
    }

    /**
     * Duty-cycle pause for this tick, scaled to the cost of printing the band just ahead — both
     * its placement density (only cells whose colour we still hold) and its colour-swap overhead.
     * Crawls through dense, many-colour fresh floor; sprints across empty/built/single-colour or
     * unprintable stretches.
     */
    private static int adaptiveOff(MinecraftClient client) {
        int maxOff = AutoWalkHandler.getOffTicks();
        if (maxOff <= 0 || unbuilt == null || unbuilt.isEmpty() || client.player == null) return 0;
        int half = Math.max(0, CarpetBalanceHandler.getBandWidth() / 2);
        int maxCells = LOOKAHEAD * (2 * half + 1);
        if (maxCells <= 0) return 0;

        Set<Item> have = availableCarpetColors(client);
        Set<Item> coloursAhead = new HashSet<>();
        int printable = printableCellsAhead(client, half, have, coloursAhead);

        double density = printable / (double) maxCells;
        int slots = LitematicaBridge.printerHotbarSlots();          // ≤ 9; colours beyond this swap
        double colourScore = Math.min(1.0, Math.max(0, coloursAhead.size() - slots) / (double) SWAP_RANGE);

        double load = Math.min(1.0,
                DENSITY_WEIGHT * Math.pow(density, DENSITY_EXP) + COLOR_WEIGHT * colourScore);
        return (int) Math.round(maxOff * load);
    }

    /**
     * Counts unbuilt cells in a forward window (LOOKAHEAD deep × band-width wide) whose colour we
     * still hold — i.e. cells the printer can actually place now — and collects their distinct
     * colours into {@code coloursAhead}. Cells we lack carpet for are ignored (they won't print).
     */
    private static int printableCellsAhead(MinecraftClient client, int half, Set<Item> have,
                                           Set<Item> coloursAhead) {
        double px = client.player.getX();
        double pz = client.player.getZ();
        double[] fwd = forwardVector(client);
        double fx = fwd[0], fz = fwd[1];
        double rx = -fz, rz = fx;   // perpendicular ("right"), spans the band width

        int count = 0;
        for (int d = 1; d <= LOOKAHEAD; d++) {
            for (int w = -half; w <= half; w++) {
                int cx = (int) Math.floor(px + d * fx + w * rx);
                int cz = (int) Math.floor(pz + d * fz + w * rz);
                Item colour = unbuilt.get(CarpetBalanceHandler.cellKey(cx, cz));
                if (colour == null || !have.contains(colour)) continue;
                // Skip cells the printer has already filled — the plan's unbuilt map is a static
                // snapshot, so without this freshly-laid (and pre-existing) floor keeps reading as
                // work to do and we crawl over ground that's actually done.
                if (isBuilt(client, new BlockPos(cx, planY, cz), colour)) continue;
                count++;
                coloursAhead.add(colour);
            }
        }
        return count;
    }

    /** Carpet colours the player currently holds at least one of (0–35). */
    private static Set<Item> availableCarpetColors(MinecraftClient client) {
        Set<Item> have = new HashSet<>();
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i <= 35; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && CarpetBalanceHandler.isCarpet(s.getItem())) have.add(s.getItem());
        }
        return have;
    }

    /** True if the player's inventory holds at least {@code demand} of every needed carpet. */
    private static boolean inventoryCovers(MinecraftClient client, Map<Item, Integer> demand) {
        if (demand == null || demand.isEmpty()) return true;
        PlayerInventory inv = client.player.getInventory();
        Map<Item, Integer> have = new HashMap<>();
        for (int i = 0; i <= 35; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && CarpetBalanceHandler.isCarpet(s.getItem())) {
                have.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<Item, Integer> e : demand.entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    private static void engagePrinter() {
        if (!printerEngaged) {
            LitematicaBridge.setPrinter(true);
            printerEngaged = true;
        }
    }

    private static void disengagePrinter() {
        if (printerEngaged) {
            LitematicaBridge.setPrinter(false);
            printerEngaged = false;
        }
    }

    /**
     * Release the cursor grab when the window loses focus, recapture it on return. With the pause
     * menu suppressed (see {@code pauseOnLostFocus}), nothing opens a screen to free the pointer on
     * alt-tab, so the GLFW cursor stays grabbed (DISABLED) and the mouse seems stuck to the game.
     * {@code unlockCursor()} drops the grab without opening a screen, so the printer keeps running.
     */
    private static void updateCursorFocus(MinecraftClient client) {
        boolean focused = client.isWindowFocused();
        if (focused == wasWindowFocused) return;
        wasWindowFocused = focused;
        if (client.mouse == null) return;
        if (!focused) {
            client.mouse.unlockCursor();                 // free the OS pointer for other windows
        } else if (client.currentScreen == null) {
            client.mouse.lockCursor();                   // back in-game — recapture for mouse-look
        }
    }

    private static void feedback(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}
