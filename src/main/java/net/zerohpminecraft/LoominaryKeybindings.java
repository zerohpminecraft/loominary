package net.zerohpminecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Registers Loominary keybindings and routes them to the equivalent commands.
 *
 * Routing through the command dispatcher (rather than calling internal helpers
 * directly) gives us identical chat feedback to typing the command — same
 * success messages, same error handling, same state mutations. It also means
 * the keybindings stay in sync automatically when the command logic evolves.
 *
 * Defaults are unbound so they don't collide with the user's existing setup.
 * Bind in the standard Controls → Key Binds menu (category "Loominary").
 */
public class LoominaryKeybindings {

    private static final String CATEGORY = "key.categories.loominary";

    private static KeyBinding keySteal;
    private static KeyBinding keyPreview;
    private static KeyBinding keyRevert;
    private static KeyBinding keyTileNext;
    private static KeyBinding keyTilePrev;
    private static KeyBinding keyStatus;
    private static KeyBinding keyEdit;

    public static void register() {
        keySteal    = registerUnbound("key.loominary.steal");
        keyPreview  = registerUnbound("key.loominary.preview");
        keyRevert   = registerUnbound("key.loominary.revert");
        keyTileNext = registerUnbound("key.loominary.tile_next");
        keyTilePrev = registerUnbound("key.loominary.tile_prev");
        keyStatus   = registerUnbound("key.loominary.status");
        keyEdit     = registerUnbound("key.loominary.edit");

        ClientTickEvents.END_CLIENT_TICK.register(LoominaryKeybindings::onTick);
    }

    private static KeyBinding registerUnbound(String translationKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,   // unbound by default
                CATEGORY));
    }

    private static void onTick(MinecraftClient client) {
        if (client.player == null) return;

        // wasPressed() drains the press queue, so a single press fires once
        // even if multiple ticks pass before we read it.
        while (keySteal.wasPressed())    runCommand(client, "loominary import steal");
        while (keyPreview.wasPressed())  runCommand(client, "loominary preview");
        while (keyRevert.wasPressed())   runCommand(client, "loominary revert");
        while (keyTileNext.wasPressed()) runCommand(client, "loominary tile next");
        while (keyTilePrev.wasPressed()) runCommand(client, "loominary tile prev");
        while (keyStatus.wasPressed())   runCommand(client, "loominary status");
        while (keyEdit.wasPressed())     runCommand(client, "loominary edit");
    }

    private static void runCommand(MinecraftClient client, String command) {
        CommandDispatcher<FabricClientCommandSource> dispatcher =
                ClientCommandManager.getActiveDispatcher();
        if (dispatcher == null) return; // not yet registered

        // Synthesize a source. The fabric API exposes a FabricClientCommandSource
        // through the ClientPlayerEntity at runtime — we can just borrow that.
        FabricClientCommandSource source =
                (FabricClientCommandSource) client.player.networkHandler.getCommandSource();

        try {
            dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            // Brigadier already sent the parsed error to the source; nothing to do.
        }
    }
}