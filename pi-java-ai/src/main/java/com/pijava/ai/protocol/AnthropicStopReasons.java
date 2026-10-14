package com.pijava.ai.protocol;

import com.anthropic.models.messages.RawMessageDeltaEvent;

import com.pijava.ai.message.StopReason;

/**
 * Anthropic 车道的 stop reason 映射（pi {@code anthropic-messages.ts:1464-1493}
 * 的 {@code mapStopReason} 与 :1475 的 {@code stopDetails?.explanation}）。
 *
 * <p>从 {@link AnthropicMessagesApi} 抽出以保持该类 ≤500 行（批 5 加入
 * responseModel/fallback/transformations 触点后）。行为逐字未改。</p>
 */
final class AnthropicStopReasons {

    private AnthropicStopReasons() {}

    /** pi {@code mapStopReason} 的返回形状 {@code { stopReason, errorMessage? }}。 */
    record Mapped(StopReason reason, String errorMessage) {}

    /**
     * pi {@code anthropic-messages.ts:1464-1493} {@code mapStopReason} 的逐字移植。
     *
     * <p>未知取值<b>抛</b> {@link IllegalStateException}，与 pi 的 {@code default: throw}
     * 一致；由 {@code AnthropicMessagesApi.mapEvent} 的 catch 转成 {@code StreamError}。</p>
     *
     * @param raw         线上的原始取值（{@code StopReason.asString()}）
     * @param explanation {@code stop_details.explanation}，无则 null
     */
    static Mapped map(String raw, String explanation) {
        return switch (raw) {
            case "end_turn" -> new Mapped(StopReason.STOP, null);
            case "max_tokens" -> new Mapped(StopReason.LENGTH, null);
            case "tool_use" -> new Mapped(StopReason.TOOL_USE, null);
            case "refusal" -> new Mapped(StopReason.ERROR,
                explanation != null && !explanation.isEmpty()
                    ? explanation
                    : "The model refused to complete the request");
            case "pause_turn" -> new Mapped(StopReason.STOP, null);
            case "stop_sequence" -> new Mapped(StopReason.STOP, null);
            case "sensitive" -> new Mapped(StopReason.ERROR, "Provider stopped with: sensitive");
            default -> throw new IllegalStateException("Unhandled stop reason: " + raw);
        };
    }

    /**
     * {@code message_delta.delta.stop_details.explanation}（pi {@code :1475}）。
     * 走非抛异常面：字段缺失/类型不符退化成「没有说明」。
     */
    static String refusalExplanation(RawMessageDeltaEvent.Delta delta) {
        return delta._stopDetails().asKnown()
            .flatMap(details -> details._explanation().asString())
            .orElse(null);
    }
}
