package net.zerohpminecraft;

/**
 * Encoding strategy for a batch of carpet tiles.
 *
 * <p>All carpet modes use the LOOM header format (LC/LS banner removed, one
 * extra overflow banner slot freed).  Banner-only mode is unchanged from the
 * original hex-indexed CJK chunk format.
 *
 * <p>Channels are listed in priority order: carpet &gt; banners &gt; shade.
 * Each mode name reflects exactly which channels it enables.
 *
 * <ul>
 *   <li>{@link #BANNER}              – 63 hex-indexed CJK banner chunks; no carpet platform.</li>
 *   <li>{@link #CARPET}              – LOOM carpet channel only; no shade, no overflow banners.</li>
 *   <li>{@link #CARPET_SHADE}        – LOOM carpet + shade channels; no overflow banners.</li>
 *   <li>{@link #CARPET_BANNERS}      – LOOM carpet + overflow banners; no shade.</li>
 *   <li>{@link #CARPET_BANNERS_SHADE}– LOOM carpet + overflow banners + shade (default).</li>
 * </ul>
 *
 * Mux pooling is supported in all five modes.
 */
public enum CodecMode {
    BANNER,
    CARPET,
    CARPET_SHADE,
    CARPET_BANNERS,
    CARPET_BANNERS_SHADE;

    /** Human-readable label for /loominary codec output. */
    public String label() {
        return switch (this) {
            case BANNER               -> "banners";
            case CARPET               -> "carpet";
            case CARPET_SHADE         -> "carpet+shade";
            case CARPET_BANNERS       -> "carpet+banners";
            case CARPET_BANNERS_SHADE -> "carpet+banners+shade (default)";
        };
    }

    public static CodecMode fromString(String s) {
        return switch (s.toLowerCase()) {
            case "banner"                        -> BANNER;
            case "carpet"                        -> CARPET;
            case "carpet+shade",
                 "carpet_shade"                  -> CARPET_SHADE;
            case "carpet+banners",
                 "carpet_banners"                -> CARPET_BANNERS;
            case "carpet+banners+shade",
                 "carpet_banners_shade"          -> CARPET_BANNERS_SHADE;
            default -> null;
        };
    }
}
