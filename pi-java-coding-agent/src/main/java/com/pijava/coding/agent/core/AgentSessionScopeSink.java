package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ScopedModel;

/** Production ScopeSink backed by AgentSession's existing package-private collaborators. */
public final class AgentSessionScopeSink implements ScopedModelController.ScopeSink {

    private final AgentSession session;

    /** Bind the scope sink to one session. */
    public AgentSessionScopeSink(AgentSession session) {
        this.session = session;
    }

    /** Current models across all registered providers, in provider/catalog order. */
    public static List<ModelInfo> availableModels(AgentSession session) {
        return session.services().providers().listAll().stream()
            .flatMap(provider -> provider.getModels().stream()).toList();
    }

    @Override
    public List<ScopedModel> sessionScopedModels() {
        return session.scopedModels();
    }

    @Override
    public void setSessionScopedModels(List<ScopedModel> models) {
        session.setScopedModels(models);
    }

    @Override
    public SettingsManager settings() {
        return session.services().settings();
    }
}
