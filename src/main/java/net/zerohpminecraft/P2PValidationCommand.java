package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.CRC32;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Stage 0 protocol test for P2P mapart transfer.
 *
 * Tests a real CjkCodec-encoded CatalogueEntry transfer over /msg with
 * per-fragment scrambling, ack, and retransmit.
 *
 * Commands:
 *   /loominary p2p-test capture           - toggle whisper/game-msg logging
 *   /loominary p2p-test send <alt> [id]   - send first (or id-matched) catalogue entry to alt
 *   /loominary p2p-test recv              - toggle receive mode (run on alt)
 *
 * DELETE this class before implementing Stage 3.
 */
public class P2PValidationCommand {

    // ── Wire constants ─────────────────────────────────────────────────────

    /** Magic sentinel — U+8E08, outside CjkCodec alphabet (U+4E00–U+8DFF). */
    static final char MAGIC = '踈';

    static final int BASE = CjkCodec.ALPHA_BASE;  // 0x4E00
    static final int SIZE = CjkCodec.ALPHA_SIZE;  // 16384

    static final int DATA = 0;
    static final int ACK  = 1;

    /**
     * Payload CJK chars per fragment.
     * Body budget: 230 chars (conservative: 256 cmd limit − "/msg " − 16-char username − " ").
     * Wire overhead: MAGIC(1) + NONCE(1) + TYPE(1) + SESSION(1) + SEQ(1) + TOTAL(1) = 6.
     * Payload: 230 − 6 = 224 chars → 224 × 14 / 8 = 392 bytes per fragment.
     */
    static final int MAX_PAYLOAD = 224;

    // Defaults overridden per send invocation
    private static int sendPauseMs   = 10_000; // mandatory gap between any two sends
    private static int ackTimeoutMs  =  5_000; // warn + plan retry if no ACK within this window
    private static int maxTries      = 0;      // 0 = unlimited

    static final Gson GSON = new Gson();

    // ── Shared flags ───────────────────────────────────────────────────────

    private static boolean captureActive = false;
    private static boolean inHandler     = false; // ALLOW_GAME reentrancy guard

    // ── Sender state (main) ────────────────────────────────────────────────

    // PAUSE: waiting for the mandatory send gap to expire (ACK may or may not have arrived yet)
    private enum SendPhase { IDLE, SEND, PAUSE, DONE, FAILED }

    private static SendPhase    sendPhase        = SendPhase.IDLE;
    private static String       sendTarget;
    private static List<String> sendFrags;        // unscrambled CJK payload per fragment
    private static int[]        sendCrcs;         // expected CRC32 per fragment
    private static int          sendIdx;          // current fragment index
    private static int          sendTries;        // attempts on current fragment (reset on ack)
    private static long         sendPauseUntil;   // earliest time the next send may occur
    private static long         sendAckDeadline;  // when to log "no ack yet, will retry"
    private static boolean      ackedCurrentFrag; // did an ACK arrive for sendIdx?
    private static boolean      ackTimeoutLogged; // suppress repeat "no ack" log lines
    private static int          sendSession;      // random ID for this transfer; resets alt on mismatch
    private static int          lastSentNonce = -1; // nonce of most recently sent DATA (for echo detection)
    private static String       sendEntryId;        // catalogue entry ID being transferred (for resume)

    // ── Receiver state (alt) ──────────────────────────────────────────────

    private static boolean              recvActive  = false;
    private static String               recvFrom;
    private static final Map<Integer, String> recvFrags = new LinkedHashMap<>();
    private static int                  recvTotal   = -1;
    private static int                  recvSession = -1; // learned from first DATA; -1 = unknown
    private static final Set<Integer>   recvRecentAckNonces = new HashSet<>(); // recent sent ACK nonces (for echo detection)

    // Pending ACK: re-sent periodically until a new DATA arrives (works around 2b2t ACK delivery drops)
    private static String recvPendingAckSender;
    private static String recvPendingAckPayload; // unscrambled 5-char payload (CRC + nextExpected)
    private static int    recvPendingAckSession;
    private static int    recvPendingAckSeq;
    private static long   recvLastAckSentTime  = 0;
    private static int    recvAckResendCount   = 0;  // how many times we've re-sent the pending ACK
    private static final int RECV_ACK_RESEND_MS = 3_000;

