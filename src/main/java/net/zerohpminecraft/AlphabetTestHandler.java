package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.DataComponentTypes;
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
import java.util.Arrays;
import java.util.List;

/**
 * Probes the server's item-rename alphabet with ~28 targeted test cases, designed
 * to fit on a single cheap item (cobblestone, dirt, etc.) before hitting the
 * "Too Expensive!" threshold (~6 anvil uses in survival).
 *
 * <p>Key questions answered:
 * <ul>
 *   <li><b>Counting mode</b>: does the 50-char limit use UTF-16 code units or Unicode
 *       code points? (discriminated by sending supplementary-plane chars × 25/26/50)</li>
 *   <li><b>Character acceptance</b>: are non-ASCII, control, NFC-affected, and private-use
 *       chars preserved unmodified?</li>
 *   <li><b>Normalization</b>: does the server apply NFC, stripping combining chars or
 *       collapsing compatibility equivalents?</li>
 * </ul>
 *
 * <p>Usage: open an anvil with <em>any</em> cheap item in the left slot (cobblestone
 * works fine), then run {@code /loominary alphabettest [wait_ticks]}.
 * Increase {@code wait_ticks} on laggy servers (e.g., 12 for ~500 ms ping on 2b2t).
 *
 * <p>Output: {@code loominary_exports/alphabettest_TIMESTAMP.tsv}
 * Columns: label | sent_units | sent_cps | output_empty | got_units | got_cps | match | got_sample
 */
public class AlphabetTestHandler {

    private enum Phase { IDLE, AFTER_RENAME, AFTER_TAKE, AFTER_RETURN }

    private static Phase phase       = Phase.IDLE;
    private static int   timer       = 0;
    private static int   waitTicks   = 6;
    private static int   idx         = 0;
    private static int   emptyStreak = 0;
    private static boolean armed     = false; // waiting for anvil to open

    private static final List<String> labels  = new ArrayList<>();
    private static final List<String> strings = new ArrayList<>();

    private static PrintWriter writer = null;

