package com.pijava.coding.agent.core;

/**
 * L5 W8（docs/25 §9；pi {@code tools/render-utils.ts:parseLineNumber}＋
 * {@code export-html/template.js:formatReadLineRange}，:908-923）：read 工具的
 * 行区间显示——{@code offset}/{@code limit} 允许是<b>数字字符串</b>（模型常以
 * 字符串传参），非法值不得产出畸形区间（空串）。
 *
 * <p>树选择器与 HTML 导出同源（pi 也是同一函数两处调用）。</p>
 */
public final class ReadLineRange {

    private ReadLineRange() {}

    /** pi {@code parseLineNumber}（:908-911）：数字字符串先转数；非有限数 ⇒ null。 */
    static Double parseLineNumber(Object value) {
        Object candidate = value;
        if (candidate instanceof String text && !text.trim().isEmpty()) {
            try {
                candidate = Double.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (candidate instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) ? parsed : null;
        }
        return null;
    }

    /**
     * pi {@code formatReadLineRange}（:914-923）：两者都缺席 ⇒ 空串；
     * offset 缺席 ⇒ 从 1 起；只有 limit ⇒ {@code :1-<limit>}；非法 ⇒ 空串。
     *
     * @return {@code ""} / {@code ":5"} / {@code ":5-14"}
     */
    public static String format(Object offset, Object limit) {
        if (offset == null && limit == null) {
            return "";
        }
        Double start = offset == null ? Double.valueOf(1) : parseLineNumber(offset);
        if (start == null) {
            return "";
        }
        if (limit == null) {
            return ":" + formatNumber(start);
        }
        var count = parseLineNumber(limit);
        if (count == null) {
            return "";
        }
        double end = start + count - 1;
        // pi 的 `end ? `:${end}` : ''`——end 为 0 时不写（负数/零宽 limit）。
        return ":" + formatNumber(start) + (end != 0 ? "-" + formatNumber(end) : "");
    }

    /** JS 数字到串的显示形态（整数不带小数点）。 */
    private static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
