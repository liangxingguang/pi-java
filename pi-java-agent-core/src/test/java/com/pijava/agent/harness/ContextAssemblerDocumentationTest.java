package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.prompt.SystemPromptOptions.ContextFile;
import com.pijava.agent.prompt.SystemPromptOptions.DocumentationPaths;
import com.pijava.agent.prompt.SystemPrompts;
import com.pijava.agent.skill.SkillManager;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 30（T6，B95）：{@code docs} 段的生产者接线——{@code ExecutionContext.documentation}
 * 槽进 {@link ContextAssembler#promptOptions} 装配；{@code null} ⇒ 段不产出
 * （SDK 默认，docs/42 裁决②）。
 */
class ContextAssemblerDocumentationTest {

    /** 最小上下文＋可选 documentation 槽（其余槽位与 ContextAssemblerSectionsTest 同形）。 */
    private static ContextAssembler assemblerWith(DocumentationPaths documentation) {
        var lane = new LaneState();
        var ctx = new ExecutionContext(
            null, () -> null, () -> com.pijava.ai.thinking.ModelThinkingLevel.off(),
            () -> "", () -> java.util.Set.of(),
            0, null, null,
            null, null, new SkillManager(),
            null, lane, null, null,
            null, null, null, null,
            null, null, null, NoopTelemetryContext.INSTANCE, null,
            null, null, null, java.util.List::of, () -> "",
            (Supplier<List<ContextFile>>) List::of, null,
            java.util.Map::of, documentation);
        return new ContextAssembler(ctx);
    }

    private static final DocumentationPaths PATHS =
        new DocumentationPaths("/repo/README.md", "/repo/docs", "/repo/pi-java-examples");

    @Test
    void documentationProducesTheDocsSection() {
        var assembler = assemblerWith(PATHS);
        var options = assembler.promptOptions(new LaneState());
        assertThat(options).isNotNull();
        assertThat(options.documentation()).isEqualTo(PATHS);
        var prompt = SystemPrompts.build(options);
        assertThat(prompt).contains("<docs>").contains("Main documentation: /repo/README.md")
            .contains("Additional docs: /repo/docs")
            .contains("Examples: /repo/pi-java-examples");
    }

    @Test
    void examplesLineOnlyWhenPresent() {
        var assembler = assemblerWith(
            new DocumentationPaths("/repo/README.md", "/repo/docs", null));
        var prompt = SystemPrompts.build(assembler.promptOptions(new LaneState()));
        assertThat(prompt).contains("<docs>").doesNotContain("Examples:");
    }

    @Test
    void nullDocumentationProducesNoDocsSection() {
        var assembler = assemblerWith(null);
        // 什么都没配 ⇒ promptOptions 的 null 臂（documentation 不救场也不需要救场）。
        assertThat(assembler.promptOptions(new LaneState())).isNull();
    }
}
