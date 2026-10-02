package com.pijava.ai.api;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * pi {@code ConstrainedSamplingConfig} json_schema 形态的 {@code strict} 取值
 * （{@code types.ts:590-594}）。
 *
 * <ul>
 *   <li>{@link #PREFER} —— provider 支持且 schema 可转换时走 strict，否则回落普通工具；</li>
 *   <li>{@link #REQUIRE} —— 无法满足时响亮失败。</li>
 * </ul>
 */
public enum StrictMode {
    PREFER("prefer"),
    REQUIRE("require");

    private final String wire;

    StrictMode(String wire) {
        this.wire = wire;
    }

    /** Wire literal（pi 小写词）。 */
    @JsonValue
    public String wire() {
        return wire;
    }
}
