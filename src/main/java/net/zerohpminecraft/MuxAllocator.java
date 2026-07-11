package net.zerohpminecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Pure mux allocation: decides which tiles are receivers, and which byte range of each
 * receiver's payload every donor carries.  Extracted from {@code poolMuxTiles} in
 * LoominaryCommand so the algorithm is testable without Minecraft on the classpath.
 *
 * <p><b>PARITY INVARIANT</b>: {@code computeMuxAllocation} in {@code web/src/mux.ts} is a port
 * of this algorithm and MUST produce identical allocations from identical sizes — the web bakes
 * its allocation into schematic LOOM headers while the mod re-runs this one from the actual
 * payload sizes at state import, and any disagreement corrupts muxed receivers ("Src size is
 * incorrect").  {@code MuxAllocationParityTest} locks the equivalence against fixtures generated
 * by the TS implementation ({@code node web/scripts/gen-mux-fixtures.mjs}); if you change either
 * side, change the other and regenerate the fixtures.
 */
public final class MuxAllocator {

    /** Hard banner-count limit observed on 2b2t (mirrored in web/src/mux.ts). */
    public static final int MAX_CHUNKS = 63;
    /** Payload bytes carried per CJK banner name. */
    public static final int BYTES_PER_BANNER = 84;

    private MuxAllocator() {}

    /** Result of {@link #allocate}. Guest triples are {@code {rxIdx, rxOffset, len}}. */
    public static final class Allocation {
        /** True for receivers that were fully redistributed (resolved). */
        public final boolean[] receiver;
        /** Per-tile guest segments this tile hosts as a donor, in hosting order. */
        public final List<int[]>[] guestMeta;
        /** Over-budget tiles that could not be redistributed (0 = fully resolved). */
        public final int unresolved;

        Allocation(boolean[] receiver, List<int[]>[] guestMeta, int unresolved) {
            this.receiver = receiver;
            this.guestMeta = guestMeta;
            this.unresolved = unresolved;
        }
    }

    /** Maximum compressed-payload bytes a tile can hold in the given codec mode. */
    public static int budget(CodecMode mode) {
        return switch (mode) {
            case BANNER               -> MAX_CHUNKS * BYTES_PER_BANNER - 2;
            case CARPET               -> CarpetChannel.MAX_CARPET_PAYLOAD;
            case CARPET_SHADE         -> CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM;
            case CARPET_BANNERS       -> CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM;
            case CARPET_BANNERS_SHADE -> CarpetChannel.MAX_TOTAL_BYTES_LOOM;
        };
    }

    /** Maximum own-segment bytes a mux receiver can hold in its own tile channels. */
    public static int receiverOwnMax(CodecMode mode) {
        return switch (mode) {
            case BANNER               -> (MAX_CHUNKS - 1) * BYTES_PER_BANNER; // 62 payload (1 LB slot)
            case CARPET               -> CarpetChannel.MAX_CARPET_PAYLOAD;
            case CARPET_SHADE         -> CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM;
            case CARPET_BANNERS       -> CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM;
            case CARPET_BANNERS_SHADE -> CarpetChannel.MAX_TOTAL_BYTES_LOOM;
        };
    }

    /** Spare guest-byte capacity of donor {@code d}, given its current guests and cargo size. */
    private static int donorSpare(int d, int[] cargoSize,
                                  List<int[]>[] guestMeta, CodecMode mode) {
        int gc = guestMeta[d].size(); // current guest count
        return switch (mode) {
            case BANNER ->
                // Spare = remaining banner slots (minus one new MG slot) × 84 bytes
                (MAX_CHUNKS - gc - 1) * BYTES_PER_BANNER - cargoSize[d];
            case CARPET ->
                // Carpet only; guest descriptors in LOOM header (10 bytes each)
                CarpetChannel.MAX_CARPET_PAYLOAD
                        - (gc + 1) * CarpetChannel.LOOM_GUEST_DESC - cargoSize[d];
            case CARPET_SHADE ->
                // Carpet + shade; guest descriptors in LOOM header (10 bytes each)
                CarpetChannel.MAX_CARPET_SHADE_ONLY_BYTES_LOOM
                        - (gc + 1) * CarpetChannel.LOOM_GUEST_DESC - cargoSize[d];
            case CARPET_BANNERS ->
                // Carpet + overflow banners; one MG routing banner (84 bytes) per guest
                CarpetChannel.MAX_CARPET_OVERFLOW_BYTES_LOOM
                        - (gc + 1) * BYTES_PER_BANNER - cargoSize[d];
            case CARPET_BANNERS_SHADE ->
                // Carpet + shade + overflow banners; one MG routing banner (84 bytes) per guest
                CarpetChannel.MAX_TOTAL_BYTES_LOOM
                        - (gc + 1) * BYTES_PER_BANNER - cargoSize[d];
        };
    }

    /**
     * Computes the allocation for the given compressed payload sizes.  Receivers are the tiles
     * whose size exceeds the codec budget; donors are non-receiver tiles accepted by
     * {@code donorEligible} with spare capacity.
     *
     * @param sizes         compressed payload size per tile
     * @param mode          active codec mode
     * @param donorEligible extra donor filter (e.g. donor-only mode); pass {@code d -> true}
     *                      to allow any non-receiver tile
     */
    @SuppressWarnings("unchecked")
    public static Allocation allocate(int[] sizes, CodecMode mode, IntPredicate donorEligible) {
        int budget = budget(mode);
        int receiverOwnMax = receiverOwnMax(mode);

        List<Integer> receivers = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++)
            if (sizes[i] > budget) receivers.add(i);

        boolean[] resolved = new boolean[sizes.length];
        List<int[]>[] guestMeta = new List[sizes.length];
        for (int i = 0; i < sizes.length; i++) guestMeta[i] = new ArrayList<>();
        if (receivers.isEmpty()) return new Allocation(resolved, guestMeta, 0);

        int[] cargoSize = sizes.clone();

        int unresolved = 0;
        for (int rIdx : receivers) {
            int payloadLen = sizes[rIdx];
            int pos = receiverOwnMax;
            boolean fits = true;
            while (pos < payloadLen) {
                List<int[]> viable = new ArrayList<>();
                for (int d = 0; d < sizes.length; d++) {
                    if (resolved[d]) continue;
                    if (!donorEligible.test(d)) continue;
                    int spare = donorSpare(d, cargoSize, guestMeta, mode);
                    if (spare > 0) viable.add(new int[]{d, spare});
                }
                if (viable.isEmpty()) { fits = false; break; }

                int remaining = payloadLen - pos;
                int share = (remaining + viable.size() - 1) / viable.size();
                for (int[] dv : viable) {
                    if (pos >= payloadLen) break;
                    int take = Math.min(Math.min(dv[1], share), payloadLen - pos);
                    if (take <= 0) continue;
                    guestMeta[dv[0]].add(new int[]{rIdx, pos, take});
                    cargoSize[dv[0]] += take;
                    pos += take;
                }
            }
            if (!fits) {
                // Roll back this receiver's allocations on donors.
                for (int d = 0; d < sizes.length; d++) {
                    for (int g = guestMeta[d].size() - 1; g >= 0; g--) {
                        if (guestMeta[d].get(g)[0] == rIdx) {
                            cargoSize[d] -= guestMeta[d].get(g)[2];
                            guestMeta[d].remove(g);
                        }
                    }
                }
                unresolved++;
                continue;
            }
            resolved[rIdx] = true;
        }
        return new Allocation(resolved, guestMeta, unresolved);
    }
}
