package dev.mcweb.feature;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

final class BrowserNettyLoggerFactory extends InternalLoggerFactory {
    @Override
    protected InternalLogger newInstance(String name) {
        return new BrowserNettyLogger(name);
    }
}
