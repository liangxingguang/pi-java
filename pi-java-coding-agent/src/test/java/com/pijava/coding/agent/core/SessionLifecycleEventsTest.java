package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_AGENT_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.INPUT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_FORK;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_SWITCH;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.USER_BASH;

/**
 * docs/15 T3 集成测试：生命周期发射点（B250）与宿主结果臂（B249 的
 * input/user_bash/before_agent_start）在真实 {@code AgentSession} 上的端到端对拍。
 * 扩展经 TCCL 作用域 loader 注入（不污染全局 classpath，批 4 先例）。
 */
class SessionLifecycleEventsTest {

    /** 录制扩展：全部经静态开关驱动（ServiceLoader 无参构造 ⇒ 静态汇）。 */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean cancelSwitch;
    static volatile boolean cancelFork;
    static volatile String transformTo;
    static volatile boolean handleInput;
    static volatile String systemPromptOverride;
    static volatile ShellResult bashReplacement;

    /** 测试扩展：订阅本批全部宿主发射点并录制。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_START, (e, c) -> {
                EVENTS.add("start:" + e.reason() + ":" + e.previousSessionFile());
                return null;
            });
            ctx.on(SESSION_SHUTDOWN, (e, c) -> {
                EVENTS.add("shutdown:" + e.reason() + ":" + e.targetSessionFile());
                return null;
            });
            ctx.on(SESSION_BEFORE_SWITCH, (e, c) -> {
                EVENTS.add("before_switch:" + e.reason() + ":" + e.targetSessionFile());
                return new SessionBeforeSwitchResult(cancelSwitch);
            });
            ctx.on(SESSION_BEFORE_FORK, (e, c) -> {
                EVENTS.add("before_fork:" + e.entryId() + ":" + e.position());
                return new SessionBeforeForkResult(cancelFork, null);
            });
            ctx.on(INPUT, (e, c) -> {
                EVENTS.add("input:" + e.text() + ":" + e.source());
                if (handleInput) {
                    return new InputEventResult.Handled();
                }
                return transformTo != null
                    ? new InputEventResult.Transform(transformTo, null) : null;
            });
            ctx.on(USER_BASH, (e, c) -> {
                EVENTS.add("user_bash:" + e.command());
                return bashReplacement != null
                    ? new UserBashEventResult(bashReplacement) : null;
            });
            ctx.on(BEFORE_AGENT_START, (e, c) -> {
                EVENTS.add("before_agent_start:" + e.prompt() + ":" + e.systemPrompt());
                var draft = new CustomMessageDraft("hook-note",
                    new com.pijava.agent.entry.CustomMessageContent.Text("from extension"),
                    true, Map.of());
                return new BeforeAgentStartEventResult(draft, systemPromptOverride);
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        cancelSwitch = false;
        cancelFork = false;
        transformTo = null;
        handleInput = false;
        systemPromptOverride = null;
        bashReplacement = null;
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Recorder.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private AgentSession session(InMemorySessionRepository repo, String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-lc",
            List.of(reply("ok"), reply("ok2"), reply("ok3"))));
        return AgentSession.create(args, repo, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private static List<StreamEvent> reply(String text) {
        var done = com.pijava.ai.message.AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(com.pijava.ai.message.StopReason.STOP);
        return List.of(
            new StreamEvent.Start(com.pijava.ai.message.AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(com.pijava.ai.message.StopReason.STOP, null, done));
    }

    // ── B250：生命周期发射点 ──

    @Test
    void sessionStartFiresAtAssemblyWithStartupReason() {
        try (var s = session(InMemorySessionRepository.create(), "--session-id", "lc1")) {
            assertThat(EVENTS).contains("start:startup:null");
        }
    }

    @Test
    void sessionShutdownFiresOnCloseWithQuitReason() {
        var s = session(InMemorySessionRepository.create(), "--session-id", "lc2");
        EVENTS.clear();
        s.close();
        assertThat(EVENTS).contains("shutdown:quit:null");
    }

    @Test
    void switchToFiresBeforeShutdownStartSequence() throws Exception {
        var repo = InMemorySessionRepository.create();
        try (var a = session(repo, "--session-id", "a");
             var b = session(repo, "--session-id", "b")) {
            EVENTS.clear();
            var result = a.switchTo("b");
            // 内存路 find 返回另一会话对象；序列与 pi switchSession 同序。
            assertThat(result.outcome()).isEqualTo(AgentSession.SwitchOutcome.SWITCHED);
            assertThat(EVENTS).containsExactly(
                "before_switch:resume:b",
                "shutdown:resume:b",
                "start:resume:" + a.sessionId());
        }
    }

    @Test
    void switchToCancelLeavesOldSessionUntouched() {
        var repo = InMemorySessionRepository.create();
        try (var a = session(repo, "--session-id", "a2");
             var b = session(repo, "--session-id", "b2")) {
            cancelSwitch = true;
            EVENTS.clear();
            var result = a.switchTo("b2");
            assertThat(result.outcome()).isEqualTo(AgentSession.SwitchOutcome.CANCELLED);
            assertThat(result.session()).isSameAs(a);
            // cancel ⇒ 旧会话不动：无 shutdown/start（pi :201-203）。
            assertThat(EVENTS).containsExactly("before_switch:resume:b2");
        }
    }

    @Test
    void forkCopyFiresBeforeForkAndForkShutdown() {
        try (var a = session(InMemorySessionRepository.create(), "--session-id", "fa")) {
            EVENTS.clear();
            var forked = a.forkCopy("branch");
            assertThat(forked).isPresent();
            assertThat(EVENTS.get(0)).startsWith("before_fork:");
            assertThat(EVENTS.get(0)).endsWith(":at");
            assertThat(EVENTS).anyMatch(e -> e.startsWith("shutdown:fork:"));

            cancelFork = true;
            EVENTS.clear();
            assertThat(a.forkCopy("branch2")).isEmpty();
            assertThat(EVENTS).hasSize(1); // 仅 before_fork，取消即返回
            assertThat(EVENTS.get(0)).startsWith("before_fork:");
        }
    }

    // ── B249③：input / before_agent_start / user_bash ──

    @Test
    void inputTransformFeedsExpansionAndDrive() {
        try (var s = session(InMemorySessionRepository.create(), "--session-id", "in1")) {
            transformTo = "transformed text";
            var result = s.processPrompt("original");
            assertThat(result.disposition()).isEqualTo("started");
            result.status();
            // input 先于展开（pi :1991）：驱动拿到的是变换后文本。
            assertThat(EVENTS).contains("input:original:interactive");
            assertThat(EVENTS).anyMatch(e -> e.equals("before_agent_start:transformed text:"
                + s.harness().getSystemPrompt()));
            var userTexts = s.harness().snapshot(s.laneName()).transcript().stream()
                .filter(e -> e instanceof Entry.Message)
                .map(e -> ((Entry.Message) e).message())
                .filter(m -> "user".equals(m.role()))
                .map(m -> ((ContentBlock.TextContent) m.content().get(0)).text())
                .toList();
            assertThat(userTexts).contains("transformed text");
        }
    }

    @Test
    void inputHandledSkipsExpansionAndDrive() {
        try (var s = session(InMemorySessionRepository.create(), "--session-id", "in2")) {
            handleInput = true;
            int before = s.harness().snapshot(s.laneName()).transcript().size();
            var result = s.processPrompt("swallow this");
            // pi preflightResult("handled")：不展开、不排队、不驱动。
            assertThat(result.disposition()).isEqualTo("handled");
            assertThat(s.harness().snapshot(s.laneName()).transcript().size())
                .isEqualTo(before);
            assertThat(EVENTS).noneMatch(e -> e.startsWith("before_agent_start:"));
        }
    }

    @Test
    void beforeAgentStartInjectsCustomMessageAndOverridesSystemPrompt() {
        try (var s = session(InMemorySessionRepository.create(), "--session-id", "ba1")) {
            systemPromptOverride = "ext system prompt";
            s.processPrompt("hello").status();
            // systemPrompt 覆盖生效（harness 全局态，窄差 D11）。
            assertThat(s.harness().getSystemPrompt()).isEqualTo("ext system prompt");
            // result.message 落成 custom message 条目。
            var customs = s.harness().snapshot(s.laneName()).transcript().stream()
                .filter(e -> e instanceof Entry.CustomMessage)
                .map(e -> (Entry.CustomMessage) e)
                .toList();
            assertThat(customs).hasSize(1);
            assertThat(customs.get(0).customType()).isEqualTo("hook-note");
        }
    }

    @Test
    void userBashResultArmReplacesExecution() {
        try (var s = session(InMemorySessionRepository.create(), "--session-id", "ub1")) {
            bashReplacement = new ShellResult("ext-bash-output", 3, false, false, 1, 15);
            var result = s.executeBash("b1", "echo real", false);
            // {result} 臂：不执行真命令、直接落盘返回（pi rpc-mode.ts:568-575）。
            assertThat(result.output()).isEqualTo("ext-bash-output");
            assertThat(result.exitCode()).isEqualTo(3);
            var bashEntries = s.harness().snapshot(s.laneName()).transcript().stream()
                .filter(e -> e instanceof Entry.BashExecution)
                .map(e -> (Entry.BashExecution) e)
                .toList();
            assertThat(bashEntries).hasSize(1);
            assertThat(bashEntries.get(0).output()).isEqualTo("ext-bash-output");
        }
    }
}
