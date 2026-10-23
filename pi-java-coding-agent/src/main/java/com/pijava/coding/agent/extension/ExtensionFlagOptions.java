package com.pijava.coding.agent.extension;

/**
 * 批 16（T1a，docs/27）：{@code registerFlag} 的声明选项（pi types.ts:1663-1680
 * 的判别联合对位）。
 *
 * <p>{@code defaultValue} 类型必须与 {@code type} 一致（BOOLEAN ⇒ {@link Boolean}、
 * STRING ⇒ {@link String}）——不匹配在
 * {@link ExtensionContext#registerFlag} 处即抛（pi loader.ts:337-340 逐字）。</p>
 */
public record ExtensionFlagOptions(String description, ExtensionFlag.Type type,
                                   Object defaultValue) {

    /** pi {@code { description?, type: "boolean", default? }}。 */
    public static ExtensionFlagOptions ofBoolean(String description, Boolean defaultValue) {
        return new ExtensionFlagOptions(description, ExtensionFlag.Type.BOOLEAN, defaultValue);
    }

    /** pi {@code { description?, type: "string", default? }}。 */
    public static ExtensionFlagOptions ofString(String description, String defaultValue) {
        return new ExtensionFlagOptions(description, ExtensionFlag.Type.STRING, defaultValue);
    }
}
