package dev.mcweb.graal;

import net.minecraft.util.Util;

/** Minimal direct-call probe for the transformed Util.make overload. */
public final class WasmLMUtilProbeMain {
    private WasmLMUtilProbeMain() {
    }

    public static void main(String[] args) {
        Object value = new Object();
        Object returned = Util.make(value, ignored -> {
        });
        if (returned != value) {
            throw new IllegalStateException("Util.make did not preserve identity");
        }
        System.out.println("WasmLM Util.make probe: OK");
    }
}
