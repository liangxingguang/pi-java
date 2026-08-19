package com.pijava.coding.agent.skill;

/**
 * 资源加载校验诊断（对齐 pi {@code core/diagnostics.ts}）。
 *
 * <p>单个技能非法不应中断整批加载 —— 由 {@link LoadSkillsResult} 携带诊断返回。
 * {@code level} 为 {@code "error"}（该技能被跳过）或 {@code "warning"}（保留）。</p>
 */
public record ResourceDiagnostic(
    String filePath,
    String level,
    String message
) {
    /** 错误：该资源被跳过。 */
    public static ResourceDiagnostic error(String filePath, String message) {
        return new ResourceDiagnostic(filePath, "error", message);
    }

    /** 警告：资源保留。 */
    public static ResourceDiagnostic warning(String filePath, String message) {
        return new ResourceDiagnostic(filePath, "warning", message);
    }
}
