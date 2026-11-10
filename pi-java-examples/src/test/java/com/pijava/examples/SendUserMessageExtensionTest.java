package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：send-user-message 验收——空闲时 {@code /ask} 与 {@code /askwith}
 * 真的发出用户消息并起轮，忙时 {@code /ask} 警告、{@code /followup} 以 FOLLOW_UP
 * 入队并通知；空参数给用法警告。
 */
class SendUserMessageExtensionTest {

    @TempDir Path tmp;

    @Test
    void askSendsUserMessageAndTriggersTurn() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, SendUserMessageExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.sequenceText("faux-sum", "answer", 3))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            run(session, "ask", "What is 2+2?");
            awaitIdle(session);

            assertThat(userTexts(session)).contains("What is 2+2?");
            assertThat(assistantTexts(session)).contains("answer");
        }
    }

    @Test
    void askwithSendsTwoTextBlocks() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, SendUserMessageExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.sequenceText("faux-sum2", "ok", 3))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            run(session, "askwith", "explain the bug");
            awaitIdle(session);

            // pi 的两段文本块按 "\n" 拼接（sendUserMessage 的数组形）
            assertThat(userTexts(session)).anySatisfy(text -> assertThat(text)
                .isEqualTo("User request: explain the bug\nPlease respond concisely."));
        }
    }

    @Test
    void emptyArgumentsWarnUsage() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, SendUserMessageExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-sum3", "ok"))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            run(session, "ask", "   ");
            run(session, "steer", "");
            run(session, "followup", "");
            run(session, "askwith", "");

            assertThat(ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
                .containsExactly("Usage: /ask <message>", "Usage: /steer <message>",
                    "Usage: /followup <message>", "Usage: /askwith <message>");
        }
    }

    @Test
    void idleSteerAndFollowupSendNormally() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, SendUserMessageExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.sequenceText("faux-sum4", "ok", 4))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            run(session, "steer", "go left");
            awaitIdle(session);
            run(session, "followup", "and then right");
            awaitIdle(session);

            assertThat(userTexts(session)).contains("go left", "and then right");
            // 忙才有 "Follow-up queued" 通知（空闲臂不发）
            assertThat(ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
                .doesNotContain("Follow-up queued");
        }
    }

    @Test
    void busyArmsWarnAndQueue() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, SendUserMessageExtension.class);
                var session = ExampleFixtures.newSession(tmp, FauxProvider.sequence("faux-sum5",
                    List.of(ExampleFixtures.textEvents("slow"), ExampleFixtures.textEvents("ok")),
                    200))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            var run = Thread.ofVirtual().start(() -> session.processPrompt("start"));
            awaitRunning(session);

            run(session, "ask", "mid-run question");
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Agent is busy. Use /steer or /followup instead."));

            run(session, "followup", "later question");
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.type())
                .isEqualTo(NotifyType.INFO));
            assertThat(ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
                .contains("Follow-up queued");

            run.join();
            awaitIdle(session);
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────

    private static void run(AgentSession session, String name, String args) {
        var command = session.services().slashCommands().get(name);
        assertThat(command).as("/%s 已注册", name).isNotNull();
        command.execute(args, SlashContext.of(session)).toCompletableFuture().join();
    }

    private static void awaitRunning(AgentSession session) throws InterruptedException {
        for (int i = 0; i < 100 && !session.isRunning(); i++) {
            Thread.sleep(20);
        }
        assertThat(session.isRunning()).isTrue();
    }

    private static void awaitIdle(AgentSession session) throws InterruptedException {
        for (int i = 0; i < 200 && session.isRunning(); i++) {
            Thread.sleep(20);
        }
        assertThat(session.isRunning()).isFalse();
    }

    private static List<String> userTexts(AgentSession session) {
        return entries(session).stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .map(Entry.Message::message)
            .filter(Message.UserMessage.class::isInstance)
            .map(Message.UserMessage.class::cast)
            .map(message -> {
                var texts = message.content().stream()
                    .filter(ContentBlock.TextContent.class::isInstance)
                    .map(block -> ((ContentBlock.TextContent) block).text())
                    .toList();
                return String.join("\n", texts);
            })
            .toList();
    }

    private static List<String> assistantTexts(AgentSession session) {
        return entries(session).stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .map(Entry.Message::message)
            .filter(Message.AssistantMessage.class::isInstance)
            .map(Message.AssistantMessage.class::cast)
            .map(message -> String.join("\n", message.content().stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(block -> ((ContentBlock.TextContent) block).text())
                .toList()))
            .toList();
    }

    private static List<Entry> entries(AgentSession session) {
        return List.copyOf(session.accumulatedEntries());
    }
}
