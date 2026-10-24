package com.pijava.coding.agent.theme;

import java.util.Map;

/**
 * 一份校验过的主题文档（pi {@code ValidatedThemeJson}，theme-schema.ts:52-90 对位，
 * 批 21/docs/32 T5）。
 *
 * <p>色值是「字符串 | 0-255 整数」联合 ⇒ java 槽位为 {@code Object}（
 * {@code String} 或 {@code Integer}），取值收敛在 {@link ThemeJsonValidator}。</p>
 *
 * @param name 主题名（不得含 {@code /}，不得为 {@code system}——后者由调用方保留字处理）
 * @param appearance 设计面向的背景；缺席 ⇒ null（从颜色检测）
 * @param vars 可复用变量表（可引用其他变量）；缺席 ⇒ 空表
 * @param colors 令牌色值表（必填令牌齐全已由校验器保证）
 * @param export HTML 导出背景覆盖；缺席 ⇒ null
 */
public record ThemeJson(String name, ThemeAppearance appearance, Map<String, Object> vars,
                        Map<String, Object> colors, ExportColors export) {

    /** HTML 导出覆盖（pi theme-schema.ts:71-83；缺席键 ⇒ null）。 */
    public record ExportColors(Object pageBg, Object cardBg, Object infoBg) {}
}
