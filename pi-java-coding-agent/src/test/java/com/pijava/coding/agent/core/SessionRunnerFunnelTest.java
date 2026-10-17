package com.pijava.coding.agent.core;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #46 族（批 2）：会话事件面<b>漏斗</b>的全序与载荷钉 —— pi 的单一漏斗
 * （{@code agent-session.ts:1139-1142}）：引擎事件全量成为会话事件，仅
 * {@code agent_end} 装饰 {@code willRetry}；{@code agent_settled{aborted}}
 * 每次驱动恰一条（{@code :203/:1083/:1849}）。
 *
 * <p>六个夹具（docs/21 §1.9）：① 单轮全序；② 工具回合粒度；③ #52 每 pass
 * 载荷；④ 失败路合成四条；⑤ abort 路；⑥ retry 粒度。</p>
 */
class SessionRunnerFunnelTest {

    // ── 剧本（与同包存量夹具同手法） ─────────────────────────────────

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

    private static List<StreamEvent> toolCallSeq() {
        Map<String, Object> args = Map.of("path", "hello.py", "content", "print(1)");
        var toolMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("id1", "write", args)))
            .withStopReason(StopReason.TOOL_USE);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.ToolCallStart(0, AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent("id1", "write", Map.of())))),
            new StreamEvent.ToolCallEnd(0, "id1", "write", args, toolMsg),
            new StreamEvent.StreamDone(StopReason.TOOL_USE, null, toolMsg));
    }

    private static List<StreamEvent> errorSeq(String text) {
        var partial = AssistantMessage.empty().withStopReason(StopReason.ERROR);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            StreamEvent.StreamError.settle(
                StopReason.ERROR, new RuntimeException(text), partial));
    }

    private static AgentSession session(String provider, List<List<StreamEvent>> seqs,
                                        Path tmp) {
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(provider, seqs));
        return AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private static List<AgentSessionEvent> drive(AgentSession session, String prompt)
            throws Exception {
        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (var sub = session.subscribe(events::add)) {
            session.processPrompt(prompt).statusFuture().get(30, TimeUnit.SECONDS);
        }
        return events;
    }

    /** 帧标签（不含 message_update / entry_appended 等旁路帧 —— 它们走 :92 广播链）。 */
    private static List<String> lifecycle(List<AgentSessionEvent> events) {
        return events.stream().map(SessionRunnerFunnelTest::label)
            .filter(l -> l != null).toList();
    }

    private static String label(AgentSessionEvent e) {
        return switch (e) {
            case AgentSessionEvent.AgentStart ignored -> "agent_start";
            case AgentSessionEvent.TurnStart ignored -> "turn_start";
            case AgentSessionEvent.TurnEnd ignored -> "turn_end";
            case AgentSessionEvent.MessageStart m -> "message_start:" + m.message().role();
            case AgentSessionEvent.MessageEnd m -> "message_end:" + m.message().role();
            case AgentSessionEvent.AgentEnd ignored -> "agent_end";
            case AgentSessionEvent.AgentSettled s -> "agent_settled:" + s.aborted();
            case AgentSessionEvent.ToolExecutionStart ignored -> "tool_execution_start";
            case AgentSessionEvent.ToolExecutionUpdate ignored -> "tool_execution_update";
            case AgentSessionEvent.ToolExecutionEnd ignored -> "tool_execution_end";
            default -> null;
        };
    }

    private static List<AgentSessionEvent.AgentEnd> agentEnds(List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.AgentEnd.class::isInstance)
            .map(AgentSessionEvent.AgentEnd.class::cast).toList();
    }

    private static List<AgentSessionEvent.TurnEnd> turnEnds(List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.TurnEnd.class::isInstance)
            .map(AgentSessionEvent.TurnEnd.class::cast).toList();
    }

    // ── ① 单轮：全序钉死（pi 引擎时序 :117-123/:207/:253/:287/:320）─────────

    @Test
    void singleTextTurnEmitsTheFullLifecycleInOrder(@TempDir Path tmp) throws Exception {
        try (var s = session("faux-funnel-1", List.of(textSeq("hi")), tmp)) {
            var frames = lifecycle(drive(s, "hello"));

            // 首 pass 的段补丁系统消息若出现，必在 user 之前；正文顺序不受其影响。
            var expected = new java.util.ArrayList<>(List.of(
                "agent_start", "turn_start"));
            if (frames.contains("message_start:system")) {
                expected.add("message_start:system");
                expected.add("message_end:system");
            }
            expected.addAll(List.of(
                "message_start:user", "message_end:user",
                "message_start:assistant", "message_end:assistant",
                "turn_end", "agent_end", "agent_settled:false"));
            assertThat(frames).containsExactlyElementsOf(expected);
        }
    }

    // ── ② 工具回合：两条 turn_end（每回合一条）＋ agent_end 一条 ──────────

    @Test
    void toolRunEmitsOneTurnEndPerTurnButASingleAgentEnd(@TempDir Path tmp) throws Exception {
        try (var s = session("faux-funnel-2", List.of(toolCallSeq(), textSeq("done")), tmp)) {
            var events = drive(s, "go");

            assertThat(turnEnds(events))
                .as("pi 的 turn_end 是每回合一条（types.ts:517）")
                .hasSize(2);
            assertThat(agentEnds(events)).hasSize(1);
            assertThat(turnEnds(events).get(0).toolResults()).hasSize(1);
            assertThat(turnEnds(events).get(1).toolResults()).isEmpty();
            assertThat(lifecycle(events))
                .as("工具生命周期帧经漏斗上会话面")
                .contains("tool_execution_start", "tool_execution_end");
            assertThat(events.get(events.size() - 1))
                .isInstanceOf(AgentSessionEvent.AgentSettled.class);
        }
    }

    // ── ③ #52：agent_end.messages 是本 pass 的 newMessages，不是全历史 ──────

    @Test
    void agentEndPayloadIsThisPasssNewMessagesNotTheWholeHistory(@TempDir Path tmp)
            throws Exception {
        var home = Files.createTempDirectory("pi-java-funnel-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AgentSession s;
        try {
            s = session("faux-funnel-3",
                List.of(errorSeq("overloaded"), textSeq("recovered")), tmp);
        } finally {
            if (savedHome != null) {
                System.setProperty("user.home", savedHome);
            }
        }
        try (s) {
            var ends = agentEnds(drive(s, "go"));

            assertThat(ends).as("retry ⇒ 两个 pass 各一条 agent_end").hasSize(2);
            assertThat(ends.get(0).messages())
                .as("pass1 载荷 = 本 pass 新增：段补丁＋prompt＋失败助手（agent-loop.ts:254，"
                    + "起手系统消息也在 initialMessages 里）")
                .hasSize(3);
            assertThat(ends.get(0).messages().get(1)).isInstanceOf(Message.UserMessage.class);
            assertThat(((Message.AssistantMessage) ends.get(0).messages().get(2)).stopReason())
                .isEqualTo(StopReason.ERROR);
            // #52 的判据是「本 pass 新增，不是全历史」：pass2 载荷与 pass1 零重叠，
            // 且末条是本轮的恢复助手。（pass2 起手可能带一条工具再声明的系统消息 ——
            // 那是引擎层 declare 的行为，不属于本测试钉的漏斗契约。）
            assertThat(ends.get(1).messages())
                .as("pass2 载荷不含首轮的任何消息 —— #52 的 pi 形状")
                .noneMatch(m -> m instanceof Message.UserMessage)
                .noneMatch(m -> m instanceof Message.AssistantMessage a
                    && a.stopReason() == StopReason.ERROR);
            var pass2Tail = ends.get(1).messages().get(ends.get(1).messages().size() - 1);
            assertThat(((Message.AssistantMessage) pass2Tail).stopReason())
                .isEqualTo(StopReason.STOP);
        }
    }

    // ── ④ 失败路：RunFailure 四条上会话面，agent_end 载荷 = [failureMessage] ──

    @Test
    void providerThrowSurfacesRunFailureQuartetOnTheSessionFace(@TempDir Path tmp)
            throws Exception {
        var providers = ProviderRegistry.create();
        providers.register(new ThrowingProvider("faux-funnel-4"));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-funnel-4", "--model", "hello", "--no-session"});
        try (var s = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            var events = drive(s, "go");

            var frames = lifecycle(events);
            var tail = frames.subList(frames.size() - 5, frames.size());
            assertThat(tail)
                .as("失败合成路四条逐字同序（agent.ts:530-547）＋ agent_settled 收尾")
                .containsExactly("message_start:assistant", "message_end:assistant",
                    "turn_end", "agent_end", "agent_settled:false");
            var ends = agentEnds(events);
            assertThat(ends).hasSize(1);
            assertThat(ends.get(0).messages())
                .as("agent_end.messages = 本 pass 新增（段补丁＋prompt＋合成失败消息），"
                    + "末条即 failureMessage（pi agent.ts:522-525）")
                .hasSize(3);
            var failure = (Message.AssistantMessage) ends.get(0).messages().get(2);
            assertThat(failure.stopReason()).isEqualTo(StopReason.ERROR);
            assertThat(failure.errorMessage()).isEqualTo("boom");
            // turn_end 的 message 就是同一条合成失败消息（恒非 null —— 旧
            // 「错误路 message 为 null」的残余偏差随 #46 族消解）。
            assertThat(turnEnds(events).get(0).message().errorMessage()).isEqualTo("boom");
        }
    }

    // ── ⑤ abort 路：agent_settled{aborted:true}，turn_end 带 aborted 合成消息 ──

    @Test
    void abortDuringRunSettlesWithAbortedTrue(@TempDir Path tmp) throws Exception {
        var started = new CountDownLatch(1);
        var stop = new AtomicBoolean();
        var providers = ProviderRegistry.create();
        providers.register(new HeartbeatProvider("faux-funnel-5", started, stop));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-funnel-5", "--model", "hello", "--no-session"});
        try (var s = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            try {
                var sub = s.subscribe(events::add);
                s.processPrompt("go");
                assertThat(started.await(10, TimeUnit.SECONDS)).as("run 已在飞").isTrue();
                s.abort();
                // 等驱动落定（订阅里出现 agent_settled 即收尾完成）。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (events.stream().noneMatch(e -> e instanceof AgentSessionEvent.AgentSettled)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                sub.close();
            } finally {
                stop.set(true);
            }

            var settled = events.stream()
                .filter(AgentSessionEvent.AgentSettled.class::isInstance)
                .map(AgentSessionEvent.AgentSettled.class::cast).toList();
            assertThat(settled).as("每次驱动恰一条 agent_settled").hasSize(1);
            assertThat(settled.get(0).aborted())
                .as("abort() 置过请求旗 ⇒ aborted:true（pi :1083）")
                .isTrue();
            assertThat(turnEnds(events))
                .as("abort 轮的 turn_end 带 aborted 合成消息")
                .isNotEmpty();
            assertThat(turnEnds(events).get(0).message().stopReason())
                .isEqualTo(StopReason.ABORTED);
        }
    }

    // ── ⑥ retry 路：两条 agent_end（第一条 willRetry=true），粒度与 pi 相同 ──

    @Test
    void retryDriveEmitsTwoAgentEndsWithWillRetryDecoration(@TempDir Path tmp)
            throws Exception {
        var home = Files.createTempDirectory("pi-java-funnel6-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AgentSession s;
        try {
            s = session("faux-funnel-6",
                List.of(errorSeq("overloaded"), textSeq("recovered")), tmp);
        } finally {
            if (savedHome != null) {
                System.setProperty("user.home", savedHome);
            }
        }
        try (s) {
            var ends = agentEnds(drive(s, "go"));

            assertThat(ends).hasSize(2);
            assertThat(ends.get(0).willRetry()).isTrue();
            assertThat(ends.get(1).willRetry()).isFalse();
        }
    }

    // ── 假 provider：抛错 / 心跳挂住 ────────────────────────────────────

    /** streamInternal 直接抛（失败合成路）；身份三元组由 AbstractChatApi 挂载。 */
    private static final class ThrowingProvider implements Provider {
        private final String name;

        ThrowingProvider(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public String displayName() { return name; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }
        @Override
        @SuppressWarnings("unchecked")  // apiType 相等性保证 T 就是 ChatApi
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            if (apiType.equals(ChatApi.class)) {
                return (T) new ThrowingChatApi();
            }
            throw new IllegalArgumentException("Unsupported API type: " + apiType);
        }
        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private final class ThrowingChatApi extends AbstractChatApi {
            @Override public String apiName() { return "faux"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    SubmissionPublisher<StreamEvent> publisher) {
                throw new IllegalStateException("boom");
            }
        }
    }

    /** 起手发 {@code Start} 后只发心跳帧直到 stop —— abort 在拉取侧两帧间生效。 */
    private static final class HeartbeatProvider implements Provider {
        private final String name;
        private final CountDownLatch started;
        private final AtomicBoolean stop;

        HeartbeatProvider(String name, CountDownLatch started, AtomicBoolean stop) {
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
        @SuppressWarnings("unchecked")  // apiType 相等性保证 T 就是 ChatApi
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            if (apiType.equals(ChatApi.class)) {
                return (T) new HeartbeatChatApi();
            }
            throw new IllegalArgumentException("Unsupported API type: " + apiType);
        }
        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private final class HeartbeatChatApi extends AbstractChatApi {
            @Override public String apiName() { return "faux"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    SubmissionPublisher<StreamEvent> publisher) {
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
