package com.pijava.agent.harness;

import java.util.List;
import java.util.function.Supplier;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.ModelId;
import com.pijava.telemetry.TelemetryContext;

/**
 * {@link AgentHarness} 的**初始化装配族**抽离（#94，docs/10 §8）：字段、构造装配
 * （快照/队列/执行上下文/引擎的组网）、内部助手与队列调度委托。纯移动、零行为变化
 * —— 包私有抽象基类，{@code AgentHarness} 经继承获得的成员与其原声明逐一同形，
 * 公共 API 面（签名与语义）不变。
 */
abstract class HarnessBootstrap implements AutoCloseable {

    // ═══════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════

    protected final StreamFn streamFn;
    /**
     * B223（{@code 原 docs/47}）：压缩设置晚读口 —— 唯一供给 {@link ExecutionContext}
     * 与包私有观察口 {@link #currentCompactionSettings()}；每次决策按 lane 当前模型现解析。
     */
    protected final Supplier<CompactionSettings> compactionSettingsSupplier;
    protected final int maxInputTokens;
    protected final ToolRegistry toolRegistry;
    protected final ToolContext toolContext;
    protected volatile boolean closed;

    /** 构造配置的留存副本 —— 只为 fork 用（新会话要一个**同配置**的独立 harness）。 */
    protected final HarnessConfig config;

    /**
     * 本 harness **唯一**的车道（pi {@code AgentState}，{@code 原 docs/31 §4.3}）。
     *
     * <p>运行时多车道容器（{@code LaneRegistry} / {@code LaneHandle} / {@code LaneConfig}）
     * 已删除：pi 的对齐目标 {@code agent.ts} 是单状态的，而「多分支」归会话层 —— 每个
     * {@code AgentSession} 持有自己的 harness，分支就是新会话（存储层 lane 保留，那正是
     * pi 的分支模型）。</p>
     */
    protected final LaneState lane = new LaneState();

    // Phase 2c: hooks
    protected final HookSystem hookSystem;

    // Phase 2c: skills
    protected final SkillManager skillManager = new SkillManager();

    // Phase 2c: event bus
    final HarnessEventBus eventBus = new HarnessEventBus();

    // Phase 2c: token counter
    protected final ExecutionContext.TokenCounter tokenCounter = new ExecutionContext.TokenCounter();

    // run 的起手/收口（pi runWithLifecycle / finishRun）
    protected RunLifecycle runLifecycle;

    // 唯一的驱动：pi 双循环引擎
    protected final PiLaneEngine piEngine;

    // Phase 2c: snapshot service
    protected SnapshotService snapshotService;

    // Phase 2c: queue manager + telemetry
    protected QueueManager queueManager;
    protected final TelemetryContext telemetry;

    HarnessBootstrap(HarnessConfig config) {
        this.config = config;
        this.streamFn = config.streamFn();
        lane.laneName = AgentHarness.DEFAULT_LANE;
        lane.model = config.model();
        lane.thinkingLevel = config.thinkingLevel();
        lane.systemPrompt = config.systemPrompt();
        lane.promptGuidelines = config.promptGuidelines();
        lane.appendSystemPrompt = config.appendSystemPrompt();
        lane.activeTools = config.activeTools();
        this.maxInputTokens = config.maxInputTokens();
        this.toolRegistry = config.toolRegistry();
        this.toolContext = config.toolContext();
        lane.steeringMode = config.steeringMode();
        lane.followUpMode = config.followUpMode();
        lane.toolExecution = config.toolExecution();
        this.telemetry = config.telemetry();
        this.hookSystem = new HookSystem(lane);
        config.skills().values().forEach(skillManager::register);

        // Build snapshot service first (referenced by execution context)
        this.snapshotService = new SnapshotService(
            lane, eventBus, tokenCounter,
            () -> lane.model != null ? lane.model.modelName() : "unknown",
            () -> lane.activeTools.stream().map(AgentTool::name)
                .collect(java.util.stream.Collectors.toSet()));

        // Build queue manager first (referenced by the execution context)
        this.queueManager = new QueueManager(
            lane,
            () -> lane.steeringMode,
            () -> lane.followUpMode);

        // B223（原 docs/47）：压缩设置晚读口 —— 每次决策按 lane 的**当前**模型现解析
        //（pi getCompactionSettings(this.model) 四调用点）；setModel/modelOverrides/
        // 设置文件改动下次决策即生效；resolver == null ⇒ 引擎旧契约「不接＝不压」。
        final java.util.function.Function<ModelId<?>, CompactionSettings> compactionResolver =
            config.compactionResolver();
        this.compactionSettingsSupplier = () -> compactionResolver == null
            ? null
            : compactionResolver.apply(lane.model);
        Supplier<CompactionSettings> compactionSettings = compactionSettingsSupplier;

        // Build execution context and the run lifecycle
        var execCtx = new ExecutionContext(
            streamFn, () -> lane.model, () -> lane.thinkingLevel,
            () -> lane.systemPrompt, () -> lane.activeTools,
            maxInputTokens, config.contextWindow(), config.maxOutputTokens(),
            toolRegistry, toolContext,
            skillManager,
            hookSystem, lane, compactionSettings, config.thinkingLevelMap(),
            tokenCounter, snapshotService, queueManager, () -> lane.toolExecution,
            () -> eventBus::broadcastStream, config.summaryGenerator(),
            lane::applyTurn, telemetry, config.compactionObserver(),
            config.retrySettings(), config.retryAborted(), config.retryObserver(),
            () -> lane.promptGuidelines, () -> lane.appendSystemPrompt,
            config.contextFiles(), config.thinkingBudgets());
        this.runLifecycle = new RunLifecycle(execCtx);
        this.piEngine = new PiLaneEngine(execCtx, runLifecycle);
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
        return lane.steeringMode;
    }

    /** Change the steer-queue drain mode (Phase 3). */
    public void steeringMode(QueueMode mode) {
        lane.steeringMode = mode;
    }

    /** Current follow-up-queue drain mode. */
    public QueueMode followUpMode() {
        return lane.followUpMode;
    }

    /** Change the follow-up-queue drain mode (Phase 3). */
    public void followUpMode(QueueMode mode) {
        lane.followUpMode = mode;
    }

    /**
     * Pending steer-queue texts in queue order（B34，原 docs/26；
     * pi {@code getSteeringMessages}，agent-session.ts:2371-2373）。
     */
    public List<String> queuedSteering(String laneName) {
        return queueManager.steeringTexts(laneName);
    }

    /** Pending follow-up-queue texts（pi {@code getFollowUpMessages}，:2376-2378）。 */
    public List<String> queuedFollowUp(String laneName) {
        return queueManager.followUpTexts(laneName);
    }

    /**
     * 下一次压缩决策将使用的设置 —— 经 resolver 按<b>当前车道模型</b>现解析
     * （B223，{@code 原 docs/47}）；未接线返回 {@code null}（G0 关）。
     * 包私有观察口：引擎测试钉「每次决策重读、读当前模型」，不进公开 API
     * （pi 侧的权威读法在宿主 settings manager）。
     */
    CompactionSettings currentCompactionSettings() {
        return compactionSettingsSupplier.get();
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lane, laneName);
    }

    /** Publish lane + session snapshots after a state change. */
    void publishState(String laneName) {
        snapshotService.publishState(laneName);
    }
}
