package com.pijava.agent.tool;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolSetFactoryTest {
    @Test
    void createCodingToolsReturnsAllSeven() {
        var tools = ToolSetFactory.createCodingTools("/tmp", null);
        assertThat(tools).hasSize(7);
    }

    @Test
    void createReadOnlyToolsHasNoMutationTools() {
        var tools = ToolSetFactory.createReadOnlyTools("/tmp");
        var names = tools.stream().map(AgentTool::name).toList();
        assertThat(names).doesNotContain("bash", "write", "edit");
        assertThat(tools).hasSize(4);
    }
}
