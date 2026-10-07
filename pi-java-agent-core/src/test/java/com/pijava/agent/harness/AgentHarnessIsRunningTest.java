package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5（docs/25）：{@code AgentHarness.isRunning(lane)} 宿主可见的运行窗口。
 *
 * <p>pi 的路由门（{@code agent-session.ts:1966}）读的是会话级
 * {@code isStreaming}；Java 侧对应即 {@code LaneState.isRunning()}，
 * 本测试钉住其公开口在运行中/空闲两个时刻的取值。</p>
 */
class AgentHarnessIsRunningTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    @Test
    void isRunningTracksActiveRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();

        StreamFn blockingFn = (model, context, options) -> new StreamIterator() {
            private int i;

            @Override
            public boolean hasNext() {
                if (i == 0) {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return i < 2;
            }

            @Override
            public StreamEvent next() {
                return switch (++i) {
                    case 1 -> new StreamEvent.Start(AssistantMessage.empty());
                    default -> new StreamEvent.StreamDone("stop", null,
                        AssistantMessage.empty().withStopReason("stop"));
                };
            }

            @Override
            public void close() { }
        };

        try (var h = AgentHarness.create(new HarnessConfig(
                blockingFn, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, new ToolRegistry(null), null, null,
                null, Map.of(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }))) {
            assertThat(h.isRunning("default"))
                .as("空闲时 isRunning=false").isFalse();

            var thread = Thread.ofVirtual().start(() -> {
                try {
                    h.prompt("default", "go", List.of());
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            entered.await();
            assertThat(h.isRunning("default"))
                .as("运行中 isRunning=true").isTrue();

            release.countDown();
            thread.join();
            assertThat(error.get()).isNull();
            assertThat(h.isRunning("default"))
                .as("收尾后 isRunning=false").isFalse();
        }
    }
}
