package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 2 迁移：{@code AgentSettled} 重塑为 pi 的 {@code { type: "agent_settled";
 * aborted: boolean }}（{@code agent-session.ts:203}）—— 每次驱动恰一条，aborted
 * 读会话层 abort 请求旗。旧载荷（message + toolResults 冒充 turn_end）由真
 * {@link AgentSessionEvent.TurnEnd} 继承（{@code types.ts:517}，两字段必填、
 * <b>每回合</b>一条、引擎直供）——「每驱动 vs 每回合」的颗粒度偏差就此拆除。
 */
class AgentSettledPayloadTest {

    private static List<StreamEvent> toolCallSeq() {
        Map<String, Object> args = Map.of("path", "hello.py", "content", "print(1)");
        var toolMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("id1", "write", args)))
            .withStopReason(StopReason.TOOL_USE);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.ToolCallStart(0, AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent(
                    "id1", "write", Map.of())))),
            new StreamEvent.ToolCallEnd(0, "id1", "write", args, toolMsg),
            new StreamEvent.StreamDone(StopReason.TOOL_USE, null, toolMsg));
    }

    private static List<StreamEvent> doneSeq(String text) {
        var doneMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, doneMsg),
            new StreamEvent.TextEnd(0, text, doneMsg),
            new StreamEvent.StreamDone(StopReason.STOP, null, doneMsg));
    }

    private static List<AgentSessionEvent> driveToolRun(String provider, int tools)
            throws Exception {
        var tmp = Files.createTempDirectory("pi-java-settled-payload");
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var script = new ArrayList<List<StreamEvent>>();
        for (int i = 0; i < tools; i++) {
            script.add(toolCallSeq());
        }
        script.add(doneSeq("done"));
        providers.register(FauxProvider.sequence(provider, script));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));

        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (var sub = session.subscribe(events::add)) {
            session.processPrompt("go").statusFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        return events;
    }

    private static List<AgentSessionEvent.AgentSettled> settledOf(List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.AgentSettled.class::isInstance)
            .map(AgentSessionEvent.AgentSettled.class::cast)
            .toList();
    }

    private static List<AgentSessionEvent.TurnEnd> turnEndsOf(List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.TurnEnd.class::isInstance)
            .map(AgentSessionEvent.TurnEnd.class::cast)
            .toList();
    }

    @Test
    void settledIsEmittedOncePerDriveWithAbortedFalse() throws Exception {
        var settled = settledOf(driveToolRun("faux-settled", 1));

        assertThat(settled)
            .as("pi agent-session.ts:1849 —— 每次 _runAgentPrompt 恰一条")
            .hasSize(1);
        assertThat(settled.get(0).aborted())
            .as("正常路无 abort 请求 ⇒ aborted=false")
            .isFalse();
    }

    @Test
    void turnEndIsPerTurnAndCarriesThatTurnsPayload() throws Exception {
        var turnEnds = turnEndsOf(driveToolRun("faux-settled2", 2));

        // 两次工具回合 + 一次文本收尾 ⇒ 三条 turn_end（每回合一条，pi 同粒度）。
        assertThat(turnEnds).hasSize(3);
        for (var turnEnd : turnEnds) {
            assertThat(turnEnd.message())
                .as("pi 的 turn_end.message 必填 ⇒ 本回合的助手消息，恒非 null")
                .isInstanceOf(Message.AssistantMessage.class);
        }
        assertThat(turnEnds.get(0).toolResults()).hasSize(1);
        assertThat(turnEnds.get(0).toolResults().get(0).toolUseId()).isEqualTo("id1");
        assertThat(turnEnds.get(2).toolResults())
            .as("文本收尾回合没有工具结果（pi 无工具回合同样发空数组，非 null）")
            .isNotNull().isEmpty();
    }

    @Test
    void turnEndToolResultsAreEmptyWhenNoToolRan() throws Exception {
        var turnEnds = turnEndsOf(driveToolRun("faux-settled3", 0));

        assertThat(turnEnds).hasSize(1);
        assertThat(turnEnds.get(0).toolResults()).isNotNull().isEmpty();
        assertThat(turnEnds.get(0).message()).isNotNull();
    }
}
