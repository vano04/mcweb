package com.mojang.jtracy;

/** Browser substitution for Tracy's GPU zone context. */
public class GpuContext {
    static final GpuContext UNAVAILABLE = new GpuContext(0);

    private final int id;

    GpuContext(int id) {
        this.id = id;
    }

    public GpuContext setName(String name) {
        return this;
    }

    public void beginZone(int color, String name, String function, String file, int line) {
    }

    public void endZone(int zone) {
    }

    public void submitQueryTimestamp(int query, long timestamp) {
    }
}
