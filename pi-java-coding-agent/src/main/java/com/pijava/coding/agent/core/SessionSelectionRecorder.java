package com.pijava.coding.agent.core;

import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.core.virtual.VirtualModelSelections;

/**
 * Record the run's model selection on the current branch (pi
 * {@code _recordModelSelection}, {@code agent-session.ts:662-706}, called from
 * {@code _recordSelection} at {@code :1979}; L5 W4).
 *
 * <p>A virtual selection that the branch does not record yet is written as a
 * {@code model_change}; equal selections and purely physical switches are not.</p>
 */
final class SessionSelectionRecorder {

    private SessionSelectionRecorder() {}

    static void record(AgentSession owner) {
        var current = owner.harness().getModel();
        if (current == null) {
            return;
        }
        var entries = owner.accumulatedEntries();
        if (entries.isEmpty()) {
            return;
        }
        var branch = ContextEntries.pathToLeaf(entries, entries.getLast().id());
        var recorded = VirtualModelSelections.getBranchSelection(branch,
            id -> modelKnown(owner, id), owner::isVirtualModel);
        if (recorded == null
                || (recorded.provider().equals(current.provider())
                    && recorded.modelId().equals(current.modelName()))) {
            return;
        }
        var recordedId = ModelId.of(recorded.provider(), recorded.modelId());
        // Only a virtual involvement needs a fresh model_change; physical switches
        // already record in setModel.
        if (!owner.isVirtualModel(current)
                && !(modelKnown(owner, recordedId) && owner.isVirtualModel(recordedId))) {
            return;
        }
        owner.harness().setModel(current);
    }

    private static boolean modelKnown(AgentSession owner, ModelId<?> id) {
        if (owner.modelCatalog().find(id).isPresent()) {
            return true;
        }
        return owner.services().providers().get(id.provider())
            .map(provider -> provider.getModels().stream()
                .anyMatch(model -> model.id().modelName().equals(id.modelName())))
            .orElse(false);
    }
}
