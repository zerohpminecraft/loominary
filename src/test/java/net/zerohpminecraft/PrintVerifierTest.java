package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import net.zerohpminecraft.PrintVerifier.Finding;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the per-column capture-verification logic ({@link PrintVerifier#classify}). Colours are item
 * raw ids; {@code -1} = none. {@code floorY} is the print layer. The invariant under test: the highest
 * non-transparent block a map would scan must be the schematic's intended carpet, at the intended Y.
 */
class PrintVerifierTest {

    private static final int FLOOR = 64;
    private static final int WHITE = 10;   // arbitrary distinct carpet ids
    private static final int RED   = 11;

    @Test
    void flatCarpet_present_correct_isOk() {
        // Schematic wants WHITE at the floor; real world has exactly that.
        assertEquals(Finding.OK,
                PrintVerifier.classify(FLOOR, WHITE, FLOOR, WHITE, true, FLOOR));
    }

    @Test
    void carpetOnCarpet_isErrant() {
        // Intended top at FLOOR, but the highest real block is a carpet one above (double-place).
        assertEquals(Finding.ERRANT,
                PrintVerifier.classify(FLOOR, WHITE, FLOOR + 1, WHITE, true, FLOOR));
    }

    @Test
    void solidBlockAboveCarpet_isObstruction() {
        // A non-carpet, non-transparent block sits above the intended carpet → reported, not broken.
        assertEquals(Finding.OBSTRUCTION,
                PrintVerifier.classify(FLOOR, WHITE, FLOOR + 5, /*stone*/ 99, false, FLOOR));
    }

    @Test
    void glassAboveCarpet_isOk() {
        // Glass is MapColor.CLEAR, so the scan skips it: the highest non-CLEAR block is still the
        // carpet at the intended Y. The caller passes realTop=FLOOR (glass never becomes realTop).
        assertEquals(Finding.OK,
                PrintVerifier.classify(FLOOR, WHITE, FLOOR, WHITE, true, FLOOR));
    }

    @Test
    void wrongColour_isMissing() {
        assertEquals(Finding.MISSING,
                PrintVerifier.classify(FLOOR, WHITE, FLOOR, RED, true, FLOOR));
    }

    @Test
    void carpetNeverPlaced_isMissing() {
        assertEquals(Finding.MISSING,
                PrintVerifier.classify(FLOOR, WHITE, Integer.MIN_VALUE, -1, false, FLOOR));
    }

    @Test
    void shadeStaircase_topAtIntendedHeight_isOk() {
        // Intended data carpet two above the floor (a height-2 shade column); real matches.
        assertEquals(Finding.OK,
                PrintVerifier.classify(FLOOR + 2, WHITE, FLOOR + 2, WHITE, true, FLOOR));
    }

    @Test
    void shadeStaircase_shortStack_isMissing() {
        // Intended top at FLOOR+2 but only the floor carpet is placed → wrong height ⇒ wrong shade.
        assertEquals(Finding.MISSING,
                PrintVerifier.classify(FLOOR + 2, WHITE, FLOOR, WHITE, true, FLOOR));
    }

    @Test
    void strayCarpetInHole_isErrant() {
        // Schematic wants no carpet here (a hole in the art), but a carpet was placed → errant.
        assertEquals(Finding.ERRANT,
                PrintVerifier.classify(Integer.MIN_VALUE, -1, FLOOR, WHITE, true, FLOOR));
    }

    @Test
    void hole_withTerrainButNoCarpet_isOk() {
        // Nothing intended and no stray carpet (only e.g. terrain below the floor) → not flagged.
        assertEquals(Finding.OK,
                PrintVerifier.classify(Integer.MIN_VALUE, -1, Integer.MIN_VALUE, -1, false, FLOOR));
    }
}
