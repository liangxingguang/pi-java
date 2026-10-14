package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawContentBlockStartEvent;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.AllowedFallbackModel;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * 一条 anthropic 流的<b>服务端响应面</b>可变状态（批 5 B148，
 * 对齐 pi {@code anthropic-messages.ts:660-690/815-817/871-885}）：
 *
 * <ul>
 *   <li>message_start 观测到的服务模型与请求 id 不同时，{@code responseModel} 落消息，
 *       且命中 {@code compat.allowedFallbackModels} 同 provider 同 model 条目时
 *       切换成本归属的定价表（pi 的 {@code usageModel}）；</li>
 *   <li>{@code input_transformations}（message_start 一份、message_delta 顶层可再带一份，
 *       <b>数组即整体替换</b>）在成功收尾时投影成
 *       {@code anthropic_input_transformations} 诊断。</li>
 * </ul>
 *
 * <p>单线程流式使用（mapEvent 在 SDK forEach 线程上顺序调用）。</p>
 */
final class AnthropicResponseExtras {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 服务端回退命中的定价；null ⇒ 沿用请求模型定价（pi {@code usageModel = model}）。 */
    private PricingInfo servingPricing;

    /** pi 的局部 {@code inputTransformations}；null＝从未观测到数组（含空数组）。 */
    private List<Transformation> inputTransformations;

    PricingInfo servingPricing() {
        return servingPricing;
    }

    /**
     * B148：message_start 的两件事（pi {@code :666-678}）：服务模型与请求不同 ⇒
     * responseModel 落 builder 且按许可回退条目切定价；{@code input_transformations}
     * 是数组即记下（delta 顶层再带则整体替换）。{@code request} 无模型元数据时整段跳过。
     * 调用方须在此后、首次 usage 计价前 {@code usePricing(servingPricing())}。
     */
    void noteStart(StreamPartialBuilder builder, StreamRequest request,
                   List<AllowedFallbackModel> fallbacks, Message startMessage) {
        if (request.model() == null) {
            return;
        }
        String wireModel = startMessage.model().asString();
        String requested = request.modelId().modelName();
        if (!wireModel.equals(requested)) {
            builder.noteResponseModel(wireModel);
            noteServingModel(request.modelId().provider(), wireModel, fallbacks);
        }
        JsonValue startTransformations =
            startMessage._additionalProperties().get("input_transformations");
        if (startTransformations != null) {
            replaceTransformations(startTransformations);
        }
    }

    /**
     * B148（pi {@code anthropic-messages.ts:690-691}）：识别
     * {@code content_block_start} 的 {@code "fallback"} 未知块。
     *
     * <p>SDK 2.66.0 的联合没有 fallback typed 变体（探针
     * {@code AnthropicFallbackBlockProbeTest} settle），经 {@code _json()} 逃生槽
     * 读原文 type；已知块的 {@code _json()} 同样有值，必须按字面量判别。</p>
     */
    static boolean isFallbackBlock(RawContentBlockStartEvent.ContentBlock block) {
        // ⚠️ 2.66.0 制品里 JsonValue 继承裸类型 JsonField<T>，继承的 asObject()/
        // asString() 在调用点被擦成 Object 口径，故用 JsonValue 自声明的 convert(Class)。
        JsonNode raw = block._json()
            .map(v -> v.convert(JsonNode.class))
            .orElse(null);
        return raw != null && raw.isObject()
            && "fallback".equals(raw.path("type").asText(null));
    }

    /**
     * pi {@code :671-677}：响应模型与请求不同后，在许可回退表里找同 provider
     * 同 model 的条目，命中才用条目的 {@code cost} 计价（找不到维持请求模型表）。
     *
     * @param provider   请求模型的 provider（pi {@code model.provider}）
     * @param wireModel  message_start 上服务端报的模型 id
     * @param fallbacks  compat 的 allowedFallbackModels（无表 ⇒ 空列表）
     */
    void noteServingModel(String provider, String wireModel,
                          List<AllowedFallbackModel> fallbacks) {
        if (fallbacks == null || provider == null || wireModel == null) {
            return;
        }
        for (var fallback : fallbacks) {
            if (provider.equals(fallback.provider()) && wireModel.equals(fallback.model())) {
                this.servingPricing = fallback.cost();
                return;
            }
        }
    }

    /**
     * pi {@code :667-668/:816-817}：原始值是数组就<b>整体替换</b>（start/delta 两处同法）；
     * 非数组/缺席保持当前列表。投影成 {@code {type?, path?, reason?}} 三字符串键。
     */
    void replaceTransformations(JsonValue raw) {
        if (raw == null) {
            return;
        }
        // 2.66.0 制品里 JsonValue 继承裸类型 JsonField<T>，asObject() 调用点被擦，
        // 用 JsonValue 自己声明的 convert(Class) 取 Jackson 树。
        JsonNode node = raw.convert(JsonNode.class);
        if (node == null || !node.isArray()) {
            return;
        }
        var projected = new ArrayList<Transformation>(node.size());
        for (JsonNode element : node) {
            if (element == null || !element.isObject()) {
                continue;
            }
            projected.add(new Transformation(
                textOrNull(element, "type"),
                textOrNull(element, "path"),
                textOrNull(element, "reason")));
        }
        this.inputTransformations = List.copyOf(projected);
    }

    boolean hasTransformations() {
        return inputTransformations != null && !inputTransformations.isEmpty();
    }

    /** pi {@code :874-882} 的 details：{@code {transformations: [{type?,path?,reason?}]}}。 */
    ObjectNode transformationsDetails() {
        var array = JSON.createArrayNode();
        for (var t : inputTransformations) {
            var item = array.addObject();
            if (t.type() != null) {
                item.put("type", t.type());
            }
            if (t.path() != null) {
                item.put("path", t.path());
            }
            if (t.reason() != null) {
                item.put("reason", t.reason());
            }
        }
        var details = JSON.createObjectNode();
        details.set("transformations", array);
        return details;
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** 一条 input transformation 的三键投影（pi {@code :876-880}）。 */
    record Transformation(String type, String path, String reason) {}
}
