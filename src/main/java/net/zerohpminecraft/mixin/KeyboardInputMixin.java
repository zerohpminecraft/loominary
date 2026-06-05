package net.zerohpminecraft.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.zerohpminecraft.LoominaryMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces player movement for loominary's autonomous walkers (the duty-cycle
 * {@code AutoWalkHandler} and the target-directed {@code WaypointMover}), arbitrated by
 * {@link LoominaryMovement}.
 *
 * <p>Injected at the tail of {@link KeyboardInput#tick()}, i.e. after the movement input
 * has been computed from the key bindings. Doing it here (instead of pressing the forward
 * key binding) means it survives mods that rewrite the movement keys every tick — notably
 * the Litematica printer, which resets forward/back/left/right from the physical keyboard
 * state, clobbering any programmatic key press. We override the *result*, so it sticks.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void loominaryForceForward(CallbackInfo ci) {
        LoominaryMovement.ForcedInput fi = LoominaryMovement.beforeMovement();
        if (fi == null) return;
        PlayerInput p = this.playerInput;
        this.playerInput = new PlayerInput(fi.forward(), p.backward(), p.left(), p.right(),
                fi.jump() || p.jump(), p.sneak(), p.sprint());
        if (fi.forward()) this.movementForward = 1.0f;
    }
}
