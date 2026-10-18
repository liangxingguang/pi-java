package com.pijava.agent.compaction;

import java.util.List;

import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;

/**
 * LLM-driven summary generator (aligned with pi
 * {@code generateSummary}/{@code generateSummaryWithUsage}). The harness
 * drives this via its stream function.
 *
 * <p><b>#93（D6，docs/25 §11.3）—— {@link #truncating()} 的定位</b>：它不是
 * 「Phase 6 前的临时占位」（真路 {@link LlmSummaryGenerator} 已全线接线），而是
 * <b>未接 LLM 生成器时的确定性兜底默认</b> —— {@code HarnessConfig} 的三处默认
 * （builder 字段、紧凑构造、便利构造）与测试夹具的消费方。分工：生产路径由宿主
 * 显式注入 {@link LlmSummaryGenerator}；缺省/测试路径拿到的是不打 LLM、
 * 输出确定可断言的截断式生成器。</p>
 *
 * <p><b>3d 的失败语义</b>（{@code 原 docs/31 §8.22}）：pi 的
 * {@code generateSummaryWithUsage} 对 error/length 收尾的摘要响应<b>抛错</b>
 * （{@code getSummarizationFailure}，{@code compaction.ts:545-553}）并附
 * toolCall 守卫（{@code :719-721}）；pi-java 的 {@link LlmSummaryGenerator}
 * 照同一形状抛 {@code IllegalStateException}，由调用方的压缩兜底 catch 转成
 * 会话事件。<b>不存在</b>「失败/空输出 ⇒ 截断兜底」这条 pi 没有的路。空文本
 * 在 stop 收尾下是合法产物（pi 原文返回 {@code contentText}，可以为空串）。</p>
 */
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

    /**
     * Summarize the turn prefix of a split turn (B171, {@code docs/18}；pi
     * {@code generateTurnPrefixSummary})：用 {@code TURN_PREFIX_SUMMARIZATION_PROMPT}
     * 对本轮 user→切点之间的消息做第二次摘要调用，输出上限取
     * {@code floor(0.5*reserveTokens)}（再被模型 maxOutputTokens 封顶）。
     */
    SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens, String reason);

    /** 无原因的旧式调用（测试/非环 B 路）：{@code reason = null}。 */
    default SummaryResult summarize(List<Message> compressed, String previousSummary,
                                    String customInstructions, int reserveTokens) {
        return summarize(compressed, previousSummary, customInstructions, reserveTokens, null);
    }

    /** Summary text plus the usage of the generating call (may be null). */
    record SummaryResult(String text, Usage usage) {}

    /**
     * Deterministic fallback: a terse structural summary. Does not call the
     * LLM — the default when the host wires no {@link LlmSummaryGenerator}
     * （{@code HarnessConfig} 的三处默认）与测试夹具（#93）。
     */
    static SummaryGenerator truncating() {
        return new SummaryGenerator() {
            @Override
            public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                           String customInstructions, int reserveTokens,
                                           String reason) {
                String text = previousSummary != null && !previousSummary.isBlank()
                    ? previousSummary
                    : "Compacted " + compressed.size() + " earlier message(s).";
                return new SummaryResult(text, null);
            }

            @Override
            public SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens,
                                                     String reason) {
                return new SummaryResult(
                    "Turn prefix checkpoint of " + messages.size() + " message(s).", null);
            }
        };
    }
}
