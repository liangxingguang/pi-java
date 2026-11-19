package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.ExtensionModelRegistry;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/52 §2.7：summarize 验收——四个纯函数、选项对位、模型守卫、对话文本。 */
class SummarizeExtensionTest {

    private static ExtensionModelInfo model() {
        return new ExtensionModelInfo(ModelId.of("openai", "gpt-5.2"), "GPT", 128000, 8192);
    }

    private static Entry messageEntry(boolean user, List<ContentBlock> content) {
        Message msg = user
            ? new Message.UserMessage(content)
            : new Message.AssistantMessage(content, StopReason.STOP, null, "api", "provider",
                "model", null, null, null, null, null, Instant.ofEpochMilli(1), null, null,
                null, null);
        return new Entry.Message("e", 0, null, Instant.ofEpochMilli(1), msg, false);
    }

    @Test
    void extractTextPartsSkipsNonText() {
        var parts = SummarizeExtension.extractTextParts(List.of(
            new ContentBlock.TextContent("hello"),
            new ContentBlock.ThinkingContent("hidden"),
            new ContentBlock.TextContent("world")));
        assertThat(parts).containsExactly("hello", "world");
    }

    @Test
    void extractToolCallLinesUsesJsJson() {
        var lines = SummarizeExtension.extractToolCallLines(List.of(
            new ContentBlock.ToolUseContent("c1", "read", Map.of("path", "a.txt"),
                null, null, null)));
        assertThat(lines).containsExactly("Tool read was called with args {\"path\":\"a.txt\"}");
    }

    @Test
    void buildConversationTextProjectsUserAssistantAndToolLines() {
        var entries = List.of(
            messageEntry(true, List.of(new ContentBlock.TextContent("  question  "))),
            messageEntry(false, List.of(
                new ContentBlock.TextContent("answer"),
                new ContentBlock.ToolUseContent("c1", "bash", Map.of("command", "ls"),
                    null, null, null))));
        var text = SummarizeExtension.buildConversationText(entries);
        assertThat(text).isEqualTo(
            "User: question\n\nAssistant: answer\nTool bash was called with args {\"command\":\"ls\"}");
    }

    @Test
    void buildSummaryPromptIsVerbatim() {
        var prompt = SummarizeExtension.buildSummaryPrompt("DIALOGUE");
        assertThat(prompt).isEqualTo(String.join("\n",
            "Summarize this conversation so I can resume it later.",
            "Include goals, key decisions, progress, open questions, and next steps.",
            "Keep it concise and structured with headings.",
            "",
            "<conversation>",
            "DIALOGUE",
            "</conversation>"));
    }

    @Test
    void modelNotFoundWarnsVerbatim() {
        var w = new W3Context();
        w.branch = List.of(messageEntry(true, List.of(new ContentBlock.TextContent("q"))));
        w.modelRegistry = new ExtensionModelRegistry() {
            public Optional<ExtensionModelInfo> find(String p, String m) { return Optional.empty(); }
            public boolean hasConfiguredAuth(ExtensionModelInfo m) { return true; }
            public Message.AssistantMessage complete(ExtensionModelInfo m, Context c,
                                                     ExtensionCompleteOptions o) { throw new AssertionError(); }
        };
        new SummarizeExtension().register(w.ctx);

        w.command("summarize", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Preparing summary...", "Model openai/gpt-5.2 not found");
    }

    @Test
    void noAuthWarnsVerbatim() {
        var w = new W3Context();
        w.branch = List.of(messageEntry(true, List.of(new ContentBlock.TextContent("q"))));
        w.modelRegistry = new ExtensionModelRegistry() {
            public Optional<ExtensionModelInfo> find(String p, String m) { return Optional.of(model()); }
            public boolean hasConfiguredAuth(ExtensionModelInfo m) { return false; }
            public Message.AssistantMessage complete(ExtensionModelInfo m, Context c,
                                                     ExtensionCompleteOptions o) { throw new AssertionError(); }
        };
        new SummarizeExtension().register(w.ctx);

        w.command("summarize", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("No authentication configured for openai/gpt-5.2");
    }

    @Test
    void completeUsesHighReasoningNoneRetentionAndV7Session() {
        var w = new W3Context();
        w.branch = List.of(messageEntry(true, List.of(new ContentBlock.TextContent("question"))));
        w.modelRegistry = new ExtensionModelRegistry() {
            public Optional<ExtensionModelInfo> find(String p, String m) { return Optional.of(model()); }
            public boolean hasConfiguredAuth(ExtensionModelInfo m) { return true; }
            public Message.AssistantMessage complete(ExtensionModelInfo m, Context c,
                                                     ExtensionCompleteOptions o) {
                assertThat(o.maxTokens()).isEqualTo(OptionalInt.empty());
                assertThat(o.reasoning()).contains(new ThinkingLevel.High());
                assertThat(o.cacheRetention()).contains(CacheRetention.NONE);
                assertThat(o.sessionId()).isPresent();
                assertThat(java.util.UUID.fromString(o.sessionId().get()).version()).isEqualTo(7);
                assertThat(ExampleFixtures.textOf(c.messages().get(0).content()))
                    .isEqualTo(SummarizeExtension.buildSummaryPrompt("User: question"));
                return new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent("SUMMARY")),
                    StopReason.STOP, null, "api", "provider", "model", null, null, null, null, null,
                    Instant.ofEpochMilli(1), null, null, null, null);
            }
        };
        new SummarizeExtension().register(w.ctx);

        w.command("summarize", "");

        // 同步方言：ui.custom 工厂内 done(summary)，component render 含标题
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Preparing summary...");
    }

    @Test
    void noConversationTextWarnsOnlyWithUi() {
        var noUi = new W3Context();
        noUi.mode = "print";
        noUi.branch = List.of();
        new SummarizeExtension().register(noUi.ctx);
        noUi.command("summarize", "");
        assertThat(noUi.ui.notices()).isEmpty();

        var withUi = new W3Context();
        withUi.branch = List.of();
        new SummarizeExtension().register(withUi.ctx);
        withUi.command("summarize", "");
        assertThat(withUi.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No conversation text found");
        assertThat(withUi.ui.notices().get(0).type()).isEqualTo(NotifyType.WARNING);
    }
}
