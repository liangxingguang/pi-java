package com.pijava.coding.agent.core.slash;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.coding.agent.support.BlockingSummaryProvider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B176（docs/27 §4-1）：{@code /compact} 的压缩跑在 worker 线程——dispatch
 * 立即返回未完成 stage，压缩窗口内命令线程仍可缓冲输入（包 26 手动路径
 * 自此可达）。
 */
class CompactSlashAsyncTest {

    private static Entry messageEntry(String id, String parentId, boolean user,
                                      String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1), message, false);
    }

    /** 4 条消息，e3 user 100k 字符（~25k tokens > 默认 keep 20k）⇒ cut=2（e3）。 */
    private static List<Entry> seededEntries() {
        return List.of(
            messageEntry("e1", null, true, "first"),
            messageEntry("e2", "e1", false, "second"),
            messageEntry("e3", "e2", true, "x".repeat(100_000)),
            messageEntry("e4", "e3", false, "fourth"));
    }

    private static AgentSession session(BlockingSummaryProvider provider) throws Exception {
        var tmp = Files.createTempDirectory("pi-java-async-compact");
        var args = ArgsParser.parse(new String[] {
            "--provider", provider.name(), "--model", "hello", "--no-session"});
        var session = AgentSession.create(args, provider.register(),
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        session.harness().seedTranscript(AgentHarness.DEFAULT_LANE, seededEntries());
        return session;
    }

    @Test
    void dispatchReturnsWhileCompactionRunsAndBufferedPromptReplays() throws Exception {
        var provider = new BlockingSummaryProvider("block-slash");
        try (var session = session(provider)) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var ignored = session.subscribe(events::add)) {
                var mode = new InteractiveMode(session);

                // Watchdog 兜底：同步变异体（M1）会让 dispatch 永久阻塞，2.5s 后
                // 强制放行使其产出普通断言红；健康路径下提前手动 release、此为 no-op。
                Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    provider.release();
                });

                var stage = CommandRegistry.withBuiltins()
                    .dispatch("/compact", SlashContext.of(session))
                    .toCompletableFuture();

                awaitCompacting(session);
                // 压缩在 worker 线程：dispatch 已返回、结果未定。
                assertThat(stage).isNotDone();

                var queued = mode.submit("later");
                assertThat(queued.disposition())
                    .as("压缩窗口内提交：缓冲（包 26 手动路径）").isEqualTo("queued");
                assertThat(events).anySatisfy(e -> {
                    assertThat(e).isInstanceOf(AgentSessionEvent.QueueUpdate.class);
                    assertThat(((AgentSessionEvent.QueueUpdate) e).steering())
                        .containsExactly("later");
                });

                provider.release();
                assertThat(stage.get(10, TimeUnit.SECONDS))
                    .isEqualTo("Compacted.");

                // CompactionEnd flush：缓冲的 "later" 起新 run 并收口。
                var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (System.nanoTime() < deadline
                        && session.harness().snapshot(session.laneName()).transcript().stream()
                            .noneMatch(CompactSlashAsyncTest::hasLaterUserMessage)) {
                    Thread.sleep(10);
                }
                deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (System.nanoTime() < deadline
                        && (session.isCompacting() || session.isRunning())) {
                    Thread.sleep(10);
                }
                assertThat(session.isCompacting() || session.isRunning()).isFalse();
                var transcript = session.harness().snapshot(session.laneName()).transcript();
                assertThat(transcript).anyMatch(e -> e instanceof Entry.Compaction);
                assertThat(transcript).anyMatch(CompactSlashAsyncTest::hasLaterUserMessage);
            }
        }
    }

    @Test
    void failedCompactionProducesFailureResultText() throws Exception {
        var provider = new BlockingSummaryProvider("block-slash-fail", true);
        try (var session = session(provider)) {
            var mode = new InteractiveMode(session);

            var stage = CommandRegistry.withBuiltins()
                .dispatch("/compact", SlashContext.of(session))
                .toCompletableFuture();
            provider.release();  // no-op: error path never blocks

            var result = stage.get(10, TimeUnit.SECONDS);
            assertThat(result).startsWith("Compaction failed:");
            assertThat(mode.session().harness().snapshot(session.laneName()).transcript())
                .noneMatch(e -> e instanceof Entry.Compaction);
        }
    }

    private static boolean hasLaterUserMessage(Entry entry) {
        return entry instanceof Entry.Message m
            && "user".equals(m.message().role())
            && m.message().content().stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(c -> ((ContentBlock.TextContent) c).text())
                .anyMatch("later"::equals);
    }

    private static void awaitCompacting(AgentSession session) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !session.isCompacting()) {
            Thread.sleep(5);
        }
        assertThat(session.isCompacting())
            .as("worker 已打开压缩窗口").isTrue();
    }
}
