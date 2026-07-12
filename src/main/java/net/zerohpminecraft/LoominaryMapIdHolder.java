package net.zerohpminecraft;

/**
 * Duck interface {@code MapTextureMixin} adds to {@code MapTextureManager$MapTexture}.
 *
 * <p>Vanilla passes the map id into the MapTexture constructor but never stores it; the mixin
 * captures it so both display paths — the vanilla texture fill and ImmediatelyFast's atlas fill
 * ({@code IfMapAtlasFillMixin}) — can look up the tile's RGB claim from {@code this}.
 *
 * <p>Lives OUTSIDE the {@code net.zerohpminecraft.mixin} package on purpose: classes inside a
 * registered mixin package cannot be loaded as normal classes (IllegalClassLoadError when the
 * merged MapTexture references the interface at runtime).
 */
public interface LoominaryMapIdHolder {
    int loominary$mapId();
}
