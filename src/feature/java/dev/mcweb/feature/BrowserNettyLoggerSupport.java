package dev.mcweb.feature;

import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Isolated so the netty reference links only when netty is actually on the
 * image classpath; the caller catches the linkage error otherwise.
 */
final class BrowserNettyLoggerSupport {
    private BrowserNettyLoggerSupport() {
    }

    static void install() {
        InternalLoggerFactory.setDefaultFactory(new BrowserNettyLoggerFactory());
    }
}
