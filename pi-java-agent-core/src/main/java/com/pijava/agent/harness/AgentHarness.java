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
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Central agent runtime — prompt → LLM → tool → repeat loop.
 *
 * <p><b>驱动只有一个</b>：{@link PiLoop}（{@code 原 docs/31 §6}）。原先并存的
 * {@code peekAction} / {@code executeAction} 步进链连同 {@code Action}、{@code RunPhase}、
 * {@code DriveMode} 已删除 —— 它们在生产路径上零调用者，只被测试使用，而推进职责本就归
 * 驱动循环。宿主剩下的职责是 pi {@code agent.ts} 的那几件：起手、收口、abort、reset、
 * 快照与订阅。</p>
 *
 * <p>{@link #prompt} / {@link #continueRun} 是**阻塞**的：pi 的 {@code prompt()} 返回
 * Promise，Java 侧由调用线程直接跑到收口（{@code 原 docs/31 §8.5} 的口径 —— 对齐的是
 * 「可观察效果的顺序」，不是 async 机器）。</p>
 *
 * <p>#94（docs/10 §8）：字段、构造装配、队列调度委托与内部助手抽在包私有基类
 * {@link HarnessBootstrap} —— 纯移动，公共 API 面（继承所得成员与下述声明）不变。</p>
 */
public class AgentHarness extends HarnessBootstrap {

    /** Default lane name. */
    public static final String DEFAULT_LANE = "default";

    // ── Factory ──────────────────────────────────────────────

    /** Create a new AgentHarness from configuration. */
    public static AgentHarness create(HarnessConfig config) {
        return new AgentHarness(config);
    }

    private AgentHarness(HarnessConfig config) {
        super(config);
    }

    /**
     * Register a listener for every {@link StreamEvent} the harness consumes.
     * Phase 6: multiple concurrent listeners supported (RPC + TUI sharing a
     * harness); closing the handle removes only this listener.
     */
    public AutoCloseable onStreamEvent(Consumer<StreamEvent> listener) {
        return eventBus.subscribeStream(listener);
    }

    /** The lane's name — 一个 harness 只有一条，恒为 {@link #DEFAULT_LANE}（{@code 原 docs/31 §4.3}）。 */
    public String laneName() {
        return lane.laneName;
    }

    /** The shared tool context (shell executor, cwd, env) for this harness. */
    public ToolContext toolContext() {
        return toolContext;
    }

    /** Current tool execution mode. */
    public ToolExecution toolExecution() {
        return lane.toolExecution;
    }

    /** Change the tool execution mode (Phase 3). */
    public void toolExecution(ToolExecution mode) {
        lane.toolExecution = mode;
    }

    // ═══════════════════════════════════════════════════════════
    // Run lifecycle — pi Agent.prompt / continue / abort / reset
    // ═══════════════════════════════════════════════════════════

    /** Run a prompt to completion on the default lane. */
    public PiLaneEngine.RunOutcome prompt(String text) {
        return prompt(DEFAULT_LANE, text, List.of(), null);
    }

    /** Run a prompt to completion on the default lane, with attached images. */
    public PiLaneEngine.RunOutcome prompt(String text, List<PromptImage> images) {
        return prompt(DEFAULT_LANE, text, images, null);
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
     * 批 26（docs/37 T2）：带 nextTurn custom 消息的 prompt 运行（pi
     * {@code agent-session.ts:2088-2092} 对位）——custom 投影排在用户消息之后进
     * 待发列表，落盘经 sink 的 custom 臂成 {@code Entry.CustomMessage}。
     */
    public PiLaneEngine.RunOutcome promptWithCustoms(String laneName, String text,
                                          List<PromptImage> images,
                                          List<LaneInfo.QueuedItem.Custom> customs,
                                          PiLoop.Sink downstream) {
        if (closed) throw new HarnessClosedException();
        return piEngine.run(laneName, text, images == null ? List.of() : images,
            customs == null ? List.of() : customs, downstream);
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
        return continueRun(DEFAULT_LANE, null);
    }

    /** Abort the current run on the default lane. */
    public void abort() {
        abort(DEFAULT_LANE);
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
     * pi {@code abortCompaction()}（agent-session.ts:2914-2917，#98/B36）：中止该车道
     * 的在飞压缩 —— 手动/自动双槽同时 abort（无在飞 ⇒ no-op，与 pi 的
     * {@code ?.abort()} 同）。生效点在生成后落库前（:2843-2845）。
     */
    public void abortCompaction(String laneName) {
        var lane = requireLane(laneName);
        var manual = lane.manualCompactionAbort;
        if (manual != null) {
            manual.abort();
        }
        var auto = lane.autoCompactionAbort;
        if (auto != null) {
            auto.abort();
        }
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
        reset(DEFAULT_LANE);
    }

    /** Seed a lane transcript from a persisted session on resume (no-op when non-empty). */
    public void seedTranscript(String laneName, List<Entry> entries) {
        runLifecycle.seedTranscript(laneName, entries);
    }

    /**
     * Replace the lane transcript wholesale (tree navigation — pi
     * {@code _refreshFinalizedContext}); unlike {@link #seedTranscript} this is
     * not first-fill-only.
     */
    public void replaceTranscript(String laneName, List<Entry> entries) {
        runLifecycle.replaceTranscript(laneName, entries);
    }

    /**
     * Append an entry to an <b>idle</b> lane and rebuild its working messages — pi
     * {@code recordBashResult} 的空闲臂（{@code appendMessage} ＋
     * {@code _refreshFinalizedContext}，agent-session.ts:3878-3899，#57/docs/10 §3.3 ⑥）。
     * 运行中的车道抛 {@link IllegalStateException}：调用方应先暂存、settled 再 flush
     * （pi 的 {@code _pendingBashMessages}）。
     */
    public void appendLaneEntry(String laneName, Entry entry) {
        runLifecycle.appendLaneEntry(laneName, entry);
    }

    /**
     * pi {@code agent.state.messages} 的读口（批 13，sdk.ts:370-377 对位）：lane 工作副本
     * 的<b>活视图</b>——run 推进中引擎只追加（含 {@code set(last)} 替换在飞 assistant），
     * 修改抛 {@link UnsupportedOperationException}。
     *
     * <p>⚠️ <b>前缀恒等比较（cache-warmer isCurrent）必须先在捕获点拍快照</b>
     * （{@code List.copyOf}）：{@code lane.messages} 是 final 字段，压缩/树导航/
     * resume/reset 全走<b>就地</b> {@code clear()+addAll()}
     * （{@code HarnessUtils.rebuildLaneMessages}）——直接持有本视图会让「捕获 vs
     * 当前」两侧包装同一存储，比对恒真（批 13 评审 C1）。</p>
     *
     * <p>竞态注记：本视图由引擎线程支改、读侧可能是 warmer 调度线程 ⇒ 快照可能
     * 拍到追加中间态。后果方向安全：isCurrent 误判 false ⇒ 提前停 warm（pi 的
     * isCurrent 本就是 best-effort 闸门）。</p>
     */
    public List<com.pijava.ai.message.Message> getMessages() {
        return java.util.Collections.unmodifiableList(lane.messages);
    }

    /**
     * pi {@code SessionManager._appendEntry} 的双态对位（批 13）：委托
     * {@link LaneState#appendDeferredEntry}——run 中记 deferred write（与
     * {@code PiLaneSink} 的 assistant usage 条目同生命周期），idle 纯追加
     * （{@code HarnessUtils.recordDeferredWrite} 自带 idle 门）。带锁。
     * 不重建 working messages（usage/custom 类条目不参与投影）。
     */
    public <E extends Entry> E appendDeferredLaneEntry(
            String laneName, LaneState.EntryFactory<E> factory) {
        return requireLane(laneName).appendDeferredEntry(factory);
    }

    /**
     * 批 26（docs/37 T2）：pi {@code _refreshFinalizedContext}
     * （agent-session.ts:938-944）对位——从转录投影整体重建工作副本。
     * 与 {@code replaceTranscript} 不同：不动转录，且<b>运行中可用</b>（pi 在
     * turn_end/边界 flush 点于 run 中途整体替换 {@code agent.state.messages}；
     * java 的调用点同样在运行线程上、循环停在事件发射里，无并发窗口）。
     */
    public void refreshLaneMessages(String laneName) {
        HarnessUtils.rebuildLaneMessages(requireLane(laneName));
    }

    /**
     * footer token 计数入口（批 13 T5）：委托
     * {@link ExecutionContext.TokenCounter}——与 {@code PiLaneSink} 车道内记账
     * 同一计数器。生产者：cache-warmer 的暖用量条目（pi getSessionStats 的
     * usage 条目臂对位，footer 计入暖 token）。
     */
    public void addTokens(long tokens) {
        tokenCounter.add(tokens);
    }

    /**
     * Restore the active tool set from the lane's latest system message after tree
     * navigation（#77；pi {@code _restoreToolsFromTranscript}，
     * agent-session.ts:1809-1820，调用点 {@code navigateTree:4138}）。
     * 须在 {@link #replaceTranscript} 之后调用（pi :4136-4138 同序）；
     * 无系统消息 ⇒ 不动。
     */
    public void restoreToolsFromTranscript(String laneName) {
        runLifecycle.restoreToolsFromTranscript(laneName);
    }

    /** Load a lane's persisted record log on resume; the lane comes back idle (原 docs/30 §4.1). */
    public void restoreRecords(String laneName, List<LaneRecord> records) {
        if (closed) throw new HarnessClosedException();
        runLifecycle.restoreRecords(laneName, records);
    }

    /**
     * 装饰 {@code agent_end.willRetry} 的公开入口（pi {@code _willRetryAfterAgentEnd}，
     * {@code agent-session.ts:721-733}；3d，{@code 原 docs/31 §8.22}）。宿主的用法与 pi
     * 一致：收到一次 {@code agent_end}，倒扫<b>它自带的 messages</b> 找最后一条助手
     * 消息，再调本法。真实的摘尾/重跑发生在引擎的 post-run ①（{@code checkAfterRun}），
     * 本法只答「会不会重试」，与 ① 各算各的、互不共享缓存。
     */
    public boolean retryWouldFollow(com.pijava.ai.message.Message.AssistantMessage lastAssistant) {
        return piEngine.retryWouldFollow(lastAssistant);
    }

    /** Return the final assistant message from the most recent run (default lane). */
    public AssistantMessage lastAssistantMessage() {
        return lane.partial;
    }

    // ═══════════════════════════════════════════════════════════
    // Compaction
    // ═══════════════════════════════════════════════════════════

    /** Run a compaction on the default lane. */
    public com.pijava.agent.compaction.CompactionResult compact(CompactionSettings settings) {
        return compact(DEFAULT_LANE, settings);
    }

    /** Run a compaction on the specified lane（旧式调用，无 customInstructions）。 */
    public com.pijava.agent.compaction.CompactionResult compact(
            String laneName, CompactionSettings settings) {
        return compact(laneName, settings, null);
    }

    /**
     * Run a compaction on the specified lane with customInstructions (B174,
     * {@code docs/23}；pi {@code AgentSession.compact(customInstructions)}).
     */
    public com.pijava.agent.compaction.CompactionResult compact(
            String laneName, CompactionSettings settings, String customInstructions) {
        if (closed) throw new HarnessClosedException();
        return runLifecycle.compact(laneName, settings, customInstructions);
    }

    /**
     * Whether a compaction is in flight on this lane（pi {@code AgentSession.isCompacting}，
     * {@code agent-session.ts:983-990}；包④，{@code 原 docs/31 §8.37}）。
     *
     * <p>pi 的三控制器析取在 pi-java 收敛成一个窗口 —— 由
     * {@code CompactionExecutor} 的两个入口置/清（{@link LaneState#isCompacting()}）。
     * 唯一的消费者是 RPC {@code get_state.isCompacting}。</p>
     */
    public boolean isCompacting(String laneName) {
        return requireLane(laneName).isCompacting();
    }

    /**
     * Whether an agent run is active on this lane（A5，{@code 原 docs/25}；
     * pi {@code isStreaming}，{@code agent-session.ts:1430-1432}）。宿主并发
     * prompt 路由门读它；与 {@link #isCompacting} 同形。
     */
    public boolean isRunning(String laneName) {
        return requireLane(laneName).isRunning();
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

    // ═══════════════════════════════════════════════════════════
    // Model / Thinking / Tools
    // ═══════════════════════════════════════════════════════════

    public ModelId<?> getModel() { return lane.model; }
    /** Set the model used for subsequent LLM calls（同时把变更写进 transcript，见 §4.1）. */
    public void setModel(ModelId<?> model) {
        if (closed) throw new HarnessClosedException();
        lane.model = model;
        runLifecycle.recordModelChange(DEFAULT_LANE, model);
    }
    public ModelThinkingLevel getThinkingLevel() { return lane.thinkingLevel; }
    /** Set the thinking level used for subsequent LLM calls（变更时才写 entry，见 §4.1）. */
    public void setThinkingLevel(ModelThinkingLevel level) {
        if (closed) throw new HarnessClosedException();
        lane.thinkingLevel = level;
        runLifecycle.recordConfigChanged(DEFAULT_LANE);
    }

    /** Change the system prompt for subsequent runs. */
    public void setSystemPrompt(String prompt) {
        if (closed) throw new HarnessClosedException();
        lane.systemPrompt = prompt;
    }

    /** The current system prompt. */
    public String getSystemPrompt() {
        return lane.systemPrompt;
    }

    /**
     * 系统提示 {@code rules} 段的追加准则条目（包 A4b）。
     *
     * <p>pi 的同一件事是 {@code BuildSystemPromptOptions.promptGuidelines} —— 它是**内容**
     * （表达风格之类），因此由宿主给，而不是由本模块硬编码。</p>
     */
    public void setPromptGuidelines(List<String> guidelines) {
        if (closed) throw new HarnessClosedException();
        lane.promptGuidelines = guidelines == null ? List.of() : List.copyOf(guidelines);
    }

    /** 追加提示（包 A4b；pi 的 {@code appendSystemPrompt}，渲染成 {@code addendum} 段）。 */
    public void setAppendSystemPrompt(String text) {
        if (closed) throw new HarnessClosedException();
        lane.appendSystemPrompt = text == null ? "" : text;
    }

    /** 当前追加提示（包 A4b）。 */
    public String getAppendSystemPrompt() {
        return lane.appendSystemPrompt;
    }

    /** 当前系统提示 {@code rules} 段的追加准则条目（包 A4b）。 */
    public List<String> getPromptGuidelines() {
        return List.copyOf(lane.promptGuidelines);
    }

    public Set<AgentTool<?, ?>> getActiveTools() {
        return Set.copyOf(lane.activeTools);
    }

    /**
     * 替换激活工具集（pi {@code _applyToolLoadout} 只写 {@code agent.state.tools}
     * 的对位，agent-session.ts:1575-1579）。
     *
     * <p>批 16（T2a，裁决①）：<b>注册面与激活面分离</b>——本方法不再清/重填
     * {@code toolRegistry}（旧语义会把扩展后注册的工具从注册表抹掉）。</p>
     */
    public void setActiveTools(Set<AgentTool<?, ?>> tools) {
        if (closed) throw new HarnessClosedException();
        lane.activeTools = Set.copyOf(tools);
    }

    /**
     * 按名并入激活集（批 16 T2d：pi 的「注册即激活」臂，
     * agent-session.ts:3603 对位）——注册表查无的名静默忽略。
     */
    public void activateTools(java.util.Collection<String> names) {
        if (closed) throw new HarnessClosedException();
        if (toolRegistry == null) {
            return;
        }
        var next = new java.util.LinkedHashSet<>(lane.activeTools);
        for (var name : names) {
            var tool = toolRegistry.get(name);
            if (tool != null) {
                next.add(tool);
            }
        }
        lane.activeTools = Set.copyOf(next);
    }

    /**
     * 批 24（docs/35 D4）：pi {@code _pendingToolNames} 的宿主访问器
     * （lane 级集合；线程纪律同 {@code activeTools}——车道装配/事件线程）。
     */
    public java.util.Set<String> pendingToolNames() {
        return lane.pendingToolNames;
    }

    /**
     * 批 16（T5b）：pi {@code syncQueueModesFromSettings} 对位——/reload 后
     * steer/followUp 排空模式随设置（pi agent-session.ts:3679 同位）。
     */
    public void setQueueModes(QueueMode steering, QueueMode followUp) {
        if (closed) throw new HarnessClosedException();
        lane.steeringMode = steering;
        lane.followUpMode = followUp;
    }

    /**
     * 系统提示自定义段（批 16 T3a；pi {@code BuildSystemPromptOptions.sections}）。
     * 唯一生产者是扩展 {@code before_agent_start} 的写回。
     */
    public void setPromptSections(java.util.Map<String, String> sections) {
        if (closed) throw new HarnessClosedException();
        lane.promptSections = sections == null ? java.util.Map.of() : java.util.Map.copyOf(sections);
    }

    /** 当前系统提示自定义段（批 16 T3a）。 */
    public java.util.Map<String, String> getPromptSections() {
        return lane.promptSections;
    }

    // ═══════════════════════════════════════════════════════════
    // Close
    // ═══════════════════════════════════════════════════════════

    @Override
    public void close() {
        closed = true;
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