    // ── Public API ────────────────────────────────────────────────────────

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AlphabetTestHandler::tick);
    }

    public static boolean isActive() { return phase != Phase.IDLE; }

    public static boolean start(MinecraftClient client, int wait) {
        waitTicks = Math.max(2, wait);
        if (!(client.currentScreen instanceof AnvilScreen)) {
            armed = true;
            msg(client, "§aAlphabet test armed (wait=" + waitTicks
                    + " ticks). Open an anvil with any cheap item in the left slot to begin.");
            return true;
        }
        armed = false;
        idx         = 0;
        emptyStreak = 0;
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
            msg(client, "§7One cheap item is enough for the whole run. XP cost: ~" + strings.size() + " levels.");
        } catch (IOException e) {
            msg(client, "§cLog open failed: " + e.getMessage());
            return false;
        }

        phase = Phase.AFTER_RENAME;
        sendRename(client);
        timer = waitTicks;
        return true;
    }

    public static void stop(MinecraftClient client) {
        armed = false;
        abort(client, "stopped by user");
    }

    // ── Test catalogue ────────────────────────────────────────────────────
    //
    // 28 tests total — comfortably under the ~6-rename "Too Expensive!" limit
    // for a single item in survival. Groups are ordered by importance so you
    // can abort early once you have the critical discriminator results.

    private static void buildTests() {
        labels.clear();
        strings.clear();

        // ── Group 1: Supplement counting-mode discriminator (6 tests) ─────
        //
        // A supplementary char (e.g., 😀 U+1F600) is 2 UTF-16 code units.
        // ×25 = 50 units / 25 cps   → accepted regardless of counting mode
        // ×26 = 52 units / 26 cps   → accepted only if server counts by codepoint
        // ×50 = 100 units / 50 cps  → accepted only if server counts by codepoint
        //
        // If ×26 succeeds: server counts by codepoint (huge potential gain).
        // If ×26 fails:    server counts by code unit (no benefit from supplementary chars).
        addSupp(0x1F600, "emoji_1F600", 25);
        addSupp(0x1F600, "emoji_1F600", 26);
        addSupp(0x1F600, "emoji_1F600", 50);
        addSupp(0x10000, "linB_10000",  25);
        addSupp(0x10000, "linB_10000",  26);
        addSupp(0x10000, "linB_10000",  50);

        // ── Group 2: ASCII edge cases (5 tests) ───────────────────────────
        //
        // Baseline 'A' verifies the anvil is working.
        // JSON special chars (", \) and control chars may be stripped by the
        // Text serialiser or filtered server-side.
        addBMP('A',    50, "ascii_A");
        addBMP('"',    50, "ascii_quote");
        addBMP('\\',   50, "ascii_backslash");
        addBMP(0x01,   50, "ctrl_SOH");       // non-printable control
        addBMP(0x7F,   50, "ctrl_DEL");

        // ── Group 3: Non-ASCII BMP acceptance (7 tests) ───────────────────
        //
        // Tests whether the server treats the name as an opaque byte sequence
        // or as text with charset restrictions.
        addBMP(0x00E9, 50, "U+00E9_e-acute");  // é — Latin-1, very common
        addBMP(0x0410, 50, "U+0410_Cyrillic"); // А
        addBMP(0x4E00, 50, "U+4E00_CJK");      // 一
        addBMP(0x2603, 50, "U+2603_snowman");  // ☃ — BMP symbol
        addBMP(0xD800, 50, "U+D800_hi-surr");  // lone high surrogate — invalid UTF
        addBMP(0xE000, 50, "U+E000_PUA");      // private use area
        addBMP(0xFFFD, 50, "U+FFFD_replacement"); // replacement character

        // ── Group 4: NFC normalisation (4 tests) ──────────────────────────
        //
        // Minecraft's Text system may apply NFC normalisation when deserialising
        // the name, collapsing some precomposed/decomposed pairs.
        // Compare tests 17 vs 18: if they produce different got_str lengths,
        // the server normalised one of them.
        addBMP(0x00E9,  50, "U+00E9_precomp-e-acute");  // é (precomposed)
        addStr("é".repeat(25), "U+0065+0301_decomp-e-acute_x25"); // e + combining acute, 50 code units
        addBMP(0x212B,  50, "U+212B_angstrom");   // Ångström sign — NFC→U+00C5
        addBMP(0x00C5,  50, "U+00C5_A-ring");     // Å — the NFC form of U+212B

        // ── Group 5: Special whitespace / invisible chars (4 tests) ──────
        //
        // These may be stripped, normalised to regular space, or preserved.
        addBMP(0x00A0, 50, "U+00A0_NBSP");        // non-breaking space
        addBMP(0x200B, 50, "U+200B_ZWSP");        // zero-width space
        addBMP(0x2028, 50, "U+2028_line-sep");    // line separator
        addBMP(0xFEFF, 50, "U+FEFF_BOM");         // byte-order mark / ZWNBSP

        // ── Group 6: Extended ASCII density check (2 tests) ──────────────
        //
        // If all Latin-1 chars are accepted unmodified, a 256-char alphabet
        // gives 8 bits/char vs base64's 6 bits/char (33 % gain).
        addBMP(0x00FF, 50, "U+00FF_y-diaeresis"); // last Latin-1 char
        addBMP(0x0100, 50, "U+0100_A-macron");    // first char beyond Latin-1
    }

    // ── Test helpers ──────────────────────────────────────────────────────

    /** Add a test using a single BMP character repeated {@code n} times. */
    private static void addBMP(int cp, int n, String label) {
        char c = (char) cp;
        char[] arr = new char[n];
        Arrays.fill(arr, c);
        labels.add(label);
        strings.add(new String(arr));
    }

    /** Add a test using a supplementary-plane character (2 code units) repeated {@code n} times. */
    private static void addSupp(int cp, String tag, int n) {
        String ch = new String(Character.toChars(cp));
        String s  = ch.repeat(n);
        labels.add(tag + "_x" + n
                + "(" + s.length() + "u_" + s.codePointCount(0, s.length()) + "cp)");
        strings.add(s);
    }

    /** Add a test with a fully specified string. */
    private static void addStr(String s, String label) {
        labels.add(label + "_(" + s.length() + "u_" + s.codePointCount(0, s.length()) + "cp)");
        strings.add(s);
    }

    // ── Tick state machine ────────────────────────────────────────────────

    private static void tick(MinecraftClient client) {
        // Armed: waiting for the player to open an anvil.
        if (armed && phase == Phase.IDLE) {
            if (client.currentScreen instanceof AnvilScreen) {
                armed = false;
                start(client, waitTicks); // anvil is now open — begin
            }
            return;
        }
        if (phase == Phase.IDLE) return;
        if (client.world == null || client.player == null) { abort(client, "disconnected"); return; }
        if (!(client.currentScreen instanceof AnvilScreen)) { abort(client, "anvil closed"); return; }
        if (timer > 0) { timer--; return; }

        AnvilScreenHandler h = (AnvilScreenHandler) client.player.currentScreenHandler;

        switch (phase) {
            case AFTER_RENAME -> {
                client.interactionManager.clickSlot(h.syncId, 2, 0, SlotActionType.PICKUP, client.player);
                phase = Phase.AFTER_TAKE;
                timer = waitTicks;
            }
            case AFTER_TAKE -> {
                ItemStack cursor = h.getCursorStack();
                logResult(cursor);

                if (cursor.isEmpty()) {
                    emptyStreak++;
                    if (emptyStreak >= 3) {
                        // Three consecutive empty outputs after at least one success
                        // almost certainly means "Too Expensive!", not character rejection.
                        abort(client, "3 consecutive empty outputs — item likely Too Expensive. "
                                + "Replace the item in the anvil and re-run the command.");
                        return;
                    }
                    // Single empty is probably a rejected character — continue.
                    advance(client);
                } else {
                    emptyStreak = 0;
                    // Return the item to the input slot.
                    client.interactionManager.clickSlot(h.syncId, 0, 0, SlotActionType.PICKUP, client.player);
                    phase = Phase.AFTER_RETURN;
                    timer = waitTicks;
                }
            }
            case AFTER_RETURN -> advance(client);
        }
    }

    private static void sendRename(MinecraftClient client) {
        client.player.networkHandler.sendPacket(new RenameItemC2SPacket(strings.get(idx)));
    }

    private static void advance(MinecraftClient client) {
        idx++;
        if (idx >= strings.size()) { done(client); return; }
        sendRename(client);
        phase = Phase.AFTER_RENAME;
        timer = waitTicks;
    }

    // ── Logging ───────────────────────────────────────────────────────────

    private static void logResult(ItemStack cursor) {
        String s      = strings.get(idx);
        int    sentU  = s.length();
        int    sentCP = s.codePointCount(0, s.length());
        boolean empty = cursor.isEmpty();

        String got    = "";
        int    gotU   = 0;
        int    gotCP  = 0;
        boolean match = false;

        if (!empty) {
            Text name = cursor.get(DataComponentTypes.CUSTOM_NAME);
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
        msg(client, "§aAlphabet test complete — " + strings.size() + " cases. Share the .tsv file.");
    }

    private static void abort(MinecraftClient client, String reason) {
        phase = Phase.IDLE;
        close();
        if (writer != null) { writer.flush(); writer.close(); writer = null; }
        msg(client, "§cAlphabet test stopped: " + reason);
    }

    private static void close() {
        if (writer != null) { writer.flush(); writer.close(); writer = null; }
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), false);
    }
}
