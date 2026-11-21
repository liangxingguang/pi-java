package com.pijava.ai.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.openai.core.JsonValue;
import com.openai.models.responses.Response;

import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * v1.6.0（pi {@code openai-responses-shared.ts:629-650}，{@code 03de604b8}）：
 * 把 API 从请求里<b>丢弃</b>的重放 reasoning 项记成诊断。
 *
 * <p>GPT-6.2 起 Responses API 会丢弃「跟在被改动前缀之后的 reasoning」
 * （{@code prefix_mismatch}）以及另一个组织/终端用户/模型下产生的 reasoning，并在
 * 终局响应的 {@code input_transformations} 里报告。每次重放该条项后都会再报一次。</p>
 *
 * <p>⚠️ {@code turn_boundary} 类的移除**不记录**：只使用当轮 reasoning 的模型
 * （GPT-5.6 之前全部）会在每个请求上把此前每一轮的 reasoning 都报成这一类 —— 那
 * 是它们的正常行为，记下来会让会话按对话长度**二次增长**。</p>
 *
 * <p>诊断类型 {@code openai_input_transformations}，三条 responses 车道
 * （openai / azure / codex）共用同一段终局处理。</p>
 */
final class ResponsesInputTransformations {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponsesInputTransformations() {}

    /**
     * pi {@code appendReasoningRemovals}：终局响应里挑出非 {@code turn_boundary} 的
     * reasoning 移除项，非空才追加一条诊断（{@code details.transformations}）。
     *
     * <p>旧服务器/代理会省略该字段，Codex 也容忍畸形条目 ⇒ 非数组、非对象一律跳过。</p>
     */
    static void appendReasoningRemovals(StreamPartialBuilder builder, Response response) {
        JsonValue raw = response._additionalProperties().get("input_transformations");
        if (raw == null) {
            return;
        }
        JsonNode transformations = raw.convert(JsonNode.class);
        if (transformations == null || !transformations.isArray()) {
            return;
        }
        var removals = JSON.createArrayNode();
        for (JsonNode transformation : transformations) {
            if (transformation == null || !transformation.isObject()) {
                continue;
            }
            if (!"reasoning".equals(textOrNull(transformation, "type"))
                    || !"removed".equals(textOrNull(transformation, "action"))
                    || "turn_boundary".equals(textOrNull(transformation, "reason"))) {
                continue;
            }
            removals.add(projectRules(transformation));
        }
        if (removals.isEmpty()) {
            return;
        }
        var details = JSON.createObjectNode();
        details.set("transformations", removals);
        builder.appendDiagnostic(new AssistantMessageDiagnostic(
            "openai_input_transformations",
            System.currentTimeMillis(),
            null,
            details));
    }

    /** pi 的解构投影 {@code ({ type, id, action, reason })}：在场的字符串键才写。 */
    private static ObjectNode projectRules(JsonNode transformation) {
        var out = JSON.createObjectNode();
        copyText(transformation, "type", out);
        copyText(transformation, "id", out);
        copyText(transformation, "action", out);
        copyText(transformation, "reason", out);
        return out;
    }

    private static void copyText(JsonNode source, String key, ObjectNode target) {
        String value = textOrNull(source, key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
