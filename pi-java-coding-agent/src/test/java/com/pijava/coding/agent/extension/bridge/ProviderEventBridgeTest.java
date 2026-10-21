package com.pijava.coding.agent.extension.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionError;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AfterProviderResponseEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ProviderStreamEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/17 §6：{@link ProviderEventBridge} —— pi runner.ts:1360-1420（两件专用 emit）
 * 与 sdk.ts:340-410（四闭包）的逐字情形：hasHandlers 门／链式替换／异常隔离／
 * 原位改写＋null 删除／观察型五键载荷／未安装空跳。
 */
class ProviderEventBridgeTest {

    private static final ModelInfo MODEL =
        ModelInfo.minimal(ModelId.of("anthropic", "claude-sonnet-5"));

    /** ① hasHandlers 门：无订阅 ⇒ 四方法全空走（pi sdk.ts:361/384/389/401 逐字）。 */
    @Test
    void noSubscriptionsMeansPassThrough() {
        var bus = new ExtensionEventBus();
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        assertThat(bridge.onPayload(Map.of("a", 1), MODEL)).isNull();
        var headers = new LinkedHashMap<String, String>();
        headers.put("x-a", "1");
        assertThat(bridge.transformHeaders(headers)).isSameAs(headers);
        // 观察两法不抛即过（无订阅零派发）。
        bridge.onResponse(200, Map.of(), MODEL);
        bridge.onStreamEvent(Map.of("raw", true), MODEL);
    }

    /** ② 未 install（--no-extensions 臂）⇒ 有订阅面也全跳（pi 空 runner 同效）。 */
    @Test
    void notInstalledSkipsEverything() {
        var bridge = new ProviderEventBridge();
        assertThat(bridge.onPayload(Map.of("a", 1), MODEL)).isNull();
        var headers = new LinkedHashMap<String, String>();
        headers.put("x-a", "1");
        assertThat(bridge.transformHeaders(headers)).isSameAs(headers);
    }

    /** ③ payload 替换链：后处理器见到前者的替换值（pi runner.ts:1365-1378 的序）。 */
    @Test
    void payloadChainSeesPreviousReplacements() {
        var bus = new ExtensionEventBus();
        var seen = new ArrayList<Object>();
        bus.on("ext-a", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) -> {
            seen.add(event.payload());
            return Map.of("step", 1);
        });
        bus.on("ext-b", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) -> {
            seen.add(event.payload());
            return null; // 观察臂：不发言 ⇒ 链值不丢
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var result = bridge.onPayload(Map.of("step", 0), MODEL);

        assertThat(seen).containsExactly(Map.of("step", 0), Map.of("step", 1));
        assertThat(result).isEqualTo(Map.of("step", 1));
    }

