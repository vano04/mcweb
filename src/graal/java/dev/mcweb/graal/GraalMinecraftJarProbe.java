package dev.mcweb.graal;

import com.mojang.blaze3d.platform.DisplayData;
import java.util.OptionalInt;

/**
 * GraalVM Web Image reachability checkpoint for the untouched Minecraft JAR.
 */
public final class GraalMinecraftJarProbe {
    private GraalMinecraftJarProbe() {
    }

    public static void main(String[] args) {
        DisplayData display = new DisplayData(
                89,
                166,
                OptionalInt.of(77),
                OptionalInt.empty(),
                false
        ).withSize(89, 166);

        int argb = 0xFF000000
                | (display.width() << 16)
                | (display.height() << 8)
                | display.fullscreenWidth().orElseThrow();
        if (argb != 0xFF59A64D) {
            throw new IllegalStateException("Minecraft JAR bytecode returned an unexpected value");
        }

        System.out.printf("Minecraft 26.2 JAR checkpoint: 0x%08X%n", argb);
    }
}
