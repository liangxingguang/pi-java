package com.pijava.agent.tool;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pijava.ai.api.ToolDefinition;

/**
 * Registry of tools available to the agent.
 *
 * <p>Thread-safe. Tools are registered by name; the registry provides
 * lookup by name, enumeration for LLM tool definitions, and execution
 * via the agent harness.</p>
 */
public class ToolRegistry {

    private final ConcurrentMap<String, AgentTool<?, ?>> tools = new ConcurrentHashMap<>();
    private final ApprovalHandler approvalHandler;

    /**
     * @param approvalHandler nullable; tool calls that require approval
     *        are passed through this handler. If null, all tools auto-approve.
     */
    public ToolRegistry(ApprovalHandler approvalHandler) {
        this.approvalHandler = approvalHandler;
    }

    /** Register a tool. */
    public void register(AgentTool<?, ?> tool) {
        tools.put(tool.name(), tool);
    }

    /** Register all tools from a list. */
    public void registerAll(List<AgentTool<?, ?>> toolList) {
        for (var t : toolList) tools.put(t.name(), t);
    }

    /** Lookup a tool by name. Returns null if not found. */
    public AgentTool<?, ?> get(String name) {
        return tools.get(name);
    }

    /** All registered tool names. */
    public Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** All registered tools. */
    public Collection<AgentTool<?, ?>> all() {
        return List.copyOf(tools.values());
    }

    /**
     * Execute a tool call by name.
     *
     * @throws SecurityException        if the tool call is not approved
     * @throws IllegalArgumentException if the tool is not found
     * @throws Exception                on tool execution failure (harness catches and wraps)
     */
    public ToolResult<?> execute(
            String toolName, String toolCallId,
            Map<String, Object> arguments,
            AbortSignal signal, ToolUpdateCallback<?> onUpdate,
            ToolContext context) throws Exception {
        var tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        // Phase 2b: check approval before execution
        if (approvalHandler != null && !approvalHandler.approve(toolName, arguments)) {
            throw new SecurityException("Tool call not approved: " + toolName);
        }
        // Heterogeneous tool map requires unchecked cast; type-safety guaranteed
        // by register-time coupling between name key and AgentTool's generic signature.
        @SuppressWarnings("unchecked")
        var result = ((AgentTool<Map<String, Object>, Object>) tool)
            .execute(toolCallId, arguments, signal,
                     (ToolUpdateCallback<Object>) onUpdate, context);
        return result;
    }

    /** Generate tool definitions suitable for the LLM request. */
    public List<ToolDefinition> toToolDefinitions() {
        return tools.values().stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema()))
            .toList();
    }

    /** Generate tool descriptions for the system prompt. */
    public String toSystemPromptFragment() {
        var sb = new StringBuilder();
        for (var tool : tools.values()) {
            sb.append("- **").append(tool.name()).append("**: ")
              .append(tool.description()).append("\n");
        }
        return sb.toString();
    }
}
