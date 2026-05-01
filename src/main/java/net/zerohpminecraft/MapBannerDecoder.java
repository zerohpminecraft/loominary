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
import net.minecraft.util.math.Box;
import net.zerohpminecraft.mixin.MapStateAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
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
    private static int tickCounter = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null)
                return;

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

        // Filter for our banner markers — skip any other decorations
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
            byte[] mapColors = reassemblePayload(names);
            paintMap(client, mapId, mapState, mapColors);

            claimedMaps.add(mapId.id());
            decorations.clear();

            System.out.println(TAG + " Successfully painted image onto map " + mapId.id()
                    + " — markers suppressed");
        } catch (Exception e) {
            System.err.println(TAG + " Reconstruction failed for map " + mapId.id()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static byte[] reassemblePayload(List<String> names) {
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

        if (originalSize == MAP_BYTES) {
            // v0 payload (Loominary 1.0.0) — no manifest, decode directly
            return Zstd.decompress(compressed, MAP_BYTES);
        } else if (originalSize > MAP_BYTES) {
            // v1+ payload — manifest prefix precedes map-color bytes
            byte[] full = Zstd.decompress(compressed, (int) originalSize);
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
                    System.err.println(TAG + " CRC32 mismatch on map " + "— expected "
                            + Long.toHexString(manifest.colorCrc32) + " got "
                            + Long.toHexString(actualCrc) + " (rendering anyway)");
                }
            }

            return mapColors;
        } else {
            throw new IllegalStateException("Decompressed size " + originalSize
                    + " is smaller than MAP_BYTES — corrupt payload?");
        }
    }

    public static void paintMap(MinecraftClient client,
            MapIdComponent mapId,
            MapState mapState,
            byte[] mapColors) {
        System.arraycopy(mapColors, 0, mapState.colors, 0, mapColors.length);
        client.getMapTextureManager().setNeedsUpdate(mapId, mapState);
    }

    public static void clearCache() {
        claimedMaps.clear();
    }
}