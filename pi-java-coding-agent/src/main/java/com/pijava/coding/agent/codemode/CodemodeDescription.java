package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.codemode.api.CodeModeDeclarations;
import com.pijava.codemode.api.CodeModeIdentifier;
import com.pijava.codemode.api.CodeModeSchemaTypes.ToolSchema;

/**
 * Model-visible description of the codemode tool and its inline tool catalog.
 * The intro, globals, and section layout are verbatim from pi's {@code tool.ts:118-275}.
 */
public final class CodemodeDescription {
    /** System-prompt snippet, verbatim from {@code tool.ts:114}. */
    public static final String SNIPPET = "Run JavaScript that calls other tools";

    /** System-prompt guideline, verbatim from {@code tool.ts:116}. */
    public static final String GUIDELINE =
        "Use codemode to batch independent tool calls (Promise.allSettled), chain them, or filter large output, "
            + "instead of many separate calls.";

    private static final String INTRO =
        "Run JavaScript that calls other tools. The input is raw JavaScript (not JSON, no code fence), "
            + "run as an async function body in a QuickJS sandbox: top-level `await` and `return` work. "
            + "No Node, file system, network, or timers.\n"
            + "- `await tools.<name>({ ...args })` resolves to a string, or an object if the tool's declaration "
            + "says so, and rejects with an Error on failure. A tool whose declaration returns an object resolves "
            + "on failure too when it returned that object; check its fields. Calls still running when the script "
            + "ends are cancelled.\n"
            + "- Optional first line: `// @options: {\"max_output_tokens\": 10000, \"timeout_ms\": 60000}`";

    private static final String GLOBALS = "Globals:\n"
        + "- `text(value)`, `image(dataUrlOrImageBlock)`, `console.log(...)`, and top-level `return` add output; "
        + "`exit()` ends the script. With several text items, each starts with a `==> text N/M <==` line, "
        + "and `console` lines follow the other output in one `<console_output>` block. `image()` also saves "
        + "the image to a temp file and the result names its path.\n"
        + "- `store(key, value)` and `load(key)` keep JSON values across codemode calls.\n"
        + "- `ALL_TOOLS`, `await searchTools(query, { limit?, namespace? })`, `await describeTool(name)`, "
        + "`await describeNamespace(name)`: find unlisted tools, such as MCP tools.\n"
        + "- `models.list(provider?)`: list available models (provider, id, name, contextWindow, maxTokens, "
        + "capabilities). pi-java ships only this read-only catalog; classifiers and image generation are unavailable.";

    private static final JsonNode TEXT_OUTPUT = CodemodeToolSchemas.TEXT_OUTPUT;

    private CodemodeDescription() {
    }

    /** Returns the description without an inline catalog. */
    public static String empty() {
        return describe(List.of(), Map.of(), Set.of(), Map.of(), null);
    }

    /**
     * Builds the model-visible description with namespace grouping and budget selection.
     *
     * @param tools callable tools (codemode itself removed)
     * @param namespaces namespace by tool name
     * @param deferred tools that are callable but never listed
     * @param guidelines prompt guidelines by tool name
     * @param inlineBudget estimated tokens for tool sections; {@code null} lists all
     * @return the description
     */
    public static String describe(List<AgentTool<?, ?>> tools, Map<String, ToolNamespace> namespaces,
                                  Set<String> deferred, Map<String, List<String>> guidelines,
                                  Integer inlineBudget) {
        Map<String, CatalogGroup> groups = new LinkedHashMap<>();
        groups.put("", new CatalogGroup(null));
        for (AgentTool<?, ?> tool : tools) {
            if (tool.name().equals(CodemodeTool.NAME) || deferred.contains(tool.name())) {
                continue;
            }
            ToolNamespace namespace = namespaces.get(tool.name());
            String key = namespace == null ? "" : "ns:" + namespace.name();
            CatalogGroup group = groups.computeIfAbsent(key, ignored -> new CatalogGroup(namespace));
            String section = renderSection(tool, guidelines.getOrDefault(tool.name(), List.of()));
            group.entries.add(new CatalogEntry(tool.name(), section,
                (section.length() + 3) / 4));
        }
        List<CatalogGroup> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparing((CatalogGroup group) -> group.namespace == null)
            .reversed().thenComparing(group -> group.namespace == null
                ? "" : group.namespace.name()));
        Set<String> shown = selectCatalog(ordered, inlineBudget);

