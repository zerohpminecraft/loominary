package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapDecoration;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.zerohpminecraft.CarpetChannel;
import net.zerohpminecraft.CjkCodec;
import net.zerohpminecraft.mixin.MapStateAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapBannerDecoder {

    private static final String TAG = "[BannerMod]";
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final double SCAN_RADIUS = 32.0;
    private static final int MAP_BYTES = 128 * 128;

    private static final Set<Integer> claimedMaps = new HashSet<>();
    private static final Map<Integer, AnimatedMapState> animatedMaps = new HashMap<>();
    private static int tickCounter = 0;

    // ── Encrypted-map handling ────────────────────────────────────────────
    // Maps pending async decryption (claimed to prevent re-scan but not yet decoded).
    private static final Set<Integer> pendingDecrypts = new HashSet<>();
    // Maps seen as encrypted when the password list was empty (suppress repeat log).
    private static final Set<Integer> encryptedNoPass = new HashSet<>();

    private static final ExecutorService DECRYPT_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "loominary-decrypt");
                t.setDaemon(true);
                return t;
            });

    // ── Sync-group cache ──────────────────────────────────────────────────
    // advanceAnimatedFrames groups map IDs by their sync key on every call.
    // Rebuilding the map every rendered frame (60-144 Hz) allocates needlessly;
    // instead we cache it and rebuild only when animatedMaps is mutated.
    private static final Map<String, List<Integer>> animSyncGroups = new HashMap<>();
    private static boolean animSyncGroupsDirty = false;

    private static void markSyncGroupsDirty() { animSyncGroupsDirty = true; }

    /** Map IDs we've already logged a first-render-update for, to keep mixin logs cheap. */
    private static final Set<Integer> renderUpdateSeen = new HashSet<>();
    /** Map IDs we've already logged a first-scan for, to keep scanner logs cheap. */
    private static final Set<Integer> scanSeen = new HashSet<>();
    /** Per-mapId paint log counter — log first {@link #PAINT_LOG_LIMIT} paints per session. */
    private static final Map<Integer, Integer> paintLogCount = new HashMap<>();
    private static final int PAINT_LOG_LIMIT = 3;
    /** Map IDs we've already logged a first-unlock for. */
    private static final Set<Integer> unlockLogged = new HashSet<>();
    /** Map IDs whose LOOM header we've already logged (once per session). */
    private static final Set<Integer> loomHeaderSeen = new HashSet<>();

    public static boolean decodingEnabled = true;

    /** Force the frame scanner to run on the very next tick. */
    public static void forceRescanOnNextTick() {
        tickCounter = SCAN_INTERVAL_TICKS;
    }
    private static final Map<Integer, byte[]> rawColors     = new HashMap<>();
    private static final Map<Integer, byte[]> decodedColors = new HashMap<>();

    // ── Per-map metadata (title / author from the manifest) ───────────
    // encrypted=true while the tile has metadata from the plaintext envelope header
    // but has not yet been decrypted; set to false once processDecompressedPayload runs.
    private record MapMeta(String title, String author, int cols, int rows,
                           int tileCol, int tileRow, boolean encrypted) {}
    private static final Map<Integer, MapMeta> mapMeta = new HashMap<>();

    // ── Mux (cross-tile redistribution) state ────────────────────────────

    private static class MuxBuffer {
        final byte[] data;
        int received;
        MapIdComponent mapId;
        MapState mapState;
        ItemFrameEntity frame;

        MuxBuffer(int total) { this.data = new byte[total]; }
    }

    /** Fully-allocated mux buffers, keyed by "col:row". */
    private static final Map<String, MuxBuffer> muxBuffers = new HashMap<>();
    /** Segments received before the receiver's LR banner was processed. */
    private static final Map<String, List<Integer>> muxPendingOffsets = new HashMap<>();
    private static final Map<String, List<byte[]>>  muxPendingBytes   = new HashMap<>();

    // ── Composite (composition-wide lossy AV1) state ──────────────────────
    // FLAG_AV1_COMPOSITE tiles each carry a byte-segment of ONE lossy AV1 stream that covers the
    // whole composition at (cols·128)×(rows·128) — encoded once, so tile boundaries have no lossy
    // seams.  Segments concatenate in tile-index order; nothing can display until every tile of
    // the composition has been seen at least once.

    /** One tile's registration inside a composite buffer (framePos null for held maps). */
    private record CompositeTile(MapIdComponent mapId, MapState mapState, BlockPos framePos) {}

    private static final class CompositeBuffer {
        final int cols, rows, frameCount, loopCount;
        final int[] frameDelays;
        final String username, title;
        final byte[][] segments;      // [tileIndex] = this tile's slice of the AV1 stream
        final CompositeTile[] tiles;  // [tileIndex] = where to paint once decoded
        int received;                 // distinct tiles seen
        boolean decoding;             // completion decode submitted

        CompositeBuffer(PayloadManifest m) {
            this.cols = m.cols; this.rows = m.rows;
            this.frameCount = Math.max(1, m.frameCount);
            this.loopCount = m.loopCount;
            this.frameDelays = m.frameDelays;
            this.username = m.username; this.title = m.title;
            this.segments = new byte[cols * rows][];
            this.tiles = new CompositeTile[cols * rows];
        }
    }

    /** In-progress composite compositions, keyed by grid + frames + nonce + author + title. */
    private static final Map<String, CompositeBuffer> compositeBuffers = new HashMap<>();

    /** AV1 decode is heavy (~0.1–0.25 s/frame even compiled) — keep it off the game thread. */
    private static final ExecutorService DECODE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "loominary-av1-decode");
                t.setDaemon(true);
                return t;
            });

    // ── Animated map state ────────────────────────────────────────────────

    private static final class AnimatedMapState {
        final MapIdComponent mapId;
        final byte[][] frames;
        /** Length 1 = global delay; length N = per-frame delay table. */
        final int[] delaysMs;
        final int loopCount;
        BlockPos framePos;
        /** Manifest grid dimensions, used to group sibling tiles for sync. */
        final int manifestCols;
        final int manifestRows;
        final String syncUsername;
        final String syncTitle;
        int  currentFrame;
        long lastAdvanceMs;
        int  loopsCompleted;

        AnimatedMapState(MapIdComponent mapId, byte[][] frames, int[] delaysMs, int loopCount,
                         BlockPos framePos, int manifestCols, int manifestRows,
                         String syncUsername, String syncTitle) {
            this.mapId = mapId;
            this.frames = frames;
            this.delaysMs = delaysMs;
            this.loopCount = loopCount;
            this.framePos = framePos;
            this.manifestCols = manifestCols;
            this.manifestRows = manifestRows;
            this.syncUsername = syncUsername;
            this.syncTitle = syncTitle;
            this.currentFrame = 0;
            this.lastAdvanceMs = System.currentTimeMillis();
            this.loopsCompleted = 0;
        }

        String syncKey() {
            return manifestCols + ":" + manifestRows + ":" + frames.length
                    + ":" + (syncUsername == null ? "" : syncUsername)
                    + ":" + (syncTitle    == null ? "" : syncTitle);
        }

        int currentDelayMs() {
            return delaysMs.length == 1 ? delaysMs[0] : delaysMs[currentFrame];
        }
    }

    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearCache());

        // Animation runs on the render thread every frame so frame delays shorter
        // than one game tick (50 ms) are honoured accurately.
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null && client.player != null && !animatedMaps.isEmpty())
                advanceAnimatedFrames(client);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null)
                return;

            // Held-map check runs every tick: cheap for claimed maps, decodes once on first encounter.
            processHeldItem(client, client.player.getMainHandStack());
            processHeldItem(client, client.player.getOffHandStack());

            // Show title/author in the action bar when looking at or holding a decoded map.
            if (!mapMeta.isEmpty()) showMapMetaActionBar(client);

            // Frame scan runs every 20 ticks to discover new encoded maps.
            tickCounter++;
            if (tickCounter < SCAN_INTERVAL_TICKS)
                return;
            tickCounter = 0;

            Box box = client.player.getBoundingBox().expand(SCAN_RADIUS);
            List<ItemFrameEntity> frames = client.world.getEntitiesByClass(
                    ItemFrameEntity.class, box, e -> true);

            for (ItemFrameEntity frame : frames) {
                processFrame(client, frame);
            }
        });
    }

    private static void processHeldItem(MinecraftClient client, ItemStack stack) {
        if (!(stack.getItem() instanceof FilledMapItem))
            return;

        MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
        if (mapId == null)
            return;

        MapState mapState = FilledMapItem.getMapState(mapId, client.world);
        if (mapState == null)
            return;

        Map<String, MapDecoration> decorations = ((MapStateAccessor) mapState).getDecorations();

        if (claimedMaps.contains(mapId.id())) {
            AnimatedMapState anim = animatedMaps.get(mapId.id());
            if (!decorations.isEmpty())
                decorations.clear();
            byte[] expected = anim != null ? anim.frames[anim.currentFrame] : decodedColors.get(mapId.id());
            if (expected != null && !Arrays.equals(mapState.colors, expected))
                paintMap(client, mapId, mapState, expected);
            return;
        }

        if (decorations.isEmpty())
            return;

        // Carpet-hybrid encoding (LC/LS manifest banner). Skip LR mux-receiver — it
        // requires payload segments from sibling tiles that may not be loaded in hand.
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            boolean isLC = s.length() >= 6  && s.startsWith("LC");
            boolean isLS = s.length() >= 10 && s.startsWith("LS");
            if (isLC || isLS) {
                processCarpetFrame(client, mapId, mapState, s, decorations, null);
                return;
            }
        }

        // Legacy banner encoding: hex-indexed chunks.
        List<String> names = new ArrayList<>();
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            if (s.length() < 2) continue;
            try { Integer.parseInt(s.substring(0, 2), 16); }
            catch (NumberFormatException e) { continue; }
            names.add(s);
        }
        if (names.isEmpty())
            return;

        System.out.println(TAG + " Decoding held map id=" + mapId.id()
                + " from " + names.size() + " banner markers");
        try {
            byte[] compressed = assembleCompressed(names);
            if (MapEncryption.isEncrypted(compressed)) {
                submitDecryptAndProcess(client, mapId, mapState, null, compressed, decorations);
                return;
            }
            byte[] full = decompressPayload(compressed);
            processDecompressedPayload(client, mapId, mapState, null, full);
            claimedMaps.add(mapId.id());
            decorations.clear();
            System.out.println(TAG + " Claimed held map " + mapId.id());
        } catch (Exception e) {
            System.err.println(TAG + " Held map decode failed for map " + mapId.id()
                    + ": " + e.getMessage());
        }
    }

    private static void processFrame(MinecraftClient client, ItemFrameEntity frame) {
        ItemStack stack = frame.getHeldItemStack();
        if (!(stack.getItem() instanceof FilledMapItem))
            return;

        MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
        if (mapId == null)
            return;

        MapState mapState = FilledMapItem.getMapState(mapId, client.world);
        if (mapState == null)
            return;

        Map<String, MapDecoration> decorations = ((MapStateAccessor) mapState).getDecorations();

        if (scanSeen.add(mapId.id())) {
            System.out.println(TAG + " scan first-see mapId=" + mapId.id()
                    + " framePos=" + frame.getBlockPos()
                    + " locked=" + mapState.locked
                    + " claimed=" + claimedMaps.contains(mapId.id())
                    + " decorations=" + decorations.size());
        }

        if (claimedMaps.contains(mapId.id())) {
            AnimatedMapState anim = animatedMaps.get(mapId.id());
            if (anim != null) {
                anim.framePos = frame.getBlockPos();
            }
            if (!decorations.isEmpty()) {
                decorations.clear();
            }
            // When another player places/replaces the item frame, the server resends
            // the map snapshot (raw carpet bytes) to observers, overwriting our paint.
            // Detect and re-paint from the cached decoded bytes.
            byte[] expected = anim != null
                    ? anim.frames[anim.currentFrame]
                    : decodedColors.get(mapId.id());
            if (expected != null && !Arrays.equals(mapState.colors, expected)) {
                System.out.println(TAG + " server overwrite detected mapId=" + mapId.id()
                        + " — re-painting cached decode");
                paintMap(client, mapId, mapState, expected);
            }
            return;
        }

        // A LOOM carpet tile carries its data in the carpet channel, not in
        // decorations, so it can legitimately have *no* decorations — e.g. a
        // pure-CARPET tile, or a blank mux donor whose only job is to carry
        // guest payload. Such tiles must still be processed; skipping them here
        // is why muxed walls failed to decode until a donor was replaced (which
        // happened to re-trigger a scan once its colours had synced).
        if (decorations.isEmpty() && !CarpetChannel.peekLoomMagic(mapState.colors))
            return;

        // 1. LR banner — legacy carpet mux receiver.
        // 2. LB banner — banner-only mux receiver.
        // 3. LOOM magic in carpet — new-format tile (carpet/carpet+shade/carpet-only).
        // 4. LC/LS banner — legacy carpet tile (backward compat).
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            if (s.length() >= 18 && s.startsWith("LR")) {
                processReceiverCarpetFrame(client, mapId, mapState, s, decorations, frame);
                return;
            }
            if (s.length() >= 18 && s.startsWith("LB")) {
                processBannerMuxReceiver(client, mapId, mapState, s, decorations);
                return;
            }
        }

        if (CarpetChannel.peekLoomMagic(mapState.colors)) {
            processLoomCarpetFrame(client, mapId, mapState, decorations, frame);
            return;
        }

        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            boolean isLC = s.length() >= 6  && s.startsWith("LC");
            boolean isLS = s.length() >= 10 && s.startsWith("LS");
            if (isLC || isLS) {
                processCarpetFrame(client, mapId, mapState, s, decorations, frame);
                return;
            }
        }

        // MG banners without carpet → banner-only mux donor.
        boolean hasMG = false;
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            if (s.length() >= 24 && s.startsWith("MG")) { hasMG = true; break; }
        }
        if (hasMG) {
            processBannerMuxDonor(client, mapId, mapState, decorations);
            return;
        }

        // Filter for legacy banner markers — skip any other decorations
        // (player markers, other players, non-banner decorations, etc.)
        List<String> names = new ArrayList<>();
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null)
                continue; // player markers etc. have no name
            String s = text.getString();
            if (s.length() < 2)
                continue;
            try {
                Integer.parseInt(s.substring(0, 2), 16);
            } catch (NumberFormatException e) {
                continue; // not our format, skip
            }
            names.add(s);
        }
        if (names.isEmpty())
            return;

        System.out.println(TAG + " Decoding map id=" + mapId.id()
                + " from " + names.size() + " banner markers");

        try {
            byte[] compressed = assembleCompressed(names);
            if (MapEncryption.isEncrypted(compressed)) {
                submitDecryptAndProcess(client, mapId, mapState, frame, compressed, decorations);
                return;
            }
            byte[] full = decompressPayload(compressed);
            processDecompressedPayload(client, mapId, mapState, frame, full);
            claimedMaps.add(mapId.id());
            decorations.clear();
            System.out.println(TAG + " Claimed map " + mapId.id());
        } catch (Exception e) {
            System.err.println(TAG + " Reconstruction failed for map " + mapId.id()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Carpet-hybrid decode ──────────────────────────────────────────────

    /**
     * Decodes a carpet-hybrid encoded map.
     *
     * <p>Format: the carpet platform encodes the first {@code min(N,8192)} bytes of
     * the zstd-compressed payload as nibble pairs in {@code mapState.colors}.
     * When N > 8192, the next {@code min(N−8192, 2032)} bytes are read from the shade
     * channel (1 shade bit per pixel, rows 1–127). Any remaining bytes are carried by
     * the LC manifest banner payload (first 44 base64 chars) and hex-indexed banners
     * {@code 00}–{@code 3D}.
     *
     * @param lcName name of the LC manifest banner: {@code LC<4-hex-N>[<b64-overflow>]}
     */
    private static void processCarpetFrame(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            String lcName, Map<String, MapDecoration> decorations,
            ItemFrameEntity frame) {
        try {
            // LS<4-hex-total><4-hex-shade>[<b64-overflow>]  — shade channel present
            // LC<4-hex-total>[<b64-overflow>]               — carpet only (old format)
            boolean hasShade = lcName.startsWith("LS");
            int totalBytes = Integer.parseInt(lcName.substring(2, 6), 16);
            int shadeBytes;
            String lcPayload;
            if (hasShade) {
                shadeBytes = Integer.parseInt(lcName.substring(6, 10), 16);
                lcPayload  = lcName.length() > 10 ? lcName.substring(10) : "";
            } else {
                shadeBytes = 0;
                lcPayload  = lcName.length() > 6 ? lcName.substring(6) : "";
            }

            int carpetBytes = Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES);
            byte[] carpetData = CarpetChannel.decodeBytes(mapState.colors, carpetBytes);

            byte[] shadeData = shadeBytes > 0
                    ? CarpetChannel.decodeShade(mapState.colors, shadeBytes)
                    : new byte[0];

            int overflowStart = carpetBytes + shadeBytes;
            byte[] overflowData = new byte[0];
            if (totalBytes > overflowStart) {
                // Collect hex-indexed overflow banners, sorted by index.
                List<String> overflowNames = new ArrayList<>();
                for (MapDecoration dec : decorations.values()) {
                    Text text = dec.name().orElse(null);
                    if (text == null) continue;
                    String s = text.getString();
                    if (s.startsWith("LC")) continue;
                    if (s.length() < 2) continue;
                    try { Integer.parseInt(s.substring(0, 2), 16); }
                    catch (NumberFormatException e) { continue; }
                    overflowNames.add(s);
                }
                overflowNames.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));

                // CJK overflow (new format): first hex banner's payload starts with a CJK char.
                // Base64 overflow (legacy format): manifest payload + banner payloads as ASCII.
                boolean cjkOverflow = !overflowNames.isEmpty()
                        && overflowNames.get(0).length() > 2
                        && overflowNames.get(0).charAt(2) >= CjkCodec.ALPHA_BASE;
                if (cjkOverflow) {
                    overflowData = CjkCodec.assembleChunks(overflowNames);
                } else {
                    StringBuilder b64 = new StringBuilder(lcPayload);
                    for (String s : overflowNames) b64.append(s.substring(2));
                    overflowData = Base64.getDecoder().decode(b64.toString());
                }
            }

            byte[] compressed = new byte[carpetBytes + shadeData.length + overflowData.length];
            System.arraycopy(carpetData,  0, compressed, 0,                              carpetBytes);
            System.arraycopy(shadeData,   0, compressed, carpetBytes,                    shadeData.length);
            System.arraycopy(overflowData, 0, compressed, carpetBytes + shadeData.length, overflowData.length);

            if (MapEncryption.isEncrypted(compressed)) {
                submitDecryptAndProcess(client, mapId, mapState, frame, compressed, decorations);
                return;
            }

            long frameSize = Zstd.getFrameContentSize(compressed);
            if (frameSize < 0) throw new IllegalStateException("Missing zstd frame size");
            byte[] full = Zstd.decompress(compressed, (int) frameSize);

            System.out.println(TAG + " Carpet-decoded map id=" + mapId.id()
                    + " (" + totalBytes + " compressed bytes, "
                    + carpetBytes + " carpet + " + shadeBytes + " shade)");

            processDecompressedPayload(client, mapId, mapState, frame, full);

            // Route any MG guest segments hosted in this tile's cargo.
            // MG<seqIdx:2><ownLen:4><tCol:2><tRow:2><tOffset:8><tLen:4>
            List<String> mgBanners = new ArrayList<>();
            for (MapDecoration dec : decorations.values()) {
                Text t2 = dec.name().orElse(null);
                if (t2 == null) continue;
                String s = t2.getString();
                if (s.length() >= 24 && s.startsWith("MG")) mgBanners.add(s);
            }
            if (!mgBanners.isEmpty()) {
                mgBanners.sort(java.util.Comparator.comparingInt(
                        s -> Integer.parseInt(s.substring(2, 4), 16)));
                int mgOwnLen = Integer.parseInt(mgBanners.get(0).substring(4, 8), 16);
                int guestStart = mgOwnLen;
                for (String mg : mgBanners) {
                    int tCol    = Integer.parseInt(mg.substring(8, 10), 16);
                    int tRow    = Integer.parseInt(mg.substring(10, 12), 16);
                    int tOffset = (int) Long.parseLong(mg.substring(12, 20), 16);
                    int tLen    = Integer.parseInt(mg.substring(20, 24), 16);
                    String key = tCol + ":" + tRow;
                    routeMuxSegment(key, tOffset, compressed, guestStart, tLen);
                    checkMuxCompletion(key, client);
                    guestStart += tLen;
                }
            }

            claimedMaps.add(mapId.id());
            decorations.clear();
            System.out.println(TAG + " Claimed map " + mapId.id() + " (carpet)");

        } catch (Exception e) {
            System.err.println(TAG + " Carpet decode failed for map " + mapId.id()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Decodes a mux receiver tile — its own segment is stored in its carpet, while
     * the remainder of its payload is distributed across donor tiles' carpets.
     *
     * <p>LR banner format: {@code LR<col:2><row:2><ownLen:4><total:8>}
     */
    private static void processReceiverCarpetFrame(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            String lrName, Map<String, MapDecoration> decorations,
            ItemFrameEntity frame) {
        try {
            int col    = Integer.parseInt(lrName.substring(2, 4), 16);
            int row    = Integer.parseInt(lrName.substring(4, 6), 16);
            int ownLen = Integer.parseInt(lrName.substring(6, 10), 16);
            int total  = (int) Long.parseLong(lrName.substring(10, 18), 16);

            // Derive carpet/shade split from ownLen (same formula as encoder).
            int carpetBytes = Math.min(ownLen, CarpetChannel.MAX_CARPET_BYTES);
            boolean hasShade = ownLen > CarpetChannel.MAX_CARPET_BYTES + CarpetChannel.MAX_OVERFLOW_BYTES;
            int shadeBytes = hasShade ? Math.min(ownLen - carpetBytes, CarpetChannel.MAX_SHADE_BYTES) : 0;

            byte[] carpetData = CarpetChannel.decodeBytes(mapState.colors, carpetBytes);
            byte[] shadeData  = shadeBytes > 0 ? CarpetChannel.decodeShade(mapState.colors, shadeBytes) : new byte[0];

            int overflowStart = carpetBytes + shadeBytes;
            byte[] overflowData = new byte[0];
            if (ownLen > overflowStart) {
                List<String> ovfNames = new ArrayList<>();
                for (MapDecoration dec : decorations.values()) {
                    Text t2 = dec.name().orElse(null);
                    if (t2 == null) continue;
                    String s = t2.getString();
                    if (s.startsWith("LR")) continue;
                    if (s.length() < 2) continue;
                    try { Integer.parseInt(s.substring(0, 2), 16); }
                    catch (NumberFormatException e) { continue; }
                    ovfNames.add(s);
                }
                ovfNames.sort(java.util.Comparator.comparingInt(
                        s -> Integer.parseInt(s.substring(0, 2), 16)));
                boolean cjk = !ovfNames.isEmpty() && ovfNames.get(0).length() > 2
                        && ovfNames.get(0).charAt(2) >= CjkCodec.ALPHA_BASE;
                if (cjk) {
                    overflowData = CjkCodec.assembleChunks(ovfNames);
                } else {
                    StringBuilder b64 = new StringBuilder();
                    for (String s : ovfNames) b64.append(s.substring(2));
                    overflowData = Base64.getDecoder().decode(b64.toString());
                }
            }

            byte[] ownSeg = new byte[ownLen];
            System.arraycopy(carpetData, 0, ownSeg, 0, carpetBytes);
            System.arraycopy(shadeData, 0, ownSeg, carpetBytes, shadeData.length);
            System.arraycopy(overflowData, 0, ownSeg, carpetBytes + shadeData.length, overflowData.length);

            System.out.println(TAG + " Mux receiver LR(" + col + "," + row + ") map id="
                    + mapId.id() + " ownLen=" + ownLen + " total=" + total);

            String key = col + ":" + row;
            // Allocate buffer if needed, then add the own segment at offset 0.
            MuxBuffer buf = muxBuffers.get(key);
            if (buf == null) {
                buf = new MuxBuffer(total);
                buf.mapId    = mapId;
                buf.mapState = mapState;
                buf.frame    = frame;
                // Apply any segments that arrived before this banner was scanned.
                List<Integer> pendingOff = muxPendingOffsets.remove(key);
                List<byte[]>  pendingByt = muxPendingBytes.remove(key);
                if (pendingOff != null) {
                    for (int i = 0; i < pendingOff.size(); i++) {
                        int off = pendingOff.get(i);
                        byte[] pb = pendingByt.get(i);
                        System.arraycopy(pb, 0, buf.data, off, pb.length);
                        buf.received += pb.length;
                    }
                }
                muxBuffers.put(key, buf);
            }
            System.arraycopy(ownSeg, 0, buf.data, 0, ownLen);
            buf.received += ownLen;

            claimedMaps.add(mapId.id());
            decorations.clear();
            checkMuxCompletion(key, client);

        } catch (Exception e) {
            System.err.println(TAG + " Mux receiver decode failed for map " + mapId.id()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── LOOM-format carpet decode ─────────────────────────────────────────

    /**
     * Decodes a tile encoded with the LOOM-header carpet format (all carpet codec modes:
     * CARPET, CARPET_SHADE, CARPET_BANNERS, CARPET_BANNERS_SHADE).  The first 4 bytes of the carpet channel
     * must already have been verified as LOOM magic by the caller.
     *
     * <p>Handles four sub-cases via LOOM header flags:
     * <ul>
     *   <li>Normal tile (no mux): decode own zstd frame, paint map.</li>
     *   <li>MUX_RX + BANNERS (carpet/carpet+shade receiver): set up mux buffer,
     *       fill with own segment from carpet+shade+overflow, wait for MG donors.</li>
     *   <li>MUX_RX (carpet-only receiver): set up mux buffer, fill from carpet+shade.</li>
     *   <li>MUX_DONOR (carpet-only donor): decode own frame, route guest bytes per
     *       LOOM guest descriptors; also route any MG banners if BANNERS flag set.</li>
     * </ul>
     */
    private static void processLoomCarpetFrame(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            Map<String, MapDecoration> decorations, ItemFrameEntity frame) {
        // Hoist header outside the try-catch so the catch block can tailor its message
        // based on what kind of tile this is (mux receiver vs. standalone/donor).
        CarpetChannel.LoomHeader header = null;
        try {
            // Two-pass read: first decode just the fixed LOOM header (16 bytes = 32 nibbles)
            // to learn the actual cargo size, then decode only as many bytes as the header
            // says exist. Reading MAX_CARPET_BYTES upfront would fail on small tiles where
            // only a fraction of the map is covered by carpet blocks.
            //
            // Peek guestCount from byte 15 directly — do NOT call decodeLoomHeader on the
            // 16-byte slice when guestCount > 0, because decodeLoomHeader immediately tries
            // to parse guest descriptors that aren't in those 16 bytes.
            byte[] fixedHeader = CarpetChannel.decodeBytes(mapState.colors, CarpetChannel.LOOM_FIXED_HEADER);
            int guestCount = fixedHeader[15] & 0xFF;
            int fullHeaderSize = CarpetChannel.LOOM_FIXED_HEADER + guestCount * CarpetChannel.LOOM_GUEST_DESC;
            byte[] headerBytes = guestCount > 0
                    ? CarpetChannel.decodeBytes(mapState.colors, fullHeaderSize)
                    : fixedHeader;
            header = CarpetChannel.decodeLoomHeader(headerBytes);

            if (loomHeaderSeen.add(mapId.id())) {
                System.out.println(TAG + " LOOM header map " + mapId.id()
                        + " flags=0x" + Integer.toHexString(header.flags)
                        + (header.isMuxRx()    ? " [MUX_RX]"    : "")
                        + (header.isMuxDonor() ? " [MUX_DONOR]" : "")
                        + (header.hasShade()   ? " [SHADE]"      : "")
                        + (header.hasBanners() ? " [BANNERS]"    : "")
                        + " col=" + header.col + " row=" + header.row
                        + " ownBytes=" + header.ownBytes
                        + " totalBytes=" + header.totalBytes
                        + " guestCount=" + header.guestCount);
            }

            // Total cargo size = dataOffset (header+descriptors) + ownBytes + guest bytes.
            int guestTotal = 0;
            for (int g = 0; g < header.guestCount; g++) guestTotal += header.guestDescs[g][3];
            int totalCargoForOwnAndGuests = header.dataOffset + header.ownBytes + guestTotal;

            // --- Assemble full cargo from all channels ---
            int carpetBytes = Math.min(totalCargoForOwnAndGuests, CarpetChannel.MAX_CARPET_BYTES);
            // Read exactly as many carpet bytes as the header says — no more.
            byte[] carpetFull = CarpetChannel.decodeBytes(mapState.colors, carpetBytes);
            byte[] carpet = Arrays.copyOf(carpetFull, carpetBytes);

            byte[] shade = new byte[0];
            int shadeBytes = 0;
            if (header.hasShade() && totalCargoForOwnAndGuests > CarpetChannel.MAX_CARPET_BYTES) {
                shadeBytes = Math.min(
                        totalCargoForOwnAndGuests - CarpetChannel.MAX_CARPET_BYTES,
                        CarpetChannel.MAX_SHADE_BYTES);
                shade = CarpetChannel.decodeShade(mapState.colors, shadeBytes);
            }

            byte[] overflow = new byte[0];
            if (header.hasBanners() && totalCargoForOwnAndGuests > carpetBytes + shadeBytes) {
                List<String> ovfNames = new ArrayList<>();
                for (MapDecoration dec : decorations.values()) {
                    Text t2 = dec.name().orElse(null);
                    if (t2 == null) continue;
                    String s = t2.getString();
                    if (s.length() < 2) continue;
                    try { Integer.parseInt(s.substring(0, 2), 16); }
                    catch (NumberFormatException e) { continue; }
                    ovfNames.add(s);
                }
                ovfNames.sort(java.util.Comparator.comparingInt(
                        s -> Integer.parseInt(s.substring(0, 2), 16)));
                if (!ovfNames.isEmpty()) {
                    boolean cjk = ovfNames.get(0).length() > 2
                            && ovfNames.get(0).charAt(2) >= CjkCodec.ALPHA_BASE;
                    overflow = cjk ? CjkCodec.assembleChunks(ovfNames)
                                   : Base64.getDecoder().decode(
                                       ovfNames.stream()
                                           .map(s -> s.substring(2))
                                           .reduce("", String::concat));
                }
            }

            byte[] cargo = new byte[carpetBytes + shadeBytes + overflow.length];
            System.arraycopy(carpet,   0, cargo, 0,                     carpetBytes);
            System.arraycopy(shade,    0, cargo, carpetBytes,            shadeBytes);
            System.arraycopy(overflow, 0, cargo, carpetBytes + shadeBytes, overflow.length);

            // --- Dispatch by mux role ---
            if (header.isMuxRx()) {
                // Mux receiver: own segment is cargo[dataOffset .. dataOffset+ownBytes).
                // Full payload is header.totalBytes (gathered from donors too).
                int col = header.col, row = header.row;
                String key = col + ":" + row;

                byte[] ownSeg = Arrays.copyOfRange(cargo, header.dataOffset,
                        header.dataOffset + header.ownBytes);

                System.out.println(TAG + " LOOM mux receiver (" + col + "," + row + ") map id="
                        + mapId.id() + " ownBytes=" + header.ownBytes
                        + " total=" + header.totalBytes);

                MuxBuffer buf = muxBuffers.get(key);
                if (buf == null) {
                    buf = new MuxBuffer(header.totalBytes);
                    buf.mapId    = mapId;
                    buf.mapState = mapState;
                    buf.frame    = frame;
                    List<Integer> pendingOff = muxPendingOffsets.remove(key);
                    List<byte[]>  pendingByt = muxPendingBytes.remove(key);
                    if (pendingOff != null) {
                        for (int i = 0; i < pendingOff.size(); i++) {
                            int off = pendingOff.get(i);
                            byte[] pb = pendingByt.get(i);
                            System.arraycopy(pb, 0, buf.data, off, pb.length);
                            buf.received += pb.length;
                        }
                    }
                    muxBuffers.put(key, buf);
                }
                System.arraycopy(ownSeg, 0, buf.data, 0, ownSeg.length);
                buf.received += ownSeg.length;
                claimedMaps.add(mapId.id());
                decorations.clear();
                checkMuxCompletion(key, client);
                // If completion didn't fire, the receiver is waiting for donor tiles.
                MuxBuffer afterCheck = muxBuffers.get(key);
                if (afterCheck != null && afterCheck.received < afterCheck.data.length) {
                    System.out.println(TAG + " Mux receiver (" + col + "," + row + ") map "
                            + mapId.id() + " awaiting donor tiles — received "
                            + afterCheck.received + " / " + afterCheck.data.length + " bytes.");
                }

            } else {
                // Non-receiver: decode own zstd frame at cargo[dataOffset..dataOffset+ownBytes).
                // ownBytes may legitimately be 0 for blank donor tiles whose only job is to
                // carry guest payload — in that case skip decompression entirely.
                if (header.ownBytes > 0) {
                    byte[] compressed = Arrays.copyOfRange(cargo, header.dataOffset,
                            header.dataOffset + header.ownBytes);
                    if (MapEncryption.isEncrypted(compressed)) {
                        submitDecryptAndProcess(client, mapId, mapState, frame, compressed, decorations);
                        return;
                    }
                    long frameSize = Zstd.getFrameContentSize(compressed);
                    if (frameSize < 0)
                        throw new IllegalStateException("Invalid zstd frame in LOOM tile (map " + mapId.id() + ")");
                    byte[] full = Zstd.decompress(compressed, (int) frameSize);
                    processDecompressedPayload(client, mapId, mapState, frame, full);
                } else {
                    // Blank donor: it carries no image of its own, only guest payload.
                    // Render it as fully transparent (map colour id 0) so the wall shows
                    // a clean hole instead of the raw carpet-encoding noise.
                    paintMap(client, mapId, mapState, new byte[MAP_BYTES]);
                }

                // Route LOOM guest descriptors (CARPET_ONLY mux donor).
                if (header.isMuxDonor() && header.guestCount > 0) {
                    int guestPos = header.dataOffset + header.ownBytes;
                    for (int g = 0; g < header.guestCount; g++) {
                        int tCol    = header.guestDescs[g][0];
                        int tRow    = header.guestDescs[g][1];
                        int tOffset = header.guestDescs[g][2];
                        int tLen    = header.guestDescs[g][3];
                        routeMuxSegment(tCol + ":" + tRow, tOffset, cargo, guestPos, tLen);
                        checkMuxCompletion(tCol + ":" + tRow, client);
                        guestPos += tLen;
                    }
                }

                // Route MG banners (CARPET/CARPET_SHADE mux donor).
                // MG<seqIdx:2><ownLen:4><tCol:2><tRow:2><tOffset:8><tLen:4>
                List<String> mgBanners = new ArrayList<>();
                for (MapDecoration dec : decorations.values()) {
                    Text t2 = dec.name().orElse(null);
                    if (t2 == null) continue;
                    String s = t2.getString();
                    if (s.length() >= 24 && s.startsWith("MG")) mgBanners.add(s);
                }
                if (!mgBanners.isEmpty()) {
                    mgBanners.sort(java.util.Comparator.comparingInt(
                            s -> Integer.parseInt(s.substring(2, 4), 16)));
                    int mgOwnLen = Integer.parseInt(mgBanners.get(0).substring(4, 8), 16);
                    int guestStart = mgOwnLen;
                    for (String mg : mgBanners) {
                        int tCol    = Integer.parseInt(mg.substring(8, 10), 16);
                        int tRow    = Integer.parseInt(mg.substring(10, 12), 16);
                        int tOffset = (int) Long.parseLong(mg.substring(12, 20), 16);
                        int tLen    = Integer.parseInt(mg.substring(20, 24), 16);
                        routeMuxSegment(tCol + ":" + tRow, tOffset, cargo, guestStart, tLen);
                        checkMuxCompletion(tCol + ":" + tRow, client);
                        guestStart += tLen;
                    }
                }

                claimedMaps.add(mapId.id());
                decorations.clear();
                System.out.println(TAG + " LOOM-decoded map " + mapId.id()
                        + " ownBytes=" + header.ownBytes
                        + (header.isMuxDonor() ? " [donor guests=" + header.guestCount + "]" : "")
                        + (!mgBanners.isEmpty() ? " [MG donors=" + mgBanners.size() + "]" : ""));
            }

        } catch (Exception e) {
            if (header != null && header.isMuxRx()) {
                // The receiver tile's own carpet data couldn't be read — this is a placement
                // issue with the receiver tile itself, not a missing-donor problem.
                System.err.println(TAG + " LOOM mux receiver (" + header.col + "," + header.row
                        + ") map " + mapId.id() + " carpet read failed: " + e.getMessage());
                System.err.println(TAG + "   Receiver tile carpet is incomplete or misread."
                        + " Donor tiles are placed separately and are not the cause of this error.");
                e.printStackTrace();
            } else {
                System.err.println(TAG + " LOOM decode failed for map " + mapId.id()
                        + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ── Banner-only mux decode ────────────────────────────────────────────

    /**
     * Decodes a banner-only mux receiver tile.  Identified by an {@code LB} banner:
     * {@code LB<col:2><row:2><ownLen:4><total:8>}.
     *
     * <p>The receiver's own segment is carried in hex-indexed CJK payload banners
     * (00, 01, …), exactly like regular banner-only tiles except the LB banner
     * replaces the first chunk slot.
     */
    private static void processBannerMuxReceiver(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            String lbName, Map<String, MapDecoration> decorations) {
        try {
            int col    = Integer.parseInt(lbName.substring(2, 4), 16);
            int row    = Integer.parseInt(lbName.substring(4, 6), 16);
            int ownLen = Integer.parseInt(lbName.substring(6, 10), 16);
            int total  = (int) Long.parseLong(lbName.substring(10, 18), 16);

            // Collect hex-indexed payload banners for the own segment.
            List<String> names = new ArrayList<>();
            for (MapDecoration dec : decorations.values()) {
                Text t2 = dec.name().orElse(null);
                if (t2 == null) continue;
                String s = t2.getString();
                if (s.length() < 2) continue;
                try { Integer.parseInt(s.substring(0, 2), 16); }
                catch (NumberFormatException e) { continue; }
                names.add(s);
            }
            names.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));

            byte[] ownSeg;
            if (!names.isEmpty() && names.get(0).length() > 2
                    && names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE) {
                ownSeg = CjkCodec.assembleChunks(names);
            } else {
                StringBuilder b64 = new StringBuilder();
                for (String s : names) b64.append(s.substring(2));
                ownSeg = Base64.getDecoder().decode(b64.toString());
            }
            // Trim to ownLen in case of encoding-boundary padding.
            if (ownSeg.length > ownLen) ownSeg = Arrays.copyOf(ownSeg, ownLen);

            System.out.println(TAG + " Banner mux receiver LB(" + col + "," + row + ") map id="
                    + mapId.id() + " ownLen=" + ownLen + " total=" + total);

            String key = col + ":" + row;
            MuxBuffer buf = muxBuffers.get(key);
            if (buf == null) {
                buf = new MuxBuffer(total);
                buf.mapId    = mapId;
                buf.mapState = mapState;
                buf.frame    = null;
                List<Integer> pendingOff = muxPendingOffsets.remove(key);
                List<byte[]>  pendingByt = muxPendingBytes.remove(key);
                if (pendingOff != null) {
                    for (int i = 0; i < pendingOff.size(); i++) {
                        int off = pendingOff.get(i);
                        byte[] pb = pendingByt.get(i);
                        System.arraycopy(pb, 0, buf.data, off, pb.length);
                        buf.received += pb.length;
                    }
                }
                muxBuffers.put(key, buf);
            }
            System.arraycopy(ownSeg, 0, buf.data, 0, ownSeg.length);
            buf.received += ownSeg.length;
            claimedMaps.add(mapId.id());
            decorations.clear();
            checkMuxCompletion(key, client);

        } catch (Exception e) {
            System.err.println(TAG + " Banner mux receiver decode failed for map " + mapId.id()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Decodes a banner-only mux donor tile.  The tile has no carpet encoding;
     * its decorations contain hex-indexed CJK payload banners (own frame + guest bytes)
     * and {@code MG} routing banners.
     */
    private static void processBannerMuxDonor(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            Map<String, MapDecoration> decorations) {
        try {
            // Collect hex-indexed payload banners (own frame + guest bytes as one block).
            List<String> payloadNames = new ArrayList<>();
            List<String> mgBanners = new ArrayList<>();
            for (MapDecoration dec : decorations.values()) {
                Text t2 = dec.name().orElse(null);
                if (t2 == null) continue;
                String s = t2.getString();
                if (s.length() >= 24 && s.startsWith("MG")) { mgBanners.add(s); continue; }
                if (s.length() < 2) continue;
                try { Integer.parseInt(s.substring(0, 2), 16); }
                catch (NumberFormatException e) { continue; }
                payloadNames.add(s);
            }
            if (mgBanners.isEmpty()) return; // sanity check
            payloadNames.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));
            mgBanners.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(2, 4), 16)));

            byte[] cargo;
            if (!payloadNames.isEmpty() && payloadNames.get(0).length() > 2
                    && payloadNames.get(0).charAt(2) >= CjkCodec.ALPHA_BASE) {
                cargo = CjkCodec.assembleChunks(payloadNames);
            } else {
                StringBuilder b64 = new StringBuilder();
                for (String s : payloadNames) b64.append(s.substring(2));
                cargo = Base64.getDecoder().decode(b64.toString());
            }

            // Decompress own frame (starts at cargo[0], length = ownLen from first MG banner).
            int mgOwnLen = Integer.parseInt(mgBanners.get(0).substring(4, 8), 16);
            byte[] compressed = Arrays.copyOfRange(cargo, 0, mgOwnLen);
            long frameSize = Zstd.getFrameContentSize(compressed);
            if (frameSize < 0)
                throw new IllegalStateException("Invalid zstd frame in banner mux donor");
            byte[] full = Zstd.decompress(compressed, (int) frameSize);
            processDecompressedPayload(client, mapId, mapState, null, full);

            // Route guest segments per MG descriptors.
            int guestStart = mgOwnLen;
            for (String mg : mgBanners) {
                int tCol    = Integer.parseInt(mg.substring(8, 10), 16);
                int tRow    = Integer.parseInt(mg.substring(10, 12), 16);
                int tOffset = (int) Long.parseLong(mg.substring(12, 20), 16);
                int tLen    = Integer.parseInt(mg.substring(20, 24), 16);
                routeMuxSegment(tCol + ":" + tRow, tOffset, cargo, guestStart, tLen);
                checkMuxCompletion(tCol + ":" + tRow, client);
                guestStart += tLen;
            }

            claimedMaps.add(mapId.id());
            decorations.clear();
            System.out.println(TAG + " Banner mux donor map " + mapId.id()
                    + " [MG guests=" + mgBanners.size() + "]");

        } catch (Exception e) {
            System.err.println(TAG + " Banner mux donor decode failed for map " + mapId.id()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void routeMuxSegment(String key, int targetOffset,
            byte[] srcBytes, int srcPos, int len) {
        MuxBuffer buf = muxBuffers.get(key);
        if (buf != null) {
            System.arraycopy(srcBytes, srcPos, buf.data, targetOffset, len);
            buf.received += len;
        } else {
            byte[] copy = Arrays.copyOfRange(srcBytes, srcPos, srcPos + len);
            muxPendingOffsets.computeIfAbsent(key, k -> new ArrayList<>()).add(targetOffset);
            muxPendingBytes.computeIfAbsent(key, k -> new ArrayList<>()).add(copy);
        }
    }

    private static void checkMuxCompletion(String key, MinecraftClient client) {
        MuxBuffer buf = muxBuffers.get(key);
        if (buf == null || buf.received < buf.data.length) return;
        muxBuffers.remove(key);
        try {
            byte[] data = buf.data;
            if (MapEncryption.isEncrypted(data)) {
                Map<String, MapDecoration> decs = buf.mapState != null
                        ? ((MapStateAccessor) buf.mapState).getDecorations() : null;
                submitDecryptAndProcess(client, buf.mapId, buf.mapState, buf.frame, data, decs);
                return;
            }
            long frameSize = Zstd.getFrameContentSize(data);
            if (frameSize < 0) {
                System.err.println(TAG + " Mux completion: bad zstd frame for receiver " + key);
                return;
            }
            byte[] full = Zstd.decompress(data, (int) frameSize);
            processDecompressedPayload(client, buf.mapId, buf.mapState, buf.frame, full);
            if (buf.mapState != null) ((MapStateAccessor) buf.mapState).getDecorations().clear();
            System.out.println(TAG + " Mux-decoded receiver " + key
                    + " (map id=" + buf.mapId.id() + ", " + data.length + " bytes)");
        } catch (Exception e) {
            System.err.println(TAG + " Mux completion failed for " + key + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shared post-decompression logic used by both banner and carpet decode paths.
     * Handles v0 (raw colors) and v1+ (manifest + frame data) payloads, including
     * animation registration.
     *
     * @param frameEntity may be null for carpet-decoded maps (no animation registration)
     */
    private static void processDecompressedPayload(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            ItemFrameEntity frameEntity, byte[] full) {

        if (full.length == MAP_BYTES) {
            paintMap(client, mapId, mapState, full);
            return;
        }

        PayloadManifest manifest = PayloadManifest.fromBytes(full);
        int offset = manifest.headerSize;

        // AV1 payloads carry a compressed stream after the header, not raw frames — it can be
        // (much) shorter than one 16,384-byte frame, so the size floor only applies to raw tiles.
        if (!manifest.av1() && offset + MAP_BYTES > full.length) {
            throw new IllegalStateException("header_size=" + offset + " past payload end");
        }

        if (manifest.manifestVersion > PayloadManifest.CURRENT_VERSION) {
            System.out.println(TAG + " Unknown manifest version " + manifest.manifestVersion
                    + " — rendering frame 0 without metadata");
        } else {
            System.out.println(TAG + " Manifest v" + manifest.manifestVersion
                    + ": grid=" + manifest.cols + "x" + manifest.rows
                    + " tile=(" + manifest.tileCol + "," + manifest.tileRow + ")"
                    + (manifest.username != null ? " by=" + manifest.username : "")
                    + (manifest.title    != null ? " title=\"" + manifest.title + "\"" : "")
                    + (manifest.animated() ? " frames=" + manifest.frameCount : ""));
            // Store title/author for in-game display.
            if (manifest.title != null || manifest.username != null) {
                mapMeta.put(mapId.id(), new MapMeta(manifest.title, manifest.username,
                        manifest.cols, manifest.rows, manifest.tileCol, manifest.tileRow, false));
            }
        }

        // The bytes at `offset` are frame 0 only for raw payloads; AV1 payloads store a
        // compressed stream there, so the manifest CRC can't be checked before decoding.
        if (manifest.colorCrc32 >= 0 && !manifest.av1()) {
            byte[] frame0 = Arrays.copyOfRange(full, offset, offset + MAP_BYTES);
            long actualCrc = PayloadManifest.crc32(frame0);
            if (actualCrc != manifest.colorCrc32) {
                System.err.println(TAG + " CRC32 mismatch — expected "
                        + Long.toHexString(manifest.colorCrc32) + " got "
                        + Long.toHexString(actualCrc) + " (rendering anyway)");
            }
        }

        // Composite tiles carry only a segment of the composition-wide stream — route it to the
        // assembler; nothing can be painted until every sibling tile has been seen.
        if (manifest.av1Composite()) {
            handleCompositeTile(client, mapId, mapState, frameEntity, manifest, full);
            return;
        }

        if (manifest.animated() && manifest.frameCount > 1 && frameEntity != null) {
            if (manifest.av1()) {
                // AV1 decode is heavy — off the game thread. Raw/delta/sparse extraction below
                // is just array copying and stays synchronous.
                decodeAv1Async(client, mapId, mapState, manifest, full, frameEntity.getBlockPos());
                return;
            }
            byte[][] frames = extractFrames(full, manifest);
            if (frames.length < 2) {
                paintMap(client, mapId, mapState, frames[0]);
                return;
            }
            AnimatedMapState animState = new AnimatedMapState(
                    mapId, frames, manifest.frameDelays, manifest.loopCount,
                    frameEntity.getBlockPos(), manifest.cols, manifest.rows,
                    manifest.username, manifest.title);
            animatedMaps.put(mapId.id(), animState);
            markSyncGroupsDirty();
            paintMap(client, mapId, mapState, frames[0]);
        } else if (manifest.av1() && manifest.frameCount > 1) {
            // Held AV1 map (no item frame to animate in): the payload has no raw frame 0 —
            // decode the stream off-thread and paint the first frame statically.
            decodeAv1Async(client, mapId, mapState, manifest, full, null);
        } else {
            paintMap(client, mapId, mapState,
                    Arrays.copyOfRange(full, offset, offset + MAP_BYTES));
        }
    }

    // ── Per-tile AV1 decode (off the game thread) ─────────────────────────

    /** Map ids with an AV1 decode in flight on DECODE_EXECUTOR (game-thread access only). */
    private static final Set<Integer> pendingAv1Decodes = new HashSet<>();

    /**
     * Decodes a per-tile AV1 payload off the game thread, then (on the client thread) registers
     * the animation when {@code framePos != null} and paints frame 0.  A 60-frame lossy tile
     * takes several seconds even with the compiled decoder — synchronous decode froze the game.
     *
     * @param mapState may be null (e.g. animation re-registration where colors are already
     *                 painted); when null nothing is painted, the animation is only registered
     */
    private static void decodeAv1Async(MinecraftClient client, MapIdComponent mapId,
            MapState mapState, PayloadManifest manifest, byte[] full, BlockPos framePos) {
        if (!pendingAv1Decodes.add(mapId.id())) return; // decode already in flight
        // Hide the raw stream noise until frame 0 is ready (same as composite tiles).
        if (mapState != null) paintMap(client, mapId, mapState, new byte[MAP_BYTES]);
        DECODE_EXECUTOR.submit(() -> {
            // Catch Throwable: submit() swallows anything thrown into an unread Future.
            try {
                long t0 = System.nanoTime();
                byte[][] frames = extractFrames(full, manifest);
                long decodeMs = (System.nanoTime() - t0) / 1_000_000;
                client.execute(() -> {
                    pendingAv1Decodes.remove(mapId.id());
                    if (frames.length > 1 && framePos != null) {
                        AnimatedMapState animState = new AnimatedMapState(
                                mapId, frames, manifest.frameDelays, manifest.loopCount,
                                framePos, manifest.cols, manifest.rows,
                                manifest.username, manifest.title);
                        animatedMaps.put(mapId.id(), animState);
                        markSyncGroupsDirty();
                    }
                    if (mapState != null) paintMap(client, mapId, mapState, frames[0]);
                    System.out.println(TAG + " AV1 map " + mapId.id() + " decoded ("
                            + frames.length + " frames, " + decodeMs + " ms)");
                });
            } catch (Throwable e) {
                System.err.println(TAG + " AV1 decode failed for map " + mapId.id() + ": " + e);
                e.printStackTrace();
                client.execute(() -> pendingAv1Decodes.remove(mapId.id()));
            }
        });
    }

    // ── Composite (composition-wide lossy AV1) assembly ───────────────────

    private static String compositeKey(PayloadManifest m) {
        return m.cols + "x" + m.rows + ":" + m.frameCount + ":" + m.nonce
                + ":" + (m.username == null ? "" : m.username)
                + ":" + (m.title    == null ? "" : m.title);
    }

    /** Crop one tile's 128×128 window out of a (cols·128)-wide composition frame. */
    private static byte[][] cropCompositeTile(byte[][] frames, int tileCol, int tileRow, int cols) {
        int w = cols * 128;
        byte[][] out = new byte[frames.length][MAP_BYTES];
        for (int f = 0; f < frames.length; f++)
            for (int y = 0; y < 128; y++)
                System.arraycopy(frames[f], (tileRow * 128 + y) * w + tileCol * 128,
                        out[f], y * 128, 128);
        return out;
    }

    /**
     * Stores one composite tile's stream segment and paints the tile transparent while its
     * siblings are still missing.  When all {@code cols×rows} segments have been seen, the full
     * stream is decoded once (off-thread) at composition size, and every registered tile gets its
     * cropped frames painted + animated on the game thread.
     */
    private static void handleCompositeTile(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            ItemFrameEntity frameEntity, PayloadManifest manifest, byte[] full) {
        int cols = manifest.cols, rows = manifest.rows;
        if (cols < 1 || rows < 1 || manifest.tileCol >= cols || manifest.tileRow >= rows
                || manifest.frameCount < 2) {
            throw new IllegalStateException("Bad composite manifest: grid=" + cols + "x" + rows
                    + " tile=(" + manifest.tileCol + "," + manifest.tileRow + ")"
                    + " frames=" + manifest.frameCount);
        }
        // v5 stores the per-frame delay table as a prefix before the stream (same as extractFrames).
        int av1Offset = manifest.headerSize
                + (manifest.manifestVersion >= 5 ? manifest.frameCount * 2 : 0);
        if (av1Offset > full.length) {
            throw new IllegalStateException("Composite stream offset " + av1Offset
                    + " past payload end (" + full.length + " bytes)");
        }
        byte[] segment = Arrays.copyOfRange(full, av1Offset, full.length);

        String key = compositeKey(manifest);
        CompositeBuffer buf = compositeBuffers.computeIfAbsent(key, k -> new CompositeBuffer(manifest));
        int idx = manifest.tileRow * cols + manifest.tileCol;
        if (buf.segments[idx] == null) buf.received++;
        buf.segments[idx] = segment;
        buf.tiles[idx] = new CompositeTile(mapId, mapState,
                frameEntity != null ? frameEntity.getBlockPos() : null);

        // Hide the raw carpet/banner noise until the composition is complete.
        paintMap(client, mapId, mapState, new byte[MAP_BYTES]);

        int totalTiles = cols * rows;
        if (buf.received < totalTiles) {
            System.out.println(TAG + " Composite tile (" + manifest.tileCol + "," + manifest.tileRow
                    + ") map " + mapId.id() + " stored — " + buf.received + "/" + totalTiles
                    + " tiles seen for \"" + key + "\"");
            return;
        }
        if (buf.decoding) return;
        buf.decoding = true;

        int streamLen = 0;
        for (byte[] s : buf.segments) streamLen += s.length;
        byte[] stream = new byte[streamLen];
        int off = 0;
        for (byte[] s : buf.segments) {
            System.arraycopy(s, 0, stream, off, s.length);
            off += s.length;
        }
        int w = cols * 128, h = rows * 128;
        System.out.println(TAG + " Composite \"" + key + "\" complete — decoding "
                + streamLen + " bytes at " + w + "x" + h + " (" + buf.frameCount + " frames)");

        DECODE_EXECUTOR.submit(() -> {
            // Catch Throwable, not Exception: submit() swallows anything the task throws into an
            // unread Future, so an Error here would kill the decode with no log line at all.
            try {
                long t0 = System.nanoTime();
                byte[][] frames = Av1FrameDecoder.decode(stream, 0, buf.frameCount, true, w, h);
                long decodeMs = (System.nanoTime() - t0) / 1_000_000;
                client.execute(() -> {
                    compositeBuffers.remove(key);
                    for (int i = 0; i < buf.tiles.length; i++) {
                        CompositeTile tile = buf.tiles[i];
                        if (tile == null) continue;
                        byte[][] tileFrames = cropCompositeTile(frames, i % cols, i / cols, cols);
                        if (tileFrames.length > 1 && tile.framePos() != null) {
                            AnimatedMapState animState = new AnimatedMapState(
                                    tile.mapId(), tileFrames, buf.frameDelays, buf.loopCount,
                                    tile.framePos(), cols, rows, buf.username, buf.title);
                            animatedMaps.put(tile.mapId().id(), animState);
                        }
                        paintMap(client, tile.mapId(), tile.mapState(), tileFrames[0]);
                    }
                    markSyncGroupsDirty();
                    System.out.println(TAG + " Composite \"" + key + "\" decoded and painted ("
                            + frames.length + " frames × " + buf.tiles.length + " tiles, "
                            + decodeMs + " ms)");
                });
            } catch (Throwable e) {
                System.err.println(TAG + " Composite decode failed for \"" + key + "\": " + e);
                e.printStackTrace();
                client.execute(() -> buf.decoding = false);
            }
        });
    }

    // ── Animation tick ────────────────────────────────────────────────────

    public static void toggle(MinecraftClient client) {
        decodingEnabled = !decodingEnabled;
        if (client.world == null || client.player == null) return;
        for (int id : claimedMaps) {
            MapIdComponent mapId = new MapIdComponent(id);
            MapState ms = FilledMapItem.getMapState(mapId, client.world);
            if (ms == null) continue;
            byte[] target;
            if (!decodingEnabled) {
                target = rawColors.get(id);
            } else {
                AnimatedMapState anim = animatedMaps.get(id);
                target = anim != null ? anim.frames[anim.currentFrame] : decodedColors.get(id);
            }
            if (target != null) {
                System.arraycopy(target, 0, ms.colors, 0, target.length);
                client.getMapTextureManager().setNeedsUpdate(mapId, ms);
            }
        }
        String msg = decodingEnabled ? "§aLoominary decoding ON" : "§cLoominary decoding OFF (raw maps)";
        client.player.sendMessage(Text.literal(msg), true);
    }

    private static void advanceAnimatedFrames(MinecraftClient client) {
        if (!decodingEnabled) return;
        long currentMs = System.currentTimeMillis();
        Vec3d playerPos = client.player.getPos();

        // Rebuild the sync-group map only when animatedMaps has changed.
        if (animSyncGroupsDirty) {
            animSyncGroups.clear();
            for (Map.Entry<Integer, AnimatedMapState> e : animatedMaps.entrySet()) {
                animSyncGroups.computeIfAbsent(e.getValue().syncKey(), k -> new ArrayList<>())
                              .add(e.getKey());
            }
            animSyncGroupsDirty = false;
        }

        for (List<Integer> group : animSyncGroups.values()) {
            // Advance the group if any in-range member is due to advance.
            boolean anyDue = false;
            for (int id : group) {
                AnimatedMapState s = animatedMaps.get(id);
                if (!isInRange(playerPos, s)) continue;
                if (s.loopCount > 0 && s.loopsCompleted >= s.loopCount) continue;
                if (currentMs - s.lastAdvanceMs >= s.currentDelayMs()) { anyDue = true; break; }
            }
            if (!anyDue) continue;

            // Advance all maps in the group to the next frame simultaneously.
            for (int id : group) {
                AnimatedMapState s = animatedMaps.get(id);
                if (s.loopCount > 0 && s.loopsCompleted >= s.loopCount) continue;

                int nextFrame = (s.currentFrame + 1) % s.frames.length;
                if (nextFrame == 0 && s.loopCount > 0) s.loopsCompleted++;
                s.currentFrame   = nextFrame;
                s.lastAdvanceMs  = currentMs;

                if (!isInRange(playerPos, s)) continue;
                MapState mapState = FilledMapItem.getMapState(s.mapId, client.world);
                if (mapState != null) paintMap(client, s.mapId, mapState, s.frames[s.currentFrame]);
            }
        }
    }

    // Use an axis-aligned box check matching the scanner's Box.expand(SCAN_RADIUS) query
    // so tiles the scanner can decode are always within the animation range too.
    private static boolean isInRange(Vec3d playerPos, AnimatedMapState s) {
        Vec3d fp = Vec3d.ofCenter(s.framePos);
        return Math.abs(fp.x - playerPos.x) <= SCAN_RADIUS
            && Math.abs(fp.y - playerPos.y) <= SCAN_RADIUS
            && Math.abs(fp.z - playerPos.z) <= SCAN_RADIUS;
    }

    /**
     * Assembles a list of banner-name chunks (CJK or base64) into raw compressed bytes
     * without decompressing. Used by the export path to get plain bytes for re-encryption.
     */
    public static byte[] assembleCompressedFromChunks(List<String> names) {
        return assembleCompressed(names);
    }

    /**
     * Assembles banner-name chunks into compressed (or encrypted) bytes.
     * Does NOT decompress — the caller decides whether to decrypt first.
     */
    private static byte[] assembleCompressed(List<String> names) {
        names.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));

        boolean cjk = !names.isEmpty() && names.get(0).length() > 2
                && names.get(0).charAt(2) >= CjkCodec.ALPHA_BASE;

        if (cjk) {
            StringBuilder sb = new StringBuilder();
            for (String s : names) sb.append(s.substring(2));
            return CjkCodec.decode(sb.toString());
        } else {
            StringBuilder b64 = new StringBuilder();
            int expectedIndex = 0;
            for (String s : names) {
                int idx = Integer.parseInt(s.substring(0, 2), 16);
                if (idx != expectedIndex)
                    throw new IllegalStateException("Non-contiguous chunk index: expected "
                            + expectedIndex + " but got " + idx);
                expectedIndex++;
                b64.append(s.substring(2));
            }
            return Base64.getDecoder().decode(b64.toString());
        }
    }

    /**
     * Decompresses a zstd-compressed payload, validating minimum size.
     * Must not be called on encrypted bytes — decrypt first.
     */
    private static byte[] decompressPayload(byte[] compressed) {
        long originalSize = Zstd.getFrameContentSize(compressed);
        if (originalSize < 0)
            throw new IllegalStateException("Missing zstd frame content size — corrupt payload?");
        if (originalSize < MAP_BYTES)
            throw new IllegalStateException("Decompressed size " + originalSize
                    + " is smaller than MAP_BYTES — corrupt payload?");
        return Zstd.decompress(compressed, (int) originalSize);
    }

    /**
     * Assembles banner-name chunks into a compressed payload and decompresses it.
     * Returns the full decompressed bytes: for v0 payloads this is 16,384 bytes of
     * raw map colors; for v1+ payloads this is the manifest header followed by one
     * or more 16,384-byte frame arrays.
     *
     * Callers that need async decryption should use {@link #assembleCompressed} directly.
     */
    private static byte[] assembleAndDecompress(List<String> names) {
        byte[] compressed = assembleCompressed(names);
        if (MapEncryption.isEncrypted(compressed)) {
            byte[] decrypted = MapEncryption.tryDecrypt(compressed, -1);
            if (decrypted == null)
                throw new IllegalStateException(
                        "Encrypted payload — add a password with /loominary password add <pw>");
            compressed = decrypted;
        }
        return decompressPayload(compressed);
    }

    public static byte[] reassemblePayload(List<String> names) {
        byte[] full = assembleAndDecompress(names);

        if (full.length == MAP_BYTES) {
            // v0 payload (Loominary 1.0.0) — no manifest, map colors only
            return full;
        }

        // v1+ payload — manifest prefix precedes map-color bytes
        PayloadManifest manifest = PayloadManifest.fromBytes(full);

        int offset = manifest.headerSize;
        if (offset + MAP_BYTES > full.length) {
            throw new IllegalStateException(
                    "Manifest header_size=" + offset + " would read past payload end (" + full.length + " bytes)");
        }

        if (manifest.manifestVersion > PayloadManifest.CURRENT_VERSION) {
            System.out.println(TAG + " Unknown manifest version " + manifest.manifestVersion
                    + " — rendering image without metadata");
        } else {
            System.out.println(TAG + " Manifest: grid=" + manifest.cols + "x" + manifest.rows
                    + " tile=(" + manifest.tileCol + "," + manifest.tileRow + ")"
                    + (manifest.username != null ? " by=" + manifest.username : "")
                    + (manifest.title    != null ? " title=\"" + manifest.title + "\"" : ""));
        }

        byte[] mapColors = Arrays.copyOfRange(full, offset, offset + MAP_BYTES);

        if (manifest.colorCrc32 >= 0) {
            long actualCrc = PayloadManifest.crc32(mapColors);
            if (actualCrc != manifest.colorCrc32) {
                System.err.println(TAG + " CRC32 mismatch — expected "
                        + Long.toHexString(manifest.colorCrc32) + " got "
                        + Long.toHexString(actualCrc) + " (rendering anyway)");
            }
        }

        return mapColors;
    }

    public static void paintMap(MinecraftClient client,
            MapIdComponent mapId,
            MapState mapState,
            byte[] mapColors) {
        rawColors.computeIfAbsent(mapId.id(), k -> mapState.colors.clone());
        decodedColors.put(mapId.id(), mapColors.clone());
        if (!decodingEnabled) return;
        // Unlock before setNeedsUpdate so MapMipMapMod doesn't see a locked map at
        // dirty-mark time and freeze the texture; MapRendererMixin handles re-unlock
        // if the server later sends a re-lock packet.
        if (mapState.locked) ((MapStateAccessor) mapState).setLocked(false);
        System.arraycopy(mapColors, 0, mapState.colors, 0, mapColors.length);
        client.getMapTextureManager().setNeedsUpdate(mapId, mapState);
        int painted = paintLogCount.getOrDefault(mapId.id(), 0);
        if (painted < PAINT_LOG_LIMIT) {
            paintLogCount.put(mapId.id(), painted + 1);
            System.out.println(TAG + " paintMap #" + (painted + 1) + " mapId=" + mapId.id()
                    + " locked=" + mapState.locked
                    + " bytes=" + mapColors.length
                    + " checksum=" + Integer.toHexString(colorsChecksum(mapColors))
                    + " mapStateChecksum=" + Integer.toHexString(colorsChecksum(mapState.colors)));
        }
    }

    /** Cheap rolling hash over map colors; only used for diagnostic logs. */
    private static int colorsChecksum(byte[] b) {
        int s = 0;
        for (byte x : b) s = s * 31 + (x & 0xff);
        return s;
    }

    /**
     * Called from MapRendererMixin HEAD inject for every MapRenderer.update call.
     * Logs cheaply: one line per (mapId) per session, plus one line every time we
     * actually flip locked → unlocked.
     */
    public static void onRenderUpdateHead(int mapId, boolean wasLocked, boolean unlocked, byte[] currentColors) {
        if (renderUpdateSeen.add(mapId)) {
            byte[] expected = decodedColors.get(mapId);
            String expectedChk = expected != null
                    ? Integer.toHexString(colorsChecksum(expected)) : "(none)";
            String currentChk = Integer.toHexString(colorsChecksum(currentColors));
            System.out.println(TAG + " MapRenderer.update first-call mapId=" + mapId
                    + " claimed=" + claimedMaps.contains(mapId)
                    + " wasLocked=" + wasLocked
                    + " unlocked=" + unlocked
                    + " currentChecksum=" + currentChk
                    + " expectedChecksum=" + expectedChk
                    + " match=" + currentChk.equals(expectedChk));
        }
        if (unlocked && unlockLogged.add(mapId)) {
            System.out.println(TAG + " MapRendererMixin unlocked mapId=" + mapId + " (first)");
        }
    }

    /**
     * Decodes chunks and registers an {@link AnimatedMapState} for the given map so
     * it animates even though it was painted via {@code /loominary preview} rather
     * than through the normal banner-marker decode path.
     *
     * Does nothing for static (single-frame) payloads. Adds the map to
     * {@code claimedMaps} so the scanner does not re-decode it and reset the clock.
     */
    public static void registerAnimatedFromChunks(MinecraftClient client,
            MapIdComponent mapId, BlockPos framePos, List<String> chunks) {
        if (client.world == null) return;
        try {
            byte[] full = assembleAndDecompress(new ArrayList<>(chunks));
            registerAnimatedFromDecompressed(client, mapId, framePos, full);
        } catch (Exception e) {
            System.err.println(TAG + " registerAnimatedFromChunks failed for map "
                    + mapId.id() + ": " + e.getMessage());
        }
    }

    /**
     * Registers animation for a map from an already-decompressed payload.
     * Works for both banner and carpet tiles. Does nothing for static payloads.
     */
    public static void registerAnimatedFromDecompressed(MinecraftClient client,
            MapIdComponent mapId, BlockPos framePos, byte[] full) {
        if (client.world == null) return;
        try {
            if (full.length == MAP_BYTES) return; // v0 static — nothing to animate

            PayloadManifest manifest = PayloadManifest.fromBytes(full);
            if (!manifest.animated() || manifest.frameCount <= 1) return;
            if (manifest.av1Composite()) {
                // A lone composite segment can't be decoded — animation comes from the
                // assembler once every sibling tile has been scanned.
                System.out.println(TAG + " Composite tile map " + mapId.id()
                        + " — animation registers when the whole composition is assembled");
                return;
            }

            if (manifest.av1()) {
                // Heavy decode — off the game thread; claim now so the scanner doesn't reset
                // the clock while the decode is in flight. Colors are already painted here,
                // so only the animation registers (null mapState = no paint).
                claimedMaps.add(mapId.id());
                decodeAv1Async(client, mapId, null, manifest, full, framePos);
                return;
            }

            byte[][] frames = extractFrames(full, manifest);
            if (frames.length < 2) return;

            AnimatedMapState animState = new AnimatedMapState(
                    mapId, frames, manifest.frameDelays, manifest.loopCount,
                    framePos, manifest.cols, manifest.rows,
                    manifest.username, manifest.title);
            animatedMaps.put(mapId.id(), animState);
            markSyncGroupsDirty();
            claimedMaps.add(mapId.id()); // prevent scanner reset
        } catch (Exception e) {
            System.err.println(TAG + " registerAnimatedFromDecompressed failed for map "
                    + mapId.id() + ": " + e.getMessage());
        }
    }

    /**
     * Removes the animated state for a map (called by {@code /loominary revert}).
     * The map stays in {@code claimedMaps} so the scanner does not re-register
     * animation after the colors are restored to the original.
     */
    public static void unregisterAnimated(int mapId) {
        animatedMaps.remove(mapId);
        markSyncGroupsDirty();
    }

    public static boolean isClaimed(int mapId) {
        return claimedMaps.contains(mapId);
    }

    /**
     * Reconstructs absolute frames in-place from XOR-delta storage.
     * Frame 0 is always raw; each subsequent frame is XOR'd with its predecessor.
     */
    public static void reconstructDeltaFrames(byte[][] frames) {
        for (int f = 1; f < frames.length; f++) {
            byte[] prev = frames[f - 1];
            byte[] cur  = frames[f];
            for (int p = 0; p < cur.length; p++)
                cur[p] = (byte)(prev[p] ^ cur[p]);
        }
    }

    /**
     * Extracts all animation frames from a decompressed payload, handling fixed-size,
     * delta-encoded, and sparse formats.  Always returns raw (absolute) frame data.
     *
     * <p>Use this in preference to manual frame extraction anywhere frames need to be
     * decoded for display, editing, or re-encoding.
     */
    public static byte[][] extractFrames(byte[] full, PayloadManifest manifest) {
        int fc     = Math.max(1, manifest.frameCount);
        int offset = manifest.headerSize;

        if (manifest.av1Composite()) {
            throw new IllegalStateException("Composite lossy tile — its stream segment cannot be"
                    + " decoded alone; all " + manifest.cols + "×" + manifest.rows
                    + " tiles of the composition are required");
        }

        if (manifest.av1() && fc > 1) {
            // v5 stores a frameCount×u16 per-frame delay table as a prefix before the AV1 stream.
            int av1Offset = offset + (manifest.manifestVersion >= 5 ? fc * 2 : 0);
            return Av1FrameDecoder.decode(full, av1Offset, fc, manifest.av1Lossy());
        }

        byte[][] frames = new byte[fc][MAP_BYTES];

        if (manifest.sparseFrames() && fc > 1) {
            // Frame 0: raw.
            System.arraycopy(full, offset, frames[0], 0, MAP_BYTES);
            int off = offset + MAP_BYTES;
            for (int f = 1; f < fc; f++) {
                frames[f] = frames[f - 1].clone();
                if (off + 2 > full.length) break;
                int cc = ((full[off] & 0xFF) << 8) | (full[off + 1] & 0xFF);
                off += 2;
                for (int c = 0; c < cc && off + 3 <= full.length; c++, off += 3) {
                    int pos = ((full[off] & 0xFF) << 8) | (full[off + 1] & 0xFF);
                    frames[f][pos] = full[off + 2];
                }
            }
        } else {
            // Fixed-size frames (raw or delta).
            for (int f = 0; f < fc; f++) {
                int start = offset + f * MAP_BYTES;
                if (start + MAP_BYTES > full.length) {
                    frames = Arrays.copyOf(frames, Math.max(1, f));
                    break;
                }
                System.arraycopy(full, start, frames[f], 0, MAP_BYTES);
            }
            if (manifest.deltaFrames() && frames.length > 1)
                reconstructDeltaFrames(frames);
        }
        return frames;
    }

    // ── In-game metadata display ──────────────────────────────────────────

    /**
     * Shows the title and author of a nearby Loominary map in the action bar.
     * Checks the crosshair-targeted item frame first, then held items.
     */
    private static void showMapMetaActionBar(MinecraftClient client) {
        Integer mapId = resolveMetaMapId(client);
        if (mapId == null) return;
        MapMeta meta = mapMeta.get(mapId);
        if (meta == null) return;
        String text = buildMetaText(meta);
        if (text != null) client.player.sendMessage(Text.literal(text), true);
    }

    private static Integer resolveMetaMapId(MinecraftClient client) {
        // Show metadata for any map that has it — claimed (decoded) or not (encrypted,
        // metadata readable from plaintext envelope header without the password).
        // Prefer crosshair-targeted frame.
        if (client.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult hit
                && hit.getEntity() instanceof ItemFrameEntity frame) {
            ItemStack stack = frame.getHeldItemStack();
            if (stack.getItem() instanceof FilledMapItem) {
                MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
                if (id != null && mapMeta.containsKey(id.id())) return id.id();
            }
        }
        // Fall back to held items.
        for (ItemStack stack : new ItemStack[]{
                client.player.getMainHandStack(), client.player.getOffHandStack()}) {
            if (stack.getItem() instanceof FilledMapItem) {
                MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
                if (id != null && mapMeta.containsKey(id.id())) return id.id();
            }
        }
        return null;
    }

    private static String buildMetaText(MapMeta meta) {
        if (meta.title() == null && meta.author() == null) return null;
        StringBuilder sb = new StringBuilder();
        if (meta.title() != null) {
            sb.append("§e\"").append(meta.title()).append("\"");
        }
        if (meta.author() != null) {
            if (meta.title() != null) sb.append(" §7by §e");
            else sb.append("§7by §e");
            sb.append(meta.author());
        }
        if (meta.cols() > 1 || meta.rows() > 1) {
            sb.append(String.format(" §8(%d,%d of %d×%d)",
                    meta.tileCol() + 1, meta.tileRow() + 1, meta.cols(), meta.rows()));
        }
        if (meta.encrypted()) sb.append(" §8[enc]");
        return sb.toString();
    }

    /**
     * Submits an async task to decrypt {@code encrypted} with stored passwords, then
     * processes the decompressed payload on the game thread.
     * Claims the map immediately to prevent concurrent re-scan attempts.
     *
     * @param decorations the map's decoration map to clear on success; may be null
     */
    private static void submitDecryptAndProcess(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            ItemFrameEntity frame, byte[] encrypted,
            Map<String, MapDecoration> decorations) {
        int id = mapId.id();
        if (pendingDecrypts.contains(id)) return;

        // Read plaintext metadata from the envelope header immediately so the
        // action bar can show author/title even before decryption completes.
        String[] meta = MapEncryption.readMeta(encrypted);
        if (meta != null) {
            mapMeta.put(id, new MapMeta(meta[1], meta[0], 1, 1, 0, 0, true));
        }

        if (MapEncryption.passwords.isEmpty()) {
            if (encryptedNoPass.add(id))
                System.out.println(TAG + " Encrypted map id=" + id
                        + " — add a password with /loominary password add <pw>");
            return;
        }

        pendingDecrypts.add(id);
        claimedMaps.add(id); // prevent re-scan while decrypt is in progress

        DECRYPT_EXECUTOR.submit(() -> {
            try {
                byte[] decrypted = MapEncryption.tryDecrypt(encrypted, id);
                if (decrypted == null) {
                    System.out.println(TAG + " Encrypted map id=" + id
                            + " — no matching password");
                    // Un-claim so a future password add can trigger another attempt.
                    client.execute(() -> {
                        claimedMaps.remove(id);
                        pendingDecrypts.remove(id);
                    });
                    return;
                }
                long sz = Zstd.getFrameContentSize(decrypted);
                if (sz < 0) throw new IllegalStateException("Invalid zstd frame after decrypt");
                byte[] full = Zstd.decompress(decrypted, (int) sz);

                client.execute(() -> {
                    try {
                        processDecompressedPayload(client, mapId, mapState, frame, full);
                        if (decorations != null) decorations.clear();
                        System.out.println(TAG + " Decrypted and claimed map id=" + id);
                    } catch (Exception e) {
                        System.err.println(TAG + " Post-decrypt process error for map " + id
                                + ": " + e.getMessage());
                        claimedMaps.remove(id);
                    } finally {
                        pendingDecrypts.remove(id);
                    }
                });
            } catch (Exception e) {
                System.err.println(TAG + " Decrypt error for map " + id + ": " + e.getMessage());
                client.execute(() -> {
                    claimedMaps.remove(id);
                    pendingDecrypts.remove(id);
                });
            }
        });
    }

    public static void clearCache() {
        claimedMaps.clear();
        animatedMaps.clear();
        animSyncGroups.clear();
        animSyncGroupsDirty = false;
        mapMeta.clear();
        muxBuffers.clear();
        muxPendingOffsets.clear();
        muxPendingBytes.clear();
        compositeBuffers.clear();
        pendingAv1Decodes.clear();
        rawColors.clear();
        decodedColors.clear();
        renderUpdateSeen.clear();
        scanSeen.clear();
        paintLogCount.clear();
        unlockLogged.clear();
        loomHeaderSeen.clear();
        pendingDecrypts.clear();
        encryptedNoPass.clear();
        MapEncryption.clearCache();
    }
}