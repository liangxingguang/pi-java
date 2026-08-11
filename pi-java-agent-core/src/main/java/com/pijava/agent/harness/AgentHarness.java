package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.EntryHeader;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.RecordHeader;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * The central agent runtime — manages lanes, tools, hooks, and the
 * main prompt → LLM → tool → repeat loop.
 *
 * <h3>Drive model (Phase 2a)</h3>
 * Manual-drive only: the outer loop (CLI, TUI, or {@code AgentLoop})
 * calls {@link #peekAction()} → {@link #executeAction(Action)} to push
 * the state machine forward. The harness never runs autonomously.
 *
 * <h3>State machine</h3>
 * <pre>
 *   IDLE → run(prompt) → ASSISTANT
 *   ASSISTANT → peekAction() → StreamAssistant
 *   executeAction(StreamAssistant) → stream LLM → CHECKPOINT
 *   CHECKPOINT → peekAction() → AppendEntry (pending writes)
 *   CHECKPOINT → peekAction() → TryFinishRun (no pending writes)
 *   executeAction(TryFinishRun) → IDLE (or ASSISTANT via tool_use)
 *   executeAction(ExecuteTool) → execute tool → ASSISTANT
 * </pre>
 */
public class AgentHarness implements AutoCloseable {

    private final StreamFn streamFn;
    private ModelId<?> model;
    private ModelThinkingLevel thinkingLevel;
    private final String systemPrompt;
    private Set<AgentTool<?, ?>> activeTools;
    private final int maxInputTokens;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final String commandPrefix;

    // Single lane (Phase 2a)
    private final LaneState lane;
    private boolean aborted;

    // ── Factory ──────────────────────────────────────────────

    /**
     * Create a new AgentHarness from configuration.
     * The {@link StreamFn} is stored internally; the loop never touches it.
     */
    public static AgentHarness create(HarnessConfig config) {
        return new AgentHarness(config);
    }

    private AgentHarness(HarnessConfig config) {
        this.streamFn = config.streamFn();
        this.model = config.model();
        this.thinkingLevel = config.thinkingLevel();
        this.systemPrompt = config.systemPrompt();
        this.activeTools = config.activeTools();
        this.maxInputTokens = config.maxInputTokens();
        this.toolRegistry = config.toolRegistry();
        this.toolContext = config.toolContext();
        this.commandPrefix = config.commandPrefix();
        this.lane = new LaneState();
    }

    // ── Operation ────────────────────────────────────────────

    /**
     * Initiate a new run with the given user prompt.
     * Writes user Message + ThinkingLevelChange entries (if non-default),
     * transitions to ASSISTANT phase. Returns the first AppendEntry action
     * so that initialization entries are flushed before StreamAssistant runs.
     *
     * @return the first action to execute (AppendEntry for initial entries,
     *         or StreamAssistant if there are no pending writes)
     */
    public Action run(String prompt) {
        if (!(lane.phase instanceof RunPhase.Idle)) {
            throw new IllegalStateException("Cannot start run: lane is not idle");
        }
        aborted = false;
        lane.runId = UUID.randomUUID().toString();
        lane.stepIndex = 0;
        lane.partial = null;
        lane.newestOwn = null;
        lane.transcript.clear();
        lane.pendingWrites.clear();
        lane.records.clear();
        lane.pendingToolCalls.clear();
        lane.abortSignal = com.pijava.agent.tool.AbortSignal.create();

        // Write user message entry
        var userEntry = new Entry.Message(
                Entry.newHeader(lane.nextSeq(), ""),
                "user",
                List.of(new ContentBlock.TextContent(prompt))
        );
        lane.transcript.add(userEntry);
        lane.pendingWrites.add(new ProvisionedEntry(userEntry));

        // Write thinking level change if non-default
        if (thinkingLevel instanceof ModelThinkingLevel.Enabled en) {
            var levelName = en.level().label();
            var tlEntry = new Entry.ThinkingLevelChange(
                    Entry.newHeader(lane.nextSeq(), userEntry.header().id()),
                    levelName
            );
            lane.transcript.add(tlEntry);
            lane.pendingWrites.add(new ProvisionedEntry(tlEntry));
        }

        lane.phase = RunPhase.ASSISTANT;
        // Record operation start
        lane.records.add(new LaneRecord.OperationStarted(
                LaneRecord.newHeader(lane.records.size()), lane.runId, prompt));
        // Return first action — ensures entries are flushed before StreamAssistant
        return peekAction();
    }

    /** Abort the current run. */
    public void abort() {
        aborted = true;
        if (lane.abortSignal != null) {
            lane.abortSignal.abort();
        }
        if (!(lane.phase instanceof RunPhase.Idle)) {
            lane.records.add(new LaneRecord.AbortRequested(
                    LaneRecord.newHeader(lane.records.size()), "user_requested"));
        }
    }

    /** Return the final assistant message from the most recent run. */
    public AssistantMessage lastAssistantMessage() {
        return lane.partial;
    }

    // ── Manual drive ─────────────────────────────────────────

    /**
     * Peek at the next pending action without changing state.
     * Returns null when there is nothing to do (IDLE phase, no run in progress).
     */
    public Action peekAction() {
        return switch (lane.phase) {
            case RunPhase.Idle i -> null;
            case RunPhase.Assistant a -> {
                var pw = drainNextPendingWrite();
                if (pw != null) yield pw;
                // Phase 2b: yield pending tool calls before starting new LLM call
                if (!lane.pendingToolCalls.isEmpty()) {
                    yield lane.pendingToolCalls.remove(0);
                }
                yield new Action.StreamAssistant("assistant", 0);
            }
            case RunPhase.Checkpoint c -> {
                var pw = drainNextPendingWrite();
                if (pw != null) yield pw;
                yield new Action.TryFinishRun(determineOutcome());
            }
        };
    }

    /**
     * Return the next pending write as an AppendEntry action, or null if none.
     */
    private Action drainNextPendingWrite() {
        if (!lane.pendingWrites.isEmpty()) {
            var pw = lane.pendingWrites.get(0);
            return new Action.AppendEntry(
                    entryTypeName(pw.entry()), pw.entry().header().id());
        }
        return null;
    }

    /**
     * Execute a single action (manual drive mode).
     * For {@code StreamAssistant}: calls LLM via {@code StreamFn}, consumes events.
     * Returns the next action to execute, or null if the run has ended.
     */
    public Action executeAction(Action action) {
        return switch (action) {
            case Action.StreamAssistant sa -> executeStreamAssistant(sa);
            case Action.AppendEntry ae -> executeAppendEntry(ae);
            case Action.TryFinishRun tfr -> executeTryFinishRun(tfr);
            case Action.ExecuteTool et -> executeTool(et);
        };
    }

    // ── Internal: StreamAssistant ────────────────────────────

    private Action executeStreamAssistant(Action.StreamAssistant sa) {
        if (aborted) {
            lane.phase = RunPhase.CHECKPOINT;
            lane.partial = AssistantMessage.empty().withStopReason("aborted");
            lane.newestOwn = deriveNewestOwn();
            return peekAction();
        }

        // Build messages from transcript
        var messages = lane.buildMessages(systemPrompt);
        var thinkingConfig = translateThinking(thinkingLevel);

        // Phase 2b: pass tool definitions from registry
        var toolDefs = toolRegistry != null
            ? toolRegistry.toToolDefinitions()
            : List.<ToolDefinition>of();
        var options = new StreamOptions(
                java.util.OptionalInt.empty(),
                java.util.OptionalDouble.empty(),
                thinkingConfig,
                toolDefs
        );

        // Record step attempt
        int attemptIdx = lane.stepIndex++;
        long inputTokens = 0;
        long outputTokens = 0;
        try {
            var iter = streamFn.stream(messages, model, options);
            try {
                while (iter.hasNext()) {
                    if (aborted) {
                        iter.close();
                        break;
                    }
                    var event = iter.next();
                    // Track usage if present
                    if (event instanceof StreamEvent.UsageInfo ui
                            && ui.partial() != null
                            && ui.partial().usage() != null) {
                        inputTokens = ui.partial().usage().inputTokens();
                        outputTokens = ui.partial().usage().outputTokens();
                    }
                    // All events carry partial — replace current snapshot
                    if (event.partial() != null) {
                        lane.partial = event.partial();
                    }
                    if (event instanceof StreamEvent.StreamDone) {
                        break;
                    }
                    if (event instanceof StreamEvent.StreamError) {
                        break;
                    }
                }
            } finally {
                iter.close();
            }
        } catch (Exception e) {
            lane.partial = AssistantMessage.empty()
                    .withStopReason("error");
        }

        // Record step attempt
        lane.records.add(new LaneRecord.StepAttempt(
                LaneRecord.newHeader(lane.records.size()),
                attemptIdx, inputTokens, outputTokens));
        // Record usage if we have token counts
        if (inputTokens > 0 || outputTokens > 0) {
            lane.records.add(new LaneRecord.UsageRecord(
                    LaneRecord.newHeader(lane.records.size()),
                    inputTokens, outputTokens, model.modelName()));
        }

        // Create assistant message entry from the final partial
        if (lane.partial != null) {
            var parentId = lane.lastEntry() != null
                    ? lane.lastEntry().header().id() : "";
            var asstEntry = new Entry.Message(
                    Entry.newHeader(lane.nextSeq(), parentId),
                    "assistant",
                    lane.partial.content()
            );
            lane.transcript.add(asstEntry);
            lane.pendingWrites.add(new ProvisionedEntry(asstEntry));
        }

        lane.newestOwn = deriveNewestOwn();
        lane.phase = RunPhase.CHECKPOINT;
        return peekAction();
    }

    // ── Internal: AppendEntry ────────────────────────────────

    private Action executeAppendEntry(Action.AppendEntry ae) {
        for (var pw : lane.pendingWrites) {
            if (pw.entry().header().id().equals(ae.entryId())) {
                pw.markWritten();
                break;
            }
        }
        lane.pendingWrites.removeIf(ProvisionedEntry::isWritten);
        return peekAction();
    }

    // ── Internal: TryFinishRun ───────────────────────────────

    private Action executeTryFinishRun(Action.TryFinishRun tfr) {
        String status;
        if (lane.newestOwn == null) {
            status = "failed";
        } else {
            String stopReason = lane.newestOwn.stopReason();
            if ("tool_use".equals(stopReason)) {
                // Phase 2b: extract tool calls from the assistant partial
                List<Action.ExecuteTool> toolActions = extractToolCalls(lane.partial);
                if (!toolActions.isEmpty()) {
                    lane.pendingToolCalls.addAll(toolActions);
                    // Stay in CHECKPOINT — tool execution will transition back to ASSISTANT
                    status = "tool_use";
                } else {
                    status = "completed";
                }
            } else if (isErrorStopReason(stopReason)) {
                status = "error";
            } else {
                status = "completed";
            }
        }
        // Record operation finish
        lane.records.add(new LaneRecord.OperationFinished(
                LaneRecord.newHeader(lane.records.size()), lane.runId, status));
        if ("tool_use".equals(status)) {
            return peekAction();
        }
        lane.phase = RunPhase.IDLE;
        return null;
    }

    // ── Internal: ExecuteTool ────────────────────────────────

    /**
     * Execute a single tool call. Follows the exception-driven error model:
     * tool throws on failure, harness catches and wraps error content.
     */
    private Action executeTool(Action.ExecuteTool et) {
        List<ContentBlock> resultBlocks;
        boolean isError = false;

        try {
            var result = toolRegistry.execute(
                et.toolName(), et.toolCallId(), et.arguments(),
                lane.abortSignal, null, toolContext);
            resultBlocks = result.content();
        } catch (Exception e) {
            resultBlocks = List.of(new ContentBlock.TextContent(
                "Error: " + e.getMessage()));
            isError = true;
        }

        // Write tool result as Entry.Message(role="tool")
        var parentId = lane.lastEntry() != null
            ? lane.lastEntry().header().id() : "";
        var toolEntry = new Entry.Message(
            Entry.newHeader(lane.nextSeq(), parentId),
            "tool",
            List.of(new ContentBlock.ToolResultContent(
                et.toolCallId(), et.toolName(),
                resultBlocks, isError))
        );
        lane.transcript.add(toolEntry);
        lane.pendingWrites.add(new ProvisionedEntry(toolEntry));

        // Record tool execution
        lane.records.add(new LaneRecord.ToolStarted(
            LaneRecord.newHeader(lane.records.size()),
            et.toolCallId(), et.toolName(), et.arguments()));

        // Transition back to ASSISTANT for follow-up LLM call
        lane.phase = RunPhase.ASSISTANT;
        return peekAction();
    }

    /**
     * Extract tool call actions from an assistant message partial.
     * Phase 2b: scans content blocks for {@link ContentBlock.ToolUseContent}.
     */
    private static List<Action.ExecuteTool> extractToolCalls(AssistantMessage partial) {
        if (partial == null || partial.content() == null) {
            return List.of();
        }
        return partial.content().stream()
            .filter(ContentBlock.ToolUseContent.class::isInstance)
            .map(b -> {
                var tc = (ContentBlock.ToolUseContent) b;
                return new Action.ExecuteTool(tc.id(), tc.name(), tc.arguments());
            })
            .toList();
    }

    private static boolean isErrorStopReason(String stopReason) {
        return "error".equals(stopReason) || "aborted".equals(stopReason);
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Translate ModelThinkingLevel to provider ThinkingConfig.
     */
    private static com.pijava.ai.thinking.ThinkingConfig translateThinking(
            ModelThinkingLevel level) {
        return switch (level) {
            case ModelThinkingLevel.Off o -> com.pijava.ai.thinking.ThinkingConfig.OFF;
            case ModelThinkingLevel.Enabled en -> switch (en.level()) {
                case com.pijava.ai.thinking.ThinkingLevel.Minimal m ->
                    com.pijava.ai.thinking.ThinkingConfig.withBudget(1024);
                case com.pijava.ai.thinking.ThinkingLevel.Low l ->
                    com.pijava.ai.thinking.ThinkingConfig.withBudget(2048);
                case com.pijava.ai.thinking.ThinkingLevel.Medium m ->
                    com.pijava.ai.thinking.ThinkingConfig.withBudget(8192);
                case com.pijava.ai.thinking.ThinkingLevel.High h ->
                    com.pijava.ai.thinking.ThinkingConfig.withBudget(16384);
                case com.pijava.ai.thinking.ThinkingLevel.XHigh x ->
                    com.pijava.ai.thinking.ThinkingConfig.withBudget(32768);
            };
        };
    }

    private LaneState.NewestOwn deriveNewestOwn() {
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            var entry = lane.transcript.get(i);
            if (entry instanceof Entry.Message msg
                    && "assistant".equals(msg.role())) {
                String stopReason = lane.partial != null
                        ? lane.partial.stopReason() : null;
                return new LaneState.NewestOwn(
                        msg.header().id(), "message", "assistant", stopReason);
            }
        }
        return null;
    }

    private String determineOutcome() {
        if (lane.newestOwn == null) return "failed";
        String sr = lane.newestOwn.stopReason();
        if (isErrorStopReason(sr)) return "failed";
        if ("tool_use".equals(sr)) return "tool_use";
        return "completed";
    }

    private static String entryTypeName(Entry entry) {
        return switch (entry) {
            case Entry.Message m -> "message";
            case Entry.ModelChange mc -> "model_change";
            case Entry.ThinkingLevelChange tlc -> "thinking_level_change";
            case Entry.ActiveToolsChange atc -> "active_tools_change";
            case Entry.Compaction c -> "compaction";
            case Entry.BranchSummary bs -> "branch_summary";
            case Entry.Custom c -> "custom";
        };
    }

    // ── Model / thinking ─────────────────────────────────────

    public ModelId<?> getModel() { return model; }
    public void setModel(ModelId<?> model) { this.model = model; }
    public ModelThinkingLevel getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(ModelThinkingLevel level) { this.thinkingLevel = level; }

    // ── Tools ────────────────────────────────────────────────

    /** Return the active tool set. */
    public Set<AgentTool<?, ?>> getActiveTools() {
        return Set.copyOf(activeTools);
    }

    /** Update the active tool set. */
    public void setActiveTools(Set<AgentTool<?, ?>> tools) {
        this.activeTools = Set.copyOf(tools);
        if (toolRegistry != null) {
            toolRegistry.clear();
            toolRegistry.registerAll(List.copyOf(tools));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Deferred (Phase 2b/2c)
    // ═══════════════════════════════════════════════════════════

    public LaneHandle lane() {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public LaneHandle createLane(LaneConfig config) {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public List<LaneHandle> lanes() {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public void moveLane(String lane, String to) {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public DriveMode drive() {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public void drive(DriveMode mode) {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public CompletionStage<Void> runToCompletion() {
        throw new UnsupportedOperationException("Phase 2c");
    }

    public void compact(CompactionSettings settings) {
        throw new UnsupportedOperationException("Phase 2c");
    }

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    public void close() {
        // no-op in Phase 2a
    }
}
