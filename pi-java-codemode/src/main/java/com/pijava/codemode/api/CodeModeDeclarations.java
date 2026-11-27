package com.pijava.codemode.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.codemode.api.CodeModeSchemaTypes.DeclarationOptions;
import com.pijava.codemode.api.CodeModeSchemaTypes.RenderOptions;
import com.pijava.codemode.api.CodeModeSchemaTypes.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders TypeScript declarations for the script-visible codemode API. */
public final class CodeModeDeclarations {
    private CodeModeDeclarations() {
    }

    /** The maximum rendered input type length before it becomes {@code unknown}. */
    public static final int DEFAULT_INPUT_SCHEMA_MAX_CHARS = CodeModeSchemaSupport.DEFAULT_INPUT_SCHEMA_MAX_CHARS;

    /**
     * Converts a JSON Schema to a TypeScript type expression.
     *
     * @param schema schema document; a boolean schema is allowed
     * @return rendered TypeScript type
     */
    public static String schemaToType(JsonNode schema) {
        return schemaToType(schema, new DeclarationOptions(null));
    }

    /**
     * Converts a JSON Schema to a TypeScript type expression.
     *
     * @param schema schema document; a boolean schema is allowed
     * @param options rendering options
     * @return rendered TypeScript type
     */
    public static String schemaToType(JsonNode schema, DeclarationOptions options) {
        String type = CodeModeTypeRenderer.render(schema, true);
        int maxChars = options.maxChars() == null ? DEFAULT_INPUT_SCHEMA_MAX_CHARS : options.maxChars();
        if (type.length() <= maxChars) {
            return type;
        }
        String untagged = CodeModeTypeRenderer.render(schema, false);
        return untagged.length() <= maxChars ? untagged : "unknown";
    }

    /**
     * Resolves a local JSON Pointer {@code $ref} against a root schema.
     *
     * @param ref reference to resolve
     * @param root root schema document
     * @return target node, or {@code null} for remote, missing, or malformed references
     */
    public static JsonNode resolveSchemaRef(String ref, JsonNode root) {
        return CodeModeSchemaSupport.resolveSchemaRef(ref, root);
    }

    /**
     * Renders one tool as a member of the {@code tools} object.
     *
     * @param tool tool schema
     * @return rendered TypeScript signature
     */
    public static String renderToolSignature(ToolSchema tool) {
        return renderToolSignature(tool, new DeclarationOptions(null));
    }

    /**
     * Renders one tool as a member of the {@code tools} object.
     *
     * @param tool tool schema
     * @param options rendering options
     * @return rendered TypeScript signature
     */
    public static String renderToolSignature(ToolSchema tool, DeclarationOptions options) {
        String input = tool.inputSchema() == null ? "unknown" : schemaToType(tool.inputSchema(), options);
        return CodeModeIdentifier.toIdentifier(tool.name()) + "(args: " + input + "): Promise<"
                + renderToolOutputType(tool.outputSchema()) + ">;";
    }

    /**
     * Renders the type a tool call resolves to.
     *
     * @param schema output schema, or {@code null}
     * @return rendered TypeScript type
     */
    public static String renderToolOutputType(JsonNode schema) {
        return schema == null ? "unknown" : schemaToType(schema);
    }

    /**
     * Renders a tool's sample: description followed by its declaration.
     *
     * @param tool tool schema
     * @return model-visible sample text
     */
    public static String renderToolSample(ToolSchema tool) {
        return renderToolSample(tool, new DeclarationOptions(null));
    }

    /**
     * Renders a tool's sample: description followed by its declaration.
     *
     * @param tool tool schema
     * @param options rendering options
     * @return model-visible sample text
     */
    public static String renderToolSample(ToolSchema tool, DeclarationOptions options) {
        String declaration = "declare const tools: { " + renderToolSignature(tool, options) + " };";
        String description = tool.description() == null ? "" : tool.description().trim();
        return description + "\n\ncodemode tool declaration:\n```ts\n" + declaration + "\n```";
    }

    /**
     * Renders TypeScript declarations for tools and globals.
     *
     * @param options tools and globals to expose
     * @return model-visible declaration text
     */
    public static String renderDeclarations(RenderOptions options) {
        List<String> sections = new ArrayList<>();
        List<ToolSchema> tools = options.tools() == null ? List.of() : options.tools();
        if (!tools.isEmpty()) {
            List<String> members = new ArrayList<>();
            for (ToolSchema tool : tools) {
                members.add(docComment(tool.description(), CodeModeSchemaSupport.INDENT)
                        + CodeModeSchemaSupport.INDENT + renderToolSignature(tool));
            }
            sections.add("declare const tools: {\n" + String.join("\n", members) + "\n};");
        }
        Map<String, List<String>> namespaces = new LinkedHashMap<>();
        List<ToolSchema> globals = options.globals() == null ? List.of() : options.globals();
        for (ToolSchema global : globals) {
            int dot = global.name().indexOf('.');
            if (dot == -1) {
                String doc = docComment(global.description(), "");
                String rendered = renderGlobal(global.name(), global, "");
                sections.add(doc + "declare function " + rendered.substring(doc.length()));
                continue;
            }
            String namespace = global.name().substring(0, dot);
            List<String> members = namespaces.computeIfAbsent(namespace, key -> new ArrayList<>());
            members.add(renderGlobal(global.name().substring(dot + 1), global, CodeModeSchemaSupport.INDENT));
        }
        for (Map.Entry<String, List<String>> entry : namespaces.entrySet()) {
            sections.add("declare const " + entry.getKey() + ": {\n" + String.join("\n", entry.getValue()) + "\n};");
        }
        return String.join("\n\n", sections);
    }

    private static String renderGlobal(String head, ToolSchema global, String indent) {
        String doc = docComment(global.description(), indent);
        if (global.signature() != null) {
            return doc + indent + head + global.signature() + ";";
        }
        String input = global.inputSchema() == null ? "unknown" : schemaToType(global.inputSchema());
        String output = global.outputSchema() == null ? "unknown" : schemaToType(global.outputSchema());
        return doc + indent + head + "(args: " + input + "): Promise<" + output + ">;";
    }

    private static String docComment(String description, String indent) {
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            return "";
        }
        String escaped = text.replace("*/", "*\\/");
        List<String> lines = CodeModeSchemaSupport.splitLines(escaped);
        if (lines.size() == 1) {
            return indent + "/** " + lines.get(0) + " */\n";
        }
        List<String> rendered = new ArrayList<>();
        rendered.add(indent + "/**");
        for (String line : lines) {
            rendered.add(indent + " *" + (line.isEmpty() ? "" : " " + line));
        }
        rendered.add(indent + " */");
        return String.join("\n", rendered) + "\n";
    }
}
