package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes the server's item-rename alphabet using a single banner renamed
 * repeatedly at the anvil.  If the anvil closes mid-run (breaks, player
 * walks away), the test pauses and resumes automatically the next time
 * the player opens any anvil.
 *
 * <p>Usage: run {@code /loominary alphabettest [wait]} before opening the
 * anvil, then open an anvil with exactly one banner anywhere in inventory.
 *
 * <p>Output: {@code loominary_exports/alphabettest_TIMESTAMP.tsv}
 */
public class AlphabetTestHandler {

    private static final String TAG                = "[AlphabetTest]";
    private static final int ACTION_COOLDOWN_TICKS = 5;
    private static final int MIN_XP_FOR_RENAME     = 1;
    private static final int RENAME_TIMEOUT_TICKS  = 60;

    private static final int LEFT_CLICK  = 0;
    private static final int RIGHT_CLICK = 1;

    private enum Phase { IDLE, PAUSED, EXTRACTING, RENAMING, TAKING, RETURNING }

    private static Phase   phase        = Phase.IDLE;
    private static int     cooldown     = 0;
    private static int     renameTicks  = 0;
    private static int     emptyStreak  = 0;
    private static int     idx          = 0;
    private static int     waitTicks    = 6;
    private static boolean armed        = false;

    // Extraction sub-state — same 3-step protocol as AnvilAutoFillHandler
    private static int extractState = 0;
    private static int extractSlot  = -1;

    private static boolean xpPausedLogged = false;

    private static final List<String> labels  = new ArrayList<>();
    private static final List<String> strings = new ArrayList<>();
    private static PrintWriter writer = null;

