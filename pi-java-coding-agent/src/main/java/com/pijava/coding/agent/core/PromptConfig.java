package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.harness.PromptImage;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Per-prompt overrides applied before a run starts (Phase 3 §10).
 *
 * @param systemPrompt      system prompt override (null = keep harness value)
 * @param thinkingLevel     thinking level override (null = keep harness value)
 * @param streamingBehavior 运行中提交时的排队行为（A5，原 docs/25；null = 未指定，
 *                          运行中无此值则在调用线程被路由门拒绝）
 * @param source            输入来源（pi {@code InputSource}：
 *                          {@code "interactive"|"rpc"|"extension"}，types.ts:1140；
 *                          null ⇒ {@code "interactive"}）——docs/15 {@code input} 事件载荷
 * @param images            图片附件（pi {@code PromptOptions.images}，agent-session.ts:318；
 *                          null/空 ≙ 缺席）——批 20（B237）随 RPC images 透传接通
 */
public record PromptConfig(
    String systemPrompt,
    ModelThinkingLevel thinkingLevel,
    StreamingBehavior streamingBehavior,
    String source,
    List<PromptImage> images
) {
    /** Default config: no overrides. */
    public static PromptConfig defaults() {
        return new PromptConfig(null, null, null, null, null);
    }

    /** 附排队行为，其余沿用默认（A5 RPC prompt 路由用）。 */
    public static PromptConfig withStreamingBehavior(StreamingBehavior behavior) {
        return new PromptConfig(null, null, behavior, null, null);
    }

    /** RPC 输入源＋排队行为（docs/15：pi rpc-mode 的 prompt source="rpc"）。 */
    public static PromptConfig rpc(StreamingBehavior behavior) {
        return new PromptConfig(null, null, behavior, "rpc", null);
    }

    /** 批 20（B237）：RPC 输入源＋排队行为＋图片附件（pi rpc-mode.ts:398-402 全参）。 */
    public static PromptConfig rpc(StreamingBehavior behavior, List<PromptImage> images) {
        return new PromptConfig(null, null, behavior, "rpc", images);
    }
}
