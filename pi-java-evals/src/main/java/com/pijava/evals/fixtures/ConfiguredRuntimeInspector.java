package com.pijava.evals.fixtures;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.stream.StreamEvent;

/**
 * 批 32（docs/44 §8.3/§8.4）：配置后运行时观测器 —— pi
 * {@code configured-runtime.ts} 对位。
 *
 * <p>两个观测点：{@code models.json} 新增模型的字段（含既有模型保留），以及按配置
 * 构造的 {@link ChatApi} 的探针结果（文本 / 停因 / token 数）。两者都只读文件，
 * 不依赖活会话 —— 宿主 eval 结束时会话已关闭、隔离 home 已清理。</p>
 */
public final class ConfiguredRuntimeInspector {

    /** 探针的输出上限（pi 的 32 token 探针）。 */
    public static final int PROBE_MAX_TOKENS = 32;

    /** 探针发送的用户消息。 */
    public static final String PROBE_PROMPT = "Reply with the single word: pong.";

    private ConfiguredRuntimeInspector() {
    }

    /** A models.json model as seen after the loader merges it with built-ins. */
    public record AddedModel(
        String providerId,
        String modelId,
        String name,
        boolean reasoning,
        boolean text,
        boolean toolUse,
        boolean streaming,
        double inputCost,
        double outputCost,
        Integer contextWindow,
        Integer maxTokens,
        int builtinModels,
        int fileModels,
        int totalModels
    ) {
    }

    /** One provider probe result. */
    public record ProviderProbe(
        String text,
        String stopReason,
        long inputTokens,
        long outputTokens
    ) {
    }

    /**
     * Read an added model from {@code models.json} and merge-count it against built-ins.
     *
     * @param modelsJsonPath models.json path
     * @param providerId      provider id (map key)
     * @param modelId         model id
     * @return added-model view
     * @throws IllegalStateException when the provider or model is absent
     */
    public static AddedModel inspectAddedModel(Path modelsJsonPath, String providerId,
                                               String modelId) {
        var config = ModelsJsonConfig.load(modelsJsonPath);
        var def = config.provider(providerId);
        if (def == null) {
            throw new IllegalStateException(
                "models.json provider not found: " + providerId + " in " + modelsJsonPath);
        }
        var id = ModelId.of(providerId, modelId);
        var model = config.catalog().find(id).orElseThrow(() -> new IllegalStateException(
            "models.json model not found: " + providerId + "/" + modelId));
        var builtin = ProviderCatalog.allModels().listModels();
        var file = config.catalog().listModels();
        return new AddedModel(providerId, modelId, model.displayName(),
            model.capabilities().contains(ModelCapability.THINKING),
            model.capabilities().contains(ModelCapability.TEXT),
            model.capabilities().contains(ModelCapability.TOOL_USE),
            model.capabilities().contains(ModelCapability.STREAMING),
            model.pricing().inputPrice(), model.pricing().outputPrice(),
            model.maxInputTokens(), model.maxOutputTokens(),
            builtin.size(), file.size(), mergedCount(builtin, file));
    }

    /**
     * Build the configured provider and send one probe request.
     *
     * @param modelsJsonPath models.json path
     * @param providerId      provider id (map key)
     * @param modelId         model id
     * @param maxOutputTokens probe output cap
     * @return probe result
     * @throws IllegalStateException when the config is invalid, the probe errors, or it
     *                               ends without a terminal stop reason
     */
    public static ProviderProbe inspectProvider(Path modelsJsonPath, String providerId,
                                                String modelId, int maxOutputTokens) {
        var config = ModelsJsonConfig.load(modelsJsonPath);
        var def = config.provider(providerId);
        if (def == null) {
            throw new IllegalStateException(
                "models.json provider not found: " + providerId + " in " + modelsJsonPath);
        }
        Provider provider = ModelsJsonConfig.buildProviderEntry(providerId, def);
        var id = ModelId.of(providerId, modelId);
        var model = provider.builtinModels().find(id)
            .orElseThrow(() -> new IllegalStateException(
                "models.json model not found: " + providerId + "/" + modelId));
        return probe(provider, model, maxOutputTokens);
    }

    private static ProviderProbe probe(Provider provider, ModelInfo model, int maxOutputTokens) {
        var request = new StreamRequest(model, "", List.of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent(PROBE_PROMPT)))), List.of(),
            maxOutputTokens, -1);
        var api = provider.createApi(ChatApi.class, ApiOptions.defaults());
        var text = new StringBuilder();
        StreamEvent.TextEnd lastTextEnd = null;
        StreamEvent.UsageInfo usage = null;
        StreamEvent.StreamDone done = null;
        try (var iterator = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iterator.hasNext()) {
                var event = iterator.next();
                switch (event) {
                    case StreamEvent.TextDelta delta -> text.append(delta.delta());
                    case StreamEvent.TextEnd end -> lastTextEnd = end;
                    case StreamEvent.UsageInfo info -> usage = info;
                    case StreamEvent.StreamDone finished -> done = finished;
                    case StreamEvent.StreamError error -> throw new IllegalStateException(
                        "Probe stream failed: " + StreamEvent.StreamError.textOf(error));
                    default -> {
                    }
                }
            }
        }
        if (done == null) {
            throw new IllegalStateException("Probe stream ended without a terminal event");
        }
        var responseText = lastTextEnd != null ? lastTextEnd.text() : text.toString();
        var stopReason = done.reason() == null ? null : done.reason().wireName();
        long input = usage == null ? 0 : usage.inputTokens();
        long output = usage == null ? 0 : usage.outputTokens();
        return new ProviderProbe(responseText, stopReason, input, output);
    }

    private static int mergedCount(List<ModelInfo> builtin, List<ModelInfo> file) {
        Set<ModelId<?>> ids = new HashSet<>();
        builtin.forEach(model -> ids.add(model.id()));
        file.forEach(model -> ids.add(model.id()));
        return ids.size();
    }
}
