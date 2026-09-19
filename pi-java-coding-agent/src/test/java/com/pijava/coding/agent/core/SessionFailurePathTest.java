package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 第 1 步（{@code docs/31 §8.36.4}）的宿主侧守卫：{@code SessionRunner.drive} 的两处
 * {@code catch} 必须是 {@code Throwable}（pi 的 {@code catch} 无类型，{@code agent.ts:500}），
 * 且两个 future 的落定要由 {@code finally} 兜底 —— 否则 {@code SessionResult.status()/entries()}
 * 是 {@code join} ⇒ 打印模式永久挂起。
 *
 * <p><b>注入点为什么是「会话监听器抛 {@code Error}」而不是其它</b>：{@code SessionEventHub.emit}
 * 只隔离 {@code RuntimeException}（{@code :35}），{@code Error} 原样穿出 —— 这是本仓库里
 * 唯一能在**宿主层**制造 {@code Error} 的生产可达路径（钩子、工具都已经被别的层收口）。</p>
 *
 * <p><b>两条夹具必须分家</b>（否则 {@code P1}/{@code P3} 两条变异探针会互相污染、变成多红）：
 * 2a 的监听器**只**在 {@code AgentSettled} 上抛（钉 {@code SessionRunner:169} 的外层 catch 与
 * {@code finally} 兜底），2b 的监听器**只**在 {@code AgentEnd} 上抛（钉 {@code :105} 的内层
 * catch —— 它是唯一能把 {@code Error} 送出 {@code harness.prompt} 的形状）。</p>
 *
 * <p>引擎侧的形状断言（合成消息的字段、四事件、转录与结算）住在 agent-core 的
 * {@code EngineFailureSettlementTest}：{@code PiLoop.Event} 只在引擎边界可见。</p>
 */
class SessionFailurePathTest {

