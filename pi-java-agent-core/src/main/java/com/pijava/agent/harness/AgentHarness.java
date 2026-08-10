package com.pijava.agent.harness;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.ai.model.ModelId;

/**
 * The central agent runtime — manages lanes, tools, hooks, and the
 * main prompt → LLM → tool → repeat loop.
 *
 * <p>Phase 0 skeleton: all methods throw
 * {@link UnsupportedOperationException}. Full implementation in Phase 2.</p>
 */
public class AgentHarness implements AutoCloseable {

    // ── Lane management ──────────────────────────────────────

    /** Get the default lane handle. */
    public LaneHandle lane() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Create a new lane. */
    public LaneHandle createLane(LaneConfig config) {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** List all lanes. */
    public List<LaneHandle> lanes() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Move entries from one lane to another. */
    public void moveLane(String lane, String to) {
        throw new UnsupportedOperationException("Phase 2");
    }

    // ── Drive mode ───────────────────────────────────────────

    /** Current drive mode. */
    public DriveMode drive() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Set drive mode. */
    public void drive(DriveMode mode) {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Peek at the next pending action (manual mode only). */
    public Optional<Action> peekAction() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Execute a single action (manual mode only). */
    public void executeAction(Action action) {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Run from the current point to completion. */
    public CompletionStage<Void> runToCompletion() {
        throw new UnsupportedOperationException("Phase 2");
    }

    // ── Model / thinking / tools ─────────────────────────────

    /** Get the current model. */
    public ModelId<?> getModel() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Set the model. */
    public void setModel(ModelId<?> model) {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Get the current thinking level. */
    public ThinkingLevel getThinkingLevel() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Set the thinking level. */
    public void setThinkingLevel(ThinkingLevel level) {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Get the active tool set. */
    public Set<Tool> getActiveTools() {
        throw new UnsupportedOperationException("Phase 2");
    }

    /** Set the active tool set. */
    public void setActiveTools(Set<Tool> tools) {
        throw new UnsupportedOperationException("Phase 2");
    }

    // ── Compaction ───────────────────────────────────────────

    /** Manually trigger compaction. */
    public void compact(CompactionSettings settings) {
        throw new UnsupportedOperationException("Phase 2");
    }

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    public void close() {
        // no-op in Phase 0
    }
}
