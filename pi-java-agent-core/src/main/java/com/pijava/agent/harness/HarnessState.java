package com.pijava.agent.harness;

import java.util.Set;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Mutable run configuration for {@link AgentHarness} — the values governing
 * the next run (model, thinking, tools, queue modes), as opposed to the
 * runtime machinery (lanes, run lifecycle, queues) held directly on the harness.
 *
 * <p>Plain mutable fields mirror {@link LaneState}; the harness accessors
 * delegate here. A single object keeps the constructor's {@code () -> state.x}
 * captures pointing at one stable reference while the fields themselves change.</p>
 */
final class HarnessState {
    ModelId<?> model;
    ModelThinkingLevel thinkingLevel;
    String systemPrompt;
    Set<AgentTool<?, ?>> activeTools;
    CompactionSettings compactionSettings;
    QueueMode steeringMode;
    QueueMode followUpMode;
    ToolExecution toolExecution;

    /** 应用一次 {@code prepare_next_turn} 的配置更新（{@code null} 表示不改该项）。 */
    void applyTurn(ModelId<?> newModel, String thinkingLevelLabel) {
        if (newModel != null) {
            model = newModel;
        }
        if (thinkingLevelLabel != null) {
            thinkingLevel = "off".equals(thinkingLevelLabel)
                ? ModelThinkingLevel.off()
                : ModelThinkingLevel.of(parseThinkingLabel(thinkingLevelLabel));
        }
    }

    /** 标签 → 思考等级。包内可见：{@link PiLaneEngine} 把它用于 {@code prepareNextTurn}。 */
    static com.pijava.ai.thinking.ThinkingLevel parseThinkingLabel(String label) {
        return switch (label) {
            case "minimal" -> new com.pijava.ai.thinking.ThinkingLevel.Minimal();
            case "low" -> new com.pijava.ai.thinking.ThinkingLevel.Low();
            case "medium" -> new com.pijava.ai.thinking.ThinkingLevel.Medium();
            case "high" -> new com.pijava.ai.thinking.ThinkingLevel.High();
            case "xhigh" -> new com.pijava.ai.thinking.ThinkingLevel.XHigh();
            default -> throw new IllegalArgumentException("Unknown thinking level: " + label);
        };
    }
}
