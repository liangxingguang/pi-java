package com.pijava.codemode.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Input records for declaration rendering. */
public final class CodeModeSchemaTypes {
    private CodeModeSchemaTypes() {
    }

    /**
     * A tool or global as needed by declaration rendering.
     *
     * @param name script-visible name
     * @param description optional doc comment text
     * @param inputSchema optional single-argument schema
     * @param outputSchema optional resolved-value schema
     * @param signature explicit TypeScript parameter list and return type, or {@code null}
     */
    public record ToolSchema(String name, String description, JsonNode inputSchema, JsonNode outputSchema,
            String signature) {
        /**
         * Creates a tool or global without an explicit signature.
         *
         * @param name script-visible name
         * @param description optional doc comment text
         * @param inputSchema optional single-argument schema
         * @param outputSchema optional resolved-value schema
         */
        public ToolSchema(String name, String description, JsonNode inputSchema, JsonNode outputSchema) {
            this(name, description, inputSchema, outputSchema, null);
        }
    }

    /**
     * Options for schema-to-type rendering.
     *
     * @param maxChars maximum rendered type length, or {@code null} for no limit
     */
    public record DeclarationOptions(Integer maxChars) {
    }

    /**
     * Options for declaration rendering.
     *
     * @param tools tools exposed on the {@code tools} object
     * @param globals globals exposed as top-level functions or namespaces
     */
    public record RenderOptions(List<ToolSchema> tools, List<ToolSchema> globals) {
    }
}
