package com.pijava.coding.agent.skill;

/**
 * 资源加载校验诊断（对齐 pi {@code core/diagnostics.ts}）。
 *
 * <p>单个技能非法不应中断整批加载 —— 由 {@link LoadSkillsResult} 携带诊断返回。
 * {@code level} 为 {@code "error"}（该资源被跳过）、{@code "warning"}（保留）或
 * {@code "collision"}（同名先到先得的后到者，pi {@code skills.ts:439-448}）。</p>
 */
public record ResourceDiagnostic(
    String filePath,
    String level,
    String message,
    Collision collision
) {
    /** pi {@code type:"collision"} 诊断载荷：同名先到先得，后到者落此诊断。 */
    public record Collision(String resourceType, String name,
                            String winnerPath, String loserPath) {}

    /** 兼容构造：非碰撞诊断。 */
    public ResourceDiagnostic(String filePath, String level, String message) {
        this(filePath, level, message, null);
    }

    /** 错误：该资源被跳过。 */
    public static ResourceDiagnostic error(String filePath, String message) {
        return new ResourceDiagnostic(filePath, "error", message);
    }

    /** 警告：资源保留。 */
    public static ResourceDiagnostic warning(String filePath, String message) {
        return new ResourceDiagnostic(filePath, "warning", message);
    }

    /** 碰撞：同名后到者被丢弃（pi {@code type:"collision"}）。 */
    public static ResourceDiagnostic collision(String filePath, String message, Collision payload) {
        return new ResourceDiagnostic(filePath, "collision", message, payload);
    }
}
