package com.pijava.codemode.api;

/** The identifier a script uses for a tool. */
public final class CodeModeIdentifier {
    private CodeModeIdentifier() {
    }

    /**
     * Replaces characters that are not valid in a JavaScript identifier with {@code '_'}.
     *
     * @param name original tool name
     * @return a valid identifier; {@code '_'} for empty input
     */
    public static String toIdentifier(String name) {
        StringBuilder identifier = new StringBuilder();
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            char[] chars = Character.toChars(codePoint);
            boolean valid = true;
            for (int i = 0; i < chars.length; i++) {
                char character = chars[i];
                boolean validChar = identifier.isEmpty()
                        ? isAsciiLetter(character) || character == '_' || character == '$'
                        : isAsciiLetter(character) || isAsciiDigit(character) || character == '_' || character == '$';
                if (!validChar) {
                    valid = false;
                    break;
                }
            }
            identifier.append(valid ? new String(chars) : "_");
            offset += Character.charCount(codePoint);
        }
        return identifier.isEmpty() ? "_" : identifier.toString();
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }
}
