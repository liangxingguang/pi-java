package com.pijava.ai.stream;

import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.utils.EcmaWhitespace;

/** Strict complete-document parsing with pi's narrow malformed-string repair family. */
final class FinalToolArguments {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FinalToolArguments() {
    }

    static ToolCallArgumentsStream.Finish parse(String json) {
        if (EcmaWhitespace.isEmpty(json)) {
            return new ToolCallArgumentsStream.Finish(Map.of(), null);
        }
        try {
            Object parsed = parseWithRepair(json);
            if (!(parsed instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            @SuppressWarnings("unchecked") // Jackson object roots have String keys.
            Map<String, Object> object = (Map<String, Object>) parsed;
            return new ToolCallArgumentsStream.Finish(JsonSnapshots.copyObject(object), null);
        } catch (IllegalArgumentException error) {
            return malformed(error.getMessage(), json);
        } catch (Exception error) {
            return malformed(error.getMessage(), json);
        }
    }

    private static Object parseWithRepair(String json) throws Exception {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception original) {
            String repaired = repair(json);
            if (!repaired.equals(json)) {
                return MAPPER.readValue(repaired, Object.class);
            }
            throw original;
        }
    }

    private static ToolCallArgumentsStream.Finish malformed(String message, String json) {
        return new ToolCallArgumentsStream.Finish(Map.of(),
                message + "\n\nReceived arguments:\n" + json);
    }

    private static String repair(String json) {
        StringBuilder repaired = new StringBuilder(json.length());
        boolean inString = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (!inString) {
                repaired.append(character);
                if (character == '"') {
                    inString = true;
                }
                continue;
            }
            if (character == '"') {
                repaired.append(character);
                inString = false;
                continue;
            }
            if (character == '\\') {
                index = repairEscape(json, index, repaired);
                continue;
            }
            if (character <= 0x1f) {
                appendControlEscape(repaired, character);
            } else {
                repaired.append(character);
            }
        }
        return repaired.toString();
    }

    private static int repairEscape(String json, int index, StringBuilder repaired) {
        if (index + 1 >= json.length()) {
            repaired.append("\\\\");
            return index;
        }
        char next = json.charAt(index + 1);
        if (next == 'u' && index + 5 < json.length() && isFourHexDigits(json, index + 2)) {
            repaired.append(json, index, index + 6);
            return index + 5;
        }
        if ("\"\\/bfnrt".indexOf(next) >= 0) {
            repaired.append('\\').append(next);
            return index + 1;
        }
        repaired.append("\\\\");
        return index;
    }

    private static boolean isFourHexDigits(String json, int start) {
        for (int index = start; index < start + 4; index++) {
            char digit = json.charAt(index);
            if (!Character.isDigit(digit) && !(digit >= 'a' && digit <= 'f')
                    && !(digit >= 'A' && digit <= 'F')) {
                return false;
            }
        }
        return true;
    }

    private static void appendControlEscape(StringBuilder repaired, char character) {
        switch (character) {
            case '\b' -> repaired.append("\\b");
            case '\f' -> repaired.append("\\f");
            case '\n' -> repaired.append("\\n");
            case '\r' -> repaired.append("\\r");
            case '\t' -> repaired.append("\\t");
            default -> repaired.append(String.format("\\u%04x", (int) character));
        }
    }
}
