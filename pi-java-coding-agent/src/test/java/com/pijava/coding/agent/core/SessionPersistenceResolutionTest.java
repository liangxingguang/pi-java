package com.pijava.coding.agent.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 批 14（docs/25 T6–T9，§7 #10）：会话解析按 pi {@code createSessionManager}
 * （main.ts:358-449）全序——--session 赢 -c/-r、-r SPI 缺席干净错误（D70）、
 * --session 全局臂三结局、--session-id warning＋带 id 新建、--fork 目标查重、
 * -c 空臂静默新建（B255 双臂）、not_found 文案逐字。
 */
class SessionPersistenceResolutionTest {

    @TempDir Path tmp;

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession rig(Path sessionDir, String... extra) throws Exception {
        var base = new String[] {
            "--provider", "faux-res", "--model", "hello",
            "--session-dir", sessionDir.toString()};
        var argv = new String[base.length + extra.length];
        System.arraycopy(base, 0, argv, 0, base.length);
        System.arraycopy(extra, 0, argv, base.length, extra.length);
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-res", List.of(reply("r"), reply("r2"))));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    private static Entry.Message user(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    /** 以别的 cwd 在同 root 里种一个跨项目会话（③ 全局臂命中的前提）。 */
    private static String seedForeignSession(Path dir, String foreignCwd) throws Exception {
        var repo = JsonlSessionRepository.over(dir);
        var foreign = repo.create(new JsonlSessionCreateOptions(null, foreignCwd, null, null));
        foreign.appendEntry(new ProvisionedEntry<>(user("foreign message")), "main");
        foreign.storage().drain();
        return foreign.getMetadata().id();
    }

    @Test
    void sessionFlagWinsOverContinue() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-order");
        String olderId;
        try (var a = rig(dir)) {
            a.processPrompt("first").status();
            olderId = a.sessionId();
        }
        String newerId;
        try (var b = rig(dir)) {
            b.processPrompt("second").status();
            newerId = b.sessionId();
        }

        try (var c = rig(dir, "--session", olderId, "-c")) {
            assertThat(c.sessionId())
                .as("pi 判定序 :391（--session）先于 :432（--continue）")
                .isEqualTo(olderId);
            assertThat(newerId).isNotEqualTo(olderId);
        }
    }

    @Test
    void resumeWithoutTuiSpiIsACleanError() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-spi");
        var thrown = catchThrowable(() -> rig(dir, "-r"));

        assertThat(thrown).isInstanceOf(SessionResolutionExit.class);
        var exit = (SessionResolutionExit) thrown;
        assertThat(exit.code()).isEqualTo(1);
        assertThat(exit.errorPrefix()).isTrue();
        assertThat(exit.getMessage()).as("窄差 D70 文案").isEqualTo(
            "--resume requires the pi-java-tui module on the classpath");
    }

    @Test
    void sessionNotFoundIsPiVerbatimError() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-nf");
        var thrown = catchThrowable(() -> rig(dir, "--session", "zzz-not-here"));

        assertThat(thrown).isInstanceOf(SessionResolutionExit.class);
        var exit = (SessionResolutionExit) thrown;
        assertThat(exit.code()).isEqualTo(1);
        assertThat(exit.errorPrefix()).isTrue();
        assertThat(exit.getMessage()).isEqualTo("No session found matching 'zzz-not-here'");
    }

    @Test
    void continueOnEmptyHistorySilentlyCreatesANewSession() throws Exception {
        // 裁决③/B255：pi continueRecent（:1800）无会话时静默新建，不报错。
        Path dir = Files.createTempDirectory("pi-res-cont");
        try (var s = rig(dir, "-c")) {
            assertThat(s.sessionId()).isNotBlank();
        }
    }

    @Test
    void continueOnEmptyInMemoryRegistrySilentlyCreates() {
        // B255 第二臂：内存仓同语义（orElseThrow ⇒ orElseGet 新建）。
        var args = ArgsParser.parse(new String[] {"--provider", "faux-res", "--model", "hello", "-c"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-res", List.of(reply("r"))));
        var session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        assertThat(session.sessionId()).isNotBlank();
    }

    @Test
    void sessionIdCreatesNewSessionWithThatIdAndWarns() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-sid");
        var err = new ByteArrayOutputStream();
        var originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            try (var s = rig(dir, "--session-id", "given-id-1")) {
                assertThat(s.sessionId()).as("T8：带 id 新建（修丢 id 现状）").isEqualTo("given-id-1");
            }
        } finally {
            System.setErr(originalErr);
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(
            "Warning: No project session found with id 'given-id-1';"
                + " creating a new session with that id.");
    }

    @Test
    void sessionIdOpensExistingProjectSession() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-sid-open");
        String id;
        try (var a = rig(dir)) {
            a.processPrompt("hi").status();
            id = a.sessionId();
        }

        try (var b = rig(dir, "--session-id", id)) {
            assertThat(b.sessionId()).isEqualTo(id);
            assertThat(b.accumulatedEntries()).as("attach 旧会话条目").isNotEmpty();
        }
    }

    @Test
    void forkWithExistingSessionIdFails() throws Exception {
        // pi :369-375（T9）：--fork 的 --session-id 目标已存在 ⇒ 干净错误 exit 1。
        Path dir = Files.createTempDirectory("pi-res-forkdup");
        String sourceId;
        try (var a = rig(dir)) {
            a.processPrompt("hi").status();
            sourceId = a.sessionId();
        }
        String otherId;
        try (var b = rig(dir)) {
            otherId = b.sessionId();
        }

        var thrown = catchThrowable(() -> rig(dir, "--fork", sourceId, "--session-id", otherId));
        assertThat(thrown).isInstanceOf(SessionResolutionExit.class);
        var exit = (SessionResolutionExit) thrown;
        assertThat(exit.code()).isEqualTo(1);
        assertThat(exit.errorPrefix()).isTrue();
        assertThat(exit.getMessage()).isEqualTo("Session already exists with id '" + otherId + "'");
    }

    @Test
    void globalSessionAbortsOnEof() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-global-eof");
        var foreignId = seedForeignSession(dir, "Z:/elsewhere");

        var originalIn = System.in;
        System.setIn(new ByteArrayInputStream(new byte[0])); // EOF ⇒ pi readline 关闭 ⇒ false
        try {
            var thrown = catchThrowable(() -> rig(dir, "--session", foreignId));
            assertThat(thrown).isInstanceOf(SessionResolutionExit.class);
            var exit = (SessionResolutionExit) thrown;
            assertThat(exit.code()).as("pi Aborted. ⇒ exit 0").isEqualTo(0);
            assertThat(exit.errorPrefix()).isFalse();
            assertThat(exit.getMessage()).isEqualTo("Aborted.");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void globalSessionForksIntoCurrentDirectoryOnYes() throws Exception {
        Path dir = Files.createTempDirectory("pi-res-global-yes");
        var foreignId = seedForeignSession(dir, "Z:/elsewhere");

        var originalIn = System.in;
        System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
        try {
            try (var s = rig(dir, "--session", foreignId)) {
                assertThat(s.sessionId()).as("fork 出新 id").isNotEqualTo(foreignId);
                assertThat(s.accumulatedEntries()).as("fork 拷贝源会话条目").isNotEmpty();
            }
        } finally {
            System.setIn(originalIn);
        }
    }
}
