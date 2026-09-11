package com.pijava.agent.harness;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.model.ModelId;

/**
 * The fold result shapes for a lane's record log, plus the entry points that
 * produce them.
 *
 * <p>Aligned with pi's {@code harness/reducer.ts} ({@code reduceLaneState}):
 * operation state, queue pending sets, the effective configuration, the
 * pending deferred writes, the unredeemed deferred handle, the newest tool
 * batch and the terminal-failure provenance (docs/21 §6, docs/22 §3.4).</p>
 *
 * <p>The fold algorithm lives in {@link LaneOperationFold} and the corruption
 * rules in {@link RecordLogValidator}; this class only owns the record
 * definitions and keeps the original entry points.</p>
 */
final class LaneStateFolder {

    private LaneStateFolder() {}

    // ═══════════════════════════════════════════════════════════
    // Fold result
    // ═══════════════════════════════════════════════════════════

    /**
     * The folded snapshot of a lane, field-aligned with {@link LaneState}.
     *
     * <p>{@code phase} is normalized: an open operation folds to
     * {@code CHECKPOINT} rather than the transient {@code ASSISTANT} state
     * that the live driver passes through right after a tool-use stream
     * (docs/21 R5).</p>
     */
    record FoldedState(
        String lane,
        RunPhase phase,
        String runId,
        int stepIndex,
        LaneState.NewestOwn newestOwn,
        boolean faulted,
        boolean aborted,
        EffectiveConfiguration effectiveConfiguration,
        List<LaneInfo.QueuedItem> pendingSteer,
        List<LaneInfo.QueuedItem> pendingFollowUp,
        List<LaneInfo.QueuedItem> pendingNextRun,
        List<ProvisionedEntry<?>> pendingWrites,
        DeferredHandle deferred,
        LaneOperationFold.ToolBatch toolBatch,
        LaneOperationFold.TerminalFailure terminalFailure
    ) {
        FoldedState {
            pendingSteer = List.copyOf(pendingSteer);
            pendingFollowUp = List.copyOf(pendingFollowUp);
            pendingNextRun = List.copyOf(pendingNextRun);
            pendingWrites = List.copyOf(pendingWrites);
        }

        /** True when the lane has no open operation. */
        boolean idle() {
            return phase instanceof RunPhase.Idle;
        }
    }

    /**
     * Configuration derived from the configuration entries, overlaid in
     * sequence order.
     *
     * <p>A {@code null} field means "nothing recorded" — the lane inherits the
     * harness default. {@code activeToolNames} is only ever non-null when an
     * {@code ActiveToolsChange} entry exists; {@code setActiveTools} does not
     * emit one today, so a lane-level tool override is not recoverable from
     * the log (docs/21 D11).</p>
     */
    record EffectiveConfiguration(
        ModelId<?> model,
        String thinkingLevel,
        List<String> activeToolNames
    ) {
        EffectiveConfiguration {
            activeToolNames = activeToolNames == null ? null : List.copyOf(activeToolNames);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Entry points
    // ═══════════════════════════════════════════════════════════

    /**
     * Fold a lane's bounded record slice back into orchestration state.
     *
     * @param lane                 lane name (for diagnostics)
     * @param records              the lane's records, oldest first
     * @param ownEntries           entries appended by the open operation, oldest first
     * @param configurationEntries configuration entries (see {@link Entry#isConfiguration()}), oldest first
     * @throws RecordLogCorruption when the record log is internally inconsistent
     */
    static FoldedState fold(String lane, List<LaneRecord> records,
                            List<Entry> ownEntries, List<Entry> configurationEntries) {
        return LaneOperationFold.fold(lane, records, ownEntries, configurationEntries);
    }

    /**
     * Validate a lane's record log against the subset of pi's rules that need
     * no entry lookups (docs/21 §3.4, R8).
     *
     * @throws RecordLogCorruption on the first violated rule
     */
    static void validateRecordLog(String lane, List<LaneRecord> records) {
        RecordLogValidator.validate(lane, records);
    }
}
