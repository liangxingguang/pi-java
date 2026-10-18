package com.pijava.agent.tool;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolSetFactoryTest {
    @Test
    void createReadOnlyToolsFollowsPiOrder() {
        // pi index.ts:205-212。
        var tools = ToolSetFactory.createReadOnlyTools();
        assertThat(tools.stream().map(AgentTool::name).toList())
            .containsExactly("read", "grep", "find", "ls");
    }
}
