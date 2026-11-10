package com.pijava.examples;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.pijava.agent.compaction.ConversationSerializer;
import com.pijava.agent.harness.Context;
import com.pijava.agent.hook.SuppliedCompaction;
import com.pijava.agent.session.UuidV7;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeCompactEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeCompactResult;

/**
 * pi {@code examples/extensions/custom-compaction.ts:1-117} 移植（docs/51 §2.5）——
 * 用 Gemini Flash 汇总<b>全部</b>消息（{@code messagesToSummarize}＋{@code turnPrefixMessages}）
 * 并接管默认压缩；找不到模型、摘要为空或补全异常都回落到默认压缩。
 */
public final class CustomCompactionExtension implements PiExtension {

    /** pi 用固定模型（便宜/快）：{@code provider}／{@code modelId} 逐字。 */
    static final String MODEL_PROVIDER = "google";

    /** pi {@code find("google", "gemini-2.5-flash")}。 */
    static final String MODEL_ID = "gemini-2.5-flash";

    /** pi 的 {@code maxTokens: 8192}。 */
    static final int MAX_TOKENS = 8192;

    /** pi 的摘要提示词模板（{@code %s} 依次为 previousContext 与 conversationText）。 */
    static final String SUMMARY_PROMPT_TEMPLATE = """
        You are a conversation summarizer. Create a comprehensive summary of this conversation \
        that captures:%s

        1. The main goals and objectives discussed
        2. Key decisions made and their rationale
        3. Important code changes, file modifications, or technical details
        4. Current state of any ongoing work
        5. Any blockers, issues, or open questions
        6. Next steps that were planned or suggested

        Be thorough but concise. The summary will replace the ENTIRE conversation history, \
        so include all information needed to continue the work effectively.

        Format the summary as structured markdown with clear sections.

        <conversation>
        %s
        </conversation>""";

    @Override
    public String name() {
        return "custom-compaction";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_BEFORE_COMPACT,
            (event, c) -> onBeforeCompact(event, c));
    }

    private SessionBeforeCompactResult onBeforeCompact(SessionBeforeCompactEvent event,
                                                       ExtensionContext c) {
        c.ui().notify("Custom compaction extension triggered", NotifyType.INFO);

        var preparation = event.preparation();
        var model = c.modelRegistry().find(MODEL_PROVIDER, MODEL_ID);
        if (model.isEmpty()) {
            c.ui().notify("Could not find Gemini Flash model, using default compaction",
                NotifyType.WARNING);
            return null;
        }

        var allMessages = new ArrayList<Message>(preparation.messagesToSummarize());
        allMessages.addAll(preparation.turnPrefixMessages());

        c.ui().notify("Custom compaction: summarizing " + allMessages.size() + " messages ("
            + String.format("%,d", preparation.tokensBefore()) + " tokens) with "
            + model.get().modelId() + "...", NotifyType.INFO);

        var conversationText = ConversationSerializer.serialize(allMessages);
        var previousContext = preparation.previousSummary() != null
            ? "\n\nPrevious session summary for context:\n" + preparation.previousSummary()
            : "";

        var userMessage = new Message.UserMessage(List.of(new ContentBlock.TextContent(
            SUMMARY_PROMPT_TEMPLATE.formatted(previousContext, conversationText))),
            Instant.now());

        try {
            // Pass signal to honor abort requests (e.g., user cancels compaction)
            var response = c.modelRegistry().complete(model.get(),
                new Context(null, List.of(userMessage), List.of()),
                new ExtensionCompleteOptions(OptionalInt.of(MAX_TOKENS), Optional.empty(),
                    Optional.of(CacheRetention.NONE), Optional.of(UuidV7.INSTANCE.next()),
                    event.signal()));

            var summary = textOf(response);
            if (summary.strip().isEmpty()) {
                if (!event.signal().isAborted()) {
                    c.ui().notify("Compaction summary was empty, using default compaction",
                        NotifyType.WARNING);
                }
                return null;
            }

            return new SessionBeforeCompactResult(Boolean.FALSE,
                new SuppliedCompaction(summary, preparation.firstKeptEntryId(),
                    preparation.tokensBefore(), response.usage(), null));
        } catch (RuntimeException e) {
            c.ui().notify("Compaction failed: " + e.getMessage(), NotifyType.ERROR);
            // Fall back to default compaction on error
            return null;
        }
    }

    /** pi 的 {@code response.content.filter(text).map(text).join("\n")}。 */
    static String textOf(Message.AssistantMessage response) {
        var texts = new ArrayList<String>();
        for (var block : response.content()) {
            if (block instanceof ContentBlock.TextContent text) {
                texts.add(text.text());
            }
        }
        return String.join("\n", texts);
    }

    /** 当前可用的汇总模型（测试与调试用；pi 侧无此成员）。 */
    static Optional<ExtensionModelInfo> findSummarizer(ExtensionContext c) {
        return c.modelRegistry().find(MODEL_PROVIDER, MODEL_ID);
    }
}
