package com.pijava.tui.screen;

import java.util.ArrayList;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * 批 29（E2 结构债拆分，ChatScreen 簇⑤纯移动——行为零变化）：扩展注入层
 * （widget 上/下区、自定义 footer/header、扩展状态段、终端列数源）——
 * 批 17（T6，docs/28 §1.5）的全量。渲染装配经 {@link #headerRows()}／
 * {@link #widgetRowsAbove}／{@link #widgetRowsBelow}／{@link #extensionStatusLine()}／
 * {@link #customFooterRows()} 包私有缝回读。
 */
final class ExtensionInjectionLayer {

    /** pi widget 条目两臂（interactive-mode.ts:2413-2436）：文本行集 | 扩展组件
     * （包级——同包测试的断言面）。 */
    sealed interface WidgetEntry {
        /** 文本行臂（截断标记行另计——渲染期定样式）。 */
        record Lines(java.util.List<String> lines, boolean truncated) implements WidgetEntry {}
        /** 组件工厂臂（已实例化）。 */
        record Comp(com.pijava.coding.agent.extension.ExtensionComponent component)
                implements WidgetEntry {}
    }

    /** pi {@code MAX_WIDGET_LINES = 10}（interactive-mode.ts:2486）。 */
    static final int MAX_WIDGET_LINES = 10;

    /** pi extensionWidgetsAbove/Below（:2398 族；LinkedHashMap＝pi Map 插入序）。 */
    private final java.util.Map<String, WidgetEntry> widgetsAbove = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, WidgetEntry> widgetsBelow = new java.util.LinkedHashMap<>();
    /** 自定义 footer/header（null ⇒ 内置/空槽；pi customFooter/customHeader 对位）。 */
    private com.pijava.coding.agent.extension.ExtensionComponent customFooter;
    private com.pijava.coding.agent.extension.ExtensionComponent customHeader;
    /** footer 扩展状态段（pi footerDataProvider.setExtensionStatus 对位；跨线程 ⇒ 并发表）。 */
    private final java.util.Map<String, String> extensionStatuses =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 终端列数源（widget/header/footer 组件 render(width)＋状态行截断；PiTuiApp 注入）。 */
    private java.util.function.IntSupplier terminalWidth = () -> 80;

    /**
     * pi {@code setExtensionWidget}（interactive-mode.ts:2398-2438 逐字序）：
     * placement 默认 aboveEditor；同 key 两区先清（dispose 旧组件臂）；
     * {@code content == null} ⇒ 清＋重排（渲染现读 map，重排天然）；
     * 文本行臂截断 {@link #MAX_WIDGET_LINES}＋{@code "... (widget truncated)"}
     * （muted，文案逐字【模型不可见】）；工厂臂调工厂得组件。
     */
    void setExtensionWidget(String key,
                            com.pijava.coding.agent.extension.ExtensionWidgetContent content,
                            com.pijava.coding.agent.extension.ExtensionWidgetOptions options,
                            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        removeWidget(widgetsAbove, key);
        removeWidget(widgetsBelow, key);
        if (content == null) {
            return;
        }
        WidgetEntry entry;
        if (content instanceof com.pijava.coding.agent.extension.ExtensionWidgetContent.Lines lines) {
            var text = new ArrayList<>(lines.lines());
            boolean truncated = text.size() > MAX_WIDGET_LINES;
            if (truncated) {
                text = new ArrayList<>(text.subList(0, MAX_WIDGET_LINES));
                text.add("... (widget truncated)");
            }
            entry = new WidgetEntry.Lines(java.util.List.copyOf(text), truncated);
        } else {
            var factory =
                ((com.pijava.coding.agent.extension.ExtensionWidgetContent.Factory) content).factory();
            entry = new WidgetEntry.Comp(factory.create(env));
        }
        String placement = options != null && options.placement() != null
            ? options.placement()
            : com.pijava.coding.agent.extension.ExtensionWidgetOptions.ABOVE_EDITOR;
        (com.pijava.coding.agent.extension.ExtensionWidgetOptions.BELOW_EDITOR.equals(placement)
            ? widgetsBelow : widgetsAbove).put(key, entry);
    }

    /** pi {@code removeExisting}（:2403-2407）：组件臂 dispose 后删键。 */
    private static void removeWidget(java.util.Map<String, WidgetEntry> map, String key) {
        var existing = map.remove(key);
        if (existing instanceof WidgetEntry.Comp comp) {
            try {
                comp.component().dispose();
            } catch (Exception ignored) {
                // pi：dispose 异常不外溢（custom 关闭路径的 try/catch 同旨）
            }
        }
    }

    /**
     * pi {@code setExtensionFooter}（:2524-2549 逐字序）：dispose 旧 custom ⇒ 清槽 ⇒
     * 有工厂挂新、无则回内置 ⇒ 标脏（渲染现读字段，标脏经 dispatcher wake 自然发生）。
     */
    void setExtensionFooter(
            com.pijava.coding.agent.extension.ExtensionFooterFactory factory,
            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        disposeQuietly(customFooter);
        customFooter = factory == null ? null
            : factory.create(env, new com.pijava.coding.agent.extension.FooterDataView(
                () -> null,     // git 分支：java footer 数据本无 provider（窄差并入 D100）
                () -> java.util.Map.copyOf(extensionStatuses)));
    }

    /**
     * pi {@code setExtensionHeader}（:2551-2595）：java 无常驻内置 header
     * （WelcomeOverlay 是转录消息）⇒ 内置臂＝空，set(null)＝清槽（窄差 D100 族注记）；
     * dispose 旧 custom 逐字。
     */
    void setExtensionHeader(
            com.pijava.coding.agent.extension.ExtensionSlotFactory factory,
            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        disposeQuietly(customHeader);
        customHeader = factory == null ? null : factory.create(env);
    }

    /** pi {@code footerDataProvider.setExtensionStatus}（:2300-2302）：undefined＝删键，否则覆盖。 */
    void setExtensionStatus(String key, String text) {
        if (text == null) {
            extensionStatuses.remove(key);
        } else {
            extensionStatuses.put(key, text);
        }
    }

    /**
     * pi {@code resetExtensionUI} 的 ChatScreen 段（interactive-mode.ts:2452-2483）：
     * 清 widget 两区（组件臂 dispose）⇒ footer/header 回内置 ⇒ 清扩展状态段。
     */
    void resetExtensionUi() {
        for (var key : java.util.List.copyOf(widgetsAbove.keySet())) {
            removeWidget(widgetsAbove, key);
        }
        for (var key : java.util.List.copyOf(widgetsBelow.keySet())) {
            removeWidget(widgetsBelow, key);
        }
        disposeQuietly(customFooter);
        customFooter = null;
        disposeQuietly(customHeader);
        customHeader = null;
        extensionStatuses.clear();
    }

    private static void disposeQuietly(
            com.pijava.coding.agent.extension.ExtensionComponent component) {
        if (component != null) {
            try {
                component.dispose();
            } catch (Exception ignored) {
                // pi 逐字：dispose 异常吞掉
            }
        }
    }

    /** 终端列数源注入（PiTuiApp 装配）。 */
    void setTerminalWidthSource(java.util.function.IntSupplier source) {
        terminalWidth = source == null ? () -> 80 : source;
    }

    /** widget 上区行集（ChatScreen render/renderBottomArea 装配缝——原直读 widgetsAbove）。 */
    java.util.List<Element> widgetRowsAbove(boolean spacerWhenEmpty, boolean leadingSpacer) {
        return widgetRows(widgetsAbove, spacerWhenEmpty, leadingSpacer);
    }

    /** widget 下区行集（同上前缀——原直读 widgetsBelow）。 */
    java.util.List<Element> widgetRowsBelow(boolean spacerWhenEmpty, boolean leadingSpacer) {
        return widgetRows(widgetsBelow, spacerWhenEmpty, leadingSpacer);
    }

    /** widget 区行集（pi renderWidgetContainer :2491-2516 的 spacer 序逐字）。 */
    private java.util.List<Element> widgetRows(java.util.Map<String, WidgetEntry> widgets,
                                               boolean spacerWhenEmpty, boolean leadingSpacer) {
        var rows = new ArrayList<Element>();
        if (widgets.isEmpty()) {
            if (spacerWhenEmpty) {
                rows.add(TamboUIAdapter.spacer(1));
            }
            return rows;
        }
        if (leadingSpacer) {
            rows.add(TamboUIAdapter.spacer(1));
        }
        int width = Math.max(1, terminalWidth.getAsInt());
        for (var entry : widgets.values()) {
            if (entry instanceof WidgetEntry.Lines lines) {
                for (int i = 0; i < lines.lines().size(); i++) {
                    var line = lines.lines().get(i);
                    // 截断标记行 muted（pi ThemedText theme.fg("muted",…) 对位）。
                    boolean marker = lines.truncated() && i == lines.lines().size() - 1;
                    var escaped = com.pijava.tui.util.TextLayout.escapeMarkup(line);
                    rows.add(TamboUIAdapter.markupText(
                        marker ? "[gray]" + escaped + "[/]" : escaped));
                }
            } else {
                for (var line : ((WidgetEntry.Comp) entry).component().render(width)) {
                    rows.add(TamboUIAdapter.markupText(line));   // colorize 产物＝markup
                }
            }
        }
        return rows;
    }

    /** 扩展状态行（pi footer.ts:289-298 逐字）：key 字典序 ⇒ sanitize ⇒ join(" ")
     *  ⇒ 超宽截断＋dim "..."；空表不渲染。 */
    Element extensionStatusLine() {
        var text = extensionStatusLineText(Math.max(1, terminalWidth.getAsInt()));
        return text == null ? null
            : TamboUIAdapter.markupText(
                "[dim]" + com.pijava.tui.util.TextLayout.escapeMarkup(text) + "[/]");
    }

    /** 状态行文本（测试断言面；空表 ⇒ null）。 */
    String extensionStatusLineText(int width) {
        if (extensionStatuses.isEmpty()) {
            return null;
        }
        var sorted = extensionStatuses.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(e -> sanitizeStatusText(e.getValue()))
            .toList();
        return UserMessageSelectorScreen.truncateToWidth(
            String.join(" ", sorted), Math.max(1, width));
    }

    /** pi {@code sanitizeStatusText}（footer.ts:14-20 逐字）：[\r\n\t]→空格→多空格折叠→trim。 */
    static String sanitizeStatusText(String text) {
        return text.replaceAll("[\\r\\n\\t]", " ").replaceAll(" +", " ").trim();
    }

    /** 自定义 footer 行集（null ⇒ 调用方回内置）。 */
    java.util.List<Element> customFooterRows() {
        if (customFooter == null) {
            return java.util.List.of();
        }
        var rows = new ArrayList<Element>();
        for (var line : customFooter.render(Math.max(1, terminalWidth.getAsInt()))) {
            rows.add(TamboUIAdapter.markupText(line));
        }
        return rows;
    }

    /** header 槽行集（null/空 ⇒ 无行）。 */
    java.util.List<Element> headerRows() {
        if (customHeader == null) {
            return java.util.List.of();
        }
        var rows = new ArrayList<Element>();
        for (var line : customHeader.render(Math.max(1, terminalWidth.getAsInt()))) {
            rows.add(TamboUIAdapter.markupText(line));
        }
        return rows;
    }

    /**
     * 批 17（T6）：扩展注入层在 inline 底部位的行数（高度驱动——
     * {@code PiTuiApp.updateBottomHeight} 的加项）。
     */
    int extensionAreaLineCount() {
        int lines = headerRows().size()
            + widgetRows(widgetsAbove, true, true).size()
            + widgetRows(widgetsBelow, false, false).size();
        if (!extensionStatuses.isEmpty()) {
            lines++;
        }
        if (customFooter != null) {
            lines += customFooterRows().size() - 1;    // 替换内置一行 ⇒ 净增行数
        }
        return lines;
    }

    /** 测试探针：扩展状态段的现读面。 */
    java.util.Map<String, String> extensionStatusesView() {
        return java.util.Map.copyOf(extensionStatuses);
    }

    /** 测试探针：widget 区内容（上/下）。 */
    java.util.Map<String, WidgetEntry> widgetsForProbe(boolean above) {
        return java.util.Map.copyOf(above ? widgetsAbove : widgetsBelow);
    }

    /** 测试探针：自定义 footer 是否在槽。 */
    boolean hasCustomFooter() {
        return customFooter != null;
    }

    /** 测试探针：自定义 header 是否在槽。 */
    boolean hasCustomHeader() {
        return customHeader != null;
    }
}
