package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.slash.builtin.ThinkingCommands;
import com.pijava.coding.agent.core.virtual.VirtualModelSelections;

/**
 * Restore the model and thinking level a resumed session branch records
 * (pi {@code sdk.ts:209-270}, anchor {@code a00a5048d}; L5 W4).
 *
 * <p>Only runs when no explicit {@code --model} / {@code --thinking} overrides the
 * branch. A model that is no longer registered, or one without configured auth,
 * falls back to the initial model with a {@code Could not restore model} warning
 * (and, once the initial model is known, {@code ". Using <p>/<m>"}).</p>
 */
public final class SessionSelectionRestore {

    private SessionSelectionRestore() {}

    /** Apply the branch selection to {@code session} after it was attached. */
    public static void apply(AgentSession session, Args args) {
        var entries = session.accumulatedEntries();
        if (entries.isEmpty()) {
            return;
        }
        var branch = ContextEntries.pathToLeaf(entries, entries.getLast().id());
        restoreModel(session, args, branch);
        restoreThinking(session, args, branch);
    }

    private static void restoreModel(AgentSession session, Args args, List<Entry> branch) {
        if (args.model() != null && !args.model().isBlank()) {
            return;
        }
        var selection = VirtualModelSelections.getBranchSelection(branch,
            id -> modelKnown(session, id), session::isVirtualModel);
        if (selection == null) {
            return;
        }
        var id = ModelId.of(selection.provider(), selection.modelId());
        if (modelKnown(session, id)
                && DefaultProviders.hasConfiguredAuth(
                    id.provider(), session.services().settings().effective())) {
            session.harness().setModelWithoutRecord(id);
            return;
        }
        var fallback = "Could not restore model " + selection.provider()
            + "/" + selection.modelId();
        var current = session.harness().getModel();
        if (current != null) {
            fallback += ". Using " + current.provider() + "/" + current.modelName();
        }
        session.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", fallback));
    }

    private static void restoreThinking(AgentSession session, Args args, List<Entry> branch) {
        if (args.thinking() != null) {
            return;
        }
        Entry.ThinkingLevelChange recorded = null;
        for (var i = branch.size() - 1; i >= 0; i--) {
            if (branch.get(i) instanceof Entry.ThinkingLevelChange change) {
                recorded = change;
                break;
            }
        }
        if (recorded == null) {
            // pi `_recordThinkingLevel`: no entry on the branch ⇒ leave the default.
            return;
        }
        var level = clamp(ThinkingLevels.parse(recorded.thinkingLevel()),
            ThinkingCommands.availableLevels(session));
        // Seed the clamped value first, so the next run does not record the
        // requested (unclamped) level over the one it actually runs as.
        session.harness().seedRecordedThinking(level.label());
        session.harness().setThinkingLevel(level);
    }

    /** pi {@code clampThinkingLevel}（models.ts:1315-1334）。 */
    static ModelThinkingLevel clamp(ModelThinkingLevel level, List<ModelThinkingLevel> available) {
        if (available.contains(level)) {
            return level;
        }
        var scale = ModelThinkingLevel.extended();
        var requested = scale.indexOf(level);
        if (requested >= 0) {
            for (var i = requested; i < scale.size(); i++) {
                if (available.contains(scale.get(i))) {
                    return scale.get(i);
                }
            }
            for (var i = requested - 1; i >= 0; i--) {
                if (available.contains(scale.get(i))) {
                    return scale.get(i);
                }
            }
        }
        return available.isEmpty() ? ModelThinkingLevel.off() : available.getFirst();
    }

    private static boolean modelKnown(AgentSession session, ModelId<?> id) {
        if (session.modelCatalog().find(id).isPresent()) {
            return true;
        }
        return session.services().providers().get(id.provider())
            .map(provider -> provider.getModels().stream()
                .anyMatch(model -> model.id().modelName().equals(id.modelName())))
            .orElse(false);
    }
}