    // ── Persistence DTOs ──────────────────────────────────────────────────

    private static class SendSave {
        String entryId;
        String target;
        int    session;
        int    nextIdx;
        int    pauseMs;
        int    ackTimeoutMs;
        int    maxTries;
    }

    private static class RecvSave {
        String             from;
        int                session;
        int                total;
        Map<String,String> frags;
        String             pendingAckPayload;
        int                pendingAckSession;
        int                pendingAckSeq;
    }

    private static Path senderSavePath() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("config/loominary_p2p_send.json");
    }

    private static Path receiverSavePath() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("config/loominary_p2p_recv.json");
    }

    private static void saveSenderState() {
        if (sendEntryId == null || sendFrags == null) return;
        try {
            SendSave s    = new SendSave();
            s.entryId     = sendEntryId;
            s.target      = sendTarget;
            s.session     = sendSession;
            s.nextIdx     = sendIdx;
            s.pauseMs     = sendPauseMs;
            s.ackTimeoutMs = ackTimeoutMs;
            s.maxTries    = maxTries;
            Files.writeString(senderSavePath(), GSON.toJson(s));
        } catch (Exception ignored) {}
    }

    private static void clearSenderSave() {
        try { Files.deleteIfExists(senderSavePath()); } catch (Exception ignored) {}
    }

    private static void saveReceiverState() {
        if (!recvActive || recvFrom == null) return;
        try {
            RecvSave s = new RecvSave();
            s.from               = recvFrom;
            s.session            = recvSession;
            s.total              = recvTotal;
            s.frags              = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : recvFrags.entrySet())
                s.frags.put(String.valueOf(e.getKey()), e.getValue());
            s.pendingAckPayload  = recvPendingAckPayload;
            s.pendingAckSession  = recvPendingAckSession;
            s.pendingAckSeq      = recvPendingAckSeq;
            Files.writeString(receiverSavePath(), GSON.toJson(s));
        } catch (Exception ignored) {}
    }

    private static void clearReceiverSave() {
        try { Files.deleteIfExists(receiverSavePath()); } catch (Exception ignored) {}
    }

    // ── Registration ──────────────────────────────────────────────────────

    public static void register() {

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || inHandler) return true;
            inHandler = true;
            try { return handleGameMessage(message); }
            finally { inHandler = false; }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean senderActive = sendPhase == SendPhase.SEND || sendPhase == SendPhase.PAUSE;
            if (!senderActive && !recvActive) return;
            if (client.getNetworkHandler() == null) {
                if (senderActive) {
                    saveSenderState();
                    abortSend(client, "disconnected — use /loominary p2p-test resume to continue");
                }
                return;
            }
            if (senderActive) tickSend(client);
            if (recvActive)   tickRecv(client);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("loominary").then(literal("p2p-test")

                .then(literal("capture").executes(ctx -> {
                    captureActive = !captureActive;
                    ctx.getSource().sendFeedback(Text.literal(
                        "§a[P2P] Capture " + (captureActive ? "ON" : "OFF")));
                    return 1;
                }))

                .then(literal("recv").executes(ctx -> {
                    recvActive = !recvActive;
                    if (!recvActive) {
                        recvFrom = null; recvFrags.clear(); recvTotal = -1; recvSession = -1;
                        recvPendingAckSender = null; recvRecentAckNonces.clear();
                        ctx.getSource().sendFeedback(Text.literal("§a[P2P] Recv OFF — state cleared."));
                    } else {
                        // Try to restore from save file
                        Path rpath = receiverSavePath();
                        boolean restored = false;
                        if (Files.exists(rpath)) {
                            try {
                                RecvSave s = GSON.fromJson(Files.readString(rpath), RecvSave.class);
                                recvFrom             = s.from;
                                recvSession          = s.session;
                                recvTotal            = s.total;
                                recvFrags.clear();
                                if (s.frags != null)
                                    s.frags.forEach((k, v) -> recvFrags.put(Integer.parseInt(k), v));
                                recvPendingAckSender  = s.from;
                                recvPendingAckPayload = s.pendingAckPayload;
                                recvPendingAckSession = s.pendingAckSession;
                                recvPendingAckSeq     = s.pendingAckSeq;
                                recvLastAckSentTime   = 0; // trigger immediate resend
                                recvAckResendCount    = 0;
                                restored = true;
                                ctx.getSource().sendFeedback(Text.literal(String.format(
                                    "§a[P2P] Recv ON — restored from disk: from=%s  session=%04X  have=%d/%d frags.",
                                    s.from, s.session, recvFrags.size(), s.total)));
                            } catch (Exception e) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§e[P2P] Could not restore recv state: " + e.getMessage()));
                            }
                        }
                        if (!restored)
                            ctx.getSource().sendFeedback(Text.literal("§a[P2P] Recv ON — waiting for transfer."));
                    }
                    return 1;
                }))

                .then(literal("send")
                    .then(argument("alt", StringArgumentType.word())
                        .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), null, 10, 5, 0))
                        .then(argument("pause_s", IntegerArgumentType.integer(5, 300))
                            .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), null, getInteger(ctx, "pause_s"), 5, 0))
                            .then(argument("timeout_s", IntegerArgumentType.integer(3, 120))
                                .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), null, getInteger(ctx, "pause_s"), getInteger(ctx, "timeout_s"), 0))
                                .then(argument("max_tries", IntegerArgumentType.integer(1, 100))
                                    .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), null, getInteger(ctx, "pause_s"), getInteger(ctx, "timeout_s"), getInteger(ctx, "max_tries"))))))
                        .then(argument("id", StringArgumentType.word())
                            .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), getString(ctx, "id"), 10, 5, 0))
                            .then(argument("pause_s", IntegerArgumentType.integer(5, 300))
                                .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), getString(ctx, "id"), getInteger(ctx, "pause_s"), 5, 0))
                                .then(argument("timeout_s", IntegerArgumentType.integer(3, 120))
                                    .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), getString(ctx, "id"), getInteger(ctx, "pause_s"), getInteger(ctx, "timeout_s"), 0))
                                    .then(argument("max_tries", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> startSend(ctx.getSource(), getString(ctx, "alt"), getString(ctx, "id"), getInteger(ctx, "pause_s"), getInteger(ctx, "timeout_s"), getInteger(ctx, "max_tries")))))))))

                .then(literal("resume").executes(ctx -> resumeSend(ctx.getSource())))
            ))
        );
    }

    // ── Sender ────────────────────────────────────────────────────────────

    private static int startSend(FabricClientCommandSource source, String alt, String idPrefix, int pauseS, int timeoutS, int maxTriesArg) {
        if (sendPhase == SendPhase.SEND || sendPhase == SendPhase.PAUSE) {
            source.sendError(Text.literal("§c[P2P] Transfer already in progress."));
            return 0;
        }

        CatalogueState.CatalogueEntry entry = null;
        for (CatalogueState.CatalogueEntry e : CatalogueState.entries) {
            if (e.tiles != null && !e.tiles.isEmpty()) {
                if (idPrefix == null || e.id.startsWith(idPrefix)) { entry = e; break; }
            }
        }
        if (entry == null) {
            source.sendError(Text.literal("§c[P2P] No catalogue entry with tiles found"
                + (idPrefix != null ? " matching id: " + idPrefix : "") + "."));
            return 0;
        }

        byte[] json;
        byte[] compressed;
        String cjk;
        try {
            json       = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
            compressed = Zstd.compress(json, 9);
            cjk        = CjkCodec.encode(compressed);
        } catch (Exception e) {
            source.sendError(Text.literal("§c[P2P] Encode failed: " + e.getMessage()));
            return 0;
        }

        int total = (cjk.length() + MAX_PAYLOAD - 1) / MAX_PAYLOAD;
        sendFrags = new ArrayList<>(total);
        sendCrcs  = new int[total];
        for (int i = 0; i < total; i++) {
            String frag = cjk.substring(i * MAX_PAYLOAD, Math.min((i + 1) * MAX_PAYLOAD, cjk.length()));
            sendFrags.add(frag);
            sendCrcs[i] = crc32(frag);
        }

        clearSenderSave();
        sendEntryId      = entry.id;
        sendTarget       = alt;
        sendIdx          = 0;
        sendTries        = 0;
        ackedCurrentFrag = false;
        ackTimeoutLogged = false;
        sendSession      = randomNonce(); // unique per transfer; alt resets its buffer on mismatch
        sendPauseMs      = pauseS   * 1000;
        ackTimeoutMs     = timeoutS * 1000;
        maxTries         = maxTriesArg;
        sendPhase        = SendPhase.SEND;

        source.sendFeedback(Text.literal(String.format(
            "§a[P2P] Sending §f\"%s\"§a → §f%s§a | json=%d B  zstd=%d B  %d chars  %d frags  pause=%ds  ack=%ds  max_tries=%s",
            entry.title, alt, json.length, compressed.length, cjk.length(), total, pauseS, timeoutS,
            maxTriesArg > 0 ? String.valueOf(maxTriesArg) : "∞")));
        return 1;
    }

    private static void tickSend(MinecraftClient mc) {
        long now = System.currentTimeMillis();

        if (sendPhase == SendPhase.SEND) {
            if (maxTries > 0 && sendTries >= maxTries) {
                abortSend(mc, String.format("frag %d/%d: no ack after %d tries",
                        sendIdx + 1, sendFrags.size(), maxTries));
                return;
            }
            sendTries++;
            ackedCurrentFrag = false;
            ackTimeoutLogged = false;
            int nonce = randomNonce();
            String wire = buildWire(DATA, sendSession, sendIdx, sendFrags.size(), sendFrags.get(sendIdx), nonce);
            mc.getNetworkHandler().sendCommand("msg " + sendTarget + " " + wire);
            lastSentNonce   = nonce;
            sendPauseUntil  = now + sendPauseMs;
            sendAckDeadline = now + ackTimeoutMs;
            sendPhase       = SendPhase.PAUSE;
            String triesStr = maxTries > 0 ? sendTries + "/" + maxTries : String.valueOf(sendTries);
            say(mc, String.format("§7[P2P→] frag %d/%d  try %s  pause=%ds  session=%04X  nonce=U+%04X  wire=%d",
                    sendIdx + 1, sendFrags.size(), triesStr, sendPauseMs / 1000,
                    sendSession, BASE + nonce, wire.length()));

        } else if (sendPhase == SendPhase.PAUSE) {
            // Warn once if ACK window elapsed without a reply
            if (!ackedCurrentFrag && !ackTimeoutLogged && now > sendAckDeadline) {
                String limitStr = maxTries > 0 ? "/" + maxTries : "";
                long remainMs = sendPauseUntil - now;
                say(mc, String.format("§e[P2P] No ACK on frag %d (try %d%s) — retrying in %.1fs",
                        sendIdx + 1, sendTries, limitStr, remainMs / 1000.0));
                ackTimeoutLogged = true;
            }
            // Mandatory pause expired — advance or retry
            if (now >= sendPauseUntil) {
                if (ackedCurrentFrag) {
                    // sendIdx was already advanced (possibly by >1) in onAckReceived
                    sendTries = 0;
                    if (sendIdx >= sendFrags.size()) {
                        sendPhase = SendPhase.DONE;
                        clearSenderSave();
                        say(mc, "§a§l[P2P] Transfer complete! " + sendFrags.size() + " fragments delivered.");
                    } else {
                        sendPhase = SendPhase.SEND;
                    }
                } else {
                    sendPhase = SendPhase.SEND; // retry same fragment
                }
            }
        }
    }

    private static void onAckReceived(MinecraftClient mc, int ackedSeq, int ackCrc, int nextExpected) {
        if (sendPhase != SendPhase.PAUSE) {
            // Not in PAUSE: likely the 2b2t echo of an outbound ACK we sent (on the receiver side),
            // or a stale ACK that arrived after the retry timer already fired.
            say(mc, String.format("§7[P2P] ignoring ack seq=%d (state=%s)", ackedSeq, sendPhase));
            return;
        }
        if (ackedSeq != sendIdx) {
            say(mc, String.format("§8[P2P] Stale ACK seq=%d (now on %d) — ignoring",
                    ackedSeq, sendIdx));
            return;
        }
        if (ackCrc != sendCrcs[sendIdx]) {
            say(mc, String.format("§c[P2P] CRC mismatch! expected=%08X got=%08X — will retry after pause",
                    sendCrcs[sendIdx], ackCrc));
            return;
        }
        // Jump sendIdx to nextExpected so we skip frags the receiver already has.
        int advanceTo = Math.max(sendIdx + 1, Math.min(nextExpected, sendFrags.size()));
        say(mc, String.format("§a[P2P✓] frag %d/%d acked  crc=%08X  next=%d",
                sendIdx + 1, sendFrags.size(), ackCrc, advanceTo));
        sendIdx          = advanceTo;
        ackedCurrentFrag = true;
        saveSenderState();
    }

    private static void abortSend(MinecraftClient mc, String reason) {
        sendPhase = SendPhase.FAILED;
        sendFrags = null;
        say(mc, "§c§l[P2P] Transfer FAILED: " + reason);
    }

    private static int resumeSend(FabricClientCommandSource source) {
        if (sendPhase == SendPhase.SEND || sendPhase == SendPhase.PAUSE) {
            source.sendError(Text.literal("§c[P2P] Transfer already in progress."));
            return 0;
        }
        Path path = senderSavePath();
        if (!Files.exists(path)) {
            source.sendError(Text.literal("§c[P2P] No saved sender state found."));
            return 0;
        }
        try {
            SendSave sv = GSON.fromJson(Files.readString(path), SendSave.class);
            CatalogueState.CatalogueEntry entry = null;
            for (CatalogueState.CatalogueEntry e : CatalogueState.entries) {
                if (e.id.equals(sv.entryId) && e.tiles != null && !e.tiles.isEmpty()) { entry = e; break; }
            }
            if (entry == null) {
                source.sendError(Text.literal("§c[P2P] Saved entry id=" + sv.entryId + " not found in catalogue."));
                return 0;
            }
            byte[] json       = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
            byte[] compressed = Zstd.compress(json, 9);
            String cjk        = CjkCodec.encode(compressed);
            int total = (cjk.length() + MAX_PAYLOAD - 1) / MAX_PAYLOAD;
            sendFrags = new ArrayList<>(total);
            sendCrcs  = new int[total];
            for (int i = 0; i < total; i++) {
                String frag = cjk.substring(i * MAX_PAYLOAD, Math.min((i + 1) * MAX_PAYLOAD, cjk.length()));
                sendFrags.add(frag);
                sendCrcs[i] = crc32(frag);
            }
            sendEntryId      = sv.entryId;
            sendTarget       = sv.target;
            sendSession      = sv.session;
            sendIdx          = Math.min(sv.nextIdx, total);
            sendPauseMs      = sv.pauseMs;
            ackTimeoutMs     = sv.ackTimeoutMs;
            maxTries         = sv.maxTries;
            sendTries        = 0;
            ackedCurrentFrag = false;
            ackTimeoutLogged = false;
            sendPhase        = SendPhase.SEND;
            source.sendFeedback(Text.literal(String.format(
                "§a[P2P] Resuming §f\"%s\"§a → §f%s §afrom frag %d/%d  session=%04X",
                entry.title, sv.target, sv.nextIdx + 1, total, sv.session)));
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§c[P2P] Resume failed: " + e.getMessage()));
            return 0;
        }
    }

    private static void tickRecv(MinecraftClient mc) {
        if (recvPendingAckSender == null) return;
        long now = System.currentTimeMillis();
        if (now - recvLastAckSentTime < RECV_ACK_RESEND_MS) return;
        recvAckResendCount++;
        int nonce = randomNonce();
        String wire = buildWire(ACK, recvPendingAckSession, recvPendingAckSeq, 1, recvPendingAckPayload, nonce);
        mc.getNetworkHandler().sendCommand("msg " + recvPendingAckSender + " " + wire);
        recvRecentAckNonces.add(nonce);
        recvLastAckSentTime = now;
        say(mc, String.format("§7[P2P-ACK↩] re-send #%d  seq=%d  nonce=U+%04X",
                recvAckResendCount, recvPendingAckSeq, BASE + nonce));
    }

    // ── Receiver ──────────────────────────────────────────────────────────

    private static void onDataReceived(MinecraftClient mc, String sender,
                                       int session, int seq, int total, String payload) {
        if (!recvActive) {
            // Silently ignore: almost always the server echoing our own /msg back to us.
            if (captureActive)
                say(mc, "§7[P2P-ECHO] server echoed frag " + (seq + 1) + " — recv is off, ignoring");
            return;
        }

        // New session from the same or a new sender → old transfer is stale, reset.
        if (recvSession != -1 && recvSession != session) {
            say(mc, String.format("§e[P2P-RECV] New session %04X (was %04X) — discarding %d buffered frag(s).",
                    session, recvSession, recvFrags.size()));
            recvFrom = null; recvFrags.clear(); recvTotal = -1; recvSession = -1;
        }

        if (recvFrom == null) {
            recvFrom    = sender;
            recvSession = session;
            say(mc, String.format("§a[P2P-RECV] Transfer started from §f%s §a— session=%04X  expecting %d frags.",
                    sender, session, total));
        } else if (!recvFrom.equals(sender)) {
            say(mc, "§e[P2P-RECV] Ignoring DATA from " + sender + " (expecting " + recvFrom + ")");
            return;
        }
        recvTotal = total;

        boolean dup = recvFrags.containsKey(seq);
        recvFrags.put(seq, payload);
        int crc = crc32(payload);
        say(mc, String.format("§b[P2P←] frag %d/%d  crc=%08X  have=%d%s",
                seq + 1, total, crc, recvFrags.size(), dup ? "  §e(dup)" : ""));

        // Always ack — sender may not have received a previous ack
        if (mc.getNetworkHandler() != null) {
            // Compute lowest seq we still need so main can skip ahead on resume
            int nextExpected = 0;
            while (recvFrags.containsKey(nextExpected)) nextExpected++;

            int nonce = randomNonce();
            String ackPayload = encodeCrc(crc) + (char)(BASE + nextExpected);
            String ackWire = buildWire(ACK, session, seq, 1, ackPayload, nonce);
            mc.getNetworkHandler().sendCommand("msg " + sender + " " + ackWire);
            recvRecentAckNonces.add(nonce);
            recvLastAckSentTime   = System.currentTimeMillis();
            recvAckResendCount    = 0;
            recvPendingAckSender  = sender;
            recvPendingAckPayload = ackPayload;
            recvPendingAckSession = session;
            recvPendingAckSeq     = seq;
            saveReceiverState();
            say(mc, String.format("§a[P2P-ACK→] seq=%d  crc=%08X  next=%d  nonce=U+%04X  wire=%d",
                    seq, crc, nextExpected, BASE + nonce, ackWire.length()));
        }

        // Check for completion
        if (recvTotal > 0 && recvFrags.size() == recvTotal) {
            boolean haveAll = true;
            for (int i = 0; i < recvTotal; i++) if (!recvFrags.containsKey(i)) { haveAll = false; break; }
            if (haveAll) reassemble(mc);
        }
    }

    private static void reassemble(MinecraftClient mc) {
        say(mc, "§a[P2P] All " + recvTotal + " frags received — reassembling...");
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < recvTotal; i++) sb.append(recvFrags.get(i));

            byte[] compressed = CjkCodec.decode(sb.toString());
            long contentSize  = Zstd.getFrameContentSize(compressed);
            if (contentSize <= 0 || contentSize > 20_000_000)
                throw new IllegalStateException("Bad zstd frame size: " + contentSize);

            byte[] json = Zstd.decompress(compressed, (int) contentSize);
            CatalogueState.CatalogueEntry entry = GSON.fromJson(
                    new String(json, StandardCharsets.UTF_8),
                    CatalogueState.CatalogueEntry.class);

            entry.sourcePeer = recvFrom;
            entry.dateAdded  = LocalDate.now().toString();
            CatalogueState.entries.removeIf(e -> e.id.equals(entry.id));
            CatalogueState.entries.add(entry);
            CatalogueState.save();

            say(mc, String.format("§a§l[P2P] §f\"%s\" §aadded to catalogue! tiles=%d  json=%d B",
                    entry.title, entry.tiles != null ? entry.tiles.size() : 0, json.length));
        } catch (Exception e) {
            say(mc, "§c[P2P] Reassembly failed: " + e.getMessage());
        } finally {
            clearReceiverSave();
            recvFrom             = null;
            recvFrags.clear();
            recvTotal            = -1;
            recvSession          = -1;
            recvPendingAckSender = null;
            recvRecentAckNonces.clear();
        }
    }

    // ── Message dispatch ──────────────────────────────────────────────────

    private static boolean handleGameMessage(Text message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return true;

        String sender = extractSender(message);
        String body   = extractBody(message);
        boolean isWhisper = sender != null && body != null;

        // Log non-P2P traffic when capture is on
        if (captureActive && !isWhisper) {
            say(mc, "§7[CAP] " + truncate(message.getString(), 100));
        }

        // Detect 2b2t "not online" response and back off receiver ACK resends
        if (!isWhisper && recvPendingAckSender != null) {
            String rawLower = message.getString().toLowerCase();
            if (rawLower.contains("not online") || rawLower.contains("player not found")) {
                recvLastAckSentTime = System.currentTimeMillis() + 57_000L; // ~60s back-off
                say(mc, "§e[P2P-RECV] Sender offline — pausing ACK resends for ~60s");
            }
        }

        if (!isWhisper || body.isEmpty()) return true;

        // Non-P2P whisper
        if (body.charAt(0) != MAGIC) {
            if (captureActive)
                say(mc, "§3[WHISPER] §f" + sender + "§3: " + truncate(body, 80));
            return true;
        }

        // P2P message — suppress raw display, parse and route
        try {
            if (body.length() < 6) throw new IllegalArgumentException("Message too short: " + body.length());
            int nonce   = body.charAt(1) - BASE;
            int type    = descramble(body.charAt(2), nonce);
            int session = descramble(body.charAt(3), nonce);
            int seq     = descramble(body.charAt(4), nonce);
            int total   = descramble(body.charAt(5), nonce);

            // Reconstruct unscrambled payload
            StringBuilder payloadSb = new StringBuilder(body.length() - 6);
            for (int i = 6; i < body.length(); i++) {
                payloadSb.append((char)(BASE + descramble(body.charAt(i), nonce)));
            }
            String payload = payloadSb.toString();

            // Detect 2b2t echoes of our own sends before routing
            boolean isOwnDataEcho = type == DATA && sender.equals(sendTarget)
                    && session == sendSession && nonce == lastSentNonce && lastSentNonce >= 0;
            boolean isOwnAckEcho  = type == ACK && recvActive && sender.equals(recvFrom)
                    && recvRecentAckNonces.contains(nonce);

            if (isOwnDataEcho) {
                say(mc, String.format("§8[P2P-ECHO] server reflected own DATA frag %d/%d (try %d)",
                        seq + 1, total, sendTries));
                return true;
            }
            if (isOwnAckEcho) {
                say(mc, String.format("§8[P2P-ECHO] server reflected own ACK seq=%d", seq));
                return true;
            }

            say(mc, String.format("§d[P2P] from=§f%s §dtype=%s session=%04X seq=%d total=%d nonce=U+%04X payload=%d",
                    sender, type == DATA ? "DATA" : type == ACK ? "ACK" : "?" + type,
                    session, seq, total, BASE + nonce, payload.length()));

            if (type == ACK) {
                int crc          = decodeCrc(payload);
                int nextExpected = payload.length() >= 5 ? (payload.charAt(4) - BASE) : seq + 1;
                onAckReceived(mc, seq, crc, nextExpected);
            } else if (type == DATA) {
                onDataReceived(mc, sender, session, seq, total, payload);
            } else {
                say(mc, "§c[P2P] Unknown type " + type + " from " + sender);
            }
        } catch (Exception e) {
            say(mc, "§c[P2P] Parse error (from " + sender + "): " + e.getMessage());
        }
        return true; // let raw whisper show in chat alongside our formatted logs
    }

    // ── Wire codec ────────────────────────────────────────────────────────

    /**
     * Builds a wire message.
     * Format: MAGIC(1) + NONCE(1) + scramble(TYPE(1) SESSION(1) SEQ(1) TOTAL(1) PAYLOAD(N))
     * Scramble: value → (value + nonce) % SIZE, applied to each field's offset from BASE.
     * NONCE and MAGIC are transmitted unscrambled so any receiver can identify and decode.
     * SESSION is random per transfer; the receiver resets its buffer when it changes.
     */
    private static String buildWire(int type, int session, int seq, int total, String payload, int nonce) {
        StringBuilder sb = new StringBuilder(6 + payload.length());
        sb.append(MAGIC);
        sb.append((char)(BASE + nonce));
        sb.append(scramble(type,    nonce));
        sb.append(scramble(session, nonce));
        sb.append(scramble(seq,     nonce));
        sb.append(scramble(total,   nonce));
        for (int i = 0; i < payload.length(); i++) {
            sb.append(scramble(payload.charAt(i) - BASE, nonce));
        }
        return sb.toString();
    }

    private static char scramble(int value, int nonce) {
        return (char)(BASE + (value + nonce) % SIZE);
    }

    private static int descramble(char c, int nonce) {
        return ((c - BASE) - nonce + SIZE) % SIZE;
    }

    // ── CRC32 helpers ─────────────────────────────────────────────────────

    private static int crc32(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_16BE));
        return (int) crc.getValue();
    }

    /**
     * Encodes 4 CRC bytes as a 4-char CJK string.
     * Each byte maps to BASE + byte_value (values 0–255 ⊂ SIZE = 16384).
     * After scrambling with a fresh nonce, the 4 chars look completely
     * different from any prior ack even when the checksum is identical.
     */
    private static String encodeCrc(int crc32) {
        char[] out = new char[4];
        out[0] = (char)(BASE + ((crc32 >>> 24) & 0xFF));
        out[1] = (char)(BASE + ((crc32 >>> 16) & 0xFF));
        out[2] = (char)(BASE + ((crc32 >>>  8) & 0xFF));
        out[3] = (char)(BASE +  (crc32         & 0xFF));
        return new String(out);
    }

    private static int decodeCrc(String s) {
        if (s.length() < 4) return 0;
        return ((s.charAt(0) - BASE) << 24)
             | ((s.charAt(1) - BASE) << 16)
             | ((s.charAt(2) - BASE) <<  8)
             |  (s.charAt(3) - BASE);
    }

    // ── Whisper extraction (2b2t format confirmed in Exp 3) ───────────────

    /** root[empty] + sib[0][empty,light_purple] + sib[1][Name,light_purple] + sib[2][" whispers: body",…] */
    private static String extractSender(Text message) {
        List<Text> sibs = message.getSiblings();
        return sibs.size() >= 3 ? sibs.get(1).getString().trim() : null;
    }

    private static String extractBody(Text message) {
        List<Text> sibs = message.getSiblings();
        if (sibs.size() < 3) return null;
        String raw    = sibs.get(2).getString();
        int colonIdx  = raw.indexOf(": ");
        return colonIdx >= 0 ? raw.substring(colonIdx + 2) : null;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static int randomNonce() {
        return (int)(Math.random() * SIZE);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void say(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }
}