        List<String> sections = new ArrayList<>();
        sections.add(INTRO);
        sections.add(GLOBALS);
        if (tools.isEmpty()) {
            return String.join("\n\n", sections);
        }
        List<String> toolSections = new ArrayList<>();
        toolSections.add("Nested tools:");
        for (CatalogGroup group : ordered) {
            List<CatalogEntry> visible = group.entries.stream().filter(e -> shown.contains(e.name)).toList();
            if (group.namespace != null) {
                String listing = visible.size() == group.entries.size() ? ""
                    : visible.isEmpty() ? " (tools not listed)" : " (some tools not listed)";
                StringBuilder heading = new StringBuilder("## ").append(group.namespace.name()).append(listing);
                if (group.namespace.description() != null && !group.namespace.description().trim().isEmpty()) {
                    heading.append('\n').append(group.namespace.description().trim());
                }
                toolSections.add(heading.toString());
            }
            for (CatalogEntry entry : visible) {
                toolSections.add(entry.section);
            }
        }
        sections.add(String.join("\n\n", toolSections));
        return String.join("\n\n", sections);
    }

    /**
     * Returns the one-line annotation appended to a declared tool's description in {@code on} mode.
     *
     * @param tool tool being declared
     * @return description ending with the codemode call hint
     */
    public static String describeScriptCall(AgentTool<?, ?> tool) {
        String output = describeOutput(tool.name(), tool.outputSchema());
        return tool.description().trim() + "\n\nCodemode: `tools."
            + CodeModeIdentifier.toIdentifier(tool.name()) + "(args)` resolves to " + output + ".";
    }

    /**
     * Renders one tool section as pi's {@code renderToolSection} does.
     *
     * @param tool tool to render
     * @param guidelines prompt guidelines of the tool
     * @return model-visible section
     */
    public static String renderSection(AgentTool<?, ?> tool, List<String> guidelines) {
        ToolSchema declaration = toDeclaration(tool, guidelines);
        String id = CodeModeIdentifier.toIdentifier(declaration.name());
        String heading = id.equals(declaration.name())
            ? "### `" + id + "`"
            : "### `" + id + "` (`" + declaration.name() + "`)";
        return heading + "\n" + CodeModeDeclarations.renderToolSample(declaration).trim();
    }

    /**
     * Converts an {@link AgentTool} into the codemode declaration input type.
     *
     * @param tool tool to convert
     * @param guidelines prompt guidelines appended to the description
     * @return declaration
     */
    @SuppressWarnings("unchecked")
    public static ToolSchema toDeclaration(AgentTool<?, ?> tool, List<String> guidelines) {
        List<String> bullets = new ArrayList<>();
        for (String guideline : guidelines) {
            if (!guideline.trim().isEmpty()) {
                bullets.add("- " + guideline.trim());
            }
        }
        String description = tool.description();
        if (!bullets.isEmpty()) {
            description = description.trim() + "\n\n" + String.join("\n", bullets);
        }
        JsonNode input = CodemodeToolSchemas.toJson(tool.inputSchema());
        JsonNode output = tool.outputSchema() == null ? TEXT_OUTPUT : CodemodeToolSchemas.toJson(tool.outputSchema());
        return new ToolSchema(tool.name(), description, input, output);
    }

    /** Converts a tool without guidelines. */
    public static ToolSchema toDeclaration(AgentTool<?, ?> tool) {
        return toDeclaration(tool, List.of());
    }

    private static String describeOutput(String name, Map<String, Object> schema) {
        JsonNode node = schema == null ? TEXT_OUTPUT : CodemodeToolSchemas.toJson(schema);
        String type = CodeModeDeclarations.renderToolOutputType(node);
        if ("string".equals(type)) {
            return "a string";
        }
        String fields = CodemodeToolSchemas.describeFields(node, node, 1);
        if (fields == null) {
            fields = CodemodeToolSchemas.describeUnionFields(node, node);
        }
        if (fields != null) {
            return "`" + fields + "`";
        }
        if (!type.contains("\n")) {
            return "`" + type + "`";
        }
        return "a value; `await describeTool(" + quote(name) + ")` shows its type";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Set<String> selectCatalog(List<CatalogGroup> groups, Integer budget) {
        Map<String, List<CatalogEntry>> queues = new LinkedHashMap<>();
        for (CatalogGroup group : groups) {
            List<CatalogEntry> queue = new ArrayList<>(group.entries);
            queue.sort(Comparator.comparingInt(entry -> entry.cost));
            queues.put(group.key, queue);
        }
        Set<String> shown = new java.util.LinkedHashSet<>();
        if (budget == null) {
            for (List<CatalogEntry> queue : queues.values()) {
                for (CatalogEntry entry : queue) {
                    shown.add(entry.name);
                }
            }
            return shown;
        }
        int[] remaining = {budget};
        List<List<CatalogEntry>> active = new ArrayList<>(queues.values());
        while (!active.isEmpty()) {
            active = active.stream().filter(queue -> {
                if (queue.isEmpty()) {
                    return false;
                }
                CatalogEntry next = queue.get(0);
                if (next.cost > remaining[0]) {
                    return false;
                }
                remaining[0] -= next.cost;
                shown.add(next.name);
                queue.remove(0);
                return !queue.isEmpty();
            }).toList();
        }
        return shown;
    }

    private static final class CatalogEntry {
        private final String name;
        private final String section;
        private final int cost;

        private CatalogEntry(String name, String section, int cost) {
            this.name = name;
            this.section = section;
            this.cost = cost;
        }
    }

    private static final class CatalogGroup {
        private final ToolNamespace namespace;
        private final List<CatalogEntry> entries = new ArrayList<>();
        private final String key;

        private CatalogGroup(ToolNamespace namespace) {
            this.namespace = namespace;
            this.key = namespace == null ? "" : "ns:" + namespace.name();
        }
    }
}
