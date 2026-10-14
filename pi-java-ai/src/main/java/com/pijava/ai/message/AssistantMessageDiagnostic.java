package com.pijava.ai.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 助手消息上的一条 provider/运行时诊断（pi {@code AssistantMessageDiagnostic}，
 * {@code packages/ai/src/utils/diagnostics.ts:9-14}）——失败与恢复的脱敏记录，
 * 随消息持久化（pi 的 bug 报告消费者 B79 另案）。
 *
 * <p>键序即 pi 声明序：{@code type, timestamp, error?, details?}。{@code error} 与
 * {@code details} 均可缺：rewrite 一类诊断只有 details，纯标记可以两者皆无。
 * 类级 NON_NULL：Jackson 投影时缺键省略，与 pi 的 {@code stringify} 丢 undefined 同形。</p>
 *
 * @param type      诊断类型字面量（{@code pi_messages_response_failure}、
 *                  {@code pi_messages_rewrite}、{@code anthropic_input_transformations} 等）
 * @param timestamp 诊断产生时刻（epoch ms，pi {@code Date.now()}）
 * @param error     错误信息（pi {@code DiagnosticErrorInfo}），无则 null
 * @param details   结构化细节，原样保真（网关错误体/重写摘要/变换列表），无则 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantMessageDiagnostic(
    String type,
    long timestamp,
    DiagnosticErrorInfo error,
    JsonNode details
) {

    /**
     * pi {@code DiagnosticErrorInfo}（{@code diagnostics.ts:3-8}）：
     * {@code name?/message/stack?/code?(string|number)}。
     *
     * <p>{@code code} 用 {@link JsonNode} 不用 String——pi 类型是
     * {@code string | number}，网关给数字码时要原样保真。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiagnosticErrorInfo(
        String name,
        String message,
        String stack,
        JsonNode code
    ) {
        public DiagnosticErrorInfo(String name, String message) {
            this(name, message, null, null);
        }

        public DiagnosticErrorInfo(String name, String message, JsonNode code) {
            this(name, message, null, code);
        }
    }
}
