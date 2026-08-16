package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolExecutor;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.TelemetryContext;

/**
 * Central agent runtime — prompt → LLM → tool → repeat loop.
 *
 * <p>Phase 2c: multi-lane support, lifecycle hooks, compaction, skills,
 * snapshot subscriptions, autonomous drive, and close().</p>
 *
 * <p>Manual-drive: the outer loop calls {@link #peekAction()} →
 * {@link #executeAction(Action)} to advance the state machine.</p>
 */
public class AgentHarness implements AutoCloseable {

    // ═══════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════

    /** Default lane name. */
    public static final String DEFAULT_LANE = "default";

    private final StreamFn streamFn;
    private final int maxInputTokens;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private boolean closed;

    // Phase 2c: multi-lane
    private final ConcurrentMap<String, LaneState> lanes = new ConcurrentHashMap<>();
    private final String defaultLaneName;

    // Phase 2c: hooks
    private final HookSystem hookSystem;

    // Phase 2c: skills
    private final SkillManager skillManager = new SkillManager();

    // Phase 2c: event bus
    final HarnessEventBus eventBus = new HarnessEventBus();

    // Phase 2c: token counter
    private final ExecutionContext.TokenCounter tokenCounter = new ExecutionContext.TokenCounter();

    // Phase 2c: action executor
    private ActionExecutor actionExecutor;

    // Phase 2c: snapshot service
    private SnapshotService snapshotService;

    // Phase 2c: queue manager + telemetry
    private QueueManager queueManager;
    private final TelemetryContext telemetry;
    private Consumer<StreamEvent> streamListener = event -> { };

    // Phase 2c: mutable run configuration (model/thinking/tools/drive/queues)
    private final HarnessState state = new HarnessState();

    // ── Factory ──────────────────────────────────────────────

    /** Create a new AgentHarness from configuration. */
    public static AgentHarness create(HarnessConfig config) {
        return new AgentHarness(config);
    }

    private AgentHarness(HarnessConfig config) {
        this.streamFn = config.streamFn();
        state.model = config.model();
        state.thinkingLevel = config.thinkingLevel();
        state.systemPrompt = config.systemPrompt();
        state.activeTools = config.activeTools();
        this.maxInputTokens = config.maxInputTokens();
        this.toolRegistry = config.toolRegistry();
        this.toolContext = config.toolContext();
        state.driveMode = config.driveMode() != null ? config.driveMode() : DriveMode.MANUAL;
        state.steeringMode = config.steeringMode();
        state.followUpMode = config.followUpMode();
        state.toolExecution = config.toolExecution();
        state.compactionSettings = config.compactionSettings();
        this.telemetry = config.telemetry();
        this.hookSystem = new HookSystem(lanes);
        this.defaultLaneName = DEFAULT_LANE;
        config.skills().values().forEach(skillManager::register);

        // Create the default lane
        var defaultLane = new LaneState();
        defaultLane.laneName = DEFAULT_LANE;
        lanes.put(DEFAULT_LANE, defaultLane);

        // Build snapshot service first (referenced by execution context)
        this.snapshotService = new SnapshotService(
            lanes, eventBus, tokenCounter,
            () -> state.model != null ? state.model.modelName() : "unknown",
            () -> state.activeTools.stream().map(AgentTool::name)
                .collect(java.util.stream.Collectors.toSet()));

        // Build queue manager first (referenced by the execution context)
        this.queueManager = new QueueManager(
            lanes,
            () -> state.steeringMode,
            () -> state.followUpMode);

        // Build execution context and action executor
        var execCtx = new ExecutionContext(
            streamFn, () -> state.model, () -> state.thinkingLevel,
            () -> state.systemPrompt, () -> state.activeTools,
            maxInputTokens, toolRegistry, toolContext,
            new ToolExecutor(toolRegistry, toolContext), skillManager,
            hookSystem, lanes, () -> state.compactionSettings, config.thinkingLevelMap(),
            tokenCounter, snapshotService, queueManager, () -> state.toolExecution,
            () -> streamListener);
        this.actionExecutor = new ActionExecutor(execCtx);
    }

    /**
     * Register a listener for every {@link StreamEvent} the harness consumes.
     * Phase 3: used by AgentSession to feed live streaming to Print/Interactive
     * modes. Only one listener is active at a time.
     *
     * @return registration handle; closing it restores the no-op listener
     */
    public AutoCloseable onStreamEvent(Consumer<StreamEvent> listener) {
        this.streamListener = listener;
        return () -> {
            if (this.streamListener == listener) {
                this.streamListener = event -> { };
            }
        };
    }

    // ═══════════════════════════════════════════════════════════
    // Multi-lane
    // ═══════════════════════════════════════════════════════════

    /** Get the default lane handle. */
    public LaneHandle lane() {
        return new LaneHandle(defaultLaneName, this);
    }

