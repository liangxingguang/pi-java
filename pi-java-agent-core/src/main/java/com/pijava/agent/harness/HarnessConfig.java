package com.pijava.agent.harness;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.pijava.agent.compaction.CompactionSettings;
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
 * <p>Phase 2c: added driveMode, compactionSettings fields.
 * Phase 3: added steeringMode, followUpMode, toolExecution fields.</p>
 *
 * @param streamFn           LLM streaming call function
 * @param model              current model identifier
 * @param thinkingLevel      thinking mode (off or enabled at a level)
 * @param systemPrompt       system prompt (Phase 2a: fixed string)
 * @param activeTools        active tool set (Phase 2b: AgentTool instances)
 * @param maxInputTokens     maximum input tokens for overflow detection
 * @param toolRegistry       tool registry for the harness
 * @param toolContext        execution environment for tools
 * @param commandPrefix      optional prefix for bash commands
 * @param driveMode          drive mode (default: MANUAL)
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
 */
public record HarnessConfig(
    StreamFn streamFn,
    ModelId<?> model,
    ModelThinkingLevel thinkingLevel,
    String systemPrompt,
    Set<AgentTool<?, ?>> activeTools,
    int maxInputTokens,
    ToolRegistry toolRegistry,
    ToolContext toolContext,
    String commandPrefix,
    DriveMode driveMode,
    CompactionSettings compactionSettings,
    Map<String, Skill> skills,
    RetryPolicy retryPolicy,
    TelemetryContext telemetry,
    ThinkingLevelMap thinkingLevelMap,
    QueueMode steeringMode,
    QueueMode followUpMode,
    ToolExecution toolExecution,
    Consumer<StreamEvent> streamListener
) {
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private StreamFn streamFn;
        private ModelId<?> model;
        private ModelThinkingLevel thinkingLevel = ModelThinkingLevel.off();
        private String systemPrompt = "";
        private Set<AgentTool<?, ?>> activeTools = Set.of();
        private int maxInputTokens = 200_000;
        private ToolRegistry toolRegistry;
        private ToolContext toolContext;
        private String commandPrefix;
        private DriveMode driveMode = DriveMode.MANUAL;
        private CompactionSettings compactionSettings;
        private Map<String, Skill> skills = Map.of();
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private TelemetryContext telemetry = NoopTelemetryContext.INSTANCE;
        private ThinkingLevelMap thinkingLevelMap = ThinkingLevelMap.empty();
        private QueueMode steeringMode = QueueMode.defaultMode();
        private QueueMode followUpMode = QueueMode.defaultMode();
        private ToolExecution toolExecution = ToolExecution.defaultMode();
        private Consumer<StreamEvent> streamListener = event -> { };

        public Builder streamFn(StreamFn fn) { this.streamFn = fn; return this; }
        public Builder model(ModelId<?> m) { this.model = m; return this; }
        public Builder thinkingLevel(ModelThinkingLevel tl) { this.thinkingLevel = tl; return this; }
        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        public Builder activeTools(Set<AgentTool<?, ?>> at) {
            this.activeTools = Set.copyOf(at); return this;
        }
        public Builder maxInputTokens(int mit) { this.maxInputTokens = mit; return this; }
        public Builder toolRegistry(ToolRegistry tr) { this.toolRegistry = tr; return this; }
        public Builder toolContext(ToolContext tc) { this.toolContext = tc; return this; }
        public Builder commandPrefix(String cp) { this.commandPrefix = cp; return this; }
        public Builder driveMode(DriveMode dm) { this.driveMode = dm; return this; }
        public Builder compactionSettings(CompactionSettings cs) { this.compactionSettings = cs; return this; }
        public Builder skills(Map<String, Skill> s) {
            this.skills = Map.copyOf(s); return this;
        }
        public Builder retryPolicy(RetryPolicy rp) { this.retryPolicy = rp; return this; }
        public Builder telemetry(TelemetryContext t) { this.telemetry = t; return this; }
        public Builder thinkingLevelMap(ThinkingLevelMap tlm) { this.thinkingLevelMap = tlm; return this; }
        public Builder steeringMode(QueueMode mode) { this.steeringMode = mode; return this; }
        public Builder followUpMode(QueueMode mode) { this.followUpMode = mode; return this; }
        public Builder toolExecution(ToolExecution mode) { this.toolExecution = mode; return this; }
        public Builder streamListener(Consumer<StreamEvent> listener) {
            this.streamListener = listener; return this;
        }

        public HarnessConfig build() {
            if (streamFn == null) throw new IllegalStateException("streamFn is required");
            if (model == null) throw new IllegalStateException("model is required");
            return new HarnessConfig(streamFn, model, thinkingLevel,
                                     systemPrompt, activeTools, maxInputTokens,
                                     toolRegistry, toolContext, commandPrefix,
                                     driveMode, compactionSettings, skills,
                                     retryPolicy, telemetry, thinkingLevelMap,
                                     steeringMode, followUpMode, toolExecution,
                                     streamListener);
        }
    }
}
