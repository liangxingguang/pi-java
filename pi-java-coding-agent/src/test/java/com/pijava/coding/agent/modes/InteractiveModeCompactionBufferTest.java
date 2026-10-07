package com.pijava.coding.agent.modes;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B175（docs/26 §4-4）：压缩窗口缓冲与自动重放的会话级 E2E。
 *
 * <p>手动 compact 在 Java 是阻塞调用 ⇒ 测试把它放独立线程（pi 的 async 形状），
 * 轮询到窗口打开后从测试线程提交：submit 缓冲为 steer、followUp 缓冲为 followUp。
 * compact 线程发 CompactionEnd ⇒ InteractiveMode flush：第一条经 prompt 起新 run，
 * 第二条入 followUp 队列自动续跑。</p>
 */
class InteractiveModeCompactionBufferTest {

    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 10, 1);

    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static Entry messageEntry(String id, String parentId, boolean user,
                                      String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1), message, false);
    }

    @Test
    void submissionsDuringCompactionAreBufferedAndReplayedAfter() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-im-buffer");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-buf", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        // 顺序：① split-turn 历史摘要 ② turn-prefix 摘要（B171，cut 落在最后一条
        // 助手、本轮 user 进 prefix）③ flush 起新 run 的回复 ④ followUp 续跑回复。
        providers.register(FauxProvider.sequence("faux-buf", List.of(
            textTurn("summary text"),
            textTurn("prefix summary"),
            textTurn("after compact reply"),
            textTurn("follow reply")), 100));
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        try (var session = AgentSession.create(args, providers, toolContext)) {
            session.harness().seedTranscript(AgentHarness.DEFAULT_LANE, List.of(
                messageEntry("e1", null, true, "first"),
                messageEntry("e2", "e1", false, "second"),
                messageEntry("e3", "e2", true, "third"),
                messageEntry("e4", "e3", false, "fourth")));

            var sessionEvents = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var ignored = session.subscribe(sessionEvents::add)) {
                var mode = new InteractiveMode(session);
                var streamEvents = new CopyOnWriteArrayList<StreamEvent>();
                mode.setObservers(e -> { }, streamEvents::add);

                var compactThread = Thread.ofPlatform()
                    .unstarted(() -> session.compact(SETTINGS));
                compactThread.start();

                var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (System.nanoTime() < deadline && !session.isCompacting()) {
                    Thread.sleep(2);
                }
                assertThat(session.isCompacting())
                    .as("compact 线程已打开压缩窗口").isTrue();

                mode.submit("steer after compact");
                mode.followUp("follow after compact");

                compactThread.join(Duration.ofSeconds(10).toMillis());
                assertThat(compactThread.isAlive()).isFalse();

                // 缓冲时的 queue_update：引擎队列空、缓冲两文本聚合。
                assertThat(sessionEvents).anySatisfy(e -> {
                    assertThat(e).isInstanceOf(AgentSessionEvent.QueueUpdate.class);
                    var q = (AgentSessionEvent.QueueUpdate) e;
                    assertThat(q.steering()).containsExactly("steer after compact");
                    assertThat(q.followUp()).containsExactly("follow after compact");
                });
                assertThat(sessionEvents.stream()
                    .filter(AgentSessionEvent.FlashStatus.class::isInstance)
                    .toList())
                    .hasSize(2);

                // flush 起的新 run ⇒ 第二条回复；followUp 自动续跑 ⇒ 第三条。
                awaitText(streamEvents, "after compact reply");
                awaitText(streamEvents, "follow reply");
            }
        }
    }

    private static void awaitText(CopyOnWriteArrayList<StreamEvent> events, String text)
            throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline
                && events.stream().noneMatch(e -> e.toString().contains(text))) {
            Thread.sleep(10);
        }
        assertThat(events.stream())
            .as("重放后收到 " + text)
            .anyMatch(e -> e.toString().contains(text));
    }
}