    /** Create a new lane. */
    public LaneHandle createLane(LaneConfig config) {
        if (closed) throw new HarnessClosedException();
        if (lanes.containsKey(config.name())) {
            throw new LaneExistsException(config.name());
        }
        var lane = new LaneState();
        lane.laneName = config.name();
        lane.parentLeafId = config.parentLeafId();
        lane.activeTools = config.activeTools() != null
            ? Set.copyOf(config.activeTools()) : null;
        lane.systemPrompt = config.systemPrompt();
        lanes.put(config.name(), lane);
        return new LaneHandle(config.name(), this);
    }

    /** List all lane handles. */
    public List<LaneHandle> lanes() {
        return lanes.keySet().stream()
            .map(name -> new LaneHandle(name, this))
            .toList();
    }

    /** Move entries from one lane to another. */
    public void moveLane(String source, String target) {
        if (closed) throw new HarnessClosedException();
        var src = requireLane(source);
        var tgt = requireLane(target);
        tgt.transcript.addAll(src.transcript);
        src.transcript.clear();
    }

    // ═══════════════════════════════════════════════════════════
    // Queue scheduling (Phase 3 stubs) — delegated to QueueManager
    // ═══════════════════════════════════════════════════════════

    /** Enqueue a steer prompt (injected into the current run's next round). */
    public String steer(String laneName, String prompt) {
        return queueManager.steer(laneName, prompt);
    }

    /** Enqueue a follow-up prompt (processed when the current run finishes). */
    public String followUp(String laneName, String prompt) {
        return queueManager.followUp(laneName, prompt);
    }

    /** Enqueue a next-run prompt (starts a run when the lane is idle). */
    public String nextRun(String laneName, String prompt) {
        return queueManager.nextRun(laneName, prompt);
    }

    /** Cancel all queued items of the given type ("steer", "followUp", "nextRun"). */
    public void cancelQueued(String laneName, String queueType) {
        queueManager.cancelQueued(laneName, queueType);
    }

    /** Current steer-queue drain mode. */
    public QueueMode steeringMode() {
        return state.steeringMode;
    }

    /** Change the steer-queue drain mode (Phase 3). */
    public void steeringMode(QueueMode mode) {
        state.steeringMode = mode;
    }

    /** Current follow-up-queue drain mode. */
    public QueueMode followUpMode() {
        return state.followUpMode;
    }

    /** Change the follow-up-queue drain mode (Phase 3). */
    public void followUpMode(QueueMode mode) {
        state.followUpMode = mode;
    }

    /** Current tool execution mode. */
    public ToolExecution toolExecution() {
        return state.toolExecution;
    }

    /** Change the tool execution mode (Phase 3). */
    public void toolExecution(ToolExecution mode) {
        state.toolExecution = mode;
    }

    // ── Operation (single-lane convenience overloads) ─────────

    /** Initiate a new run on the default lane. */
    public Action run(String prompt) {
        return run(defaultLaneName, prompt);
    }

    /** Initiate a new run on the specified lane. */
    public Action run(String laneName, String prompt) {
        if (closed) throw new HarnessClosedException();
        telemetry.incrementCounter("harness.turn", 1);
        return actionExecutor.run(laneName, prompt);
    }

    /**
     * Seed a lane transcript from a persisted session on resume. No-op when
     * the lane already has entries (Phase 4 recovery).
     */
    public void seedTranscript(String laneName, List<com.pijava.agent.entry.Entry> entries) {
        var lane = requireLane(laneName);
        if (!lane.transcript.isEmpty()) {
            return;
        }
        lane.transcript.addAll(entries);
    }

    /** Abort the current run on the default lane. */
    public void abort() {
        abort(defaultLaneName);
    }

    /** Abort the current run on the specified lane. */
    public void abort(String laneName) {
        var lane = requireLane(laneName);
        if (lane.abortSignal != null) {
            lane.abortSignal.abort();
        }
        if (!(lane.phase instanceof RunPhase.Idle)) {
            lane.records.add(new LaneRecord.AbortRequested(
                java.util.UUID.randomUUID().toString(), 0, laneName, null,
                lane.runId == null ? "" : lane.runId));
        }
        publishState(laneName);
    }

    /** Return the final assistant message from the most recent run (default lane). */
    public AssistantMessage lastAssistantMessage() {
        return lanes.get(defaultLaneName).partial;
    }

    // ═══════════════════════════════════════════════════════════
    // Manual drive
    // ═══════════════════════════════════════════════════════════

    /** Return the next pending action from the default lane. */
    public Action peekAction() {
        return peekAction(defaultLaneName);
    }

    /** Return the next pending action from the specified lane. */
    public Action peekAction(String laneName) {
        if (state.driveMode instanceof DriveMode.Automatic) {
            throw new IllegalStateException("peekAction is disabled in AUTOMATIC mode");
        }
        return actionExecutor.peekAction(laneName);
    }

    /** Execute a single action (default lane). */
    public Action executeAction(Action action) {
        return executeAction(defaultLaneName, action);
    }

