package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.openai.core.JsonField;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.CustomToolInputFormat;
import com.openai.models.responses.CustomTool;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCustomToolCallOutput;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseToolSearchOutputItemParam;
import com.openai.models.responses.Tool;

import com.pijava.ai.api.GrammarInputProperties;
import com.pijava.ai.api.StrictJsonSchema;
import com.pijava.ai.api.StrictSampling;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * docs/66/69：openai-responses 车道的出站工具声明（pi
 * {@code openai-responses-shared.ts:359-396}）。
 *
 * <p>每工具先看 grammar（门开 ⇒ OpenAI custom tool、扁平 format），否则
 * function：strict 解析、{@code supportsStrictMode} ⇒ 明确发 strict，不支持就整个键不发。</p>
 */
final class ResponseToolWire {

    private ResponseToolWire() {}

    /**
     * Build a responses tool: custom grammar tool when the gate is open, else function.
     */
    static Tool responseTool(ToolDefinition td, boolean supportsStrictMode,
                              Map<String, String> grammarProperties,
                              boolean deferLoading) {
        var grammar = GrammarInputProperties.resolve(td,
            grammarProperties.containsKey(td.name()));
        if (grammar != null) {
            return customTool(td, grammar, deferLoading);
        }

        var strict = Boolean.TRUE.equals(
            StrictSampling.resolveStrict(td, supportsStrictMode));
        var schema = strict ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();

        var functionBuilder = FunctionTool.builder()
            .name(td.name())
            .description(td.description())
            .parameters(FunctionTool.Parameters.builder()
                .putAllAdditionalProperties(toJsonValues(schema))
                .build());
        // ⚠️ gate false 时必须显式 JsonMissing（跳过 setter 会被 checkRequired 判未设置）。
        functionBuilder.strict(supportsStrictMode
            ? JsonField.of(strict) : JsonMissing.of());
        if (deferLoading) {
            functionBuilder.deferLoading(true);
        }
        return Tool.ofFunction(functionBuilder.build());
    }

    /** pi convertResponsesTools 的 custom 分支（{@code shared:366-378}）。 */
    private static Tool customTool(ToolDefinition td,
                                    GrammarInputProperties.ResolvedGrammar grammar,
                                    boolean deferLoading) {
        var syntax = "lark".equals(grammar.format())
            ? CustomToolInputFormat.Grammar.Syntax.LARK
            : CustomToolInputFormat.Grammar.Syntax.REGEX;
        var grammarFormat = CustomToolInputFormat.Grammar.builder()
            .syntax(syntax)
            .definition(grammar.definition())
            .build();
        var builder = CustomTool.builder()
            .name(td.name())
            .description(td.description())
            .format(grammarFormat);
        if (deferLoading) {
            builder.deferLoading(true);
        }
        return Tool.ofCustom(builder.build());
    }

    /**
     * Build the replay item for one assistant ToolUseContent：custom_tool_call
     * （grammar 工具）或 function_call（pi shared :288-326）。
     */
    static ResponseInputItem historyItem(
            ContentBlock.ToolUseContent toolUse,
            String callId, String itemId, boolean dropItemId,
            Map<String, String> grammarProperties) {
        var property = grammarProperties.get(toolUse.name());
        if (property != null) {
            return customHistoryItem(toolUse, callId, itemId, dropItemId, property);
        }
        var callBuilder = com.openai.models.responses.ResponseFunctionToolCall.builder()
            .callId(callId)
            .name(toolUse.name())
            .arguments(toArgumentsJson(toolUse.arguments()));
        if (itemId != null && !dropItemId) {
            callBuilder.id(itemId);
        }
        return ResponseInputItem.ofFunctionCall(callBuilder.build());
    }

