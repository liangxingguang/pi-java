package com.pijava.examples.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** docs/56 §2.3：rpc-extension-ui 验收——九方法、三类回包、纯函数、事件顺序。 */
class RpcExtensionUiDemoTest {

    @Test
    void nineMethodsAndDialogSubset() {
        assertThat(RpcExtensionUiDemo.methods()).containsExactly(
            "select", "confirm", "input", "editor", "notify", "setStatus",
            "setWidget", "setTitle", "set_editor_text");
        assertThat(RpcExtensionUiDemo.dialogMethods())
            .containsExactly("select", "confirm", "input", "editor");
    }

    @Test
    void pureFunctions() {
        assertThat(RpcExtensionUiDemo.nextDots(0)).isEqualTo(1);
        assertThat(RpcExtensionUiDemo.nextDots(1)).isEqualTo(2);
        assertThat(RpcExtensionUiDemo.nextDots(3)).isEqualTo(1);
        assertThat(RpcExtensionUiDemo.flattenPrefill("line1\nline2"))
            .isEqualTo("line1 line2");
        assertThat(RpcExtensionUiDemo.flattenPrefill(null)).isNull();
        assertThat(RpcExtensionUiDemo.truncateResult("x".repeat(200)))
            .isEqualTo("x".repeat(120));
        assertThat(RpcExtensionUiDemo.tail(List.of(1, 2, 3, 4, 5), 3))
            .containsExactly(3, 4, 5);
        assertThat(RpcExtensionUiDemo.tail(List.of(1, 2), 5)).containsExactly(1, 2);
    }

    @Test
    void responseShapes() {
        assertThat(RpcExtensionUiDemo.withValue("id1", "Red").kind()).isEqualTo("value");
        assertThat(RpcExtensionUiDemo.confirmed("id2", true).kind()).isEqualTo("confirmed");
        assertThat(RpcExtensionUiDemo.confirmed("id2", true).value()).isEqualTo(true);
        assertThat(RpcExtensionUiDemo.cancelled("id3").kind()).isEqualTo("cancelled");
        assertThat(RpcExtensionUiDemo.cancelled("id3").value()).isEqualTo(true);
    }

    @Test
    void eventOrder() {
        assertThat(RpcExtensionUiDemo.eventOrder()).containsExactly(
            "response-error", "agent_start", "extension_ui_request",
            "message_update.text_delta", "tool_execution_start", "tool_execution_end",
            "agent_settled");
    }
}
