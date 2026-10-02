package com.pijava.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * 包 A4c：系统提示的**段补丁生产者**（pi {@code _preparePromptAndToolLoadout}，
 * {@code agent-session.ts:1143-1170}）与它的两个调用点。
 *
 * <p>oracle 是 pi {@code packages/coding-agent/test/system-prompt-updates.test.ts} 的
 * {@code setActiveTools emits prompt sections and tool changes before the next request}
 * —— 同一条剧本、同一组期望（pi 实测 8/8 绿）。三件事一起被钉住：</p>
 *
 * <ol>
 *   <li><b>起手</b>：会话第一位是**段补丁**系统消息（{@code content} 空、{@code sections} 载提示），
 *       不是「提示文本塞在 content 里」；工具增删被 {@code declareToolChanges} **合并进同一条**
 *       （pi 的锚点语义）；</li>
 *   <li><b>中途工具变更</b>：{@code setActiveTools} 之后的下一次请求带一条**最小差分**补丁
 *       —— 只有变了的段（{@code tools}/{@code rules}），没变的（{@code preamble}/{@code cwd}）
 *       不出现在补丁里；</li>
 *   <li><b>不重复落盘</b>：补丁由 {@code RunLifecycle.startRun} 先行写入，循环重发的那一份是
 *       复制品，按时间戳抑制。</li>
 * </ol>
 */
class PromptLoadoutTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "loadout-model");

    @Test
    void theStartLoadoutCarriesEverySectionAndTheToolDeclaration() {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var h = harness(requests, scripted(List.of(textTurn("answer"))), first());

        h.prompt(AgentHarness.DEFAULT_LANE, "go", List.of(), null);

        var head = requests.get(0).get(0);
        assertThat(head).isInstanceOf(Message.SystemMessage.class);
        var system = (Message.SystemMessage) head;
        assertThat(system.content()).as("提示正文在 sections 里，content 只有中途追加的指令")
            .containsExactly(new ContentBlock.TextContent(""));
        assertThat(system.sections().keySet())
            .as("pi 的段名与顺序（preamble 裸着、其余包标签）")
            .containsExactly("preamble", "tools", "rules", "cwd");
        assertThat(system.sections().get("tools")).contains("- first: first prompt snippet");
        assertThat(system.sections().get("rules")).contains("- Use first carefully.");
        assertThat(system.toolsAdded()).extracting(d -> d.name()).containsExactly("first");

        // 转录序与 pi 同：[system(补丁), user, assistant]
        assertThat(transcriptRoles(h)).containsExactly("system", "user", "assistant");
    }

    /**
     * pi 的 {@code :110-145} —— 切工具之后的**下一个请求**带最小差分补丁，且工具增删合并在同一条上。
     */
    @Test
    void switchingToolsPatchesTheSectionsAndDeclaresTheToolDelta() {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var h = harness(requests, scripted(List.of(
            textTurn("first"),
            toolTurn("tc1", "first"),
            textTurn("second"))), first());

        h.prompt(AgentHarness.DEFAULT_LANE, "first", List.of(), null);
        h.setActiveTools(Set.of(second()));
        h.prompt(AgentHarness.DEFAULT_LANE, "second", List.of(), null);

        assertThat(requests).hasSize(3);

        var update = lastSystemMessage(requests.get(1));
        assertThat(update).isNotNull();
        assertThat(update.content()).containsExactly(new ContentBlock.TextContent(""));
        // 最小差分：只有变了的段
        assertThat(update.sections().keySet()).containsExactly("tools", "rules");
        assertThat(update.sections().get("tools")).contains("second prompt snippet")
            .as("被换掉的工具不再出现在工具表里").doesNotContain("first prompt snippet");
        assertThat(update.sections().get("rules")).contains("Use second carefully.")
            .doesNotContain("Use first carefully.");
        assertThat(update.toolsAdded()).extracting(d -> d.name()).containsExactly("second");
        assertThat(update.toolsRemoved()).extracting(r -> r.name()).containsExactly("first");

        // 被换掉之后仍调用它 ⇒ 错误结果（pi 的同一条断言）
        var toolResults = requests.get(2).stream()
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(Message.ToolResultMessage.class::cast)
            .toList();
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.get(0).isError()).isTrue();
        assertThat(toolResults.get(0).toolName()).isEqualTo("first");

        // 转录：两次运行各带**一条**起手补丁（循环重发的是副本，按时间戳抑制），
        // 且每条都排在那次运行的用户消息之前 —— 与 pi 的 `unshift` 同序。
        var transcript = transcriptOf(h);
        assertThat(transcript.stream().filter(Message.SystemMessage.class::isInstance).count())
            .as("起手补丁 + 切工具补丁，各一条").isEqualTo(2);
        assertThat(transcript.stream().map(Message::role).toList())
            .containsExactly("system", "user", "assistant",
                "system", "user", "assistant", "tool", "assistant");
    }

    /**
     * pi 的第二个调用点（{@code agent-session.ts:599-611}）：**同一个 run 之内**（
     * {@code prepareNextTurnWithContext}）切工具，补丁经由 {@code NextTurnUpdate.messages}
     * 交回循环。
     *
     * <p>⚠️ 没有这条夹具，那个通道**零覆盖**：java 的每次 {@code prompt()} 都是新 run，
     * 而新 run 的 {@code startPass} 自己会算一次补丁 —— 于是「跨 prompt 切工具」这条（pi 的
     * 那条 oracle）走的是起手那条路，`NextTurnUpdate.messages` 那一支被顺带掩盖。
     * 变异探针 C4（把该支改成恒 {@code null}）首次实测**零红**，正是这个盲区的证据。</p>
     */
    @Test
    void aToolSwitchInsideOneRunTravelsThroughTheNextTurnChannel() {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var h = harness(requests, scripted(List.of(
            toolTurn("tc1", "first"),
            textTurn("done"))), first());
        var switched = new boolean[1];
        h.hookSystem().onPrepareNextTurn(AgentHarness.DEFAULT_LANE, ctx -> {
            if (!switched[0]) {
                switched[0] = true;
                h.setActiveTools(Set.of(second()));
            }
            return null;
        });

        h.prompt(AgentHarness.DEFAULT_LANE, "go", List.of(), null);

        assertThat(switched[0]).as("钩子确实跑过（否则本用例恒绿）").isTrue();
        assertThat(requests).hasSize(2);

        var update = lastSystemMessage(requests.get(1));
        assertThat(update).as("第二轮请求带一条段补丁").isNotNull();
        assertThat(update.sections().keySet()).containsExactly("tools", "rules");
        assertThat(update.sections().get("tools")).contains("second prompt snippet")
            .doesNotContain("first prompt snippet");
        assertThat(update.toolsAdded()).extracting(d -> d.name()).containsExactly("second");
        assertThat(update.toolsRemoved()).extracting(r -> r.name()).containsExactly("first");
        // 第一轮那次调用发生在切换之前 ⇒ 仍成功
        var toolResults = requests.get(1).stream()
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(Message.ToolResultMessage.class::cast)
            .toList();
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.get(0).isError()).isFalse();
    }

    /** 段表没变 ⇒ **不产出**补丁（pi 的 {@code diffSystemPromptSections} 返回 undefined）。 */
    @Test
    void anUnchangedLoadoutProducesNoSecondPatch() {
        var requests = new CopyOnWriteArrayList<List<Message>>();
        var h = harness(requests, scripted(List.of(textTurn("one"), textTurn("two"))), first());

        h.prompt(AgentHarness.DEFAULT_LANE, "one", List.of(), null);
        h.prompt(AgentHarness.DEFAULT_LANE, "two", List.of(), null);

        assertThat(transcriptOf(h).stream().filter(Message.SystemMessage.class::isInstance).count())
            .isEqualTo(1);
        assertThat(requests.get(1)).as("第二个请求里也没有新的系统消息")
            .filteredOn(Message.SystemMessage.class::isInstance).hasSize(1);
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具
    // ═══════════════════════════════════════════════════════════

    private static AgentHarness harness(CopyOnWriteArrayList<List<Message>> requests,
                                        StreamFn script, AgentTool<?, ?> initialTool) {
        var registry = new ToolRegistry(null);
        registry.register(initialTool);
        var context = new ToolContext(System.getProperty("java.io.tmpdir"), Map.of(),
            new com.pijava.agent.tool.DefaultShellExecutor(),
            new com.pijava.agent.tool.DefaultFileSystem());
        var capturing = capturing(requests, script);
        return AgentHarness.create(new HarnessConfig(
            capturing, MODEL, ModelThinkingLevel.off(), "", Set.of(initialTool), 200_000,
            registry, context, null, null, Map.of(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE, ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    /** 记下每次请求的消息列表，再转给剧本。 */
    private static StreamFn capturing(List<List<Message>> requests, StreamFn inner) {
        return (model, context, options) -> {
            requests.add(List.copyOf(context.messages()));
            return inner.stream(model, context, options);
        };
    }

    private static StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;

                @Override public boolean hasNext() { return i < script.size(); }
                @Override public StreamEvent next() { return script.get(i++); }
                @Override public void close() { }
            };
        };
    }

    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop")
            .withUsage(new StreamEvent.UsageInfo(11, 7, null, null));
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.UsageInfo(11, 7, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static List<StreamEvent> toolTurn(String callId, String name) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, Map.of())))
            .withStopReason("toolUse")
            .withUsage(new StreamEvent.UsageInfo(5, 3, null, null));
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.ToolCallEnd(0, callId, name, Map.of(), done),
            new StreamEvent.UsageInfo(5, 3, done),
            new StreamEvent.StreamDone("toolUse", null, done));
    }

    /** 带片段与准则的工具（pi 的 {@code promptSnippet}/{@code promptGuidelines}）。 */
    private static AgentTool<String, Void> named(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return name + " description"; }
            @Override public String promptSnippet() { return name + " prompt snippet"; }
            @Override public List<String> promptGuidelines() { return List.of("Use " + name + " carefully."); }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return ""; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success("ok");
            }
        };
    }

    private static AgentTool<String, Void> first() {
        return named("first");
    }

    private static AgentTool<String, Void> second() {
        return named("second");
    }

    private static Message.SystemMessage lastSystemMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.SystemMessage system) {
                return system;
            }
        }
        return null;
    }

    private static List<Message> transcriptOf(AgentHarness h) {
        return h.snapshot(AgentHarness.DEFAULT_LANE).transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
    }

    private static List<String> transcriptRoles(AgentHarness h) {
        return transcriptOf(h).stream().map(Message::role).toList();
    }
}
