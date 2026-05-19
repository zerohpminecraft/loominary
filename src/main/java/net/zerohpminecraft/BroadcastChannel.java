package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Hybrid gossip-pull protocol for catalogue sharing.
 * Peer discovery via steganographic public-chat ANNOUNCE beacons.
 * Data transfer via CJK-encoded /msg whispers.
 */
public final class BroadcastChannel {

    // ── Protocol constants ─────────────────────────────────────────────────

    static final int  FRAGMENT_DATA_BYTES    = 384;   // verified: 228 CJK → 397 usable − 13 header
    static final long SEND_INTERVAL_MS      = 20_000L; // min wall-clock ms between outbound whispers
    static final long ACK_TIMEOUT_MS        = 5_000L;  // ms without ACK before scheduling retransmit
    static final int  BEACON_INTERVAL_TICKS = 1200;    // 60 s (tick-based is fine for beacon)
    static final int  PRIO_RETRANSMIT       = 0;       // unACKed messages retried — highest priority
    static final int  PRIO_ACK              = 1;       // ACK responses
    static final int  PRIO_CTRL             = 2;       // control messages (GET_INDEX, REQUEST, …)
    static final int  PRIO_DATA             = 3;       // data fragments
    static final int  PRIO_INDEX_DONE       = 4;       // INDEX_DONE — must drain after INDEX_ENTRY retransmits
    static final int  MAX_PEERS              = 128;
    static final int  MAX_NONCE_HISTORY      = 64;
    static final long PEER_EVICT_MS          = 30 * 60 * 1000L;  // 30 min
    static final long DOWNLOAD_TIMEOUT_MS    = 5  * 60 * 1000L;  // 5 min
    static final long INDEX_TIMEOUT_MS       = 30_000L;           // 30 s

    static final char   MAGIC = '踈'; // U+8E08, outside CjkCodec alphabet (U+4E00–U+8DFF)
    // ANNOUNCE detection: template matching + Y-range gate [60..67]. U+200B stripped by 2b2t.

    public static final byte MSG_GET_INDEX   = 0x02;
    public static final byte MSG_INDEX_ENTRY = 0x03;
    public static final byte MSG_REQUEST     = 0x04;
    public static final byte MSG_DATA        = 0x05;
    public static final byte MSG_ACK         = 0x06;
    public static final byte MSG_INDEX_DONE  = 0x07;
    public static final byte MSG_ACK_CTRL    = 0x08;

    // ── Inner classes ──────────────────────────────────────────────────────

    public static class PeerInfo {
        public String name;
        public long   lastSeen;
        public long   catalogueCrc  = -1; // -1 forces first GET_INDEX
        public int    sharedCount;
        public boolean indexRequested;
        public long   indexRequestedAt;
        public final Map<String, RemoteEntry> index = new LinkedHashMap<>();
    }

    public static class RemoteEntry {
        public String  entryId;          // 32-char hex
        public String  title;
        public String  author;
        public int     gridCols, gridRows, frameCount;
        public int     compressedSize;
        public byte[]  assembled;        // preallocated to compressedSize; null = metadata stub
        public BitSet  receivedSeqs;
        public int     totalFragments;
        public long    lastFragmentAt;
        public String  fromPeer;
    }

    public static class UploadState {
        public String targetName;
        public String entryId;
        public byte[] payload;           // zstd-compressed JSON
        public int    nextSeq;
        public int    totalFragments;
        public int    retryCount;
        public long   lastSentMs;        // System.currentTimeMillis() at last DATA send
        public byte[] lastFragData;
        public int    lastFragCrc;
    }

    static class OutboundMsg implements Comparable<OutboundMsg> {
        final int             priority;
        final long            enqueueTime;
        final String          targetName;
        final String          whisperBody;  // MAGIC + NONCE + CjkCodec output
        final BooleanSupplier guard;        // null = always send; false = discard (already ACKed)
        final Runnable        onSent;       // called when message leaves the queue onto the wire
        OutboundMsg(int priority, String t, String b, BooleanSupplier guard, Runnable onSent) {
            this.priority    = priority;
            this.enqueueTime = System.nanoTime();
            this.targetName  = t;
            this.whisperBody = b;
            this.guard       = guard;
            this.onSent      = onSent;
        }
        @Override public int compareTo(OutboundMsg o) {
            int c = Integer.compare(this.priority, o.priority);
            return c != 0 ? c : Long.compare(this.enqueueTime, o.enqueueTime);
        }
    }

    static class PendingCtrl {
        final int    seq;
        final byte   msgType;
        final String target;
        final byte[] payload;  // full wire bytes with seq inserted
        int  retryCount;
        long lastSentMs;
        PendingCtrl(int seq, byte msgType, String target, byte[] payload, long nowMs) {
            this.seq = seq; this.msgType = msgType; this.target = target;
            this.payload = payload; this.retryCount = 0; this.lastSentMs = nowMs;
        }
    }

    static class PeerSnapshot {
        String name;
        long catalogueCrc = -1;
        long lastSeen;
        int sharedCount;
        Map<String, EntrySnapshot> index = new LinkedHashMap<>();
    }

    static class EntrySnapshot {
        String entryId, title, author, fromPeer;
        int gridCols, gridRows, frameCount, compressedSize, totalFragments;
    }

    static class StateFile {
        Map<String, PeerSnapshot> peers = new LinkedHashMap<>();
    }

    // ── Static state ───────────────────────────────────────────────────────

    public static final Map<String, PeerInfo>    peers         = new LinkedHashMap<>();
    public static final Map<String, RemoteEntry> downloads     = new LinkedHashMap<>();
    public static volatile UploadState           upload        = null;
    public static final PriorityQueue<OutboundMsg> outboundQueue = new PriorityQueue<>();
    static long lastSendWallMs = 0;
    static final LinkedHashSet<Character> outboundNonces = new LinkedHashSet<>();
    static int nextCtrlSeq = 0;
    static final Map<Integer, PendingCtrl> pendingCtrl = new LinkedHashMap<>();
    static boolean stateLoaded = false;

    public static boolean beaconing          = false;
    public static int     beaconTickCounter  = 0;
    static int     announceTemplateIdx = 0;
    public static boolean debugMode          = true;

