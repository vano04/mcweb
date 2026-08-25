package com.mojang.jtracy;

/** Browser substitution for Tracy's plot series. */
public class Plot {
    static final Plot UNAVAILABLE = new Plot(0L);

    private final long handle;

    Plot(long handle) {
        this.handle = handle;
    }

    public void setValue(double value) {
    }
}
