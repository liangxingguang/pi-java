package com.pijava.agent.context;

/**
 * 上下文窗口用量 —— pi {@code ContextUsage}
 * （{@code packages/coding-agent/src/core/extensions/types.ts:327-333}）。
 *
 * <p>语义逐字对位 pi：{@code tokens} 与 {@code percent} 可空。最新 compaction
 * 之后还没有可信的 assistant 用量时，pi 返回
 * {@code { tokens: null, contextWindow, percent: null }}；宿主据此显示
 * {@code ?/window}，而不是展示压缩前已失真的数字。</p>
 *
 * @param tokens        当前上下文估算令牌数；{@code null} ≙ pi {@code null}
 * @param contextWindow 模型上下文窗口（恒 &gt; 0；调用方保证）
 * @param percent       {@code tokens / contextWindow * 100}；{@code null} 同上
 */
public record ContextUsage(Double tokens, int contextWindow, Double percent) {}
