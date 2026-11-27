package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.codemode.api.CodeModeIdentifier;
import com.pijava.codemode.api.CodeModeSchemaTypes;
import com.pijava.coding.agent.extension.ExtensionToolInfo;
import com.pijava.coding.agent.extension.toolsearch.Bm25Ranker;
import com.pijava.coding.agent.extension.toolsearch.ToolSearchDocument;

/** Discovery globals: {@code ALL_TOOLS}, {@code searchTools}, and the describe helpers. */
public final class CodemodeDiscovery {
    /** Default result limit of {@code searchTools()}, from pi's {@code DEFAULT_TOOL_SEARCH_LIMIT}. */
    public static final int DEFAULT_LIMIT = 8;

    private CodemodeDiscovery() {
    }

    /**
     * Builds all discovery globals for one execution.
     *
     * @param tools callable tools except codemode
     * @param samples rendered tool samples by raw name
     * @param namespaces namespace by tool name
     * @param guidelines prompt guidelines by tool name
     * @param toolInfos pi-style tool metadata, used for BM25 documents
     * @return globals keyed by script-visible name
     */
    public static Map<String, com.pijava.codemode.api.ScriptTool> globals(
            List<AgentTool<?, ?>> tools, Map<String, String> samples,
            Map<String, ToolNamespace> namespaces, Map<String, List<String>> guidelines,
            Map<String, ExtensionToolInfo> toolInfos) {
        Map<String, com.pijava.codemode.api.ScriptTool> globals = new LinkedHashMap<>();
        globals.put("searchTools", args -> search(tools, samples, namespaces, toolInfos, args));
        globals.put("describeTool", args -> describeTool(tools, samples, args));
        globals.put("describeNamespace", args -> describeNamespace(tools, namespaces, args));
        return globals;
    }

    /** Returns declarations for the {@code ALL_TOOLS} prelude. */
    public static List<CodeModeSchemaTypes.ToolSchema> declarations() {
        return List.of(
            CodemodeToolSchemas.global("ALL_TOOLS", "Array of { name, description } for every callable tool.",
                ": Array<{ name: string, description: string }>"),
            CodemodeToolSchemas.global("searchTools", "Rank callable tools by a query.",
                "(query: string, options?: { limit?: number, namespace?: string }): Promise<"
                    + "Array<{ name: string, description: string }>>"),
            CodemodeToolSchemas.global("describeTool", "Return a callable tool's declaration by name.",
                "(name: string): Promise<string | undefined>"),
            CodemodeToolSchemas.global("describeNamespace", "Describe a namespace and list its tools.",
                "(name: string): Promise<object | undefined>"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> allTools(List<AgentTool<?, ?>> tools, Map<String, String> samples,
                                                      Map<String, List<String>> guidelines) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AgentTool<?, ?> tool : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", CodeModeIdentifier.toIdentifier(tool.name()));
            String description = CodemodeDescription.toDeclaration(tool,
                guidelines.getOrDefault(tool.name(), List.of())).description();
            entry.put("description", samples.containsKey(tool.name()) ? samples.get(tool.name()) : description);
            entries.add(entry);
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Object search(List<AgentTool<?, ?>> tools, Map<String, String> samples,
                                 Map<String, ToolNamespace> namespaces,
                                 Map<String, ExtensionToolInfo> toolInfos, Object args) {
        Object[] call = array(args);
        if (call.length == 0 || !(call[0] instanceof String query)) {
            throw new IllegalArgumentException("searchTools() expects a query string");
        }
        int limit = DEFAULT_LIMIT;
        String namespace = null;
        if (call.length > 1 && call[1] instanceof Map<?, ?> options) {
            if (options.get("limit") instanceof Number number) {
                limit = number.intValue();
            }
            if (options.get("namespace") instanceof String name) {
                namespace = name;
            }
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("searchTools() limit must be a positive integer");
        }
        List<ToolSearchDocument> documents = new ArrayList<>();
        List<AgentTool<?, ?>> candidates = new ArrayList<>();
        for (AgentTool<?, ?> tool : tools) {
            ToolNamespace toolNamespace = namespaces.get(tool.name());
            if (namespace != null && (toolNamespace == null || !isNamespaceName(toolNamespace, namespace))) {
                continue;
            }
            candidates.add(tool);
            ExtensionToolInfo info = toolInfos.get(tool.name());
            documents.add(info == null
                ? new ToolSearchDocument(tool.name(), tool.description(),
                    toolNamespace == null ? "" : toolNamespace.name())
                : ToolSearchDocument.of(info, toolNamespace));
        }
        Bm25Ranker ranker = new Bm25Ranker();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Bm25Ranker.Match match : ranker.rank(query, documents, limit)) {
            AgentTool<?, ?> tool = candidates.stream()
                .filter(candidate -> candidate.name().equals(match.name())).findFirst().orElse(null);
            if (tool == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", CodeModeIdentifier.toIdentifier(tool.name()));
            entry.put("description", samples.getOrDefault(tool.name(), tool.description()));
            results.add(entry);
        }
        return results;
    }

    private static Object describeTool(List<AgentTool<?, ?>> tools, Map<String, String> samples, Object args) {
        Object[] call = array(args);
        if (call.length == 0 || !(call[0] instanceof String name)) {
            throw new IllegalArgumentException("describeTool() expects a tool name");
        }
        for (AgentTool<?, ?> tool : tools) {
            if (tool.name().equals(name) || CodeModeIdentifier.toIdentifier(tool.name()).equals(name)) {
                return samples.getOrDefault(tool.name(), tool.description());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object describeNamespace(List<AgentTool<?, ?>> tools,
                                            Map<String, ToolNamespace> namespaces, Object args) {
        Object[] call = array(args);
        if (call.length == 0 || !(call[0] instanceof String name)) {
            throw new IllegalArgumentException("describeNamespace() expects a namespace name");
        }
        ToolNamespace found = null;
        List<String> names = new ArrayList<>();
        for (AgentTool<?, ?> tool : tools) {
            ToolNamespace namespace = namespaces.get(tool.name());
            if (namespace == null || !isNamespaceName(namespace, name)) {
                continue;
            }
            if (found == null) {
                found = namespace;
            }
            names.add(CodeModeIdentifier.toIdentifier(tool.name()));
        }
        if (found == null) {
            return null;
        }
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("name", found.name());
        if (found.description() != null && !found.description().isEmpty()) {
            description.put("description", found.description());
        }
        if (found.instructions() != null && !found.instructions().isEmpty()) {
            description.put("instructions", found.instructions());
        }
        description.put("tools", names);
        return description;
    }

    /** Whether a query names the namespace by name or alias. */
    public static boolean isNamespaceName(ToolNamespace namespace, String query) {
        String queryId = CodeModeIdentifier.toIdentifier(query);
        List<String> names = new ArrayList<>();
        if (namespace.name() != null) {
            names.add(namespace.name());
        }
        if (namespace.aliases() != null) {
            names.addAll(namespace.aliases());
        }
        return names.stream().anyMatch(name -> name.equals(query)
            || CodeModeIdentifier.toIdentifier(name).equals(queryId));
    }

    private static Object[] array(Object args) {
        if (args instanceof Object[] values) {
            return values;
        }
        if (args instanceof List<?> values) {
            return values.toArray();
        }
        return new Object[0];
    }
}
