package com.pijava.protocol;

/**
 * 模型引用（对齐 pi {@code ModelRefSchema}）：{@code {provider, id}}。
 */
public record ModelRef(
    String provider,
    String id
) {}
