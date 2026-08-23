package dev.mcweb.graal;

import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;

/**
 * Holds the main-loop drain method. The build transplants this method's
 * bytecode into the Minecraft class with COMPUTE_MAXS, which preserves its
 * javac-generated frames.
 *
 * <p>The body uses only string-based reflection (no static fields, no lambda /
 * invokedynamic): every referenced class is a JDK class or {@link Minecraft}
 * and every member name is a string constant, so the bytecode references
 * nothing in this holder class and therefore verifies unchanged after the
 * method is transplanted into Minecraft. runAllTasks() is protected in
 * BlockableEventLoop, so it is reached via setAccessible(true). The holder is
 * never on the image classpath; only its method bytes are reused.
 */
public final class McwebDrainHolder {
    private McwebDrainHolder() {
    }

    public static void mcwebDrainMainLoop() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object instance = mcClass.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return;
            }
            // The browser runtime is cooperatively single-threaded. During the
            // Minecraft constructor the desktop game-thread marker has not
            // been installed yet, but reload apply tasks already need this
            // queue to drain. Guarding this call with isSameThread() therefore
            // stalls the initial reload and leaves fonts and atlases empty.
            Method runAll = null;
            Class<?> declaringClass = mcClass;
            while (declaringClass != null && runAll == null) {
                try {
                    runAll = declaringClass.getDeclaredMethod("runAllTasks");
                } catch (NoSuchMethodException ignored) {
                    declaringClass = declaringClass.getSuperclass();
                }
            }
            if (runAll == null) {
                return;
            }
            runAll.setAccessible(true);
            runAll.invoke(instance);
        } catch (Throwable ignored) {
            // Drain is best-effort; the wait loop retries next iteration.
        }
    }
}
