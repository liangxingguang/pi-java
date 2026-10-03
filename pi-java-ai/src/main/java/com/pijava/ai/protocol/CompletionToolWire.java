package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionCustomTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageCustomToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;

import com.pijava.ai.api.GrammarInputProperties;
import com.pijava.ai.api.StrictJsonSchema;
import com.pijava.ai.api.StrictSampling;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * docs/66/69：openai-completions 车道的出站工具声明与历史回放 tool_calls 构建
 * （pi {@code openai-completions.ts:1472-1506/1351-1363}）。从
 * {@code OpenAICompletionsMessageConverter} 抽出（拆分步骤 7）。
 *
 * <p>每工具先看 grammar（门开 ⇒ OpenAI custom tool），否则 function：
 * strict 解析、gate ⇒ 恒发 {@code strict} 键；gate false ⇒ 整个键不发。</p>
 */
final class CompletionToolWire {

    private CompletionToolWire() {}

    /** Build a chat-completions tool: custom grammar tool when the gate is open, else function. */
    static ChatCompletionTool toTool(ToolDefinition td, boolean strictGate,
                                      boolean grammarGate) {
        var grammar = GrammarInputProperties.resolve(td, grammarGate);
        if (grammar != null) {
            return customTool(td, grammar);
        }

        var strict = Boolean.TRUE.equals(StrictSampling.resolveStrict(td, strictGate));
        var schema = strict ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();

        var function = FunctionDefinition.builder()
            .name(td.name())
            .description(td.description())
            .parameters(FunctionParameters.builder()
                .putAllAdditionalProperties(toJsonValues(schema))
                .build());
        // pi :1502-1503：仅 provider 支持时发 strict；有些端点拒绝未知字段。
        if (strictGate) {
            function.strict(strict);
        }
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .type(JsonValue.from("function"))
                .function(function.build())
                .build());
    }

    /** pi convertTools 的 custom 分支（{@code openai-completions.ts:1478-1493}）。 */
    private static ChatCompletionTool customTool(ToolDefinition td,
                                                  GrammarInputProperties.ResolvedGrammar grammar) {
        var syntax = "lark".equals(grammar.format())
            ? ChatCompletionCustomTool.Custom.Format.Grammar.InnerGrammar.Syntax.LARK
            : ChatCompletionCustomTool.Custom.Format.Grammar.InnerGrammar.Syntax.REGEX;
        var inner = ChatCompletionCustomTool.Custom.Format.Grammar.InnerGrammar.builder()
            .syntax(syntax)
            .definition(grammar.definition())
            .build();
        var format = ChatCompletionCustomTool.Custom.Format.Grammar.builder()
            .grammar(inner).build();
        var custom = ChatCompletionCustomTool.Custom.builder()
            .name(td.name())
            .description(td.description())
            .format(format)
            .build();
        return ChatCompletionTool.ofCustom(
            ChatCompletionCustomTool.builder().custom(custom).build());
    }

    /**
     * History replay one tool call: custom shape (input sanitized) for grammar tools,
     * else function (pi {@code openai-completions.ts:1351-1371}).
     */
    static ChatCompletionMessageToolCall historyToolCall(
            ContentBlock.ToolUseContent toolUse, Map<String, String> grammarProperties) {
        var property = grammarProperties.get(toolUse.name());
        if (property != null) {
            return customHistoryToolCall(toolUse, property);
        }
        return ChatCompletionMessageToolCall.ofFunction(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(toolUse.id())
                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(toolUse.name())
                    .arguments(argumentsJson(toolUse.arguments()))
                    .build())
                .build());
    }

    /** pi replay 的 custom 分支（{@code openai-completions.ts:1353-1362}）：输入串净化。 */
    private static ChatCompletionMessageToolCall customHistoryToolCall(
            ContentBlock.ToolUseContent toolUse, String property) {
        var input = toolUse.arguments().get(property);
        if (!(input instanceof String inputString)) {
            // pi getGrammarToolInput:145-155 的逐字文案。
            throw new IllegalStateException("Grammar tool call \"" + toolUse.name()
                + "\" requires argument \"" + property + "\" to be a string.");
        }
        var custom = ChatCompletionMessageCustomToolCall.Custom.builder()
            .name(toolUse.name())
            .input(SanitizeUnicode.surrogates(inputString))
            .build();
        return ChatCompletionMessageToolCall.ofCustom(
            ChatCompletionMessageCustomToolCall.builder()
                .id(toolUse.id()).custom(custom).build());
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    /** Serialize tool-call arguments, falling back to "{}". */
    static String argumentsJson(Map<String, Object> arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
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
                                                         boolean gate,
                                                         boolean grammarGate) {
        var tools = addedTools.stream()
            .map(td -> toTool(td, gate, grammarGate)).toList();
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
