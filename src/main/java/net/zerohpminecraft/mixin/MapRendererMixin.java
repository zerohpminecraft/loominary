package net.zerohpminecraft.mixin;

import net.minecraft.client.render.MapRenderState;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.zerohpminecraft.MapBannerDecoder;
import net.zerohpminecraft.mixin.MapStateAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Priority 1500 > MapMipMapMod's 1200: this HEAD inject executes first,
// clearing mapState.locked before MapMipMapMod's cancel check sees it.
// Without this, MapMipMapMod's "skip updates for locked maps" optimization
// silently swallows every paintMap() call Loominary makes.
@Mixin(value = MapRenderer.class, priority = 1500)
public abstract class MapRendererMixin {

    // Track whether THIS call to update() temporarily unlocked a map.
    // Without this, the RETURN inject would lock unlocked maps (breaking animation/toggle).
    private static final ThreadLocal<Boolean> wasUnlocked = ThreadLocal.withInitial(() -> false);

    @Inject(method = "update", at = @At("HEAD"))
    private void unlockForLoominary(MapIdComponent mapId, MapState mapState, MapRenderState renderState, CallbackInfo ci) {
        if (MapBannerDecoder.isClaimed(mapId.id()) && mapState.locked) {
            ((MapStateAccessor) mapState).setLocked(false);
            wasUnlocked.set(true);
        } else {
            wasUnlocked.set(false);
        }
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void relockAfterUpdate(MapIdComponent mapId, MapState mapState, MapRenderState renderState, CallbackInfo ci) {
        if (wasUnlocked.get()) {
            ((MapStateAccessor) mapState).setLocked(true);
            wasUnlocked.set(false);
        }
    }
}
