package com.mojang.jtracy;

/** Browser substitution for Tracy's continuous frame markers. */
public class ContinuousFrame {
    static final ContinuousFrame UNAVAILABLE = new ContinuousFrame(0L);

    private final long id;

    ContinuousFrame(long id) {
        this.id = id;
    }

    public void mark() {
    }
}
