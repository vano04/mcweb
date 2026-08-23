package com.mojang.jtracy;

/** Browser substitution for Tracy's profiling zone. */
public class Zone implements AutoCloseable {
    static final Zone UNAVAILABLE = new Zone(0);

    private final int id;

    Zone(int id) {
        this.id = id;
    }

    public Zone addText(String text) {
        return this;
    }

    public Zone setColor(int color) {
        return this;
    }

    public Zone addValue(long value) {
        return this;
    }

    @Override
    public void close() {
    }
}
