package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class PalettePermutationTest {

    @Test
    void tablesAreInverseBijections() {
        boolean[] seen = new boolean[256];
        for (int i = 0; i < 256; i++) {
            int mapByte = PalettePermutation.INV_PERM[i] & 0xFF;
            assertFalse(seen[mapByte], "duplicate mapByte " + mapByte + " in INV_PERM");
            seen[mapByte] = true;
            assertEquals(i, PalettePermutation.PERM[mapByte] & 0xFF,
                    "PERM/INV_PERM not inverse at permIndex " + i);
        }
        for (int b = 0; b < 256; b++) assertTrue(seen[b], "mapByte " + b + " missing from permutation");
    }

    @Test
    void crc32MatchesCommittedConstant() {
        // Recompute CRC32 over the Java INV_PERM table and compare to the constant the
        // generator wrote — this locks the Java table against silent corruption and, since
        // the same generator wrote the identical constant into web/src/palette-perm.ts,
        // transitively locks web/mod parity.
        CRC32 crc = new CRC32();
        crc.update(PalettePermutation.INV_PERM);
        assertEquals(PalettePermutation.PERM_CRC32, crc.getValue());
    }
}
