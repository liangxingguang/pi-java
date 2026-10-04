package com.pijava.ai.api;

import java.util.Map;

/**
 * 工具的**声明形状** —— pi 的 ai 层 {@code Tool}（{@code types.ts:600-605}）在 java 的对应物：
 * {@code {name, description, parameters}} ＋ 可选 {@code constrainedSampling}。
 *
 * <p>它和 {@link ToolDefinition} 的区别是**职责**而不是字段多少：{@code ToolDefinition} 是全形
 * （多带 {@code label}/{@code promptSnippet}/{@code promptGuidelines}/{@code renderShell}
 * 四个 UI 与提示词元数据，schema 的字段名是 {@code inputSchema}）；本记录是**剥掉那些之后**
 * 的线格/比较形状，由 {@link Transcripts#toToolDeclaration} 产出。</p>
 *
 * <p>存在的理由：系统消息的 {@code toolsAdded} 在 pi 侧收的正是 ai 层 {@code Tool}
 * （{@code types.ts:503}），而包 B87② 之前本仓**两处各写各的** —— {@code SessionJson}
 * 写 {@code ToolDefinition} 全形、{@code PiMessagesApi} 手写三键。收敛到一处之后，
 * 「会话落线」与「PiMessages 线格」字节一致。</p>
 *
 * @param name        tool identifier
 * @param description human-readable description（pi 侧可缺省，java 允许 {@code null}）
 * @param parameters  JSON Schema，线格上的键名是 {@code parameters}
 * @param constrainedSampling pi {@code Tool.constrainedSampling}（原 docs/66）；{@code null}
 *                    时 SessionJson 的 NON_NULL 使该键不出场，与 pi 缺席同形
 */
public record ToolDeclaration(String name, String description, Map<String, Object> parameters,
                              ConstrainedSampling constrainedSampling) {

    /** Compact constructor: defensively copy the schema map. */
    public ToolDeclaration {
        parameters = Map.copyOf(parameters);
    }

    /** Three-component shape (pre-constrained-sampling call sites). */
    public ToolDeclaration(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, null);
    }
}
