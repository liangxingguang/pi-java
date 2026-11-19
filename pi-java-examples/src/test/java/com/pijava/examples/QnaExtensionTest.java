package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.ExtensionModelRegistry;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/52 §2.6：qna 验收——守卫、分支扫描、SYSTEM_PROMPT 逐字、complete 文本回填。 */
class QnaExtensionTest {

    private static Entry assistant(String id, StopReason stop, String text) {
        var msg = new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
            stop, null, "api", "provider", "model", null, null, null, null, null,
            Instant.ofEpochMilli(1), null, null, null, null);
        return new Entry.Message(id, 0, null, Instant.ofEpochMilli(1), msg, false);
    }

    private static ExtensionModelInfo model() {
        return new ExtensionModelInfo(ModelId.of("openai", "gpt-5.2"), "GPT", 128000, 8192);
    }

    @Test
    void guardsForModeAndModel() {
        var w = new W3Context();
        new QnaExtension().register(w.ctx);

        w.mode = "print";
        w.command("qna", "");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("qna requires interactive mode");

        w.mode = "tui";
        w.command("qna", "");
        assertThat(w.ui.notices()).last()
            .extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .isEqualTo("No model selected");
    }

    @Test
    void incompleteAssistantWarnsVerbatim() {
        var w = new W3Context();
        w.model = Optional.of(model());
        w.branch = List.of(assistant("a1", StopReason.LENGTH, "partial"));
        new QnaExtension().register(w.ctx);

        w.command("qna", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Last assistant message incomplete (LENGTH)");
        assertThat(w.ui.notices()).allSatisfy(n ->
            assertThat(n.type()).isEqualTo(NotifyType.ERROR));
    }

    @Test
    void noAssistantMessagesWarnsVerbatim() {
        var w = new W3Context();
        w.model = Optional.of(model());
        w.branch = List.of();
        new QnaExtension().register(w.ctx);

        w.command("qna", "");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No assistant messages found");
    }

    @Test
    void extractsQuestionsAndLoadsIntoEditor() {
        var w = new W3Context();
        w.model = Optional.of(model());
        w.branch = List.of(assistant("a1", StopReason.STOP, "Which DB?\nWhat language?"));
        w.modelRegistry = new ExtensionModelRegistry() {
            @Override
            public Optional<ExtensionModelInfo> find(String provider, String modelId) {
                return Optional.of(model());
            }

            @Override
            public boolean hasConfiguredAuth(ExtensionModelInfo m) {
                return true;
            }

            @Override
            public Message.AssistantMessage complete(ExtensionModelInfo m, Context context,
                                                     ExtensionCompleteOptions options) {
                assertThat(context.systemPrompt()).isEqualTo(QnaExtension.SYSTEM_PROMPT);
                assertThat(ExampleFixtures.textOf(context.messages().get(0).content()))
                    .isEqualTo("Which DB?\nWhat language?");
                return new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent("Q: Which DB?\nA: \n\nQ: What language?\nA: ")),
                    StopReason.STOP, null, "api", "provider", "model", null, null, null, null, null,
                    Instant.ofEpochMilli(1), null, null, null, null);
            }
        };
        new QnaExtension().register(w.ctx);

        w.command("qna", "");

        assertThat(w.ui.editorTexts()).containsExactly("Q: Which DB?\nA: \n\nQ: What language?\nA: ");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Questions loaded. Edit and submit when ready.");
    }

    @Test
    void abortedResponseCancels() {
        var w = new W3Context();
        w.model = Optional.of(model());
        w.branch = List.of(assistant("a1", StopReason.STOP, "question?"));
        w.modelRegistry = new ExtensionModelRegistry() {
            @Override
            public Optional<ExtensionModelInfo> find(String p, String m) { return Optional.of(model()); }
            @Override
            public boolean hasConfiguredAuth(ExtensionModelInfo m) { return true; }
            @Override
            public Message.AssistantMessage complete(ExtensionModelInfo m, Context c,
                                                     ExtensionCompleteOptions o) {
                return new Message.AssistantMessage(List.of(new ContentBlock.TextContent("")),
                    StopReason.ABORTED, null, "api", "provider", "model", null, null, null, null, null,
                    Instant.ofEpochMilli(1), null, null, null, null);
            }
        };
        new QnaExtension().register(w.ctx);

        w.command("qna", "");

        assertThat(w.ui.editorTexts()).isEmpty();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Cancelled");
    }
}
