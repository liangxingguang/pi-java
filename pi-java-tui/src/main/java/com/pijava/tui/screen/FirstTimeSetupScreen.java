package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次设置向导状态机（L7 W7，S18）：pi
 * {@code components/first-time-setup.ts} 的 java 对位。
 *
 * <p>两步：主题（system/dark/light，选主题即预览）→ 匿名用量分析
 * （sharing / don't share）。渲染文本由 {@link #lines(int)} 逐帧重建，
 * 主题切换后重着色（pi {@code update()} 同形）。</p>
 */
public final class FirstTimeSetupScreen {

    /** 主题候选（pi {@code THEME_OPTIONS} 逐字，system 值对位）。 */
    public static final List<Option> THEMES = List.of(
        new Option("system", "System (matches your terminal colors)"),
        new Option("dark", "Dark"),
        new Option("light", "Light"));

    /** 分析候选（pi {@code ANALYTICS_OPTIONS} 逐字）。 */
    public static final List<Option> ANALYTICS = List.of(
        new Option("true", "Share anonymous usage data"),
        new Option("false", "Don't share"));

    /** 选项（值＋展示文案）。 */
    public record Option(String value, String label) {}

    /** 提交结果。 */
    public record Result(String theme, boolean shareAnalytics) {}

    /** 主题预览回调。 */
    @FunctionalInterface
    public interface ThemePreview {
        void preview(String themeName);
    }

    private enum Step { THEME, ANALYTICS }

    private static final String[] LOGO = {"██████", "██  ██", "████  ██", "██    ██"};

    private Step step = Step.THEME;
    private int themeIndex;
    private int analyticsIndex;
    private boolean cancelled;

    /** 移动选择（-1 上 / +1 下）；主题步触发预览。 */
    public void move(int delta, ThemePreview preview) {
        if (step == Step.THEME) {
            int next = Math.max(0, Math.min(THEMES.size() - 1, themeIndex + delta));
            if (next != themeIndex) {
                themeIndex = next;
                preview.preview(THEMES.get(themeIndex).value());
            }
        } else {
            analyticsIndex = Math.max(0, Math.min(ANALYTICS.size() - 1, analyticsIndex + delta));
        }
    }

    /** Enter：主题步进下一屏；分析步完成。返回 true 表示已可提交。 */
    public boolean confirm() {
        if (step == Step.THEME) {
            step = Step.ANALYTICS;
            return false;
        }
        return true;
    }

    /** Esc：跳过设置（不写盘）。 */
    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** 当前结果（仅在 {@link #confirm()} 返回 true 后有意义）。 */
    public Result result() {
        return new Result(THEMES.get(themeIndex).value(),
            Boolean.parseBoolean(ANALYTICS.get(analyticsIndex).value()));
    }

    /** 渲染文本行（宽用于未来折行；当前文案均短于常见宽度）。 */
    public List<String> lines(int width) {
        var out = new ArrayList<String>();
        out.addAll(List.of(LOGO));
        out.add("");
        out.add("Welcome to pi-java, the minimal coding agent.");
        out.add("");
        if (step == Step.THEME) {
            out.add("Pick a theme.");
            out.add("");
            addOptions(out, THEMES, themeIndex);
        } else {
            out.add("Opt-in to anonymous usage data sharing?");
            out.add("");
            out.add("Opting in stores a tracking identifier in settings.json and enables anonymous");
            out.add("usage analytics. This helps us to better debug, reproduce, and resolve issues");
            out.add("and bugs within pi-java. You can observe what is shared using /privacy and make");
            out.add("changes anytime in settings.json.");
            out.add("");
            addOptions(out, ANALYTICS, analyticsIndex);
        }
        return out;
    }

    /** 底部键位提示（pi 逐字键名与措辞）。 */
    public String hint() {
        String action = step == Step.THEME ? "continue" : "finish";
        return "↑↓ navigate  Enter " + action + "  Esc skip setup";
    }

    private static void addOptions(List<String> out, List<Option> options, int selected) {
        for (int i = 0; i < options.size(); i++) {
            String prefix = i == selected ? "→ " : "  ";
            out.add(prefix + options.get(i).label());
        }
    }
}
