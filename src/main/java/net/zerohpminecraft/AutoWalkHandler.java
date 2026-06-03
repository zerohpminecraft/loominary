package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Walks the player straight forward on a duty cycle — N ticks moving, then M ticks
 * paused, repeating. Useful for slowly tracing a build edge hands-free.
 *
 * <p>Rather than press the {@code forwardKey} binding (which mods like the Litematica
 * printer overwrite every tick — it resets the movement keys from the <em>physical</em>
 * keyboard state, so a programmatic press never sticks), this just tracks the on/off
 * phase here and lets {@code KeyboardInputMixin} force the forward movement in at the
 * tail of {@link net.minecraft.client.input.KeyboardInput#tick()}, after the keys have
 * been read. That overrides the result regardless of who set the keys, so it coexists
 * with the printer. Direction is whatever you're facing; this only goes forward.
 *
 * <p>20 ticks = 1 second. A 0-tick phase is skipped, so {@code 20 0} walks continuously.
 */
public class AutoWalkHandler {

    private static final String TAG = "[Loominary]";

    private static boolean active = false;
    private static int onTicks  = 10;   // default: 0.5s forward
    private static int offTicks = 20;   // default: 1.0s pause
    private static boolean phaseOn = true;
    private static int remaining = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoWalkHandler::onTick);
    }

    public static boolean isActive() {
        return active;
    }

    /** Queried by {@code KeyboardInputMixin} each tick: should we hold forward right now? */
    public static boolean shouldForceForward() {
        if (!active || !phaseOn) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        // Don't drive movement while a GUI/container is open — e.g. when the printer opens
        // a chest to restock, let it work without us walking off.
        return mc.player != null && mc.currentScreen == null;
    }

    /** Hotkey action: turn the cycle on or off, using the current timings. */
    public static void toggle(MinecraftClient client) {
        if (active) {
            stop(client);
        } else {
            phaseOn = true;
            remaining = onTicks;
            active = true;
            if (client.player != null) {
                client.player.sendMessage(Text.literal(String.format(
                        "§a%s Auto-walk on — forward %d t / pause %d t (%.1fs / %.1fs).",
                        TAG, onTicks, offTicks, onTicks / 20.0, offTicks / 20.0)), false);
            }
        }
    }

    /** Command action: set the on/off durations (in ticks) the hotkey will follow. */
    public static void setTimings(MinecraftClient client, int on, int off) {
        onTicks  = Math.max(1, on);     // a 0-tick "on" would never move; clamp to 1
        offTicks = Math.max(0, off);
        if (active) { phaseOn = true; remaining = onTicks; }   // apply live to a running cycle
        if (client.player != null) {
            client.player.sendMessage(Text.literal(String.format(
                    "§a%s Auto-walk timings set — forward %d t / pause %d t (%.1fs / %.1fs).%s",
                    TAG, onTicks, offTicks, onTicks / 20.0, offTicks / 20.0,
                    active ? "" : " §7Press the Auto-walk hotkey to start.")), false);
        }
    }

    public static void stop(MinecraftClient client) {
        active = false;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§e" + TAG + " Auto-walk off."), false);
        }
    }

    private static void onTick(MinecraftClient client) {
        if (!active) return;
        if (client.player == null || client.world == null) {   // left the world — drop the cycle
            active = false;
            return;
        }
        if (--remaining <= 0) {
            phaseOn = !phaseOn;
            remaining = phaseOn ? onTicks : offTicks;
            // Skip a zero-length phase (e.g. offTicks == 0 → walk continuously).
            if (remaining <= 0) {
                phaseOn = !phaseOn;
                remaining = phaseOn ? onTicks : offTicks;
            }
        }
    }
}
