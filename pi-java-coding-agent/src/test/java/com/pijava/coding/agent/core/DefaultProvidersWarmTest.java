package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.ProviderHooks;
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

/**
 * 批 13（docs/24 §7，T7）：暖路径请求面三裁决（裁决③）——
 * ① {@code maxRetries} 强制 0（pi cache-warmer.ts:337 的展开覆盖对位：java 的
 * maxRetries 在 apiOptions 从 settings 现读，需强制通道）；
 * ② 跳过三钩（pi 的 onPayload/onResponse/onProviderStreamEvent 是 Agent 级回调，
 * {@code models.streamSimple} 直调不过）；
 * ③ 保留 transformHeaders（pi 暖请求 options 含烘焙的头变换闭包 ⇒
 * before_provider_headers 钩与头合并照走）。
 */
class DefaultProvidersWarmTest {

    /** 桩 provider：录 createApi 收到的 ApiOptions（{@code DefaultProvidersProviderHooksTest} 同款）。 */
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

    private static ApiOptions driveWarm(ProviderEventBridge bridge, Settings settings) {
        var registry = ProviderRegistry.create();
        var provider = new RecordingProvider();
        registry.register(provider);
        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var streamFn = DefaultProviders.warmStreamFnFor(args, "alpha", registry,
            settings, "session-1", bridge);
        streamFn.stream(ModelId.of("alpha", "alpha-model"),
            Context.of(List.of()), StreamOptions.defaults());
        return provider.optionsSeen.get(0);
    }

    /** ① settings 给 maxRetries=3 ⇒ 暖路径强制 0；主路径不受染（现读链不动）。 */
    @Test
    void warmPathForcesMaxRetriesZero() {
        var settings = new Settings();
        settings.retry = new Settings.Retry(null, null, null, null,
            new Settings.ProviderRetry(5_000L, 3, 10_000L));

        var warm = driveWarm(null, settings);
        assertThat(warm.maxRetries()).isZero();

        // 对照：主路径同 settings 仍读 3（强制通道只长在暖路径上）。
        var registry = ProviderRegistry.create();
        var provider = new RecordingProvider();
        registry.register(provider);
        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        DefaultProviders.streamFnFor(args, "alpha", registry, settings, null)
            .stream(ModelId.of("alpha", "alpha-model"), Context.of(List.of()),
                StreamOptions.defaults());
        assertThat(provider.optionsSeen.get(0).maxRetries()).isEqualTo(3);
    }

    /** ② 桥在场且全钩订阅 ⇒ 暖路径 extra 仍零钩键（三钩不入车道）。 */
    @Test
    void warmPathSkipsRequestHooks() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, ctx) -> null);
        bus.on("ext", ExtensionEventSpec.AFTER_PROVIDER_RESPONSE, (event, ctx) -> null);
        bus.on("ext", ExtensionEventSpec.PROVIDER_STREAM_EVENT, (event, ctx) -> null);
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var options = driveWarm(bridge, new Settings());

        assertThat(ProviderHooks.payloadHookOf(options)).isNull();
        assertThat(ProviderHooks.responseHookOf(options)).isNull();
        assertThat(ProviderHooks.streamEventHookOf(options)).isNull();
        assertThat(options.extra()).doesNotContainKeys("onPayload", "onResponse",
            "onProviderStreamEvent");
    }

    /** ③ 头变换保留：订阅 before_provider_headers ⇒ 暖请求头改写生效。 */
    @Test
    @SuppressWarnings("unchecked")
    void warmPathKeepsHeaderTransform() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.BEFORE_PROVIDER_HEADERS, (event, ctx) -> {
            event.headers().put("x-hook", "yes");
            return null;
        });
        var bridge = new ProviderEventBridge();
        bridge.install(bus, null);

        var options = driveWarm(bridge, new Settings());

        var headers = (Map<String, String>) options.extra().get("headers");
        assertThat(headers).containsEntry("x-hook", "yes");
    }

    /** 桥 null ⇒ 暖路径与主路径同：extra 无 headers 键（存量行为不变）。 */
    @Test
    void nullBridgeLeavesWarmOptionsUntouched() {
        var options = driveWarm(null, new Settings());
        assertThat(options.extra()).doesNotContainKey("headers");
        assertThat(options.maxRetries()).isZero();
    }
}
