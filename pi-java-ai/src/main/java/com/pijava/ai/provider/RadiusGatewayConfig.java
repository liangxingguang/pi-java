package com.pijava.ai.provider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/** Validated Radius /v1/config response mapped to provider models. */
public record RadiusGatewayConfig(String baseUrl, List<ModelInfo> models) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Defensively copy the model list. */
    public RadiusGatewayConfig {
        models = List.copyOf(models);
    }

    /** Parse and validate a gateway response; invalid models are filtered individually. */
    public static RadiusGatewayConfig parse(String providerId, String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        if (root == null || !root.isObject() || !root.path("baseUrl").isTextual()
                || !root.path("models").isArray()) {
            throw new IOException("Invalid Radius config");
        }
        var baseUrl = root.path("baseUrl").textValue();
        var models = new ArrayList<ModelInfo>();
        for (var node : root.path("models")) {
            if (validModel(node)) {
                models.add(toModel(providerId, baseUrl, node));
            }
        }
        return new RadiusGatewayConfig(baseUrl, models);
    }

    private static boolean validModel(JsonNode model) {
        return model != null && model.isObject()
            && model.path("id").isTextual()
            && model.path("name").isTextual()
            && model.path("reasoning").isBoolean()
            && model.path("input").isArray()
            && model.path("cost").isObject()
            && model.path("contextWindow").isNumber()
            && model.path("maxTokens").isNumber();
    }

    private static ModelInfo toModel(String providerId, String baseUrl, JsonNode node) {
        var capabilities = new java.util.LinkedHashSet<ModelCapability>();
        capabilities.add(ModelCapability.TEXT);
        capabilities.add(ModelCapability.TOOL_USE);
        capabilities.add(ModelCapability.STREAMING);
        if (node.path("reasoning").booleanValue()) {
            capabilities.add(ModelCapability.THINKING);
        }
        for (var input : node.path("input")) {
            if ("image".equals(input.textValue())) {
                capabilities.add(ModelCapability.IMAGE_INPUT);
            }
        }
        var cost = node.path("cost");
        var pricing = new PricingInfo(cost.path("input").asDouble(-1), cost.path("output").asDouble(-1),
            cost.path("cacheRead").asDouble(-1), cost.path("cacheWrite").asDouble(-1), List.of());
        return new ModelInfo(
            ModelId.of(providerId, node.path("id").textValue()),
            node.path("name").textValue(), capabilities,
            node.path("contextWindow").asInt(), node.path("maxTokens").asInt(), false,
            pricing, thinkingMap(node.path("thinkingLevelMap")), Map.of(), Map.of(), ModelCompat.NONE,
            "pi-messages", baseUrl);
    }

    private static ThinkingLevelMap thinkingMap(JsonNode node) {
        if (!node.isObject()) {
            return ThinkingLevelMap.empty();
        }
        Map<ModelThinkingLevel, Optional<String>> entries = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> ModelThinkingLevel.parse(entry.getKey())
            .ifPresent(level -> entries.put(level,
                entry.getValue().isTextual() ? Optional.of(entry.getValue().textValue()) : Optional.empty())));
        return ThinkingLevelMap.of(entries);
    }
}
