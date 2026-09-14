package com.pijava.agent.compaction;

import java.util.List;

import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;

/**
 * LLM-driven summary generator (aligned with pi
 * {@code generateSummary}/{@code generateSummaryWithUsage}). The harness
 * drives this via its stream function; {@link #truncating()} provides a
 * deterministic placeholder until Phase 6 wires the real summarization flow.
 *
 * <p><b>3d 的失败语义</b>（{@code docs/31 §8.22}）：pi 的
 * {@code generateSummaryWithUsage} 对 error/length 收尾的摘要响应<b>抛错</b>
 * （{@code getSummarizationFailure}，{@code compaction.ts:545-553}）并附
 * toolCall 守卫（{@code :719-721}）；pi-java 的 {@link LlmSummaryGenerator}
 * 照同一形状抛 {@code IllegalStateException}，由调用方的压缩兜底 catch 转成
 * 会话事件。<b>不存在</b>「失败/空输出 ⇒ 截断兜底」这条 pi 没有的路。空文本
 * 在 stop 收尾下是合法产物（pi 原文返回 {@code contentText}，可以为空串）。</p>
 */
@FunctionalInterface
public interface SummaryGenerator {

    /**
     * Summarize the compressed message list.
     *
     * @param reason 本次摘要的触发原因（pi {@code summarization_retry_attempt_start}
     *               的 {@code reason} 字段："manual"/"threshold"/"overflow"；3d，
     *               仅供环 B 的事件装饰，可为 null）
     */
    SummaryResult summarize(List<Message> compressed, String previousSummary,
                            String customInstructions, int reserveTokens, String reason);

    /** 无原因的旧式调用（测试/非环 B 路）：{@code reason = null}。 */
    default SummaryResult summarize(List<Message> compressed, String previousSummary,
                                    String customInstructions, int reserveTokens) {
        return summarize(compressed, previousSummary, customInstructions, reserveTokens, null);
    }

    /** Summary text plus the usage of the generating call (may be null). */
    record SummaryResult(String text, Usage usage) {}

    /**
     * Deterministic fallback: a terse structural summary. Does not call the
     * LLM; used by the harness until the summarization prompt flow lands.
     */
    static SummaryGenerator truncating() {
        return (compressed, previousSummary, customInstructions, reserveTokens, reason) -> {
            String text = previousSummary != null && !previousSummary.isBlank()
                ? previousSummary
                : "Compacted " + compressed.size() + " earlier message(s).";
            return new SummaryResult(text, null);
        };
    }
}
