package com.pijava.ai.protocol;

import java.util.Map;

/**
 * pi-messages wire 上的工具调用（对齐 pi {@code ToolCall}）。
 *
 * <p>独立 record 而非 {@code ContentBlock.ToolUseContent}，避免继承 ContentBlock
 * 接口的多态 {@code type} 字段（pi-messages 的 toolCall 不带 type）。</p>
 */
public record PiToolCall(
    String id,
    String name,
    Map<String, Object> arguments
) {
    /** Compact constructor that defensively copies the arguments map. */
    public PiToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
