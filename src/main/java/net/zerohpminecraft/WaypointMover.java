package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Target-directed straight-line steering — the autonomous counterpart to
 * {@link AutoWalkHandler}'s blind duty-cycle walk. Give it a waypoint and it faces the
 * player toward it (yaw) and asks {@link LoominaryMovement} to hold forward until the
 * player arrives. It does <em>not</em> pathfind: it walks straight at the target, with a
 * jump-to-unstick nudge for 1-block ledges. That's enough on the open, flat carpet floor
 * and in open line-of-sight storage rooms (walls/corners would defeat it).
 *
 * <p>Forward (and jump) are injected through the same {@code KeyboardInputMixin} tail hook
 * {@link AutoWalkHandler} uses, so it coexists with the Litematica printer. A single active
 * target at a time — only one consumer ({@link AutoPrintHandler} or {@link CarpetFillHandler})
 * drives it at once.
 */
public final class WaypointMover {

    private WaypointMover() {}

    private static final double ARRIVE_RADIUS_SQ = 0.6 * 0.6;   // horizontal "close enough"
    private static final double STILL_EPS_SQ     = 0.04 * 0.04; // per-tick move below this = no progress
    private static final int    STILL_TICKS_JUMP = 8;           // no progress this long → try a jump
    private static final int    JUMP_DURATION    = 6;
    private static final int    MAX_STUCK_RETRIES = 10;         // give up after this many jump nudges

    private static boolean active = false;
    private static double  tx, ty, tz;          // target centre
    private static float   fixedPitch = Float.NaN;

    private static boolean arrived = false;
    private static boolean stuck   = false;

    private static double lastX = Double.NaN, lastZ = Double.NaN;
    private static int    stillTicks  = 0;
    private static int    jumpTicks    = 0;
    private static int    stuckRetries = 0;

    // Optional duty cycle (N ticks moving / M paused), used to pace the print walk so the
    // printer keeps up. dutyOn <= 0 means no duty cycle (walk continuously).
    private static int     dutyOn  = 0;
    private static int     dutyOff = 0;
    private static boolean dutyPhaseOn = true;
    private static int     dutyRemaining = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WaypointMover::tick);
    }

    /** Walk to the centre of {@code pos}. Resets arrival/stuck state. */
    public static void setWaypoint(BlockPos pos) {
        setTarget(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    public static void setTarget(double x, double y, double z) {
        tx = x; ty = y; tz = z;
        active = true;
        arrived = false;
        stuck = false;
        stillTicks = 0;
        jumpTicks = 0;
        stuckRetries = 0;
        lastX = Double.NaN; lastZ = Double.NaN;
    }

    /** Hold this pitch each tick while steering (e.g. aim down at the floor); NaN = leave it. */
    public static void setPitch(float pitch) { fixedPitch = pitch; }
    public static void clearPitch()           { fixedPitch = Float.NaN; }

    /**
     * Pace movement on a duty cycle: {@code on} ticks walking, {@code off} ticks paused (still
     * facing the target), repeating. {@code off <= 0} (or {@code on <= 0}) walks continuously.
     * Used by the print walk so the printer keeps up; cleared for chest navigation.
     */
    public static void setDutyCycle(int on, int off) {
        dutyOn = Math.max(0, on);
        dutyOff = Math.max(0, off);
        dutyPhaseOn = true;
        dutyRemaining = dutyOn;
    }

    public static void clearDutyCycle() { dutyOn = 0; dutyOff = 0; dutyPhaseOn = true; }

    /**
     * Update the duty-cycle timings without resetting the current phase — for live, per-tick
     * adaptive pacing. Going from continuous to paced (re)starts the phase; staying paced keeps
     * the running phase so the change takes effect at the next phase boundary.
     */
    public static void setDutyTimings(int on, int off) {
        boolean wasActive = dutyActive();
        dutyOn = Math.max(0, on);
        dutyOff = Math.max(0, off);
        if (dutyActive() && !wasActive) { dutyPhaseOn = true; dutyRemaining = dutyOn; }
    }

    private static boolean dutyActive() { return dutyOn > 0 && dutyOff > 0; }

    public static void stop() {
        active = false;
        jumpTicks = 0;
        clearDutyCycle();   // chest navigation runs continuously; the print walk re-sets its own pace
    }

    public static boolean isActive()  { return active; }
    public static boolean arrived()   { return arrived; }
    public static boolean stuck()     { return stuck; }

    public static double horizontalDistSq(MinecraftClient client) {
        if (client.player == null) return Double.MAX_VALUE;
        double dx = tx - client.player.getX();
        double dz = tz - client.player.getZ();
        return dx * dx + dz * dz;
    }

    /** Forced input for {@link LoominaryMovement}, or null when not steering. */
    static LoominaryMovement.ForcedInput forcedInput() {
        if (!active) return null;
        boolean walk = !dutyActive() || dutyPhaseOn;   // during a duty "off" phase, pause forward
        return new LoominaryMovement.ForcedInput(walk, jumpTicks > 0);
    }

    /**
     * Aim at the target. Called from the movement mixin right before the player's movement
     * is computed, so the yaw set here drives this tick's walk direction.
     */
    static void steer(MinecraftClient client) {
        if (!active || client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        client.player.setYaw(yaw);
        if (!Float.isNaN(fixedPitch)) client.player.setPitch(fixedPitch);
    }

    /** Post-movement bookkeeping: arrival, progress/stuck tracking, jump-nudge timer. */
    private static void tick(MinecraftClient client) {
        if (!active || client.player == null || client.world == null) return;

        // Advance the duty-cycle phase (walk N ticks / pause M ticks), skipping a zero-length phase.
        if (dutyActive() && --dutyRemaining <= 0) {
            dutyPhaseOn = !dutyPhaseOn;
            dutyRemaining = dutyPhaseOn ? dutyOn : dutyOff;
            if (dutyRemaining <= 0) { dutyPhaseOn = !dutyPhaseOn; dutyRemaining = dutyPhaseOn ? dutyOn : dutyOff; }
        }

        if (horizontalDistSq(client) <= ARRIVE_RADIUS_SQ) {
            arrived = true;
            active = false;       // stop forcing so we don't overshoot; consumer advances
            jumpTicks = 0;
            return;
        }

        // Only judge progress while actually walking — a duty-cycle pause isn't being stuck.
        boolean walking = !dutyActive() || dutyPhaseOn;
        double px = client.player.getX();
        double pz = client.player.getZ();
        if (walking && !Double.isNaN(lastX)) {
            double moved = (px - lastX) * (px - lastX) + (pz - lastZ) * (pz - lastZ);
            if (moved < STILL_EPS_SQ) {
                if (++stillTicks >= STILL_TICKS_JUMP) {
                    stillTicks = 0;
                    if (++stuckRetries > MAX_STUCK_RETRIES) {
                        stuck = true;
                        active = false;
                        jumpTicks = 0;
                        return;
                    }
                    jumpTicks = JUMP_DURATION;   // hop a ledge, then keep walking
                }
            } else {
                stillTicks = 0;
            }
        }
        lastX = px; lastZ = pz;
        if (jumpTicks > 0) jumpTicks--;
    }
}
