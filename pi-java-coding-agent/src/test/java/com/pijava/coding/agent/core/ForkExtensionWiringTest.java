package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_FORK;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;

/**
 * docs/16（T1/B251）：fork 装配重建 —— forked 会话全量重装扩展（工具/命令/事件
 * 订阅），{@code session_start{fork, previousSessionFile}} 有发射点；序对位 pi
 * {@code fork}（agent-session-runtime.ts:262-345）：源侧 {@code shutdown{fork}}
 * 先于 copy 的 {@code start{fork}}。扩展经 TCCL 作用域 loader 注入（批 4/5 先例）。
 */
class ForkExtensionWiringTest {

    static final String PROBE_TOOL = "fork-probe-tool";
    static final String PROBE_COMMAND = "fork-probe-cmd";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 探针扩展：录生命周期事件＋注册一个工具与一条 slash 命令。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "fork-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_START, (e, c) -> {
                EVENTS.add("start:" + e.reason() + ":" + e.previousSessionFile());
                return null;
            });
            ctx.on(SESSION_SHUTDOWN, (e, c) -> {
                EVENTS.add("shutdown:" + e.reason() + ":" + e.targetSessionFile());
                return null;
            });
            ctx.on(SESSION_BEFORE_FORK, (e, c) -> {
                EVENTS.add("before_fork:" + e.entryId() + ":" + e.position());
                return new SessionBeforeForkResult(false, null);
            });
            ctx.tools().register(probeTool());
            ctx.slashCommands().register(probeCommand());
        }
    }

    private static AgentTool<Map<String, Object>, Void> probeTool() {
        return new AgentTool<>() {
            @Override public String name() { return PROBE_TOOL; }
            @Override public String label() { return PROBE_TOOL; }
            @Override public String description() { return "fork probe"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }
            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Parallel();
            }
            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate, ToolContext context) {
                return ToolResult.success("done");
            }
        };
    }

    private static SlashCommand probeCommand() {
        return new SlashCommand() {
            @Override public String name() { return PROBE_COMMAND; }
            @Override public String description() { return "fork probe"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                return CompletableFuture.completedFuture("ok");
            }
        };
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Probe.class.getName() + "\n");
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
        providers.register(FauxProvider.sequence("faux-fw",
            List.of(reply("ok"), reply("ok2"))));
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

    private static int indexOfPrefix(List<String> events, String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** ①②：copy 收到 start{fork, 源 id}，且源侧 shutdown{fork, copy id} 先于它（pi §1.2 序）。 */
    @Test
    void forkedSessionReceivesStartForkAfterSourceShutdown() {
        try (var a = session(InMemorySessionRepository.create(), "--session-id", "fw1")) {
            EVENTS.clear();
            var copy = a.forkCopy("branch").orElseThrow();

            var shutdownIdx = indexOfPrefix(EVENTS, "shutdown:fork:");
            var startIdx = indexOfPrefix(EVENTS, "start:fork:");
            assertThat(EVENTS.get(0)).as("before_fork 最先").startsWith("before_fork:");
            assertThat(shutdownIdx).as("源侧 shutdown{fork} 发射").isPositive();
            assertThat(EVENTS.get(shutdownIdx))
                .as("shutdown 的 targetSessionFile＝copy 会话 id")
                .isEqualTo("shutdown:fork:" + copy.sessionId());
            assertThat(startIdx)
                .as("pi 逐字序：teardown（shutdown）先于 rebind（start）")
                .isGreaterThan(shutdownIdx);
            assertThat(EVENTS.get(startIdx))
                .as("start{fork} 的 previousSessionFile＝源会话 id（窄差 D14 延续：装会话 id）")
                .isEqualTo("start:fork:" + a.sessionId());
        }
    }

    /** ③④：fresh 注册表 —— copy 含扩展工具/命令各一份，二次 fork 不叠加（裁决②）。 */
    @Test
    void forkedSessionRegistriesAreFreshWithExactlyOneProbeEach() {
        try (var a = session(InMemorySessionRepository.create(), "--session-id", "fw2")) {
            var copy = a.forkCopy("branch").orElseThrow();
            assertThat(copy.services().tools().get(PROBE_TOOL))
                .as("forked 的 ToolRegistry 含扩展工具").isNotNull();
            assertThat(copy.services().tools().toolNames().stream()
                .filter(PROBE_TOOL::equals).count())
                .as("fresh 注册表：恰一份，不从父会话叠加").isEqualTo(1);
            assertThat(copy.services().slashCommands().get(PROBE_COMMAND))
                .as("forked 的 CommandRegistry 含扩展命令").isNotNull();

            var copy2 = copy.forkCopy("branch2").orElseThrow();
            assertThat(copy2.services().tools().toolNames().stream()
                .filter(PROBE_TOOL::equals).count())
                .as("二次 fork 仍恰一份（pi 每运行时 fresh services 对位）").isEqualTo(1);
        }
    }

    /** ⑤：--no-extensions ⇒ 零 firing、fork 行为不变（pi 无扩展＝runner 空表）。 */
    @Test
    void noExtensionsSkipsAllFiringButForkStillWorks() {
        try (var a = session(InMemorySessionRepository.create(),
                "--session-id", "fw3", "--no-extensions")) {
            EVENTS.clear();
            var copy = a.forkCopy("branch").orElseThrow();
            assertThat(EVENTS).isEmpty();
            assertThat(copy.services().tools().get(PROBE_TOOL)).isNull();
            assertThat(copy.harness()).isNotSameAs(a.harness());
        }
    }
}
