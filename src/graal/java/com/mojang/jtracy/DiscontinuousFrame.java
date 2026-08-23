package com.mojang.jtracy;

/** Browser substitution for Tracy's discontinuous frame markers. */
public class DiscontinuousFrame {
    static final DiscontinuousFrame UNAVAILABLE = new DiscontinuousFrame(0L);

    private final long id;

    DiscontinuousFrame(long id) {
        this.id = id;
    }

    public void start() {
    }

    public void end() {
    }
}
