package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.zerohpminecraft.command.LoominaryCommand;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.BannerItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class AnvilAutoFillHandler {

    private static final String TAG = "[Loominary]";
    private static final int ACTION_COOLDOWN_TICKS = 5;
    private static final int MIN_XP_FOR_RENAME    = 1;
    private static final int RENAME_TIMEOUT_TICKS = 60;
    private static final int MAX_RENAME_ATTEMPTS  = 3;

    private static final int LEFT_CLICK  = 0;
    private static final int RIGHT_CLICK = 1;

    // ── Extraction state (steps 1-3 across separate ticks) ─────────────────
    // Sending all three clicks in one tick triggers slot-state-ID mismatches
    // in 1.21.4; spreading them lets the server confirm each step first.
    private static int extractState = 0;  // 0=idle 1=awaiting step2 2=awaiting step3
    private static int extractSlot  = -1;

    // ── Storage state (steps 4-5 across separate ticks) ────────────────────
    // Same reason: step 5 must not fire until the server has confirmed step 4.
    // storeState 1 = step 4 sent, waiting for cursor to have the banner.
    private static int storeState = 0;

    // ── Bundle-extract state (whitelist reuse from inside a bundle) ────────
    // 0 = idle. 1 = sent BundleItemSelectedC2SPacket last tick; right-click
    // bundle this tick to pop the selected item to cursor. 2 = banner is on
    // cursor; drop it into anvil slot 0 this tick so PRIORITY 2 can rename.
    private static int bundleExtractState = 0;
    private static int bundleExtractSlot  = -1;

    // True while a banner that was already named (and was on the whitelist)
    // sits in anvil slot 0. PRIORITY 2 ignores the "must be unnamed" rule
    // when this is set, since the whole point is to overwrite the old name.
    private static boolean extractedNamed   = false;
    private static String  extractedOldName = null;

    private static String pendingName  = null;
    private static int    pendingIndex = -1;
    private static int    cooldown     = 0;
    private static int    renameTicks  = 0;
    private static int    renameAttemptCount = 0;
    private static boolean haltedForResalt   = false;

    private static boolean xpPausedLogged     = false;
    private static boolean bannerPausedLogged = false;
    private static boolean batchDoneLogged    = false;

    /** Called by /loominary resalt after re-encoding the stuck tile. */
    public static void clearHalt() {
        haltedForResalt    = false;
        renameAttemptCount = 0;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AnvilScreen)) return;
            System.out.println(TAG + " Anvil screen opened — resetting state.");
            pendingName  = null;
            pendingIndex = -1;
            cooldown     = 0;
            renameTicks  = 0;
            renameAttemptCount = 0;
            extractState = 0;
            extractSlot  = -1;
            storeState   = 0;
            bundleExtractState = 0;
            bundleExtractSlot  = -1;
            extractedNamed   = false;
            extractedOldName = null;
            noBundleWarningShown = false;
            xpPausedLogged     = false;
            bannerPausedLogged = false;
            batchDoneLogged    = false;
            // haltedForResalt is intentionally preserved across screen opens;
            // only /loominary resalt clears it.

            // If encryption is configured, apply it now to the active tile's
            // plain chunks and load the result into ACTIVE_CHUNKS.
            // Encryption is deferred to this point so editing never touches
            // encrypted bytes. The tile's canonical chunks stay plain.
            applyEncryptionForPlacement();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            if (!(client.currentScreen instanceof AnvilScreen anvilScreen)) return;

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            // ── Halt: server has permanently rejected this chunk name ───────
            if (haltedForResalt) {
                client.inGameHud.setOverlayMessage(
                        Text.literal("§c" + TAG + " Stuck — run /loominary resalt"), false);
                cooldown = 20;
                return;
            }

            if (PayloadState.ACTIVE_CHUNKS.isEmpty()) return;

            if (PayloadState.activeChunkIndex >= PayloadState.ACTIVE_CHUNKS.size()) {
                if (!batchDoneLogged) {
                    System.out.println(TAG + " Batch complete — all "
                            + PayloadState.ACTIVE_CHUNKS.size() + " banners processed.");
                    client.inGameHud.setOverlayMessage(
                            Text.literal("§a" + TAG + " Batch complete!"), false);
                    batchDoneLogged = true;
                }
                return;
            }

            if (client.player.experienceLevel < MIN_XP_FOR_RENAME) {
                if (!xpPausedLogged) {
                    System.out.println(TAG + " Paused: out of XP.");
                    xpPausedLogged = true;
                }
                client.inGameHud.setOverlayMessage(
                        Text.literal("§e" + TAG + " Paused — out of XP."), false);
                return;
            }
            if (xpPausedLogged) {
                System.out.println(TAG + " Resuming: XP restored.");
                xpPausedLogged = false;
            }

            AnvilScreenHandler handler = anvilScreen.getScreenHandler();
            ItemStack cursor    = handler.getCursorStack();
            ItemStack leftSlot  = handler.getSlot(0).getStack();
            ItemStack outputSlot = handler.getSlot(2).getStack();

            // ── Cursor safety ──────────────────────────────────────────────
            // The cursor must be empty except while we're mid-operation.
            // If anything unexpected is on it, put it back before proceeding.
            if (!cursor.isEmpty() && extractState == 0 && storeState == 0
                    && bundleExtractState == 0) {
                if (cursor.getItem() instanceof BannerItem
                        && cursor.getCustomName() != null && pendingName != null) {
                    // Renamed banner stuck on cursor — try bundle first.
                    int dst = findAvailableBundle(handler);
                    if (dst == -1) dst = findEmptySlot(handler);
                    if (dst != -1) {
                        System.out.println(TAG + " Cursor drain: renamed banner → slot " + dst);
                        client.interactionManager.clickSlot(
                                handler.syncId, dst, LEFT_CLICK, SlotActionType.PICKUP, client.player);
                        cooldown = ACTION_COOLDOWN_TICKS;
                        return;
                    }
                } else {
                    // Bundle, unnamed banner, or anything else — return to empty slot.
                    int dst = findEmptySlot(handler);
                    if (dst != -1) {
                        System.out.println(TAG + " Cursor drain: "
                                + cursor.getItem().toString() + " → slot " + dst);
                        client.interactionManager.clickSlot(
                                handler.syncId, dst, LEFT_CLICK, SlotActionType.PICKUP, client.player);
                        cooldown = ACTION_COOLDOWN_TICKS;
                        return;
                    }
                }
            }

            // ── Extraction steps 2 and 3 ────────────────────────────────────
            if (extractState == 1) {
                System.out.println(TAG + "   Step 2: RIGHT-click slot 0 → place 1 banner");
                client.interactionManager.clickSlot(
                        handler.syncId, 0, RIGHT_CLICK, SlotActionType.PICKUP, client.player);
                extractState = 2;
                cooldown = ACTION_COOLDOWN_TICKS;
                return;
            }
            if (extractState == 2) {
                System.out.println(TAG + "   Step 3: LEFT-click slot "
                        + extractSlot + " → return remaining");
                client.interactionManager.clickSlot(
                        handler.syncId, extractSlot, LEFT_CLICK, SlotActionType.PICKUP, client.player);
                extractState = 0;
                extractSlot  = -1;
                bannerPausedLogged = false;
                cooldown = ACTION_COOLDOWN_TICKS;
                return;
            }

            // ── Bundle-extract states: pop a whitelisted banner out of a bundle ─
            if (bundleExtractState == 1) {
                if (bundleExtractSlot < 0 || bundleExtractSlot >= handler.slots.size()) {
                    bundleExtractState = 0;
                    bundleExtractSlot  = -1;
                    return;
                }
                System.out.println(TAG + "   Bundle step 1: RIGHT-click slot "
                        + bundleExtractSlot + " → pop selected ('" + extractedOldName
                        + "') to cursor");
                client.interactionManager.clickSlot(
                        handler.syncId, bundleExtractSlot, RIGHT_CLICK,
                        SlotActionType.PICKUP, client.player);
                bundleExtractState = 2;
                cooldown = ACTION_COOLDOWN_TICKS;
                return;
            }
            if (bundleExtractState == 2) {
                boolean wrongItem = cursor.isEmpty()
                        || !(cursor.getItem() instanceof BannerItem)
                        || cursor.getCustomName() == null
                        || !cursor.getCustomName().getString().equals(extractedOldName);
                if (wrongItem) {
                    // Server's selected-item index didn't match ours (race or
                    // out-of-range), so the wrong banner — or something other
                    // than a banner — was popped. Park whatever's on the cursor
                    // in a free slot and abort this extraction.
                    if (!cursor.isEmpty()) {
                        int dst = findEmptySlot(handler);
                        if (dst != -1) {
                            client.interactionManager.clickSlot(
                                    handler.syncId, dst, LEFT_CLICK,
                                    SlotActionType.PICKUP, client.player);
                        }
                    }
                    System.out.println(TAG + " Bundle extract for '"
                            + extractedOldName + "' produced "
                            + (cursor.isEmpty() ? "nothing"
                                    : cursor.getItem().toString()
                                            + (cursor.getCustomName() != null
                                                    ? " ('" + cursor.getCustomName().getString() + "')"
                                                    : ""))
                            + " — aborting.");
                    bundleExtractState = 0;
                    bundleExtractSlot  = -1;
                    extractedNamed   = false;
                    extractedOldName = null;
                    cooldown = ACTION_COOLDOWN_TICKS;
                    return;
                }
                if (!leftSlot.isEmpty()) {
                    // Anvil input occupied (shouldn't normally happen here, but
                    // be defensive). Stash the popped banner in a free slot and
                    // reset extraction state. Clearing extractedNamed prevents
                    // PRIORITY 2 from later trying to rename whatever is in
                    // slot 0 as if it were this banner.
                    int dst = findEmptySlot(handler);
                    if (dst != -1) {
                        client.interactionManager.clickSlot(
                                handler.syncId, dst, LEFT_CLICK,
                                SlotActionType.PICKUP, client.player);
                    }
                    bundleExtractState = 0;
                    bundleExtractSlot  = -1;
                    extractedNamed   = false;
                    extractedOldName = null;
                    cooldown = ACTION_COOLDOWN_TICKS;
                    return;
                }
                System.out.println(TAG + "   Bundle step 2: LEFT-click slot 0 → drop '"
                        + extractedOldName + "' into anvil input");
                client.interactionManager.clickSlot(
                        handler.syncId, 0, LEFT_CLICK, SlotActionType.PICKUP, client.player);
                bundleExtractState = 0;
                bundleExtractSlot  = -1;
                cooldown = ACTION_COOLDOWN_TICKS;
                return;
            }

            // ── storeState 1: confirm QUICK_MOVE ──────────────────────────────
            if (storeState == 1) {
                if (!outputSlot.isEmpty()) {
                    // Output still has item — QUICK_MOVE was rejected.
                    // Retry the shift-click immediately.
                    renameTicks = 0;
                    client.interactionManager.clickSlot(
                            handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);
                    cooldown = ACTION_COOLDOWN_TICKS;
                } else {
                    // Output is empty — QUICK_MOVE was processed (or optimistically cleared).
                    // Confirm by finding the banner in inventory.
                    int bannerSlot = findNamedBannerInInventory(handler, pendingName);
                    if (bannerSlot != -1) {
                        // Banner confirmed in inventory — try to bundle it.
                        renameTicks = 0;
                        int bundleSlot = findAvailableBundle(handler);
                        if (bundleSlot != -1 && cursor.isEmpty()) {
                            System.out.println(TAG + "   Step 5a: LEFT-click slot " + bannerSlot
                                    + " → pick up banner for bundle");
                            client.interactionManager.clickSlot(
                                    handler.syncId, bannerSlot, LEFT_CLICK, SlotActionType.PICKUP,
                                    client.player);
                            storeState = 2;
                        } else {
                            warnNoOutputBundle(client, handler);
                            advance(pendingIndex);
                        }
                        cooldown = ACTION_COOLDOWN_TICKS;
                    } else {
                        // Banner not yet visible in inventory.
                        // Either the server update hasn't arrived, or QUICK_MOVE was
                        // rejected and the output will be restored via resync.
                        // Wait; the !outputSlot.isEmpty() branch above will catch restores.
                        renameTicks++;
                        if (renameTicks >= RENAME_TIMEOUT_TICKS) {
                            System.out.println(TAG + " Banner not found in inventory after "
                                    + RENAME_TIMEOUT_TICKS + " ticks (index " + pendingIndex
                                    + ") — resetting for retry");
                            renameTicks = 0;
                            storeState = 0;  // let Priority 1 / rename-timeout retry
                            cooldown = ACTION_COOLDOWN_TICKS;
                        }
                        // Do NOT advance — we haven't confirmed success.
                    }
                }
                return;
            }

            // ── storeState 2: insert banner from cursor into bundle ──────────
            if (storeState == 2) {
                if (!cursor.isEmpty()
                        && cursor.getItem() instanceof BannerItem
                        && cursor.getCustomName() != null) {
                    int bundleSlot = findAvailableBundle(handler);
                    if (bundleSlot != -1) {
                        System.out.println(TAG + "   Step 5b: LEFT-click bundle slot "
                                + bundleSlot + " → insert into bundle");
                        client.interactionManager.clickSlot(
                                handler.syncId, bundleSlot, LEFT_CLICK, SlotActionType.PICKUP,
                                client.player);
                        noBundleWarningShown = false;
                    } else {
                        warnNoOutputBundle(client, handler);
                        // No bundle — return banner to any free slot.
                        int dst = findEmptySlot(handler);
                        if (dst != -1) {
                            client.interactionManager.clickSlot(
                                    handler.syncId, dst, LEFT_CLICK, SlotActionType.PICKUP,
                                    client.player);
                        }
                    }
                    advance(pendingIndex);
                } else if (cursor.isEmpty()) {
                    // Step 5a was rejected; banner is still in inventory.
                    advance(pendingIndex);
                }
                return;
            }

            // ── Already-in-inventory check ─────────────────────────────────────
            // Catches the case where the anvil was closed between QUICK_MOVE and
            // storeState=1 confirmation: advance() was never called, so the handler
            // tries to process the same chunk again.  If the banner is already in
            // inventory, bundle it and advance rather than renaming a fresh banner.
            if (pendingName == null && storeState == 0 && extractState == 0
                    && bundleExtractState == 0 && cursor.isEmpty()) {
                String target = PayloadState.ACTIVE_CHUNKS.get(PayloadState.activeChunkIndex);
                int existing = findNamedBannerInInventory(handler, target);
                if (existing != -1) {
                    int bundleSlot = findAvailableBundle(handler);
                    if (bundleSlot != -1) {
                        System.out.println(TAG + " Already-renamed banner found in slot "
                                + existing + " for index " + PayloadState.activeChunkIndex
                                + " — bundling directly");
                        // Remove from whitelist so this name can't double-match
                        // a later extraction (and so our own freshly-written
                        // banners are never seen as raw material).
                        if (PayloadState.whitelistedBannerNames.remove(target)) {
                            PayloadState.save();
                        }
                        pendingName  = target;
                        pendingIndex = PayloadState.activeChunkIndex;
                        client.interactionManager.clickSlot(
                                handler.syncId, existing, LEFT_CLICK, SlotActionType.PICKUP,
                                client.player);
                        storeState = 2;
                        cooldown = ACTION_COOLDOWN_TICKS;
                        return;
                    }
                    // Bundle not available yet — fall through to normal priorities.
                }
            }

            // ── PRIORITY 1: output has renamed banner → shift-click to inv ───
            // QUICK_MOVE bypasses the cursor entirely, so there is no
            // cursor-state-ID mismatch: we just check "did slot 2 go empty?"
            if (pendingName != null
                    && !outputSlot.isEmpty()
                    && outputSlot.getItem() instanceof BannerItem
                    && outputSlot.getCustomName() != null) {
                renameTicks = 0;
                System.out.println(TAG + "   Step 4: SHIFT-click slot 2 → move '"
                        + pendingName + "' to inventory");
                client.interactionManager.clickSlot(
                        handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);
                storeState = 1;
                cooldown = ACTION_COOLDOWN_TICKS;
                return;
            }

            // ── Rename timeout ───────────────────────────────────────────────
            if (pendingName != null && outputSlot.isEmpty()) {
                renameTicks++;
                if (renameTicks >= RENAME_TIMEOUT_TICKS) {
                    renameTicks = 0;
                    if (!leftSlot.isEmpty()) {
                        renameAttemptCount++;
                        if (renameAttemptCount >= MAX_RENAME_ATTEMPTS) {
                            System.out.println(TAG + " Chunk " + pendingIndex
                                    + " failed " + MAX_RENAME_ATTEMPTS
                                    + " times — halting. Run /loominary resalt.");
                            haltedForResalt = true;
                            pendingName  = null;
                            pendingIndex = -1;
                            storeState   = 0;
                            extractedNamed   = false;
                            extractedOldName = null;
                            cooldown = ACTION_COOLDOWN_TICKS;
                        } else {
                            System.out.println(TAG + " Output empty after " + RENAME_TIMEOUT_TICKS
                                    + " ticks — re-rename attempt " + renameAttemptCount
                                    + "/" + MAX_RENAME_ATTEMPTS + " for index " + pendingIndex);
                            client.player.networkHandler.sendPacket(new RenameItemC2SPacket(""));
                            client.player.networkHandler.sendPacket(
                                    new RenameItemC2SPacket(pendingName));
                            cooldown = ACTION_COOLDOWN_TICKS * 4;
                        }
                    } else {
                        // Slot 0 is empty — the QUICK_MOVE already consumed the input.
                        // The banner landed somewhere we haven't scanned yet; fall
                        // back to a full re-extract cycle so the rename fires fresh.
                        System.out.println(TAG + " Output empty and slot 0 empty after "
                                + RENAME_TIMEOUT_TICKS + " ticks — re-extracting for index "
                                + pendingIndex);
                        pendingName  = null;
                        pendingIndex = -1;
                        storeState   = 0;
                        extractedNamed   = false;
                        extractedOldName = null;
                        cooldown = ACTION_COOLDOWN_TICKS;
                    }
                }
                return;
            }

            // ── PRIORITY 2: single banner in slot 0 → rename it ─────────────
            // Normally the input must be unnamed (we never want to clobber a
            // banner the user dropped in by hand). The `extractedNamed` flag
            // marks banners we deliberately pulled from the whitelist; for
            // those, overwriting the old name is the entire point — but we
            // additionally require the name in slot 0 to match the name we
            // expected to pull, so a stray named banner left in slot 0 can't
            // be silently renamed.
            boolean namedAndExpected = extractedNamed
                    && extractedOldName != null
                    && leftSlot.getCustomName() != null
                    && extractedOldName.equals(leftSlot.getCustomName().getString());
            if (pendingName == null
                    && !leftSlot.isEmpty()
                    && leftSlot.getItem() instanceof BannerItem
                    && (leftSlot.getCustomName() == null || namedAndExpected)
                    && leftSlot.getCount() == 1) {  // never rename a stack

                renameTicks  = 0;
                pendingName  = PayloadState.ACTIVE_CHUNKS.get(PayloadState.activeChunkIndex);
                pendingIndex = PayloadState.activeChunkIndex;

                System.out.println(TAG + " STAGE: Rename — name='" + pendingName
                        + "' (index " + pendingIndex + "/"
                        + (PayloadState.ACTIVE_CHUNKS.size() - 1) + ")"
                        + (extractedNamed ? " [reusing whitelist banner '"
                                + extractedOldName + "']" : ""));

                // Priority 1 fires when the server confirms the output slot is populated.
                // Omitting the client-side setNewItemName() call means the output slot
                // stays empty until the server responds, preventing premature QUICK_MOVEs
                // that the server would reject.
                client.player.networkHandler.sendPacket(new RenameItemC2SPacket(pendingName));
                cooldown = ACTION_COOLDOWN_TICKS * 4;
                return;
            }

            // ── PRIORITY 3: slot 0 empty → start extraction ──────────────────
            if (pendingName == null
                    && leftSlot.isEmpty()
                    && extractState == 0
                    && cursor.isEmpty()) {  // never start extraction with cursor occupied
                startExtraction(client, handler);
            }
        });
    }

    // ── Extraction step 1 ──────────────────────────────────────────────────

    private static void startExtraction(MinecraftClient client, AnvilScreenHandler handler) {
        // Current chunk's target name; used to short-circuit when a whitelisted
        // banner already happens to carry the exact name we'd rename it to.
        // Vanilla anvil treats same-name as a no-op (empty output), which would
        // stall PRIORITY 2 and eventually trip the resalt halt.
        String target = PayloadState.ACTIVE_CHUNKS.get(PayloadState.activeChunkIndex);

        // Pass 1: prefer an unnamed banner stack (existing behavior).
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BannerItem)) continue;
            if (stack.getCustomName() != null) continue;

            System.out.println(TAG + " STAGE: Extract — unnamed banner at slot "
                    + i + " (count=" + stack.getCount() + ")");
            System.out.println(TAG + "   Step 1: LEFT-click slot " + i
                    + " → pick up whole stack");
            client.interactionManager.clickSlot(
                    handler.syncId, i, LEFT_CLICK, SlotActionType.PICKUP, client.player);
            extractState = 1;
            extractSlot  = i;
            cooldown = ACTION_COOLDOWN_TICKS;
            bannerPausedLogged = false;
            return;
        }

        // Pass 2: a loose whitelisted named banner — pick it up, run it through
        // the anvil with the current chunk name. Same three-step extract as for
        // unnamed stacks (works fine for count=1 named banners; step 3 is a
        // no-op return).
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BannerItem)) continue;
            if (stack.getCustomName() == null) continue;
            String name = stack.getCustomName().getString();
            if (!PayloadState.whitelistedBannerNames.contains(name)) continue;

            // Same-name short-circuit: nothing to rename, this chunk is already
            // satisfied by the existing loose banner. Skip slot ops and advance.
            // The line-257 shortcut would have caught this if a bundle slot
            // were free; we handle the "no bundle space" case here.
            if (name.equals(target)) {
                System.out.println(TAG + " Chunk " + PayloadState.activeChunkIndex
                        + " already satisfied by loose whitelisted banner '"
                        + name + "' at slot " + i + " — advancing.");
                PayloadState.whitelistedBannerNames.remove(name);
                advance(PayloadState.activeChunkIndex);
                return;
            }

            PayloadState.whitelistedBannerNames.remove(name);
            PayloadState.save();
            extractedNamed   = true;
            extractedOldName = name;
            System.out.println(TAG + " STAGE: Extract — whitelisted banner '" + name
                    + "' at slot " + i + " (reuse as raw material)");
            client.interactionManager.clickSlot(
                    handler.syncId, i, LEFT_CLICK, SlotActionType.PICKUP, client.player);
            extractState = 1;
            extractSlot  = i;
            cooldown = ACTION_COOLDOWN_TICKS;
            bannerPausedLogged = false;
            return;
        }

        // Pass 3: a whitelisted banner inside a bundle — pop it out via the
        // bundle's selected-item slot interaction, then drop it into anvil
        // slot 0 in subsequent ticks.
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (stack.getItem() != Items.BUNDLE) continue;
            BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (contents == null || contents.isEmpty()) continue;
            for (int j = 0; j < contents.size(); j++) {
                ItemStack inside = contents.get(j);
                if (!(inside.getItem() instanceof BannerItem)) continue;
                if (inside.getCustomName() == null) continue;
                String name = inside.getCustomName().getString();
                if (!PayloadState.whitelistedBannerNames.contains(name)) continue;

                // Same-name short-circuit (see Pass 2). The banner is already
                // bundled and already named correctly — chunk is done.
                if (name.equals(target)) {
                    System.out.println(TAG + " Chunk " + PayloadState.activeChunkIndex
                            + " already satisfied by bundled whitelisted banner '"
                            + name + "' at slot " + i + " (index " + j + ") — advancing.");
                    PayloadState.whitelistedBannerNames.remove(name);
                    advance(PayloadState.activeChunkIndex);
                    return;
                }

                PayloadState.whitelistedBannerNames.remove(name);
                PayloadState.save();
                extractedNamed    = true;
                extractedOldName  = name;
                bundleExtractSlot = i;
                System.out.println(TAG + " STAGE: Extract — whitelisted banner '" + name
                        + "' inside bundle at slot " + i + " (index " + j + ")");
                System.out.println(TAG + "   Bundle pre-step: SelectBundleItem(slot="
                        + i + ", index=" + j + ")");
                // Critical: ALSO update the client-side bundle component so the
                // local optimistic right-click in bundleExtractState 1 pops the
                // same index as the server. Without this, the client's
                // selectedStackIndex stays at -1 (or stale) and removeSelected
                // falls back to index 0 — popping a different item than the
                // server. The symptom: "Bundle extract produced nothing" / the
                // wrong banner ends up on cursor and gets parked.
                BundleItem.setSelectedStackIndex(stack, j);
                client.player.networkHandler.sendPacket(
                        new BundleItemSelectedC2SPacket(i, j));
                bundleExtractState = 1;
                cooldown = ACTION_COOLDOWN_TICKS;
                bannerPausedLogged = false;
                return;
            }
        }

        if (!bannerPausedLogged) {
            System.out.println(TAG + " Paused: no unnamed or whitelisted banners available.");
            bannerPausedLogged = true;
        }
        client.inGameHud.setOverlayMessage(
                Text.literal("§e" + TAG + " Paused — add unnamed banners or §f/loominary whitelist add§e."),
                false);
    }

    // ── Slot search helpers ────────────────────────────────────────────────

    private static int findAvailableBundle(AnvilScreenHandler handler) {
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() != Items.BUNDLE) continue;
            BundleContentsComponent contents = stack.getOrDefault(
                    DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (contents.getOccupancy().doubleValue() >= 1.0) continue;
            // Never use a bundle that still holds whitelisted source banners as
            // an output target. Inserting at index 0 shifts the remaining
            // whitelist entries and seems to desync the bundle's selected-index
            // book-keeping with the server, causing later pops to come up empty
            // or stall around the halfway mark. The user must provide separate
            // empty bundles for renamer output.
            if (bundleContainsWhitelisted(contents)) continue;
            return i;
        }
        return -1;
    }

    /**
     * Shows a one-shot HUD warning when the renamer has just produced a banner
     * but every bundle in inventory is either full or excluded as a source
     * (still holds whitelisted entries). Triggered once per "stuck" episode;
     * resets when the user adds an empty bundle and a future bundle-insert
     * succeeds (see {@link #noBundleWarningShown} reset on storeState 2).
     */
    private static boolean noBundleWarningShown = false;

    private static void warnNoOutputBundle(MinecraftClient client, AnvilScreenHandler handler) {
        if (noBundleWarningShown) return;
        int sourceBundleCount = 0;
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() != Items.BUNDLE) continue;
            BundleContentsComponent contents = stack.getOrDefault(
                    DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (bundleContainsWhitelisted(contents)) sourceBundleCount++;
        }
        if (sourceBundleCount > 0) {
            System.out.println(TAG + " No output bundle available — "
                    + sourceBundleCount + " bundle(s) hold whitelisted source banners and"
                    + " can't be reused. Renamed banner left loose; add an empty bundle.");
            client.inGameHud.setOverlayMessage(
                    Text.literal("§e" + TAG + " Add empty bundle for renamed output ("
                            + sourceBundleCount + " source bundle"
                            + (sourceBundleCount == 1 ? "" : "s") + " skipped)."),
                    false);
        } else {
            System.out.println(TAG + " No output bundle available — all bundles full or absent."
                    + " Renamed banner left loose.");
            client.inGameHud.setOverlayMessage(
                    Text.literal("§e" + TAG + " Add a bundle for renamed output."),
                    false);
        }
        noBundleWarningShown = true;
    }

    private static boolean bundleContainsWhitelisted(BundleContentsComponent contents) {
        if (PayloadState.whitelistedBannerNames.isEmpty()) return false;
        for (ItemStack inside : contents.iterate()) {
            if (!(inside.getItem() instanceof BannerItem)) continue;
            if (inside.getCustomName() == null) continue;
            if (PayloadState.whitelistedBannerNames.contains(
                    inside.getCustomName().getString())) return true;
        }
        return false;
    }

    private static int findEmptySlot(AnvilScreenHandler handler) {
        for (int i = 3; i < handler.slots.size(); i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private static int findNamedBannerInInventory(AnvilScreenHandler handler, String name) {
        // Scan ALL slots except the output (slot 2).  QUICK_MOVE sometimes places
        // the banner in slot 1 (the second anvil input) rather than the player
        // inventory, so we must not restrict the scan to slots 3+.
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == 2) continue; // never match the output slot itself
            ItemStack s = handler.getSlot(i).getStack();
            if (!(s.getItem() instanceof BannerItem)) continue;
            if (s.getCustomName() == null) continue;
            if (s.getCustomName().getString().equals(name)) return i;
        }
        return -1;
    }

    private static void advance(int doneIndex) {
        PayloadState.activeChunkIndex = doneIndex + 1;
        System.out.println(TAG + " Completed rename " + doneIndex
                + " → advancing to " + PayloadState.activeChunkIndex + "/"
                + PayloadState.ACTIVE_CHUNKS.size());
        PayloadState.save();
        pendingName  = null;
        pendingIndex = -1;
        renameTicks  = 0;
        storeState   = 0;
        renameAttemptCount = 0;
        extractedNamed   = false;
        extractedOldName = null;
        cooldown = ACTION_COOLDOWN_TICKS;
    }

    /**
     * Called when the anvil screen opens. If encryption is configured and the
     * active tile is a plain-byte banner tile, rebuilds ACTIVE_CHUNKS with
     * encrypted chunk names for this placement session.
     *
     * The tile's canonical chunks (TileData.chunks) are never modified —
     * PayloadState.placementEncrypted prevents syncToActiveTile from writing
     * encrypted chunks back to the tile.
     */
    private static void applyEncryptionForPlacement() {
        // Reset any previous encrypted session so plain chunks are active again.
        PayloadState.placementEncrypted = false;

        String pw = LoominaryCommand.encryptPassword;
        if (pw == null || pw.isEmpty()) return;
        if (PayloadState.tiles.isEmpty()) return;

        PayloadState.TileData tile = PayloadState.tiles.get(PayloadState.activeTileIndex);
        // Carpet tiles are encrypted at schematic-export time, not here.
        if (tile.carpetEncoded) return;
        if (PayloadState.ACTIVE_CHUNKS.isEmpty()) return;

        try {
            // Assemble plain compressed bytes from the current chunks.
            java.util.List<String> plainChunks = new java.util.ArrayList<>(PayloadState.ACTIVE_CHUNKS);
            byte[] plain = MapBannerDecoder.assembleCompressedFromChunks(plainChunks);
            // Decrypt first in case the tile was imported before the deferred-encrypt fix.
            if (MapEncryption.isEncrypted(plain)) {
                plain = MapEncryption.tryDecrypt(plain, -1);
                if (plain == null) {
                    System.err.println(TAG + " Cannot encrypt for placement: decryption of stored payload failed.");
                    return;
                }
            }
            String placementAuthor = PayloadState.effectiveAuthor(
                    net.minecraft.client.MinecraftClient.getInstance()
                            .player.getGameProfile().getName());
            java.util.List<String> encrypted = LoominaryCommand.buildEncryptedChunksForOutput(
                    plain, placementAuthor, PayloadState.currentTitle);
            PayloadState.ACTIVE_CHUNKS.clear();
            PayloadState.ACTIVE_CHUNKS.addAll(encrypted);
            PayloadState.activeChunkIndex = 0; // encrypted run always starts fresh
            PayloadState.placementEncrypted = true;
            System.out.println(TAG + " Encrypted " + encrypted.size()
                    + " chunks for placement (was " + plainChunks.size() + " plain).");
        } catch (Exception e) {
            System.err.println(TAG + " Encryption for placement failed: " + e.getMessage());
            PayloadState.placementEncrypted = false;
        }
    }
}
