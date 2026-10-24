package com.pijava.coding.agent.modes;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
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
import com.pijava.coding.agent.core.AgentSession;
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

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.INPUT;

/**
 * 批 20（B237，T1e）：{@link InteractiveMode} 提交重路由（pi
 * interactive-mode.ts:3362-3381/:4479-4496 对位）——压缩中/流式中扩展命令
 * 即执行（processPrompt 臂），普通文本流式中走 prompt{steer}（input 事件
 * 自此对 TUI steer 发射，pi 同形）。
 */
class InteractiveExtensionCommandRoutingTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 录制扩展：input 事件透传录制（不干预）。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "im-route-recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(INPUT, (e, c) -> {
                EVENTS.add("input:" + e.text() + ":" + e.source() + ":"
                    + e.streamingBehavior());
                return null;
            });
        }
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

    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static SlashCommand probe(String name, List<String> calls) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " probe"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext context) {
                calls.add(args);
                return CompletableFuture.completedFuture("ok");
            }
        };
    }

    private AgentSession session(String providerName, List<List<StreamEvent>> replies,
                                 int delayMs) {
        var args = ArgsParser.parse(new String[] {
            "--provider", providerName, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(providerName, replies, delayMs));
        return AgentSession.create(args, providers, new ToolContext(tmp.toString(),
            Map.of(), new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    @Test
    void streamingSubmitExecutesExtensionCommandImmediately() throws Exception {
        try (var s = session("faux-ier",
                List.of(textTurn("slow reply"), textTurn("next")), 300)) {
            var calls = new CopyOnWriteArrayList<String>();
            s.services().slashCommands().extensionView().register(probe("hello", calls));
            var mode = new InteractiveMode(s);
            var streamEvents = new CopyOnWriteArrayList<StreamEvent>();
            mode.setObservers(e -> { }, streamEvents::add);

            var first = mode.submit("start");
            assertThat(first.disposition()).isEqualTo("started");
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && streamEvents.isEmpty()) {
                Thread.sleep(5);
            }
            assertThat(s.isRunning()).isTrue();

            // pi :3377-3381：流式中扩展命令在 prompt 内即执行——不排队。
            var second = mode.submit("/hello world");

            assertThat(second.disposition()).isEqualTo("handled");
            assertThat(calls).containsExactly("world");
            assertThat(s.clearQueue().steering())
                .as("扩展命令不入 steer 队列").isEmpty();
        }
    }

    @Test
    void streamingSubmitOfPlainTextFiresInputEventWithSteerBehavior() throws Exception {
        try (var s = session("faux-ier",
                List.of(textTurn("slow reply"), textTurn("next")), 300)) {
            var mode = new InteractiveMode(s);
            var streamEvents = new CopyOnWriteArrayList<StreamEvent>();
            mode.setObservers(e -> { }, streamEvents::add);

            mode.submit("start");
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && streamEvents.isEmpty()) {
                Thread.sleep(5);
            }
            assertThat(s.isRunning()).isTrue();

            // 批 20 行为变化（pi 同形）：TUI steer 自此过 input 事件。
            var second = mode.submit("plain text");

            assertThat(second.disposition()).isEqualTo("queued");
            assertThat(EVENTS).contains("input:plain text:interactive:steer");
            assertThat(s.clearQueue().steering()).containsExactly("plain text");
        }
    }

    @Test
    void compactingSubmitExecutesExtensionCommandInsteadOfBuffering() throws Exception {
        // 压缩窗口：手动 compact 阻塞 ⇒ 独立线程（批 15 缓冲测试先例）。
        try (var s = session("faux-ier",
                List.of(textTurn("summary"), textTurn("prefix"), textTurn("after")), 200)) {
            s.harness().seedTranscript(AgentHarness.DEFAULT_LANE, List.of(
                messageEntry("e1", null, true, "first"),
                messageEntry("e2", "e1", false, "second"),
                messageEntry("e3", "e2", true, "third"),
                messageEntry("e4", "e3", false, "fourth")));
            var calls = new CopyOnWriteArrayList<String>();
            s.services().slashCommands().extensionView().register(probe("hello", calls));
            var mode = new InteractiveMode(s);
            mode.setObservers(e -> { }, e -> { });

            var compactThread = Thread.ofPlatform()
                .unstarted(() -> s.compact(new CompactionSettings(true, 10, 1)));
            compactThread.start();
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && !s.isCompacting()) {
                Thread.sleep(2);
            }
            assertThat(s.isCompacting()).isTrue();

            // pi :3362-3370：压缩中扩展命令即执行（不缓冲、不吃压缩门错）。
            var result = mode.submit("/hello world");

            assertThat(result.disposition()).isEqualTo("handled");
            assertThat(calls).containsExactly("world");
            compactThread.join(Duration.ofSeconds(10).toMillis());
            assertThat(compactThread.isAlive()).isFalse();
        }
    }

    private static Entry messageEntry(String id, String parentId, boolean user,
                                      String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1), message, false);
    }
}
