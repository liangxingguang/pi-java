package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.ai.Usage;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;

/**
 * docs/50 §2.6：把 {@link StreamIterator} 事件收成单条
 * {@link Message.AssistantMessage} 的共享收集器。
 *
 * <p>逐事件检查 {@code aborted}；中止时关闭迭代器并返回归一化的
 * {@code stopReason="aborted"} 消息。{@link LlmSummaryGenerator} 的单次摘要调用
 * 委托本收集器（消除重复的事件循环），扩展面 {@code modelRegistry.complete}
 * 也经它聚合。</p>
 */
public final class AssistantResponseCollector {

    private AssistantResponseCollector() {
    }

    /**
     * 收集一次流。
     *
     * @param iterator 事件迭代器（本方法负责关闭）
     * @param aborted  中止观察口（可为 {@code null} ≙ 永不中止）
     * @return 终局消息；流被截断时返回 aborted 方言
     */
    public static Message.AssistantMessage collect(StreamIterator iterator,
                                                   BooleanSupplier aborted) {
        var toolCalls = new ArrayList<ContentBlock>();
        StringBuilder text = new StringBuilder();
        Usage[] usage = {null};
        Message.AssistantMessage result = null;
        try {
            while (iterator.hasNext()) {
                if (aborted != null && aborted.getAsBoolean()) {
                    result = terminal(null, StopReason.ABORTED, text, toolCalls, usage[0], null);
                    break;
                }
                var event = iterator.next();
                if (event instanceof StreamEvent.TextDelta td) {
                    text.append(td.delta());
                } else if (event instanceof StreamEvent.ToolCallEnd tc) {
                    // 批 30（ai#8）：namespace 随块携带（pi frame 终局写回对位）。
                    toolCalls.add(new ContentBlock.ToolUseContent(tc.id(), tc.name(),
                        tc.arguments(), null, tc.namespace(), tc.argumentsError()));
                } else if (event instanceof StreamEvent.UsageInfo ui) {
                    usage[0] = ui.usage() != null ? ui.usage() : synthesizeUsage(ui);
                } else if (event instanceof StreamEvent.StreamDone done) {
                    result = terminal(done.partial(), done.reason(), text, toolCalls, usage[0], null);
                    break;
                } else if (event instanceof StreamEvent.StreamError err) {
                    result = terminal(err.partial(), err.reason(), text, toolCalls, usage[0],
                        StreamEvent.StreamError.textOf(err));
                    break;
                }
            }
            if (result == null) {
                // 流被截断、终局事件缺席 —— provider 方言的 aborted（见 LlmSummaryGenerator）。
                result = terminal(null, StopReason.ABORTED, text, toolCalls, usage[0], null);
            }
        } finally {
            iterator.close();
        }
        return result;
    }

    /** 调用方已中止时的同形消息（pi 的 {@code {...rest, stopReason:"aborted"}}）。 */
    public static Message.AssistantMessage abortedMessage() {
        return new Message.AssistantMessage(List.of(), StopReason.ABORTED,
            null, null, null, null, null, null, null, null);
    }

    /**
     * 终局合成：partial 优先（全字段投影），但 partial 上缺位的 usage/errorMessage
     * 用收集值补上；无 partial（或 partial 连 stopReason 都没有）⇒ 用收集到的
     * text + toolCall 块按 reason 合成（空文本合法，不塞占位块）。
     *
     * <p>判据从 {@code projected.usage() == null} 挪到 {@code partial.usage() == null}
     * （包⑨ B41 对位），partial 才是「这一次流有没有报用量」的原件。</p>
     */
    private static Message.AssistantMessage terminal(
            com.pijava.ai.message.AssistantMessage partial, StopReason reason,
            StringBuilder text, List<ContentBlock> toolCalls, Usage usage, String errorMessage) {
        if (partial != null && partial.stopReason() != null) {
            var projected = Message.AssistantMessage.fromPartial(partial);
            boolean backfillUsage = usage != null && partial.usage() == null;
            boolean backfillError = errorMessage != null && projected.errorMessage() == null;
            if (backfillUsage || backfillError) {
                projected = new Message.AssistantMessage(projected.content(),
                    projected.stopReason(), projected.deferred(), projected.api(),
                    projected.provider(), projected.model(), projected.responseModel(),
                    projected.providerThinkingLevel(), projected.thinkingLevel(),
                    projected.diagnostics(),
                    backfillUsage ? usage : projected.usage(), projected.timestamp(),
                    backfillError ? errorMessage : projected.errorMessage(),
                    projected.rawStopReason(), projected.durationMs(), projected.responseId());
            }
            return projected;
        }
        var blocks = new ArrayList<ContentBlock>();
        if (text.length() > 0) {
            blocks.add(new ContentBlock.TextContent(text.toString()));
        }
        blocks.addAll(toolCalls);
        return new Message.AssistantMessage(blocks, reason, null, null, null, null,
            usage, null, errorMessage, null);
    }

    private static Usage synthesizeUsage(StreamEvent.UsageInfo ui) {
        return new Usage(ui.inputTokens(), ui.outputTokens(), 0, 0, null, null,
            ui.inputTokens() + ui.outputTokens(), Usage.Cost.zero());
    }
}
