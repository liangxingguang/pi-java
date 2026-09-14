package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3d（docs/31 §8.22.3 测试⑤）：环 A 迁进引擎后的会话事件全序钉。
 *
 * <p>faux 脚本 [error("overloaded"), text("recovered")]，期望（pi
 * {@code agent-session.ts:655-735, 2917-2965}）：</p>
 * <pre>
 *   agent_end#1{willRetry:true}      ← pass 1 收尾，装饰判据 = retryWouldFollow
 *   auto_retry_start{1,3,"overloaded"}← 引擎 post-run ①（_prepareRetry 同形）
 *   auto_retry_end{true,1}           ← pass 2 的 message_end 成功复位（:698-706）
 *   agent_end#2{willRetry:false}     ← pass 2 收尾
 *   agent_settled
 * </pre>
 * <p>另钉：用户回显恰好一次（续跑不重复），退出码 0/reason stop。</p>
 */
class AgentSessionRetryEventOrderTest {

    private static List<StreamEvent> errorSeq() {
        var partial = AssistantMessage.empty().withStopReason("error");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamError("error", new RuntimeException("overloaded"), partial));
    }

    private static List<StreamEvent> textSeq(String text) {
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
    void retryEventsKeepPiOrderWithSingleEcho(@TempDir java.nio.file.Path tmp) throws Exception {
        // getRetryEnabled 在 create 时读真实全局 settings（pi 的 effective 读盘语义）——
        // user.home 指到临时目录保证「默认开」不被本机设置翻转；构造完成即还原。
        var home = Files.createTempDirectory("pi-java-retry-order-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AgentSession session;
        try {
            var args = ArgsParser.parse(new String[] {
                "--provider", "faux-order", "--model", "hello", "--no-session"});
            var providers = ProviderRegistry.create();
            providers.register(FauxProvider.sequence("faux-order",
                List.of(errorSeq(), textSeq("recovered"))));
            session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
        } finally {
            if (savedHome != null) {
                System.setProperty("user.home", savedHome);
            }
        }

        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (var sub = session.subscribe(events::add)) {
            var status = session.processPrompt("go")
                .statusFuture().get(20, TimeUnit.SECONDS);
            assertThat(status.exitCode()).isZero();
            assertThat(status.reason()).isEqualTo("stop");
        }

        int end1 = -1;
        int end2 = -1;
        int retryStart = -1;
        int retryEnd = -1;
        var ends = new ArrayList<AgentSessionEvent.AgentEnd>();
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            if (e instanceof AgentSessionEvent.AgentEnd) {
                ends.add((AgentSessionEvent.AgentEnd) e);
                if (end1 < 0) {
                    end1 = i;
                } else if (end2 < 0) {
                    end2 = i;
                }
            } else if (e instanceof AgentSessionEvent.AutoRetryStart && retryStart < 0) {
                retryStart = i;
            } else if (e instanceof AgentSessionEvent.AutoRetryEnd && retryEnd < 0) {
                retryEnd = i;
            }
        }

        // agent_end 每 pass 一条、恰好两条（pi 的 per-pass agent_end，:666）。
        assertThat(ends).hasSize(2);
        assertThat(end1).isNotNegative();
        assertThat(retryStart).isGreaterThan(end1);
        assertThat(retryEnd).isGreaterThan(retryStart);
        assertThat(end2).isGreaterThan(retryEnd);

        assertThat(ends.get(0).willRetry()).isTrue();
        assertThat(ends.get(1).willRetry()).isFalse();

        var start = (AgentSessionEvent.AutoRetryStart) events.get(retryStart);
        assertThat(start.attempt()).isEqualTo(1);
        assertThat(start.maxAttempts()).isEqualTo(3);
        // pi：errorMessage = 错误消息文本（withErrorShape 投影后分类器与事件同源）。
        assertThat(start.errorMessage()).isEqualTo("overloaded");
        assertThat(start.delayMs()).isPositive();

        var end = (AgentSessionEvent.AutoRetryEnd) events.get(retryEnd);
        assertThat(end.success()).isTrue();
        assertThat(end.attempt()).isEqualTo(1);

        // 用户回显只在首 pass 起点一次（续跑不重复注入）。
        assertThat(events.stream()
            .filter(e -> e instanceof AgentSessionEvent.UserMessageReceived)
            .count()).isEqualTo(1);
        // 静默收口：agent_settled 在所有事件之后。
        assertThat(events.get(events.size() - 1))
            .isInstanceOf(AgentSessionEvent.AgentSettled.class);
    }
}
