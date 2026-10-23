package com.pijava.coding.agent.extension;

/**
 * 批 16（T1a，docs/27）：pi {@code ExtensionFlag}（types.ts:2058-2064）对位——
 * 扩展经 {@code registerFlag} 声明的 CLI flag。
 *
 * <p>{@code extensionPath} 的 java 对位是扩展名（{@link PiExtension#name()}）；
 * {@code defaultValue} 缺席为 {@code null}（≙ pi 的 undefined）。</p>
 */
public record ExtensionFlag(String name, String description, Type type,
                            Object defaultValue, String extensionName) {

    /** pi {@code type: "boolean" | "string"}（纯常量闭集 ⇒ enum）。 */
    public enum Type {
        /** pi {@code "boolean"}：命令行出现即 {@code true}（吞下的字符串值被忽略）。 */
        BOOLEAN,
        /** pi {@code "string"}：必须带值（{@code --k=v} 或 {@code --k v}）。 */
        STRING
    }
}
