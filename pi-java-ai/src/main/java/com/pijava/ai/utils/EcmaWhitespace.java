package com.pijava.ai.utils;

/** ECMAScript WhiteSpace and LineTerminator recognition used by pi's message-shape checks. */
public final class EcmaWhitespace {

    private EcmaWhitespace() {
    }

    /** Whether the complete string is empty or contains only ECMAScript whitespace code points. */
    public static boolean isEmpty(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!isWhitespace(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000d
                || codePoint == 0x0020
                || codePoint == 0x00a0
                || codePoint == 0x1680
                || codePoint >= 0x2000 && codePoint <= 0x200a
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202f
                || codePoint == 0x205f
                || codePoint == 0x3000
                || codePoint == 0xfeff;
    }
}
