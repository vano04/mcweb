package dev.mcweb.graal;

import net.minecraft.server.Bootstrap;

/**
 * Minimal WasmLM link probe for the first Minecraft bootstrap boundary.
 *
 * <p>This deliberately does not construct the client or start carriers. It
 * catches image/link regressions in {@code Util} and {@code Bootstrap} in a
 * small image before spending the full Minecraft build time.</p>
 */
public final class WasmLMBootstrapProbeMain {
    private WasmLMBootstrapProbeMain() {
    }

    public static void main(String[] args) {
        Bootstrap.bootStrap();
        System.out.println("WasmLM bootstrap probe: OK");
    }
}
