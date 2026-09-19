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
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑦（docs/34）：工具执行生命周期事件要**出口到会话层**。
 *
 * <p>三条事件在 `PiLoop.Event` 里早已存在且与 pi 逐字段同形（`PiLoop.java:66-76`），
 * 但 `SessionRunner.passEvents` 只认 `MessageEnd`/`AgentStart` ⇒ 它们在会话层被丢弃，
 * 而 pi 的 `AgentSessionEvent` 是**带着这三条**的（`agent-session.ts:143-145` 复用
 * agent 联合）⇒ RPC 线逐字节透传（`json-event.ts:48-51`）、pi 的 TUI 逐字段读它
 * （`interactive-mode.ts:3324-3365`）。</p>
 *
 * <p>⚠️ **`update` 不在这里钉**：pi-java 的内置工具**没有一条**调 `onUpdate`
 * （docs/34 §4-F，登记 B46）⇒ 会话级的 update 在生产上结构性不可达，
 * 这个面只能被测试桩行使。此处只钉生产可达的 start/end。</p>
 */
class AgentSessionToolExecutionEventTest {

    private static List<StreamEvent> toolCallSeq() {
        Map<String, Object> args = Map.of("path", "hello.py", "content", "print(\"hi\")");
        var toolMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("id1", "write", args)))
            .withStopReason("tool_use");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.ToolCallStart(0, AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent(
                    "id1", "write", Map.of())))),
            new StreamEvent.ToolCallEnd(0, "id1", "write", args, toolMsg),
            new StreamEvent.StreamDone("tool_use", null, toolMsg));
    }

    private static List<StreamEvent> doneSeq() {
        var doneMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("done"))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, "done", doneMsg),
            new StreamEvent.TextEnd(0, "done", doneMsg),
            new StreamEvent.StreamDone("stop", null, doneMsg));
    }

    @Test
    void toolExecutionStartAndEndReachTheSessionEventStream() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-tool-exec-events");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-toolexec", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-toolexec",
            List.of(toolCallSeq(), doneSeq())));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));

        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (var sub = session.subscribe(events::add)) {
            session.processPrompt("write a hello file")
                .statusFuture().get(20, TimeUnit.SECONDS);
        }

        var starts = new ArrayList<AgentSessionEvent.ToolExecutionStart>();
        var ends = new ArrayList<AgentSessionEvent.ToolExecutionEnd>();
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentSessionEvent.ToolExecutionStart s) {
                starts.add(s);
                if (startIdx < 0) {
                    startIdx = i;
                }
            } else if (events.get(i) instanceof AgentSessionEvent.ToolExecutionEnd e) {
                ends.add(e);
                if (endIdx < 0) {
                    endIdx = i;
                }
            }
        }

        assertThat(starts).as("一次工具调用 ⇒ 一条 tool_execution_start").hasSize(1);
        assertThat(ends).as("一次工具调用 ⇒ 一条 tool_execution_end").hasSize(1);

        var start = starts.get(0);
        assertThat(start.toolCallId()).isEqualTo("id1");
        assertThat(start.toolName()).isEqualTo("write");
        assertThat(start.args())
            .as("args 是模型给的原始调用参数（pi：toolCall.arguments，未校验）")
            .containsEntry("path", "hello.py")
            .containsEntry("content", "print(\"hi\")");

        var end = ends.get(0);
        assertThat(end.toolCallId()).isEqualTo("id1");
        assertThat(end.toolName()).isEqualTo("write");
        assertThat(end.isError()).as("write 成功 ⇒ isError=false").isFalse();
        assertThat(end.result()).as("end 带完整结果（pi 的 finalized.result）").isNotNull();

        assertThat(startIdx).as("start 早于 end").isLessThan(endIdx);
    }

    @Test
    void toolExecutionEndPrecedesTheAgentEndOfThatPass() throws Exception {
        // pi 的规范单工具全序（agent-loop.test.ts:1188-1201）：
        //   tool_execution_start → tool_execution_end → message_*(toolResult) → turn_end → agent_end
        // ⇒ end 必须早于本 pass 的 agent_end。
        var tmp = Files.createTempDirectory("pi-java-tool-exec-order");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-toolexec2", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-toolexec2",
            List.of(toolCallSeq(), doneSeq())));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));

        var events = new CopyOnWriteArrayList<AgentSessionEvent>();
        try (var sub = session.subscribe(events::add)) {
            session.processPrompt("write a hello file")
                .statusFuture().get(20, TimeUnit.SECONDS);
        }

        int endIdx = -1;
        int agentEndIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentSessionEvent.ToolExecutionEnd && endIdx < 0) {
                endIdx = i;
            } else if (events.get(i) instanceof AgentSessionEvent.AgentEnd && agentEndIdx < 0) {
                agentEndIdx = i;
            }
        }

        assertThat(endIdx).isNotNegative();
        assertThat(agentEndIdx).isNotNegative();
        assertThat(endIdx).isLessThan(agentEndIdx);
    }
}
