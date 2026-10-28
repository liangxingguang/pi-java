package com.pijava.coding.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.coding.agent.extension.ToolExposure;

/**
 * 批 27（B162 裁决④）：工具定义元数据表——pi {@code AgentSession._toolDefinitions}
 * （agent-session.ts:1554-1556 的查找源）的 java 对位。
 *
 * <p>{@code ToolRegistry}（agent-core）只存 {@code AgentTool} 本体；exposure/
 * defaultActive/namespace 是扩展层概念，存这里。查无条目 ⇒ pi 的默认：
 * {@code exposure "direct"}（:1556 的 {@code ?? "direct"}）、defaultActive true。</p>
 *
 * <p>生产方：内置 tool-search 扩展（model-only＋defaultActive=false）、MCP registrar
 * （按服务器配置逐工具写入）、未来扩展工具注册面。消费方：{@code AgentSession}
 * 四门（declarable/activatedOnRegistration/activatable/callable）与
 * {@code tool_search} 的候选构建。</p>
 */
public final class ToolDefinitions {

    /**
     * pi {@code ToolNamespace}（types.ts:547-558 的字段子集——name/description/
     * instructions，tool_search 文档构建的三来源）。
     */
    public record Namespace(String name, String description, String instructions) {}

    /** 一个工具的注册元数据。 */
    public record Entry(ToolExposure exposure, boolean defaultActive, Namespace namespace) {

        /** 无命名空间的简形。 */
        public static Entry of(ToolExposure exposure, boolean defaultActive) {
            return new Entry(exposure, defaultActive, null);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 登记/覆盖（pi 的同名重注册即替换语义）。 */
    public void register(String toolName, Entry entry) {
        entries.put(toolName, entry);
    }

    /** 查条目；未登记 ⇒ {@code null}（调用方按 pi 默认回落）。 */
    public Entry get(String toolName) {
        return entries.get(toolName);
    }

    /** pi {@code _getToolExposure}（:1554-1556）的默认臂：查无 ⇒ {@code direct}。 */
    public ToolExposure exposureOf(String toolName) {
        var entry = entries.get(toolName);
        return entry == null ? ToolExposure.DIRECT : entry.exposure();
    }
}
