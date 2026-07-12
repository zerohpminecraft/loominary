package net.zerohpminecraft;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;

import net.zerohpminecraft.av1.Av1DecodeMachine;

/**
 * Decodes the lossless AV1 animation stream (FLAG_AV1) into raw map-colour frames.
 *
 * <p>Runs {@code av1-decode.wasm} — the SAME binary the web editor decodes with — inside the
 * JVM via <a href="https://github.com/dylibso/chicory">Chicory</a> (pure Java, no native libs,
 * single cross-platform jar), pre-compiled to JVM bytecode at build time
 * ({@link net.zerohpminecraft.av1.Av1DecodeMachine}, see {@code generateAv1Machine} in
 * build.gradle).  Each decoded frame is a 128×128 monochrome plane of <em>permuted</em> palette
 * indices; {@link PalettePermutation#INV_PERM} maps them back to map-colour bytes.
 *
 * <p>The bytes after the manifest header are {@code frameCount} length-prefixed temporal units,
 * each {@code [u32 LE len][TU bytes]}.  Decode is stateful across TUs (inter-frame prediction),
 * so one {@code dec_open}/{@code dec_tu…}/{@code dec_close} cycle spans the whole animation.
 */
public final class Av1FrameDecoder {

    private static final String TAG      = "[Loominary/AV1]";
    private static final int    MAP_BYTES = 128 * 128;

    private Av1FrameDecoder() {}

    /**
     * Decodes a single-tile (128×128) stream. See {@link #decode(byte[], int, int, boolean, int, int)}.
     */
    public static byte[][] decode(byte[] full, int offset, int frameCount, boolean lossy) {
        return decode(full, offset, frameCount, lossy, 128, 128);
    }

    /** Largest length-prefixed temporal unit in the stream (for sizing the TU scratch buffer). */
    private static int maxTuLength(byte[] full, int offset) {
        int max = 0, off = offset;
        while (off + 4 <= full.length) {
            int len = (full[off] & 0xFF) | ((full[off + 1] & 0xFF) << 8)
                    | ((full[off + 2] & 0xFF) << 16) | ((full[off + 3] & 0xFF) << 24);
            off += 4;
            if (len <= 0 || off + len > full.length) break;
            if (len > max) max = len;
            off += len;
        }
        return max;
    }

    /**
     * @param full        the decompressed payload (manifest header + AV1 stream), or a bare stream
     * @param offset      byte offset where the AV1 stream begins
     * @param frameCount  expected number of frames
     * @param lossy       true for lossy-colour streams (nearest-palette in wasm, no INV_PERM);
     *                    false for lossless monochrome (permuted indices → INV_PERM)
     * @param width       frame width in pixels — 128 for per-tile streams, {@code cols*128} for
     *                    composite streams ({@link PayloadManifest#FLAG_AV1_COMPOSITE})
     * @param height      frame height in pixels — 128 or {@code rows*128}
     * @return absolute map-colour frames, each {@code byte[width*height]}
     */
    public static byte[][] decode(byte[] full, int offset, int frameCount, boolean lossy,
                                  int width, int height) {
        return decode(full, offset, frameCount, lossy, width, height, null);
    }

    /**
     * @param onFrameDecoded optional progress callback, called with the number of frames
     *                       decoded so far (1..frameCount) on the decoding thread
     */
    public static byte[][] decode(byte[] full, int offset, int frameCount, boolean lossy,
                                  int width, int height,
                                  java.util.function.IntConsumer onFrameDecoded) {
        return decodeImpl(full, offset, frameCount, lossy, width, height, onFrameDecoded, false).idxFrames();
    }

    /**
     * Both views of a decoded lossy-colour stream: {@code idxFrames} are nearest-palette map
     * bytes (what {@code MapState.colors} holds — the quantised twin), {@code rgbFrames} are the
     * reconstructed packed-RGB planes ({@code byte[width*height*3]}) before quantisation — the
     * true-colour art shown by the sRGB display path.
     */
    public record Decoded(byte[][] idxFrames, byte[][] rgbFrames) {}

    /**
     * Decodes a lossy-colour stream (FLAG2_SRGB payloads) keeping BOTH the nearest-palette map
     * bytes and the raw RGB per frame.  Lossy-only: sRGB art is always carried as an AV1 colour
     * stream.  Same framing/state rules as {@link #decode(byte[], int, int, boolean, int, int)}.
     */
    public static Decoded decodeWithRgb(byte[] full, int offset, int frameCount,
                                        int width, int height,
                                        java.util.function.IntConsumer onFrameDecoded) {
        return decodeImpl(full, offset, frameCount, true, width, height, onFrameDecoded, true);
    }

