package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

    // ── Animated map state ────────────────────────────────────────────────

    private static final class AnimatedMapState {
        final MapIdComponent mapId;
        final byte[][] frames;
        /** Length 1 = global delay; length N = per-frame delay table. */
        final int[] delaysMs;
        final int loopCount;
        final BlockPos framePos;
        /** Manifest grid dimensions, used to group sibling tiles for sync. */
        final int manifestCols;
        final int manifestRows;
        final String syncUsername;
        final String syncTitle;
        int  currentFrame;
        long lastAdvanceTick;
        int  loopsCompleted;

        AnimatedMapState(MapIdComponent mapId, byte[][] frames, int[] delaysMs, int loopCount,
                         BlockPos framePos, int manifestCols, int manifestRows,
                         String syncUsername, String syncTitle, long startTick) {
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
            this.lastAdvanceTick = startTick;
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

        if (claimedMaps.contains(mapId.id())) {
            if (!decorations.isEmpty()) {
                decorations.clear();
            }
            return;
        }

        if (decorations.isEmpty())
            return;

        // Check for carpet-hybrid LC manifest banner first.
        for (MapDecoration dec : decorations.values()) {
            Text text = dec.name().orElse(null);
            if (text == null) continue;
            String s = text.getString();
            if (s.length() >= 6 && s.startsWith("LC")) {
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
     * Overflow bytes (when {@code N > 8192}) are carried by the LC manifest banner
     * payload (first 44 base64 chars) and hex-indexed banners {@code 00}–{@code 3D}.
     *
     * @param lcName name of the LC manifest banner: {@code LC<4-hex-N>[<b64-overflow>]}
     */
    private static void processCarpetFrame(MinecraftClient client,
            MapIdComponent mapId, MapState mapState,
            String lcName, Map<String, MapDecoration> decorations,
            ItemFrameEntity frame) {
        try {
            int totalBytes = Integer.parseInt(lcName.substring(2, 6), 16);
            String lcPayload = lcName.length() > 6 ? lcName.substring(6) : "";

            int carpetBytes = Math.min(totalBytes, CarpetChannel.MAX_CARPET_BYTES);
            byte[] carpetData = CarpetChannel.decodeBytes(mapState.colors, carpetBytes);

            byte[] overflowData = new byte[0];
            if (totalBytes > CarpetChannel.MAX_CARPET_BYTES) {
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

                StringBuilder b64 = new StringBuilder(lcPayload);
                for (String s : overflowNames) b64.append(s.substring(2));
                overflowData = Base64.getDecoder().decode(b64.toString());
            }

            byte[] compressed = new byte[carpetBytes + overflowData.length];
            System.arraycopy(carpetData,   0, compressed, 0,           carpetBytes);
            System.arraycopy(overflowData, 0, compressed, carpetBytes, overflowData.length);

            long frameSize = Zstd.getFrameContentSize(compressed);
            if (frameSize < 0) throw new IllegalStateException("Missing zstd frame size");
            byte[] full = Zstd.decompress(compressed, (int) frameSize);

            System.out.println(TAG + " Carpet-decoded map id=" + mapId.id()
                    + " (" + totalBytes + " compressed bytes, "
                    + carpetBytes + " from carpet channel)");

            processDecompressedPayload(client, mapId, mapState, frame, full);

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
                    manifest.username, manifest.title, client.world.getTime());
            animatedMaps.put(mapId.id(), animState);
            paintMap(client, mapId, mapState, frames[0]);
        } else {
            paintMap(client, mapId, mapState,
                    Arrays.copyOfRange(full, offset, offset + MAP_BYTES));
        }
    }

    // ── Animation tick ────────────────────────────────────────────────────

    private static void advanceAnimatedFrames(MinecraftClient client) {
        long currentTick = client.world.getTime();
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
                long delayTicks = Math.max(1, Math.round(s.currentDelayMs() / 50.0));
                if (currentTick - s.lastAdvanceTick >= delayTicks) { anyDue = true; break; }
            }
            if (!anyDue) continue;

            // Advance all maps in the group to the next frame simultaneously.
            for (int id : group) {
                AnimatedMapState s = animatedMaps.get(id);
                if (s.loopCount > 0 && s.loopsCompleted >= s.loopCount) continue;

                int nextFrame = (s.currentFrame + 1) % s.frames.length;
                if (nextFrame == 0 && s.loopCount > 0) s.loopsCompleted++;
                s.currentFrame    = nextFrame;
                s.lastAdvanceTick = currentTick;

                if (!isInRange(playerPos, s)) continue;
                MapState mapState = FilledMapItem.getMapState(s.mapId, client.world);
                if (mapState != null) paintMap(client, s.mapId, mapState, s.frames[s.currentFrame]);
            }
        }
    }

    private static boolean isInRange(Vec3d playerPos, AnimatedMapState s) {
        return playerPos.squaredDistanceTo(Vec3d.ofCenter(s.framePos))
                <= SCAN_RADIUS * SCAN_RADIUS;
    }

    /**
     * Assembles banner-name chunks into a compressed payload and decompresses it.
     * Returns the full decompressed bytes: for v0 payloads this is 16,384 bytes of
     * raw map colors; for v1+ payloads this is the manifest header followed by one
     * or more 16,384-byte frame arrays.
     */
    private static byte[] assembleAndDecompress(List<String> names) {
        names.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, 2), 16)));

        StringBuilder b64 = new StringBuilder();
        int expectedIndex = 0;
        for (String s : names) {
            int idx = Integer.parseInt(s.substring(0, 2), 16);
            if (idx != expectedIndex) {
                throw new IllegalStateException("Non-contiguous chunk index: expected "
                        + expectedIndex + " but got " + idx);
            }
            expectedIndex++;
            b64.append(s.substring(2));
        }

        byte[] compressed = Base64.getDecoder().decode(b64.toString());
        long originalSize = Zstd.getFrameContentSize(compressed);
        if (originalSize < 0) {
            throw new IllegalStateException("Missing zstd frame content size — corrupt payload?");
        }
        if (originalSize < MAP_BYTES) {
            throw new IllegalStateException("Decompressed size " + originalSize
                    + " is smaller than MAP_BYTES — corrupt payload?");
        }
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
        System.arraycopy(mapColors, 0, mapState.colors, 0, mapColors.length);
        client.getMapTextureManager().setNeedsUpdate(mapId, mapState);
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
                    manifest.username, manifest.title,
                    client.world.getTime());
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
    }
}