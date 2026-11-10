package com.pijava.coding.agent.extension;

/**
 * docs/50 §2.1 / §3.4：pi {@code ContextUsage} 对位。
 *
 * <p>{@code tokens}/{@code percent} 在「压缩之后没有可信用量」时为 {@code null}
 * （pi 的 {@code null}），与「不可得」（{@code Optional.empty()}）是两个不同结局。</p>
 *
 * @param tokens        估算上下文令牌数；不可得时 {@code null}
 * @param contextWindow 模型上下文窗口（恒有值）
 * @param percent       {@code tokens / contextWindow * 100}；不可得时 {@code null}
 */
public record ExtensionContextUsage(Integer tokens, int contextWindow, Double percent) {}
