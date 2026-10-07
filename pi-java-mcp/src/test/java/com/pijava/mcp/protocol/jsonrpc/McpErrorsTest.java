package com.pijava.mcp.protocol.jsonrpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpErrorsTest {

    @Test
    void connectionClosedCarriesDefaultMessage() {
        assertThat(new McpConnectionClosedError().getMessage()).isEqualTo("MCP connection closed");
        assertThat(new McpConnectionClosedError("custom").getMessage()).isEqualTo("custom");
    }

    @Test
    void timeoutCarriesTimeoutMs() {
        var error = new McpTimeoutError(5_000);
        assertThat(error.timeoutMs()).isEqualTo(5_000);
        assertThat(error.getMessage()).isEqualTo("MCP request timed out after 5000ms");
    }

    @Test
    void abortCarriesDefaultMessage() {
        assertThat(new McpAbortError().getMessage()).isEqualTo("MCP request aborted");
    }

    @Test
    void mcpErrorCarriesCodeAndData() {
        var standard = new McpError(JsonRpcErrorCode.INTERNAL_ERROR, "x");
        assertThat(standard.code()).isEqualTo(-32603);
        assertThat(standard.data()).isNull();

        var custom = new McpError(42, "m", "data");
        assertThat(custom.code()).isEqualTo(42);
        assertThat(custom.data()).isEqualTo("data");
    }
}
