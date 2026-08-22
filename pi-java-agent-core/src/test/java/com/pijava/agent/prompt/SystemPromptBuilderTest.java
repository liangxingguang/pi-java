package com.pijava.agent.prompt;

import java.util.List;
import java.util.Map;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptBuilderTest {

    private static AgentTool<String, Void> tool(String name, String desc) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return desc; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, com.pijava.agent.tool.ToolContext ctx) {
                return ToolResult.success("ok");
            }
        };
    }

    private static Skill skill(String name, String prompt) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "desc"; }
            @Override public String systemPrompt() { return prompt; }
        };
    }

    @Test
    void baseOnlyProducesTemplate() {
        var prompt = new SystemPromptBuilder().base("You are a helpful assistant.").build();
        assertThat(prompt).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void toolsSectionListsToolDescriptions() {
        var prompt = new SystemPromptBuilder()
                .tools(List.of(tool("read", "Read a file")))
                .build();
        assertThat(prompt).contains("## Available Tools");
        assertThat(prompt).contains("- **read**: Read a file");
    }

    @Test
    void toolsSectionUsesPromptSnippetWhenPresent() {
        var prompt = new SystemPromptBuilder()
                .tools(List.of(renderTool("snippet", "Short snippet", List.of())))
                .build();
        assertThat(prompt).contains("- **snippet**: Short snippet");
        assertThat(prompt).doesNotContain("full description");
    }

    @Test
    void toolsSectionAppendsPromptGuidelines() {
        var prompt = new SystemPromptBuilder()
                .tools(List.of(renderTool("guide", "", List.of("Always return JSON"))))
                .build();
        assertThat(prompt).contains("- Always return JSON");
    }

    /** AgentTool carrying pi {@code promptSnippet}/{@code promptGuidelines} render metadata. */
    private static AgentTool<String, Void> renderTool(String name, String snippet, List<String> guidelines) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return name + " full description"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String promptSnippet() { return snippet; }
            @Override public List<String> promptGuidelines() { return guidelines; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, com.pijava.agent.tool.ToolContext ctx) {
                return ToolResult.success("ok");
            }
        };
    }

    @Test
    void skillsSectionListsSkillPrompts() {
        var prompt = new SystemPromptBuilder()
                .skills(List.of(skill("tdd", "Write tests first.")))
                .build();
        assertThat(prompt).contains("## Active Skills");
        assertThat(prompt).contains("Write tests first.");
    }

    @Test
    void instructionsAppended() {
        var prompt = new SystemPromptBuilder()
                .instructions("Be concise.")
                .build();
        assertThat(prompt).isEqualTo("Be concise.");
    }

    @Test
    void emptyBuildReturnsEmptyString() {
        assertThat(new SystemPromptBuilder().build()).isEmpty();
    }

    @Test
    void combinedBuildIncludesAllSections() {
        var prompt = new SystemPromptBuilder()
                .base("Base prompt")
                .tools(List.of(tool("bash", "Run a command")))
                .skills(List.of(skill("code-review", "Review code.")))
                .build();
        assertThat(prompt).contains("Base prompt");
        assertThat(prompt).contains("## Available Tools");
        assertThat(prompt).contains("## Active Skills");
    }
}
