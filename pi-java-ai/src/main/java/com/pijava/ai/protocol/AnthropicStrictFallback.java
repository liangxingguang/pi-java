package com.pijava.ai.protocol;

import java.util.List;

import com.pijava.ai.message.AssistantMessageDiagnostic;

/**
 * #101（docs/17 §5）：Anthropic strict 工具 400 的一次性降级辅助
 * （pi {@code constrained-sampling.ts:149-159} ＋ {@code anthropic-messages.ts:683-689}）。
 *
 * <p>只有三类「工具上限」文案允许降级——其他 400 原样抛出。诊断只进
 * {@code AssistantMessage.diagnostics}，不进入任何线格文本。</p>
 */
final class AnthropicStrictFallback {

    private AnthropicStrictFallback() {}

    /** pi {@code STRICT_TOOL_LIMIT_ERROR_MESSAGES}（逐字三条）。 */
    private static final List<String> STRICT_TOOL_LIMIT_ERROR_MESSAGES = List.of(
        "The compiled grammar is too large",
        "Too many strict tools (",
        "Schemas contains too many parameters with union types (");

    /** pi {@code isStrictToolLimitErrorMessage}：任一文案作为子串出现。 */
    static boolean isStrictToolLimitErrorMessage(String message) {
        if (message == null) {
            return false;
        }
        return STRICT_TOOL_LIMIT_ERROR_MESSAGES.stream().anyMatch(message::contains);
    }

    /**
     * pi {@code appendAssistantMessageDiagnostic}：
     * {@code {type:"anthropic_strict_tools_fallback", timestamp, error:{message},
     * details:{reason:"strict_tool_limits_exceeded"}}}。
     */
    static AssistantMessageDiagnostic diagnostic(String message) {
        var details = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        details.put("reason", "strict_tool_limits_exceeded");
        return new AssistantMessageDiagnostic(
            "anthropic_strict_tools_fallback",
            System.currentTimeMillis(),
            new AssistantMessageDiagnostic.DiagnosticErrorInfo(null, message),
            details);
    }
}
