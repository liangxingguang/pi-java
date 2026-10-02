package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

/**
 * Definition of a tool that the LLM can call.
 *
 * @param name            tool identifier
 * @param description     human-readable description
 * @param inputSchema     JSON Schema for the tool's arguments
 * @param label           human-readable label for UI display (pi {@code label}; defaults to {@code name})
 * @param promptSnippet   one-line snippet for the system prompt's Available-tools section
 *                        (pi {@code promptSnippet}; falls back to {@code description} when absent)
 * @param promptGuidelines guideline bullets appended to the system prompt when this tool is active
 * @param renderShell     whether the UI renders the standard shell or the tool renders itself
 *                        (pi {@code renderShell: "default"|"self"}; default {@code "default"})
 * @param constrainedSampling pi {@code Tool.constrainedSampling}（docs/66）：可选 provider 侧
 *                        约束采样；{@code null} ≙ 缺席（扩展显式 {@code false} 同形）
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    String label,
    String promptSnippet,
    List<String> promptGuidelines,
    String renderShell,
    ConstrainedSampling constrainedSampling
) {
    /** Compact constructor that defensively copies collections and defaults nulls. */
    public ToolDefinition {
        inputSchema = Map.copyOf(inputSchema);
        promptGuidelines = List.copyOf(promptGuidelines);
        label = label == null ? name : label;
        renderShell = renderShell == null ? "default" : renderShell;
        // constrainedSampling 不归一：null（缺席）即 pi 的 undefined|false。
    }

    /** Seven-component shape (pre-constrained-sampling canonical; sampling absent). */
    public ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema,
        String label,
        String promptSnippet,
        List<String> promptGuidelines,
        String renderShell
    ) {
        this(name, description, inputSchema, label, promptSnippet, promptGuidelines,
            renderShell, null);
    }

    /** Convenience constructor for tools without rendering metadata. */
    public ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
    ) {
        this(name, description, inputSchema, name, null, List.of(), "default", null);
    }
}
