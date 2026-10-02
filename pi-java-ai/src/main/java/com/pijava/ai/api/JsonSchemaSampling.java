package com.pijava.ai.api;

/**
 * pi {@code {type:"json_schema", strict:"prefer"|"require"}}（{@code types.ts:591-594}）：
 * provider 以 strict JSON Schema 约束工具调用。
 *
 * @param strict prefer（不支持则回落）／require（不支持则响亮失败）
 */
public record JsonSchemaSampling(StrictMode strict) implements ConstrainedSampling {

    /** Compact constructor: strict is required on the wire. */
    public JsonSchemaSampling {
        if (strict == null) {
            throw new IllegalArgumentException("json_schema constrained sampling requires strict");
        }
    }
}
