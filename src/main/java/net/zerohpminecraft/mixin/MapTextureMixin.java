package net.zerohpminecraft.mixin;

import net.minecraft.client.texture.MapTextureManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.map.MapState;
import net.zerohpminecraft.LoominaryMapIdHolder;
import net.zerohpminecraft.MapBannerDecoder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * sRGB display path, vanilla side: when a map is RGB-claimed (a decoded FLAG2_SRGB tile), fill
 * the per-map texture from the true-colour frame instead of letting vanilla palette-lookup
 * {@code MapState.colors} — colors[] holds only the nearest-palette twin for that tile.
 *
 * <p>ImmediatelyFast's map atlas replaces this path entirely (it nulls {@code texture} in the
 * ctor and cancels {@code updateTexture} at HEAD, priority 1100, filling its atlas straight from
 * {@code colors[]}); {@code IfMapAtlasFillMixin} handles that route.  The {@code image == null}
 * guard below makes this injection a no-op in atlas mode REGARDLESS of which HEAD callback runs
 * first — never cancel in that case, or IF's handler is starved and the atlas goes stale.
 */
@Mixin(targets = "net.minecraft.client.texture.MapTextureManager$MapTexture")
public abstract class MapTextureMixin implements LoominaryMapIdHolder {

    @Shadow @Final private NativeImageBackedTexture texture;
    @Shadow private boolean needsUpdate;

    @Unique private int loominary$mapId = -1;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void loominary$captureMapId(MapTextureManager manager, int id, MapState state,
                                        CallbackInfo ci) {
        this.loominary$mapId = id;
    }

    @Override
    public int loominary$mapId() {
        return this.loominary$mapId;
    }

    @Inject(method = "updateTexture", at = @At("HEAD"), cancellable = true)
    private void loominary$paintRgb(CallbackInfo ci) {
        if (!this.needsUpdate) return;
        byte[] rgb = MapBannerDecoder.currentRgbFrame(this.loominary$mapId);
        if (rgb == null || rgb.length < 128 * 128 * 3) return;
        NativeImage image = this.texture == null ? null : this.texture.getImage();
        if (image == null) return; // ImmediatelyFast atlas mode — IfMapAtlasFillMixin's route
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int i = (x + y * 128) * 3;
                image.setColorArgb(x, y, 0xFF000000
                        | ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF));
            }
        }
        this.texture.upload();
        this.needsUpdate = false;
        ci.cancel();
    }
}
