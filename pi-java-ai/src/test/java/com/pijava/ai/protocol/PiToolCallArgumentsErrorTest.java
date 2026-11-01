package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** pi-messages terminal ToolCall wire shape retains all local metadata. */
class PiToolCallArgumentsErrorTest {

    @Test
    void terminalCallCarriesNamespaceErrorAndNestedNulls() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("value", null);
        List<Object> list = new ArrayList<>();
        list.add(null);
        arguments.put("list", list);
        PiToolCall call = new PiToolCall("id", "name", arguments, "signature", "ns", "error");

        assertThat(call.namespace()).isEqualTo("ns");
        assertThat(call.argumentsError()).isEqualTo("error");
        assertThat(call.arguments().containsKey("value")).isTrue();
        assertThat(call.arguments().get("value")).isNull();
        assertThat((List<?>) call.arguments().get("list")).hasSize(1);
        assertThat(((List<?>) call.arguments().get("list")).getFirst()).isNull();

        PiToolCall legacy = new PiToolCall("id", "name", Map.of());
        assertThat(legacy.namespace()).isNull();
        assertThat(legacy.argumentsError()).isNull();
    }
}
