package com.pijava.coding.agent.rpc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.coding.agent.extension.ExtensionUiDialogOptions;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 20（E7-2 核销，T3）：extension_ui 双向通道的线格 parity——pi
 * rpc-types.ts:253-297 的九 method 与三 response 形逐键对拍。
 * 生产者＝{@link RpcExtensionUI}（批 17 落地）；本类钉键名/键形不漂。
 */
class ExtensionUiWireParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Capture {
        final List<RpcExtensionUIRequest> blocking = new CopyOnWriteArrayList<>();
        final List<RpcExtensionUIRequest> fireAndForget = new CopyOnWriteArrayList<>();

        RpcExtensionUI ui() {
            return new RpcExtensionUI(
                request -> {
                    blocking.add(request);
                    return new RpcExtensionUIResponse(request.id(), "v", null, null);
                },
                fireAndForget::add);
        }
    }

    private static JsonNode wire(RpcExtensionUIRequest request) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(request));
    }

    @Test
    void selectWireShape() throws Exception {
        var capture = new Capture();
        capture.ui().select("Pick", List.of("a", "b"),
            new ExtensionUiDialogOptions(null, 5_000L));
        var node = wire(capture.blocking.get(0));
        // pi：{type, id, method:"select", title, options, timeout?}——参数平铺顶层。
        assertThat(node.get("type").asText()).isEqualTo("extension_ui_request");
        assertThat(node.get("method").asText()).isEqualTo("select");
        assertThat(node.get("title").asText()).isEqualTo("Pick");
        assertThat(node.get("options")).hasSize(2);
        assertThat(node.get("timeout").asInt()).isEqualTo(5_000);
        assertThat(node.has("params")).as("pi spread——无 params 包裹键").isFalse();
    }

    @Test
    void confirmAndInputWireShapes() throws Exception {
        var capture = new Capture();
        capture.ui().confirm("T", "msg", null);
        capture.ui().input("T", "hint", null);
        var confirm = wire(capture.blocking.get(0));
        assertThat(confirm.get("method").asText()).isEqualTo("confirm");
        assertThat(confirm.get("title").asText()).isEqualTo("T");
        assertThat(confirm.get("message").asText()).isEqualTo("msg");
        assertThat(confirm.has("timeout")).as("null 键省略（pi undefined 不落线）").isFalse();
        var input = wire(capture.blocking.get(1));
        assertThat(input.get("method").asText()).isEqualTo("input");
        assertThat(input.get("placeholder").asText()).isEqualTo("hint");
    }

    @Test
    void editorWireShape() throws Exception {
        var capture = new Capture();
        capture.ui().editor("Edit", "prefilled");
        var node = wire(capture.blocking.get(0));
        assertThat(node.get("method").asText()).isEqualTo("editor");
        assertThat(node.get("title").asText()).isEqualTo("Edit");
        assertThat(node.get("prefill").asText()).isEqualTo("prefilled");
        assertThat(node.has("timeout")).as("pi editor 无 timeout 面").isFalse();
    }

    @Test
    void notifyWireShape() throws Exception {
        var capture = new Capture();
        capture.ui().notify("hello", NotifyType.WARNING);
        var node = wire(capture.fireAndForget.get(0));
        assertThat(node.get("method").asText()).isEqualTo("notify");
        assertThat(node.get("message").asText()).isEqualTo("hello");
        assertThat(node.get("notifyType").asText()).isEqualTo("warning");
    }

    @Test
    void setStatusWireShape() throws Exception {
        var capture = new Capture();
        capture.ui().setStatus("k", "text");
        var node = wire(capture.fireAndForget.get(0));
        assertThat(node.get("method").asText()).isEqualTo("setStatus");
        assertThat(node.get("statusKey").asText()).isEqualTo("k");
        assertThat(node.get("statusText").asText()).isEqualTo("text");
    }

    @Test
    void setWidgetWireShape() throws Exception {
        var capture = new Capture();
        capture.ui().setWidget("w",
            new ExtensionWidgetContent.Lines(List.of("l1", "l2")),
            new ExtensionWidgetOptions("belowEditor"));
        var node = wire(capture.fireAndForget.get(0));
        // pi：{widgetKey, widgetLines, widgetPlacement?}——camel 全名逐字。
        assertThat(node.get("method").asText()).isEqualTo("setWidget");
        assertThat(node.get("widgetKey").asText()).isEqualTo("w");
        assertThat(node.get("widgetLines")).hasSize(2);
        assertThat(node.get("widgetPlacement").asText()).isEqualTo("belowEditor");
    }

    @Test
    void setTitleAndSetEditorTextWireShapes() throws Exception {
        var capture = new Capture();
        capture.ui().setTitle("T");
        capture.ui().setEditorText("abc");
        var title = wire(capture.fireAndForget.get(0));
        assertThat(title.get("method").asText()).isEqualTo("setTitle");
        assertThat(title.get("title").asText()).isEqualTo("T");
        var editorText = wire(capture.fireAndForget.get(1));
        assertThat(editorText.get("method").asText()).isEqualTo("set_editor_text");
        assertThat(editorText.get("text").asText()).isEqualTo("abc");
    }

    @Test
    void responseShapesParse() throws Exception {
        // pi RpcExtensionUIResponse 三形（rpc-types.ts:294-297）。
        var value = JSON.readValue("{\"type\":\"extension_ui_response\",\"id\":\"1\","
            + "\"value\":\"x\"}", RpcExtensionUIResponse.class);
        assertThat(value.value()).isEqualTo("x");
        var confirmed = JSON.readValue("{\"type\":\"extension_ui_response\",\"id\":\"2\","
            + "\"confirmed\":true}", RpcExtensionUIResponse.class);
        assertThat(confirmed.confirmed()).isTrue();
        var cancelled = JSON.readValue("{\"type\":\"extension_ui_response\",\"id\":\"3\","
            + "\"cancelled\":true}", RpcExtensionUIResponse.class);
        assertThat(cancelled.cancelled()).isTrue();
    }
}
