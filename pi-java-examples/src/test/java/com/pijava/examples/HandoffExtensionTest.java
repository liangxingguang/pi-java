package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：handoff 验收——分支投影（压缩摘要＋{@code firstKeptEntryId} 之后）、
 * 非 tui／空目标／无对话三条报错臂、生成成功后的编辑器回填与取消切换通知。
 */
class HandoffExtensionTest {

    @TempDir Path tmp;

    // ── 分支投影（纯逻辑）────────────────────────────────────────

    @Test
    void handoffMessagesIncludeCompactionAndKeptEntries() {
        var user = messageEntry("u1", new Message.UserMessage(List.of(
            new ContentBlock.TextContent("old question"))));
        var kept = messageEntry("m1", new Message.UserMessage(List.of(
            new ContentBlock.TextContent("kept question"))));
        var answer = messageEntry("m2", new Message.AssistantMessage(List.of(
            new ContentBlock.TextContent("kept answer"))));
        var tail = messageEntry("m3", new Message.UserMessage(List.of(
            new ContentBlock.TextContent("after compaction"))));
        // pi 的会话形状：压缩条目在最旧保留条目之后（firstKeptEntryId 指回前者）
        var compaction = new Entry.Compaction("c1", 1, null, Instant.EPOCH, "SUMMARY",
            "m1", List.of(), 4_321, null, null, null, null);

        var messages = HandoffExtension.handoffMessages(
            List.of(user, kept, answer, compaction, tail));

        assertThat(messages).hasSize(4);
        var first = (ContentBlock.TextContent) messages.get(0).content().get(0);
        assertThat(first.text()).isEqualTo(ContextEntries.compactionText("SUMMARY"));
        assertThat(messages.get(1)).isSameAs(kept.message());
        assertThat(messages.get(2)).isSameAs(answer.message());
        assertThat(messages.get(3)).isSameAs(tail.message());
    }

    @Test
    void handoffMessagesWithoutCompactionUseWholeBranch() {
        var a = messageEntry("a", new Message.UserMessage(List.of(
            new ContentBlock.TextContent("hello"))));
        assertThat(HandoffExtension.handoffMessages(List.of(a))).hasSize(1);
        // bash 条目按 ContextEntries 的 user 文本投影；excludeFromContext 臂不计
        var bash = new Entry.BashExecution("b1", 1, null, Instant.EPOCH, "ls", "out",
            0, false, false, null, false);
        var skipped = new Entry.BashExecution("b2", 1, null, Instant.EPOCH, "ls", "out",
            0, false, false, null, true);
        assertThat(HandoffExtension.handoffMessages(List.of(a, bash))).hasSize(2);
        assertThat(HandoffExtension.handoffMessages(List.of(a, bash, skipped))).hasSize(2);
    }

    // ── 报错臂 ─────────────────────────────────────────────────

    @Test
    void nonTuiModeIsRejected() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, HandoffExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-ho", "ok"))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "print");

            handoff(session, "do the thing");
            assertThat(ui.notices()).anySatisfy(notice -> {
                assertThat(notice.message()).isEqualTo("handoff requires interactive mode");
                assertThat(notice.type()).isEqualTo(NotifyType.ERROR);
            });
        }
    }

    @Test
    void emptyGoalReportsUsage() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, HandoffExtension.class);
                var session = catalogSession("ok")) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            handoff(session, "   ");
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Usage: /handoff <goal for new thread>"));
        }
    }

    @Test
    void generatedPromptIsNotCancelledWhenEditorCancels() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, HandoffExtension.class);
                var session = catalogSession("GENERATED PROMPT")) {
            var ui = new ExampleFixtures.ScriptedUi().editorAnswer((String) null);
            session.extensionUI(ui, "tui");
            session.processPrompt("we fixed the parser", PromptConfig.defaults()).status();

            handoff(session, "continue with the lexer");
            // 生成成功 ⇒ 进入编辑；编辑器取消 ⇒ "Cancelled"
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Cancelled"));
            assertThat(ui.editorTexts()).isEmpty();
        }
    }

    @Test
    void successfulHandoffSwitchesSessionUnlessCancelled() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, HandoffExtension.class,
                SwitchCanceller.class);
                var session = catalogSession("GENERATED PROMPT")) {
            var ui = new ExampleFixtures.ScriptedUi().editorAnswer("edited prompt");
            session.extensionUI(ui, "tui");
            session.processPrompt("we fixed the parser", PromptConfig.defaults()).status();

            handoff(session, "continue with the lexer");
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("New session cancelled"));
        }
    }

    /**
     * 目录模型的会话：{@code ctx.getModel()} 需要可解析的模型（pi 的
     * {@code ctx.model}），faux provider 名字取 {@code anthropic} 以服务该模型。
     */
    private com.pijava.coding.agent.core.AgentSession catalogSession(String replyPrefix) {
        var session = ExampleFixtures.newSession(tmp, ExampleFixtures.sequenceText(
            "anthropic", replyPrefix, 4), "claude-sonnet-4-5");
        session.setModel(com.pijava.ai.model.ModelId.of("anthropic", "claude-sonnet-4-5"));
        return session;
    }

    private static void handoff(com.pijava.coding.agent.core.AgentSession session, String args) {
        var command = session.services().slashCommands().get("handoff");
        assertThat(command).isNotNull();
        command.execute(args, SlashContext.of(session)).toCompletableFuture().join();
    }

    private static Entry.Message messageEntry(String id, Message message) {
        return new Entry.Message(id, 1, null, Instant.EPOCH, message, null);
    }

    /** 伴生扩展：取消任何 {@code session_before_switch}（验证替换侧回执）。 */
    public static final class SwitchCanceller implements PiExtension {

        @Override
        public String name() {
            return "handoff-switch-canceller";
        }

        @Override
        public void register(ExtensionContext ctx) {
            ctx.on(ExtensionEventSpec.SESSION_BEFORE_SWITCH,
                (event, c) -> new SessionBeforeSwitchResult(Boolean.TRUE));
        }
    }
}
