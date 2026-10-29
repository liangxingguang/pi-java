package com.pijava.agent.tool;
import com.pijava.ai.AbortSignal;

import java.util.List;
import java.util.Map;

/**
 * A tool definition executed by the agent runtime.
 *
 * @param <TParams>  validated arguments type (Jackson-deserialized from LLM JSON)
 * @param <TDetails> structured detail type for UI rendering
 *
 * <p>Aligned with pi's {@code AgentTool}. Key Java-izations:
 * <ul>
 *   <li>5-param {@code execute()} — adds explicit {@code ToolContext} (pi injects via closure)</li>
 *   <li>Exception-driven errors — tools throw on failure, harness catches and
 *       wraps in error content (pi identical approach)</li>
 *   <li>{@code @FunctionalInterface} for callbacks instead of inline lambdas</li>
 * </ul></p>
 *
 * <p><b>并发契约（#81，docs/10 §10.1）</b>：并行批次的工具调用以虚拟线程
 * <b>真并发</b>执行（pi {@code Promise.all} 不设上限，{@code agent-loop.ts:516-520}；
 * 对位 {@code PiLoopTools} 的虚拟线程×1/任务）⇒ 实现必须并发安全：不得把
 * 跨调用的可变状态挂在实例/静态字段上而不加串行化；同一文件上的
 * 读-改-写走 {@code FileMutationQueue}（pi 模块级单例，edit/write 共享）。</p>
 */
public interface AgentTool<TParams, TDetails> {

    /** Unique tool name (e.g. "bash", "read", "write"). */
    String name();

    /** Human-readable label for UI display. */
    String label();

    /**
     * One-line snippet for the system prompt's Available-tools section
     * (pi {@code ToolDefinition.promptSnippet}). Empty means fall back to
     * {@link #description()}.
     */
    default String promptSnippet() {
        return "";
    }

    /** Guideline bullets appended to the system prompt when this tool is active. */
    default List<String> promptGuidelines() {
        return List.of();
    }

    /** Description shown to the LLM in the system prompt / tool definition. */
    String description();

    // ── 批 30（T1，B203 收编）：pi ToolDefinition 的五件元数据上本体 ──
    // pi 的 agent 包 AgentTool 只有 outputSchema；其余四件是 coding-agent
    // ToolDefinition 的扩展。Java 的 AgentTool 是两型合并（promptSnippet/
    // promptGuidelines 已在上面，pi 里它们也只在 ToolDefinition）——故五件
    // 全上 AgentTool 与该先例一致（docs/42 §3.1 末注）。

    /**
     * pi {@code ToolDefinition.outputSchema}（types.ts:596-600）——成功结果里
     * {@code structuredContent} 的 JSON Schema。默认 {@code null}（无结构化输出契约）。
     */
    default Map<String, Object> outputSchema() {
        return null;
    }

    /**
     * pi {@code ToolDefinition.exposure}（types.ts:602-604）——模型如何触达本工具。
     * 默认 {@link ToolExposure#DIRECT}（pi {@code ?? "direct"}，agent-session.ts:1556）。
     */
    default ToolExposure exposure() {
        return ToolExposure.DIRECT;
    }

    /**
     * pi {@code ToolDefinition.namespace}（types.ts:607）——工具所属的组
     * （如它的 MCP 服务器）。默认 {@code null}（无组）。
     */
    default ToolNamespace namespace() {
        return null;
    }

    /**
     * pi {@code ToolDefinition.annotations}（types.ts:610）——工具行为提示
     * （如来自 MCP 服务器的四 hint）。默认 {@code null}（无提示）。
     */
    default ToolAnnotations annotations() {
        return null;
    }

    /**
     * pi {@code ToolDefinition.defaultActive}（types.ts:612-617）——注册是否即激活。
     * 默认 {@code true}：pi 对 {@code direct}/{@code model-only} 工具注册即激活，
     * 其余 exposure 注册时永不激活（激活门消费本值，不消费 exposure 臂）。
     */
    default boolean defaultActive() {
        return true;
    }

    /** JSON Schema describing the tool's input parameters. */
    Map<String, Object> inputSchema();

    /**
     * Execution mode hint.
     * {@link ExecutionMode.Sequential} — cannot run concurrently with other tools (e.g. bash).
     * {@link ExecutionMode.Parallel} — can run concurrently with other parallel tools (e.g. read, grep, ls, glob).
     */
    ExecutionMode executionMode();

    /**
     * 原 docs/66：pi {@code Tool.constrainedSampling}：可选 provider 侧约束采样；
     * {@code null} ≙ 缺席（扩展显式 {@code false} 同形）。
     */
    default com.pijava.ai.api.ConstrainedSampling constrainedSampling() {
        return null;
    }

    /**
     * Optional compatibility shim for raw tool-call arguments before
     * schema validation. Must return an object matching TParams.
     */
    @SuppressWarnings("unchecked")  // safe: callers supply TParams via type inference at the call site
    default TParams prepareArguments(Map<String, Object> raw) {
        return (TParams) raw;
    }

    /**
     * Execute the tool call. <b>Throw on failure</b> — the harness catches
     * exceptions and wraps them in error results. This aligns with pi's
     * exception-driven error model.
     *
     * @param toolCallId unique identifier from the LLM
     * @param params     validated arguments
     * @param signal     abort signal (may be null)
     * @param onUpdate   progress callback (may be null; scoped to this invocation)
     * @param context    execution environment (cwd, shell, filesystem) —
     *                   explicit because Java lacks TypeScript's closure-based DI
     * @return the tool result
     * @throws Exception on failure (harness wraps in error result)
     */
    ToolResult<TDetails> execute(
        String toolCallId,
        TParams params,
        AbortSignal signal,
        ToolUpdateCallback<TDetails> onUpdate,
        ToolContext context
    ) throws Exception;
}
