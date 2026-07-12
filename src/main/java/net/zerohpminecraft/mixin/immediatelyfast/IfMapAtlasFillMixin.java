package net.zerohpminecraft.mixin.immediatelyfast;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.zerohpminecraft.LoominaryMapIdHolder;
import net.zerohpminecraft.MapBannerDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * sRGB display path, ImmediatelyFast side.  Applied only when IF is loaded (see
 * {@code LoominaryMixinPlugin}).
 *
 * <p>IF's {@code map_atlas_generation} replaces the vanilla per-map texture: it cancels
 * {@code MapTexture.updateTexture} at HEAD and fills its atlas slot straight from
 * {@code MapColor.getRenderColor(state.colors[i])} in an x-outer/y-inner 128×128 loop, then
 * region-uploads.  MapMipMapMod hooks the END of that same handler to {@code glGenerateMipmap}.
 *
 * <p>We wrap the {@code getRenderColor} call inside IF's handler (via MixinSquared — the same
 * mechanism MapMipMapMod itself uses on this handler): for RGB-claimed maps each call returns
 * the true-colour pixel instead of the palette colour.  IF then uploads OUR pixels and MMM's
 * mipmaps are generated from them — sRGB art stays full colour under IF and IF+MMM with no
 * injection-ordering games.
 *
 * <p>Pixel position: the wrap gets no coordinates, but IF's fill is exactly 16384 calls per
 * handler run in deterministic x-outer/y-inner order, so a cursor reset at handler HEAD maps
 * call n → (x = n/128, y = n%128) → colors index x + y*128.  If IF ever changes the loop shape
 * the cursor overruns 16384 and we fall back to the palette colour (visible but safe) and log
 * once.
 */
@Mixin(targets = "net.minecraft.client.texture.MapTextureManager$MapTexture", priority = 1500)
public abstract class IfMapAtlasFillMixin {

    @Unique private int loominary$fillCursor;
    @Unique private static boolean loominary$driftLogged;

    @TargetHandler(
            mixin = "net.raphimc.immediatelyfast.injection.mixins.map_atlas_generation.MixinMapTextureManager_MapTexture",
            name = "updateAtlasTexture"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"))
    private void loominary$resetCursor(CallbackInfo ci) {
        this.loominary$fillCursor = 0;
    }

    @TargetHandler(
            mixin = "net.raphimc.immediatelyfast.injection.mixins.map_atlas_generation.MixinMapTextureManager_MapTexture",
            name = "updateAtlasTexture"
    )
    @WrapOperation(
            method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/block/MapColor;getRenderColor(I)I")
    )
    private int loominary$rgbPixel(int colorByte, Operation<Integer> original) {
        byte[] rgb = MapBannerDecoder.currentRgbFrame(((LoominaryMapIdHolder) this).loominary$mapId());
        if (rgb == null || rgb.length < 128 * 128 * 3) return original.call(colorByte);
        int n = this.loominary$fillCursor++;
        if (n >= 128 * 128) {
            if (!loominary$driftLogged) {
                loominary$driftLogged = true;
                System.err.println("[Loominary] ImmediatelyFast atlas fill made more than 16384"
                        + " getRenderColor calls per run — loop shape changed; sRGB tiles fall"
                        + " back to the quantised palette under IF until this is updated.");
            }
            return original.call(colorByte);
        }
        int x = n >> 7, y = n & 127;           // IF's loop is x-outer, y-inner
        int i = (x + (y << 7)) * 3;            // colors index x + y*128
        return 0xFF000000
                | ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF);
    }
}
