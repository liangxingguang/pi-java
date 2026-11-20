package com.pijava.tui.component.session;

import com.pijava.coding.agent.pkg.PathComparisonKeys;

/**
 * docs/63（L7 W3，pi {@code session-selector.ts:42-44} {@code getSessionPathKey}）：
 * 「同一会话」的比较键——<b>先 realpath（失败回落原串），再绝对化+normalize，
 * Windows 上折整串小写</b>。
 *
 * <p>消费面（pi 同）：树按键去重、{@code parentSessionPath} 转键后认父、当前会话
 * 高亮/禁删。<b>选中恢复仍用原始字符串</b>（{@code SessionListModel#setSessions}，
 * pi {@code :353-362}）——pi 自身不对称，不要"顺手统一"。</p>
 */
public final class SessionPathKey {

    private SessionPathKey() {}

    /** 比较键；{@code null} 原样返回（调用方自行判空）。 */
    public static String key(String path) {
        if (path == null) {
            return null;
        }
        return PathComparisonKeys.key(SessionTreeBuilder.canonicalizePath(path));
    }

    /** 两个路径是否指向同一会话（{@code null} 只与 {@code null} 相等）。 */
    public static boolean same(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return key(left).equals(key(right));
    }
}
