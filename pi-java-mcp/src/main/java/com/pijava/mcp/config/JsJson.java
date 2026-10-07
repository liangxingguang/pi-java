package com.pijava.mcp.config;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * {@code JSON.stringify(value, null, indent)} over a parsed tree
 * ({@code config.ts:241}).
 *
 * <p>Jackson's pretty printer writes {@code "key" : value} and inlines arrays, so the
 * containers are written here and the scalars come from {@link JsonNode#toString()},
 * which produces the same literal as JS (quotes and escapes included).</p>
 */
public final class JsJson {

    /** The indentation of the file: the first line that starts with whitespace ({@code config.ts:239}). */
    private static final Pattern INDENT = Pattern.compile("^([ \\t]+)\\S", Pattern.MULTILINE);

    /** JS uses at most the first ten characters of the indentation. */
    private static final int MAX_INDENT = 10;

    private JsJson() {
    }

    /**
     * The indentation to write the file back with, or two spaces.
     *
     * @param text the file as read, or {@code null} when it does not exist yet
     */
    public static String detectIndent(@Nullable String text) {
        if (text == null) {
            return "  ";
        }
        var matcher = INDENT.matcher(text);
        if (!matcher.find()) {
            return "  ";
        }
        var indent = matcher.group(1);
        return indent.length() > MAX_INDENT ? indent.substring(0, MAX_INDENT) : indent;
    }

    /**
     * Serialize a tree the way JS does.
     *
     * @param node   the tree to write
     * @param indent one indentation level, never empty ({@link #detectIndent} guarantees it)
     */
    public static String stringify(JsonNode node, String indent) {
        var out = new StringBuilder();
        write(out, node, indent, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, JsonNode node, String indent, int depth) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                out.append(indent.repeat(depth + 1)).append(quote(field.getKey())).append(": ");
                write(out, field.getValue(), indent, depth + 1);
                if (fields.hasNext()) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append(indent.repeat(depth)).append('}');
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (var index = 0; index < node.size(); index++) {
                out.append(indent.repeat(depth + 1));
                write(out, node.get(index), indent, depth + 1);
                if (index + 1 < node.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append(indent.repeat(depth)).append(']');
            return;
        }
        out.append(node);
    }

    private static String quote(String value) {
        return TextNode.valueOf(value).toString();
    }
}
