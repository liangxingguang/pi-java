package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B30（map #48）：{@code setSessionName} 发射 {@code session_info_changed} ——
 * pi {@code agent-session.ts:3942-3947} 对位（3686 回归）；规范化对位
 * {@code session-manager.ts:1305}（5996 换行消毒回归）。
 *
 * <p>载荷是**规范化后的名字**，不是入参；空名 ⇒ 清除（载荷 {@code null} ≙ pi
 * 的 {@code undefined}）。</p>
 */
class SessionInfoChangedEventTest {

    private static AgentSession newSession() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-session-info-test");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-info", "--model", "hello", "--no-session"});
        return AgentSession.create(
            args, InMemorySessionRepository.create(), ProviderRegistry.create(),
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    @Test
    void emitsNormalizedNameOnRename() throws Exception {
        try (var session = newSession()) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(events::add);

            session.setSessionName("  my session  ");

            var emitted = events.stream()
                .filter(e -> e instanceof AgentSessionEvent.SessionInfoChanged)
                .map(e -> (AgentSessionEvent.SessionInfoChanged) e)
                .toList();
            assertThat(emitted).as("改名必发一条 session_info_changed").hasSize(1);
            assertThat(emitted.getFirst().name())
                .as("载荷是规范化名（trim 后），不是入参").isEqualTo("my session");
            assertThat(session.sessionName()).isEqualTo("my session");
        }
    }

    @Test
    void newlinesAreSanitizedBeforeEmit() throws Exception {
        try (var session = newSession()) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(events::add);

            session.setSessionName("line1\nline2\r\nline3");

            var emitted = events.stream()
                .filter(e -> e instanceof AgentSessionEvent.SessionInfoChanged)
                .map(e -> (AgentSessionEvent.SessionInfoChanged) e)
                .toList();
            assertThat(emitted).hasSize(1);
            // pi session-manager.ts:1305：replace(/[\r\n]+/g, " ").trim()
            assertThat(emitted.getFirst().name()).isEqualTo("line1 line2 line3");
        }
    }

    @Test
    void emptyNameClearsAndEmitsNull() throws Exception {
        try (var session = newSession()) {
            session.setSessionName("something");
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(events::add);

            session.setSessionName("   \n  ");

            var emitted = events.stream()
                .filter(e -> e instanceof AgentSessionEvent.SessionInfoChanged)
                .map(e -> (AgentSessionEvent.SessionInfoChanged) e)
                .toList();
            assertThat(emitted).hasSize(1);
            assertThat(emitted.getFirst().name())
                .as("空名 ⇒ 清除（null ≙ pi 的 undefined）").isNull();
            assertThat(session.sessionName()).isNull();
        }
    }
}
