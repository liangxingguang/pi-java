package com.pijava.agent.tool;

import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {
    @Test
    void successCreatesTextResult() {
        var result = ToolResult.success("hello");
        assertThat(result.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text()).isEqualTo("hello");
        assertThat(result.details()).isNull();
    }

    @Test
    void successWithDetails() {
        var result = ToolResult.success("ok", "detail-value");
        assertThat(result.details()).isEqualTo("detail-value");
    }

    @Test
    void addedToolNamesDefaultsToEmpty() {
        var result = ToolResult.success("test");
        assertThat(result.addedToolNames()).isEmpty();
    }

    @Test
    void terminateDefaultsToFalse() {
        var result = ToolResult.success("test");
        assertThat(result.terminate()).isFalse();
    }
}
