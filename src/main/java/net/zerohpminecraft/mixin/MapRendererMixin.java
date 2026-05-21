package net.zerohpminecraft.mixin;

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

    @Inject(method = "updateTexture", at = @At("HEAD"))
    private void unlockForLoominary(MapIdComponent mapId, MapState mapState, CallbackInfo ci) {
        if (MapBannerDecoder.isClaimed(mapId.id()) && mapState.locked) {
            ((MapStateAccessor) mapState).setLocked(false);
        }
    }

    @Inject(method = "updateTexture", at = @At("RETURN"))
    private void relockAfterUpdate(MapIdComponent mapId, MapState mapState, CallbackInfo ci) {
        if (MapBannerDecoder.isClaimed(mapId.id())) {
            ((MapStateAccessor) mapState).setLocked(true);
        }
    }
}
