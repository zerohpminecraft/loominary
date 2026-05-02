package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
            xpPausedLogged     = false;
            bannerPausedLogged = false;
            batchDoneLogged    = false;
            // haltedForResalt is intentionally preserved across screen opens;
            // only /loominary resalt clears it.
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
            if (!cursor.isEmpty() && extractState == 0 && storeState == 0) {
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
                    } else {
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
                    && cursor.isEmpty()) {
                String target = PayloadState.ACTIVE_CHUNKS.get(PayloadState.activeChunkIndex);
                int existing = findNamedBannerInInventory(handler, target);
                if (existing != -1) {
                    int bundleSlot = findAvailableBundle(handler);
                    if (bundleSlot != -1) {
                        System.out.println(TAG + " Already-renamed banner found in slot "
                                + existing + " for index " + PayloadState.activeChunkIndex
                                + " — bundling directly");
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
                        cooldown = ACTION_COOLDOWN_TICKS;
                    }
                }
                return;
            }

            // ── PRIORITY 2: single unnamed banner in slot 0 → rename it ─────
            if (pendingName == null
                    && !leftSlot.isEmpty()
                    && leftSlot.getItem() instanceof BannerItem
                    && leftSlot.getCustomName() == null
                    && leftSlot.getCount() == 1) {  // never rename a stack

                renameTicks  = 0;
                pendingName  = PayloadState.ACTIVE_CHUNKS.get(PayloadState.activeChunkIndex);
                pendingIndex = PayloadState.activeChunkIndex;

                System.out.println(TAG + " STAGE: Rename — name='" + pendingName
                        + "' (index " + pendingIndex + "/"
                        + (PayloadState.ACTIVE_CHUNKS.size() - 1) + ")");

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
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BannerItem)) continue;
            if (stack.getCustomName() != null) continue;  // skip already-named banners

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
        if (!bannerPausedLogged) {
            System.out.println(TAG + " Paused: no unnamed banner stacks in inventory.");
            bannerPausedLogged = true;
        }
        client.inGameHud.setOverlayMessage(
                Text.literal("§e" + TAG + " Paused — add unnamed banners to resume."), false);
    }

    // ── Slot search helpers ────────────────────────────────────────────────

    private static int findAvailableBundle(AnvilScreenHandler handler) {
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() != Items.BUNDLE) continue;
            BundleContentsComponent contents = stack.getOrDefault(
                    DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (contents.getOccupancy().doubleValue() < 1.0) return i;
        }
        return -1;
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
        cooldown = ACTION_COOLDOWN_TICKS;
    }
}
