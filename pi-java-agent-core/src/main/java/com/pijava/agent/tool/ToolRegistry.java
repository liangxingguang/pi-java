package com.pijava.agent.tool;
import com.pijava.ai.AbortSignal;

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
     * 注册表里**名字在 {@code names} 中**的那一子集 —— pi 的 {@code selectedTools} 过滤。
     *
     * <p>两处共用（包 A3 的 R6）：{@code PiLaneEngine.activeTools} 用它装配 {@code Context}
     * 的工具表，{@code PiLaneSink.toolCount} 用它记账。此前记账读的是 {@code all().size()}
     * —— 那是**注册表规模**，不是「本次请求带了多少工具」，工具装载变化后这个数不跟着动。</p>
     *
     * <p>⚠️ 顺序跟注册表（{@code tools} 是 {@code LinkedHashMap}），不跟 {@code names}
     * ——{@code names} 是 {@code Set}，让它的迭代序渗进请求会引入不可复现的线格
     * （与 pi 的 {@code names.flatMap(registry.get)} 同义：pi 跟 {@code names}，
     * 但 pi 的 {@code selectedTools} 是有序数组；java 侧以注册表序为稳定源）。</p>
     */
    public List<AgentTool<?, ?>> activeOf(Collection<String> names) {
        var active = new java.util.HashSet<String>(names);
        return tools.values().stream().filter(tool -> active.contains(tool.name())).toList();
    }

    /** Remove all registered tools. */
    public void clear() {
        tools.clear();
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
        // Heterogeneous tool map requires unchecked cast back to raw AgentTool;
        // type-safety is guaranteed by register-time coupling between name key
        // and the tool's generic signature. prepareArguments must be called first
        // to convert the raw Map into the tool's typed input record.
        @SuppressWarnings("unchecked")
        var rawTool = (AgentTool<Object, Object>) tool;
        // Runtime schema validation (agent-loop plan §3.2): reject truncated or
        // malformed arguments before they reach prepareArguments/execute. A
        // violation throws IllegalArgumentException, which the tool pipeline
        // encodes as an error result fed back to the model.
        ToolArgumentsValidator.validate(rawTool.inputSchema(), arguments);
        var prepared = rawTool.prepareArguments(arguments);
        @SuppressWarnings("unchecked")
        var result = rawTool.execute(toolCallId, prepared, signal,
                                     (ToolUpdateCallback<Object>) onUpdate, context);
        return result;
    }

    /** Generate tool definitions suitable for the LLM request. */
    public List<ToolDefinition> toToolDefinitions() {
        return definitionsOf(tools.values());
    }

    /**
     * 工具本体 → LLM 定义（pi 的 {@code AgentTool} → ai 层 {@code Tool} 投影）。
     *
     * <p>agent 层的 {@code Context} 装的是**本体**（要读 {@code executionMode}，
     * {@code agent-loop.ts:417-421}），provider 只认窄定义 —— 投影发生在请求边界，
     * 与 pi 把 {@code AgentTool[]} 直接当 {@code Tool[]} 用是同一件事（它靠结构类型，
     * Java 侧靠这次显式转换）。</p>
     */
    public static List<ToolDefinition> definitionsOf(Collection<AgentTool<?, ?>> tools) {
        return tools.stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema(),
                t.label(), t.promptSnippet().isEmpty() ? null : t.promptSnippet(),
                t.promptGuidelines(), null))
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
