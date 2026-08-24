package net.minecraft.client;

/**
 * Browser seam for Minecraft's desktop frame limiter.
 *
 * The browser host schedules {@link Minecraft#runTick(boolean)} from either
 * {@code requestAnimationFrame} (VSync on) or a yielding timer (VSync off).
 * Mojang's desktop limiter instead busy-waits with {@code LockSupport.parkNanos}
 * and {@code Thread.onSpinWait}; retaining that loop burns the browser thread polling the
 * clock. {@code BrowserFramePump} publishes the real
 * {@code FramerateLimitTracker} value to the host whenever it changes.
 */
public final class FramerateLimiter {

    public FramerateLimiter() {
    }

    public static void limitDisplayFPS(int requestedLimit) {
        // BrowserFramePump applies this effective limit outside runTick.
    }
}
