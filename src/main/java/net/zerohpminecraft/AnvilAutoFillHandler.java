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

public class AnvilAutoFillHandler {

    private static final String TAG = "[Loominary]";
    private static final int ACTION_COOLDOWN_TICKS = 5;
    private static final int MIN_XP_FOR_RENAME = 1;

    private static final int LEFT_CLICK  = 0;
    private static final int RIGHT_CLICK = 1;

    private static String pendingName = null;
    private static int pendingIndex = -1;
    private static int cooldown = 0;

    private static boolean xpPausedLogged = false;
    private static boolean bundlePausedLogged = false;
    private static boolean bannerPausedLogged = false;
    private static boolean batchDoneLogged = false;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AnvilScreen)) return;
            System.out.println(TAG + " Anvil screen opened — resetting in-cycle state.");
            pendingName = null;
            pendingIndex = -1;
            cooldown = 0;
            xpPausedLogged = false;
            bundlePausedLogged = false;
            bannerPausedLogged = false;
            batchDoneLogged = false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            if (!(client.currentScreen instanceof AnvilScreen anvilScreen)) return;

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            if (PayloadState.ACTIVE_CHUNKS.isEmpty()) return;

            if (PayloadState.activeChunkIndex >= PayloadState.ACTIVE_CHUNKS.size()) {
                if (!batchDoneLogged) {
                    System.out.println(TAG + " Batch complete — all "
                            + PayloadState.ACTIVE_CHUNKS.size()
                            + " banners processed.");
                    batchDoneLogged = true;
                }
                return;
            }

            if (client.player.experienceLevel < MIN_XP_FOR_RENAME) {
                if (!xpPausedLogged) {
                    System.out.println(TAG + " Paused: out of XP (level="
                            + client.player.experienceLevel
                            + "). Farm some levels and I'll pick up automatically.");
                    xpPausedLogged = true;
                }
                return;
            }
            if (xpPausedLogged) {
                System.out.println(TAG + " Resuming: XP restored (level="
                        + client.player.experienceLevel + ")");
                xpPausedLogged = false;
            }

            AnvilScreenHandler handler = anvilScreen.getScreenHandler();
            ItemStack leftSlot   = handler.getSlot(0).getStack();
            ItemStack outputSlot = handler.getSlot(2).getStack();

            // PRIORITY 1: renamed banner in output → store in bundle
            if (pendingName != null
                    && !outputSlot.isEmpty()
                    && outputSlot.getCustomName() != null
                    && outputSlot.getCustomName().getString().equals(pendingName)) {

                moveOutputToBundle(client, handler);
                return;
            }

            // PRIORITY 2: unnamed banner in left slot → rename it
            if (pendingName == null
                    && !leftSlot.isEmpty()
                    && leftSlot.getItem() instanceof BannerItem
                    && leftSlot.getCustomName() == null) {

                pendingName = PayloadState.ACTIVE_CHUNKS.get(PayloadState.activeChunkIndex);
                pendingIndex = PayloadState.activeChunkIndex;

                System.out.println(TAG + " STAGE: Rename — sending RenameItemC2SPacket name='"
                        + pendingName + "' (index " + pendingIndex + "/"
                        + (PayloadState.ACTIVE_CHUNKS.size() - 1) + ")");

                client.player.networkHandler.sendPacket(new RenameItemC2SPacket(pendingName));
                cooldown = ACTION_COOLDOWN_TICKS;
                return;
            }

            // PRIORITY 3: left slot empty → extract one banner from a stack
            if (pendingName == null && leftSlot.isEmpty()) {
                placeOneBannerInAnvil(client, handler);
            }
        });
    }

    private static void placeOneBannerInAnvil(MinecraftClient client, AnvilScreenHandler handler) {
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty()
                    && stack.getItem() instanceof BannerItem
                    && stack.getCustomName() == null) {

                int count = stack.getCount();
                System.out.println(TAG + " STAGE: Extract — unnamed banner stack at slot "
                        + i + " (count=" + count + ")");

                System.out.println(TAG + "   Step 1: LEFT-click slot " + i + " → pick up whole stack");
                client.interactionManager.clickSlot(
                        handler.syncId, i, LEFT_CLICK, SlotActionType.PICKUP, client.player);

                System.out.println(TAG + "   Step 2: RIGHT-click slot 0 → place 1 banner");
                client.interactionManager.clickSlot(
                        handler.syncId, 0, RIGHT_CLICK, SlotActionType.PICKUP, client.player);

                System.out.println(TAG + "   Step 3: LEFT-click slot " + i + " → return " + (count - 1));
                client.interactionManager.clickSlot(
                        handler.syncId, i, LEFT_CLICK, SlotActionType.PICKUP, client.player);

                cooldown = ACTION_COOLDOWN_TICKS;
                bannerPausedLogged = false;
                return;
            }
        }
        if (!bannerPausedLogged) {
            System.out.println(TAG + " Paused: no unnamed banner stacks in inventory.");
            bannerPausedLogged = true;
        }
    }

    private static void moveOutputToBundle(MinecraftClient client, AnvilScreenHandler handler) {
        int bundleSlot = findAvailableBundle(handler);

        if (bundleSlot == -1) {
            if (!bundlePausedLogged) {
                System.out.println(TAG + " Paused: all bundles full or missing. "
                        + "Empty a bundle or add more to continue.");
                bundlePausedLogged = true;
            }
            cooldown = ACTION_COOLDOWN_TICKS;
            return;
        }
        bundlePausedLogged = false;

        ItemStack bundleStack = handler.getSlot(bundleSlot).getStack();
        BundleContentsComponent contents = bundleStack.getOrDefault(
                DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);

        System.out.println(TAG + " STAGE: Store — target bundle slot " + bundleSlot
                + " (" + contents.size() + "/16)");

        System.out.println(TAG + "   Step 4: LEFT-click slot 2 → pick up renamed banner '" + pendingName + "'");
        client.interactionManager.clickSlot(
                handler.syncId, 2, LEFT_CLICK, SlotActionType.PICKUP, client.player);

        System.out.println(TAG + "   Step 5: LEFT-click slot " + bundleSlot + " → insert into bundle");
        client.interactionManager.clickSlot(
                handler.syncId, bundleSlot, LEFT_CLICK, SlotActionType.PICKUP, client.player);

        PayloadState.activeChunkIndex = pendingIndex + 1;
        System.out.println(TAG + " Completed rename " + pendingIndex + " → advancing to "
                + PayloadState.activeChunkIndex + "/"
                + PayloadState.ACTIVE_CHUNKS.size());

        PayloadState.save();

        pendingName = null;
        pendingIndex = -1;
        cooldown = ACTION_COOLDOWN_TICKS;
    }

    private static int findAvailableBundle(AnvilScreenHandler handler) {
        for (int i = 3; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() != Items.BUNDLE) continue;

            BundleContentsComponent contents = stack.getOrDefault(
                    DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (contents.size() < 16) {
                return i;
            }
        }
        return -1;
    }
}