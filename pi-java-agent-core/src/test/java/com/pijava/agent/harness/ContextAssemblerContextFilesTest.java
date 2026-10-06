package com.pijava.agent.harness;

import java.util.List;
import java.util.function.Supplier;

import com.pijava.agent.prompt.SystemPromptOptions;
import com.pijava.agent.prompt.SystemPromptOptions.ContextFile;
import com.pijava.agent.prompt.SystemPrompts;
import com.pijava.agent.skill.SkillManager;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring of the context-file discovery slot（包 24，B64）：
 * {@link ContextAssembler#promptOptions} must carry the late-read
 * {@code contextFiles} supplier into the prompt options.
 */
class ContextAssemblerContextFilesTest {

    /** Minimal context: only the slots promptOptions reads; defaults fill the rest. */
    private static ExecutionContext ctx(
            LaneState lane, Supplier<List<ContextFile>> contextFiles) {
        return new ExecutionContext(
            null, () -> null, () -> com.pijava.ai.thinking.ModelThinkingLevel.off(),
            () -> "", () -> java.util.Set.of(),
            0, null, null,
            null, null, new SkillManager(),
            null, lane, null, null,
            null, null, null, null,
            null, null, null, NoopTelemetryContext.INSTANCE, null,
            null, null, null, java.util.List::of, () -> "", contextFiles);
    }

    @Test
    void contextFilesFlowIntoPromptOptions() {
        var lane = new LaneState();
        var file = new ContextFile("/project/AGENTS.md", "build with maven");
        var assembler = new ContextAssembler(ctx(lane, () -> List.of(file)));

        SystemPromptOptions options = assembler.promptOptions(lane);

        assertThat(options).isNotNull();
        assertThat(options.contextFiles())
            .containsExactly(file);
        // render: project_instructions tag carries path and content
        assertThat(SystemPrompts.build(options))
            .contains("<project_instructions path=\"/project/AGENTS.md\">")
            .contains("build with maven");
    }

    @Test
    void noContextFilesWhenSupplierEmpty() {
        var lane = new LaneState();
        var assembler = new ContextAssembler(ctx(lane, List::of));

        var options = assembler.promptOptions(lane);

        // Nothing else configured ⇒ pi's empty ⇒ null options, no project_context
        assertThat(options).isNull();
    }
}