    static boolean inHandler = false; // ALLOW_GAME reentrancy guard

    static final Gson    GSON        = new GsonBuilder().create();
    static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

    private BroadcastChannel() {}

    // ── Registration ───────────────────────────────────────────────────────

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BroadcastChannel::onTick);
        ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> {
            if (overlay || inHandler) return true;
            inHandler = true;
            try { return onGameMessage(msg); }
            finally { inHandler = false; }
        });
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    private static void onTick(MinecraftClient client) {
        if (client.getNetworkHandler() == null || client.player == null) return;
        long nowMs = System.currentTimeMillis();

        // 0. Load persisted state once on first tick
        if (!stateLoaded) {
            stateLoaded = true;
            loadState();
        }

        // 1. Beacon
        if (beaconing && ++beaconTickCounter >= BEACON_INTERVAL_TICKS) {
            beaconTickCounter = 0;
            sendAnnounce(client);
        }

        // 2. Drain outbound priority queue — one message per SEND_INTERVAL_MS (wall clock).
        //    Skip offline targets and stale retransmits (guard == false) without burning the slot.
        if (nowMs - lastSendWallMs >= SEND_INTERVAL_MS && !outboundQueue.isEmpty()) {
            OutboundMsg toSend = null;
            while (!outboundQueue.isEmpty()) {
                OutboundMsg candidate = outboundQueue.poll();
                if (!isPlayerOnline(candidate.targetName)) {
                    debug("Dropped queued msg to offline player " + candidate.targetName);
                    continue;
                }
                if (candidate.guard != null && !candidate.guard.getAsBoolean()) {
                    debug("Skipping stale retransmit to " + candidate.targetName);
                    continue;
                }
                toSend = candidate;
                break;
            }
            if (toSend != null) {
                client.getNetworkHandler().sendCommand("msg " + toSend.targetName + " " + toSend.whisperBody);
                lastSendWallMs = nowMs;
                if (toSend.onSent != null) toSend.onSent.run();
            }
        }

        // 3. Upload — abort if target offline, otherwise schedule retransmit on ACK timeout
        if (upload != null && !isPlayerOnline(upload.targetName)) {
            log("§e[Broadcast] Upload target §f" + upload.targetName + "§e went offline — aborting.");
            upload = null;
        } else if (upload != null && nowMs - upload.lastSentMs > ACK_TIMEOUT_MS) {
            upload.retryCount++;
            upload.lastSentMs = Long.MAX_VALUE / 2;
            log("§e[Broadcast] ACK timeout frag " + upload.nextSeq
                + "/" + upload.totalFragments + " — retry #" + upload.retryCount);
            byte[] pkt = buildDataPacket(upload.entryId, upload.nextSeq,
                upload.totalFragments, upload.lastFragData);
            final String tgt  = upload.targetName;
            final int    frag = upload.nextSeq;
            enqueue(tgt, CjkCodec.encode(pkt), PRIO_RETRANSMIT,
                () -> upload != null && upload.targetName.equals(tgt) && upload.nextSeq == frag,
                () -> { if (upload != null && upload.nextSeq == frag) upload.lastSentMs = System.currentTimeMillis(); });
        }

        // 4. Download timeout
        downloads.entrySet().removeIf(e -> {
            if (nowMs - e.getValue().lastFragmentAt > DOWNLOAD_TIMEOUT_MS) {
                log("§e[Broadcast] Download of \"" + e.getValue().title + "\" timed out.");
                return true;
            }
            return false;
        });

        // 5. Control message retransmit — retry until ACK arrives or target goes offline
        for (Iterator<PendingCtrl> it = pendingCtrl.values().iterator(); it.hasNext(); ) {
            PendingCtrl pc = it.next();
            if (nowMs - pc.lastSentMs > ACK_TIMEOUT_MS) {
                if (!isPlayerOnline(pc.target)) {
                    debug("Ctrl seq=" + pc.seq + " to " + pc.target + " — offline, dropping");
                    if (pc.msgType == MSG_GET_INDEX) {
                        PeerInfo p = peers.get(pc.target);
                        if (p != null) p.indexRequested = false;
                    }
                    it.remove();
                    continue;
                }
                if (pc.msgType == MSG_GET_INDEX) {
                    PeerInfo p = peers.get(pc.target);
                    if (p == null || !p.indexRequested) { it.remove(); continue; }
                }
                pc.retryCount++;
                pc.lastSentMs = Long.MAX_VALUE / 2;
                debug("Ctrl retry #" + pc.retryCount + " seq=" + pc.seq + " to " + pc.target);
                final int capturedSeq = pc.seq;
                int retryPrio = (pc.msgType == MSG_INDEX_DONE) ? PRIO_INDEX_DONE : PRIO_CTRL;
                enqueue(pc.target, CjkCodec.encode(pc.payload), retryPrio,
                    () -> pendingCtrl.containsKey(capturedSeq),
                    () -> { PendingCtrl live = pendingCtrl.get(capturedSeq); if (live != null) live.lastSentMs = System.currentTimeMillis(); });
            }
        }

        // 6. Peer eviction — oldest by insertion order if over cap, or stale by time
        if (peers.size() > MAX_PEERS) {
            peers.remove(peers.keySet().iterator().next());
        }
        peers.entrySet().removeIf(e -> nowMs - e.getValue().lastSeen > PEER_EVICT_MS);
    }

    // ── Message listener ──────────────────────────────────────────────────

    private static boolean onGameMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return true;

        String ownName = client.player.getGameProfile().getName();

        // ANNOUNCE: 4-sib public chat format confirmed by Stage 1 (sib[0]="<", sib[1]=name, sib[2]="> ", sib[3]=body)
        List<Text> sibs = message.getSiblings();
        if (sibs.size() == 4) {
            String senderName = sibs.get(1).getString().trim();
            String chatBody   = sibs.get(3).getString();
            if (!senderName.equals(ownName) && isValidPlayerName(senderName)
                    && isAnnounceTemplate(chatBody)) {
                tryParseAnnounce(chatBody, senderName);
            }
            return true; // always show public chat
        }

        // Whisper: extractSender returns non-null, body starts with MAGIC
        String sender = extractSender(message);
        String body   = extractBody(message);
        if (sender == null || body == null || body.length() < 2) return true;
        if (body.charAt(0) != MAGIC) return true;

        char nonceChar = body.charAt(1);
        if (outboundNonces.contains(nonceChar)) {
            debug("Echo from " + sender + " (nonce=U+" + Integer.toHexString(nonceChar) + ") — skipped");
            return true;
        }

        try {
            handlePayload(sender, descramble(body.substring(2), nonceChar - CjkCodec.ALPHA_BASE));
        } catch (Exception e) {
            log("§c[Broadcast] Parse error from " + sender + ": " + e.getMessage());
        }
        return true;
    }

    // ── ANNOUNCE ──────────────────────────────────────────────────────────

    public static void sendAnnounce(MinecraftClient client) {
        if (client.getNetworkHandler() == null) return;
        long crc = computeCatalogueCrc();
        int shared = (int) CatalogueState.entries.stream().filter(e -> e.shareEnabled).count();

        short x = (short)(crc >>> 16);
        short z = (short)(crc & 0xFFFF);
        short y = (short)(60 + ThreadLocalRandom.current().nextInt(8));
        short w = (short) Math.min(shared, 32767);

        String template = TEMPLATES[announceTemplateIdx % TEMPLATES.length];
        announceTemplateIdx = (announceTemplateIdx + 1) % TEMPLATES.length;

        String text = template
            .replace("{x}", String.valueOf((int) x))
            .replace("{y}", String.valueOf((int) y))
            .replace("{z}", String.valueOf((int) z))
            .replace("{w}", String.valueOf((int) w));

        client.getNetworkHandler().sendChatMessage(text);
        debug("ANNOUNCE sent  crc=" + Long.toHexString(crc & 0xFFFFFFFFL)
            + "  shared=" + shared + "  len=" + text.length());
    }

    private static void tryParseAnnounce(String body, String senderName) {
        Matcher m = INT_PATTERN.matcher(body);
        List<Integer> ints = new ArrayList<>(4);
        while (m.find() && ints.size() < 4) ints.add(Integer.parseInt(m.group()));
        if (ints.size() < 4) return;

        // slot[0]=X (upper 16 bits of CRC), slot[1]=Y (noise 60–67), slot[2]=Z (lower 16 bits), slot[3]=W (sharedCount)
        int y = ints.get(1);
        if (y < 60 || y > 67) return; // Y-range gate keeps false positives ~0
        short x = ints.get(0).shortValue();
        short z = ints.get(2).shortValue();
        short w = ints.get(3).shortValue();
        long crc         = ((x & 0xFFFFL) << 16) | (z & 0xFFFFL);
        int  sharedCount = w & 0xFFFF;

        debug("ANNOUNCE from " + senderName + "  crc=" + Long.toHexString(crc) + "  shared=" + sharedCount);
        handleAnnounce(senderName, crc, sharedCount);
    }

    private static void handleAnnounce(String senderName, long catalogueCrc, int sharedCount) {
        long now = System.currentTimeMillis();
        PeerInfo peer = peers.computeIfAbsent(senderName, n -> {
            PeerInfo p = new PeerInfo();
            p.name = n;
            return p;
        });
        peer.lastSeen   = now;
        peer.sharedCount = sharedCount;

        boolean crcChanged = peer.catalogueCrc != catalogueCrc;
        peer.catalogueCrc = catalogueCrc;

        if (crcChanged && !peer.indexRequested) {
            peer.indexRequested   = true;
            peer.indexRequestedAt = now;
            enqueueControl(senderName, new byte[]{MSG_GET_INDEX});
            log("§a[Broadcast] Peer §f" + senderName + "§a (shared=" + sharedCount + ") — fetching index...");
        }
    }

    // ── Payload dispatch ──────────────────────────────────────────────────

    private static void handlePayload(String senderName, String cjkBody) {
        byte[] decoded = CjkCodec.decode(cjkBody);
        if (decoded.length == 0) return;
        switch (decoded[0]) {
            case MSG_GET_INDEX   -> { sendCtrlAck(senderName, decoded); handleGetIndex(senderName); }
            case MSG_INDEX_ENTRY -> { sendCtrlAck(senderName, decoded); handleIndexEntry(senderName, decoded); }
            case MSG_INDEX_DONE  -> { sendCtrlAck(senderName, decoded); handleIndexDone(senderName); }
            case MSG_REQUEST     -> { sendCtrlAck(senderName, decoded); handleRequest(senderName, decoded); }
            case MSG_DATA        -> handleData(senderName, decoded);
            case MSG_ACK         -> handleAck(senderName, decoded);
            case MSG_ACK_CTRL    -> handleCtrlAck(senderName, decoded);
            default -> debug("Unknown type 0x" + Integer.toHexString(decoded[0] & 0xFF)
                + " from " + senderName);
        }
    }

    // ── GET_INDEX ─────────────────────────────────────────────────────────

    private static void handleGetIndex(String senderName) {
        debug(senderName + " requested our index");
        long count = CatalogueState.entries.stream().filter(e -> e.shareEnabled).count();
        for (CatalogueState.CatalogueEntry entry : CatalogueState.entries) {
            if (!entry.shareEnabled || entry.tiles == null) continue;
            byte[] json       = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
            byte[] compressed = Zstd.compress(json, 3);
            byte[] payload    = buildIndexEntry(entry, compressed.length);
            enqueueControl(senderName, payload);
        }
        enqueueControlPrio(senderName, new byte[]{MSG_INDEX_DONE}, PRIO_INDEX_DONE);
        debug("Sent " + count + " INDEX_ENTRY + INDEX_DONE to " + senderName);
    }

    // ── INDEX_ENTRY ───────────────────────────────────────────────────────

    private static void handleIndexEntry(String senderName, byte[] d) {
        // Format: [0x03][seq:2][entryId:16][compressedSize:4][gridCols:1][gridRows:1][frameCount:2]
        //         [titleLen:1][title:N][authorLen:1][author:M]
        if (d.length < 28) return;
        try {
            String entryId = readEntryId(d, 3);
            ByteBuffer buf = ByteBuffer.wrap(d, 19, d.length - 19);
            int compressedSize = buf.getInt();
            if (compressedSize <= 0 || compressedSize > 10_000_000) {
                debug("INDEX_ENTRY from " + senderName
                    + ": invalid compressedSize=" + compressedSize + " — ignoring");
                return;
            }
            int gridCols   = buf.get() & 0xFF;
            int gridRows   = buf.get() & 0xFF;
            int frameCount = buf.getShort() & 0xFFFF;

            int titleLen = buf.get() & 0xFF;
            byte[] titleBytes = new byte[Math.min(titleLen, buf.remaining())];
            buf.get(titleBytes);
            String title = new String(titleBytes, StandardCharsets.UTF_8);

            String author = "";
            if (buf.hasRemaining()) {
                int authorLen = buf.get() & 0xFF;
                byte[] authorBytes = new byte[Math.min(authorLen, buf.remaining())];
                buf.get(authorBytes);
                author = new String(authorBytes, StandardCharsets.UTF_8);
            }

            PeerInfo peer = peers.computeIfAbsent(senderName, n -> {
                PeerInfo p = new PeerInfo(); p.name = n;
                p.lastSeen = System.currentTimeMillis(); return p;
            });

            RemoteEntry entry = new RemoteEntry();
            entry.entryId        = entryId;
            entry.title          = title;
            entry.author         = author;
            entry.gridCols       = gridCols;
            entry.gridRows       = gridRows;
            entry.frameCount     = frameCount;
            entry.compressedSize = compressedSize;
            entry.totalFragments = (compressedSize + FRAGMENT_DATA_BYTES - 1) / FRAGMENT_DATA_BYTES;
            entry.fromPeer       = senderName;
            boolean isNew = !peer.index.containsKey(entryId);
            peer.index.put(entryId, entry);

            if (isNew) {
                log("§b[Broadcast] §f" + senderName + "§b has §f\"" + title + "\"§b ("
                    + gridCols + "×" + gridRows + ", " + frameCount + " frames, "
                    + (compressedSize / 1024) + " KB)  id=" + entryId.substring(0, 8));
            }
        } catch (Exception e) {
            debug("INDEX_ENTRY parse error: " + e.getMessage());
        }
    }

    // ── INDEX_DONE ────────────────────────────────────────────────────────

    private static void handleIndexDone(String senderName) {
        PeerInfo peer = peers.get(senderName);
        if (peer != null) {
            peer.indexRequested = false;
            log("§a[Broadcast] Index from §f" + senderName
                + "§a complete (" + peer.index.size() + " entries).");
            saveState();
        }
    }

    // ── REQUEST ───────────────────────────────────────────────────────────

    private static void handleRequest(String senderName, byte[] d) {
        if (d.length < 19) return;
        // REQUEST proves peer received our index — drop any pending INDEX_ENTRY/INDEX_DONE retransmits
        pendingCtrl.values().removeIf(pc -> pc.target.equals(senderName)
            && (pc.msgType == MSG_INDEX_ENTRY || pc.msgType == MSG_INDEX_DONE));
        String entryId = readEntryId(d, 3);

        CatalogueState.CatalogueEntry found = null;
        for (CatalogueState.CatalogueEntry e : CatalogueState.entries) {
            if (e.shareEnabled && e.id.equals(entryId) && e.tiles != null) {
                found = e; break;
            }
        }
        if (found == null) {
            debug("REQUEST for " + entryId.substring(0, 8) + " from " + senderName
                + " — not found / not shared");
            return;
        }
        if (upload != null) {
            log("§e[Broadcast] REQUEST from " + senderName + " for \""
                + found.title + "\" — busy uploading, try again.");
            return;
        }

        byte[] json;
        byte[] compressed;
        try {
            json       = GSON.toJson(found).getBytes(StandardCharsets.UTF_8);
            compressed = Zstd.compress(json, 3);
        } catch (Exception e) {
            log("§c[Broadcast] Compress failed for \"" + found.title + "\": " + e.getMessage());
            return;
        }

        UploadState u = new UploadState();
        u.targetName    = senderName;
        u.entryId       = found.id;
        u.payload       = compressed;
        u.nextSeq       = 0;
        u.totalFragments = (compressed.length + FRAGMENT_DATA_BYTES - 1) / FRAGMENT_DATA_BYTES;
        u.retryCount    = 0;
        u.lastSentMs    = Long.MAX_VALUE / 2;
        upload = u;

        log("§a[Broadcast] Uploading §f\"" + found.title + "\"§a → §f" + senderName
            + "§a  " + u.totalFragments + " frags  "
            + (compressed.length / 1024) + " KB");
        enqueueNextFragment();
    }

    private static void enqueueNextFragment() {
        if (upload == null) return;
        int off = upload.nextSeq * FRAGMENT_DATA_BYTES;
        int len = Math.min(FRAGMENT_DATA_BYTES, upload.payload.length - off);
        upload.lastFragData = Arrays.copyOfRange(upload.payload, off, off + len);
        upload.lastFragCrc  = crc32bytes(upload.lastFragData, 0, len);
        upload.lastSentMs   = Long.MAX_VALUE / 2; // reset; onSent sets actual wire time

        byte[] pkt = buildDataPacket(upload.entryId, upload.nextSeq,
            upload.totalFragments, upload.lastFragData);
        debug("→ DATA frag " + upload.nextSeq + "/" + upload.totalFragments
            + "  " + len + "B  crc=" + Integer.toHexString(upload.lastFragCrc));
        enqueue(upload.targetName, CjkCodec.encode(pkt), PRIO_DATA, null,
            () -> { if (upload != null) upload.lastSentMs = System.currentTimeMillis(); });
    }

    // ── DATA ──────────────────────────────────────────────────────────────

    private static void handleData(String senderName, byte[] d) {
        // Format: [0x05][entryIdPrefix:8][seq:2][total:2][fragData:N]
        if (d.length < 14) return;

        // Find matching download by first 8 bytes of entryId
        long rxHi = 0;
        for (int i = 1; i <= 8; i++) rxHi = (rxHi << 8) | (d[i] & 0xFF);

        RemoteEntry entry = null;
        for (RemoteEntry re : downloads.values()) {
            long expHi = Long.parseUnsignedLong(re.entryId.substring(0, 16), 16);
            if (expHi == rxHi) { entry = re; break; }
        }
        if (entry == null) {
            // Transfer may already be complete — re-synthesize the final ACK so Z can clear its upload
            int seq     = ((d[9]  & 0xFF) << 8) | (d[10] & 0xFF);
            int total   = ((d[11] & 0xFF) << 8) | (d[12] & 0xFF);
            int dataLen = d.length - 13;
            CatalogueState.CatalogueEntry completed = null;
            for (CatalogueState.CatalogueEntry ce : CatalogueState.entries) {
                if (Long.parseUnsignedLong(ce.id.substring(0, 16), 16) == rxHi) { completed = ce; break; }
            }
            if (completed != null) {
                int fragCrc = crc32bytes(d, 13, dataLen);
                enqueue(senderName, CjkCodec.encode(buildAckPacket(completed.id, seq, fragCrc, total)), PRIO_ACK);
                debug("DATA from " + senderName + " — already complete, re-ACKing seq=" + seq);
            } else {
                debug("DATA from " + senderName + " — no matching download");
            }
            return;
        }

        // Implicit ACK: receiving DATA proves the peer got our REQUEST — clear ctrl retry so
        // DATA ACKs (PRIO_ACK) are no longer starved by REQUEST retransmits (PRIO_RETRANSMIT).
        pendingCtrl.entrySet().removeIf(e -> {
            PendingCtrl pc = e.getValue();
            return pc.msgType == MSG_REQUEST && pc.target.equalsIgnoreCase(senderName);
        });

        int seq     = ((d[9]  & 0xFF) << 8) | (d[10] & 0xFF);
        int total   = ((d[11] & 0xFF) << 8) | (d[12] & 0xFF);
        int dataLen = d.length - 13;
        int off     = seq * FRAGMENT_DATA_BYTES;

        if (seq < 0 || seq >= entry.totalFragments
                || off + dataLen > entry.assembled.length) {
            debug("DATA seq=" + seq + " bounds check failed (total=" + entry.totalFragments
                + " off=" + off + " len=" + dataLen + " buf=" + entry.assembled.length + ")");
            return;
        }

        System.arraycopy(d, 13, entry.assembled, off, dataLen);
        entry.receivedSeqs.set(seq);
        entry.lastFragmentAt = System.currentTimeMillis();

        int fragCrc     = crc32bytes(d, 13, dataLen);
        int nextExpected = firstMissingSeq(entry);
        enqueue(senderName, CjkCodec.encode(buildAckPacket(entry.entryId, seq, fragCrc, nextExpected)), PRIO_ACK);

        debug("← DATA frag " + seq + "/" + total + "  " + dataLen + "B"
            + "  have=" + entry.receivedSeqs.cardinality() + "/" + entry.totalFragments);

        if (entry.receivedSeqs.cardinality() == entry.totalFragments) {
            finishDownload(entry);
        }
    }

    // ── ACK ───────────────────────────────────────────────────────────────

    private static void handleAck(String senderName, byte[] d) {
        // Format: [0x06][entryIdPrefix:8][seq:2][crc32:4][nextExpected:2]
        if (upload == null || !senderName.equals(upload.targetName)) return;
        if (d.length < 17) return;

        int seq          = ((d[9]  & 0xFF) << 8) | (d[10] & 0xFF);
        int ackCrc       = ((d[11] & 0xFF) << 24) | ((d[12] & 0xFF) << 16)
                         | ((d[13] & 0xFF) <<  8) |  (d[14] & 0xFF);
        int nextExpected = ((d[15] & 0xFF) << 8)  |  (d[16] & 0xFF);

        if (seq != upload.nextSeq) {
            debug("ACK seq=" + seq + " stale (nextSeq=" + upload.nextSeq + ")");
            return;
        }
        if (ackCrc != upload.lastFragCrc) {
            log("§e[Broadcast] CRC mismatch frag " + seq + " — exp="
                + Integer.toHexString(upload.lastFragCrc)
                + " got=" + Integer.toHexString(ackCrc) + " — retransmitting");
            upload.retryCount++;
            byte[] pkt = buildDataPacket(upload.entryId, upload.nextSeq,
                upload.totalFragments, upload.lastFragData);
            final String tgt  = upload.targetName;
            final int    frag = upload.nextSeq;
            upload.lastSentMs = Long.MAX_VALUE / 2;
            enqueue(tgt, CjkCodec.encode(pkt), PRIO_RETRANSMIT,
                () -> upload != null && upload.targetName.equals(tgt) && upload.nextSeq == frag,
                () -> { if (upload != null && upload.nextSeq == frag) upload.lastSentMs = System.currentTimeMillis(); });
            return;
        }

        upload.nextSeq   = Math.max(seq + 1, nextExpected); // gap-skip
        upload.retryCount = 0;
        upload.lastSentMs = System.currentTimeMillis();
        debug("ACK frag " + seq + " ok  next=" + upload.nextSeq + "/" + upload.totalFragments);

        if (upload.nextSeq >= upload.totalFragments) {
            log("§a§l[Broadcast] §f\"" + upload.entryId.substring(0, 8)
                + "\" §aupload complete (" + upload.totalFragments + " frags).");
            upload = null;
        } else {
            enqueueNextFragment();
        }
    }

    // ── Download complete ─────────────────────────────────────────────────

    private static void finishDownload(RemoteEntry entry) {
        try {
            long contentSize = Zstd.getFrameContentSize(entry.assembled);
            if (contentSize <= 0 || contentSize > 20_000_000)
                throw new IllegalStateException("Bad zstd frame size: " + contentSize);

            byte[] json = Zstd.decompress(entry.assembled, (int) contentSize);
            CatalogueState.CatalogueEntry parsed = GSON.fromJson(
                new String(json, StandardCharsets.UTF_8),
                CatalogueState.CatalogueEntry.class);

            parsed.sourcePeer = entry.fromPeer;
            CatalogueState.entries.removeIf(e -> e.id.equals(parsed.id));
            CatalogueState.entries.add(parsed);
            CatalogueState.save();
            downloads.remove(entry.entryId);

            log("§a§l[Broadcast] §f\"" + parsed.title + "\" §adownloaded from §f"
                + entry.fromPeer + "§a — added to catalogue!");
        } catch (Exception e) {
            log("§c[Broadcast] Reassembly failed for \"" + entry.title
                + "\": " + e.getMessage());
            downloads.remove(entry.entryId);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    public static void requestEntry(String peerName, String entryId) {
        PeerInfo peer = peers.get(peerName);
        if (peer == null) { log("§c[Broadcast] Unknown peer: " + peerName); return; }
        RemoteEntry meta = peer.index.get(entryId);
        if (meta == null) {
            log("§c[Broadcast] Entry " + entryId.substring(0, 8)
                + " not in " + peerName + "'s index."); return;
        }
        if (downloads.containsKey(entryId)) {
            log("§e[Broadcast] Already downloading " + entryId.substring(0, 8)); return;
        }

        RemoteEntry active = new RemoteEntry();
        active.entryId        = entryId;
        active.title          = meta.title;
        active.author         = meta.author;
        active.gridCols       = meta.gridCols;
        active.gridRows       = meta.gridRows;
        active.frameCount     = meta.frameCount;
        active.compressedSize = meta.compressedSize;
        active.assembled      = new byte[meta.compressedSize];
        active.receivedSeqs   = new BitSet(meta.totalFragments);
        active.totalFragments = meta.totalFragments;
        active.lastFragmentAt = System.currentTimeMillis();
        active.fromPeer       = peerName;
        downloads.put(entryId, active);

        enqueueControl(peerName, buildRequest(entryId));
        log("§a[Broadcast] Requesting §f\"" + meta.title + "\"§a from §f" + peerName
            + "§a (" + meta.totalFragments + " frags)");
    }

    // ── Wire builders ─────────────────────────────────────────────────────

    private static byte[] buildIndexEntry(CatalogueState.CatalogueEntry entry, int compressedSize) {
        byte[] titleBytes  = entry.title  != null
            ? truncateUtf8(entry.title.getBytes(StandardCharsets.UTF_8),  150) : new byte[0];
        byte[] authorBytes = entry.author != null
            ? truncateUtf8(entry.author.getBytes(StandardCharsets.UTF_8),  64) : new byte[0];
        int cap = 1 + 16 + 4 + 1 + 1 + 2 + 1 + titleBytes.length + 1 + authorBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(cap);
        buf.put(MSG_INDEX_ENTRY);
        buf.put(putEntryId(entry.id));
        buf.putInt(compressedSize);
        buf.put((byte)(entry.gridCols  & 0xFF));
        buf.put((byte)(entry.gridRows  & 0xFF));
        buf.putShort((short)(entry.frameCount & 0xFFFF));
        buf.put((byte) titleBytes.length);
        buf.put(titleBytes);
        buf.put((byte) authorBytes.length);
        buf.put(authorBytes);
        return buf.array();
    }

    private static byte[] buildRequest(String entryId) {
        byte[] buf = new byte[17];
        buf[0] = MSG_REQUEST;
        System.arraycopy(putEntryId(entryId), 0, buf, 1, 16);
        return buf;
    }

    private static byte[] buildDataPacket(String entryId, int seq, int total, byte[] fragData) {
        byte[] buf = new byte[13 + fragData.length];
        buf[0] = MSG_DATA;
        // Pack first 8 bytes of entryId (= first 16 hex chars)
        long hi = Long.parseUnsignedLong(entryId.substring(0, 16), 16);
        for (int i = 7; i >= 0; i--) { buf[1 + (7 - i)] = (byte)(hi >>> (i * 8)); }
        buf[9]  = (byte)(seq   >>> 8); buf[10] = (byte) seq;
        buf[11] = (byte)(total >>> 8); buf[12] = (byte) total;
        System.arraycopy(fragData, 0, buf, 13, fragData.length);
        return buf;
    }

    private static byte[] buildAckPacket(String entryId, int seq, int crc32, int nextExpected) {
        byte[] buf = new byte[17];
        buf[0] = MSG_ACK;
        long hi = Long.parseUnsignedLong(entryId.substring(0, 16), 16);
        for (int i = 7; i >= 0; i--) { buf[1 + (7 - i)] = (byte)(hi >>> (i * 8)); }
        buf[9]  = (byte)(seq          >>> 8);  buf[10] = (byte) seq;
        buf[11] = (byte)(crc32        >>> 24); buf[12] = (byte)(crc32 >>> 16);
        buf[13] = (byte)(crc32        >>>  8); buf[14] = (byte) crc32;
        buf[15] = (byte)(nextExpected >>> 8);  buf[16] = (byte) nextExpected;
        return buf;
    }

    static byte[] putEntryId(String hexId) {
        long hi = Long.parseUnsignedLong(hexId.substring(0,  16), 16);
        long lo = Long.parseUnsignedLong(hexId.substring(16, 32), 16);
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putLong(hi); b.putLong(lo);
        return b.array();
    }

    static String readEntryId(byte[] buf, int off) {
        ByteBuffer b = ByteBuffer.wrap(buf, off, 16);
        return String.format("%016x%016x", b.getLong(), b.getLong());
    }

    // ── Enqueue ───────────────────────────────────────────────────────────

    public static void enqueue(String targetName, String cjkBody) {
        enqueue(targetName, cjkBody, PRIO_DATA, null, null);
    }

    static void enqueue(String targetName, String cjkBody, int priority) {
        enqueue(targetName, cjkBody, priority, null, null);
    }

    static void enqueue(String targetName, String cjkBody, int priority, BooleanSupplier guard) {
        enqueue(targetName, cjkBody, priority, guard, null);
    }

    static void enqueue(String targetName, String cjkBody, int priority,
                        BooleanSupplier guard, Runnable onSent) {
        char nonceChar = (char)(CjkCodec.ALPHA_BASE
            + ThreadLocalRandom.current().nextInt(CjkCodec.ALPHA_SIZE));
        outboundNonces.add(nonceChar);
        if (outboundNonces.size() > MAX_NONCE_HISTORY) {
            outboundNonces.remove(outboundNonces.iterator().next());
        }
        outboundQueue.offer(new OutboundMsg(priority, targetName,
            MAGIC + String.valueOf(nonceChar) + scramble(cjkBody, nonceChar - CjkCodec.ALPHA_BASE),
            guard, onSent));
    }

    public static void enqueueControl(String target, byte[] payload) {
        enqueueControlPrio(target, payload, PRIO_CTRL);
    }

    private static void enqueueControlPrio(String target, byte[] payload, int prio) {
        int seq = nextCtrlSeq & 0xFFFF;
        nextCtrlSeq = (nextCtrlSeq + 1) & 0xFFFF;
        byte[] wire = insertSeq(payload, seq);
        // lastSentMs = Long.MAX_VALUE/2 until onSent fires; prevents timeout before actual send
        pendingCtrl.put(seq, new PendingCtrl(seq, payload[0], target, wire, Long.MAX_VALUE / 2));
        enqueue(target, CjkCodec.encode(wire), prio, null,
            () -> { PendingCtrl pc = pendingCtrl.get(seq); if (pc != null) pc.lastSentMs = System.currentTimeMillis(); });
    }

    private static byte[] insertSeq(byte[] payload, int seq) {
        byte[] out = new byte[payload.length + 2];
        out[0] = payload[0];
        out[1] = (byte)(seq >>> 8);
        out[2] = (byte) seq;
        System.arraycopy(payload, 1, out, 3, payload.length - 1);
        return out;
    }

    private static void sendCtrlAck(String target, byte[] decoded) {
        if (decoded.length < 3) return;
        int seq = ((decoded[1] & 0xFF) << 8) | (decoded[2] & 0xFF);
        byte[] ack = {MSG_ACK_CTRL, (byte)(seq >>> 8), (byte) seq};
        enqueue(target, CjkCodec.encode(ack), PRIO_ACK);
    }

    private static void handleCtrlAck(String senderName, byte[] decoded) {
        if (decoded.length < 3) return;
        int seq = ((decoded[1] & 0xFF) << 8) | (decoded[2] & 0xFF);
        PendingCtrl pc = pendingCtrl.remove(seq);
        if (pc != null) {
            debug("CTRL_ACK seq=" + seq + " from " + senderName + " ok");
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static boolean isPlayerOnline(String name) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.getNetworkHandler() == null) return false;
        return c.getNetworkHandler().getPlayerList().stream()
            .anyMatch(e -> e.getProfile().getName().equalsIgnoreCase(name));
    }

    private static File getStateFile() {
        MinecraftClient c = MinecraftClient.getInstance();
        File dir = new File(c.runDirectory, "config");
        dir.mkdirs();
        return new File(dir, "loominary_p2p_state.json");
    }

    static void saveState() {
        try {
            StateFile sf = new StateFile();
            for (PeerInfo p : peers.values()) {
                PeerSnapshot ps = new PeerSnapshot();
                ps.name         = p.name;
                ps.catalogueCrc = p.catalogueCrc;
                ps.lastSeen     = p.lastSeen;
                ps.sharedCount  = p.sharedCount;
                for (Map.Entry<String, RemoteEntry> e : p.index.entrySet()) {
                    RemoteEntry re = e.getValue();
                    EntrySnapshot es = new EntrySnapshot();
                    es.entryId        = re.entryId;
                    es.title          = re.title;
                    es.author         = re.author;
                    es.fromPeer       = re.fromPeer;
                    es.gridCols       = re.gridCols;
                    es.gridRows       = re.gridRows;
                    es.frameCount     = re.frameCount;
                    es.compressedSize = re.compressedSize;
                    es.totalFragments = re.totalFragments;
                    ps.index.put(e.getKey(), es);
                }
                sf.peers.put(p.name, ps);
            }
            try (Writer w = new FileWriter(getStateFile(), StandardCharsets.UTF_8)) {
                GSON.toJson(sf, w);
            }
        } catch (Exception e) {
            log("§c[Broadcast] Failed to save P2P state: " + e.getMessage());
        }
    }

    static void loadState() {
        try {
            File f = getStateFile();
            if (!f.exists()) return;
            StateFile sf;
            try (Reader r = new FileReader(f, StandardCharsets.UTF_8)) {
                sf = GSON.fromJson(r, StateFile.class);
            }
            if (sf == null || sf.peers == null) return;
            long now = System.currentTimeMillis();
            for (PeerSnapshot ps : sf.peers.values()) {
                PeerInfo p = new PeerInfo();
                p.name         = ps.name;
                p.catalogueCrc = ps.catalogueCrc;
                p.lastSeen     = now; // refresh so eviction doesn't immediately drop them
                p.sharedCount  = ps.sharedCount;
                if (ps.index != null) {
                    for (EntrySnapshot es : ps.index.values()) {
                        RemoteEntry re = new RemoteEntry();
                        re.entryId        = es.entryId;
                        re.title          = es.title;
                        re.author         = es.author;
                        re.fromPeer       = es.fromPeer;
                        re.gridCols       = es.gridCols;
                        re.gridRows       = es.gridRows;
                        re.frameCount     = es.frameCount;
                        re.compressedSize = es.compressedSize;
                        re.totalFragments = es.totalFragments;
                        p.index.put(es.entryId, re);
                    }
                }
                peers.put(ps.name, p);
            }
            if (!peers.isEmpty())
                log("§a[Broadcast] Loaded P2P state: " + peers.size() + " peer(s), "
                    + peers.values().stream().mapToInt(p -> p.index.size()).sum() + " entries.");
        } catch (Exception e) {
            log("§c[Broadcast] Failed to load P2P state: " + e.getMessage());
        }
    }

    static long computeCatalogueCrc() {
        List<String> ids = new ArrayList<>();
        for (CatalogueState.CatalogueEntry e : CatalogueState.entries) {
            if (e.shareEnabled) ids.add(e.id);
        }
        Collections.sort(ids);
        CRC32 crc = new CRC32();
        for (String id : ids) crc.update(id.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static int crc32bytes(byte[] data, int off, int len) {
        CRC32 crc = new CRC32(); crc.update(data, off, len); return (int) crc.getValue();
    }

    private static String scramble(String cjkBody, int nonce) {
        StringBuilder sb = new StringBuilder(cjkBody.length());
        for (int i = 0; i < cjkBody.length(); i++) {
            sb.append((char)(CjkCodec.ALPHA_BASE
                + ((cjkBody.charAt(i) - CjkCodec.ALPHA_BASE + nonce) % CjkCodec.ALPHA_SIZE)));
        }
        return sb.toString();
    }

    private static String descramble(String body, int nonce) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            sb.append((char)(CjkCodec.ALPHA_BASE
                + ((body.charAt(i) - CjkCodec.ALPHA_BASE - nonce + CjkCodec.ALPHA_SIZE) % CjkCodec.ALPHA_SIZE)));
        }
        return sb.toString();
    }

    private static boolean isAnnounceTemplate(String body) {
        for (Pattern p : ANNOUNCE_PATTERNS) {
            if (p.matcher(body).matches()) return true;
        }
        return false;
    }

    private static boolean isValidPlayerName(String s) {
        return s != null && !s.isEmpty() && s.length() <= 16
            && s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    /** Whisper sender extraction — format confirmed by Stage 0 (sib[1] = sender name). */
    private static String extractSender(Text message) {
        List<Text> sibs = message.getSiblings();
        return sibs.size() >= 3 ? sibs.get(1).getString().trim() : null;
    }

    /** Whisper body extraction — format confirmed by Stage 0. */
    private static String extractBody(Text message) {
        List<Text> sibs = message.getSiblings();
        if (sibs.size() < 3) return null;
        String raw = sibs.get(2).getString();
        int ci = raw.indexOf(": ");
        return ci >= 0 ? raw.substring(ci + 2) : null;
    }

    private static int firstMissingSeq(RemoteEntry entry) {
        for (int i = 0; i < entry.totalFragments; i++) {
            if (!entry.receivedSeqs.get(i)) return i;
        }
        return entry.totalFragments;
    }

    private static byte[] truncateUtf8(byte[] bytes, int maxBytes) {
        if (bytes.length <= maxBytes) return bytes;
        int end = maxBytes;
        while (end > 0 && (bytes[end - 1] & 0xC0) == 0x80) end--;
        return Arrays.copyOf(bytes, end);
    }

    static void log(String msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null) c.player.sendMessage(Text.literal(msg), false);
    }

    private static void debug(String msg) {
        if (!debugMode) return;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null)
            c.player.sendMessage(Text.literal("§8[BC-dbg] " + msg), false);
    }

    // ── ANNOUNCE templates (≥50; rotate to defeat anti-spam similarity) ──

    static final String[] TEMPLATES = {
        "found cave at {x} {y} {z} depth {w}",
        "nether portal {x} {y} {z} overworld {w}",
        "stash coords {x} {y} {z} items {w}",
        "base x={x} y={y} z={z} index {w}",
        "dropped stuff at {x} {y} {z} hurry {w}",
        "diamond vein {x} {y} {z} count {w}",
        "portal hub {x} {y} {z} exit {w}",
        "spawn camp at {x} {y} {z} online {w}",
        "ancient city {x} {y} {z} level {w}",
        "mineshaft {x} {y} {z} branch {w}",
        "lava lake {x} {y} {z} depth {w}",
        "ravine at {x} {y} {z} length {w}",
        "dungeon {x} {y} {z} mobs {w}",
        "nether fortress {x} {y} {z} blaze {w}",
        "bastion {x} {y} {z} loot {w}",
        "ruined portal {x} {y} {z} pieces {w}",
        "coords x {x} y {y} z {z} heading {w}",
        "meeting point {x} {y} {z} time {w}",
        "tunnel exit {x} {y} {z} section {w}",
        "piglin trade spot {x} {y} {z} gold {w}",
        "gold farm {x} {y} {z} rate {w}",
        "end portal {x} {y} {z} eye {w}",
        "stronghold {x} {y} {z} rooms {w}",
        "buried treasure {x} {y} {z} depth {w}",
        "spawner {x} {y} {z} type {w}",
        "abandoned mine {x} {y} {z} level {w}",
        "shipwreck {x} {y} {z} chests {w}",
        "ocean monument {x} {y} {z} elder {w}",
        "jungle temple {x} {y} {z} traps {w}",
        "desert temple {x} {y} {z} tnt {w}",
        "woodland mansion {x} {y} {z} rooms {w}",
        "pillager outpost {x} {y} {z} pillagers {w}",
        "village {x} {y} {z} houses {w}",
        "marked location {x} {y} {z} flag {w}",
        "base camp {x} {y} {z} members {w}",
        "respawn anchor {x} {y} {z} charges {w}",
        "obsidian farm {x} {y} {z} stacks {w}",
        "end city {x} {y} {z} ships {w}",
        "elder guardian {x} {y} {z} rooms {w}",
        "kelp forest {x} {y} {z} depth {w}",
        "coral reef {x} {y} {z} fish {w}",
        "mushroom island {x} {y} {z} mooshrooms {w}",
        "bamboo jungle {x} {y} {z} pandas {w}",
        "cherry grove {x} {y} {z} trees {w}",
        "badlands plateau {x} {y} {z} gold {w}",
        "nether wastes {x} {y} {z} ghasts {w}",
        "soul sand valley {x} {y} {z} skeletons {w}",
        "crimson forest {x} {y} {z} hoglins {w}",
        "warped forest {x} {y} {z} endermen {w}",
        "deep dark {x} {y} {z} sculk {w}",
        "trial chamber {x} {y} {z} vaults {w}",
        "overworld to nether {x} {y} {z} scale {w}",
        "ice biome {x} {y} {z} size {w}",
        "mesa biome {x} {y} {z} layers {w}",
        "chorus farm {x} {y} {z} height {w}",
        "mangrove swamp {x} {y} {z} trees {w}",
    };

    // Built once at class load from TEMPLATES — used by isAnnounceTemplate()
    static final Pattern[] ANNOUNCE_PATTERNS = buildAnnouncePatterns();

    private static Pattern[] buildAnnouncePatterns() {
        Pattern[] pats = new Pattern[TEMPLATES.length];
        for (int i = 0; i < TEMPLATES.length; i++) {
            String regex = TEMPLATES[i]
                .replace("{x}", "-?\\d+")
                .replace("{y}", "\\d+")
                .replace("{z}", "-?\\d+")
                .replace("{w}", "\\d+");
            pats[i] = Pattern.compile(regex);
        }
        return pats;
    }
}
