package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落盘时机对齐 pi 产品（docs/27 §2.1）。
 *
 * <p>pi 的 {@code session-manager._appendEntry → _persist} 在**每条 entry 产生后**
 * 立即 {@code appendFileSync}，所以崩溃窗口是"一条 entry"。pi-java 此前只在
 * **run 边界**批量 flush（{@code SessionRunner} 的 {@code AgentEnd} 前与
 * {@code AgentSettled} 前），崩溃窗口是"一整个 run"——多轮助手响应加工具调用，
 * 可能数分钟。</p>
 *
 * <p>本测试用一个 {@link StreamObserver} 在**第一个流事件到达时**（助手流已开始、
 * run 尚未结束）读取存储里的 entry 数。若落盘仍是 run 边界触发，此刻为 0；
 * 按 pi 的时机，用户 prompt 必须已经落盘。</p>
 *
 * <p>用 {@code --no-session} 建会话后手工挂上 JSONL 持久会话：in-memory 仓库
 * （{@code InMemorySessionRepository}）只登记 {@code AgentSession} 对象、不提供
 * {@code Session<?>} 存储，因此那条路径上落盘本就应当 no-op。</p>
 */
class SessionFlushTimingTest {

    @Test
    void userPromptIsPersistedBeforeTheAssistantStreamStarts() throws Exception {
        var root = Files.createTempDirectory("pi-java-flush-timing");
        var tmp = Files.createTempDirectory("pi-java-flush-cwd");

        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-flush", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var doneMsg = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("ok")))
            .withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-flush", List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, "ok", doneMsg),
            new StreamEvent.TextEnd(0, "ok", doneMsg),
            new StreamEvent.StreamDone("stop", null, doneMsg)))));

        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        var session = AgentSession.create(args, providers, toolContext);
        // 手工挂上 JSONL 持久会话（--no-session 跳过了 resolveSession）。
        var persistent = PersistentSessionRepositories.jsonl(root).create("cwd", null);
        session.session(persistent);

        // 第一个流事件到达时采样已落盘的 entry 数（-1 = 观察器未被调用）。
        var persistedAtFirstStreamEvent = new AtomicInteger(-1);
        try (session) {
            var result = session.processPrompt("hi", PromptConfig.defaults(),
                event -> persistedAtFirstStreamEvent.compareAndSet(-1, persistedCount(session)),
                null);
            // processPrompt 是异步的（虚拟线程 + 惰性 stream）：必须等 run 结束
            // 再退出 try，否则 close() 会在 drive 中途关掉 harness。
            result.status();
        }

        assertThat(persistedAtFirstStreamEvent.get())
            .as("助手流开始前，用户 prompt 的 entry 必须已经落盘"
                + "（docs/27 §2.1：pi 逐条写，崩溃窗口为一条 entry）")
            .isGreaterThanOrEqualTo(1);
    }

    private static int persistedCount(AgentSession session) {
        if (session.session() == null) {
            return -1;
        }
        return session.session().findEntries(
            new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null)).size();
    }
}
