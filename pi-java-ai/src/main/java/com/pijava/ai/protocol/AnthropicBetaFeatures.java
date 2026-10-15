package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.AllowedFallbackModel;
import com.pijava.ai.catalog.ModelCompat;

/**
 * 自动 {@code anthropic-beta} 协商列表的组装 —— pi {@code getBetaFeatures}
 * （{@code anthropic-messages.ts:1083-1124}）的逐字移植。
 *
 * <p>顺序固定为 <b>OAuth 对 → fine-grained → interleaved → fallback →
 * managed×2 → inline</b>（B81 起与 pi 逐字节一致）；显式 {@code anthropic-beta}
 * 请求头的整组替换门在调用方（批 4 裁决③），本类只产自动列表。</p>
 */
final class AnthropicBetaFeatures {

    private AnthropicBetaFeatures() {
    }

    /** pi {@code anthropic-messages.ts:181}：不支 eager 工具流的端点改挂此 beta。 */
    private static final String FINE_GRAINED_TOOL_STREAMING_BETA =
        "fine-grained-tool-streaming-2025-05-14";

    private static final String INTERLEAVED_THINKING_BETA =
        "interleaved-thinking-2025-05-14";

    /**
     * pi {@code anthropic-messages.ts:195} —— 原生工具增删 beta（**定义内联形状**）。
     * 后续工具不按名引用，而在 {@code tool_addition} 块里按值内联。
     */
    private static final String INLINE_TOOLS_BETA = "inline-tools-2026-09-15";

    /** B98（pi {@code anthropic-messages.ts:192}）：服务端拒绝回退 beta。 */
    private static final String SERVER_SIDE_FALLBACK_BETA =
        "server-side-fallback-2026-07-01";

    /**
     * @param request              本次请求（模型能力/reasoning 门用）
     * @param compat               解析后的 anthropic compat
     * @param oauth                B81：Claude Code 伪装车道（身份对最前）
     * @param currentTools         当前工具表（fine-grained 门：非空 且 不支 eager）
     * @param eagerToolInputStreaming 端点是否支 eager 工具输入流
     * @param allowedFallbackModels compat 的许可回退模型（B98 门）
     * @param nativeToolChanges    原生中途工具增删三条件门
     */
    static List<String> assemble(StreamRequest request, ModelCompat compat, boolean oauth,
                                 List<ToolDefinition> currentTools,
                                 boolean eagerToolInputStreaming,
                                 List<AllowedFallbackModel> allowedFallbackModels,
                                 boolean nativeToolChanges) {
        var betas = new ArrayList<String>();
        if (oauth) {
            betas.addAll(ClaudeCodeNames.OAUTH_BETAS);
        }
        // 包 09（B90）：pi `:1450-1454` 的两个合取项 —— 工具表非空 **且** 端点不支持 eager。
        if (!eagerToolInputStreaming && !currentTools.isEmpty()) {
            betas.add(FINE_GRAINED_TOOL_STREAMING_BETA);
        }
        if (request.model() != null
                && request.model().capabilities().contains(com.pijava.ai.model.ModelCapability.THINKING)
                && request.reasoning().isPresent()
                && !compat.forceAdaptiveThinking()) {
            betas.add(INTERLEAVED_THINKING_BETA);
        }
        // B98（pi anthropic-messages.ts:211-213/:1116）：模型带 allowedFallbackModels
        // 才挂服务端回退 beta；流内回退块处理随 B148。
        if (allowedFallbackModels != null && !allowedFallbackModels.isEmpty()) {
            betas.add(SERVER_SIDE_FALLBACK_BETA);
        }
        // B99（pi :1117-1119）：managed effort 两 beta **无条件**随 flag 挂
        // （不看 thinkingEnabled/effort；managed 模型 forceAdaptive 恒真，
        // interleaved 已被上面的第四合取项自然排除）。
        if (Boolean.TRUE.equals(compat.supportsMidConvoEffort())) {
            betas.add(AnthropicThinking.MID_CONVERSATION_OUTPUT_CONFIG_BETA);
            betas.add(AnthropicThinking.THINKING_BINDING_CONTROLS_BETA);
        }
        if (nativeToolChanges) {
            betas.add(INLINE_TOOLS_BETA);
        }
        return betas;
    }
}
