package com.ibm.icu.text;

/**
 * Browser shadow of ICU's {@code Bidi}. The real class loads Unicode property
 * data (uprops/ubidi binaries) through byte-swapping NIO bulk reads, which hit
 * {@code Unsafe.copySwapMemory0} — unimplemented in Web Image — from its
 * static initializer (ExceptionInInitializerError at first font use). The
 * client only references the API below (Font, FormattedBidiReorder); this
 * shadow treats every string as a single left-to-right run, which is exactly
 * correct for the English UI. Constant fields are inlined into the client's
 * bytecode at its compile time, so their values here are linkage formality.
 */
public final class Bidi {
    public static final int LTR = 0;
    public static final int RTL = 1;
    public static final int DIRECTION_DEFAULT_RIGHT_TO_LEFT = 124;
    public static final int DIRECTION_DEFAULT_LEFT_TO_RIGHT = 125;
    public static final int DIRECTION_LEFT_TO_RIGHT = 126;
    public static final int DIRECTION_RIGHT_TO_LEFT = 127;
    public static final int REORDER_DEFAULT = 0;
    public static final int REORDER_NUMBERS_SPECIAL = 1;
    public static final int REORDER_GROUP_NUMBERS_WITH_R = 2;
    public static final int REORDER_RUNS_ONLY = 3;
    public static final int REORDER_INVERSE_NUMBERS_AS_L = 6;
    public static final int REORDER_INVERSE_LIKE_DIRECT = 7;
    public static final int REORDER_INVERSE_FOR_NUMBERS_SPECIAL = 8;
    public static final int DO_MIRRORING = 2;
    public static final int INSERT_LRM_FOR_NUMBERS = 4;
    public static final int REMOVE_BIDI_CONTROLS = 8;
    public static final int OUTPUT_REVERSE = 16;

    private final String text;

    public Bidi(String paragraph, int flags) {
        this.text = paragraph == null ? "" : paragraph;
    }

    public void setReorderingMode(int reorderingMode) {
        // Everything is one LTR run; nothing to reorder.
    }

    public boolean isLeftToRight() {
        return true;
    }

    public boolean isRightToLeft() {
        return false;
    }

    public boolean isMixed() {
        return false;
    }

    public int getLength() {
        return text.length();
    }

    public byte getDirectionality() {
        return 0;
    }

    public int countRuns() {
        return text.isEmpty() ? 0 : 1;
    }

    public BidiRun getVisualRun(int run) {
        return new BidiRun(0, text.length(), (byte) 0);
    }

    public String writeReordered(int doReordering) {
        return text;
    }

    public static String reorderVisually(String text) {
        return text;
    }
}
