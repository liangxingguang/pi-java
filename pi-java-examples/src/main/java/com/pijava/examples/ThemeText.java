package com.pijava.examples;

/**
 * pi {@code theme.fg(slot, text)} 在 <b>handler 层</b>的窄化投影（docs/27 §3.4）。
 *
 * <p>pi 的 {@code ctx.ui.theme} 是结构化 Theme 对象，可在任何处理器里着色；
 * java 的 {@link com.pijava.coding.agent.extension.ExtensionUI#theme()} 只给主题
 * <b>名</b>，且注入槽位的着色通道（{@link com.pijava.coding.agent.extension
 * .ExtensionUiHostEnv#colorize()}）只在组件工厂内可见——handler 侧没有对等通道。
 * 因此本类恒等返回：示例的结构、文本与键位逐字保留，只有色槽丢失。</p>
 *
 * <p>用户可见文本因此<b>不带</b>颜色，验收按纯文本断言（docs/27 §3.4 的
 * 「border-status-editor、status-line、model-status、working-indicator」同族窄差）。
 * 组件工厂内的着色<b>不</b>走这里——那一路是真实的 {@code env.colorize}。</p>
 */
final class ThemeText {

    private ThemeText() {}

    /**
     * pi {@code theme.fg(color, text)} 的降级形。
     *
     * @param slot pi 的色槽名（{@code "dim"}/{@code "accent"}/{@code "success"}/…）；
     *             降级后不参与输出，只为保留与 pi 源码的一一对应
     * @param text 待着色文本
     * @return 原样返回的文本
     */
    static String fg(String slot, String text) {
        return text;
    }
}
