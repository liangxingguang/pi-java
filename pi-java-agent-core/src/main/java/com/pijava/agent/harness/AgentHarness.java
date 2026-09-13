package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.TelemetryContext;

/**
 * Central agent runtime — prompt → LLM → tool → repeat loop.
 *
 * <p><b>驱动只有一个</b>：{@link PiLoop}（{@code docs/31 §6}）。原先并存的
 * {@code peekAction} / {@code executeAction} 步进链连同 {@code Action}、{@code RunPhase}、
 * {@code DriveMode} 已删除 —— 它们在生产路径上零调用者，只被测试使用，而推进职责本就归
 * 驱动循环。宿主剩下的职责是 pi {@code agent.ts} 的那几件：起手、收口、abort、reset、
 * 快照与订阅。</p>
 *
 * <p>{@link #prompt} / {@link #continueRun} 是**阻塞**的：pi 的 {@code prompt()} 返回
 * Promise，Java 侧由调用线程直接跑到收口（{@code docs/31 §8.5} 的口径 —— 对齐的是
 * 「可观察效果的顺序」，不是 async 机器）。</p>
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
    private volatile boolean closed;

    // Phase 2c: multi-lane（容器与生命周期在 LaneRegistry）
    private final LaneRegistry registry;

    // Phase 2c: hooks
    private final HookSystem hookSystem;

    // Phase 2c: skills
    private final SkillManager skillManager = new SkillManager();

    // Phase 2c: event bus
    final HarnessEventBus eventBus = new HarnessEventBus();

    // Phase 2c: token counter
    private final ExecutionContext.TokenCounter tokenCounter = new ExecutionContext.TokenCounter();

    // run 的起手/收口（pi runWithLifecycle / finishRun）
    private RunLifecycle runLifecycle;

    // 唯一的驱动：pi 双循环引擎
    private final PiLaneEngine piEngine;

    // Phase 2c: snapshot service
    private SnapshotService snapshotService;

    // Phase 2c: queue manager + telemetry
    private QueueManager queueManager;
    private final TelemetryContext telemetry;

    // Phase 2c: mutable run configuration (model/thinking/tools/queues)
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
        state.steeringMode = config.steeringMode();
        state.followUpMode = config.followUpMode();
        state.toolExecution = config.toolExecution();
        state.compactionSettings = config.compactionSettings();
        this.telemetry = config.telemetry();
        this.registry = new LaneRegistry(DEFAULT_LANE);
        var lanes = registry.lanes();
        this.hookSystem = new HookSystem(lanes);
        config.skills().values().forEach(skillManager::register);

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

        // Build execution context and the run lifecycle
        var execCtx = new ExecutionContext(
            streamFn, () -> state.model, () -> state.thinkingLevel,
            () -> state.systemPrompt, () -> state.activeTools,
            maxInputTokens, toolRegistry, toolContext,
            skillManager,
            hookSystem, lanes, () -> state.compactionSettings, config.thinkingLevelMap(),
            tokenCounter, snapshotService, queueManager, () -> state.toolExecution,
            () -> eventBus::broadcastStream, config.summaryGenerator(),
            state::applyTurn, telemetry);
        this.runLifecycle = new RunLifecycle(execCtx);
        this.piEngine = new PiLaneEngine(execCtx, runLifecycle);
    }

    /**
     * Register a listener for every {@link StreamEvent} the harness consumes.
     * Phase 6: multiple concurrent listeners supported (RPC + TUI sharing a
     * harness); closing the handle removes only this listener.
     */
    public AutoCloseable onStreamEvent(Consumer<StreamEvent> listener) {
        return eventBus.subscribeStream(listener);
    }

    // ═══════════════════════════════════════════════════════════
    // Multi-lane
    // ═══════════════════════════════════════════════════════════

    /** Get the default lane handle. */
    public LaneHandle lane() {
        return registry.handle(this);
    }

    /** Create a new lane. */
    public LaneHandle createLane(LaneConfig config) {
        if (closed) throw new HarnessClosedException();
        return registry.create(this, config);
    }

    /** List all lane handles. */
    public List<LaneHandle> lanes() {
        return registry.handles(this);
    }

    /** Move entries from one lane to another. */
    public void moveLane(String source, String target) {
        if (closed) throw new HarnessClosedException();
        registry.move(source, target);
    }

    // ═══════════════════════════════════════════════════════════
    // Queue scheduling — delegated to QueueManager
    // ═══════════════════════════════════════════════════════════

    /** Enqueue a steer prompt (injected into the current run's next round). */
    public String steer(String laneName, String prompt) {
        return queueManager.steer(laneName, prompt);
    }

    /** Enqueue a steer prompt with images. */
    public String steer(String laneName, String prompt, List<PromptImage> images) {
        return queueManager.steer(laneName, prompt, images == null ? List.of() : images);
    }

    /** Enqueue a follow-up prompt (processed when the current run finishes). */
    public String followUp(String laneName, String prompt) {
        return queueManager.followUp(laneName, prompt);
    }

    /** Enqueue a follow-up prompt with images. */
    public String followUp(String laneName, String prompt, List<PromptImage> images) {
        return queueManager.followUp(laneName, prompt, images == null ? List.of() : images);
    }

    /** Enqueue a next-run prompt (starts a run when the lane is idle). */
    public String nextRun(String laneName, String prompt) {
        return queueManager.nextRun(laneName, prompt);
    }

    /** Enqueue a next-run prompt with images. */
    public String nextRun(String laneName, String prompt, List<PromptImage> images) {
        return queueManager.nextRun(laneName, prompt, images == null ? List.of() : images);
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

    /** The shared tool context (shell executor, cwd, env) for this harness. */
    public ToolContext toolContext() {
        return toolContext;
    }

    /** Current tool execution mode. */
    public ToolExecution toolExecution() {
        return state.toolExecution;
    }

    /** Change the tool execution mode (Phase 3). */
    public void toolExecution(ToolExecution mode) {
        state.toolExecution = mode;
    }

    // ═══════════════════════════════════════════════════════════
    // Run lifecycle — pi Agent.prompt / continue / abort / reset
    // ═══════════════════════════════════════════════════════════

    /** Run a prompt to completion on the default lane. */
    public PiLaneEngine.RunOutcome prompt(String text) {
        return prompt(registry.defaultLaneName(), text, List.of(), null);
    }

    /** Run a prompt to completion on the default lane, with attached images. */
    public PiLaneEngine.RunOutcome prompt(String text, List<PromptImage> images) {
        return prompt(registry.defaultLaneName(), text, images, null);
    }

    /** Run a prompt to completion on the specified lane. */
    public PiLaneEngine.RunOutcome prompt(String laneName, String text, List<PromptImage> images) {
        return prompt(laneName, text, images, null);
    }

    /**
     * Run a prompt to completion on the specified lane（pi {@code Agent.prompt}）。
     *
     * <p><b>阻塞</b>：返回时运行已收口，车道回到空闲。{@code downstream} 是会话层的事件
     * 接收器，可为 {@code null}。</p>
     */
    public PiLaneEngine.RunOutcome prompt(String laneName, String text, List<PromptImage> images,
                                          PiLoop.Sink downstream) {
        if (closed) throw new HarnessClosedException();
        // 计数点在 PiLaneEngine.run —— 全仓唯一的新起运行入口，这里不再重复记。
        return piEngine.run(laneName, text, images == null ? List.of() : images, downstream);
    }

    /**
     * Continue a run from the current transcript tail（pi {@code Agent.continue}）：
     * 不写新的用户 entry，直接进助手流。
     *
     * <p><b>阻塞</b>，语义同 {@link #prompt}。</p>
     */
    public PiLaneEngine.RunOutcome continueRun(String laneName, PiLoop.Sink downstream) {
        if (closed) throw new HarnessClosedException();
        return piEngine.continueRun(laneName, downstream);
    }

    /** Continue a run on the default lane. */
    public PiLaneEngine.RunOutcome continueRun() {
        return continueRun(registry.defaultLaneName(), null);
    }

    /** Abort the current run on the default lane. */
    public void abort() {
        abort(registry.defaultLaneName());
    }

    /** Abort the current run on the specified lane. */
    public void abort(String laneName) {
        var lane = requireLane(laneName);
        var signal = lane.abortSignal();
        if (signal != null) {
            signal.abort();
        }
        if (lane.isRunning()) {
            lane.records.add(new LaneRecord.AbortRequested(
                java.util.UUID.randomUUID().toString(), 0, laneName, null,
                lane.runId == null ? "" : lane.runId));
        }
        publishState(laneName);
    }

    /**
     * Wait for the lane's current run to finish（pi {@code Agent.waitForIdle}）。
     *
     * <p>车道空闲时立即返回。它在「另一条线程 abort 之后等它真的停下来」这个场景里有用
     * —— 调用 {@link #prompt} 的那条线程本来就是阻塞的，不需要它。</p>
     */
    public void waitForIdle(String laneName) {
        var lane = requireLane(laneName);
        var run = lane.activeRun;
        if (run != null) {
            run.done().join();
        }
    }

    /**
     * Clear lane transcript, all queues, and run state (pi Agent.reset
     * alignment). Rejected while the lane is running.
     */
    public void reset(String laneName) {
        if (closed) throw new HarnessClosedException();
        runLifecycle.reset(laneName);
        publishState(laneName);
    }

    /** Clear the default lane (pi Agent.reset alignment). */
    public void reset() {
        reset(registry.defaultLaneName());
    }

    /** Seed a lane transcript from a persisted session on resume (no-op when non-empty). */
    public void seedTranscript(String laneName, List<Entry> entries) {
        runLifecycle.seedTranscript(laneName, entries);
    }

    /** Load a lane's persisted record log on resume; the lane comes back idle (docs/30 §4.1). */
    public void restoreRecords(String laneName, List<LaneRecord> records) {
        if (closed) throw new HarnessClosedException();
        runLifecycle.restoreRecords(laneName, records);
    }

    /** Drop the trailing error assistant entry so a retry continues from the prior context. */
    public void dropTrailingErrorAssistant(String laneName) {
        runLifecycle.dropTrailingErrorAssistant(laneName);
    }

    /** Return the final assistant message from the most recent run (default lane). */
    public AssistantMessage lastAssistantMessage() {
        return registry.get(registry.defaultLaneName()).partial;
    }

    // ═══════════════════════════════════════════════════════════
    // Compaction
    // ═══════════════════════════════════════════════════════════

    /** Run a compaction on the default lane. */
    public void compact(CompactionSettings settings) {
        compact(registry.defaultLaneName(), settings);
    }

    /** Run a compaction on the specified lane. */
    public void compact(String laneName, CompactionSettings settings) {
        if (closed) throw new HarnessClosedException();
        runLifecycle.compact(laneName, settings);
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
    /** Set the model used for subsequent LLM calls（同时把变更写进 transcript，见 §4.1）. */
    public void setModel(ModelId<?> model) {
        if (closed) throw new HarnessClosedException();
        state.model = model;
        runLifecycle.recordModelChange(registry.defaultLaneName(), model);
    }
    public ModelThinkingLevel getThinkingLevel() { return state.thinkingLevel; }
    /** Set the thinking level used for subsequent LLM calls（变更时才写 entry，见 §4.1）. */
    public void setThinkingLevel(ModelThinkingLevel level) {
        if (closed) throw new HarnessClosedException();
        state.thinkingLevel = level;
        runLifecycle.recordConfigChanged(registry.defaultLaneName());
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
        for (var lane : registry.lanes().values()) {
            var signal = lane.abortSignal();
            if (signal != null) {
                signal.abort();
            }
            // Closing a running lane is an abort request: record it so the log
            // says why the operation stopped, matching abort().
            if (lane.isRunning()) {
                lane.records.add(new LaneRecord.AbortRequested(
                    java.util.UUID.randomUUID().toString(), 0, lane.laneName, null,
                    lane.runId == null ? "" : lane.runId));
            }
            snapshotService.publishState(lane.laneName);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    private LaneState requireLane(String laneName) {
        return registry.require(laneName);
    }
}
