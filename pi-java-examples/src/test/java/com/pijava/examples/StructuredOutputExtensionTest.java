package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.PromptConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P15：structured-output——工具带 promptSnippet/promptGuidelines/terminate:true；
 * 执行返回 details 三字段；回合在工具结果后直接结束（无第二次 LLM 回合）；
 * renderResult 两臂（裁决④ 责任链文本级渲染器，D173）。
 */
class StructuredOutputExtensionTest {

    @TempDir Path tmp;

    private static final Map<String, Object> ARGS = Map.of(
        "headline", "Release plan",
        "summary", "Ship on Friday.",
        "actionItems", List.of("cut branch", "tag release"));

    @Test
    void toolCarriesPromptMetadataAndTerminates() throws Exception {
        var tool = new StructuredOutputExtension.StructuredOutputTool(d -> { });
        assertThat(tool.promptSnippet())
            .isEqualTo("Emit a final structured answer as a terminating tool result");
        assertThat(tool.promptGuidelines()).containsExactly(
            "Use structured_output as your final action when the user asks for"
                + " structured output, JSON-like output, or a machine-readable summary.",
            "After calling structured_output, do not emit another assistant response"
                + " in the same turn.");
        // F3：TypeBox 键序 type/required/properties；array 臂 type/items/description
        var schema = tool.inputSchema();
        assertThat(schema.keySet()).containsExactly("type", "required", "properties");

        var result = tool.execute("t1", ARGS, null, null, null);
        assertThat(result.terminate()).isTrue();
        assertThat(ExampleFixtures.textOf(result.content()))
            .isEqualTo("Saved structured output: Release plan");
        assertThat(result.details()).containsEntry("headline", "Release plan")
            .containsEntry("summary", "Ship on Friday.")
            .containsEntry("actionItems", List.of("cut branch", "tag release"));
    }

    @Test
    void turnEndsRightAfterToolResult() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, StructuredOutputExtension.class)) {
            var toolTurn = AssistantMessage.empty()
                .withContent(List.of(
                    new ContentBlock.ToolUseContent("s1", "structured_output", ARGS)))
                .withStopReason(StopReason.TOOL_USE);
            // 单响应序列：若 terminate 未生效，第二回合重放同一工具调用
            // ⇒ 第二条助手消息
            var faux = FauxProvider.sequence("faux-so", List.of(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, "s1", "structured_output", ARGS, toolTurn),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null, toolTurn))));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var result = session.processPrompt("summarize", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var assistantMessages = session.accumulatedEntries().stream()
                    .filter(e -> e instanceof Entry.Message m
                        && m.message() instanceof Message.AssistantMessage)
                    .toList();
                assertThat(assistantMessages).hasSize(1);

                var toolResult = session.accumulatedEntries().stream()
                    .filter(e -> e instanceof Entry.Message m
                        && m.message() instanceof Message.ToolResultMessage)
                    .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
                    .findFirst().orElseThrow();
                assertThat(ExampleFixtures.textOf(toolResult.content()))
                    .isEqualTo("Saved structured output: Release plan");
            }
        }
    }

    @Test
    void rendererTwoArmsViaRegistry() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, StructuredOutputExtension.class)) {
            var faux = FauxProvider.sequence("faux-render", List.of(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, "s1", "structured_output", ARGS,
                    AssistantMessage.empty()
                        .withContent(List.of(
                            new ContentBlock.ToolUseContent("s1", "structured_output", ARGS)))
                        .withStopReason(StopReason.TOOL_USE)),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null,
                    AssistantMessage.empty()
                        .withContent(List.of(
                            new ContentBlock.ToolUseContent("s1", "structured_output", ARGS)))
                        .withStopReason(StopReason.TOOL_USE)))));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var registry = session.toolRenderers();
                assertThat(registry).isNotNull();
                var renderer = registry.resolve("structured_output", null);
                assertThat(renderer).isNotNull();

                // 无 details 臂（扩展实例尚无执行记录）⇒ 回落 content 首文本
                assertThat(renderer.renderResult("structured_output", "plain text", false, false))
                    .containsExactly("plain text");

                // 有 details 臂（执行后）⇒ headline／summary／空行／编号列表
                session.processPrompt("go", PromptConfig.defaults()).status();
                assertThat(renderer.renderResult("structured_output", "ignored", false, false))
                    .containsExactly("Release plan", "Ship on Friday.", "",
                        "1. cut branch", "2. tag release");

                // 其他工具名不接管（resolver 返 null ⇒ base null ⇒ 链尾 null）
                assertThat(registry.resolve("bash", null)).isNull();
            }
        }
    }
}
