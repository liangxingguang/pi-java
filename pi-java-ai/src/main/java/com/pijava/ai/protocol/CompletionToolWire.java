package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;

import com.pijava.ai.api.StrictJsonSchema;
import com.pijava.ai.api.StrictSampling;
import com.pijava.ai.api.ToolDefinition;

/**
 * docs/66：openai-completions 车道的 function-tool 出站构建
 * （pi {@code openai-completions.ts:1472-1506}）。从
 * {@code OpenAICompletionsMessageConverter} 抽出（拆分步骤 7）。
 *
 * <p>每工具：{@code strict = resolveStrict(tool, gate)}；strict==true ⇒ 参数走 strict
 * schema 转换；gate==true ⇒ 恒发 {@code strict} 键（false for 非采样工具）；
 * gate==false ⇒ 整个键不发（schema 不转换）。</p>
 */
final class CompletionToolWire {

    private CompletionToolWire() {}

    /** Build a chat-completions function tool. */
    static ChatCompletionTool toTool(ToolDefinition td, boolean gate) {
        var strict = Boolean.TRUE.equals(StrictSampling.resolveStrict(td, gate));
        var schema = strict ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();

        var function = FunctionDefinition.builder()
            .name(td.name())
            .description(td.description())
            .parameters(FunctionParameters.builder()
                .putAllAdditionalProperties(toJsonValues(schema))
                .build());
        // pi :1502-1503：仅 provider 支持时发 strict；有些端点拒绝未知字段。
        if (gate) {
            function.strict(strict);
        }
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .type(JsonValue.from("function"))
                .function(function.build())
                .build());
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    /**
     * pi {@code openai-completions.ts:1240-1246} 的 <b>Kimi 形状</b>：
     * {@code {role:"system", tools:[…]}}。
     *
     * <p>openai-java 4.42 无类型化对应物（六个消息变体无一带 tools）⇒ 走 SDK 的
     * 未知键直通（原始 JSON 节点反序列化，序列化原样写出）。通路与逐字节往返证据见
     * {@code SdkJsonEscapeHatchTest}。</p>
     */
    static ChatCompletionMessageParam kimiSystemMessage(List<ToolDefinition> addedTools,
                                                         boolean gate) {
        var tools = addedTools.stream().map(td -> toTool(td, gate)).toList();
        var mapper = com.openai.core.ObjectMappers.jsonMapper();
        var node = mapper.createObjectNode();
        node.put("role", "system");
        node.set("tools", mapper.valueToTree(tools));
        try {
            return mapper.treeToValue(node, ChatCompletionMessageParam.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot build the Kimi tool system message", e);
        }
    }
}
