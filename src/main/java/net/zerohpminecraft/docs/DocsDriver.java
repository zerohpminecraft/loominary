package net.zerohpminecraft.docs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless documentation-screenshot driver. Never active in normal play:
 * {@code ClientModInit} only touches this class when {@code -Dloominary.docs=true}
 * is set, and the release jar excludes the whole {@code docs} package
 * (see {@code jar.exclude} in build.gradle).
 *
 * On launch it creates (or rejoins) a superflat creative world named
 * {@code docs-world}, then executes the step script given by
 * {@code -Dloominary.docs.script=<path>} — a JSON array of steps:
 *
 * <pre>
 *   {"cmd": "time set noon"}          run a command (no leading slash)
 *   {"chat": "hello"}                 send a chat message
 *   {"waitTicks": 40}                 idle N ticks
 *   {"tp": [0.5, -59, 0.5, 180, 0]}   teleport (x y z yaw pitch, via /tp)
 *   {"useItem": true}                 right-click the held item
 *   {"useEntity": true}               right-click the entity at the crosshair
 *   {"hotbar": 3}                     select hotbar slot 0-8
 *   {"hud": false}                    show/hide the HUD (F1)
 *   {"screenshot": "name"}            save run/screenshots/name.png
 *   {"exit": true}                    stop the client
 * </pre>
 *
 * Wrapper: scripts/game-shots.sh (xvfb + gradle runDocsShots + copy to docs).
 */
public final class DocsDriver {

    private static final String WORLD = "docs-world";
    private static final String TAG = "[LoominaryDocs]";

    private enum Phase { TITLE, JOINING, RUNNING, DONE }

    private static Phase phase = Phase.TITLE;
    private static List<JsonObject> steps = new ArrayList<>();
    private static int stepIndex = 0;
    private static int waitTicks = 0;
    private static int settleTicks = 0;

    private DocsDriver() {}

