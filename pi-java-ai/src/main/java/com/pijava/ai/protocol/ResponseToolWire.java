package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openai.core.JsonField;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseToolSearchOutputItemParam;
import com.openai.models.responses.Tool;

import com.pijava.ai.api.StrictJsonSchema;
import com.pijava.ai.api.StrictSampling;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.Message;

/**
 * docs/66：openai-responses 车道（openai/azure/codex）的 function-tool 出站构建
 * （pi {@code openai-responses-shared.ts:359-396}）。从
 * {@code ResponsesMessageConverter} 抽出（拆分步骤 7）。
 *
 * <p>{@code strict = resolveStrict(tool, gate)}；true ⇒ 参数走 strict schema 转换。
 * gate（compat supportsStrictMode）⇒ 发 {@code strict} 键（缺省 false）；gate false
 * ⇒ 键整个不发（B88 形状）。</p>
 */
final class ResponseToolWire {

    private ResponseToolWire() {}

    /**
     * @param supportsStrictMode whether the lane/model supports strict tools
     * @param deferLoading       tool-search result: mark the tool defer_loading
     */
    static Tool responseTool(ToolDefinition td, boolean supportsStrictMode,
                               boolean deferLoading) {
        var strict = Boolean.TRUE.equals(StrictSampling.resolveStrict(td, supportsStrictMode));
        var schema = strict ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();

        var functionTool = FunctionTool.builder()
            .name(td.name())
            .description(td.description())
            .parameters(FunctionTool.Parameters.builder()
                .putAllAdditionalProperties(toJsonValues(schema))
                .build());
        // ⚠️ gate false 时必须显式 JsonMissing（跳过 setter 会被 checkRequired 判未设置）。
        functionTool.strict(supportsStrictMode
            ? JsonField.of(strict) : JsonMissing.of());
        if (deferLoading) {
            functionTool.deferLoading(true);
        }
        return Tool.ofFunction(functionTool.build());
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
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
                                            boolean supportsStrictMode) {
        var anchored = system.toolsAdded().stream()
            .map(td -> responseTool(td, supportsStrictMode, false)).toList();
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
                    .map(td -> responseTool(td, supportsStrictMode, true)).toList())
                .build()));
    }
}
