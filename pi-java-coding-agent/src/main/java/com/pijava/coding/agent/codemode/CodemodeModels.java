package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.provider.Provider;
import com.pijava.codemode.api.ScriptTool;
import com.pijava.coding.agent.extension.ExtensionContext;

/**
 * Minimal {@code models} namespace: read-only catalog query only.
 *
 * <p>pi exposes {@code getModelsOfType}/{@code getAvailableOfType}/
 * {@code getModelOfType} plus {@code classify}/{@code generateImages}
 * ({@code execute.ts:812-845}). pi-java has no classifier model type and keeps
 * image generation out of the script surface, so B6 ships only
 * {@code models.list(provider?)}: every model of every registered provider with
 * credential-bearing fields dropped. The name is pi-java specific; it is not an
 * upstream global.</p>
 */
final class CodemodeModels {
    private CodemodeModels() {
    }

    /** Builds the {@code models.list} global for one execution. */
    static Map<String, ScriptTool> globals(ExtensionContext ctx) {
        return Map.of("models.list", args -> list(ctx, args));
    }

    private static List<Map<String, Object>> list(ExtensionContext ctx, Object args) {
        String providerFilter = null;
        if (args instanceof Object[] call && call.length > 0 && call[0] != null) {
            if (!(call[0] instanceof String name)) {
                throw new IllegalArgumentException("models.list() expects an optional provider string");
            }
            providerFilter = name;
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (Provider provider : ctx.providers().listAll()) {
            if (providerFilter != null && !providerFilter.equals(provider.name())) {
                continue;
            }
            for (ModelInfo model : provider.getModels()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("provider", provider.name());
                entry.put("id", model.id().modelName());
                entry.put("name", model.displayName());
                entry.put("contextWindow", model.maxInputTokens());
                entry.put("maxTokens", model.maxOutputTokens());
                entry.put("deprecated", model.deprecated());
                entry.put("capabilities",
                    model.capabilities().stream().map(CodemodeModels::capabilityName).sorted().toList());
                models.add(entry);
            }
        }
        return models;
    }

    private static String capabilityName(ModelCapability capability) {
        if (capability == ModelCapability.TEXT) {
            return "text";
        }
        if (capability == ModelCapability.IMAGE_INPUT) {
            return "image_input";
        }
        if (capability == ModelCapability.IMAGE_OUTPUT) {
            return "image_output";
        }
        if (capability == ModelCapability.TOOL_USE) {
            return "tool_use";
        }
        if (capability == ModelCapability.THINKING) {
            return "thinking";
        }
        if (capability == ModelCapability.STREAMING) {
            return "streaming";
        }
        if (capability == ModelCapability.PROMPT_CACHING) {
            return "prompt_caching";
        }
        if (capability == ModelCapability.COMPUTER_USE) {
            return "computer_use";
        }
        return capability.getClass().getSimpleName();
    }
}
