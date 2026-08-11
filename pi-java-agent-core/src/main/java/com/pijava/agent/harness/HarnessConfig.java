package com.pijava.agent.harness;

import java.util.Set;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Configuration for creating an {@link AgentHarness}.
 *
 * <p>Aligned with pi's {@code AgentHarnessOptions}. The {@code StreamFn}
 * is injected here so the harness (and its internal {@code AgentLoop})
 * never needs to know about HTTP or provider details.</p>
 *
 * @param streamFn       LLM streaming call function
 * @param model          current model identifier
 * @param thinkingLevel  thinking mode (off or enabled at a level)
 * @param systemPrompt   system prompt (Phase 2a: fixed string)
 * @param activeTools    active tool set (Phase 2b: AgentTool instances)
 * @param maxInputTokens maximum input tokens for overflow detection
 * @param toolRegistry   tool registry for the harness (Phase 2b)
 * @param toolContext    execution environment for tools (Phase 2b)
 * @param commandPrefix  optional prefix for bash commands (Phase 2b)
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
    String commandPrefix
) {
    public HarnessConfig {
        activeTools = Set.copyOf(activeTools);
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

        public HarnessConfig build() {
            if (streamFn == null) throw new IllegalStateException("streamFn is required");
            if (model == null) throw new IllegalStateException("model is required");
            return new HarnessConfig(streamFn, model, thinkingLevel,
                                     systemPrompt, activeTools, maxInputTokens,
                                     toolRegistry, toolContext, commandPrefix);
        }
    }
}
