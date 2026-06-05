package net.zerohpminecraft;

import net.minecraft.client.MinecraftClient;

/**
 * Single arbiter for forced player movement, so the two movers ({@link WaypointMover} and
 * {@link AutoWalkHandler}) never fight over the one {@code KeyboardInputMixin} injection
 * point. The mixin asks here each tick what to force; whoever's active wins, with the
 * target-directed {@link WaypointMover} taking priority over the blind duty-cycle walk.
 *
 * <p>Nothing is forced while a GUI/container is open — e.g. when a chest is open during
 * restock we want the player standing still, not walking off.
 */
public final class LoominaryMovement {

    private LoominaryMovement() {}

    /** What the mixin should force this tick: forward and/or jump. */
    public record ForcedInput(boolean forward, boolean jump) {}

    /**
     * Resolves the forced input for this tick and, as a side effect, lets the active mover
     * aim (the {@link WaypointMover} sets yaw here, right before movement is computed).
     * Returns null when nothing should be forced.
     */
    public static ForcedInput beforeMovement() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return null;

        ForcedInput wp = WaypointMover.forcedInput();
        if (wp != null) {
            WaypointMover.steer(mc);
            return wp;
        }
        if (AutoWalkHandler.shouldForceForward()) return new ForcedInput(true, false);
        return null;
    }
}
