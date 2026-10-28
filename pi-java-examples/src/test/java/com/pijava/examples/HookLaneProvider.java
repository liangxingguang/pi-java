package com.pijava.examples;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * P14 验收的钩消费桩车道（coding-agent {@code ProviderHooksSessionWiringTest} 的
 * HookLaneProvider 同形）——消费 payload/response 钩（生产车道的钩读取面同形），
 * 载荷固定 {@code {model: "hook-model", stream: true}}。
 */
public final class HookLaneProvider implements Provider {

    /** 车道消费到的载荷（before_provider_request 替换臂的观测面）。 */
    static volatile Object wirePayload;

    @Override
    public String name() {
        return "hooklane";
    }

    @Override
    public String displayName() {
        return "hooklane";
    }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        var payloadHook = ProviderHooks.payloadHookOf(options);
        var responseHook = ProviderHooks.responseHookOf(options);
        return apiType.cast(new ChatApi() {
            @Override
            public java.util.concurrent.Flow.Publisher<StreamEvent> stream(StreamRequest request,
                                                                           ApiOptions o) {
                throw new UnsupportedOperationException("测试只走 streamBlocking");
            }

            @Override
            public StreamIterator streamBlocking(StreamRequest request, ApiOptions o) {
                // 插入序＝JS 对象键序（Map.of 不保单序，逐 put 保序）
                var payloadMap = new LinkedHashMap<String, Object>();
                payloadMap.put("model", request.modelId().modelName());
                payloadMap.put("stream", true);
                Object payload = payloadMap;
                if (payloadHook != null) {
                    var next = payloadHook.onPayload(payload, request.model());
                    if (next != null) {
                        payload = next;
                    }
                }
                wirePayload = payload;
                if (responseHook != null) {
                    responseHook.onResponse(200, Map.of("x-resp", "r"), request.model());
                }
                var done = AssistantMessage.empty().withContent(List.of(
                    new ContentBlock.TextContent("ok"))).withStopReason(StopReason.STOP);
                return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.StreamDone(StopReason.STOP, null, done)));
            }

            @Override
            public Message send(StreamRequest request, ApiOptions o) {
                throw new UnsupportedOperationException("测试只走 streamBlocking");
            }
        });
    }

    @Override
    public ModelCatalog builtinModels() {
        var model = new ModelInfo(ModelId.of("hooklane", "hook-model"),
            "hook-model", Set.of(), 200_000, 8192, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), ModelCompat.NONE, "hook-api");
        return new ModelCatalog() {
            @Override
            public List<ModelInfo> listModels() {
                return List.of(model);
            }

            @Override
            public Optional<ModelInfo> find(ModelId<?> id) {
                return model.id().equals(id) ? Optional.of(model) : Optional.empty();
            }

            @Override
            public List<ModelInfo> search(String query) {
                return query != null && query.contains("hook") ? List.of(model) : List.of();
            }
        };
    }
}
