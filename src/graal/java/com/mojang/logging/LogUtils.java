package com.mojang.logging;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.NOPLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.event.Level;

/** Browser substitution that keeps Mojang call sites while avoiding Log4j native setup. */
public class LogUtils {
    public static final String FATAL_MARKER_ID = "FATAL";
    public static final Marker FATAL_MARKER = new BasicMarkerFactory().getMarker(FATAL_MARKER_ID);

    private static final class DeferredValue {
        private final Supplier<Object> supplier;

        private DeferredValue(Supplier<Object> supplier) {
            this.supplier = supplier;
        }

        @Override
        public String toString() {
            return String.valueOf(supplier.get());
        }
    }

    public LogUtils() {
    }

    public static boolean isLoggerActive() {
        return false;
    }

    public static void configureRootLoggingLevel(Level level) {
    }

    public static Object defer(Supplier<Object> supplier) {
        return new DeferredValue(supplier);
    }

    public static Logger getLogger() {
        return NOPLogger.NOP_LOGGER;
    }
}
