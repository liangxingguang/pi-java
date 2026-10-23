package com.pijava.coding.agent.core.bugreport;

import java.util.ArrayList;

import com.pijava.agent.compaction.ConversationSerializer;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.AgentSession;

/**
 * bug 摘要生成（pi {@code generateBugReportSummary}，core/bug-report.ts:340-375
 * ＋ {@code AgentSession.summarizeForBugReport}，agent-session.ts:4322-4336
 * 逐段对位）：用户不分享转录时，由会话模型代写上报摘要。
 *
 * <p>【模型可见面】（系统提示词与指令）在 {@link BugReport}，逐字不可动。
 * 四守卫逐字：aborted ⇒ cancelled；failure ⇒ {@code getSummarizationFailure}；
 * toolCall ⇒ attempted to call a tool；空文本（trim 后）⇒ was empty。</p>
 */
public final class BugReportSummarizer {

    /** pi {@code model.contextWindow > 0 ? model.contextWindow : 128_000}。 */
    private static final int FALLBACK_CONTEXT_WINDOW = 128_000;

    private final LlmSummaryGenerator generator;

    /** @param generator 带会话 streamFn＋retry 环的摘要生成器（reason 传 {@code "bugReport"}） */
    public BugReportSummarizer(LlmSummaryGenerator generator) {
        this.generator = generator;
    }

    /**
     * pi {@code summarizeForBugReport}：model 缺 ⇒ {@code No model selected}；
     * 预算 {@code floor(contextWindow * 0.6)} 尾选消息 ⇒ 四段 prompt ⇒
     * {@code min(4096, maxTokens)} ⇒ 四守卫。
     */
    public String summarize(AgentSession session, String hint) {
        var model = session.harness().getModel();
        if (model == null) {
            throw new IllegalStateException("No model selected");
        }
        var info = session.modelCatalog().find(model);
        int contextWindow = info.map(ModelInfo::maxInputTokens).orElse(0) > 0
            ? info.get().maxInputTokens() : FALLBACK_CONTEXT_WINDOW;
        var messages = session.accumulatedMessages();
        var selected = BugReport.selectMessages(messages, (long) Math.floor(contextWindow * 0.6));
        int maxOutput = info.map(ModelInfo::maxOutputTokens).orElse(0);
        int maxTokens = Math.min(4096, maxOutput > 0 ? maxOutput : Integer.MAX_VALUE);

        var prompt = buildPrompt(selected, messages.size(), hint);
        var response = generator.callBranch(prompt, maxTokens, "bugReport",
            BugReport.BUG_SUMMARY_SYSTEM_PROMPT);
        if (response.stopReason() == StopReason.ABORTED) {
            throw new IllegalStateException("Bug report summary was cancelled");
        }
        var failure = LlmSummaryGenerator.summarizationFailure(response, "Bug report summary");
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
        for (var block : response.content()) {
            if (block instanceof ContentBlock.ToolUseContent) {
                throw new IllegalStateException("Bug report summary attempted to call a tool");
            }
        }
        var text = LlmSummaryGenerator.contentTextOf(response.content()).trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("Bug report summary was empty");
        }
        return text;
    }

    /**
     * pi 的 prompt 四段拼装（截断注记／{@code <conversation>}／{@code <user-report>}／
     * 指令，{@code \n\n} 连，未定义段剔除）。
     */
    private static String buildPrompt(java.util.List<com.pijava.ai.message.Message> selected,
                                      int totalMessages, String hint) {
        var parts = new ArrayList<String>();
        if (selected.size() < totalMessages) {
            parts.add("Note: only the last " + selected.size() + " of "
                + totalMessages + " messages are shown.");
        }
        parts.add("<conversation>\n" + ConversationSerializer.serialize(selected)
            + "\n</conversation>");
        var trimmed = hint == null ? "" : hint.trim();
        if (!trimmed.isEmpty()) {
            parts.add("<user-report>\n" + trimmed + "\n</user-report>");
        }
        parts.add(BugReport.BUG_SUMMARY_INSTRUCTIONS);
        return String.join("\n\n", parts);
    }
}
