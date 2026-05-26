package net.zerohpminecraft;

/**
 * Encoding strategy for a batch of carpet tiles.
 *
 * <p>All carpet modes use the LOOM header format (LC/LS banner removed, one
 * extra overflow banner slot freed).  Banner-only mode is unchanged from the
 * original hex-indexed CJK chunk format.
 *
 * <ul>
 *   <li>{@link #BANNER}       – 63 hex-indexed CJK banner chunks; no carpet platform.</li>
 *   <li>{@link #CARPET}       – LOOM carpet channel + overflow banners; shade disabled.</li>
 *   <li>{@link #CARPET_SHADE} – LOOM carpet + shade + overflow banners (default).</li>
 *   <li>{@link #CARPET_ONLY}  – LOOM carpet + shade; no overflow banners.</li>
 * </ul>
 *
 * Mux pooling is supported in all four modes.
 */
public enum CodecMode {
    BANNER,
    CARPET,
    CARPET_SHADE,
    CARPET_ONLY;

    /** Human-readable label for /loominary codec output. */
    public String label() {
        return switch (this) {
            case BANNER       -> "banner-only";
            case CARPET       -> "carpet (no shade)";
            case CARPET_SHADE -> "carpet + shade (default)";
            case CARPET_ONLY  -> "carpet-only (no overflow banners)";
        };
    }

    public static CodecMode fromString(String s) {
        return switch (s.toLowerCase()) {
            case "banner"        -> BANNER;
            case "carpet"        -> CARPET;
            case "carpet+shade",
                 "carpet_shade"  -> CARPET_SHADE;
            case "carpet-only",
                 "carpet_only"   -> CARPET_ONLY;
            default -> null;
        };
    }
}
