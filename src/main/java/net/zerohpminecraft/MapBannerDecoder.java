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

public class MapBannerDecoder {

    private static final String TAG = "[BannerMod]";
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final double SCAN_RADIUS = 32.0;
    private static final int MAP_BYTES = 128 * 128;

    private static final Set<Integer> claimedMaps = new HashSet<>();
    private static final Map<Integer, AnimatedMapState> animatedMaps = new HashMap<>();
    private static int tickCounter = 0;

    /** Map IDs we've already logged a first-render-update for, to keep mixin logs cheap. */
    private static final Set<Integer> renderUpdateSeen = new HashSet<>();
    /** Map IDs we've already logged a first-scan for, to keep scanner logs cheap. */
    private static final Set<Integer> scanSeen = new HashSet<>();
    /** Per-mapId paint log counter — log first {@link #PAINT_LOG_LIMIT} paints per session. */
    private static final Map<Integer, Integer> paintLogCount = new HashMap<>();
    private static final int PAINT_LOG_LIMIT = 3;
    /** Map IDs we've already logged a first-unlock for. */
    private static final Set<Integer> unlockLogged = new HashSet<>();

    public static boolean decodingEnabled = true;
    private static final Map<Integer, byte[]> rawColors     = new HashMap<>();
    private static final Map<Integer, byte[]> decodedColors = new HashMap<>();

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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null)
                return;

            // Animation tick runs every tick for smooth frame transitions.
            if (!animatedMaps.isEmpty()) {
                advanceAnimatedFrames(client);
            }

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

        if (decorations.isEmpty())
            return;

        // Check for mux receiver (LR) banner, then carpet LC/LS manifest banner.
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            if (s.length() >= 18 && s.startsWith("LR")) {
                processReceiverCarpetFrame(client, mapId, mapState, s, decorations, frame);
                return;
            }
            boolean isLC = s.length() >= 6  && s.startsWith("LC");
            boolean isLS = s.length() >= 10 && s.startsWith("LS");
            if (isLC || isLS) {
                processCarpetFrame(client, mapId, mapState, s, decorations, frame);
                return;
            }
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
            byte[] full = assembleAndDecompress(names);
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

                // CJK overflow: detect old (2-char index) vs new (4-char index) format.
                // Base64 overflow (legacy): all ASCII payloads, 2-char index.
                if (!overflowNames.isEmpty()) {
                    String ofirst = overflowNames.get(0);
                    boolean oldCjk = ofirst.length() > 2 && ofirst.charAt(2) >= CjkCodec.ALPHA_BASE;
                    boolean newCjk = !oldCjk && ofirst.length() > 4 && ofirst.charAt(4) >= CjkCodec.ALPHA_BASE;
                    if (oldCjk || newCjk) {
                        overflowData = CjkCodec.assembleChunks(overflowNames);
                    } else {
                        overflowNames.sort(Comparator.comparingInt(
                                s -> Integer.parseInt(s.substring(0, 2), 16)));
                        StringBuilder b64 = new StringBuilder(lcPayload);
                        for (String s : overflowNames) b64.append(s.substring(2));
                        overflowData = Base64.getDecoder().decode(b64.toString());
                    }
                }
            }

            byte[] compressed = new byte[carpetBytes + shadeData.length + overflowData.length];
            System.arraycopy(carpetData,  0, compressed, 0,                              carpetBytes);
            System.arraycopy(shadeData,   0, compressed, carpetBytes,                    shadeData.length);
            System.arraycopy(overflowData, 0, compressed, carpetBytes + shadeData.length, overflowData.length);

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
                if (!ovfNames.isEmpty()) {
                    String ofirst = ovfNames.get(0);
                    boolean oldCjk = ofirst.length() > 2 && ofirst.charAt(2) >= CjkCodec.ALPHA_BASE;
                    boolean newCjk = !oldCjk && ofirst.length() > 4 && ofirst.charAt(4) >= CjkCodec.ALPHA_BASE;
                    if (oldCjk || newCjk) {
                        overflowData = CjkCodec.assembleChunks(ovfNames);
                    } else {
                        ovfNames.sort(java.util.Comparator.comparingInt(
                                s -> Integer.parseInt(s.substring(0, 2), 16)));
                        StringBuilder b64 = new StringBuilder();
                        for (String s : ovfNames) b64.append(s.substring(2));
                        overflowData = Base64.getDecoder().decode(b64.toString());
                    }
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
            long frameSize = Zstd.getFrameContentSize(buf.data);
            if (frameSize < 0) {
                System.err.println(TAG + " Mux completion: bad zstd frame for receiver " + key);
                return;
            }
            byte[] full = Zstd.decompress(buf.data, (int) frameSize);
            processDecompressedPayload(client, buf.mapId, buf.mapState, buf.frame, full);
            if (buf.mapState != null) ((MapStateAccessor) buf.mapState).getDecorations().clear();
            System.out.println(TAG + " Mux-decoded receiver " + key
                    + " (map id=" + buf.mapId.id() + ", " + buf.data.length + " bytes)");
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

        if (offset + MAP_BYTES > full.length) {
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
        }

        if (manifest.colorCrc32 >= 0) {
            byte[] frame0 = Arrays.copyOfRange(full, offset, offset + MAP_BYTES);
            long actualCrc = PayloadManifest.crc32(frame0);
            if (actualCrc != manifest.colorCrc32) {
                System.err.println(TAG + " CRC32 mismatch — expected "
                        + Long.toHexString(manifest.colorCrc32) + " got "
                        + Long.toHexString(actualCrc) + " (rendering anyway)");
            }
        }

        if (manifest.animated() && manifest.frameCount > 1 && frameEntity != null) {
            int frameCount = manifest.frameCount;
            byte[][] frames = new byte[frameCount][MAP_BYTES];
            for (int f = 0; f < frameCount; f++) {
                int start = offset + f * MAP_BYTES;
                if (start + MAP_BYTES > full.length) {
                    System.err.println(TAG + " Truncated animated payload: only "
                            + f + "/" + frameCount + " frames present");
                    frameCount = f;
                    frames = Arrays.copyOf(frames, frameCount);
                    break;
                }
                System.arraycopy(full, start, frames[f], 0, MAP_BYTES);
            }
            AnimatedMapState animState = new AnimatedMapState(
                    mapId, frames, manifest.frameDelays, manifest.loopCount,
                    frameEntity.getBlockPos(), manifest.cols, manifest.rows,
                    manifest.username, manifest.title);
            animatedMaps.put(mapId.id(), animState);
            paintMap(client, mapId, mapState, frames[0]);
        } else {
            paintMap(client, mapId, mapState,
                    Arrays.copyOfRange(full, offset, offset + MAP_BYTES));
        }
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

        // Group maps by sync key so sibling tiles in a mural advance together.
        Map<String, List<Integer>> groups = new HashMap<>();
        for (Map.Entry<Integer, AnimatedMapState> e : animatedMaps.entrySet()) {
            groups.computeIfAbsent(e.getValue().syncKey(), k -> new ArrayList<>())
                  .add(e.getKey());
        }

        for (List<Integer> group : groups.values()) {
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
     * Assembles banner-name chunks into a compressed payload and decompresses it.
     * Returns the full decompressed bytes: for v0 payloads this is 16,384 bytes of
     * raw map colors; for v1+ payloads this is the manifest header followed by one
     * or more 16,384-byte frame arrays.
     */
    private static byte[] assembleAndDecompress(List<String> names) {
        // Detect format from first banner:
        //   Old CJK  (2-char index, 2-byte header): charAt(2) ≥ U+4E00
        //   New CJK  (4-char index, 4-byte header): charAt(4) ≥ U+4E00
        //   Old base64 (2-char index, no header)  : everything else (all ASCII)
        String first = names.get(0);
        final int prefix;
        final boolean cjk;
        final int headerBytes;
        if (first.length() > 2 && first.charAt(2) >= CjkCodec.ALPHA_BASE) {
            prefix = 2; cjk = true; headerBytes = 2;   // old CJK
        } else if (first.length() > 4 && first.charAt(4) >= CjkCodec.ALPHA_BASE) {
            prefix = 4; cjk = true; headerBytes = 4;   // new CJK
        } else {
            prefix = 2; cjk = false; headerBytes = 0;  // old base64
        }

        names.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, prefix), 16)));

        byte[] compressed;
        if (cjk) {
            StringBuilder sb = new StringBuilder();
            for (String s : names) sb.append(s.substring(prefix));
            compressed = CjkCodec.decode(sb.toString(), headerBytes);
        } else {
            StringBuilder b64 = new StringBuilder();
            int expectedIndex = 0;
            for (String s : names) {
                int idx = Integer.parseInt(s.substring(0, prefix), 16);
                if (idx != expectedIndex)
                    throw new IllegalStateException("Non-contiguous chunk index: expected "
                            + expectedIndex + " but got " + idx);
                expectedIndex++;
                b64.append(s.substring(prefix));
            }
            compressed = Base64.getDecoder().decode(b64.toString());
        }

        long originalSize = Zstd.getFrameContentSize(compressed);
        if (originalSize < 0)
            throw new IllegalStateException("Missing zstd frame content size — corrupt payload?");
        if (originalSize < MAP_BYTES)
            throw new IllegalStateException("Decompressed size " + originalSize
                    + " is smaller than MAP_BYTES — corrupt payload?");
        return Zstd.decompress(compressed, (int) originalSize);
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

            int fc = manifest.frameCount;
            int offset = manifest.headerSize;
            byte[][] frames = new byte[fc][MAP_BYTES];
            for (int f = 0; f < fc; f++) {
                int start = offset + f * MAP_BYTES;
                if (start + MAP_BYTES > full.length) {
                    fc = f;
                    frames = Arrays.copyOf(frames, f);
                    break;
                }
                System.arraycopy(full, start, frames[f], 0, MAP_BYTES);
            }
            if (fc == 0) return;

            AnimatedMapState animState = new AnimatedMapState(
                    mapId, frames, manifest.frameDelays, manifest.loopCount,
                    framePos, manifest.cols, manifest.rows,
                    manifest.username, manifest.title);
            animatedMaps.put(mapId.id(), animState);
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
    }

    public static boolean isClaimed(int mapId) {
        return claimedMaps.contains(mapId);
    }

    public static void clearCache() {
        claimedMaps.clear();
        animatedMaps.clear();
        muxBuffers.clear();
        muxPendingOffsets.clear();
        muxPendingBytes.clear();
        rawColors.clear();
        decodedColors.clear();
        renderUpdateSeen.clear();
        scanSeen.clear();
        paintLogCount.clear();
        unlockLogged.clear();
    }
}