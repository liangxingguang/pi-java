package com.pijava.coding.agent.modes;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
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
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/21（T4，S20）：{@link InteractiveMode#restoreQueuedMessages()} 规格——
 * pi {@code clearAllQueues}＋{@code restoreQueuedMessagesToEditor} 的队列侧
 * （interactive-mode.ts:4708-4763）：引擎两队列＋压缩缓冲清空，pi 序合并文本
 * （{@code "\n\n"} 连接）＋条数；空 ⇒ null；清理后 queue_update 真空。
 *
 * <p>引擎臂与缓冲臂分测：手动 compact 先 abort 在飞 run（abortAndWaitForIdle），
 * 「run 在飞＋压缩窗口」同存的状态在 java 手动路构造不出（pi 的混合序由
 * {@code [...engine, ...compaction]} 两数组拼接逐字对拍，见方法注释）。</p>
 */
class InteractiveModeRestoreTest {

    private static final CompactionSettings COMPACT = new CompactionSettings(true, 10, 1);

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

    private static Entry messageEntry(String id, String parentId, boolean user, String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, parentId,
            java.time.Instant.ofEpochMilli(1), message, false);
    }

    private record Rig(AgentSession session, InteractiveMode mode,
                       CopyOnWriteArrayList<AgentSessionEvent> sessionEvents)
            implements AutoCloseable {
        @Override public void close() { session.close(); }
    }

    private static Rig rig(String provider, int delayMs,
                           List<List<StreamEvent>> turns) throws Exception {
        var tmp = Files.createTempDirectory("pi-java-im-restore");
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(provider, turns, delayMs));
        var session = AgentSession.create(args, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        session.subscribe(events::add);
        var mode = new InteractiveMode(session);
        mode.setObservers(e -> { }, e -> { });
        return new Rig(session, mode, events);
    }

    /** 空队列 ⇒ null（pi restored===0 ⇒ "No queued messages to restore" 臂）。 */
    @Test
    void emptyQueuesRestoreToNull() throws Exception {
        try (var rig = rig("faux-rst-empty", 0, List.of(textTurn("r1")))) {
            assertThat(rig.mode().restoreQueuedMessages()).isNull();
        }
    }

    /** 引擎臂：运行中 steer＋followUp ⇒ pi 序合并（steer 前 followUp 后）＋条数。 */
    @Test
    void engineQueuesMergeInPiOrder() throws Exception {
        try (var rig = rig("faux-rst-engine", 1500,
                List.of(textTurn("first reply"), textTurn("second reply")))) {
            var first = rig.mode().submit("hello");
            assertThat(first.disposition()).isEqualTo("started");
            // 等首轮 LLM 调用在飞再排队（InteractiveModeRoutingTest 同款时机）。
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && !rig.session().isRunning()) {
                Thread.sleep(5);
            }
            rig.mode().submit("engine steer");       // 运行中 ⇒ steer 排队
            rig.mode().followUp("engine follow");    // 运行中 ⇒ followUp 排队

            var restored = rig.mode().restoreQueuedMessages();
            assertThat(restored).isNotNull();
            assertThat(restored.combinedText())
                .as("pi [...steering, ...followUp]＋\"\\n\\n\" 连接")
                .isEqualTo("engine steer\n\nengine follow");
            assertThat(restored.count()).isEqualTo(2);
            // 清空后两队列皆空（二次取回 ⇒ null）。
            assertThat(rig.mode().restoreQueuedMessages()).isNull();
            assertThat(rig.sessionEvents().stream()
                .filter(AgentSessionEvent.QueueUpdate.class::isInstance)
                .map(AgentSessionEvent.QueueUpdate.class::cast).toList().getLast())
                .as("清理后 queue_update 真空")
                .satisfies(q -> {
                    assertThat(q.steering()).isEmpty();
                    assertThat(q.followUp()).isEmpty();
                });
        }
    }

    /** 缓冲臂：压缩窗口内 steer＋followUp ⇒ 取回后缓冲清空、CompactionEnd 无重放。 */
    @Test
    void compactionBufferMergesAndStaysEmptyAfterRestore() throws Exception {
        try (var rig = rig("faux-rst-buf", 1200, List.of(
                textTurn("summary text"), textTurn("prefix summary"),
                textTurn("unused reply")))) {
            rig.session().harness().seedTranscript(
                com.pijava.agent.harness.AgentHarness.DEFAULT_LANE, List.of(
                    messageEntry("e1", null, true, "first"),
                    messageEntry("e2", "e1", false, "second"),
                    messageEntry("e3", "e2", true, "third"),
                    messageEntry("e4", "e3", false, "fourth")));

            var compactThread = Thread.ofPlatform()
                .unstarted(() -> rig.session().compact(COMPACT));
            compactThread.start();
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && !rig.session().isCompacting()) {
                Thread.sleep(2);
            }
            assertThat(rig.session().isCompacting()).as("压缩窗口已开").isTrue();

            rig.mode().submit("buffered steer");       // 压缩中 ⇒ 缓冲 steer
            rig.mode().followUp("buffered follow");    // 压缩中 ⇒ 缓冲 followUp

            var restored = rig.mode().restoreQueuedMessages();
            assertThat(restored).isNotNull();
            assertThat(restored.combinedText())
                .isEqualTo("buffered steer\n\nbuffered follow");
            assertThat(restored.count()).isEqualTo(2);
            // 取回后的 queue_update 已是真空；标记事件数，后面只许再出现真空。
            int mark = rig.sessionEvents().size();

            compactThread.join(Duration.ofSeconds(15).toMillis());
            assertThat(compactThread.isAlive()).isFalse();
            // CompactionEnd flush 面对空缓冲 ⇒ 不重放（unused reply 永不出现）；
            // 取回之后的全部 queue_update 都是真空（② 清理后 queue_update 真空）。
            var after = rig.sessionEvents().subList(mark, rig.sessionEvents().size())
                .stream().filter(AgentSessionEvent.QueueUpdate.class::isInstance)
                .map(AgentSessionEvent.QueueUpdate.class::cast).toList();
            assertThat(after).isNotEmpty()
                .allSatisfy(q -> {
                    assertThat(q.steering()).isEmpty();
                    assertThat(q.followUp()).isEmpty();
                });
        }
    }
}
