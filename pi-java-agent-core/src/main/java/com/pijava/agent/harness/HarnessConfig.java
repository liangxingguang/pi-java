package com.pijava.agent.harness;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.skill.Skill;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.http.RetryPolicy;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.telemetry.NoopTelemetryContext;
import com.pijava.telemetry.TelemetryContext;

/**
 * Configuration for creating an {@link AgentHarness}.
 *
 * <p>Phase 2c: added compactionSettings. Phase 3: added steeringMode, followUpMode,
 * toolExecution. {@code driveMode} was removed with the step chain — the harness
 * has exactly one driver now ({@code docs/31 §6}).</p>
 *
 * @param streamFn           LLM streaming call function
 * @param model              current model identifier
 * @param thinkingLevel      thinking mode (off or enabled at a level)
 * @param systemPrompt       system prompt (Phase 2a: fixed string)
 * @param activeTools        active tool set (Phase 2b: AgentTool instances)
 * @param maxInputTokens     maximum input tokens for overflow detection
 * @param contextWindow      pi {@code model.contextWindow} 的操作数来源（3b，
 *                           {@code docs/31 §8.20}）：按<b>当前</b>模型查其上下文
 *                           窗口大小（阈值压缩读的正是这个值，模型切换后随之变）。
 *                           默认 {@code ignored -> maxInputTokens}（静态回退，
 *                           与 3b 之前的行为一致）；宿主装配时传目录查询
 *                           （coding-agent：catalog 的 {@code maxInputTokens}
 *                           即上下文窗口）。返回 0/负值 ⇒ pi 的
 *                           {@code contextWindow <= 0} 守卫跳过自动压缩。
 * @param maxOutputTokens    pi {@code Model.maxTokens} 的操作数来源（3c，
 *                           {@code docs/31 §8.21}）：按<b>当前</b>模型查其输出上限，
 *                           {@code isRecoverableLength} 的「钳制前意图上限」判据读它。
 *                           默认 {@code ignored -> 0} —— 解析不到 ⇒ 0 ⇒ 判据恒
 *                           false（裁决④：length 收尾不做 compact-and-retry）。
 * @param toolRegistry       tool registry for the harness
 * @param toolContext        execution environment for tools
 * @param commandPrefix      optional prefix for bash commands
 * @param compactionSettings compaction settings (null = no auto-compaction)
 * @param skills             named skills to register (default: empty)
 * @param retryPolicy        retry policy for the LLM HTTP client (default: default policy)
 * @param telemetry          telemetry context (default: no-op)
 * @param thinkingLevelMap   per-model thinking translation (default: empty = no thinking)
 * @param steeringMode       how steer-queue messages are drained (default: one-at-a-time)
 * @param followUpMode       how follow-up-queue messages are drained (default: one-at-a-time)
 * @param toolExecution      tool execution mode for multi-tool turns (default: parallel)
 * @param streamListener     receives every StreamEvent as the harness consumes
 *                           it (default: no-op; Phase 3 TUI/print streaming)
 * @param summaryGenerator   generates the compaction summary (default: truncating)
 * @param compactionObserver pi {@code compaction_start}/{@code compaction_end} 会话
 *                           事件的宿主观察口（3c）；默认 {@code NOOP}
 * @param retrySettings      自动重试设置的晚读口（3d，{@code docs/31 §8.22}）：
 *                           两环（post-run ① 与摘要重试）每次判定都重读，宿主
 *                           setter 即时生效（pi {@code getRetrySettings()}）。
 *                           默认 {@code RetrySettings::defaults}（pi 的 ?? 链）
 * @param retryAborted       退避睡眠的中止观察口（3d）；pi 是 AbortController
 *                           信号，Java 方言为每 50ms 轮询本谓词。默认恒 false
 * @param retryObserver      {@code auto_retry_*} / {@code summarization_retry_*}
 *                           会话事件的宿主观察口（3d）；默认 {@code NOOP}
 */
