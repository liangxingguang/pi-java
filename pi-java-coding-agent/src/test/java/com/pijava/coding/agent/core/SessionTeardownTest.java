package com.pijava.coding.agent.core;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.HarnessClosedException;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionStorage;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.rpc.JsonlWriter;
import com.pijava.coding.agent.rpc.RpcDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;

/**
 * docs/16（T2/B252）：teardown 集中 —— closeWith 三参化（abort 对位＋句柄开关＋
 * 幂等）、fork 内部拆解（保共享句柄）、RPC new_session 的 closeForReplacement、
 * 原地 attach 关旧 storage（pi teardownCurrent/dispose 语义）。
 */
class SessionTeardownTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 录制扩展：仅 start/shutdown（teardown 序列断言用）。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "teardown-recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_START, (e, c) -> {
                EVENTS.add("start:" + e.reason() + ":" + e.previousSessionFile());
                return null;
            });
            ctx.on(SESSION_SHUTDOWN, (e, c) -> {
                EVENTS.add("shutdown:" + e.reason() + ":" + e.targetSessionFile());
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

    private static List<StreamEvent> textSeq(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private ToolContext toolContext() {
        return new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    /** ①：fork 臂拆解 —— 源 harness 关闭、共享 repository 句柄仍活（copy 能跑能落盘）。 */
    @Test
    void forkTeardownClosesSourceButKeepsTheSharedHandleAlive() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("sessions-fork"));
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-td",
            List.of(textSeq("r1"), textSeq("r2"), textSeq("r3"))));
        var handle = PersistentSessionRepositories.jsonl(dir);
        var args = ArgsParser.parse(new String[] {"--provider", "faux-td", "--model", "hello"});
        try (var parent = AgentSession.create(args, handle, providers, toolContext(),
                SettingsManager.load(args.projectTrustOverride()))) {
            parent.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);

            var copy = parent.forkCopy("branch").orElseThrow();

            assertThatThrownBy(() -> parent.harness().prompt("x"))
                .as("fork 后源会话真正关闭（pi teardownCurrent，docs/16 B252）")
                .isInstanceOf(HarnessClosedException.class);
            assertThat(EVENTS).contains("shutdown:fork:" + copy.sessionId());

            // 共享句柄未被父会话关掉：copy 照常跑 run、落盘（§2.5③ 的父关杀子回归）。
            copy.processPrompt("more").statusFuture().get(20, TimeUnit.SECONDS);
            assertThat(copy.accumulatedEntries()).isNotEmpty();
        }
    }

    /** ②：RPC new_session —— 旧会话 closeForReplacement（关闭＋shutdown{new, 新 id} 恰一次）。 */
    @Test
    void rpcNewSessionClosesTheOldSessionExactlyOnce() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("sessions-new"));
        var args = ArgsParser.parse(new String[] {"--session-dir", dir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-td2", List.of(textSeq("r1"))));
        var old = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, toolContext());
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(old, new JsonlWriter(out), args);
        EVENTS.clear();

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"new_session\"}");

        var node = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8)
            .lines().filter(l -> l.contains("\"new_session\"")).findFirst().orElseThrow());
        assertThat(node.path("success").asBoolean()).isTrue();
        var newId = node.path("data").path("sessionId").asText();
        assertThatThrownBy(() -> old.harness().prompt("x"))
            .as("旧会话真正关闭（docs/16 §3.6）")
            .isInstanceOf(HarnessClosedException.class);
        assertThat(EVENTS.stream().filter(e -> e.startsWith("shutdown:new:")).toList())
            .as("shutdown{new, 新 id} 恰一次").containsExactly("shutdown:new:" + newId);
        assertThat(EVENTS).contains("start:new:" + old.sessionId());
        dispatcher.close();
    }

    /** ③：幂等（裁决⑥）—— 二次 close 零事件零异常。 */
    @Test
    void closeIsIdempotent() {
        var args = ArgsParser.parse(new String[] {"--session-id", "td3"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-td3", List.of(textSeq("r1"))));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, toolContext());
        EVENTS.clear();

        session.close();
        session.close();

        assertThat(EVENTS.stream().filter(e -> e.startsWith("shutdown:")).toList())
            .as("二次 close 不重发 shutdown 事件")
            .containsExactly("shutdown:quit:null");
        assertThatThrownBy(() -> session.harness().prompt("x"))
            .isInstanceOf(HarnessClosedException.class);
    }

    /** ④：close 首步 abort（裁决⑤）—— 在途 run 被 settle（pi :168/:1395-1401）。 */
    @Test
    void closeAbortsTheInFlightRunFirst() throws Exception {
        var started = new CountDownLatch(1);
        var stop = new AtomicBoolean();
        var providers = ProviderRegistry.create();
        providers.register(new GatedProvider("faux-gated-td", started, stop));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-gated-td", "--model", "hello",
            "--no-session", "--no-extensions"});
        try (var session = AgentSession.create(args, InMemorySessionRepository.create(),
                providers, toolContext())) {
            session.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);
            var result = session.processPrompt("go");
            assertThat(started.await(10, TimeUnit.SECONDS)).as("run 已在飞").isTrue();

            session.close();
            stop.set(true);

            assertThat(session.agentRunAbortRequested())
                .as("close 第一步 settle 在途 turn（pi teardownCurrent 的 await abort 对位）")
                .isTrue();
            result.statusFuture().get(20, TimeUnit.SECONDS);
        } finally {
            stop.set(true);
        }
    }

    /** ⑤：原地 attach（switchTo 持久路）—— 旧 storage 关闭前 persistPending（计数缝：
     *  代理记录 appendEntry/close 调用序）。 */
    @Test
    void inPlaceAttachPersistsPendingThenClosesPreviousStorage() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("sessions-attach"));
        var args = ArgsParser.parse(new String[] {"--no-extensions"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-td5",
            List.of(textSeq("r1"), textSeq("r2"))));
        var handle = PersistentSessionRepositories.jsonl(dir);
        try (var a = AgentSession.create(args, handle, providers, toolContext(),
                SettingsManager.load(args.projectTrustOverride()))) {
            a.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);
            String bId;
            try (var b = AgentSession.create(args,
                    PersistentSessionRepositories.jsonl(dir), providers, toolContext(),
                    SettingsManager.load(args.projectTrustOverride()))) {
                bId = b.sessionId();
            }
            // 造一条只在 harness、未落盘的 entry（persistPending 的操作数）。
            a.harness().appendLaneEntry(a.laneName(), new Entry.Message(
                "pending-1", 0, null, null,
                new Message.UserMessage(List.of(new ContentBlock.TextContent("pending"))),
                null));
            var calls = new CopyOnWriteArrayList<String>();
            var real = a.session();
            // Session 是 final class —— 计数缝打在它委托的 SessionStorage 接口上。
            var spyStorage = (SessionStorage<?>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionStorage.class},
                (proxy, method, methodArgs) -> {
                    if (method.getName().equals("appendEntry")
                            || method.getName().equals("close")) {
                        calls.add(method.getName());
                    }
                    return method.invoke(real.storage(), methodArgs);
                });
            a.session(new Session<>(spyStorage));

            var result = a.switchTo(bId);

            assertThat(result.outcome()).isEqualTo(AgentSession.SwitchOutcome.SWITCHED);
            int lastAppend = calls.lastIndexOf("appendEntry");
            int closeIdx = calls.indexOf("close");
            assertThat(lastAppend)
                .as("attach 前未落盘写入先 persistPending 到旧 storage（§3.7）")
                .isGreaterThanOrEqualTo(0);
            assertThat(closeIdx)
                .as("旧 storage 的 JDBC/文件句柄随后被关闭")
                .isGreaterThan(lastAppend);
        }
    }

    /**
     * 第 1 个请求（种子轮）立即给完整文本；之后挂住 run：起手发 {@code Start} 后
     * 只发心跳帧 —— abort 在拉取侧两帧之间生效（AgentSessionCompactAbortTest 同手法）。
     */
    private static final class GatedProvider implements Provider {
        private final String name;
        private final CountDownLatch started;
        private final AtomicBoolean stop;
        private final AtomicInteger calls = new AtomicInteger();

        GatedProvider(String name, CountDownLatch started, AtomicBoolean stop) {
            this.name = name;
            this.started = started;
            this.stop = stop;
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return name; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }
        @Override
        @SuppressWarnings("unchecked")  // apiType 相等性保证 T 就是 ChatApi（FauxProvider 同式）
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            if (apiType.equals(ChatApi.class)) {
                return (T) new GatedChatApi();
            }
            throw new IllegalArgumentException("Unsupported API type: " + apiType);
        }
        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private final class GatedChatApi extends AbstractChatApi {
            @Override public String apiName() { return "faux"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    SubmissionPublisher<StreamEvent> publisher) {
                if (calls.incrementAndGet() == 1) {
                    textSeq("seed answer").forEach(publisher::submit);
                    return;
                }
                publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                started.countDown();
                while (!stop.get()) {
                    publisher.submit(new StreamEvent.TextDelta(0, "x", AssistantMessage.empty()
                        .withContent(List.of(new ContentBlock.TextContent("x")))));
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
