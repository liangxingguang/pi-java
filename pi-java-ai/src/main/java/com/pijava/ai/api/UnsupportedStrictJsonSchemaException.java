package com.pijava.ai.api;

/**
 * pi {@code UnsupportedStrictJsonSchemaError}（{@code constrained-sampling.ts:10}）：
 * 工具 schema 无法转换为 strict 子集。resolver 按此类型决定 prefer 回落还是 require 失败。
 */
public class UnsupportedStrictJsonSchemaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedStrictJsonSchemaException(String message) {
        super(message);
    }
}
