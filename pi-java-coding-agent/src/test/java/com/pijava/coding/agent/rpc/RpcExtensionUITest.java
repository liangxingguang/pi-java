package com.pijava.coding.agent.rpc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.ExtensionUiDialogOptions;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T3，docs/28 §7-4）：RPC 臂 typed 实现——pi {@code rpc-mode.ts:136-260}
 * 逐字对拍。线格式参数键平铺（pi 的 spread）＋应答三形（cancelled/value/confirmed）
 * ＋signal 预检/中途 abort＋本地 timeout＋setWidget 工厂臂静默忽略。
 */
class RpcExtensionUITest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 假通道：记录请求、按脚本应答（可阻塞）。 */
    private static final class FakeChannel {
        final List<RpcExtensionUIRequest> requests = new CopyOnWriteArrayList<>();
        final List<RpcExtensionUIRequest> fired = new CopyOnWriteArrayList<>();
        volatile RpcExtensionUIResponse next;
        volatile CountDownLatch blockOn;   // 非 null ⇒ 通道阻塞到 latch 释放

        RpcExtensionUIResponse apply(RpcExtensionUIRequest request) {
            requests.add(request);
            var gate = blockOn;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return next;
        }

        void fire(RpcExtensionUIRequest request) {
            fired.add(request);
        }

        RpcExtensionUI ui() {
            return new RpcExtensionUI(this::apply, this::fire);
        }
    }

    /** 序列化后的平铺 JSON（pi 线格式断言面）。 */
    private static com.fasterxml.jackson.databind.JsonNode wire(RpcExtensionUIRequest request)
            throws Exception {
        return JSON.readTree(JSON.writeValueAsString(request));
    }

    // ── 应答三形＋参数键（pi :136-158 逐字）──

    @Test
    void selectSendsVerbatimKeysAndReadsValue() throws Exception {
        var channel = new FakeChannel();
        var ui = channel.ui();

        channel.next = RpcExtensionUIResponse.value("x", "opt-b");
        var result = ui.select("Pick one", List.of("opt-a", "opt-b"),
            new ExtensionUiDialogOptions(null, 30_000L));

        assertThat(result).isEqualTo("opt-b");
        var wire = wire(channel.requests.get(0));
        assertThat(wire.get("type").asText()).isEqualTo("extension_ui_request");
        assertThat(wire.get("method").asText()).isEqualTo("select");
        assertThat(wire.get("title").asText()).isEqualTo("Pick one");
        assertThat(wire.get("options")).isNotNull();
        assertThat(wire.get("options").get(1).asText()).isEqualTo("opt-b");
        // pi：timeout 写进请求（宿主显示倒计时）。
        assertThat(wire.get("timeout").asLong()).isEqualTo(30_000L);
        assertThat(wire.has("params")).as("参数平铺（pi spread），无嵌套 params 键").isFalse();
    }

    @Test
    void cancelledResponseYieldsDefaults() {
        var channel = new FakeChannel();
        var ui = channel.ui();

        channel.next = new RpcExtensionUIResponse("x", null, null, true);
        assertThat(ui.select("t", List.of("a"), null)).isNull();
        assertThat(ui.confirm("t", "m", null)).isFalse();
        assertThat(ui.input("t", null, null)).isNull();
    }

    @Test
    void confirmReadsConfirmedFlag() {
        var channel = new FakeChannel();
        var ui = channel.ui();

        channel.next = RpcExtensionUIResponse.confirm("x", true);
        assertThat(ui.confirm("Sure?", "really", null)).isTrue();
        assertThat(channel.requests.get(0).params())
            .containsEntry("title", "Sure?").containsEntry("message", "really");

        channel.next = RpcExtensionUIResponse.confirm("x", false);
        assertThat(ui.confirm("Sure?", "really", null)).isFalse();
    }

    @Test
    void editorHasNoSignalOrTimeoutFace() {
        var channel = new FakeChannel();
        var ui = channel.ui();

        channel.next = RpcExtensionUIResponse.value("x", "body text");
        assertThat(ui.editor("Compose", "prefilled")).isEqualTo("body text");
        // pi :239-260：editor 无 timeout/signal 面（键只有 title/prefill）。
        assertThat(channel.requests.get(0).params())
            .containsEntry("title", "Compose").containsEntry("prefill", "prefilled")
            .doesNotContainKey("timeout");

        channel.next = new RpcExtensionUIResponse("x", null, null, true);
        assertThat(ui.editor("Compose", null)).isNull();
    }

    // ── fire-and-forget 五件（pi :160-236 逐字键名）──

    @Test
    void fireAndForgetMethodsNeverWaitForResponse() {
        var channel = new FakeChannel();
        var ui = channel.ui();

        ui.notify("hello", NotifyType.WARNING);
        ui.setStatus("myKey", "status text");
        ui.setTitle("window title");
        ui.setEditorText("editor content");
        ui.pasteToEditor("pasted");

        assertThat(channel.requests).isEmpty();   // 无一等应答
        assertThat(channel.fired).hasSize(5);
        assertThat(channel.fired.get(0).method()).isEqualTo("notify");
        assertThat(channel.fired.get(0).params())
            .containsEntry("message", "hello").containsEntry("notifyType", "warning");
        assertThat(channel.fired.get(1).method()).isEqualTo("setStatus");
        assertThat(channel.fired.get(1).params())
            .containsEntry("statusKey", "myKey").containsEntry("statusText", "status text");
        assertThat(channel.fired.get(2).method()).isEqualTo("setTitle");
        assertThat(channel.fired.get(2).params()).containsEntry("title", "window title");
        assertThat(channel.fired.get(3).method()).isEqualTo("set_editor_text");
        assertThat(channel.fired.get(3).params()).containsEntry("text", "editor content");
        // pi :225-228：pasteToEditor 回落 set_editor_text。
        assertThat(channel.fired.get(4).method()).isEqualTo("set_editor_text");
        assertThat(channel.fired.get(4).params()).containsEntry("text", "pasted");
    }

    @Test
    void notifyDefaultsToOmittedType() {
        var channel = new FakeChannel();
        channel.ui().notify("plain", null);
        assertThat(channel.fired.get(0).params())
            .containsEntry("message", "plain").doesNotContainKey("notifyType");
    }

    // ── setWidget（pi :201-211）──

    @Test
    void setWidgetSendsLinesArmAndSilentlyIgnoresFactoryArm() {
        var channel = new FakeChannel();
        var ui = channel.ui();

        ui.setWidget("w1", new ExtensionWidgetContent.Lines(List.of("l1", "l2")),
            new ExtensionWidgetOptions(ExtensionWidgetOptions.BELOW_EDITOR));
        assertThat(channel.fired).hasSize(1);
        assertThat(channel.fired.get(0).method()).isEqualTo("setWidget");
        assertThat(channel.fired.get(0).params())
            .containsEntry("widgetKey", "w1")
            .containsEntry("widgetLines", List.of("l1", "l2"))
            .containsEntry("widgetPlacement", "belowEditor");

        // 组件工厂臂静默忽略（pi 注释逐字）。
        ui.setWidget("w2", new ExtensionWidgetContent.Factory(env -> null), null);
        assertThat(channel.fired).hasSize(1);

        // null（清槽）上线，widgetLines 键省略（pi undefined ⇒ JSON.stringify 丢键）。
        ui.setWidget("w1", null, null);
        assertThat(channel.fired).hasSize(2);
        assertThat(channel.fired.get(1).params())
            .containsEntry("widgetKey", "w1")
            .doesNotContainKey("widgetLines")
            .doesNotContainKey("widgetPlacement");
    }

    // ── 空操作臂（pi 逐字）──

    @Test
    void unsupportedMembersMatchPiRpcArm() {
        var ui = new FakeChannel().ui();
        ui.setFooter(null);
        ui.setHeader(null);
        assertThat(ui.<Object>custom((env, keys, done) -> null, null)).isNull();
        assertThat(ui.getEditorText()).isEmpty();
        ui.onTerminalInput(data -> null).run();
    }

    // ── signal / timeout（createDialogPromise :91-121＋裁决⑦）──

    @Test
    void preAbortedSignalShortCircuitsBeforeSending() {
        var channel = new FakeChannel();
        var ui = channel.ui();
        var signal = AbortSignal.create();
        signal.abort();

        var result = ui.select("t", List.of("a"), new ExtensionUiDialogOptions(signal, null));

        assertThat(result).isNull();
        assertThat(channel.requests).as("预检直返，请求不上线").isEmpty();
    }

    @Test
    void midWaitAbortUnblocksWithDefaultValue() {
        var channel = new FakeChannel();
        channel.blockOn = new CountDownLatch(1);   // 通道永不应答（测试内）
        var ui = channel.ui();
        var signal = AbortSignal.create();

        var outcome = new java.util.concurrent.atomic.AtomicReference<String>();
        var caller = Thread.startVirtualThread(() ->
            outcome.set(ui.input("name", null, new ExtensionUiDialogOptions(signal, null))));
        // 等请求上线后 abort（pi onAbort：cleanup + resolve(default)）。
        while (channel.requests.isEmpty()) {
            Thread.yield();
        }
        signal.abort();
        try {
            caller.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(outcome.get()).isNull();
        assertThat(caller.isAlive()).isFalse();
    }

    @Test
    void localTimeoutReturnsDefaultValue() {
        var channel = new FakeChannel();
        channel.blockOn = new CountDownLatch(1);
        var ui = channel.ui();

        long start = System.nanoTime();
        var result = ui.confirm("t", "m", new ExtensionUiDialogOptions(null, 300L));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isFalse();
        assertThat(elapsedMs).as("本地 timeout 到点解除").isLessThan(5_000);
        // timeout 已写进请求（pi :105-109）。
        assertThat(channel.requests.get(0).params()).containsEntry("timeout", 300L);
    }

    @Test
    void requestPassthroughHitsChannel() {
        var channel = new FakeChannel();
        channel.next = RpcExtensionUIResponse.value("r1", "v");
        var response = channel.ui().request(
            new RpcExtensionUIRequest("r1", "input", Map.of("title", "t")));
        assertThat(response.value()).isEqualTo("v");
        assertThat(channel.requests.get(0).id()).isEqualTo("r1");
    }
}