    /** 恒抛 {@code AssertionError} 的工具（{@code Error} 不在 {@code PiToolRunner} 的射程内）。 */
    private static AgentTool<String, Void> thrower(String name, String message) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Always throws"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return ""; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                throw new AssertionError(message);
            }
        };
    }

    private static AgentSession session(String provider, List<List<StreamEvent>> seqs, Path tmp,
                                        Set<AgentTool<?, ?>> tools) {
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(provider, seqs));
        var session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        session.harness().setActiveTools(tools);
        return session;
    }

    private static List<StreamEvent> textSeq(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static List<StreamEvent> toolCallSeq(List<ContentBlock.ToolUseContent> calls) {
        var partial = AssistantMessage.empty()
            .withContent(new ArrayList<ContentBlock>(calls))
            .withStopReason("tool_use");
        var events = new ArrayList<StreamEvent>();
        events.add(new StreamEvent.Start(AssistantMessage.empty()));
        for (int i = 0; i < calls.size(); i++) {
            events.add(new StreamEvent.ToolCallStart(i, AssistantMessage.empty()));
            events.add(new StreamEvent.ToolCallEnd(i, calls.get(i).id(), calls.get(i).name(),
                calls.get(i).arguments(), partial));
        }
        events.add(new StreamEvent.StreamDone("tool_use", null, partial));
        return List.copyOf(events);
    }

    private static List<AgentSessionEvent.AgentEnd> agentEnds(
            List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.AgentEnd.class::isInstance)
            .map(AgentSessionEvent.AgentEnd.class::cast)
            .toList();
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具 1 的宿主半边（A 组）：引擎内 Error 的活性与终局
    // ═══════════════════════════════════════════════════════════

    /**
     * A1/A2/A3（{@code docs/31 §8.36.6}）：引擎把工具抛出的 {@code Error} 收成失败助手消息后，
     * <b>流上不会再有终局信号</b> ⇒ 宿主只能照 pi {@code modes/print-mode.ts:139-155}
     * 在 {@code prompt()} 返回后读尾 assistant（{@code SessionRunner} 的 {@code tailAssistant} 判定）。
     * 没有这一步，崩溃的 run 会被记成 {@code (0, completed)}。
     */
    @Test
    void engineInternalErrorSettlesRunWithErrorStatus(@TempDir Path tmp) throws Exception {
        var session = session("faux-boom",
            List.of(toolCallSeq(List.of(new ContentBlock.ToolUseContent("c1", "boom", Map.of())))),
            tmp, Set.<AgentTool<?, ?>>of(thrower("boom", "boom")));
        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (session; var ignored = session.subscribe(events::add)) {
            var result = session.processPrompt("go", PromptConfig.defaults());

            // A1：future 落定且结算是失败（回退第 2 步的宿主读尾 ⇒ 这里恒为 (0, completed)）
            var status = result.statusFuture().get(10, TimeUnit.SECONDS);
            assertThat(status.exitCode()).isEqualTo(1);
            assertThat(status.reason()).isEqualTo("error");

            // A3：pi 的引擎内失败走**消息**、不走流错误
            assertThat(result.stream().toList())
                .as("引擎内的抛出不该伪装成 provider 流错误")
                .noneMatch(StreamEvent.StreamError.class::isInstance);

            // A2：合成的失败消息经 agent_end 到达会话事件面
            assertThat(agentEnds(events))
                .anySatisfy(end -> assertThat(end.messages())
                    .anySatisfy(m -> assertThat(m).isInstanceOfSatisfying(
                        Message.AssistantMessage.class,
                        a -> assertThat(a.errorMessage()).isEqualTo("boom"))));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具 2a：catch 体自己抛 ⇒ 只有 finally 兜底
    // ═══════════════════════════════════════════════════════════

    /**
     * B1/B2：监听器在 {@code AgentSettled} 上抛。抛出点落在 {@code SessionRunner} 的
     * <b>正常收尾路的 {@code AgentSettled} 投递</b>（{@code :156}）—— 也就是宿主外层 try 的中段 ⇒
     * 走 {@code :169} 的外层 catch。而同一个监听器在 catch 体内的
     * {@code AgentSettled}（{@code :180}）上**再抛一次**，于是 catch 尾部的两次
     * {@code complete}（{@code :181-182}）根本跑不到 ⇒ <b>只有 {@code finally}（{@code :193-194}）
     * 能救这两个 future</b>。
     *
     * <p>（这正是 {@code finally} 兜底那一行的唯一判别器：删掉它，本用例在
     * {@code statusFuture().get(10s)} 上超时。）</p>
     */
    @Test
    void listenerErrorOnAgentSettledStillSettlesFutures(@TempDir Path tmp) throws Exception {
        var session = session("faux-settled-boom", List.of(textSeq("hi")), tmp, Set.of());
        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (session; var ignored = session.subscribe(e -> {
            events.add(e);
            if (e instanceof AgentSessionEvent.AgentSettled) {
                throw new AssertionError("listener-boom");
            }
        })) {
            var result = session.processPrompt("go", PromptConfig.defaults());

            // B1：两个 future 都落定 —— 没有 finally 兜底就是永久挂起
            var status = result.statusFuture().get(10, TimeUnit.SECONDS);
            assertThat(status.exitCode()).isEqualTo(1);
            assertThat(status.reason()).isEqualTo("error");

            // B2：外层 catch 真的接住了（回退成 catch (Exception) ⇒ Error 直穿、这条路不发错误）
            assertThat(result.stream().toList())
                .anyMatch(StreamEvent.StreamError.class::isInstance);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具 2b：Error 逃出引擎之后谁来接（宿主 :114）
    // ═══════════════════════════════════════════════════════════

    /**
     * B3/B4/B5：监听器在 {@code AgentEnd} 上抛。
     *
     * <p>抛出点有两处、都在**引擎之内**：pass 收尾时 {@code passEvents} 投递的
     * {@code AgentEnd}，以及引擎 catch 体里 {@code RunFailure.settle} 之后投递的那一条
     * —— 第二条正是「catch 体自己抛」的形状（{@code docs/31 §8.36.5} 下游表末行）。
     * 于是 {@code Error} 穿过 {@code harness.prompt} 落到宿主的 {@code :114}。</p>
     *
     * <p><b>B5 才是 P1 的判别器</b>：{@code :105} 若回退成 {@code catch (Exception)}，
     * 这个 {@code Error} 会掉到 {@code :169}（那里是 {@code Throwable}），于是
     * {@code :179} 的 {@code AgentEnd(List.of(), false)} 就会冒出来 —— 那是一条
     * <b>载荷为空的 agent_end</b>，pi 的四处发射点没有一处是空的（登记 -15(a)）。
     * B4 两条路都发 {@code StreamError}，当判别器会得到「回退也绿」的假探针。</p>
     *
     * <p><b>为什么断言的是「没有空载荷」而不是「没有 AgentEnd」</b>：注入点落在
     * {@code AgentEnd} 上，{@code passEvents} 那条（{@code SessionRunner:235}）**先发出去才抛**，
     * 所以事件流里必然有 {@code AgentEnd}；缺的只可能是「空载荷」那一条。</p>
     */
    @Test
    void listenerErrorOnAgentEndReachesHostFailurePath(@TempDir Path tmp) throws Exception {
        var session = session("faux-end-boom", List.of(textSeq("hi")), tmp, Set.of());
        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (session; var ignored = session.subscribe(e -> {
            events.add(e);
            if (e instanceof AgentSessionEvent.AgentEnd) {
                throw new AssertionError("listener-boom");
            }
        })) {
            var result = session.processPrompt("go", PromptConfig.defaults());

            // B3：宿主的 :114 catch 收住了 ⇒ 照常结算并落定（不是冒泡出线程）
            var status = result.statusFuture().get(10, TimeUnit.SECONDS);
            assertThat(status.exitCode()).isEqualTo(1);
            assertThat(status.reason()).isEqualTo("error");

            // B4：`:114` 那处会补一条流错误（两条路都发 ⇒ 不是判别器）
            assertThat(result.stream().toList())
                .anyMatch(StreamEvent.StreamError.class::isInstance);

            // B5：没有空载荷的 agent_end（只有 :186 那条会发空的）
            assertThat(agentEnds(events))
                .as("宿主层的空载荷 agent_end（登记 -15(a)）不该出现")
                .noneMatch(end -> end.messages().isEmpty());
        }
    }
}
