package com.pijava.ai.api;

/**
 * pi {@code UnsupportedStrictSchemaKeywordCheck}（{@code constrained-sampling.ts:218}）：
 * 「该 provider 的 strict 模式是否拒绝这个 schema 关键字/值」。
 *
 * <p>探测遍（{@link StrictSampling}）带本谓词：prefer 工具命中不支持关键字 ⇒ 静默回落
 * 非 strict；require 工具 ⇒ 抛错。各 provider 谓词不同（Anthropic 有自己的关键字集，
 * 其他车道传 {@code null}）。</p>
 */
@FunctionalInterface
public interface UnsupportedStrictKeyword {

    /**
     * @param key   schema 键名（如 {@code minimum}、{@code minItems}、{@code format}）
     * @param value 该键的值（任意 JSON 标量/结构）
     * @return true 表示该车道 strict 模式拒绝此键值
     */
    boolean test(String key, Object value);
}
