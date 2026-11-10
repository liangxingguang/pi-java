package com.pijava.coding.agent.extension;

import java.util.Optional;
import java.util.OptionalDouble;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.core.AgentSession;

/**
 * docs/50 §2.2：{@link ExtensionModelRegistry} 的默认实现 —— 晚绑定到当前
 * {@link AgentSession}（fork/switch 后自动指向新会话）。{@code complete} 经宿主
 * 的 {@code ModelCompletion} 缝执行，纯读、不写会话。
 */
final class DefaultExtensionModelRegistry implements ExtensionModelRegistry {

    private final DefaultExtensionContext context;

    DefaultExtensionModelRegistry(DefaultExtensionContext context) {
        this.context = context;
    }

    @Override
    public Optional<ExtensionModelInfo> find(String provider, String modelId) {
        var session = context.requireHost("modelRegistry.find");
        for (var definition : session.virtualModels().list()) {
            if (definition.provider().equals(provider) && definition.id().equals(modelId)) {
                return Optional.of(ExtensionModelInfo.from(definition));
            }
        }
        return session.modelCatalog().find(ModelId.of(provider, modelId))
            .map(ExtensionModelInfo::from);
    }

    @Override
    public boolean hasConfiguredAuth(ExtensionModelInfo model) {
        var session = context.requireHost("modelRegistry.hasConfiguredAuth");
        var provider = model.provider();
        return session.virtualModels().isAuthExempt(provider)
            || Credentials.resolveCredential(provider).isPresent();
    }

    @Override
    public Message.AssistantMessage complete(ExtensionModelInfo model, Context context,
                                             ExtensionCompleteOptions options) {
        var session = this.context.requireHost("modelRegistry.complete");
        var opts = options == null ? ExtensionCompleteOptions.defaults() : options;
        if (opts.aborted()) {
            return com.pijava.agent.compaction.AssistantResponseCollector.abortedMessage();
        }
        var streamOptions = new StreamOptions(
            opts.maxTokens(), OptionalDouble.empty(), opts.reasoning(),
            opts.cacheRetention(), opts.sessionId());
        return session.completeModel(model.id(), context, streamOptions, opts::aborted);
    }
}
