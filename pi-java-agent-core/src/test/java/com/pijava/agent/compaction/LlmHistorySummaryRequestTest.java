package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 同族收尾（{@code docs/21}）：历史摘要调用的请求形状 —— pi
 * {@code generateSummaryWithUsage}（{@code compaction.ts:696-760}）：会话经
 * {@code <conversation>} 标签包裹、旧摘要经 {@code <previous-summary>} 标签，
 * 按旧摘要有无切换两套逐字指令，{@code customInstructions} 追
 * {@code Additional focus} 后缀；输出上限 {@code floor(0.8*reserveTokens)}，
 * 再被模型 {@code maxOutputTokens} 封顶，缓存门 {@code none}。
 */
class LlmHistorySummaryRequestTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "summary");

    private static final List<Message> ONE = List.of(
        new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));

    /** pi SUMMARIZATION_SYSTEM_PROMPT（utils.ts:161-163）逐字。 */
    private static final String EXPECTED_SYSTEM_PROMPT = """
        You are a context summarization assistant. Your task is to read a conversation \
        between a user and an AI assistant, then produce a structured summary following \
        the exact format specified.

        Do NOT continue the conversation. Do NOT respond to any questions in the \
        conversation. ONLY output the structured summary.""";

    /** pi SUMMARIZATION_PROMPT（compaction.ts:507-538）逐字。 */
    private static final String EXPECTED_SUMMARIZATION_PROMPT = """
        The messages above are a conversation to summarize. Create a structured context \
        checkpoint summary that another LLM will use to continue the work.

        Use this EXACT format:

        ## Goal
        [What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

        ## Constraints & Preferences
        - [Any constraints, preferences, or requirements mentioned by user]
        - [Or "(none)" if none were mentioned]

        ## Progress
        ### Done
        - [x] [Completed tasks/changes]

        ### In Progress
        - [ ] [Current work]

        ### Blocked
        - [Issues preventing progress, if any]

        ## Key Decisions
        - **[Decision]**: [Brief rationale]

        ## Next Steps
        1. [Ordered list of what should happen next]

        ## Critical Context
        - [Any data, examples, or references needed to continue]
        - [Or "(none)" if not applicable]

        Keep each section concise. Preserve exact file paths, function names, and error \
        messages.""";

    /** pi UPDATE_SUMMARIZATION_PROMPT 首句（compaction.ts:577），钉 prompt 切换。 */
    private static final String UPDATE_PROMPT_FIRST_LINE =
        "The messages above are NEW conversation messages to incorporate into the existing "
        + "summary provided in <previous-summary> tags.";

    /** 捕获请求的脚本流。 */
    private static final class Captured {
        final List<Context> contexts = new ArrayList<>();
        final List<StreamOptions> options = new ArrayList<>();

        StreamFn fn() {
            return (model, context, options) -> {
                this.contexts.add(context);
                this.options.add(options);
                return StreamIterator.from(success("SUMMARY"));
            };
        }

        String promptText() {
            Message first = contexts.get(0).messages().getFirst();
            ContentBlock block = first.content().getFirst();
            return ((ContentBlock.TextContent) block).text();
        }
    }

    private static List<StreamEvent> success(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static LlmSummaryGenerator generator(StreamFn fn, int cap) {
        IntSupplier modelCap = () -> cap;
        return new LlmSummaryGenerator(fn, () -> MODEL,
            RetrySettings::defaults, () -> false, RetryObserver.NOOP, modelCap);
    }

    @Test
    void initialSummaryWrapsConversationAndSendsVerbatimPromptAtEightyPercentReserve() {
        var captured = new Captured();

        generator(captured.fn(), 0)
            .summarize(ONE, null, null, 20_000, "manual");

        assertThat(captured.contexts.get(0).systemPrompt())
            .as("pi utils.ts:161-163 系统提示词，逐字")
            .isEqualTo(EXPECTED_SYSTEM_PROMPT);
        assertThat(captured.promptText())
            .as("pi compaction.ts:729-733 的 conversation 标签 + 逐字指令")
            .isEqualTo("<conversation>\n[User]: hi\n</conversation>\n\n"
                + EXPECTED_SUMMARIZATION_PROMPT);
        int maxTokens = captured.options.get(0).maxTokens().orElseThrow();
        assertThat(maxTokens)
            .as("floor(0.8 * 20000) = 16000")
            .isEqualTo(16_000);
        assertThat(captured.options.get(0).cacheRetention())
            .contains(CacheRetention.NONE);
    }

    @Test
    void previousSummarySwitchesToUpdatePromptAndTags() {
        var captured = new Captured();

        generator(captured.fn(), 0)
            .summarize(ONE, "Prior: build app", null, 20_000, "manual");

        assertThat(captured.promptText())
            .as("pi :729-733：conversation → previous-summary → UPDATE 指令")
            .isEqualTo("<conversation>\n[User]: hi\n</conversation>\n\n"
                + "<previous-summary>\nPrior: build app\n</previous-summary>\n\n"
                + UPDATE_PROMPT_FIRST_LINE + "\n\n" + UPDATE_INSTRUCTIONS);
    }

    /** pi UPDATE_SUMMARIZATION_INSTRUCTIONS（compaction.ts:540-575）逐字。 */
    private static final String UPDATE_INSTRUCTIONS = """
        Update the existing structured summary with new information. RULES:
        - PRESERVE all existing information from the previous summary
        - ADD new progress, decisions, and context from the new messages
        - UPDATE the Progress section: move items from "In Progress" to "Done" when completed
        - UPDATE "Next Steps" based on what was accomplished
        - PRESERVE exact file paths, function names, and error messages
        - If something is no longer relevant, you may remove it

        Use this EXACT format:

        ## Goal
        [Preserve existing goals, add new ones if the task expanded]

        ## Constraints & Preferences
        - [Preserve existing, add new ones discovered]

        ## Progress
        ### Done
        - [x] [Include previously done items AND newly completed items]

        ### In Progress
        - [ ] [Current work - update based on progress]

        ### Blocked
        - [Current blockers - remove if resolved]

        ## Key Decisions
        - **[Decision]**: [Brief rationale] (preserve all previous, add new)

        ## Next Steps
        1. [Update based on current state]

        ## Critical Context
        - [Preserve important context, add new if needed]

        Keep each section concise. Preserve exact file paths, function names, and error \
        messages.""";

    @Test
    void customInstructionsAppendsAdditionalFocus() {
        var captured = new Captured();

        generator(captured.fn(), 0)
            .summarize(ONE, null, "Focus on auth module", 20_000, "manual");

        assertThat(captured.promptText())
            .as("pi compaction.ts:719-720：Additional focus 后缀")
            .endsWith("\n\nAdditional focus: Focus on auth module");
    }

    @Test
    void modelOutputCapClampsTheEightyPercentReserve() {
        var capped = new Captured();
        generator(capped.fn(), 4_096)
            .summarize(ONE, null, null, 20_000, "manual");
        assertThat(capped.options.get(0).maxTokens())
            .as("min(16000, model maxOutputTokens=4096)")
            .hasValue(4_096);

        var uncapped = new Captured();
        generator(uncapped.fn(), 0)
            .summarize(ONE, null, null, 20_000, "manual");
        assertThat(uncapped.options.get(0).maxTokens())
            .as("未编目（0）⇒ 不封顶，16000")
            .hasValue(16_000);
    }
}