    /** Execute a single action on the specified lane. */
    public Action executeAction(String laneName, Action action) {
        if (closed) throw new HarnessClosedException();
        if (state.driveMode instanceof DriveMode.Automatic) {
            throw new IllegalStateException("executeAction is disabled in AUTOMATIC mode");
        }
        var result = actionExecutor.executeAction(laneName, action);
        publishState(laneName);
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // Drive mode
    // ═══════════════════════════════════════════════════════════

    /** The current harness drive mode. */
    public DriveMode drive() {
        return state.driveMode;
    }

    /** Change the harness drive mode. */
    public void drive(DriveMode mode) {
        if (closed) throw new HarnessClosedException();
        state.driveMode = mode;
    }

    /** Drive the default lane to completion in AUTOMATIC mode. */
    public CompletionStage<Void> runToCompletion() {
        return runToCompletion(defaultLaneName);
    }

    /** Drive the specified lane to completion in AUTOMATIC mode. */
    public CompletionStage<Void> runToCompletion(String laneName) {
        if (state.driveMode instanceof DriveMode.Manual) {
            throw new IllegalStateException("Cannot runToCompletion in MANUAL mode");
        }
        return CompletableFuture.runAsync(() -> {
            Action action;
            while ((action = actionExecutor.peekAction(laneName)) != null) {
                actionExecutor.executeAction(laneName, action);
                publishState(laneName);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    // Compaction
    // ═══════════════════════════════════════════════════════════

    /** Run a compaction on the default lane. */
    public void compact(CompactionSettings settings) {
        compact(defaultLaneName, settings);
    }

    /** Run a compaction on the specified lane. */
    public void compact(String laneName, CompactionSettings settings) {
        if (closed) throw new HarnessClosedException();
        actionExecutor.compact(laneName, settings);
    }

    // ═══════════════════════════════════════════════════════════
    // Skills
    // ═══════════════════════════════════════════════════════════

    /** The skill registry used by this harness. */
    public SkillManager skillManager() {
        return skillManager;
    }

    // ═══════════════════════════════════════════════════════════
    // Hooks — delegated to HookSystem (registration via hookSystem())
    // ═══════════════════════════════════════════════════════════

    /** Access the hook system for registration (Phase 2c hooks). */
    public HookSystem hookSystem() {
        return hookSystem;
    }

    // ═══════════════════════════════════════════════════════════
    // Snapshot / Watch — delegated to SnapshotService
    // ═══════════════════════════════════════════════════════════

    /** Take a snapshot of the current lane state. */
    public LaneSnapshot snapshot(String laneName) {
        return snapshotService.snapshot(laneName);
    }

    /** Subscribe to snapshot updates for a lane. */
    public WatchHandle<LaneSnapshot> watch(String laneName) {
        return snapshotService.watch(laneName);
    }

    /** Subscribe to session-level snapshot updates. */
    public WatchHandle<SessionSnapshot> watchSession() {
        return snapshotService.watchSession();
    }

    /** Publish lane + session snapshots after a state change. */
    private void publishState(String laneName) {
        snapshotService.publishState(laneName);
    }

    // ═══════════════════════════════════════════════════════════
    // Model / Thinking / Tools
    // ═══════════════════════════════════════════════════════════

    public ModelId<?> getModel() { return state.model; }
    /** Set the model used for subsequent LLM calls. */
    public void setModel(ModelId<?> model) {
        if (closed) throw new HarnessClosedException();
        state.model = model;
    }
    public ModelThinkingLevel getThinkingLevel() { return state.thinkingLevel; }
    /** Set the thinking level used for subsequent LLM calls. */
    public void setThinkingLevel(ModelThinkingLevel level) {
        if (closed) throw new HarnessClosedException();
        state.thinkingLevel = level;
    }

    /** Change the system prompt for subsequent runs. */
    public void setSystemPrompt(String prompt) {
        if (closed) throw new HarnessClosedException();
        state.systemPrompt = prompt;
    }

    /** The current system prompt. */
    public String getSystemPrompt() {
        return state.systemPrompt;
    }

    public Set<AgentTool<?, ?>> getActiveTools() {
        return Set.copyOf(state.activeTools);
    }

    /** Replace the set of active tools, re-registering them in the tool registry. */
    public void setActiveTools(Set<AgentTool<?, ?>> tools) {
        if (closed) throw new HarnessClosedException();
        state.activeTools = Set.copyOf(tools);
        if (toolRegistry != null) {
            toolRegistry.clear();
            toolRegistry.registerAll(List.copyOf(tools));
        }
    }

    public CompactionSettings getCompactionSettings() { return state.compactionSettings; }
    /** Set the compaction settings used for subsequent runs. */
    public void setCompactionSettings(CompactionSettings s) {
        if (closed) throw new HarnessClosedException();
        state.compactionSettings = s;
    }

    // ═══════════════════════════════════════════════════════════
    // Close
    // ═══════════════════════════════════════════════════════════

    @Override
    public void close() {
        closed = true;
        for (var lane : lanes.values()) {
            if (lane.abortSignal != null) {
                lane.abortSignal.abort();
            }
            snapshotService.publishState(lane.laneName);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    private LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lanes, laneName);
    }

}
