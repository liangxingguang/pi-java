package com.pijava.agent.skill;

import com.pijava.ai.api.ToolDefinition;
import java.util.List;

/**
 * A named skill that can be loaded into the agent's context.
 * Aligned with pi's Skill interface.
 */
public interface Skill {
    /** Unique skill name (e.g. "code-review", "tdd"). */
    String name();

    /** Human-readable label. */
    String label();

    /** Description shown to the LLM. */
    String description();

    /** Get the system prompt fragment for this skill. */
    String systemPrompt();

    /** Optional tool definitions contributed by this skill. */
    default List<ToolDefinition> tools() {
        return List.of();
    }
}
