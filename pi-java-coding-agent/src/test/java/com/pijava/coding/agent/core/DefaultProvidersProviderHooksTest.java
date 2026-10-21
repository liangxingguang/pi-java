package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.bridge.ProviderEventBridge;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/17 §6（T3）：{@link DefaultProviders} 的桥注入 —— pi models.ts:866-871
 * （合并头集过 transformHeaders 写回、钩本体剥离）与 sdk.ts:432-434（三钩下车道）
 * 在 {@code createApi} 观测面上的对账。
 */
class DefaultProvidersProviderHooksTest {

    /** 桩 provider：录 createApi 收到的 ApiOptions；ChatApi 满足接口即可。 */
    private static final class RecordingProvider implements Provider {
        private final List<ApiOptions> optionsSeen = new ArrayList<>();

        @Override public String name() { return "alpha"; }

        @Override public String displayName() { return "alpha"; }

        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override public <T extends ProviderApi> T createApi(Class<T> apiType,
                                                             ApiOptions options) {
            optionsSeen.add(options);
            return apiType.cast(new ChatApi() {
                @Override public Flow.Publisher<StreamEvent> stream(StreamRequest request,
                                                                    ApiOptions o) {
                    throw new UnsupportedOperationException("只观测 createApi 入参");
                }

                @Override public StreamIterator streamBlocking(StreamRequest request,
                                                               ApiOptions o) {
                    return StreamIterator.from(List.of());
                }

                @Override public Message send(StreamRequest request, ApiOptions o) {
                    throw new UnsupportedOperationException("只观测 createApi 入参");
                }
            });
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }
    }

    private static ApiOptions drive(ProviderRegistry registry,
                                    ProviderEventBridge bridge) {
        var provider = (RecordingProvider) registry.get("alpha").orElseThrow();
        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var streamFn = DefaultProviders.streamFnFor(args, "alpha", registry,
            new Settings(), null, bridge);
        streamFn.stream(ModelId.of("alpha", "alpha-model"),
            Context.of(List.of()), StreamOptions.defaults());
        return provider.optionsSeen.get(0);
    }

    private static ProviderRegistry registry() {
        var registry = ProviderRegistry.create();
        registry.register(new RecordingProvider());
        return registry;
    }

    /** ① 桥在场：合并头集先过桥（扩展新头落进车道 options），三钩键入 extra，
     *  {@code transformHeaders} 键不转发（pi 测试 "without forwarding the transform" 对位）。 */
    @Test
    @SuppressWarnings("unchecked")
    void bridgeInjectsHooksAndTransformsHeaders() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_HEADERS, (event, ctx) -> {
            event.headers().put("x-hook", "yes");
            return null;
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var options = drive(registry(), bridge);

        assertThat(options.extra()).containsKeys("onPayload", "onResponse",
            "onProviderStreamEvent");
        assertThat(options.extra()).doesNotContainKey("transformHeaders");
        assertThat(ProviderHooks.payloadHookOf(options)).isNotNull();
        assertThat(ProviderHooks.responseHookOf(options)).isNotNull();
        assertThat(ProviderHooks.streamEventHookOf(options)).isNotNull();
        var headers = (Map<String, String>) options.extra().get("headers");
        assertThat(headers).containsEntry("x-hook", "yes");
    }

    /** ② 桥 null（旧签名臂）⇒ extra 零钩键、零 headers 键（存量行为不变）。 */
    @Test
    void nullBridgeLeavesOptionsUntouched() {
        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var registry = registry();
        var provider = (RecordingProvider) registry.get("alpha").orElseThrow();
        var streamFn = DefaultProviders.streamFnFor(args, "alpha", registry, new Settings());

        streamFn.stream(ModelId.of("alpha", "alpha-model"),
            Context.of(List.of()), StreamOptions.defaults());

        var options = provider.optionsSeen.get(0);
        assertThat(options.extra()).doesNotContainKeys("onPayload", "onResponse",
            "onProviderStreamEvent", "transformHeaders", "headers");
    }

    /** ③ 钩端到端：createApi 入参里的 payload 钩穿透到总线（替换值回到调用方）。 */
    @Test
    void injectedPayloadHookReachesTheBus() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) ->
            Map.of("replaced", true));
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var options = drive(registry(), bridge);

        var hook = ProviderHooks.payloadHookOf(options);
        var replaced = hook.onPayload(Map.of("original", 1), null);
        assertThat(replaced).isEqualTo(Map.of("replaced", true));
    }

    /** ④ 未安装（--no-extensions 臂）⇒ 键照注但全空跳（pi 空 runner 同效）。 */
    @Test
    void uninstalledBridgeHooksAreInert() {
        var options = drive(registry(), new ProviderEventBridge());

        assertThat(ProviderHooks.payloadHookOf(options).onPayload(Map.of("a", 1), null))
            .isNull();
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("x-a", "1");
        // transformHeaders 无订阅 ⇒ options 原样（headers 键不出现）。
        assertThat(options.extra()).doesNotContainKey("headers");
    }
}
