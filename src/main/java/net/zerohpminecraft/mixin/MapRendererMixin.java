package net.zerohpminecraft.mixin;

import net.minecraft.client.render.MapRenderState;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.zerohpminecraft.MapBannerDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Priority 1500 > MapMipMapMod's 1200: HEAD fires first, clearing locked before
// MapMipMapMod's cancel/atlas-skip check. No RETURN inject — re-locking at priority
// 1500 would fire before MapMipMapMod's RETURN (priority 1200) and suppress the
// atlas commit that we just unblocked.
@Mixin(value = MapRenderer.class, priority = 1500)
public abstract class MapRendererMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void unlockForLoominary(MapIdComponent mapId, MapState mapState,
                                    MapRenderState renderState, CallbackInfo ci) {
        boolean claimed = MapBannerDecoder.isClaimed(mapId.id());
        boolean wasLocked = mapState.locked;
        boolean unlocked = false;
        if (claimed && wasLocked) {
            ((MapStateAccessor) mapState).setLocked(false);
            unlocked = true;
        }
        if (claimed) {
            MapBannerDecoder.onRenderUpdateHead(mapId.id(), wasLocked, unlocked, mapState.colors);
        }
    }
}
