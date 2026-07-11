package net.zerohpminecraft;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks web↔mod mux-allocation equivalence: {@code computeMuxAllocation} (web/src/mux.ts) and
 * {@link MuxAllocator} MUST produce identical allocations from identical payload sizes.  The web
 * bakes its allocation into schematic LOOM headers at export while the mod re-runs the Java
 * allocation from the actual payload sizes at state import — if they diverge, muxed receivers
 * reassemble garbage in-game ("Src size is incorrect").
 *
 * <p>The fixtures are the TS implementation's real output; regenerate with
 * {@code node web/scripts/gen-mux-fixtures.mjs} whenever either implementation changes.
 */
class MuxAllocationParityTest {

    @Test
    void javaAllocationMatchesTsFixtures() throws Exception {
        int scenarios = 0;
        try (InputStream in = getClass().getResourceAsStream("/mux/mux_alloc_fixtures.txt")) {
            assertNotNull(in, "fixture missing — run: node web/scripts/gen-mux-fixtures.mjs");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String line;
            String name = null;
            CodecMode codec = null;
            int[] sizes = null;
            int expectedUnresolved = -1;
            List<String> expectedTiles = new ArrayList<>();

            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (line.startsWith("scenario ")) {
                    name = line.substring(9);
                    codec = null; sizes = null; expectedUnresolved = -1;
                    expectedTiles.clear();
                } else if (line.startsWith("codec ")) {
                    codec = CodecMode.valueOf(line.substring(6));
                } else if (line.startsWith("sizes ")) {
                    String[] parts = line.substring(6).split(",");
                    sizes = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) sizes[i] = Integer.parseInt(parts[i]);
                } else if (line.startsWith("unresolved ")) {
                    expectedUnresolved = Integer.parseInt(line.substring(11));
                } else if (line.startsWith("tile ")) {
                    expectedTiles.add(line);
                } else if (line.equals("end")) {
                    assertNotNull(codec, name + ": codec");
                    assertNotNull(sizes, name + ": sizes");
                    checkScenario(name, codec, sizes, expectedUnresolved, expectedTiles);
                    scenarios++;
                }
            }
        }
        assertTrue(scenarios >= 10, "suspiciously few scenarios parsed: " + scenarios);
    }

    private static void checkScenario(String name, CodecMode codec, int[] sizes,
                                      int expectedUnresolved, List<String> expectedTiles) {
        MuxAllocator.Allocation alloc = MuxAllocator.allocate(sizes, codec, d -> true);
        assertEquals(expectedUnresolved, alloc.unresolved, name + ": unresolved");
        assertEquals(expectedTiles.size(), sizes.length, name + ": tile line count");

        int ownMax = MuxAllocator.receiverOwnMax(codec);
        for (int ti = 0; ti < sizes.length; ti++) {
            boolean isReceiver = alloc.receiver[ti];
            boolean isDonor    = !alloc.guestMeta[ti].isEmpty();
            String role = isReceiver ? "receiver" : isDonor ? "donor" : "normal";
            int ownBytes = isReceiver ? Math.min(ownMax, sizes[ti]) : sizes[ti];

            StringBuilder guests = new StringBuilder();
            for (int[] g : alloc.guestMeta[ti]) {
                if (guests.length() > 0) guests.append(';');
                guests.append(g[0]).append(':').append(g[1]).append(':').append(g[2]);
            }
            String actual = "tile " + ti + " " + role + " own=" + ownBytes
                    + " guests=" + (guests.length() == 0 ? "-" : guests);
            assertEquals(expectedTiles.get(ti), actual, name + ": tile " + ti);
        }
    }
}