    /** ④ 无替换 ⇒ null（调用方保留原载荷并跳过同型守卫）。 */
    @Test
    void noReplacementReturnsNull() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) -> null);
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        assertThat(bridge.onPayload(Map.of("a", 1), MODEL)).isNull();
    }

    /** ⑤ 异常隔离：抛错处理器 emitError 一次，后续处理器照常（pi :1380-1387）。 */
    @Test
    void throwingHandlerIsIsolatedAndChainContinues() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        bus.on("ext-throw", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) -> {
            throw new RuntimeException("boom");
        });
        bus.on("ext-good", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) ->
            Map.of("applied", true));
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var result = bridge.onPayload(Map.of("a", 1), MODEL);

        assertThat(result).isEqualTo(Map.of("applied", true));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).event()).isEqualTo("before_provider_request");
        assertThat(errors.get(0).message()).contains("boom");
        assertThat(errors.get(0).extensionName()).isEqualTo("ext-throw");
    }

    /** ⑥ headers 原位改＋null 删除＋返回值忽略（pi :1395-1419 逐字）。 */
    @Test
    void headersMutateInPlaceAndNullDeletes() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_HEADERS, (event, ctx) -> {
            event.headers().put("x-trace", "t-1");
            event.headers().put("x-drop-me", null);
            return null; // pi：返回值被忽略（观察型）
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var headers = new LinkedHashMap<String, String>();
        headers.put("x-keep", "k");
        headers.put("x-drop-me", "v");
        var result = bridge.transformHeaders(headers);

        assertThat(result).containsEntry("x-keep", "k")
            .containsEntry("x-trace", "t-1")
            .doesNotContainKey("x-drop-me");
    }

    /** ⑦ after_provider_response／provider_stream_event 载荷逐字段（pi 五键）。 */
    @Test
    void observationEventsCarryVerbatimPayloads() {
        var bus = new ExtensionEventBus();
        var responses = new ArrayList<AfterProviderResponseEvent>();
        var streams = new ArrayList<ProviderStreamEvent>();
        bus.on("ext", ExtensionEventSpec.AFTER_PROVIDER_RESPONSE, (event, ctx) -> {
            responses.add(event);
            return null;
        });
        bus.on("ext", ExtensionEventSpec.PROVIDER_STREAM_EVENT, (event, ctx) -> {
            streams.add(event);
            return null;
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        bridge.onResponse(200, Map.of("x-a", "1"), MODEL);
        bridge.onStreamEvent(Map.of("chunk", 1), MODEL);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo(200);
        assertThat(responses.get(0).headers()).containsEntry("x-a", "1");
        assertThat(streams).hasSize(1);
        assertThat(streams.get(0).provider()).isEqualTo("anthropic");
        assertThat(streams.get(0).model()).isEqualTo("claude-sonnet-5");
        assertThat(streams.get(0).data()).isEqualTo(Map.of("chunk", 1));
        // api：ModelInfo.minimal 无 api 字段 ⇒ null（java 数据面缺席，pi 必填字段无对位）。
        assertThat(streams.get(0).api()).isNull();
    }

    /** ⑧ 头变换缝：extra 合并集过桥写回；无订阅 ⇒ options 原样（pi models.ts:866-871）。 */
    @Test
    void applyHeaderTransformRoundTripsThroughExtra() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_HEADERS, (event, ctx) -> {
            event.headers().put("x-hook", "yes");
            return null;
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var options = new com.pijava.ai.api.ApiOptions("http://x", "k",
            java.time.Duration.ofSeconds(5), 0,
            Map.of("headers", Map.of("x-model", "m")));

        var transformed = bridge.applyHeaderTransform(options);

        @SuppressWarnings("unchecked")
        var headers = (Map<String, String>) transformed.extra().get("headers");
        assertThat(headers).containsEntry("x-model", "m").containsEntry("x-hook", "yes");
        // pi 测试对位：transformHeaders 钩本体不出现在 options 里。
        assertThat(transformed.extra()).doesNotContainKey("transformHeaders");

        // 无订阅 ⇒ 原样返回（同一个对象）。
        var quiet = new ProviderEventBridge();
        quiet.install(new ExtensionEventBus(), null);
        assertThat(quiet.applyHeaderTransform(options)).isSameAs(options);
    }

    /** ⑨ 三钩键入 extra 且类型正确（车道构造期的读取面）。 */
    @Test
    void withRequestHooksInjectsThreeKeys() {
        var bridge = new ProviderEventBridge();
        var options = new com.pijava.ai.api.ApiOptions("http://x", "k",
            java.time.Duration.ofSeconds(5), 0, Map.of());

        var withHooks = bridge.withRequestHooks(options);

        assertThat(withHooks.extra()).containsKeys("onPayload", "onResponse",
            "onProviderStreamEvent");
        assertThat(com.pijava.ai.api.ProviderHooks.payloadHookOf(withHooks)).isNotNull();
        assertThat(com.pijava.ai.api.ProviderHooks.responseHookOf(withHooks)).isNotNull();
        assertThat(com.pijava.ai.api.ProviderHooks.streamEventHookOf(withHooks)).isNotNull();
    }

    /** ⑩ 运行期订阅生效（hasHandlers 调用时现查，pi 逐字）。 */
    @Test
    void lateSubscriptionTakesEffect() {
        var bus = new ExtensionEventBus();
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);
        assertThat(bridge.onPayload(Map.of("a", 1), MODEL)).isNull();

        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) ->
            Map.of("late", true));

        assertThat(bridge.onPayload(Map.of("a", 1), MODEL)).isEqualTo(Map.of("late", true));
    }

    /** ⑪ headers 处理器抛错被隔离、改写仍生效（pi extensions-runner.test.ts:1405-1435）。 */
    @Test
    void throwingHeaderHandlerIsIsolated() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        bus.on("ext-throw", ExtensionEventSpec.BEFORE_PROVIDER_HEADERS, (event, ctx) -> {
            throw new RuntimeException("header handler boom");
        });
        bus.on("ext-good", ExtensionEventSpec.BEFORE_PROVIDER_HEADERS, (event, ctx) -> {
            event.headers().put("x-good", "yes");
            return null;
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", "x");
        var result = bridge.transformHeaders(headers);

        assertThat(result).containsEntry("x-good", "yes").containsEntry("User-Agent", "x");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).event()).isEqualTo("before_provider_headers");
        assertThat(errors.get(0).message()).contains("header handler boom");
    }

    /** ⑫ 观察型处理器见到 List<Object> 形原始 data 原样（只读约定由文档约束）。 */
    @Test
    void streamEventPassesDataThroughUntouched() {
        var bus = new ExtensionEventBus();
        var seen = new ArrayList<Object>();
        bus.on("ext", ExtensionEventSpec.PROVIDER_STREAM_EVENT, (event, ctx) -> {
            seen.add(event.data());
            return null;
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);
        var raw = new Object();

        bridge.onStreamEvent(raw, MODEL);

        assertThat(seen).containsExactly(raw);
    }
}
