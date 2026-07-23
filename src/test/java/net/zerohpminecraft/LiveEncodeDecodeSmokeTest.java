package net.zerohpminecraft;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast, boot-free smoke test for the encode → place-format → decode roundtrip.
 *
 * <p>This is the CI-safe floor of the smoke-test harness (see {@code SMOKE_TESTS.md}).
 * It does NOT boot Minecraft — it drives the same real mod classes the
 * {@code /loominary import} command uses ({@link PngToMapColors#convert} to map
 * bytes, the banner-name chunk split, and {@link MapBannerDecoder#reassemblePayload})
 * against a PNG written into a throwaway sandbox directory, and asserts the decoded
 * map bytes are pixel-identical to the encoded ones.
 *
 * <p>The full live in-game smoke run (boots a real headless client, drives
 * {@code /loominary import} in a sandboxed integrated-server world, and asserts the
 * resulting {@link PayloadState}) lives behind the {@code runSmokeTest} Gradle run and
 * {@code scripts/smoke-test.sh}; it is intentionally NOT part of {@code ./gradlew build}
 * because it downloads the Minecraft assets and renders under software GL.
 *
 * <p>Tagged {@code "smoke"} so it can be selected/excluded independently.
 */
@Tag("smoke")
class LiveEncodeDecodeSmokeTest {

    private static final int MAP_BYTES = 128 * 128;
    private static final int CHUNK_SIZE = 48; // 50-char banner name = 2 hex index + 48 payload

    /** Exactly the banner-name chunk format the placement pipeline produces. */
    private static List<String> makeChunks(byte[] mapColors) {
        byte[] compressed = Zstd.compress(mapColors, Zstd.maxCompressionLevel());
        String b64 = Base64.getEncoder().encodeToString(compressed);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i * CHUNK_SIZE < b64.length(); i++) {
            int end = Math.min((i + 1) * CHUNK_SIZE, b64.length());
            chunks.add(String.format("%02x", i) + b64.substring(i * CHUNK_SIZE, end));
        }
        return chunks;
    }

    /** A deterministic, non-trivial test image (gradient + blocks) — many distinct colors. */
    private static BufferedImage sampleImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / (w - 1);
                int g = (y * 255) / (h - 1);
                int b = ((x + y) * 255) / (w + h - 2);
                if (((x / 16) + (y / 16)) % 2 == 0) { r = 255 - r; }
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    @Test
    void image_encodes_and_decodes_back_to_identical_map_bytes(@TempDir Path sandbox) throws Exception {
        // 1. Write a source PNG into an isolated sandbox dir (mirrors loominary_data/).
        Path src = sandbox.resolve("smoke-source.png");
        ImageIO.write(sampleImage(200, 140), "png", src.toFile());
        assertTrue(java.nio.file.Files.exists(src), "sandbox source PNG should exist");

        // 2. Encode exactly as /loominary import does: scale-to-128 + nearest-palette match.
        BufferedImage loaded = ImageIO.read(src.toFile());
        byte[] mapColors = PngToMapColors.convert(loaded, /*legalOnly=*/ true);

        assertEquals(MAP_BYTES, mapColors.length, "map color array must be exactly 128x128");
        assertTrue(PngToMapColors.countDistinct(mapColors) > 8,
                "a real image should map to many distinct palette colors, got "
                        + PngToMapColors.countDistinct(mapColors));

        // 3. Split into banner-name chunks (the on-the-wire placement format).
        List<String> chunks = makeChunks(mapColors);
        assertFalse(chunks.isEmpty(), "encoding must produce at least one chunk");
        for (String c : chunks) {
            assertTrue(c.length() <= 2 + CHUNK_SIZE, "chunk over banner-name capacity: " + c.length());
            assertTrue(c.substring(0, 2).matches("[0-9a-f]{2}"), "chunk needs a 2-hex index: " + c);
        }

        // 4. Decode back through the real decoder and assert a pixel-perfect roundtrip.
        byte[] decoded = MapBannerDecoder.reassemblePayload(new ArrayList<>(chunks));
        assertArrayEquals(mapColors, decoded,
                "decoded map bytes must be identical to the encoded map bytes");
    }
}
