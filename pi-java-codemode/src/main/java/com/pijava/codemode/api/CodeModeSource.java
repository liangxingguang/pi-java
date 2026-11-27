package com.pijava.codemode.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Codemode source format: JavaScript, optionally preceded by one options line. */
public final class CodeModeSource {
    /** Prefix of the optional options line. */
    public static final String CODEMODE_OPTIONS_PREFIX = "// @options:";

    /** Lark grammar for the shape of the optional options line. */
    public static final String CODEMODE_SOURCE_GRAMMAR = """
            start: options_source | plain_source
            options_source: OPTIONS_LINE NEWLINE SOURCE
            plain_source: SOURCE

            OPTIONS_LINE: /[ \\t]*\\/\\/ @options:[^\\r\\n]*/
            NEWLINE: /\\r?\\n/
            SOURCE: /[\\s\\S]+/
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUPPORTED_FIELDS_TEXT = "`max_output_tokens` and `timeout_ms`";
    private static final long MAX_TIMEOUT_MS = 2_147_483_647L;

    private CodeModeSource() {
    }

    /** Options parsed from the first line. */
    public record Options(Long maxOutputTokens, Long timeoutMs) {
    }

    /** The script and its options. */
    public record Parsed(String code, Options options) {
    }

    /**
     * Splits an optional first-line options directive from the script.
     *
     * @param input source text
     * @return source with the options line replaced by an empty line, plus the parsed options
     * @throws CodeModeSourceException if the input, options, or line placement is invalid
     */
    public static Parsed parse(String input) throws CodeModeSourceException {
        if (input.trim().isEmpty()) {
            throw new CodeModeSourceException("Expected JavaScript source text (non-empty). Provide JS only, "
                    + "optionally with a first line `// @options: {\"max_output_tokens\": 1000}`.");
        }
        String[] lines = input.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            if (stripCarriageReturn(lines[i]).stripLeading().startsWith(CODEMODE_OPTIONS_PREFIX)) {
                throw new CodeModeSourceException("The " + CODEMODE_OPTIONS_PREFIX
                        + " line must be the first line of the input; found one on line " + (i + 1));
            }
        }
        int newline = input.indexOf('\n');
        String firstLine = stripCarriageReturn(lines[0]);
        String trimmed = firstLine.stripLeading();
        if (!trimmed.startsWith(CODEMODE_OPTIONS_PREFIX)) {
            return new Parsed(input, new Options(null, null));
        }
        String code = newline == -1 ? "" : input.substring(newline);
        if (code.trim().isEmpty()) {
            throw new CodeModeSourceException("The @options line must be followed by JavaScript source "
                    + "on subsequent lines");
        }
        return new Parsed(code, parseOptions(trimmed.substring(CODEMODE_OPTIONS_PREFIX.length()).trim()));
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static Options parseOptions(String directive) throws CodeModeSourceException {
        if (directive.isEmpty()) {
            throw new CodeModeSourceException("@options must be a JSON object with supported fields "
                    + SUPPORTED_FIELDS_TEXT);
        }
        JsonNode value;
        try {
            value = MAPPER.readTree(directive);
        } catch (JsonProcessingException exception) {
            throw new CodeModeSourceException("@options must be valid JSON with supported fields "
                    + SUPPORTED_FIELDS_TEXT + ": " + jacksonMessage(exception));
        }
        if (value == null || !value.isObject()) {
            throw new CodeModeSourceException("@options must be a JSON object with supported fields "
                    + SUPPORTED_FIELDS_TEXT);
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            String key = field.getKey();
            if (!key.equals("max_output_tokens") && !key.equals("timeout_ms")) {
                throw new CodeModeSourceException(
                        "@options only supports " + SUPPORTED_FIELDS_TEXT + "; got `" + key + "`");
            }
        }
        JsonNode maxOutputTokens = value.get("max_output_tokens");
        JsonNode timeoutMs = value.get("timeout_ms");
        Long parsedMax = null;
        Long parsedTimeout = null;
        if (maxOutputTokens != null && !maxOutputTokens.isNull()) {
            if (!isSafeInteger(maxOutputTokens)) {
                throw new CodeModeSourceException(
                        "@options field `max_output_tokens` must be a non-negative safe integer");
            }
            parsedMax = maxOutputTokens.longValue();
        }
        if (timeoutMs != null && !timeoutMs.isNull()) {
            if (!isSafeInteger(timeoutMs) || timeoutMs.longValue() == 0 || timeoutMs.longValue() > MAX_TIMEOUT_MS) {
                throw new CodeModeSourceException("@options field `timeout_ms` must be a positive integer up to "
                        + MAX_TIMEOUT_MS);
            }
            parsedTimeout = timeoutMs.longValue();
        }
        return new Options(parsedMax, parsedTimeout);
    }

    private static boolean isSafeInteger(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            return false;
        }
        long number = node.longValue();
        return number >= 0 && number <= 9_007_199_254_740_991L;
    }

    private static String jacksonMessage(JsonProcessingException exception) {
        String message = exception.getOriginalMessage();
        return message == null ? exception.toString() : message;
    }
}