    private static Decoded decodeImpl(byte[] full, int offset, int frameCount, boolean lossy,
                                      int width, int height,
                                      java.util.function.IntConsumer onFrameDecoded, boolean wantRgb) {
        final int planeBytes = width * height;
        WasiOptions wasiOpts = WasiOptions.builder().build();
        try (WasiPreview1 wasi = WasiPreview1.builder().withOptions(wasiOpts).build()) {
            ImportValues imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
            // Av1DecodeMachine is av1-decode.wasm compiled to JVM bytecode at BUILD time by
            // Chicory (generateAv1Machine in build.gradle) — ~50× the interpreter, which took
            // ~12 s per composite frame. Runtime compilation is not an option in-game: it needs
            // ASM 9.9+, and nesting ASM collides with fabric-loader's copy.
            Instance inst = Instance.builder(Av1DecodeMachine.load())
                    .withImportValues(imports)
                    .withMachineFactory(Av1DecodeMachine::create)
                    .withStart(false)
                    .build();
            inst.export("_initialize").apply();

            Memory mem = inst.memory();
            if (inst.export("dec_open").apply()[0] != 0)
                throw new IllegalStateException("dec_open failed");

            // Lossy colour frames are re-quantised to the nearest palette entry inside the wasm,
            // using the SAME committed palette RGB the web preview used (MapPalette).
            if (lossy) {
                byte[] pal = MapPalette.RGB_ENTRIES;
                int palPtr = (int) inst.export("shim_malloc").apply(pal.length)[0];
                mem.write(palPtr, pal);
                inst.export("dec_set_palette").apply(palPtr, pal.length / 4);
            }

            int obuCap = Math.max(1 << 20, maxTuLength(full, offset));
            int obuPtr = (int) inst.export("shim_malloc").apply(obuCap)[0];
            int outPtr = (int) inst.export("shim_malloc").apply(planeBytes)[0];
            int rgbPtr = wantRgb ? (int) inst.export("shim_malloc").apply(planeBytes * 3)[0] : 0;
            byte[] invPerm = PalettePermutation.INV_PERM;

            byte[][] frames = new byte[frameCount][];
            byte[][] rgbFrames = wantRgb ? new byte[frameCount][] : null;
            int off = offset;
            int f = 0;
            for (; f < frameCount; f++) {
                if (off + 4 > full.length) break;
                int len = (full[off] & 0xFF) | ((full[off + 1] & 0xFF) << 8)
                        | ((full[off + 2] & 0xFF) << 16) | ((full[off + 3] & 0xFF) << 24);
                off += 4;
                if (len <= 0 || off + len > full.length) break;

                mem.write(obuPtr, java.util.Arrays.copyOfRange(full, off, off + len));
                off += len;

                long wrote = wantRgb
                        ? inst.export("dec_tu_full").apply(obuPtr, len, outPtr, planeBytes,
                                                           rgbPtr, planeBytes * 3)[0]
                        : inst.export("dec_tu").apply(obuPtr, len, outPtr, planeBytes)[0];
                if (wrote != planeBytes) {
                    System.err.println(TAG + " dec_tu frame " + f + " returned " + wrote);
                    break;
                }
                byte[] plane = mem.readBytes(outPtr, planeBytes);
                byte[] frame;
                if (lossy) {
                    frame = plane; // already real map bytes (nearest-palette in wasm)
                } else {
                    frame = new byte[planeBytes];
                    for (int p = 0; p < planeBytes; p++) frame[p] = invPerm[plane[p] & 0xFF];
                }
                frames[f] = frame;
                if (wantRgb) rgbFrames[f] = mem.readBytes(rgbPtr, planeBytes * 3);
                if (onFrameDecoded != null) onFrameDecoded.accept(f + 1);
            }
            inst.export("dec_close").apply();

            if (f == 0) throw new IllegalStateException("AV1 decode produced no frames");
            if (f < frameCount) {
                byte[][] trimmed = new byte[f][];
                System.arraycopy(frames, 0, trimmed, 0, f);
                byte[][] trimmedRgb = null;
                if (wantRgb) {
                    trimmedRgb = new byte[f][];
                    System.arraycopy(rgbFrames, 0, trimmedRgb, 0, f);
                }
                return new Decoded(trimmed, trimmedRgb);
            }
            return new Decoded(frames, rgbFrames);
        }
    }
}
