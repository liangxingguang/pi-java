package com.pijava.tui.component.tree;

/**
 * pi {@code FilterMode}（{@code tree-selector.ts:95}）对位：树选择器的五种
 * 显示过滤。纯常量闭集 ⇒ enum；线值（settings.json 的 {@code treeFilterMode}）
 * 用小写串。
 */
public enum FilterMode {
    /** 默认：隐 settings/簿记条目。 */
    DEFAULT("default"),
    /** 默认再减 toolResult。 */
    NO_TOOLS("no-tools"),
    /** 仅 user 消息。 */
    USER_ONLY("user-only"),
    /** 仅有标签的条目。 */
    LABELED_ONLY("labeled-only"),
    /** 全显。 */
    ALL("all");

    private final String wireName;

    FilterMode(String wireName) {
        this.wireName = wireName;
    }

    /** pi 的字符串值（settings 持久化形）。 */
    public String wireName() {
        return wireName;
    }

    /** 由 settings 值解析；非法值回退 {@link #DEFAULT}（pi 读时校验对位）。 */
    public static FilterMode parse(String value) {
        for (var mode : values()) {
            if (mode.wireName.equals(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    /** pi 过滤环的固定序（cycleForward/cycleBackward 用）。 */
    static FilterMode[] cycleOrder() {
        return new FilterMode[] { DEFAULT, NO_TOOLS, USER_ONLY, LABELED_ONLY, ALL };
    }
}
