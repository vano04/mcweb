package com.ibm.icu.text;

/** Browser shadow of ICU's {@code BidiRun}: one whole-string LTR run. */
public class BidiRun {
    public int start;
    public int limit;
    public byte direction;

    public BidiRun() {
        this(0, 0, (byte) 0);
    }

    BidiRun(int start, int limit, byte direction) {
        this.start = start;
        this.limit = limit;
        this.direction = direction;
    }

    public int getStart() {
        return start;
    }

    public int getLimit() {
        return limit;
    }

    public int getLength() {
        return limit - start;
    }

    public byte getDirection() {
        return direction;
    }

    public byte getEmbeddingLevel() {
        return 0;
    }

    public boolean isOddRun() {
        return (direction & 1) != 0;
    }

    public boolean isEvenRun() {
        return (direction & 1) == 0;
    }
}