    /** pi replay 的 custom_tool_call 分支（shared:306-316）：输入串净化。 */
    private static ResponseInputItem customHistoryItem(
            ContentBlock.ToolUseContent toolUse,
            String callId, String itemId, boolean dropItemId, String property) {
        var input = toolUse.arguments().get(property);
        if (!(input instanceof String inputString)) {
            // pi getGrammarToolInput:145-155 的逐字文案。
            throw new IllegalStateException("Grammar tool call \"" + toolUse.name()
                + "\" requires argument \"" + property + "\" to be a string.");
        }
        var builder = com.openai.models.responses.ResponseCustomToolCall.builder()
            .callId(callId)
            .name(toolUse.name())
            .input(com.pijava.ai.utils.SanitizeUnicode.surrogates(inputString));
        if (itemId != null && !dropItemId) {
            builder.id(itemId);
        }
        return ResponseInputItem.ofCustomToolCall(builder.build());
    }

    /**
     * Build the custom_tool_call_output for a grammar tool result, else
     * function_call_output (pi shared :331-347). The output conversion is shared.
     */
    static ResponseInputItem historyToolResult(
            String callId,
            ResponseInputItem.FunctionCallOutput.Output output,
            boolean isGrammarTool) {
        if (isGrammarTool) {
            // custom 与 function 的 output wire 同形、SDK 类型不同 ⇒ JSON 树转换。
            var json = ObjectMappers.jsonMapper().convertValue(output, JsonNode.class);
            ResponseCustomToolCallOutput.Output customOutputType;
            try {
                customOutputType = ObjectMappers.jsonMapper().treeToValue(
                    json, ResponseCustomToolCallOutput.Output.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("cannot convert tool result output", e);
            }
            var customOutput = ResponseCustomToolCallOutput.builder()
                .callId(callId).output(customOutputType).build();
            return ResponseInputItem.ofCustomToolCallOutput(customOutput);
        }
        return ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(callId).output(output).build());
    }

    /**
     * pi {@code appendSystemToolAdditions}（{@code openai-responses-shared.ts:182-210}）：
     * 把一条系统消息声明的工具**就地锚定**在它自己的位置上。
     *
     * <p>{@code supportsAdditionalTools} ⇒ 一条
     * {@code {type:"additional_tools", role:"developer", tools:[…]}}；否则
     * {@code supportsToolSearch} ⇒ 合成一对 {@code tool_search_call} +
     * {@code tool_search_output}（都 client/completed），call_id 由「位置＋名字」哈希
     * 确定性生成。两者皆无 ⇒ 不发（调用方已按同一判据保证实际不可达）。</p>
     */
    static void anchorSystemToolAdditions(List<ResponseInputItem> items,
                                            Message.SystemMessage system, int msgIndex,
                                            boolean supportsAdditionalTools,
                                            boolean supportsToolSearch,
                                            boolean supportsStrictMode,
                                            Map<String, String> grammarProperties) {
        var anchored = system.toolsAdded().stream()
            .map(td -> responseTool(td, supportsStrictMode, grammarProperties, false)).toList();
        if (supportsAdditionalTools) {
            items.add(ResponseInputItem.ofAdditionalTools(
                ResponseInputItem.AdditionalTools.builder()
                    .role(JsonValue.from("developer"))
                    .tools(anchored)
                    .build()));
            return;
        }
        if (!supportsToolSearch) {
            return;
        }
        var names = system.toolsAdded().stream().map(ToolDefinition::name).toList();
        var callId = "pi_tool_load_"
            + com.pijava.ai.utils.ShortHash.of(
                "system:" + msgIndex + ":" + String.join(",", names));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("query", String.join(" ", names));
        arguments.put("limit", names.size());
        items.add(ResponseInputItem.ofToolSearchCall(ResponseInputItem.ToolSearchCall.builder()
            .callId(callId)
            .execution(ResponseInputItem.ToolSearchCall.Execution.CLIENT)
            .status(ResponseInputItem.ToolSearchCall.Status.COMPLETED)
            .arguments(JsonValue.from(arguments))
            .build()));
        items.add(ResponseInputItem.ofToolSearchOutput(
            ResponseToolSearchOutputItemParam.builder()
                .callId(callId)
                .execution(ResponseToolSearchOutputItemParam.Execution.CLIENT)
                .status(ResponseToolSearchOutputItemParam.Status.COMPLETED)
                .tools(system.toolsAdded().stream()
                    .map(td -> responseTool(td, supportsStrictMode, grammarProperties, true)).toList())
                .build()));
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new java.util.LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
