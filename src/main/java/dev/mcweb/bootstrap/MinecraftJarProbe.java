package dev.mcweb.bootstrap;

import com.mojang.blaze3d.platform.DisplayData;
import java.util.OptionalInt;
import org.teavm.jso.JSBody;

/**
 * First closed-world checkpoint for the port.
 *
 * <p>This class deliberately executes a method from the untouched Minecraft
 * 26.2 client JAR. It proves TeaVM can read Java 25 Mojang bytecode before the
 * much larger GLFW/GPU platform boundary is made reachable.</p>
 */
public final class MinecraftJarProbe {
    private MinecraftJarProbe() {
    }

    public static void main(String[] args) {
        DisplayData display = new DisplayData(
                89,
                166,
                OptionalInt.of(77),
                OptionalInt.empty(),
                false
        ).withSize(89, 166);

        int blue = display.fullscreenWidth().orElseThrow();
        int clearColor = 0xFF000000
                | (display.width() << 16)
                | (display.height() << 8)
                | blue;
        int expected = 0xFF59A64D;

        if (clearColor != expected) {
            throw new IllegalStateException("Minecraft ARGB bytecode returned an unexpected value");
        }

        showJarCheckpoint(
                clearColor,
                "com.mojang.blaze3d.platform.DisplayData",
                "26.2 / Java 25 classfile 69"
        );
    }

    @JSBody(params = {"argb", "method", "version"},
            script = "window.mcWebJarCheckpoint(argb, method, version);")
    private static native void showJarCheckpoint(int argb, String method, String version);
}
