package com.ibm.icu.text;

/**
 * Browser shadow of ICU's {@code ArabicShaping}. The real shaper loads Arabic
 * shaping tables through the same byte-swapping binary path that fails in Web
 * Image. Latin/Cyrillic UI text needs no joining: shaping is the identity.
 */
public final class ArabicShaping {
    public static final int LETTERS_NOOP = 0;
    public static final int LETTERS_SHAPE = 1;
    public static final int LETTERS_SHAPE_TASHKEEL_ISOLATED = 2;
    public static final int LETTERS_MASK = 3;
    public static final int TEXT_DIRECTION_LOGICAL = 4;
    public static final int TEXT_DIRECTION_VISUAL_LTR = 0;
    public static final int TEXT_DIRECTION_MASK = 4;
    public static final int LENGTH_GROW_SHAPED = 8;
    public static final int LENGTH_FIXED_SPACES_NEAR = 16;
    public static final int LENGTH_FIXED_SPACES_AT_END = 32;
    public static final int LENGTH_FIXED_SPACES_AT_BEGINNING = 48;
    public static final int LENGTH_MASK = 48;
    public static final int SEEN_TWOCELL_NEAR = 64;
    public static final int SEEN_MASK = 224;
    public static final int TASHKEEL_NOOP = 0;
    public static final int TASHKEEL_BEGIN = 256;
    public static final int TASHKEEL_END = 512;
    public static final int TASHKEEL_REPLACE = 768;
    public static final int TASHKEEL_MASK = 768;
    public static final int LAMALEF_NOOP = 0;
    public static final int LAMALEF_BEGIN = 65536;
    public static final int LAMALEF_NEAR = 131072;
    public static final int LAMALEF_END = 196608;
    public static final int LAMALEF_MASK = 196608;
    public static final int DIGITS_NOOP = 0;
    public static final int DIGITS_EN2AN = 32;
    public static final int DIGITS_AN2EN = 64;
    public static final int DIGITS_EN2AN_INIT_LR = 96;
    public static final int DIGITS_EN2AN_INIT_AL = 128;
    public static final int DIGITS_MASK = 224;
    public static final int DIGIT_TYPE_AN = 0;
    public static final int DIGIT_TYPE_AN_EXTENDED = 256;
    public static final int DIGIT_TYPE_MASK = 256;
    public static final int SPACES_RELATIVE_TO_TEXT_MASK = 1048576;
    public static final int SPACES_RELATIVE_TO_TEXT_BEGINNING = 1048576;

    public ArabicShaping(int shapingOptions) {
    }

    public String shape(String input) throws ArabicShapingException {
        return input;
    }

    public void shape(char[] source, int sourceStart, int sourceLength,
            char[] destination, int destStart, int destLength) throws ArabicShapingException {
        System.arraycopy(source, sourceStart, destination, destStart,
                Math.min(sourceLength, destLength));
    }
}
