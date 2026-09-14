package com.pijava.agent.harness;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.telemetry.TelemetryContext;

/**
 * Bundles harness-level dependencies for the run machinery
 * ({@link RunLifecycle} / {@link PiLaneEngine} / {@link PiLaneSink} /
 * {@link ContextAssembler} / {@link CompactionExecutor}).
 *
 * <p>Package-private — only {@code AgentHarness} creates this.
 * Introduced in Phase 2c to reduce constructor parameter count.</p>
 *
 * <p>Mutable harness configuration (model, thinking level, active tools,
 * system prompt, compaction settings) is exposed via {@link Supplier} so
 * setters on {@code AgentHarness} take effect on subsequent runs — the
 * executor always reads the current value, not a construction-time snapshot.</p>
 */
record ExecutionContext(
    StreamFn streamFn,
    Supplier<ModelId<?>> model,
    Supplier<ModelThinkingLevel> thinkingLevel,
    Supplier<String> systemPrompt,
    Supplier<Set<AgentTool<?, ?>>> activeTools,
    int maxInputTokens,
    java.util.function.ToIntFunction<ModelId<?>> contextWindow,
    java.util.function.ToIntFunction<ModelId<?>> maxOutputTokens,
    ToolRegistry toolRegistry,
    ToolContext toolContext,
    SkillManager skillManager,
    HookSystem hookSystem,
    LaneState lane,
    Supplier<CompactionSettings> compactionSettings,
    ThinkingLevelMap thinkingLevelMap,
    TokenCounter tokenCounter,
    SnapshotService snapshotService,
    QueueManager queueManager,
    Supplier<ToolExecution> toolExecution,
    Supplier<Consumer<StreamEvent>> streamListener,
    SummaryGenerator summaryGenerator,
    java.util.function.BiConsumer<ModelId<?>, String> turnConfigApplier,
    TelemetryContext telemetry,
    com.pijava.agent.compaction.CompactionObserver compactionObserver,
    Supplier<RetrySettings> retrySettings,
    BooleanSupplier retryAborted,
    RetryObserver retryObserver
) {
    ExecutionContext {
        // 与 HarnessConfig 的规范默认同置：直接构造 ExecutionContext 的装配（测试、
        // 未来的第二宿主）不许从这些槽读到 null（3d 起含 retrySettings/
        // retryAborted/retryObserver 三槽——pi 的 getRetrySettings() ?? 链即默认值）。
        if (maxOutputTokens == null) {
            maxOutputTokens = ignored -> 0;
        }
        if (compactionObserver == null) {
            compactionObserver = com.pijava.agent.compaction.CompactionObserver.NOOP;
        }
        if (retrySettings == null) {
            retrySettings = RetrySettings::defaults;
        }
        if (retryAborted == null) {
            retryAborted = () -> false;
        }
        if (retryObserver == null) {
            retryObserver = RetryObserver.NOOP;
        }
    }

    LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lane, laneName);
    }

    /**
     * pi {@code model.contextWindow} —— 阈值压缩门的操作数（3b，
     * {@code agent-session.ts:547-549}）。按**当前**模型解析，切换模型后
     * 随之变化；解析不到元数据 ⇒ 0 ⇒ 门的 {@code <= 0} 守卫跳过自动压缩。
     */
    int contextWindow(ModelId<?> model) {
        return contextWindow.applyAsInt(model);
    }

    /**
     * pi {@code model.maxTokens} —— 溢出恢复的 {@code isRecoverableLength} 操作数
     * （3c，{@code docs/31 §8.21}；{@code agent-session.ts:2184}）。按当前模型解析；
     * 解析不到 ⇒ 0 ⇒ 判据恒 false（裁决④）。
     */
    int maxOutputTokens(ModelId<?> model) {
        return maxOutputTokens.applyAsInt(model);
    }

    void addTokens(long tokens) {
        tokenCounter.add(tokens);
    }

    void incrementTurn() {
        tokenCounter.incrementTurn();
    }

    void publishState(String laneName) {
        snapshotService.publishState(laneName);
    }

    /**
     * Mutable token counter shared between AgentHarness and ActionExecutor.
     * Used to track total tokens for SessionSnapshot.
     */
    static final class TokenCounter {
        private long totalTokens;
        private int turnCount;

        void add(long tokens) {
            totalTokens += tokens;
        }

        void incrementTurn() {
            turnCount++;
        }

        long totalTokens() { return totalTokens; }

        int turnCount() { return turnCount; }
    }
}
