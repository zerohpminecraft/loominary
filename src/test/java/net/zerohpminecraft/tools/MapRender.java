package net.zerohpminecraft.tools;

import net.zerohpminecraft.MapPalette;
import net.zerohpminecraft.PlaceholderArt;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders 128×128 map-color byte frames to PNGs/GIFs for the documentation —
 * exactly what a map shows in-game, without launching the game. Lives in test
 * sources so it never ships in the jar. Run via:
 *
 *   ./gradlew renderMapPreviews          → docs/wiki/assets/game/status-*.png
 *
 * or as a library from other doc tooling: {@link #toImage}, {@link #writePng},
 * {@link #writeGif}.
 */
public final class MapRender {

    private static final Map<Byte, Integer> RGB = new HashMap<>();
    static {
        byte[] p = MapPalette.RGB_ENTRIES;
        for (int i = 0; i < p.length; i += 4)
            RGB.put(p[i], ((p[i + 1] & 0xFF) << 16) | ((p[i + 2] & 0xFF) << 8) | (p[i + 3] & 0xFF));
    }

    private MapRender() {}

    /** Map bytes → RGB image at {@code scale}× (nearest neighbor, like the in-game texture). */
    public static BufferedImage toImage(byte[] colors, int scale) {
        BufferedImage img = new BufferedImage(128 * scale, 128 * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128 * scale; y++)
            for (int x = 0; x < 128 * scale; x++) {
                Integer rgb = RGB.get(colors[(y / scale) * 128 + (x / scale)]);
                img.setRGB(x, y, rgb != null ? rgb : 0xFF00FF); // magenta = invalid byte
            }
        return img;
    }

    public static void writePng(byte[] colors, int scale, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        ImageIO.write(toImage(colors, scale), "png", out.toFile());
    }

    /** Frames → looping animated GIF (delay per frame in ms). */
    public static void writeGif(byte[][] frames, int scale, int delayMs, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (byte[] frame : frames) {
                BufferedImage img = toImage(frame, scale);
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(img), writer.getDefaultWriteParam());
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("transparentColorIndex", "0");
                gce.setAttribute("delayTime", String.valueOf(Math.max(2, delayMs / 10)));
                IIOMetadataNode apps = child(root, "ApplicationExtensions");
                IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                app.setAttribute("applicationID", "NETSCAPE");
                app.setAttribute("authenticationCode", "2.0");
                app.setUserObject(new byte[]{1, 0, 0}); // loop forever
                apps.appendChild(app);
                meta.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(img, null, meta), writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++)
            if (parent.item(i).getNodeName().equals(name)) return (IIOMetadataNode) parent.item(i);
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    /** Renders the status screens (and anything else docs need) into args[0]. */
    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "docs/wiki/assets/game");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        Map<String, byte[]> screens = new LinkedHashMap<>();
        screens.put("status-waiting", PlaceholderArt.waiting(1, 2));
        screens.put("status-decoding", PlaceholderArt.decoding(25, 60));
        screens.put("status-locked", PlaceholderArt.locked());
        screens.put("status-error", PlaceholderArt.error());
        for (Map.Entry<String, byte[]> e : screens.entrySet())
            writePng(e.getValue(), scale, outDir.resolve(e.getKey() + ".png"));

        // Animated decode-progress GIF for the wiki's animated-art page.
        byte[][] progress = new byte[21][];
        for (int i = 0; i <= 20; i++) progress[i] = PlaceholderArt.decoding(i * 3, 60);
        writeGif(progress, scale, 150, outDir.resolve("status-decoding-anim.gif"));

        System.out.println("MapRender: wrote " + (screens.size() + 1) + " files to " + outDir);
    }
}
