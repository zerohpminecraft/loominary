package net.zerohpminecraft.gen;

import com.dylibso.chicory.build.time.compiler.Config;
import com.dylibso.chicory.build.time.compiler.Generator;

import java.nio.file.Path;
import java.util.Set;

/**
 * Build-time entry point (not shipped in the mod): compiles {@code av1-decode.wasm} to JVM
 * bytecode with Chicory's build-time compiler.  Runtime compilation is not an option in-game —
 * chicory-compiler needs ASM 9.9+, and nesting ASM in the jar collides with the copy
 * fabric-loader puts on the classpath (Sodium crashed on the duplicate at preLaunch).
 *
 * <p>Args: wasmFile name targetClassFolder targetSourceFolder targetWasmFolder
 */
public final class GenAv1Machine {

    private GenAv1Machine() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.builder()
                .withWasmFile(Path.of(args[0]))
                .withName(args[1])
                .withTargetClassFolder(Path.of(args[2]))
                .withTargetSourceFolder(Path.of(args[3]))
                .withTargetWasmFolder(Path.of(args[4]))
                .build();
        Generator generator = new Generator(config);
        Set<Integer> interpreted = generator.generateResources();
        generator.generateMetaWasm(interpreted);
        generator.generateSources();
        if (!interpreted.isEmpty()) {
            System.out.println("chicory: " + interpreted.size()
                    + " function(s) fell back to the interpreter: " + interpreted);
        }
    }
}
