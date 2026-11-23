package com.pijava.tui.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message.AssistantMessage;

/**
 * L5 W7（pi {@code getDroppedReasoning}／{@code maybeShowThinkingDropNotice}，
 * interactive-mode.ts:473-523/:4384-4410 逐字）：provider 报告「有 reasoning 块
 * 被 withheld 未进请求」的转录提示——Anthropic 的 {@code thinking_dropped} 与
 * OpenAI 的 {@code reasoning}；门在调用方（{@code showCacheMissNotices}）。
 */
public final class ReasoningDropNotice {

    private ReasoningDropNotice() {}

    private record Kind(String provider, String droppedType, String singular, String plural) {}

    private static final Map<String, Kind> KINDS = Map.of(
        "anthropic_input_transformations",
        new Kind("Anthropic", "thinking_dropped", "thinking block", "thinking blocks"),
        "openai_input_transformations",
        new Kind("OpenAI", "reasoning", "reasoning item", "reasoning items"));

    /** 一条消息上命中的丢弃统计（{@code reasons} 去重保序，同 pi 的 Set）。 */
    public record Dropped(String provider, String droppedType, String singular, String plural,
                          int count, List<String> reasons) {}

    /** Count the reasoning blocks a provider reported as withheld for {@code message}. */
    public static Optional<Dropped> find(AssistantMessage message) {
        if (message == null || message.diagnostics() == null) {
            return Optional.empty();
        }
        for (var diagnostic : message.diagnostics()) {
            var kind = KINDS.get(diagnostic.type());
            var details = diagnostic.details();
            if (kind == null || details == null) {
                continue;
            }
            var transformations = details.get("transformations");
            if (transformations == null || !transformations.isArray()) {
                continue;
            }
            int count = 0;
            var reasons = new LinkedHashSet<String>();
            for (var transformation : transformations) {
                if (!transformation.isObject()
                        || !kind.droppedType().equals(transformation.path("type").asText())) {
                    continue;
                }
                count++;
                var reason = transformation.get("reason");
                if (reason != null && reason.isTextual()) {
                    reasons.add(reason.asText());
                }
            }
            if (count == 0) {
                continue;
            }
            return Optional.of(new Dropped(kind.provider(), kind.droppedType(),
                kind.singular(), kind.plural(), count, List.copyOf(reasons)));
        }
        return Optional.empty();
    }

    /**
     * pi 的同类抑制：providers 在每次重放该块的请求上再报一次，只有计数高于
     * 上一响应的才算新损失。
     */
    public static boolean isRepeat(Dropped current, Optional<Dropped> previous) {
        return previous.isPresent()
            && previous.get().provider().equals(current.provider())
            && previous.get().droppedType().equals(current.droppedType())
            && current.count() <= previous.get().count();
    }

    /** pi 的提示行（{@code (details in session)} 尾部逐字）。 */
    public static String text(Dropped dropped) {
        var noun = dropped.count() == 1 ? dropped.singular() : dropped.plural();
        var reasons = dropped.reasons().isEmpty()
            ? "" : ": " + String.join(", ", dropped.reasons());
        return dropped.provider() + " dropped " + dropped.count() + " " + noun + reasons
            + " (details in session)";
    }

    /**
     * 分支里最后一条 assistant 消息（{@code message_end} 先于落盘 ⇒ 这就是
     * 上一响应，pi 的 {@code sessionManager.getBranch()} 反向扫描对位）。
     */
    public static Optional<AssistantMessage> previousAssistant(List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof Entry.Message message
                    && message.message() instanceof AssistantMessage assistant) {
                return Optional.of(assistant);
            }
        }
        return Optional.empty();
    }
}
