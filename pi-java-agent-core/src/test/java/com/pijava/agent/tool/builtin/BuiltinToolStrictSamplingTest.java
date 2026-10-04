package com.pijava.agent.tool.builtin;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StrictMode;
import com.pijava.ai.api.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原 docs/66：内置 read/bash/edit/write 默认 strict-prefer（pi
 * {@code builtin-tool-strict-mode.test.ts} 的断言）；其余工具不携带。
 */
class BuiltinToolStrictSamplingTest {

    @Test
    void readBashEditWritePreferStrictSampling() {
        var strictTools = List.of(
            ReadTool.create(), BashTool.create(),
            EditTool.create(), WriteTool.create());

        for (AgentTool<?, ?> tool : strictTools) {
            assertThat(tool.constrainedSampling())
                .isEqualTo(new JsonSchemaSampling(StrictMode.PREFER));
            // 投影到 ToolDefinition 后仍携带。
            var definition = com.pijava.agent.tool.ToolRegistry
                .definitionsOf(List.of(tool)).get(0);
            assertThat(definition.constrainedSampling())
                .isEqualTo(new JsonSchemaSampling(StrictMode.PREFER));
        }
    }

    @Test
    void otherBuiltinToolsDoNotCarrySampling() {
        var plainTools = List.of(
            GlobTool.create(), GrepTool.create(), LsTool.create());

        for (AgentTool<?, ?> tool : plainTools) {
            assertThat(tool.constrainedSampling()).isNull();
            var definition = com.pijava.agent.tool.ToolRegistry
                .definitionsOf(List.of(tool)).get(0);
            assertThat(definition.constrainedSampling()).isNull();
        }
    }
}
