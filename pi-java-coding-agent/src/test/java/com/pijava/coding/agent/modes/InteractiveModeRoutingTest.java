package com.pijava.coding.agent.modes;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.StreamObserver;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5（docs/25）：{@link InteractiveMode} 提交门 —— pi
 * {@code interactive-mode.ts:3331-3338}：运行中提交走 steer 排队、
 * 不产生新驱动线程。
 */
class InteractiveModeRoutingTest {

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

    @Test
    void submitWhileRunningQueuesSteerAndContinuesSameRun() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-im-route");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-im", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-im", List.of(
            textTurn("first reply"),
            textTurn("second reply")), 100));
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        try (var session = AgentSession.create(args, providers, toolContext)) {
            var events = new CopyOnWriteArrayList<StreamEvent>();
            var mode = new InteractiveMode(session);
            StreamObserver recording = events::add;
            mode.setObservers(e -> { }, recording);

            var first = mode.submit("hello");
            assertThat(first.disposition()).isEqualTo("started");

            // 必须等首轮 LLM 调用已在飞（首个流事件）再排队：PiLoop 每个 pass
            // 起手第一动作就是 drain steer（PiLoopRunner:54），过早入队会被并进
            // 首次请求，而不是触发第二回合。真实用户天然在流式输出后才提交。
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && events.isEmpty()) {
                Thread.sleep(5);
            }
            assertThat(events).isNotEmpty();

            var second = mode.submit("turn here");
            assertThat(second.disposition())
                .as("运行中提交：不起新 run，仅 steer 排队").isEqualTo("queued");

            // steer 在本 run 下一 pass 注入 ⇒ 引擎多跑一回合（second reply）。
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline
                    && events.stream().noneMatch(e -> e.toString().contains("second reply"))) {
                Thread.sleep(10);
            }
            assertThat(events.stream())
                .anyMatch(e -> e.toString().contains("second reply"));
        }
    }
}
