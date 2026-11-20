package com.pijava.ai.validation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Resolves local JSON Schema references ({@code #} and {@code #/...}) against one root schema.
 *
 * <p>pi-schema is compiled with {@code unresolvedRefs: "reject"}: a reference that names nothing
 * rejects only the value at its place. External documents are not loaded; they count as unresolved.
 */
final class SchemaRefResolver {

    /** A resolved schema node; {@code unresolved} is true when the reference names nothing here. */
    record Resolution(Object node, boolean unresolved) {}

    private final Object root;

    SchemaRefResolver(Object root) {
        this.root = root;
    }

    /** Resolve a {@code $ref} value. */
    Resolution resolve(String ref) {
        if (ref.isEmpty() || ref.equals("#")) {
            return new Resolution(root, false);
        }
        if (!ref.startsWith("#/")) {
            return new Resolution(null, true);
        }
        Object node = root;
        for (String rawToken : ref.substring(2).split("/", -1)) {
            String token = decodeToken(rawToken);
            if (node instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return new Resolution(null, true);
                }
                node = map.get(token);
            } else if (node instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return new Resolution(null, true);
                }
                if (index < 0 || index >= list.size()) {
                    return new Resolution(null, true);
                }
                node = list.get(index);
            } else {
                return new Resolution(null, true);
            }
        }
        return new Resolution(node, false);
    }

    /** JSON Pointer token decoding: percent escapes first, then {@code ~1} and {@code ~0}. */
    private static String decodeToken(String token) {
        String decoded = percentDecode(token);
        return decoded.replace("~1", "/").replace("~0", "~");
    }

    private static String percentDecode(String token) {
        if (token.indexOf('%') < 0) {
            return token;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '%' && i + 2 < token.length()) {
                try {
                    bytes.write(Integer.parseInt(token.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // Not a percent escape: keep the character.
                }
            }
            byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            for (byte b : raw) {
                bytes.write(b);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
