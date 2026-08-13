package com.pijava.agent.harness;

import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolExecutor;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * Bundles harness-level dependencies for {@link ActionExecutor}.
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
    ToolRegistry toolRegistry,
    ToolContext toolContext,
    ToolExecutor toolExecutor,
    SkillManager skillManager,
    HookSystem hookSystem,
    ConcurrentMap<String, LaneState> lanes,
    Supplier<CompactionSettings> compactionSettings,
    ThinkingLevelMap thinkingLevelMap,
    TokenCounter tokenCounter,
    SnapshotService snapshotService,
    QueueManager queueManager,
    Supplier<ToolExecution> toolExecution,
    Supplier<Consumer<StreamEvent>> streamListener
) {
    LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lanes, laneName);
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
