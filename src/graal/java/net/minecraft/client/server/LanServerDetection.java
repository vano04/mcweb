package net.minecraft.client.server;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Classpath-first shadow of Mojang's LAN-server discovery.
 *
 * <p>The JAR's detector opens a {@code MulticastSocket}, which in this image
 * reaches {@code sun.nio.ch.Net.socket0} and fails with a
 * {@code LinkageError: Found unsupported native method}. Vanilla wraps the
 * construction in {@code catch (Exception)} precisely so a machine without
 * multicast still gets a server list — but a {@code LinkageError} is an
 * {@code Error}, so it walks straight through that guard and took the whole
 * screen down with it.</p>
 *
 * <p>A browser has no LAN discovery to offer, so this reports no servers and
 * never touches a socket. The manual server list is unaffected, which is the
 * part that matters here.</p>
 */
public class LanServerDetection {

    public LanServerDetection() {
    }

    /** Always empty: nothing can broadcast to a browser tab. */
    public static class LanServerList {
        public LanServerList() {
        }

        public synchronized List<LanServer> takeDirtyServers() {
            return new ArrayList<>();
        }

        public synchronized void addServer(final String motd, final InetAddress address) {
        }
    }

    /**
     * A thread that does nothing. Still a {@code Thread} subclass, because the
     * screen calls {@code start()} and {@code interrupt()} on it, and still
     * declares {@code IOException} so the JAR's call sites keep compiling.
     */
    public static class LanServerDetector extends Thread {
        public LanServerDetector(final LanServerList servers) throws IOException {
            super("LanServerDetector");
            this.setDaemon(true);
        }

        @Override
        public void run() {
        }
    }
}
