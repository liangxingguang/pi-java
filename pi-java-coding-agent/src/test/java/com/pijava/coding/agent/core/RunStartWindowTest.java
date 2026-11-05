package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_AGENT_START;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W1（B275）：运行启动窗（pi {@code _startRun}，agent-session.ts:1894-2013）——
 * hook 内 sendMessage 进本 run 的延迟缓冲而非另起入口；hook 内 abort 在
 * {@code agent_start} 后立即中止；custom 触发携带 {@code messageType=custom}；
 * 启动窗内到达的 bash/custom 排在起始用户消息之前。
 */
class RunStartWindowTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile AgentSession CURRENT;
    static volatile boolean sendInHook;
    static volatile boolean abortInHook;
    static volatile boolean bashAndMessageInHook;
    static volatile long entryCountBefore;
    static volatile long entryCountAfter;

    /** 探针扩展（ServiceLoader 无参构造 ⇒ 静态开关驱动）。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "run-start-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(BEFORE_AGENT_START, (event, c) -> {
                EVENTS.add("bas:" + event.messageType()
                    + (event.message() != null ? ":" + event.message().customType() : ""));
                if (sendInHook) {
                    entryCountBefore = CURRENT.entryCount();
                    c.sendMessage("during-hook", CustomMessageContent.of("during"),
                        true, Map.of(),
                        new ExtensionContext.SendMessageOptions(Boolean.FALSE, null));
                    entryCountAfter = CURRENT.entryCount();
                }
                if (bashAndMessageInHook) {
                    // 启动窗内 bash 结果与延迟 custom（都应等 hook 结束再落盘）。
                    CURRENT.recordBashResult("echo hi",
                        new ShellResult("hi\n", 0, false, false, 1, 3), false);
                    c.sendMessage("start-window", CustomMessageContent.of("queued"),
                        true, Map.of(),
                        new ExtensionContext.SendMessageOptions(Boolean.FALSE, null));
                }
                if (abortInHook) {
                    CURRENT.abort();
                }
                return new BeforeAgentStartEventResult(
                    new CustomMessageDraft("hook-draft", CustomMessageContent.of("from-hook"),
                        true, Map.of()), null);
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        CURRENT = null;
        sendInHook = false;
        abortInHook = false;
        bashAndMessageInHook = false;
        entryCountBefore = 0;
        entryCountAfter = 0;
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

    private AgentSession session() {
        var args = ArgsParser.parse(new String[] {"--provider", "faux", "--model", "hello",
            "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux", List.of(reply("ok")), 30));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
        CURRENT = session;
        return session;
    }

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static List<Entry.CustomMessage> customs(AgentSession s) {
        return s.harness().snapshot(s.laneName()).transcript().stream()
            .filter(e -> e instanceof Entry.CustomMessage)
            .map(e -> (Entry.CustomMessage) e)
            .toList();
    }

    private static String textOf(Entry.CustomMessage message) {
        var content = message.content();
        return content instanceof CustomMessageContent.Text text ? text.text() : "";
    }

    @Test
    void sendMessageInHookIsQueuedIntoThisRun() throws Exception {
        try (var s = session()) {
            sendInHook = true;
            s.processPrompt("hi").statusFuture().get(30, TimeUnit.SECONDS);
            assertThat(entryCountAfter)
                .as("启动窗内 triggerTurn=false ⇒ 延迟缓冲，不即时落盘")
                .isEqualTo(entryCountBefore);
            assertThat(customs(s).stream().map(RunStartWindowTest::textOf).toList())
                .as("延迟消息在本 run 起跑前 flush 落盘")
                .contains("during");
        }
    }

    @Test
    void abortInHookAbortsWhenAgentStarts() throws Exception {
        try (var s = session()) {
            var settled = new AtomicReference<AgentSessionEvent.AgentSettled>();
            var latch = new CountDownLatch(1);
            s.subscribe(event -> {
                if (event instanceof AgentSessionEvent.AgentSettled value) {
                    settled.set(value);
                    latch.countDown();
                }
            });
            abortInHook = true;
            s.processPrompt("hi").statusFuture().get(30, TimeUnit.SECONDS);
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(settled.get()).isNotNull();
            assertThat(settled.get().aborted())
                .as("hook 内 abort 未被驱动起手抹掉，agent_start 时立即中止")
                .isTrue();
        }
    }

    @Test
    void customTriggerFiresCustomHookArmAndOrdersMessages() throws Exception {
        try (var s = session()) {
            var latch = new CountDownLatch(1);
            s.subscribe(event -> {
                if (event instanceof AgentSessionEvent.AgentSettled) {
                    latch.countDown();
                }
            });
            var id = s.sendCustomMessage("start-note", CustomMessageContent.of("trigger"),
                true, Map.of(), true, null);
            assertThat(id).isNull();
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(EVENTS).contains("bas:CUSTOM:start-note");
            assertThat(customs(s).stream().map(RunStartWindowTest::textOf).toList())
                .as("起始 custom ⇒ 处理器消息（pi messages 序）")
                .containsExactly("trigger", "from-hook");
        }
    }

    @Test
    void startWindowBashAndCustomFlushBeforeUserMessage() throws Exception {
        try (var s = session()) {
            bashAndMessageInHook = true;
            s.processPrompt("hi").statusFuture().get(30, TimeUnit.SECONDS);
            var transcript = s.harness().snapshot(s.laneName()).transcript();
            int bashIndex = -1;
            int customIndex = -1;
            int userIndex = -1;
            for (int i = 0; i < transcript.size(); i++) {
                if (transcript.get(i) instanceof Entry.BashExecution bash
                        && bash.command().equals("echo hi")) {
                    bashIndex = i;
                }
                if (transcript.get(i) instanceof Entry.CustomMessage custom
                        && textOf(custom).equals("queued")) {
                    customIndex = i;
                }
                if (transcript.get(i) instanceof Entry.Message message
                        && message.message() instanceof com.pijava.ai.message.Message.UserMessage) {
                    userIndex = i;
                }
            }
            assertThat(bashIndex).isGreaterThanOrEqualTo(0);
            assertThat(customIndex).isGreaterThanOrEqualTo(0);
            assertThat(userIndex).isGreaterThanOrEqualTo(0);
            assertThat(bashIndex)
                .as("启动窗内 bash 排在起始用户消息之前")
                .isLessThan(userIndex);
            assertThat(customIndex)
                .as("启动窗内 custom 排在起始用户消息之前")
                .isLessThan(userIndex);
        }
    }

}
