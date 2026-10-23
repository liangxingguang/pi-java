package com.pijava.tui.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.TuiEventDispatcher;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 15（T2a/T2d）：启动崩溃提示装配（pi :1220-1226）＋ /bug 建议门四臂
 * （pi :2168-2190 maybeSuggestBugReport）。
 */
class CrashNoticeTest {

    /** 夹具会话落盘到临时目录（原 docs/39 裁决 B）。 */
    @TempDir
    Path sessionDir;

    private static final String SUGGESTION =
        "If this looks like a pi-java bug, /bug sends a report to the developers.";

    private PiTuiApp newApp(AgentSession session, ChatScreen chatScreen) {
        return new PiTuiApp(new InteractiveMode(session), chatScreen,
            new KeybindingsManager(), new TuiEventDispatcher());
    }

    private static List<String> systemTexts(ChatScreen chatScreen) {
        return chatScreen.messages().stream()
            .filter(ChatMessage.System.class::isInstance)
            .map(m -> ((ChatMessage.System) m).text())
            .toList();
    }

    private static Message.AssistantMessage assistant(StopReason reason, String errorMessage) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("t")), reason, null,
            "anthropic-messages", "anthropic", "claude-fable-5", null, null, null,
            null, null, java.time.Instant.now(), errorMessage, null, null);
    }

    @Test
    void startupCrashNoticeAppearsAfterWelcomeOnce() throws Exception {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        var chatScreen = new ChatScreen();
        var app = newApp(session, chatScreen);
        app.setStartupCrashNotice(
            "pi-java crashed on 10/23/26, 10:00:00 AM (boom)."
                + " Run /bug to report it; the crash details are attached automatically.");
        var shell = InlineTuiShell.createForTest(new FakeBackend());
        try {
            app.startInline(shell);
            assertThat(systemTexts(chatScreen)).anySatisfy(text ->
                assertThat(text).startsWith("pi-java crashed on ")
                    .contains("(boom). Run /bug to report it"));
            // 一次性：再触发不再有（字段已消费）。
            assertThat(systemTexts(chatScreen).stream()
                .filter(t -> t.startsWith("pi-java crashed on ")).count()).isEqualTo(1);
        } finally {
            shell.close();
            session.close();
        }
    }

    @Test
    void noStartupCrashNoticeShowsNothing() throws Exception {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        var chatScreen = new ChatScreen();
        var app = newApp(session, chatScreen);
        var shell = InlineTuiShell.createForTest(new FakeBackend());
        try {
            app.startInline(shell);
            assertThat(systemTexts(chatScreen))
                .noneMatch(t -> t.startsWith("pi-java crashed on "));
        } finally {
            shell.close();
            session.close();
        }
    }

    @Test
    void nonRetryableErrorSuggestsBugReportExactlyOnce() throws Exception {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        var chatScreen = new ChatScreen();
        newApp(session, chatScreen);
        try {
            var error = assistant(StopReason.ERROR, "Unexpected response schema");
            chatScreen.onSessionEvent(new AgentSessionEvent.MessageEnd(error));
            assertThat(systemTexts(chatScreen)).contains(SUGGESTION);

            // 每进程最多一次（pi bugReportHintShown）。
            chatScreen.onSessionEvent(new AgentSessionEvent.MessageEnd(error));
            assertThat(systemTexts(chatScreen).stream()
                .filter(SUGGESTION::equals).count()).isEqualTo(1);
        } finally {
            session.close();
        }
    }

    @Test
    void retryableErrorDoesNotSuggest() throws Exception {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        var chatScreen = new ChatScreen();
        newApp(session, chatScreen);
        try {
            chatScreen.onSessionEvent(new AgentSessionEvent.MessageEnd(
                assistant(StopReason.ERROR, "Provider overloaded, try again")));
            assertThat(systemTexts(chatScreen)).doesNotContain(SUGGESTION);
        } finally {
            session.close();
        }
    }

    @Test
    void abortCancelWordDoesNotSuggest() throws Exception {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        var chatScreen = new ChatScreen();
        newApp(session, chatScreen);
        try {
            chatScreen.onSessionEvent(new AgentSessionEvent.MessageEnd(
                assistant(StopReason.ERROR, "Request was cancelled by user")));
            assertThat(systemTexts(chatScreen)).doesNotContain(SUGGESTION);
        } finally {
            session.close();
        }
    }

    @Test
    void nonErrorStopReasonDoesNotSuggest() throws Exception {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        var chatScreen = new ChatScreen();
        newApp(session, chatScreen);
        try {
            chatScreen.onSessionEvent(new AgentSessionEvent.MessageEnd(
                assistant(StopReason.STOP, null)));
            assertThat(systemTexts(chatScreen)).doesNotContain(SUGGESTION);
        } finally {
            session.close();
        }
    }
}
