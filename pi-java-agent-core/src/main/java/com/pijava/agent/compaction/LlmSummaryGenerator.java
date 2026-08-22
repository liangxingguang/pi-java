package com.pijava.agent.compaction;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingConfig;

/**
 * LLM 驱动摘要生成器（对齐 pi {@code compaction.ts} 的
 * {@code SUMMARIZATION_SYSTEM_PROMPT} + {@code serializeConversation}）。
 *
 * <p>经 harness 的 {@link StreamFn} 调 LLM 生成结构化压缩摘要；失败或空输出时
 * 回退 {@link #fallback} 的结构化截断摘要，保证压缩流程不因无 LLM/网络中断崩。</p>
 */
public final class LlmSummaryGenerator implements SummaryGenerator {

    /** 对齐 pi {@code SUMMARIZATION_SYSTEM_PROMPT}。 */
    private static final String SYSTEM_PROMPT =
        "You are a context summarization assistant. Read a conversation between a user "
        + "and an AI assistant, then produce a structured summary following the exact "
        + "format. Do NOT continue the conversation. Do NOT respond to any questions "
        + "in the conversation. ONLY output the structured summary.";

    private final StreamFn streamFn;
    private final Supplier<ModelId<?>> model;

    /** @param streamFn LLM 调用函数（与 harness 同源）
     *  @param model    生成摘要所用模型（运行时读取） */
    public LlmSummaryGenerator(StreamFn streamFn, Supplier<ModelId<?>> model) {
        this.streamFn = streamFn;
        this.model = model;
    }

    @Override
    public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                   String customInstructions, int reserveTokens) {
        try {
            var system = new Message.SystemMessage(
                List.of(new ContentBlock.TextContent(SYSTEM_PROMPT)));
            var user = new Message.UserMessage(
                List.of(new ContentBlock.TextContent(buildPrompt(compressed, previousSummary))));
            var options = new StreamOptions(
                OptionalInt.empty(), OptionalDouble.empty(), ThinkingConfig.OFF, List.of());
            var text = new StringBuilder();
            var usage = new Usage[] {null};
            var iter = streamFn.stream(List.of(system, user), model.get(), options);
            try {
                while (iter.hasNext()) {
                    var event = iter.next();
                    if (event instanceof StreamEvent.StreamDone || event instanceof StreamEvent.StreamError) {
                        break;
                    }
                    if (event instanceof StreamEvent.UsageInfo ui) {
                        usage[0] = new Usage(ui.inputTokens(), ui.outputTokens(),
                            0, 0, null, null,
                            ui.inputTokens() + ui.outputTokens(), Usage.Cost.zero());
                    }
                    if (event instanceof StreamEvent.TextDelta td) {
                        text.append(td.delta());
                    }
                }
            } finally {
                iter.close();
            }
            String summary = text.toString().strip();
            if (summary.isEmpty()) {
                return fallback(compressed, previousSummary);
            }
            return new SummaryResult(summary, usage[0]);
        } catch (Exception e) {
            return fallback(compressed, previousSummary);
        }
    }

    /** 结构化摘要 prompt（对齐 pi：Goal / Constraints / Progress / Current State）。 */
    private static String buildPrompt(List<Message> compressed, String previousSummary) {
        var sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("Previous summary:\n").append(previousSummary).append("\n\n");
        }
        sb.append("Create a structured context checkpoint summary that another LLM will "
            + "use to continue the work. Use this EXACT format:\n\n"
            + "## Goal\n[What is the user trying to accomplish?]\n\n"
            + "## Constraints & Preferences\n- [Any constraints, or \"(none)\"]\n\n"
            + "## Progress\n- [Key steps taken, or \"(none)\"]\n\n"
            + "## Current State\n- [Files/tools/tasks in progress]\n\n"
            + "Conversation:\n");
        for (var msg : compressed) {
            sb.append("user".equals(msg.role()) ? "[User]: " : "[Assistant]: ");
            sb.append(textOf(msg)).append('\n');
        }
        return sb.toString();
    }

    private static String textOf(Message msg) {
        var sb = new StringBuilder();
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.TextContent t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.ThinkingContent t) {
                sb.append("[thinking] ").append(t.text());
            }
        }
        return sb.toString();
    }

    /** 失败/空输出回退：与 {@link SummaryGenerator#truncating()} 同语义。 */
    private static SummaryResult fallback(List<Message> compressed, String previousSummary) {
        String text = previousSummary != null && !previousSummary.isBlank()
            ? previousSummary
            : "Compacted " + compressed.size() + " earlier message(s).";
        return new SummaryResult(text, null);
    }
}
