package dev.mcweb.graal.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Minimal SLF4J provider routing Mojang's logging to System.out (the browser
 * console). The log4j stack is excluded from the wasm image and the
 * META-INF/services registrations of any bridge are not embedded, so SLF4J
 * falls back to its NOP logger — silently swallowing EVERY Mojang log line,
 * including texture-upload / resource-reload failures that are otherwise
 * invisible in the console. Prints INFO and above (DEBUG/TRACE suppressed).
 * Registered via src/graal/resources/META-INF/services; the image build must
 * embed that resource (see -H:IncludeResources in build.gradle).
 */
public final class McwebSlf4jProvider implements SLF4JServiceProvider {
    public static final String REQUESTED_API_VERSION = "2.0.99";

    private final ILoggerFactory loggerFactory = new ILoggerFactory() {
        private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

        @Override
        public Logger getLogger(String name) {
            return loggers.computeIfAbsent(name, ConsoleLogger::new);
        }
    };
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // Nothing to configure.
    }

    static final class ConsoleLogger extends AbstractLogger {
        ConsoleLogger(String name) {
            this.name = name;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(
                Level level,
                Marker marker,
                String messagePattern,
                Object[] arguments,
                Throwable throwable
        ) {
            System.out.println("[MC]" + level + " " + name + ": "
                    + MessageFormatter.basicArrayFormat(messagePattern, arguments));
            if (throwable != null) {
                System.out.println("[MC]   exception: " + throwable);
                StackTraceElement[] frames = throwable.getStackTrace();
                int limit = Math.min(frames.length, 24);
                for (int i = 0; i < limit; i++) {
                    System.out.println("[MC]     at " + frames[i]);
                }
                Throwable cause = throwable.getCause();
                if (cause != null && cause != throwable) {
                    System.out.println("[MC]   caused by: " + cause);
                }
            }
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return true;
        }
    }
}