    public static void init() {
        Path script = Path.of(System.getProperty("loominary.docs.script",
                "docs/tools/game-shots.json"));
        try {
            JsonArray arr = new Gson().fromJson(Files.readString(script), JsonArray.class);
            arr.forEach(e -> steps.add(e.getAsJsonObject()));
            System.out.println(TAG + " loaded " + steps.size() + " steps from " + script);
        } catch (Exception e) {
            System.err.println(TAG + " cannot read script " + script + ": " + e);
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(DocsDriver::tick);
    }

    private static void tick(MinecraftClient client) {
        switch (phase) {
            case TITLE -> {
                if (!(client.currentScreen instanceof TitleScreen)) return;
                phase = Phase.JOINING;
                if (client.getLevelStorage().levelExists(WORLD)) {
                    System.out.println(TAG + " joining existing " + WORLD);
                    client.createIntegratedServerLoader().start(WORLD,
                            () -> client.setScreen(new TitleScreen()));
                } else {
                    System.out.println(TAG + " creating superflat " + WORLD);
                    LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false,
                            Difficulty.PEACEFUL, true,
                            new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES),
                            DataConfiguration.SAFE_MODE);
                    GeneratorOptions gen = new GeneratorOptions(20260712L, false, false);
                    client.createIntegratedServerLoader().createAndStart(WORLD, info, gen,
                            registries -> registries.getOrThrow(RegistryKeys.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.FLAT).value()
                                    .createDimensionsRegistryHolder(),
                            client.currentScreen);
                }
            }
            case JOINING -> {
                if (client.player == null || client.world == null) return;
                // Give the world a moment to render before the first step.
                if (++settleTicks < 60) return;
                System.out.println(TAG + " world joined — running script");
                phase = Phase.RUNNING;
            }
            case RUNNING -> {
                if (waitTicks > 0) { waitTicks--; return; }
                if (stepIndex >= steps.size()) { phase = Phase.DONE; return; }
                JsonObject step = steps.get(stepIndex++);
                try {
                    run(client, step);
                } catch (Exception e) {
                    System.err.println(TAG + " step " + stepIndex + " failed: " + step + " — " + e);
                }
            }
            case DONE -> { }
        }
    }

    private static void run(MinecraftClient client, JsonObject step) {
        if (step.has("cmd")) {
            String cmd = step.get("cmd").getAsString();
            System.out.println(TAG + " /" + cmd);
            client.getNetworkHandler().sendChatCommand(cmd);
            waitTicks = 2;
        } else if (step.has("chat")) {
            client.getNetworkHandler().sendChatMessage(step.get("chat").getAsString());
            waitTicks = 2;
        } else if (step.has("waitTicks")) {
            waitTicks = step.get("waitTicks").getAsInt();
        } else if (step.has("tp")) {
            JsonArray a = step.getAsJsonArray("tp");
            client.getNetworkHandler().sendChatCommand(String.format(
                    "tp @s %s %s %s %s %s",
                    a.get(0).getAsString(), a.get(1).getAsString(), a.get(2).getAsString(),
                    a.size() > 3 ? a.get(3).getAsString() : "0",
                    a.size() > 4 ? a.get(4).getAsString() : "0"));
            // Hover in place — otherwise elevated camera positions fall during waits.
            client.player.getAbilities().flying = true;
            client.player.sendAbilitiesUpdate();
            waitTicks = 5;
        } else if (step.has("useItem")) {
            var result = client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            System.out.println(TAG + " useItem → " + result + ", now holding "
                    + client.player.getMainHandStack().getItem());
            waitTicks = 5;
        } else if (step.has("useBlock")) {
            // Right-click the block at the crosshair with the held item (e.g. place an
            // item frame on a wall).
            if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr
                    && bhr.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                var result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, bhr);
                System.out.println(TAG + " useBlock @ " + bhr.getBlockPos().toShortString() + " → " + result);
            } else {
                System.err.println(TAG + " useBlock: no block at crosshair (target=" + client.crosshairTarget + ")");
            }
            waitTicks = 5;
        } else if (step.has("useEntity")) {
            // Prefer the crosshair target, but fall back to the nearest item frame —
            // headless camera aim is not pixel-reliable.
            net.minecraft.entity.Entity target = null;
            if (client.crosshairTarget instanceof EntityHitResult hit) target = hit.getEntity();
            if (target == null) {
                var frames = client.world.getEntitiesByClass(
                        net.minecraft.entity.decoration.ItemFrameEntity.class,
                        client.player.getBoundingBox().expand(16), e -> true);
                System.out.println(TAG + " nearby item frames: " + frames.size());
                double best = Double.MAX_VALUE;
                for (var f : frames) {
                    double d = f.squaredDistanceTo(client.player);
                    if (d < best) { best = d; target = f; }
                }
            }
            if (target != null) {
                System.out.println(TAG + " useEntity → " + target.getType().getUntranslatedName());
                client.interactionManager.interactEntity(client.player, target, Hand.MAIN_HAND);
            } else {
                System.err.println(TAG + " useEntity: no target found");
            }
            waitTicks = 5;
        } else if (step.has("hotbar")) {
            client.player.getInventory().selectedSlot = step.get("hotbar").getAsInt();
            waitTicks = 2;
        } else if (step.has("select")) {
            // Select the hotbar slot holding the given item id (e.g. after using an
            // empty map in creative, the filled map lands in the next free slot).
            String want = step.get("select").getAsString();
            var inv = client.player.getInventory();
            for (int i = 0; i < 9; i++) {
                var id = net.minecraft.registry.Registries.ITEM.getId(inv.getStack(i).getItem());
                if (id.toString().equals(want)) {
                    inv.selectedSlot = i;
                    System.out.println(TAG + " selected " + want + " in slot " + i);
                    break;
                }
            }
            waitTicks = 5;
        } else if (step.has("placeCarpets")) {
            // Places the ACTIVE tile's real carpet platform by writing blocks straight
            // into the integrated server world — the authentic LOOM layout (16-byte
            // header + payload nibbles), scannable and decodable with an in-game map,
            // no Litematica needed. Args: [x0, y0, z0]; align x0/z0 to a map cell
            // (≡ −64 mod 128) if the footage should actually decode.
            //
            // A noobline row of carpets is placed directly north of the first data row
            // so row 0 tops out level with its northern neighbor and shades flat
            // (see the wiki's In-Game-Placement page).
            JsonArray a = step.getAsJsonArray("placeCarpets");
            int x0 = a.get(0).getAsInt(), y0 = a.get(1).getAsInt(), z0 = a.get(2).getAsInt();
            var tile = net.zerohpminecraft.PayloadState.tiles.get(
                    net.zerohpminecraft.PayloadState.activeTileIndex);
            byte[] compressed = java.util.Base64.getDecoder().decode(tile.carpetCompressedB64);
            byte[] header = net.zerohpminecraft.CarpetChannel.buildLoomHeader(
                    0, 0, 0, compressed.length, compressed.length, null);
            byte[] cargo = new byte[header.length + compressed.length];
            System.arraycopy(header, 0, cargo, 0, header.length);
            System.arraycopy(compressed, 0, cargo, header.length, compressed.length);
            int carpetBytes = Math.min(cargo.length, net.zerohpminecraft.CarpetChannel.MAX_CARPET_BYTES);
            var world = client.getServer().getOverworld();
            var colors = net.minecraft.util.DyeColor.values();
            var white = net.minecraft.registry.Registries.BLOCK.get(
                    net.minecraft.util.Identifier.of("minecraft", "white_carpet")).getDefaultState();
            for (int x = 0; x < 128; x++) world.setBlockState(new BlockPos(x0 + x, y0, z0 - 1), white);
            int placed = 128;
            for (int i = 0; i < carpetBytes * 2; i++) {
                int b = cargo[i / 2] & 0xFF;
                int nib = (i % 2 == 0) ? (b >> 4) & 0xF : b & 0xF;
                var id = net.minecraft.util.Identifier.of("minecraft", colors[nib].getName() + "_carpet");
                var block = net.minecraft.registry.Registries.BLOCK.get(id);
                world.setBlockState(new BlockPos(x0 + (i % 128), y0, z0 + (i / 128)), block.getDefaultState());
                placed++;
            }
            System.out.println(TAG + " placed " + placed + " carpets (LOOM header + "
                    + compressed.length + " payload bytes, noobline row at z=" + (z0 - 1) + ")");
            waitTicks = 20;
        } else if (step.has("cursor")) {
            // Park the mouse pointer (window-relative fractions) so slot tooltips
            // don't cover container-screen screenshots.
            JsonArray a = step.getAsJsonArray("cursor");
            double px = client.getWindow().getWidth() * a.get(0).getAsDouble();
            double py = client.getWindow().getHeight() * a.get(1).getAsDouble();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getHandle(), px, py);
            try {
                // GLFW setCursorPos doesn't fire the position callback, so update the
                // fields MC reads for hover/tooltips too. Yarn field names — dev-only.
                var mouse = client.mouse;
                var fx = mouse.getClass().getDeclaredField("x");
                var fy = mouse.getClass().getDeclaredField("y");
                fx.setAccessible(true); fy.setAccessible(true);
                fx.setDouble(mouse, px); fy.setDouble(mouse, py);
            } catch (ReflectiveOperationException e) {
                System.err.println(TAG + " cursor: mouse field poke failed: " + e);
            }
            waitTicks = 2;
        } else if (step.has("closeScreen")) {
            if (client.player != null) client.player.closeHandledScreen();
            client.setScreen(null);
            waitTicks = 5;
        } else if (step.has("hud")) {
            client.options.hudHidden = !step.get("hud").getAsBoolean();
        } else if (step.has("screenshot")) {
            String name = step.get("screenshot").getAsString();
            if (!name.endsWith(".png")) name += ".png";
            System.out.println(TAG + " screenshot " + name);
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name,
                    client.getFramebuffer(), text -> {});
            waitTicks = 5;
        } else if (step.has("exit")) {
            System.out.println(TAG + " script complete — stopping client");
            phase = Phase.DONE;
            client.scheduleStop();
        } else {
            System.err.println(TAG + " unknown step: " + step);
        }
    }
}
