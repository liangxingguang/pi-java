package com.pijava.ai.protocol;

import java.util.LinkedHashMap;

import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.ResponseCreateParams;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.catalog.ModelInfo;

/**
 * 包 A-10：把模型级采样参数写进 OpenAI 兼容车道的请求体。
 *
 * <pre>{@code
 * // pi openai-completions.ts:996-999（responses:362-365 与 azure:345-347 是同形副本）
 * // Last so custom keys override the named request fields.
 * if (options?.samplingParams) {
 *     Object.assign(params, options.samplingParams);
 * }
 * }</pre>
 *
 * <h2>⚠️ 「最后一个」的语义是「同名键压过具名字段」，不是「排在最后」</h2>
 *
 * <p>{@code Object.assign} 只替换**同名**键、不删除别的键 ⇒ 与具名字段同名的采样键必须走
 * **类型化 setter** 重新赋值。这不是风格问题：本 SDK 的非类型化通道是
 * {@code putAdditionalBodyProperty}，它写进的是 `_additionalBodyProperties`，序列化时与
 * 类型化字段**并列** ⇒ 同名键会写成**两份**（实测报文：
 * {@code "max_completion_tokens":16384, … "max_completion_tokens":7}），那是「两个键」
 * 而不是 pi 的「覆盖」。</p>
 *
 * <p>当初把这段放在两个 converter 里各写一份，是因为两边的具名字段不同
 * （completions 是 {@code max_tokens}/{@code max_completion_tokens}，responses 是
 * {@code max_output_tokens}）。抽成本类之后，「不许写成两份」这条规则只有**本文件**一处
 * 要守 —— 但两个 builder 类型不同 ⇒ 路由代码仍是两段（M6 实测：只变异
 * {@code applyToCompletions} ⇒ 恰 1 红；两段同时变异 ⇒ 恰 2 红，{@code 原 docs/57 §8.2}）。</p>
 *
 * <p>数据来源只有**模型级**那一半：pi 的 per-request 半（{@code options.samplingParams}）
 * 在 java 没有生产者（{@code StreamRequest.extra} 生产恒空）⇒ 登记 B125。</p>
 */
final class SamplingParamsWriter {

    private SamplingParamsWriter() {}

    /** pi {@code openai-completions.ts:996-999}。 */
    static void applyToCompletions(ChatCompletionCreateParams.Builder builder, ModelInfo model) {
        SimpleOptions.samplingParamsOf(model).ifPresent(params -> {
            var extra = new LinkedHashMap<>(params);
            if (extra.remove("temperature") instanceof Number temperature) {
                builder.temperature(temperature.doubleValue());
            }
            if (extra.remove("max_tokens") instanceof Number maxTokens) {
                builder.maxTokens(maxTokens.intValue());
            }
            if (extra.remove("max_completion_tokens") instanceof Number maxTokens) {
                builder.maxCompletionTokens(maxTokens.intValue());
            }
            // 包 A-09（R5，原 docs/58 §4.8）：形态链条（ThinkingFormatWriter）用类型化
            // setter 写 reasoning_effort ⇒ 同名采样键必须走同一个 setter 覆盖，
            // 否则非类型化通道会把它写成**两份**（原 docs/57 §10 的实测病理）。
            if (extra.remove("reasoning_effort") instanceof String effort) {
                builder.reasoningEffort(ReasoningEffort.of(effort));
            }
            extra.forEach((key, value) -> builder.putAdditionalBodyProperty(key, JsonValue.from(value)));
        });
    }

    /** pi {@code openai-responses.ts:362-365}（azure 的副本同上，java 两处共用本方法）。 */
    static void applyToResponses(ResponseCreateParams.Builder builder, ModelInfo model) {
        SimpleOptions.samplingParamsOf(model).ifPresent(params -> {
            var extra = new LinkedHashMap<>(params);
            if (extra.remove("temperature") instanceof Number temperature) {
                builder.temperature(temperature.doubleValue());
            }
            if (extra.remove("max_output_tokens") instanceof Number maxOutput) {
                builder.maxOutputTokens(maxOutput.intValue());
            }
            extra.forEach((key, value) -> builder.putAdditionalBodyProperty(key, JsonValue.from(value)));
        });
    }
}
