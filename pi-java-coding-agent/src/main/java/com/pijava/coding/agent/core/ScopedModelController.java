package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelScopeDiagnostic;
import com.pijava.ai.model.ModelScopeResolver;
import com.pijava.ai.model.ScopedModel;

/** Session-only scoped-model edits, explicit persistence, and refresh reconciliation. */
public final class ScopedModelController {

    /** Session/settings boundary used by the real session and lightweight test rigs. */
    public interface ScopeSink {
        List<ScopedModel> sessionScopedModels();
        void setSessionScopedModels(List<ScopedModel> models);
        SettingsManager settings();
    }

    private final ScopeSink sink;
    private final List<String> configuredPatterns;
    private final boolean initialSessionScopeEmpty;
    private List<ModelInfo> availableModels;
    private EnabledModelSet enabled;
    private boolean dirty;
    private String refreshStatus = "Refreshing model catalogs…";

    private ScopedModelController(ScopeSink sink, List<ModelInfo> availableModels) {
        this.sink = sink;
        this.availableModels = List.copyOf(availableModels);
        this.configuredPatterns = sink.settings().accessors().getEnabledModelsOrNull();
        var sessionScope = sink.sessionScopedModels();
        this.initialSessionScopeEmpty = sessionScope.isEmpty();
        this.enabled = sessionScope.isEmpty()
            ? configuredEnabledIds(availableModels)
            : new EnabledModelSet.Explicit(sessionScope.stream().map(ScopedModelController::fullId).toList());
    }

    /** Open against the current session scope, falling back to configured patterns. */
    public static ScopedModelController open(ScopeSink sink, List<ModelInfo> available) {
        var controller = new ScopedModelController(sink, available);
        if (controller.initialSessionScopeEmpty) {
            controller.updateSessionModels();
        }
        return controller;
    }

    /** Apply one session-only edit and mark it unsaved. */
    public void update(EnabledModelSet enabled) {
        this.enabled = enabled;
        this.dirty = true;
        updateSessionModels();
    }

    /** Persist the current set; All and complete available coverage become unset. */
    public void persist() {
        var availableIds = availableIds();
        boolean allEnabled = enabled instanceof EnabledModelSet.Explicit explicit
            && explicit.orderedIds().size() == availableIds.size()
            && explicit.orderedIds().containsAll(availableIds);
        sink.settings().accessors().setEnabledModels(
            enabled instanceof EnabledModelSet.All || allEnabled ? null : enabledIds());
        sink.settings().flush();
        dirty = false;
    }

    /** Reconcile refreshed models without overwriting a user's in-progress selection. */
    public void refreshFinished(List<ModelInfo> available, String status) {
        this.availableModels = List.copyOf(available);
        if (!dirty && initialSessionScopeEmpty) {
            enabled = configuredEnabledIds(availableModels);
        }
        updateSessionModels();
        this.refreshStatus = status;
    }

    private EnabledModelSet configuredEnabledIds(List<ModelInfo> models) {
        if (configuredPatterns == null || configuredPatterns.isEmpty()) {
            return new EnabledModelSet.All();
        }
        var resolved = ModelScopeResolver.resolve(configuredPatterns, models);
        var ids = new ArrayList<>(resolved.scopedModels().stream().map(ScopedModelController::fullId).toList());
        for (var diagnostic : resolved.diagnostics()) {
            if (diagnostic.code() == ModelScopeDiagnostic.Code.NO_MATCH && !ids.contains(diagnostic.pattern())) {
                ids.add(diagnostic.pattern());
            }
        }
        return new EnabledModelSet.Explicit(ids);
    }

    private void updateSessionModels() {
        if (!(enabled instanceof EnabledModelSet.Explicit explicit)) {
            sink.setSessionScopedModels(List.of());
            return;
        }
        var availableIds = availableIds();
        boolean hasAvailable = explicit.orderedIds().stream().anyMatch(availableIds::contains);
        boolean allAvailable = availableIds.stream().allMatch(explicit.orderedIds()::contains);
        if (hasAvailable && !allAvailable) {
            sink.setSessionScopedModels(ModelScopeResolver.resolve(explicit.orderedIds(), availableModels)
                .scopedModels());
        } else {
            sink.setSessionScopedModels(List.of());
        }
    }

    private LinkedHashSet<String> availableIds() {
        return availableModels.stream().map(ScopedModelController::fullId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> enabledIds() {
        return ((EnabledModelSet.Explicit) enabled).orderedIds();
    }

    private static String fullId(ScopedModel model) {
        return fullId(model.model());
    }

    private static String fullId(ModelInfo model) {
        return model.id().provider() + "/" + model.id().modelName();
    }

    /** Current selection, including unavailable ids. */
    public EnabledModelSet enabled() {
        return enabled;
    }

    /** Current catalog snapshot used by the selector. */
    public List<ModelInfo> availableModels() {
        return availableModels;
    }

    /** Whether changes have not been explicitly saved. */
    public boolean dirty() {
        return dirty;
    }

    /** Current refresh status text. */
    public String refreshStatus() {
        return refreshStatus;
    }
}
