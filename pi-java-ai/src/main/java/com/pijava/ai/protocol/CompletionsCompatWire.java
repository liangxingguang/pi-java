package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.VercelGatewayRouting;

/**
 * B98（docs/51）：completions 车道四个 **body 级** compat 写点，pi
 * {@code openai-completions.ts} 的逐字对应：
 * <ul>
 *   <li>{@code supportsUsageInStreaming}：:834 的 {@code !== false} 门（stream_options）；</li>
 *   <li>{@code zaiToolStream}：:856-860，**有请求工具时**发顶层 {@code tool_stream:true}；</li>
 *   <li>{@code vllmPriority}：:873-875，顶层 {@code priority}，位于思考形态链条之前；</li>
 *   <li>{@code vercelGatewayRouting}：:992-1001，{@code providerOptions.gateway}，
 *       位于 openRouterRouting 之后。</li>
 * </ul>
 * 消息形三处（name/插桥/思考转文本）在 {@link OpenAICompletionsMessageConverter} 内。
 */
final class CompletionsCompatWire {

    /** 无 NON_NULL inclusion 的普通 mapper（与 ThinkingFormatWriter.PLAIN_JSON 同物，经树保 null 语义）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private CompletionsCompatWire() {
    }

    /** pi :834-836：只有显式 {@code false} 才不发 include_usage（探测缺省 true）。 */
    static void applyUsageInStreaming(ChatCompletionCreateParams.Builder builder,
                                     ModelCompat compat) {
        if (!Boolean.FALSE.equals(compat.supportsUsageInStreaming())) {
            builder.streamOptions(com.openai.models.chat.completions.ChatCompletionStreamOptions
                .builder().includeUsage(true).build());
        }
    }

    /** pi :856-860：门是 requestTools.length &gt; 0（历史工具只给 tools:[]，不发）。 */
    static void applyZaiToolStream(ChatCompletionCreateParams.Builder builder,
                                   ModelCompat compat, boolean hasRequestTools) {
        if (hasRequestTools && Boolean.TRUE.equals(compat.zaiToolStream())) {
            builder.putAdditionalBodyProperty("tool_stream", JsonValue.from(true));
        }
    }

    /** pi :873-875：显式-only（无探测默认）；位置在 tool_choice 之后、思考链条之前。 */
    static void applyVllmPriority(ChatCompletionCreateParams.Builder builder,
                                  ModelCompat compat) {
        if (compat.vllmPriority() != null) {
            builder.putAdditionalBodyProperty("priority",
                JsonValue.from(compat.vllmPriority().intValue()));
        }
    }

    /** pi :992-1001：两个子列表都空 ⇒ 不发 providerOptions（{@code if (r.only || r.order)}）。 */
    static void applyVercelGatewayRouting(ChatCompletionCreateParams.Builder builder,
                                          ModelCompat compat) {
        VercelGatewayRouting routing = compat.vercelGatewayRouting();
        if (routing == null || !routing.hasRouting()) {
            return;
        }
        var gateway = new LinkedHashMap<String, Object>();
        if (routing.only() != null && !routing.only().isEmpty()) {
            gateway.put("only", routing.only());
        }
        if (routing.order() != null && !routing.order().isEmpty()) {
            gateway.put("order", routing.order());
        }
        builder.putAdditionalBodyProperty("providerOptions",
            JsonValue.from(JSON.valueToTree(Map.of("gateway", gateway))));
    }
}
