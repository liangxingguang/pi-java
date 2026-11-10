package com.pijava.coding.agent.extension;

import java.util.function.Consumer;

/**
 * docs/50 §2.1 / D234：pi {@code NewSessionOptions} 的两字段子集。
 *
 * <p>{@code setup} 不实现（本锚点 examples 零消费；需可写 SessionManager，
 * 与只读门面纪律冲突）。</p>
 *
 * @param parentSession 新会话 header 的父会话（{@code null} ≙ 未给）
 * @param withSession   新会话接管后的回调，收到替换后的上下文（可 {@code null}）
 */
public record ExtensionNewSessionOptions(
        @org.jspecify.annotations.Nullable String parentSession,
        @org.jspecify.annotations.Nullable Consumer<ExtensionContext> withSession) {

    /** 无父会话、无回调。 */
    public static ExtensionNewSessionOptions none() {
        return new ExtensionNewSessionOptions(null, null);
    }
}
