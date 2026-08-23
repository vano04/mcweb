package dev.mcweb.graal;


/**
 * Converts the browser host's single callable input boundary into the retained
 * GLFW callback interfaces in BrowserInputCompat.
 */
public final class BrowserInputBridge {
    private BrowserInputBridge() {
    }

    public static BrowserInputDispatcher dispatcher() {
        return BrowserInputBridge::dispatch;
    }

    /**
     * Input is dispatched from a DOM event, not from inside the frame pump, so
     * a throw here unwinds into JavaScript and disappears: the screen simply
     * does not change and nothing is logged. That is what a "dead button" in
     * this port has always been. Name it instead.
     */
    private static void dispatch(
            String name,
            double first,
            double second,
            double third,
            double fourth
    ) {
        try {
            dispatchOrReport(name, first, second, third, fourth);
        } catch (Throwable failure) {
            // Throwable, not Exception: the usual culprits are LinkageError and
            // NoClassDefFoundError from a class the image never initialised.
            dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                    "input:" + name,
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
        }
    }

    private static void dispatchOrReport(
            String name,
            double first,
            double second,
            double third,
            double fourth
    ) {
        // Every branch below except "resize" is a person touching the game;
        // Minecraft's inactivity throttle has to hear about it or it clamps the
        // frame rate to 30 (then 10) while they are still playing.
        if (!"resize".equals(name)) {
            BrowserFramePump.noteInput();
        }
        switch (name) {
            case "key" -> BrowserInputCompat.dispatchKey(
                    (int) first,
                    (int) second,
                    (int) third,
                    (int) fourth
            );
            case "charInput" -> BrowserInputCompat.dispatchChar((int) first);
            case "cursorPos" -> BrowserInputCompat.dispatchCursorPos(first, second);
            // Clicking used to arm InlineExecutorService's per-task enter/exit
            // reporting, which then logged two lines for every task the game
            // ran -- ~29k lines during one world load, enough to overflow the
            // console pipe and hide real failures. Arm it deliberately instead.
            case "mouseButton" -> BrowserInputCompat.dispatchMouseButton(
                    (int) first,
                    (int) second,
                    (int) third
            );
            case "scroll" -> BrowserInputCompat.dispatchScroll(first, second);
            case "resize" -> BrowserWindowCompat.dispatchResize((int) first, (int) second);
            default -> {
            }
        }
    }
}
