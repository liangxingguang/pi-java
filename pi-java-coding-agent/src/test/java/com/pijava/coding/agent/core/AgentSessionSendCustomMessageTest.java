package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 26（docs/37 T2，E5-12）：{@code AgentSession.sendCustomMessage} 五臂
 * （pi agent-session.ts:2293-2329 逐字序）——nextTurn 缓冲／流式 steer·followUp
 * （默认 steer）／triggerTurn 起轮（settled 窗内延迟）／流式延迟落盘／空闲立即落盘。
 */
class AgentSessionSendCustomMessageTest {

    @TempDir
    java.nio.file.Path tmp;

    private AgentSession session() {
        var args = ArgsParser.parse(new String[] {"--provider", "faux", "--model", "hello",
            "--no-session", "--no-extensions"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    private static List<Entry.CustomMessage> customs(AgentSession s) {
        return s.harness().snapshot(s.laneName()).transcript().stream()
            .filter(e -> e instanceof Entry.CustomMessage)
            .map(e -> (Entry.CustomMessage) e)
            .toList();
    }

    @Test
    void idleWithoutTriggerTurnAppendsImmediately() {
        try (var s = session()) {
            var id = s.sendCustomMessage("note", CustomMessageContent.of("hello"), true,
                Map.of(), null, null);

            assertThat(id).isNotBlank();
            var found = customs(s);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).id()).isEqualTo(id);
            assertThat(((CustomMessageContent.Text) found.get(0).content()).text())
                .isEqualTo("hello");
        }
    }

    @Test
    void nextTurnBufferInjectsAfterUserMessageOnNextPrompt() throws Exception {
        try (var s = session()) {
            // 臂 1（pi :2306-2307）：缓冲，不落盘、不起轮。
            var early = s.sendCustomMessage("aside", CustomMessageContent.of("context-x"),
                false, null, null, CustomMessageDelivery.NEXT_TURN);
            assertThat(early).isNull();
            assertThat(customs(s)).isEmpty();

            s.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);

            // pi :2088-2092：custom 投影排在用户消息之后（转录序：user ⇒ custom_message）。
            var transcript = s.harness().snapshot(s.laneName()).transcript();
            int userIdx = -1;
            int customIdx = -1;
            for (int i = 0; i < transcript.size(); i++) {
                var e = transcript.get(i);
                if (userIdx < 0 && e instanceof Entry.Message m
                        && "user".equals(m.message().role())) {
                    userIdx = i;
                }
                if (customIdx < 0 && e instanceof Entry.CustomMessage) {
                    customIdx = i;
                }
            }
            assertThat(userIdx).isGreaterThanOrEqualTo(0);
            assertThat(customIdx).isGreaterThan(userIdx);
            // 缓冲已清空（第二次 prompt 不再注入）。
            s.processPrompt("again").statusFuture().get(30, TimeUnit.SECONDS);
            assertThat(customs(s)).hasSize(1);
        }
    }

    @Test
    void triggerTurnStartsARunAsynchronously() throws Exception {
        try (var s = session()) {
            var settledCount = new AtomicInteger();
            var latch = new CountDownLatch(2); // 首次 prompt + triggerTurn 各一条
            s.subscribe(event -> {
                if (event instanceof AgentSessionEvent.AgentSettled) {
                    settledCount.incrementAndGet();
                    latch.countDown();
                }
            });
            s.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);
            
            var id = s.sendCustomMessage("poke", CustomMessageContent.of("run please"),
                false, null, true, null);
            assertThat(id).isNull(); // triggerTurn 臂无即时条目

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(settledCount.get()).isEqualTo(2);
            // 条目在转录（先于新轮的助手消息）。
            assertThat(customs(s)).hasSize(1);
        }
    }

    @Test
    void streamingSteerIsDefaultArm() throws Exception {
        try (var s = session()) {
            // 流式中（run 在飞）无 triggerTurn ⇒ 默认 steer 臂（pi :2308-2313）。
            var queued = new java.util.concurrent.atomic.AtomicBoolean();
            var registration = s.harness().onStreamEvent(e -> {
                if (e instanceof com.pijava.ai.stream.StreamEvent.Start
                        && queued.compareAndSet(false, true)) {
                    s.sendCustomMessage("mid", CustomMessageContent.of("steered-context"),
                        false, null, null, null);
                }
            });
            try {
                s.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);
            } finally {
                registration.close();
            }
            assertThat(queued).isTrue();
            // steer 的 custom 在同一 run 内排空 ⇒ 落 custom_message 条目。
            var found = customs(s);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).customType()).isEqualTo("mid");
        }
    }

    @Test
    void streamingTriggerTurnFalseDefersToTurnEnd() throws Exception {
        try (var s = session()) {
            var queued = new java.util.concurrent.atomic.AtomicBoolean();
            var registration = s.harness().onStreamEvent(e -> {
                if (e instanceof com.pijava.ai.stream.StreamEvent.Start
                        && queued.compareAndSet(false, true)) {
                    // 臂 4（pi :2321-2326）：缓冲——此刻不落盘、不发事件。
                    var id = s.sendCustomMessage("deferred", CustomMessageContent.of("later"),
                        false, null, false, null);
                    assertThat(id).isNull();
                    assertThat(customs(s)).isEmpty();
                }
            });
            try {
                s.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);
            } finally {
                registration.close();
            }
            // turn_end flush 点后落盘。
            var found = customs(s);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).customType()).isEqualTo("deferred");
        }
    }

    @Test
    void promptDuringSettledEmissionDefersUntilAfterSettled() throws Exception {
        try (var s = session()) {
            // pi :1968-1972：settled 发射窗内的 prompt 进 deferredSettledActions。
            var deferred = new java.util.concurrent.atomic.AtomicBoolean();
            var secondDone = new CountDownLatch(1);
            var subscription = s.subscribe(event -> {
                if (event instanceof AgentSessionEvent.AgentSettled
                        && deferred.compareAndSet(false, true)) {
                    // 在 AgentSettled 发射窗内（监听者线程＝发射线程）提交 prompt。
                    Thread.startVirtualThread(() -> {
                        var result = s.processPrompt("second");
                        new Thread(() -> {
                            result.status();
                            secondDone.countDown();
                        }).start();
                    });
                }
            });
            try {
                s.processPrompt("first").statusFuture().get(30, TimeUnit.SECONDS);
                assertThat(secondDone.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                subscription.close();
            }
            var userTexts = s.harness().snapshot(s.laneName()).transcript().stream()
                .filter(e -> e instanceof Entry.Message m
                    && "user".equals(m.message().role()))
                .map(e -> ((Entry.Message) e).message().content().toString())
                .toList();
            assertThat(userTexts).hasSize(2);
            assertThat(userTexts.get(0)).contains("first");
            assertThat(userTexts.get(1)).contains("second");
        }
    }
}