    // ── Public API ────────────────────────────────────────────────────────

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AnvilScreen)) return;
            if (armed) {
                doStart(client);
            } else if (phase == Phase.PAUSED) {
                resumeOnAnvil(client);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(AlphabetTestHandler::tick);
    }

    public static boolean isActive() { return phase != Phase.IDLE || armed; }

    public static boolean start(MinecraftClient client, int wait) {
        waitTicks = Math.max(2, wait);
        if (!(client.currentScreen instanceof AnvilScreen)) {
            armed = true;
            msg(client, "§aAlphabet test armed (wait=" + waitTicks
                    + " ticks). Open an anvil with one banner in your inventory.");
            return true;
        }
        return doStart(client);
    }

    private static boolean doStart(MinecraftClient client) {
        armed        = false;
        idx          = 0;
        emptyStreak  = 0;
        renameTicks  = 0;
        extractState = 0;
        extractSlot  = -1;
        cooldown     = 0;
        xpPausedLogged = false;
        buildTests();

        try {
            Path dir = client.runDirectory.toPath().resolve("loominary_exports");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path p = dir.resolve("alphabettest_" + ts + ".tsv");
            writer = new PrintWriter(Files.newBufferedWriter(p));
            writer.println("label\tsent_units\tsent_cps\toutput_empty\tgot_units\tgot_cps\tmatch\tgot_sample");
            writer.flush();
            msg(client, "§aAlphabet test started — " + strings.size()
                    + " cases, wait=" + waitTicks + " ticks. Log: " + p.getFileName());
        } catch (IOException e) {
            msg(client, "§cLog open failed: " + e.getMessage());
            return false;
        }

        phase = Phase.EXTRACTING;
        return true;
    }

    /** Called by ScreenEvents when anvil opens while test is paused. */
    private static void resumeOnAnvil(MinecraftClient client) {
        extractState   = 0;
        extractSlot    = -1;
        cooldown       = 0;
        renameTicks    = 0;
        xpPausedLogged = false;
        phase = Phase.EXTRACTING;
        msg(client, "§aAlphabet test resuming — test " + (idx + 1) + "/" + strings.size());
    }

    public static void stop(MinecraftClient client) {
        armed = false;
        abort(client, "stopped by user");
    }

    // ── Tick ──────────────────────────────────────────────────────────────

    private static void tick(MinecraftClient client) {
        if (phase == Phase.IDLE) return;
        if (client.world == null || client.player == null) { abort(client, "disconnected"); return; }
        if (phase == Phase.PAUSED) return; // waiting for ScreenEvents to fire

        if (!(client.currentScreen instanceof AnvilScreen)) {
            // Anvil closed mid-test — pause and wait for the next anvil open.
            // TAKING and RETURNING mean the current test's result was already logged
            // but idx hasn't incremented yet; advance it so we don't re-run it.
            if (phase == Phase.TAKING || phase == Phase.RETURNING) {
                idx++;
                if (idx >= strings.size()) { done(client); return; }
            }
            phase = Phase.PAUSED;
            msg(client, "§eAlphabet test paused (anvil closed) — open any anvil to resume. "
                    + "Test " + (idx + 1) + "/" + strings.size());
            return;
        }

        if (cooldown > 0) { cooldown--; return; }

        AnvilScreenHandler h = (AnvilScreenHandler) client.player.currentScreenHandler;
        ItemStack cursor = h.getCursorStack();
        ItemStack slot0  = h.getSlot(0).getStack();
        ItemStack slot2  = h.getSlot(2).getStack();

        // XP gate — only RENAMING consumes XP.
        if (phase == Phase.RENAMING) {
            if (client.player.experienceLevel < MIN_XP_FOR_RENAME) {
                if (!xpPausedLogged) {
                    xpPausedLogged = true;
                    msg(client, "§e" + TAG + " Paused — out of XP. Restore XP to continue.");
                }
                client.inGameHud.setOverlayMessage(
                        Text.literal("§e" + TAG + " Paused — out of XP"), false);
                return;
            }
            if (xpPausedLogged) {
                xpPausedLogged = false;
                sendRename(client);
                renameTicks = 0;
                cooldown = waitTicks * 4;
                msg(client, "§a" + TAG + " XP restored — resuming.");
                return;
            }
        }

        switch (phase) {
            case EXTRACTING -> tickExtract(client, h, cursor, slot0);
            case RENAMING   -> tickRename(client, h, slot0, slot2);
            case TAKING     -> tickTake(client, h, cursor);
            case RETURNING  -> tickReturn(client);
            default -> {}
        }
    }

    // ── Extraction ────────────────────────────────────────────────────────

    private static void tickExtract(MinecraftClient client, AnvilScreenHandler h,
                                     ItemStack cursor, ItemStack slot0) {
        // Banner already in slot 0 — skip extraction, start renaming.
        if (!slot0.isEmpty() && slot0.getItem() instanceof BannerItem && slot0.getCount() == 1) {
            sendRename(client);
            phase = Phase.RENAMING;
            renameTicks = 0;
            cooldown = waitTicks * 4;
            return;
        }

        if (extractState == 1) {
            client.interactionManager.clickSlot(
                    h.syncId, 0, RIGHT_CLICK, SlotActionType.PICKUP, client.player);
            extractState = 2;
            cooldown = ACTION_COOLDOWN_TICKS;
            return;
        }
        if (extractState == 2) {
            client.interactionManager.clickSlot(
                    h.syncId, extractSlot, LEFT_CLICK, SlotActionType.PICKUP, client.player);
            extractState = 0;
            extractSlot  = -1;
            cooldown = ACTION_COOLDOWN_TICKS;
            return;
        }

        // Cursor safety
        if (!cursor.isEmpty()) {
            int dst = findEmptySlot(h);
            if (dst != -1) {
                client.interactionManager.clickSlot(
                        h.syncId, dst, LEFT_CLICK, SlotActionType.PICKUP, client.player);
                cooldown = ACTION_COOLDOWN_TICKS;
            }
            return;
        }

        // Find ANY banner in inventory — may be named if we're resuming after a pause.
        for (int i = 3; i < h.slots.size(); i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (s.isEmpty() || !(s.getItem() instanceof BannerItem)) continue;
            client.interactionManager.clickSlot(
                    h.syncId, i, LEFT_CLICK, SlotActionType.PICKUP, client.player);
            extractState = 1;
            extractSlot  = i;
            cooldown = ACTION_COOLDOWN_TICKS;
            return;
        }

        abort(client, "no banner in inventory — put one in and re-run /loominary alphabettest");
    }

    // ── Rename ────────────────────────────────────────────────────────────

    private static void tickRename(MinecraftClient client, AnvilScreenHandler h,
                                    ItemStack slot0, ItemStack slot2) {
        if (!slot2.isEmpty() && slot2.getItem() instanceof BannerItem) {
            logResult(slot2);
            emptyStreak = 0;
            client.interactionManager.clickSlot(
                    h.syncId, 2, LEFT_CLICK, SlotActionType.PICKUP, client.player);
            phase = Phase.TAKING;
            cooldown = ACTION_COOLDOWN_TICKS;
            return;
        }

        renameTicks++;
        if (renameTicks < RENAME_TIMEOUT_TICKS) return;
        renameTicks = 0;

        logResult(ItemStack.EMPTY);
        emptyStreak++;
        if (emptyStreak >= 3) {
            abort(client, "3 consecutive empty outputs — banner may be Too Expensive. "
                    + "Replace it and re-run /loominary alphabettest.");
            return;
        }

        idx++;
        if (idx >= strings.size()) { done(client); return; }
        if (slot0.isEmpty()) {
            phase = Phase.EXTRACTING;
            extractState = 0;
            cooldown = ACTION_COOLDOWN_TICKS;
        } else {
            sendRename(client);
            cooldown = waitTicks * 4;
        }
    }

    // ── Take / Return ─────────────────────────────────────────────────────

    private static void tickTake(MinecraftClient client, AnvilScreenHandler h, ItemStack cursor) {
        if (cursor.isEmpty()) {
            abort(client, "cursor empty after clicking output slot — unexpected state");
            return;
        }
        client.interactionManager.clickSlot(
                h.syncId, 0, LEFT_CLICK, SlotActionType.PICKUP, client.player);
        phase = Phase.RETURNING;
        cooldown = ACTION_COOLDOWN_TICKS;
    }

    private static void tickReturn(MinecraftClient client) {
        idx++;
        if (idx >= strings.size()) { done(client); return; }
        sendRename(client);
        phase = Phase.RENAMING;
        renameTicks = 0;
        cooldown = waitTicks * 4;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void sendRename(MinecraftClient client) {
        client.player.networkHandler.sendPacket(new RenameItemC2SPacket(strings.get(idx)));
    }

    private static int findEmptySlot(AnvilScreenHandler h) {
        for (int i = 3; i < h.slots.size(); i++) {
            if (h.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    // ── Logging ───────────────────────────────────────────────────────────

    private static void logResult(ItemStack result) {
        String s      = strings.get(idx);
        int    sentU  = s.length();
        int    sentCP = s.codePointCount(0, s.length());
        boolean empty = result.isEmpty();

        String got = ""; int gotU = 0, gotCP = 0; boolean match = false;
        if (!empty) {
            Text name = result.get(DataComponentTypes.CUSTOM_NAME);
            if (name != null) got = name.getString();
            gotU  = got.length();
            gotCP = got.isEmpty() ? 0 : got.codePointCount(0, got.length());
            match = s.equals(got);
        }

        String sample = empty ? "(empty)"
                : got.length() <= 12 ? got
                : got.substring(0, 6) + "…(len=" + got.length() + ")";

        if (writer != null) {
            writer.printf("%s\t%d\t%d\t%b\t%d\t%d\t%b\t%s%n",
                    labels.get(idx), sentU, sentCP, empty, gotU, gotCP, match, sample);
            writer.flush();
        }
    }

    private static void done(MinecraftClient client) {
        phase = Phase.IDLE;
        close();
        msg(client, "§aAlphabet test complete — " + strings.size()
                + " cases. Results in loominary_exports/");
    }

    private static void abort(MinecraftClient client, String reason) {
        phase = Phase.IDLE;
        extractState = 0;
        extractSlot  = -1;
        close();
        msg(client, "§cAlphabet test stopped: " + reason);
    }

    private static void close() {
        if (writer != null) { writer.flush(); writer.close(); writer = null; }
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), false);
    }

    // ── Test catalogue ────────────────────────────────────────────────────

    private static void buildTests() {
        labels.clear();
        strings.clear();

        // ── CJK range probe for efficient banner encoding ──────────────────
        //
        // The proposed alphabet is U+4E00–U+63FF (8192 chars, 13 bits each).
        // U+4E00 (一) is already confirmed passing from the previous run.
        // These tests verify the rest of the proposed range and the wider
        // CJK block, so we know exactly how far the safe alphabet extends.
        //
        // All CJK Unified Ideographs (U+4E00–U+9FFF) are NFC-stable by
        // Unicode spec — no canonical decomposition — so normalization is
        // not a risk here.
        addBMP(0x4E01, 50, "KJ_4E01_near-start");   // 丁 — one past confirmed U+4E00
        addBMP(0x5000, 50, "KJ_5000_mid-lo");        // within proposed range
        addBMP(0x5800, 50, "KJ_5800_mid");           // within proposed range
        addBMP(0x6000, 50, "KJ_6000_mid-hi");        // within proposed range
        addBMP(0x63FF, 50, "KJ_63FF_end-proposed");  // last char of proposed 8192-char range
        addBMP(0x6400, 50, "KJ_6400_beyond");        // first char past proposed range
        addBMP(0x7000, 50, "KJ_7000_upper");         // well into wider CJK block
        addBMP(0x9000, 50, "KJ_9000_near-end");      // near end of CJK Unified block
        addBMP(0xF900, 50, "KJ_F900_compat");        // CJK Compatibility Ideograph (different sub-block)
    }

    private static void addBMP(int cp, int n, String label) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, (char) cp);
        labels.add(label);
        strings.add(new String(arr));
    }
}
