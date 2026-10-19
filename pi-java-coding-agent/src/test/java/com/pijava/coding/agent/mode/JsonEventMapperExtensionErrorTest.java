package com.pijava.coding.agent.mode;

import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.extension.ExtensionError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 §7.6：{@code extension_error} 线形 —— pi rpc-mode.ts:349 逐字：
 * {@code {type, extensionPath, event, error}} 四键，{@code stack} 不上线；
 * {@code extensionPath} 在 java 是扩展名。
 */
class JsonEventMapperExtensionErrorTest {

    @Test
    void extensionErrorWireShapeIsVerbatimPi() {
        var event = new AgentSessionEvent.ExtensionError(
            new ExtensionError("my-ext", "tool_result", "boom", "STACK-TRACE"));

        var node = JsonEventMapper.toWire(event);

        assertThat(node.get("type").asText()).isEqualTo("extension_error");
        assertThat(node.get("extensionPath").asText()).isEqualTo("my-ext");
        assertThat(node.get("event").asText()).isEqualTo("tool_result");
        assertThat(node.get("error").asText()).isEqualTo("boom");
        assertThat(node.has("stack")).as("stack 不上线（pi 同）").isFalse();
        var keys = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(
            "type", "extensionPath", "event", "error");
    }
}
