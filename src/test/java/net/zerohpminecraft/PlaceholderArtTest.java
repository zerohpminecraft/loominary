package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placeholder screens must be full 128×128 frames of valid, renderable map bytes. */
class PlaceholderArtTest {

    @Test
    void allScreensAreValidMapFrames() {
        Set<Byte> valid = new HashSet<>();
        byte[] p = MapPalette.RGB_ENTRIES;
        for (int i = 0; i < p.length; i += 4) valid.add(p[i]);

        List<byte[]> screens = List.of(
                PlaceholderArt.waiting(0, 2),
                PlaceholderArt.waiting(1, 2),
                PlaceholderArt.waiting(17, 64),   // continuous-bar branch
                PlaceholderArt.decoding(0, 60),
                PlaceholderArt.decoding(60, 60),
                PlaceholderArt.decoding(3, 0),    // degenerate total
                PlaceholderArt.locked(),
                PlaceholderArt.error());

        for (byte[] s : screens) {
            assertEquals(128 * 128, s.length);
            for (byte b : s)
                assertTrue(valid.contains(b), "invalid map byte " + b);
        }
    }
}
