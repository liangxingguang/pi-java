package com.pijava.agent.prompt;

import java.util.Collection;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.tool.AgentTool;

/**
 * Builder for constructing the agent's system prompt from components.
 * Centralizes prompt assembly: base template + active tools + active skills + custom instructions.
 */
public final class SystemPromptBuilder {

    private final StringBuilder sb = new StringBuilder();

    /** Append a base template. */
    public SystemPromptBuilder base(String template) {
        sb.append(template).append("\n\n");
        return this;
    }

    /** Append tool descriptions for the given tools. */
    public SystemPromptBuilder tools(Collection<AgentTool<?, ?>> tools) {
        if (tools.isEmpty()) return this;
        sb.append("## Available Tools\n\n");
        for (var t : tools) {
            sb.append("- **").append(t.name()).append("**: ")
              .append(t.description()).append("\n");
        }
        sb.append("\n");
        return this;
    }

    /** Append skill system prompts (excludes {@code disableModelInvocation} skills). */
    public SystemPromptBuilder skills(Collection<Skill> skills) {
        var active = skills.stream()
            .filter(s -> !s.disableModelInvocation())
            .toList();
        if (active.isEmpty()) return this;
        sb.append("## Active Skills\n\n");
        for (var s : active) {
            sb.append(s.systemPrompt()).append("\n");
        }
        sb.append("\n");
        return this;
    }

    /** Append custom instructions. */
    public SystemPromptBuilder instructions(String text) {
        if (text != null && !text.isEmpty()) {
            sb.append(text).append("\n");
        }
        return this;
    }

    /** Build the final system prompt string. */
    public String build() {
        return sb.toString().stripTrailing();
    }
}
