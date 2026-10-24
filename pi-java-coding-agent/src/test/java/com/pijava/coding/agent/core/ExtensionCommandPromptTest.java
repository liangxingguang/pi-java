package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.INPUT;

/**
 * 批 20（B237，T1b/T1c）：扩展命令臂（pi agent-session.ts:1978-1984/
 * _tryExecuteExtensionCommand :2116-2141）与 steer/followUp 全序
 * （_queueUserInput :2173-2200／_throwIfExtensionCommand :2268-2279）对拍。
 *
 * <p>扩展命令直接注册进共享注册表的打标视图（{@code extensionView}）；
 * input 事件与 extension_error 观察经 TCCL 作用域 loader 注入录制扩展
 * （批 4/5/15 先例）。</p>
 */
class ExtensionCommandPromptTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean handleInput;
    static volatile String transformTo;

    /** 录制扩展：input 事件（静态开关驱动）。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "ext-cmd-recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(INPUT, (e, c) -> {
                EVENTS.add("input:" + e.text() + ":" + e.source() + ":"
                    + e.streamingBehavior());
                if (handleInput) {
                    return new InputEventResult.Handled();
                }
                return transformTo != null
                    ? new InputEventResult.Transform(transformTo, null) : null;
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        handleInput = false;
        transformTo = null;
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

    /** 探针命令：录制 name/args 或按开关抛错。 */
    private static final class ProbeCommand implements SlashCommand {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile Throwable failure;

        private final String name;

        ProbeCommand(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return name + " probe"; }
        @Override public String argumentHint() { return ""; }

        @Override
        public CompletionStage<String> execute(String args, SlashContext context) {
            calls.add(args);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture("ok");
        }
    }

    private AgentSession session(String... argv) {
        var full = new String[argv.length + 4];
        full[0] = "--provider";
        full[1] = "faux-ext";
        full[2] = "--model";
        full[3] = "hello";
        System.arraycopy(argv, 0, full, 4, argv.length);
        var args = ArgsParser.parse(full);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-ext",
            List.of(reply("r1"), reply("r2"), reply("r3"), reply("r4"))));
        return AgentSession.create(args,
            com.pijava.coding.agent.core.session.InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
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

    // ── T1b：processPrompt 扩展命令臂（pi :1978-1984）──

    @Test
    void promptExecutesExtensionCommandAndReturnsHandled() {
        try (var s = session("--session-id", "ec1")) {
            var probe = new ProbeCommand("hello");
            s.services().slashCommands().extensionView().register(probe);

            var result = s.processPrompt("/hello world", PromptConfig.defaults());

            assertThat(result.disposition()).isEqualTo("handled");
            assertThat(probe.calls).containsExactly("world");
            assertThat(s.harness().isRunning(s.laneName()))
                .as("handled ⇒ 不起 run").isFalse();
        }
    }

    @Test
    void promptCommandArgsAreNotTrimmed() {
        try (var s = session("--session-id", "ec2")) {
            var probe = new ProbeCommand("hello");
            s.services().slashCommands().extensionView().register(probe);

            // pi :2118-2120 逐字：spaceIndex + 1 起切——前导空格保留。
            s.processPrompt("/hello  world", PromptConfig.defaults());

            assertThat(probe.calls).containsExactly(" world");
        }
    }

    @Test
    void unknownSlashFallsThroughToRun() {
        try (var s = session("--session-id", "ec3")) {
            var result = s.processPrompt("/nosuchcmd", PromptConfig.defaults());
            assertThat(result.disposition()).isEqualTo("started");
            result.status(); // 等 run 落地（FauxProvider 即时回复）
        }
    }

    @Test
    void builtinCommandsAreNotInterceptedByPromptArm() {
        try (var s = session("--session-id", "ec4")) {
            // 内置命令不在扩展命令表——"/model" 落 prompt（pi RPC 同形：
            // 内置件是 TUI 本地件，AgentSession.prompt 不执行）。
            var result = s.processPrompt("/model", PromptConfig.defaults());
            assertThat(result.disposition()).isEqualTo("started");
            result.status();
        }
    }

    @Test
    void commandErrorIsStillHandledAndEmitsExtensionError() throws Exception {
        try (var s = session("--session-id", "ec5")) {
            var probe = new ProbeCommand("boom");
            probe.failure = new IllegalStateException("kaput");
            s.services().slashCommands().extensionView().register(probe);
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var ignored = s.subscribe(events::add)) {
                var result = s.processPrompt("/boom", PromptConfig.defaults());

                // pi :2131-2139 逐字：出错也是 handled。
                assertThat(result.disposition()).isEqualTo("handled");
                assertThat(events).anySatisfy(e -> {
                    assertThat(e).isInstanceOf(AgentSessionEvent.ExtensionError.class);
                    var err = ((AgentSessionEvent.ExtensionError) e).error();
                    // pi emitError 载荷逐字：extensionPath "command:<name>"、event "command"。
                    assertThat(err.extensionName()).isEqualTo("command:boom");
                    assertThat(err.event()).isEqualTo("command");
                    assertThat(err.message()).isEqualTo("kaput");
                });
            }
        }
    }

    // ── T1c：steer/followUp 全序（pi :2173-2200）──

    @Test
    void steerReturnsHandledWhenInputConsumes() {
        try (var s = session("--session-id", "ec6")) {
            handleInput = true;
            assertThat(s.steer("take this")).isEqualTo("handled");
            var cleared = s.clearQueue();
            assertThat(cleared.steering()).as("handled ⇒ 不入队").isEmpty();
            assertThat(EVENTS).contains("input:take this:interactive:null");
        }
    }

    @Test
    void followUpQueuesTransformedText() {
        try (var s = session("--session-id", "ec7")) {
            transformTo = "changed";
            assertThat(s.followUp("orig")).isEqualTo("queued");
            assertThat(s.clearQueue().followUp()).containsExactly("changed");
        }
    }

    @Test
    void steerThrowsVerbatimOnExtensionCommand() {
        try (var s = session("--session-id", "ec8")) {
            s.services().slashCommands().extensionView().register(new ProbeCommand("hello"));
            // pi :2273-2276 错文逐字。
            assertThatThrownBy(() -> s.steer("/hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Extension command \"/hello\" cannot be queued."
                    + " Use prompt() or execute the command when not streaming.");
        }
    }

    @Test
    void steerAllowsUnknownSlashText() {
        try (var s = session("--session-id", "ec9")) {
            assertThat(s.steer("/nosuchcmd")).isEqualTo("queued");
            assertThat(s.clearQueue().steering()).containsExactly("/nosuchcmd");
        }
    }

    @Test
    void inputEventCarriesExplicitSource() {
        try (var s = session("--session-id", "ec10")) {
            s.steer("via rpc", null, "rpc");
            assertThat(EVENTS).contains("input:via rpc:rpc:null");
        }
    }

    @Test
    void followUpSkillAndTemplateExpansionStillApplies() {
        try (var s = session("--session-id", "ec11")) {
            // 展开在 input 事件之后（pi :2191-2192 序）——未知 /skill: 原文透传。
            assertThat(s.followUp("/skill:nope rest")).isEqualTo("queued");
            assertThat(s.clearQueue().followUp()).containsExactly("/skill:nope rest");
        }
    }
}
