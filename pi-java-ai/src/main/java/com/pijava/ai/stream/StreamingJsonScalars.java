package com.pijava.ai.stream;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Number and literal recognition shared by complete and pending streaming scalars. */
final class StreamingJsonScalars {

    static final Object UNDEFINED = new Object();

    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final Pattern WITHOUT_EXPONENT = Pattern.compile(
            "(-?(?:0|[1-9]\\d*)(?:\\.\\d+)?)[eE][+-]?");

    private StreamingJsonScalars() {
    }

    static boolean isDelimiter(char character) {
        return " \t\n\r,:{}[]\"".indexOf(character) >= 0;
    }

    static boolean isHexDigit(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
    }

    static String simpleEscape(char character) {
        return switch (character) {
            case '"' -> "\"";
            case '\\' -> "\\";
            case '/' -> "/";
            case 'b' -> "\b";
            case 'f' -> "\f";
            case 'n' -> "\n";
            case 'r' -> "\r";
            case 't' -> "\t";
            default -> null;
        };
    }

    static Object complete(String token) {
        return switch (token) {
            case "true" -> true;
            case "false" -> false;
            case "null" -> null;
            default -> NUMBER.matcher(token).matches() ? number(token) : UNDEFINED;
        };
    }

    static Object pending(String token) {
        if ("true".startsWith(token)) {
            return true;
        }
        if ("false".startsWith(token)) {
            return false;
        }
        if ("null".startsWith(token)) {
            return null;
        }
        if (NUMBER.matcher(token).matches()) {
            return number(token);
        }
        Matcher mantissa = WITHOUT_EXPONENT.matcher(token);
        return mantissa.matches() ? number(mantissa.group(1)) : UNDEFINED;
    }

    private static Object number(String token) {
        if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
            return Double.parseDouble(token);
        }
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException ignored) {
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException tooLarge) {
                return new BigInteger(token);
            }
        }
    }
}
