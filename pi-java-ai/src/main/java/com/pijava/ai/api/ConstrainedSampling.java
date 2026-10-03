package com.pijava.ai.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * pi {@code ConstrainedSamplingConfig}（{@code types.ts:590-598}）：工具可选的
 * provider 侧约束采样配置。
 *
 * <p>两个变体：{@link JsonSchemaSampling} 与 {@link GrammarSampling}。判别字段
 * {@code type} 与 pi 线格逐字相同。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = JsonSchemaSampling.class, name = "json_schema"),
    @JsonSubTypes.Type(value = GrammarSampling.class, name = "grammar"),
})
public sealed interface ConstrainedSampling permits JsonSchemaSampling, GrammarSampling {
}
