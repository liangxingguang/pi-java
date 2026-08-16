package com.pijava.agent.harness;

import java.util.Set;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Mutable run configuration for {@link AgentHarness} — the values governing
 * the next run (model, thinking, tools, drive/queue modes), as opposed to the
 * runtime machinery (lanes, executor, queues) held directly on the harness.
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
    DriveMode driveMode = DriveMode.MANUAL;
    CompactionSettings compactionSettings;
    QueueMode steeringMode;
    QueueMode followUpMode;
    ToolExecution toolExecution;
}
