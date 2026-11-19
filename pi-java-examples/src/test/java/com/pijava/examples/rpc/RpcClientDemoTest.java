package com.pijava.examples.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/56 §2.3：rpc-client 验收——默认提示词、事件过滤、工具前缀、prompt 形状。 */
class RpcClientDemoTest {

    @Test
    void defaultPrompt() {
        assertThat(RpcClientDemo.promptOf()).isEqualTo("Explain this repository in one paragraph.");
        assertThat(RpcClientDemo.promptOf("")).isEqualTo("Explain this repository in one paragraph.");
        assertThat(RpcClientDemo.promptOf("explain", "this")).isEqualTo("explain this");
    }

    @Test
    void eventFilters() {
        assertThat(RpcClientDemo.isTextDelta("message_update", "text_delta")).isTrue();
        assertThat(RpcClientDemo.isTextDelta("message_update", "thinking_delta")).isFalse();
        assertThat(RpcClientDemo.isTextDelta("agent_start", "text_delta")).isFalse();
        assertThat(RpcClientDemo.isToolStart("tool_execution_start")).isTrue();
        assertThat(RpcClientDemo.isToolStart("tool_execution_end")).isFalse();
    }

    @Test
    void toolPrefixAndPromptRequest() {
        assertThat(RpcClientDemo.toolPrefix("bash")).isEqualTo("\n[tool: bash]\n");
        var req = RpcClientDemo.promptRequest(1, "hello");
        assertThat(req.id()).isEqualTo("req_1");
        assertThat(req.type()).isEqualTo("prompt");
        assertThat(req.message()).isEqualTo("hello");
    }
}
