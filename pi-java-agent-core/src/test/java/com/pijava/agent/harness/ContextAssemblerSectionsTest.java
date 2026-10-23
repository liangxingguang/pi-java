package com.pijava.agent.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.prompt.SystemPromptOptions.ContextFile;
import com.pijava.agent.prompt.SystemPrompts;
import com.pijava.agent.skill.SkillManager;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 16（T3a/T3b，docs/27）：promptSections 车道——LaneState 供应口经
 * ExecutionContext 进 {@link ContextAssembler#promptOptions} 装配；
 * 段名包装/校验/diff 补丁复用 {@link SystemPrompts} 既有机制
 * （pi system-prompt.ts:127-229 对位）。
 */
class ContextAssemblerSectionsTest {

    /** 最小上下文：只填 promptOptions 会读的槽位（promptSections 经 lane 现读）。 */
    private static ExecutionContext ctx(LaneState lane) {
        return new ExecutionContext(
            null, () -> null, () -> com.pijava.ai.thinking.ModelThinkingLevel.off(),
            () -> "", () -> java.util.Set.of(),
            0, null, null,
            null, null, new SkillManager(),
            null, lane, null, null,
            null, null, null, null,
            null, null, null, NoopTelemetryContext.INSTANCE, null,
            null, null, null, java.util.List::of, () -> "",
            (Supplier<List<ContextFile>>) List::of, null,
            () -> lane.promptSections);
    }

    /** 段进装配：build 产出含 {@code <name>} 包装段（pi :188-191 对位）。 */
    @Test
    void sectionsFlowIntoBuiltPrompt() {
        var lane = new LaneState();
        lane.promptSections = new LinkedHashMap<>(Map.of("mcp_servers", "server: one"));
        var assembler = new ContextAssembler(ctx(lane));

        var options = assembler.promptOptions(lane);
        assertThat(options).isNotNull();
        assertThat(options.sections()).containsEntry("mcp_servers", "server: one");
        assertThat(SystemPrompts.build(options))
            .contains("<mcp_servers>\nserver: one\n</mcp_servers>");
    }

    /** 段名非法 ⇒ 装配即抛（pi :144-148 文案逐字）。 */
    @Test
    void invalidSectionNameThrowsPiMessage() {
        var lane = new LaneState();
        lane.promptSections = Map.of("Bad Name", "x");
        var assembler = new ContextAssembler(ctx(lane));

        var options = assembler.promptOptions(lane);
        assertThatThrownBy(() -> SystemPrompts.buildSections(options))
            .hasMessageContaining("Invalid system prompt section name: Bad Name");
    }

    /** diff 补丁：与转录重放值比对，变化段进补丁（pi :217-229 既有机制的消费核验）。 */
    @Test
    void sectionChangeProducesPatchMessage() {
        var lane = new LaneState();
        lane.promptSections = Map.of("mcp_servers", "server: one");
        var assembler = new ContextAssembler(ctx(lane));

        // 转录为空（无系统消息）⇒ 全量段进补丁消息。
        var patch = assembler.promptLoadout(lane, List.of());
        assertThat(patch).isNotNull();
        // pi 逐字 {content: "", sections: 补丁}（content 是空文本块列表）。
        assertThat(com.pijava.ai.message.MessageTexts.contentText(patch.content())).isEmpty();
        assertThat(patch.sections()).containsKey("mcp_servers");

        // 第二节同值 ⇒ 无差不产补丁（pi :227-228 undefined 臂）。
        var patched = assembler.promptLoadout(lane, List.of(patch));
        assertThat(patched).isNull();
    }
}
