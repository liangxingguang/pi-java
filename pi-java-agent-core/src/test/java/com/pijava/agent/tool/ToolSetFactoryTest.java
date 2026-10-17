package com.pijava.agent.tool;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolSetFactoryTest {
    @Test
    void createCodingToolsFollowsPiCreateAllToolsOrder() {
        // pi index.ts:213-224（无 powershell —— B241 随 B236 批）。
        var tools = ToolSetFactory.createCodingTools(null);
        assertThat(tools.stream().map(AgentTool::name).toList())
            .containsExactly("read", "bash", "edit", "write", "grep", "find", "ls");
    }

    @Test
    void createReadOnlyToolsFollowsPiOrder() {
        // pi index.ts:205-212。
        var tools = ToolSetFactory.createReadOnlyTools();
        assertThat(tools.stream().map(AgentTool::name).toList())
            .containsExactly("read", "grep", "find", "ls");
    }
}
