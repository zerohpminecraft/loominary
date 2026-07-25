package net.zerohpminecraft.docs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
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
 * <h2>Smoke-test steps</h2>
 * The same engine also backs the live in-game smoke harness (see {@code SMOKE_TESTS.md}).
 * When {@code -Dloominary.smoke.result=<path>} is set, each assertion below is counted, and
 * a one-line {@code PASS n/n} or {@code FAIL: …} verdict is written to that path on {@code exit}
 * (or when the script runs out). {@code scripts/smoke-test.sh} reads it for the process exit code.
 * Assertions never throw — a failed assertion is recorded and the run continues so every check
 * reports.
 * <pre>
 *   {"assertTilesAtLeast": 1}         PayloadState.tiles.size() &gt;= N
 *   {"assertPayloadPresent": true}    active tile carried a payload (carpet OR banner chunks)
 *   {"assertActiveChunksAtLeast": 1}  PayloadState.ACTIVE_CHUNKS.size() &gt;= N (banner-only path)
 *   {"assertSourceLoaded": "sample"}  currentSourceFilename contains the substring
 * </pre>
 *
 * Wrappers: scripts/game-shots.sh (docs) and scripts/smoke-test.sh (smoke).
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
    private static boolean idleWait = false;   // block the script until autonomy (print/fill) finishes
    private static int idleCap = 0;            // 10-tick polls left before giving up the idle wait

    // Smoke-test bookkeeping (inert unless -Dloominary.smoke.result is set).
    private static int smokeChecks = 0;
    private static final List<String> smokeFailures = new ArrayList<>();
    private static boolean smokeResultWritten = false;

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
                if (idleWait) {
                    boolean busy = net.zerohpminecraft.AutoPrintHandler.isActive()
                            || net.zerohpminecraft.CarpetFillHandler.isActive();
                    if (busy && idleCap-- > 0) { waitTicks = 10; return; }
                    idleWait = false;
                    System.out.println(TAG + " waitIdle finished (" + (busy ? "cap reached" : "idle") + ")");
                }
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

    /**
     * Run block/registry work on the integrated-server thread, guarded so a throw there can
     * never crash the server thread and abort a headless capture. run() itself is wrapped in
     * a try/catch in tick(), but that guard does NOT extend into a deferred server.execute()
     * lambda — this does. Also null-checks the server so a missing integrated server logs
     * instead of NPEing.
     */
    private static void serverExec(MinecraftClient client, String what, Runnable body) {
        var server = client.getServer();
        if (server == null) { System.err.println(TAG + " " + what + ": no integrated server"); return; }
        server.execute(() -> {
            try {
                body.run();
            } catch (Exception e) {
                System.err.println(TAG + " " + what + " failed on server thread: " + e);
            }
        });
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
        } else if (step.has("placeBanners")) {
            // Ep07: place a grid of banner blocks (a visible "wall of banners") on the server.
            // Args {"origin":[x,y,z], "cols":N, "step":M}. Colour cycles for a lively look.
            JsonObject o = step.getAsJsonObject("placeBanners");
            JsonArray org = o.getAsJsonArray("origin");
            final int ox = org.get(0).getAsInt(), oy = org.get(1).getAsInt(), oz = org.get(2).getAsInt();
            final int cols = o.has("cols") ? o.get("cols").getAsInt() : 8;
            final int stepB = o.has("step") ? o.get("step").getAsInt() : 1;
            final int count = o.has("count") ? o.get("count").getAsInt()
                    : net.zerohpminecraft.PayloadState.tiles.get(net.zerohpminecraft.PayloadState.activeTileIndex).chunks.size();
            final String[] cc = {"white","light_blue","pink","yellow","lime","orange","magenta","cyan"};
            serverExec(client, "placeBanners", () -> {
                var world = client.getServer().getOverworld();
                for (int i = 0; i < count; i++) {
                    int bx = ox + (i % cols) * stepB, bz = oz + (i / cols) * stepB;
                    var id = net.minecraft.util.Identifier.of("minecraft", cc[i % cc.length] + "_banner");
                    var st = net.minecraft.registry.Registries.BLOCK.get(id).getDefaultState();
                    world.setBlockState(new BlockPos(bx, oy, bz), st);
                }
                System.out.println(TAG + " placed " + count + " banners");
            });
            waitTicks = 20;
        } else if (step.has("registerBanners")) {
            // Ep07: attach the active BANNER-tile's chunk strings as banner map-decorations onto
            // the crosshair/held filled map, the same records a right-click would create. The mod's
            // decoder reads decoration names matching [0-9a-f]{2}.* and rebuilds the image.
            var tile = net.zerohpminecraft.PayloadState.tiles.get(net.zerohpminecraft.PayloadState.activeTileIndex);
            net.minecraft.item.ItemStack mapStack = client.player.getMainHandStack();
            if (!(mapStack.getItem() instanceof net.minecraft.item.FilledMapItem)) {
                for (int i = 0; i < 9; i++) {
                    var s = client.player.getInventory().getStack(i);
                    if (s.getItem() instanceof net.minecraft.item.FilledMapItem) { mapStack = s; break; }
                }
            }
            var mapId = mapStack.get(net.minecraft.component.DataComponentTypes.MAP_ID);
            if (mapId == null) {
                System.err.println(TAG + " registerBanners: no filled map in hand");
            } else {
                final var fMapId = mapId;
                final java.util.List<String> chunks = new java.util.ArrayList<>(tile.chunks);
                // Add the chunk strings as banner decorations on the SERVER MapState and mark it
                // dirty, so the server syncs them to the client the way a real right-click would.
                serverExec(client, "registerBanners", () -> {
                    var sworld = client.getServer().getOverworld();
                    var mapState = sworld.getMapState(fMapId);
                    if (mapState == null) { System.err.println(TAG + " registerBanners: no server map state"); return; }
                    var decos = ((net.zerohpminecraft.mixin.MapStateAccessor) (Object) mapState).getDecorations();
                    int i = 0;
                    for (String chunk : chunks) {
                        byte mx = (byte) (-120 + (i % 12) * 20);
                        byte mz = (byte) (-120 + (i / 12) * 20);
                        decos.put("loom_banner_" + i, new net.minecraft.item.map.MapDecoration(
                                net.minecraft.item.map.MapDecorationTypes.BANNER_WHITE,
                                mx, mz, (byte) 0, java.util.Optional.of(net.minecraft.text.Text.literal(chunk))));
                        i++;
                    }
                    mapState.markDirty();
                    System.out.println(TAG + " registered " + i + " banner decorations (server)");
                });
            }
            waitTicks = 30;
        } else if (step.has("decodeToggle")) {
            // Ep06: flip every claimed map between the decoded art and the raw carpet/banner view
            // the unmodded world sees (the key.loominary.decode_toggle binding calls the same thing).
            net.zerohpminecraft.MapBannerDecoder.toggle(client);
            System.out.println(TAG + " decodeToggle");
            waitTicks = 10;
        } else if (step.has("waitTicks")) {
            waitTicks = step.get("waitTicks").getAsInt();
        } else if (step.has("waitIdle")) {
            // Block the script until the autonomous printer/fill finishes (or maxTicks elapses).
            // Used so ep05's catalogue and print beats wait on the real thing, not a fixed guess.
            int maxTicks = step.get("waitIdle").isJsonPrimitive() && step.get("waitIdle").getAsJsonPrimitive().isNumber()
                    ? step.get("waitIdle").getAsInt() : 24000;
            idleWait = true;
            idleCap = Math.max(1, maxTicks / 10);
            waitTicks = 20;   // grace for the autonomy to spin up before we start polling
            System.out.println(TAG + " waitIdle up to " + maxTicks + " ticks");
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
            if (tile.carpetCompressedB64 == null) {
                System.err.println(TAG + " placeCarpets: active tile has no carpet payload"
                        + " (banner-only tile); nothing to place");
                return;
            }
            byte[] compressed = java.util.Base64.getDecoder().decode(tile.carpetCompressedB64);
            byte[] header = net.zerohpminecraft.CarpetChannel.buildLoomHeader(
                    0, 0, 0, compressed.length, compressed.length, null);
            byte[] cargo = new byte[header.length + compressed.length];
            System.arraycopy(header, 0, cargo, 0, header.length);
            System.arraycopy(compressed, 0, cargo, header.length, compressed.length);
            final int carpetBytes = Math.min(cargo.length, net.zerohpminecraft.CarpetChannel.MAX_CARPET_BYTES);
            final byte[] fCargo = cargo;
            final int fx0 = x0, fy0 = y0, fz0 = z0;
            final int payloadLen = compressed.length;
            // Place on the SERVER thread: a render-thread setBlockState is racy for large platforms,
            // leaving some carpets unplaced when the scanned map captures colours (the decode then
            // trips "Non-carpet map color at nibble …"). server.execute() places them all coherently.
            serverExec(client, "placeCarpets", () -> {
                var world = client.getServer().getOverworld();
                var colors = net.minecraft.util.DyeColor.values();
                var white = net.minecraft.registry.Registries.BLOCK.get(
                        net.minecraft.util.Identifier.of("minecraft", "white_carpet")).getDefaultState();
                for (int x = 0; x < 128; x++) world.setBlockState(new BlockPos(fx0 + x, fy0, fz0 - 1), white);
                int placed = 128;
                for (int i = 0; i < carpetBytes * 2; i++) {
                    int b = fCargo[i / 2] & 0xFF;
                    int nib = (i % 2 == 0) ? (b >> 4) & 0xF : b & 0xF;
                    var id = net.minecraft.util.Identifier.of("minecraft", colors[nib].getName() + "_carpet");
                    var block = net.minecraft.registry.Registries.BLOCK.get(id);
                    world.setBlockState(new BlockPos(fx0 + (i % 128), fy0, fz0 + (i / 128)), block.getDefaultState());
                    placed++;
                }
                System.out.println(TAG + " placed " + placed + " carpets (LOOM header + "
                        + payloadLen + " payload bytes, noobline row at z=" + (fz0 - 1) + ")");
            });
            waitTicks = 25;
        } else if (step.has("stockChests")) {
            // Ep05 capture: place a row of single chests on the integrated server, each
            // filled with one carpet colour, so /loominary walk print has real storage to
            // catalogue and restock from. Filling the server-side ChestBlockEntity directly
            // (not setblock NBT) guarantees the contents are present and sync to the client
            // the moment the bot opens each chest. Args:
            //   {"start":[x,y,z], "axis":"x"|"z", "step":2, "facing":"south",
            //    "slots":27, "count":64, "colors":["white","orange", …]}
            JsonObject o = step.getAsJsonObject("stockChests");
            JsonArray st = o.getAsJsonArray("start");
            final int sx = st.get(0).getAsInt(), sy = st.get(1).getAsInt(), sz = st.get(2).getAsInt();
            final String axis = o.has("axis") ? o.get("axis").getAsString() : "x";
            final int stepBlocks = o.has("step") ? o.get("step").getAsInt() : 2;
            final int slots = o.has("slots") ? o.get("slots").getAsInt() : 27;
            final int count = o.has("count") ? o.get("count").getAsInt() : 64;
            final String facing = o.has("facing") ? o.get("facing").getAsString() : "south";
            final JsonArray cols = o.getAsJsonArray("colors");
            // Place + fill on the SERVER thread: DocsDriver.tick runs on the render thread, and
            // mutating/reading the integrated server's block entities from there is racy (the BE
            // read back null right after setBlockState, so the chests placed but stayed empty).
            // server.execute() runs this coherently on the server thread next tick; contents then
            // sync to the client when the bot opens each chest.
            serverExec(client, "stockChests", () -> {
                var world = client.getServer().getOverworld();
                var dir = net.minecraft.util.math.Direction.byName(facing);
                var chestState = net.minecraft.block.Blocks.CHEST.getDefaultState()
                        .with(net.minecraft.block.ChestBlock.FACING, dir)
                        .with(net.minecraft.block.ChestBlock.CHEST_TYPE, net.minecraft.block.enums.ChestType.SINGLE);
                int stocked = 0;
                for (int i = 0; i < cols.size(); i++) {
                    int cx = sx + (axis.equals("x") ? i * stepBlocks : 0);
                    int cz = sz + (axis.equals("z") ? i * stepBlocks : 0);
                    BlockPos pos = new BlockPos(cx, sy, cz);
                    world.setBlockState(pos, chestState);
                    if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
                        var item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(
                                "minecraft", cols.get(i).getAsString() + "_carpet"));
                        for (int s = 0; s < slots && s < chest.size(); s++)
                            chest.setStack(s, new net.minecraft.item.ItemStack(item, count));
                        chest.markDirty();
                        stocked++;
                    }
                }
                System.out.println(TAG + " stocked " + stocked + " chests (" + slots + " slots × "
                        + count + " carpet each)");
            });
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
        } else if (step.has("perspective")) {
            String p = step.get("perspective").getAsString();
            net.minecraft.client.option.Perspective persp =
                    p.startsWith("third")
                        ? (p.contains("front") ? net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT
                                               : net.minecraft.client.option.Perspective.THIRD_PERSON_BACK)
                        : net.minecraft.client.option.Perspective.FIRST_PERSON;
            client.options.setPerspective(persp);
            System.out.println(TAG + " perspective → " + persp);
            waitTicks = 2;
        } else if (step.has("openInventory")) {
            if (client.player != null)
                client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player));
            waitTicks = 5;
        } else if (step.has("screenshot")) {
            String name = step.get("screenshot").getAsString();
            if (!name.endsWith(".png")) name += ".png";
            System.out.println(TAG + " screenshot " + name);
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name,
                    client.getFramebuffer(), text -> {});
            waitTicks = 5;
        } else if (step.has("assertTilesAtLeast")) {
            int want = step.get("assertTilesAtLeast").getAsInt();
            int got = net.zerohpminecraft.PayloadState.tiles.size();
            smokeCheck(got >= want, "tiles >= " + want + " (got " + got + ")");
            waitTicks = 2;
        } else if (step.has("assertActiveChunksAtLeast")) {
            int want = step.get("assertActiveChunksAtLeast").getAsInt();
            int got = net.zerohpminecraft.PayloadState.ACTIVE_CHUNKS.size();
            smokeCheck(got >= want, "active chunks >= " + want + " (got " + got + ")");
            waitTicks = 2;
        } else if (step.has("assertPayloadPresent")) {
            // Codec-agnostic: a correct import lands a payload on the active tile. The
            // DEFAULT carpet codec (CARPET_BANNERS_SHADE) stores it as carpetCompressedB64
            // with 0 overflow banner chunks, so checking ACTIVE_CHUNKS alone wrongly fails
            // the common case. Accept either channel: carpet payload OR banner chunks.
            var tiles = net.zerohpminecraft.PayloadState.tiles;
            int idx = net.zerohpminecraft.PayloadState.activeTileIndex;
            boolean ok = false;
            String detail;
            if (idx < 0 || idx >= tiles.size()) {
                detail = "no active tile (index " + idx + ", tiles " + tiles.size() + ")";
            } else {
                var tile = tiles.get(idx);
                String carpet = tile.carpetCompressedB64;
                int carpetLen = carpet == null ? 0 : carpet.length();
                int chunks = tile.chunks.size();
                ok = carpetLen > 0 || chunks > 0;
                detail = "tile " + idx + " carpetB64=" + carpetLen + "b, chunks=" + chunks;
            }
            smokeCheck(ok, "active tile carried a payload (" + detail + ")");
            waitTicks = 2;
        } else if (step.has("assertSourceLoaded")) {
            String want = step.get("assertSourceLoaded").getAsString();
            String got = net.zerohpminecraft.PayloadState.currentSourceFilename;
            smokeCheck(got != null && got.contains(want),
                    "source contains '" + want + "' (got " + got + ")");
            waitTicks = 2;
        } else if (step.has("exit")) {
            System.out.println(TAG + " script complete — stopping client");
            writeSmokeResult();
            phase = Phase.DONE;
            client.scheduleStop();
        } else if (step.has("placeSchematic")) {
            // Ep05 capture: generate a small carpet-floor .litematic and place its
            // Litematica ghost, so /loominary walk print has a real placement to
            // print. Args: {"w":40,"d":32,"origin":[x,y,z]}. Litematica + the printer
            // fork must be on the classpath (dev run with -Pep05capture). tick() is
            // already on the client thread, so the placement API can be called here.
            JsonObject o = step.getAsJsonObject("placeSchematic");
            int w = o.get("w").getAsInt(), d = o.get("d").getAsInt();
            JsonArray p = o.getAsJsonArray("origin");
            int ox = p.get(0).getAsInt(), oy = p.get(1).getAsInt(), oz = p.get(2).getAsInt();
            try {
                Path dir = client.runDirectory.toPath().resolve("schematics");
                Files.createDirectories(dir);
                Path file = dir.resolve("ep05demo.litematic");
                var st = net.zerohpminecraft.PayloadState.tiles;
                boolean haveCarpet = !st.isEmpty()
                        && st.get(net.zerohpminecraft.PayloadState.activeTileIndex).carpetCompressedB64 != null;
                if (haveCarpet) {
                    int[] dim = writeLoomSchematic(file);   // real LOOM platform → decodes back to the image
                    w = dim[0]; d = dim[1];
                    System.out.println(TAG + " built LOOM schematic from loaded state: " + w + "x" + d);
                } else {
                    writeCarpetSchematic(w, d, file);       // fallback demo pattern (no carpet payload loaded)
                }

                Class<?> cLS = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
                Class<?> cFT = Class.forName("fi.dy.masa.litematica.util.FileType");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object lite = Enum.valueOf((Class) cFT, "LITEMATICA_SCHEMATIC");
                Object schem = cLS.getMethod("createFromFile", java.io.File.class, String.class, cFT)
                        .invoke(null, file.getParent().toFile(), file.getFileName().toString(), lite);
                if (schem == null) {
                    System.err.println(TAG + " placeSchematic: Litematica failed to load the file");
                } else {
                    Class<?> cSP = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
                    Object placement = cSP.getMethod("createFor", cLS, BlockPos.class, String.class,
                            boolean.class, boolean.class)
                            .invoke(null, schem, new BlockPos(ox, oy, oz), "loominary", true, true);
                    Class<?> cDM = Class.forName("fi.dy.masa.litematica.data.DataManager");
                    Object mgr = cDM.getMethod("getSchematicPlacementManager").invoke(null);
                    mgr.getClass().getMethod("addSchematicPlacement", cSP, boolean.class)
                            .invoke(mgr, placement, false);
                    System.out.println(TAG + " placed schematic " + w + "x" + d + " ghost at "
                            + ox + "," + oy + "," + oz);
                }
            } catch (Exception e) {
                System.err.println(TAG + " placeSchematic failed: " + e);
            }
            waitTicks = 30;
        } else {
            System.err.println(TAG + " unknown step: " + step);
        }
    }

    /** Record a smoke assertion; never throws so the whole script always runs. */
    private static void smokeCheck(boolean ok, String desc) {
        smokeChecks++;
        if (ok) {
            System.out.println(TAG + " smoke PASS: " + desc);
        } else {
            System.err.println(TAG + " smoke FAIL: " + desc);
            smokeFailures.add(desc);
        }
    }

    /** Write PASS/FAIL verdict to -Dloominary.smoke.result, if set. No-op for docs runs. */
    private static void writeSmokeResult() {
        String out = System.getProperty("loominary.smoke.result");
        if (out == null || smokeResultWritten) return;
        smokeResultWritten = true;
        String verdict = smokeFailures.isEmpty()
                ? "PASS " + smokeChecks + "/" + smokeChecks
                : "FAIL " + (smokeChecks - smokeFailures.size()) + "/" + smokeChecks
                        + ": " + String.join("; ", smokeFailures);
        try {
            Files.writeString(Path.of(out), verdict + "\n");
            System.out.println(TAG + " smoke result → " + out + ": " + verdict);
        } catch (Exception e) {
            System.err.println(TAG + " could not write smoke result " + out + ": " + e);
        }
    }

    /** Builds the ACTIVE loaded Loominary tile's real LOOM carpet platform as a
     *  .litematic ghost (128 wide × data rows, with a white noobline row at z=0),
     *  matching the placeCarpets nibble layout so the printed floor decodes back to
     *  the source image/animation. Returns {width, depth}. */
    private static int[] writeLoomSchematic(Path file) throws java.io.IOException {
        var tile = net.zerohpminecraft.PayloadState.tiles.get(net.zerohpminecraft.PayloadState.activeTileIndex);
        byte[] compressed = java.util.Base64.getDecoder().decode(tile.carpetCompressedB64);
        byte[] header = net.zerohpminecraft.CarpetChannel.buildLoomHeader(0, 0, 0,
                compressed.length, compressed.length, null);
        byte[] cargo = new byte[header.length + compressed.length];
        System.arraycopy(header, 0, cargo, 0, header.length);
        System.arraycopy(compressed, 0, cargo, header.length, compressed.length);
        int carpetBytes = Math.min(cargo.length, net.zerohpminecraft.CarpetChannel.MAX_CARPET_BYTES);
        int dataNibbles = carpetBytes * 2;
        int dataRows = (dataNibbles + 127) / 128;
        int W = 128, D = dataRows + 1;               // region z=0 = noobline, z=1.. = data
        DyeColor[] colors = DyeColor.values();

        NbtList palette = new NbtList();
        NbtCompound air = new NbtCompound(); air.putString("Name", "minecraft:air"); palette.add(air);
        for (DyeColor c : colors) {
            NbtCompound e = new NbtCompound(); e.putString("Name", "minecraft:" + c.getName() + "_carpet"); palette.add(e);
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int[] idx = new int[W * D];
        int white = 1 + DyeColor.WHITE.ordinal();
        for (int x = 0; x < W; x++) idx[x] = white;   // noobline
        for (int i = 0; i < dataNibbles; i++) {
            int b = cargo[i / 2] & 0xFF;
            int nib = (i % 2 == 0) ? (b >> 4) & 0xF : b & 0xF;
            idx[(i / 128 + 1) * W + (i % 128)] = 1 + nib;
        }
        long[] blockStates = packBlockIndices(idx, bits);

        NbtCompound root = new NbtCompound();
        root.putInt("Version", 6); root.putInt("SubVersion", 1); root.putInt("MinecraftDataVersion", 4189);
        NbtCompound meta = new NbtCompound();
        meta.putString("Name", "loomfloor"); meta.putString("Author", "Loominary");
        meta.putString("Description", "Loominary carpet platform");
        meta.putInt("RegionCount", 1);
        meta.putLong("TimeCreated", 1_752_000_000_000L); meta.putLong("TimeModified", 1_752_000_000_000L);
        meta.putInt("TotalBlocks", W * D); meta.putInt("TotalVolume", W * D);
        NbtCompound es = new NbtCompound(); es.putInt("x", W); es.putInt("y", 1); es.putInt("z", D);
        meta.put("EnclosingSize", es);
        root.put("Metadata", meta);
        NbtCompound region = new NbtCompound();
        NbtCompound pos = new NbtCompound(); pos.putInt("x", 0); pos.putInt("y", 0); pos.putInt("z", 0);
        region.put("Position", pos);
        NbtCompound size = new NbtCompound(); size.putInt("x", W); size.putInt("y", 1); size.putInt("z", D);
        region.put("Size", size);
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", blockStates);
        region.put("TileEntities", new NbtList()); region.put("Entities", new NbtList());
        region.put("PendingBlockTicks", new NbtList()); region.put("PendingFluidTicks", new NbtList());
        NbtCompound regions = new NbtCompound(); regions.put("loomfloor", region);
        root.put("Regions", regions);
        try (OutputStream fos = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(gz)) {
            NbtIo.write(root, dos);
        }
        return new int[]{W, D};
    }

    /** Writes a small W×D×1 carpet-floor schematic (.litematic v6) with a repeating
     *  multi-colour pattern (fallback when no Loominary state is loaded). */
    private static void writeCarpetSchematic(int w, int d, Path file) throws java.io.IOException {
        int[] pick = {
            DyeColor.RED.ordinal(), DyeColor.ORANGE.ordinal(), DyeColor.YELLOW.ordinal(),
            DyeColor.LIME.ordinal(), DyeColor.LIGHT_BLUE.ordinal(), DyeColor.MAGENTA.ordinal(),
        };
        NbtList palette = new NbtList();
        NbtCompound air = new NbtCompound(); air.putString("Name", "minecraft:air"); palette.add(air);
        for (DyeColor c : DyeColor.values()) {
            NbtCompound e = new NbtCompound();
            e.putString("Name", "minecraft:" + c.getName() + "_carpet");
            palette.add(e);
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)); // 5 for 17 entries
        int[] idx = new int[w * d];
        for (int z = 0; z < d; z++)
            for (int x = 0; x < w; x++)
                idx[z * w + x] = 1 + pick[((x / 4) + (z / 4)) % pick.length];
        long[] blockStates = packBlockIndices(idx, bits);

        NbtCompound root = new NbtCompound();
        root.putInt("Version", 6); root.putInt("SubVersion", 1); root.putInt("MinecraftDataVersion", 4189);
        NbtCompound meta = new NbtCompound();
        meta.putString("Name", "ep05demo"); meta.putString("Author", "Loominary");
        meta.putString("Description", "ep05 autonomous-print demo floor");
        meta.putInt("RegionCount", 1);
        meta.putLong("TimeCreated", 1_752_000_000_000L); meta.putLong("TimeModified", 1_752_000_000_000L);
        meta.putInt("TotalBlocks", w * d); meta.putInt("TotalVolume", w * d);
        NbtCompound es = new NbtCompound(); es.putInt("x", w); es.putInt("y", 1); es.putInt("z", d);
        meta.put("EnclosingSize", es);
        root.put("Metadata", meta);

        NbtCompound region = new NbtCompound();
        NbtCompound pos = new NbtCompound(); pos.putInt("x", 0); pos.putInt("y", 0); pos.putInt("z", 0);
        region.put("Position", pos);
        NbtCompound size = new NbtCompound(); size.putInt("x", w); size.putInt("y", 1); size.putInt("z", d);
        region.put("Size", size);
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", blockStates);
        region.put("TileEntities", new NbtList()); region.put("Entities", new NbtList());
        region.put("PendingBlockTicks", new NbtList()); region.put("PendingFluidTicks", new NbtList());
        NbtCompound regions = new NbtCompound(); regions.put("ep05demo", region);
        root.put("Regions", regions);

        try (OutputStream fos = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(gz)) {
            NbtIo.write(root, dos);
        }
    }

    /** Litematica spanning bit-pack (entries may cross long boundaries). */
    private static long[] packBlockIndices(int[] indices, int bitsPerEntry) {
        long totalBits = (long) indices.length * bitsPerEntry;
        long[] longs = new long[(int) ((totalBits + 63) / 64)];
        long mask = (1L << bitsPerEntry) - 1;
        for (int i = 0; i < indices.length; i++) {
            long bitIndex = (long) i * bitsPerEntry;
            int li = (int) (bitIndex >> 6);
            int bitOffset = (int) (bitIndex & 63);
            long value = indices[i] & mask;
            longs[li] |= value << bitOffset;
            int bitsInFirstLong = 64 - bitOffset;
            if (bitsInFirstLong < bitsPerEntry) longs[li + 1] |= value >>> bitsInFirstLong;
        }
        return longs;
    }
}
