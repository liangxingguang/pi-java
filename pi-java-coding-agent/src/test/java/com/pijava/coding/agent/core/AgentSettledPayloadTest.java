package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
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
 * 包⑨（docs/36，B42）：`turn_end` 的载荷。
 *
 * <p>pi 的 {@code turn_end} 是 {@code { message: AgentMessage; toolResults:
 * ToolResultMessage[] }}，**两个字段都必填、一个 `?` 都没有**
 * （{@code agent/src/types.ts:438}），而且**原样上 RPC/JSON 线**
 * （{@code json-event.ts:48-51}）。pi-java 此前把它翻成一个**只有类型**的空事件。</p>
 *
 * <p>⚠️ <b>颗粒度如实说明</b>：pi 的 {@code turn_end} 是<b>每回合</b>一条，而 pi-java 的
 * {@code AgentSettled} 是<b>每次驱动</b>一条。若照字面取「最后一回合的工具结果」，
 * 那个字段在常见形状下<b>反而恒空</b>（最后那回合通常是纯文本收尾、没有工具调用）
 * ⇒ 本包按<b>本次驱动</b>填，让该字段真的能承载前端要的结果。
 * 颗粒度差异本身另登记（见 docs/36 §10）。</p>
 */
class AgentSettledPayloadTest {

    private static List<StreamEvent> toolCallSeq() {
        Map<String, Object> args = Map.of("path", "hello.py", "content", "print(1)");
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

    private static List<StreamEvent> doneSeq(String text) {
        var doneMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, doneMsg),
            new StreamEvent.TextEnd(0, text, doneMsg),
            new StreamEvent.StreamDone("stop", null, doneMsg));
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

    private static AgentSessionEvent.AgentSettled settledOf(List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.AgentSettled.class::isInstance)
            .map(AgentSessionEvent.AgentSettled.class::cast)
            .findFirst().orElseThrow();
    }

    @Test
    void settledCarriesTheAssistantMessageAndTheDrivesToolResults() throws Exception {
        var settled = settledOf(driveToolRun("faux-settled", 1));

        assertThat(settled.message())
            .as("pi 的 turn_end.message 必填 ⇒ 终局助手消息")
            .isInstanceOf(Message.AssistantMessage.class);
        assertThat(settled.toolResults()).hasSize(1);
        assertThat(settled.toolResults().get(0))
            .isInstanceOf(Message.ToolResultMessage.class);
        assertThat(((Message.ToolResultMessage) settled.toolResults().get(0)).toolUseId())
            .isEqualTo("id1");
    }

    @Test
    void settledCarriesEveryToolResultOfTheDrive() throws Exception {
        // 两次工具调用 ⇒ 两条结果都要带上（前端按 toolCallId 去重，多发是幂等的）。
        var settled = settledOf(driveToolRun("faux-settled2", 2));

        assertThat(settled.toolResults()).hasSize(2);
        assertThat(settled.toolResults()).allSatisfy(
            m -> assertThat(m).isInstanceOf(Message.ToolResultMessage.class));
    }

    @Test
    void settledToolResultsAreEmptyWhenNoToolRan() throws Exception {
        // 反向：没有工具调用时是**空列表**（pi 的无工具回合同样发 []），不是 null。
        var settled = settledOf(driveToolRun("faux-settled3", 0));

        assertThat(settled.toolResults()).isNotNull().isEmpty();
        assertThat(settled.message()).isNotNull();
    }

    @Test
    void compatConstructorStillWorksForPathsWithoutATurn() {
        // pi 的错误/中止路发 {message(合成失败消息), toolResults: []}；
        // pi-java 的错误路拿不到转写 ⇒ 用无参兼容构造器（两字段都缺）。
        var empty = new AgentSessionEvent.AgentSettled();

        assertThat(empty.message()).isNull();
        assertThat(empty.toolResults()).isNotNull().isEmpty();
    }
}
