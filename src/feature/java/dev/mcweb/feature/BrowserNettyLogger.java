package dev.mcweb.feature;

import io.netty.util.internal.logging.AbstractInternalLogger;
import io.netty.util.internal.logging.InternalLogLevel;

/** No-op Netty logger: keeps java.util.logging out of the image universe. */
final class BrowserNettyLogger extends AbstractInternalLogger {
    private static final long serialVersionUID = 1L;

    BrowserNettyLogger(String name) {
        super(name);
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public void trace(String msg) {
    }

    @Override
    public void trace(String format, Object arg) {
    }

    @Override
    public void trace(String format, Object argA, Object argB) {
    }

    @Override
    public void trace(String format, Object... arguments) {
    }

    @Override
    public void trace(String msg, Throwable t) {
    }

    @Override
    public void trace(Throwable t) {
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void debug(String msg) {
    }

    @Override
    public void debug(String format, Object arg) {
    }

    @Override
    public void debug(String format, Object argA, Object argB) {
    }

    @Override
    public void debug(String format, Object... arguments) {
    }

    @Override
    public void debug(String msg, Throwable t) {
    }

    @Override
    public void debug(Throwable t) {
    }

    @Override
    public boolean isInfoEnabled() {
        return false;
    }

    @Override
    public void info(String msg) {
    }

    @Override
    public void info(String format, Object arg) {
    }

    @Override
    public void info(String format, Object argA, Object argB) {
    }

    @Override
    public void info(String format, Object... arguments) {
    }

    @Override
    public void info(String msg, Throwable t) {
    }

    @Override
    public void info(Throwable t) {
    }

    @Override
    public boolean isWarnEnabled() {
        return false;
    }

    @Override
    public void warn(String msg) {
    }

    @Override
    public void warn(String format, Object arg) {
    }

    @Override
    public void warn(String format, Object... arguments) {
    }

    @Override
    public void warn(String format, Object argA, Object argB) {
    }

    @Override
    public void warn(String msg, Throwable t) {
    }

    @Override
    public void warn(Throwable t) {
    }

    @Override
    public boolean isErrorEnabled() {
        return false;
    }

    @Override
    public void error(String msg) {
    }

    @Override
    public void error(String format, Object arg) {
    }

    @Override
    public void error(String format, Object argA, Object argB) {
    }

    @Override
    public void error(String format, Object... arguments) {
    }

    @Override
    public void error(String msg, Throwable t) {
    }

    @Override
    public void error(Throwable t) {
    }

    @Override
    public void log(InternalLogLevel level, String msg) {
    }

    @Override
    public void log(InternalLogLevel level, String format, Object arg) {
    }

    @Override
    public void log(InternalLogLevel level, String format, Object argA, Object argB) {
    }

    @Override
    public void log(InternalLogLevel level, String format, Object... arguments) {
    }

    @Override
    public void log(InternalLogLevel level, String msg, Throwable t) {
    }

    @Override
    public void log(InternalLogLevel level, Throwable t) {
    }
}