public record HarnessConfig(
    StreamFn streamFn,
    ModelId<?> model,
    ModelThinkingLevel thinkingLevel,
    String systemPrompt,
    Set<AgentTool<?, ?>> activeTools,
    int maxInputTokens,
    java.util.function.ToIntFunction<ModelId<?>> contextWindow,
    java.util.function.ToIntFunction<ModelId<?>> maxOutputTokens,
    ToolRegistry toolRegistry,
    ToolContext toolContext,
    String commandPrefix,
    CompactionSettings compactionSettings,
    Map<String, Skill> skills,
    RetryPolicy retryPolicy,
    TelemetryContext telemetry,
    ThinkingLevelMap thinkingLevelMap,
    QueueMode steeringMode,
    QueueMode followUpMode,
    ToolExecution toolExecution,
    Consumer<StreamEvent> streamListener,
    SummaryGenerator summaryGenerator,
    com.pijava.agent.compaction.CompactionObserver compactionObserver,
    Supplier<RetrySettings> retrySettings,
    BooleanSupplier retryAborted,
    RetryObserver retryObserver
) {
    /** Canonical constructor applying default values and defensive copies. */
    public HarnessConfig {
        activeTools = Set.copyOf(activeTools);
        skills = Map.copyOf(skills);
        if (retryPolicy == null) retryPolicy = RetryPolicy.defaultPolicy();
        if (telemetry == null) telemetry = NoopTelemetryContext.INSTANCE;
        if (thinkingLevelMap == null) thinkingLevelMap = ThinkingLevelMap.empty();
        if (steeringMode == null) steeringMode = QueueMode.defaultMode();
        if (followUpMode == null) followUpMode = QueueMode.defaultMode();
        if (toolExecution == null) toolExecution = ToolExecution.defaultMode();
        if (streamListener == null) streamListener = event -> { };
        if (summaryGenerator == null) summaryGenerator = SummaryGenerator.truncating();
        if (compactionObserver == null) compactionObserver = com.pijava.agent.compaction.CompactionObserver.NOOP;
        if (contextWindow == null) contextWindow = ignored -> maxInputTokens;
        if (maxOutputTokens == null) maxOutputTokens = ignored -> 0;
        if (retrySettings == null) retrySettings = RetrySettings::defaults;
        if (retryAborted == null) retryAborted = () -> false;
        if (retryObserver == null) retryObserver = RetryObserver.NOOP;
    }

    /** Create a new configuration builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** 18-参便利构造（测试/旧路径）；summary 默认 {@code truncating()}。 */
    public HarnessConfig(
            StreamFn streamFn, ModelId<?> model, ModelThinkingLevel thinkingLevel,
            String systemPrompt, Set<AgentTool<?, ?>> activeTools, int maxInputTokens,
            ToolRegistry toolRegistry, ToolContext toolContext, String commandPrefix,
            CompactionSettings compactionSettings,
            Map<String, Skill> skills, RetryPolicy retryPolicy, TelemetryContext telemetry,
            ThinkingLevelMap thinkingLevelMap, QueueMode steeringMode,
            QueueMode followUpMode, ToolExecution toolExecution,
            Consumer<StreamEvent> streamListener) {
        this(streamFn, model, thinkingLevel, systemPrompt, activeTools, maxInputTokens,
             null, null, toolRegistry, toolContext, commandPrefix, compactionSettings,
             skills, retryPolicy, telemetry, thinkingLevelMap, steeringMode,
             followUpMode, toolExecution, streamListener, SummaryGenerator.truncating(),
             null, null, null, null);
    }

    public static final class Builder {
        private StreamFn streamFn;
        private ModelId<?> model;
        private ModelThinkingLevel thinkingLevel = ModelThinkingLevel.off();
        private String systemPrompt = "";
        private Set<AgentTool<?, ?>> activeTools = Set.of();
        private int maxInputTokens = 200_000;
        private java.util.function.ToIntFunction<ModelId<?>> contextWindow;
        private java.util.function.ToIntFunction<ModelId<?>> maxOutputTokens;
        private ToolRegistry toolRegistry;
        private ToolContext toolContext;
        private String commandPrefix;
        private CompactionSettings compactionSettings;
        private Map<String, Skill> skills = Map.of();
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private TelemetryContext telemetry = NoopTelemetryContext.INSTANCE;
        private ThinkingLevelMap thinkingLevelMap = ThinkingLevelMap.empty();
        private QueueMode steeringMode = QueueMode.defaultMode();
        private QueueMode followUpMode = QueueMode.defaultMode();
        private ToolExecution toolExecution = ToolExecution.defaultMode();
        private Consumer<StreamEvent> streamListener = event -> { };
        private SummaryGenerator summaryGenerator = SummaryGenerator.truncating();
        private com.pijava.agent.compaction.CompactionObserver compactionObserver;
        private Supplier<RetrySettings> retrySettings;
        private BooleanSupplier retryAborted;
        private RetryObserver retryObserver;

        public Builder streamFn(StreamFn fn) { this.streamFn = fn; return this; }
        public Builder model(ModelId<?> m) { this.model = m; return this; }
        public Builder thinkingLevel(ModelThinkingLevel tl) { this.thinkingLevel = tl; return this; }
        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        /** Set the active tools; returns {@code this} for chaining. */
        public Builder activeTools(Set<AgentTool<?, ?>> at) {
            this.activeTools = Set.copyOf(at); return this;
        }
        public Builder maxInputTokens(int mit) { this.maxInputTokens = mit; return this; }
        /** Set the per-model context-window resolver (3b; default: {@code id -> maxInputTokens}). */
        public Builder contextWindow(java.util.function.ToIntFunction<ModelId<?>> cw) {
            this.contextWindow = cw; return this;
        }
        /** Set the per-model max-output resolver (3c; default: {@code id -> 0}). */
        public Builder maxOutputTokens(java.util.function.ToIntFunction<ModelId<?>> mo) {
            this.maxOutputTokens = mo; return this;
        }
        public Builder toolRegistry(ToolRegistry tr) { this.toolRegistry = tr; return this; }
        public Builder toolContext(ToolContext tc) { this.toolContext = tc; return this; }
        public Builder commandPrefix(String cp) { this.commandPrefix = cp; return this; }
        public Builder compactionSettings(CompactionSettings cs) { this.compactionSettings = cs; return this; }
        /** Set the named skills to register; returns {@code this} for chaining. */
        public Builder skills(Map<String, Skill> s) {
            this.skills = Map.copyOf(s); return this;
        }
        public Builder retryPolicy(RetryPolicy rp) { this.retryPolicy = rp; return this; }
        public Builder telemetry(TelemetryContext t) { this.telemetry = t; return this; }
        public Builder thinkingLevelMap(ThinkingLevelMap tlm) { this.thinkingLevelMap = tlm; return this; }
        public Builder steeringMode(QueueMode mode) { this.steeringMode = mode; return this; }
        public Builder followUpMode(QueueMode mode) { this.followUpMode = mode; return this; }
        public Builder toolExecution(ToolExecution mode) { this.toolExecution = mode; return this; }
        /** Set the stream listener; returns {@code this} for chaining. */
        public Builder streamListener(Consumer<StreamEvent> listener) {
            this.streamListener = listener; return this;
        }

        /** Set the compaction summary generator (default: truncating placeholder). */
        public Builder summaryGenerator(SummaryGenerator generator) {
            this.summaryGenerator = generator; return this;
        }

        /** Set the compaction observer (3c; default: {@code NOOP}). */
        public Builder compactionObserver(com.pijava.agent.compaction.CompactionObserver observer) {
            this.compactionObserver = observer; return this;
        }

        /** Set the late-read retry settings supplier (3d; default: {@code RetrySettings::defaults}). */
        public Builder retrySettings(Supplier<RetrySettings> settings) {
            this.retrySettings = settings; return this;
        }

        /** Set the retry-backoff abort predicate (3d; default: never aborted). */
        public Builder retryAborted(BooleanSupplier aborted) {
            this.retryAborted = aborted; return this;
        }

        /** Set the retry observer (3d; default: {@code NOOP}). */
        public Builder retryObserver(RetryObserver observer) {
            this.retryObserver = observer; return this;
        }

        /** Build the {@link HarnessConfig}, validating required fields. */
        public HarnessConfig build() {
            if (streamFn == null) throw new IllegalStateException("streamFn is required");
            if (model == null) throw new IllegalStateException("model is required");
            return new HarnessConfig(streamFn, model, thinkingLevel,
                                     systemPrompt, activeTools, maxInputTokens,
                                     contextWindow, maxOutputTokens,
                                     toolRegistry, toolContext, commandPrefix,
                                     compactionSettings, skills,
                                     retryPolicy, telemetry, thinkingLevelMap,
                                     steeringMode, followUpMode, toolExecution,
                                     streamListener, summaryGenerator, compactionObserver,
                                     retrySettings, retryAborted, retryObserver);
        }
    }
}
