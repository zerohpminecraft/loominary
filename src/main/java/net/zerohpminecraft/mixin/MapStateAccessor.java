package net.zerohpminecraft.mixin;
 
import net.minecraft.item.map.MapDecoration;
import net.minecraft.item.map.MapState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
 
import java.util.Map;
 
/**
 * Exposes the package-private {@code decorations} field on MapState.
 *
 * On the client, {@code banners} is always empty — only {@code decorations}
 * contains the per-marker data (including the banner name as {@code Optional<Text>}),
 * sent from the server via MapUpdateS2CPacket.
 */
@Mixin(MapState.class)
public interface MapStateAccessor {
    @Accessor("decorations")
    Map<String, MapDecoration> getDecorations();
}
 