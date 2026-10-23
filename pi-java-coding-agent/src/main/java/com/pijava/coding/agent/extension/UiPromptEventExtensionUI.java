package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.function.Supplier;

/**
 * 批 17（T2，docs/28 §3-T2.4）：pi {@code wrapUIPromptContext}（runner.ts:585-596）
 * ＋{@code withUIPrompt}（:598-622）的 java 装饰器——只包**五个阻塞方法**
 * （select/confirm/input/editor/custom），其余 23 个成员全部直委托。
 *
 * <p>逐字语义：</p>
 * <ul>
 *   <li><b>深度计数</b>：嵌套调用（如 custom 里再 select）只有最外层发
 *       {@code ui_prompt_start}；深度归零才发 {@code ui_prompt_end}，且 end 的
 *       kind/title 取<b>最外层记录值</b>（{@code activeUIPrompt}），丢失时回落当前调用。</li>
 *   <li><b>异常安全</b>：委托同步抛也先 finish 再重抛（深度恒复位——pi 的
 *       try/catch 对位；java 无 Promise 回调，{@code finally} 即 {@code .finally(finish)}）。</li>
 *   <li><b>异步发射</b>：pi 的 {@code queueMicrotask} 对位＝虚拟线程
 *       （事件不阻塞 UI 调用本身）。</li>
 * </ul>
 *
 * <p>java 的阻塞语义下包装是同步的：事件区间即调用区间（pi 的 Promise 存活区间
 * 对位）。</p>
 */
final class UiPromptEventExtensionUI implements ExtensionUI {

    /** pi {@code activeUIPrompt} 的记录形（runner.ts:600）。 */
    private record ActivePrompt(String kind, String title) {}

    private final ExtensionUI delegate;
    private final Supplier<SessionEventFirer> firer;
    /** pi {@code uiPromptDepth}（runner 字段——java 随包装器；synchronized 保护）。 */
    private int depth;
    private ActivePrompt activePrompt;

    UiPromptEventExtensionUI(ExtensionUI delegate, Supplier<SessionEventFirer> firer) {
        this.delegate = delegate;
        this.firer = firer;
    }

    // ── 五件包装（pi runner.ts:588-595 逐字序）──

    @Override
    public String select(String title, List<String> options, ExtensionUiDialogOptions opts) {
        return withUIPrompt("select", title, () -> delegate.select(title, options, opts));
    }

    @Override
    public boolean confirm(String title, String message, ExtensionUiDialogOptions opts) {
        return withUIPrompt("confirm", title, () -> delegate.confirm(title, message, opts));
    }

    @Override
    public String input(String title, String placeholder, ExtensionUiDialogOptions opts) {
        return withUIPrompt("input", title, () -> delegate.input(title, placeholder, opts));
    }

    @Override
    public String editor(String title, String prefill) {
        return withUIPrompt("editor", title, () -> delegate.editor(title, prefill));
    }

    @Override
    public <T> T custom(ExtensionComponentFactory<T> factory, ExtensionCustomOptions options) {
        return withUIPrompt("custom", null, () -> delegate.custom(factory, options));
    }

    /** pi {@code withUIPrompt}（runner.ts:598-622 逐字——java 同步形）。 */
    private <T> T withUIPrompt(String kind, String title, Supplier<T> run) {
        boolean outerPrompt;
        synchronized (this) {
            outerPrompt = depth++ == 0;
            if (outerPrompt) {
                activePrompt = new ActivePrompt(kind, title);
            }
        }
        if (outerPrompt) {
            emitStart(kind, title);
        }
        try {
            return run.get();
        } finally {
            finish(kind, title);
        }
    }

    /** pi {@code finish}（runner.ts:606-614 逐字）。 */
    private void finish(String kind, String title) {
        ActivePrompt prompt;
        synchronized (this) {
            if (--depth > 0) {
                return;
            }
            depth = 0;
            prompt = activePrompt != null ? activePrompt : new ActivePrompt(kind, title);
            activePrompt = null;
        }
        emitEnd(prompt.kind(), prompt.title());
    }

    /**
     * pi {@code emitUIPromptEvent}（runner.ts:624-626）：{@code queueMicrotask} 对位——
     * 共享单线程虚拟线程执行器（microtask 队列的 <b>FIFO 序</b>对位：每事件独立
     * {@code Thread.start} 不保序，start/end 可能倒挂）。
     */
    private static final java.util.concurrent.ExecutorService UI_PROMPT_EVENTS =
        java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    private void emitStart(String kind, String title) {
        var f = firer.get();
        if (f != null) {
            UI_PROMPT_EVENTS.execute(() -> f.uiPromptStart(kind, title));
        }
    }

    private void emitEnd(String kind, String title) {
        var f = firer.get();
        if (f != null) {
            UI_PROMPT_EVENTS.execute(() -> f.uiPromptEnd(kind, title));
        }
    }

    // ── 其余 23 成员直委托（pi 的 {@code ...ui} 展开对位）──

    @Override
    public void notify(String message, NotifyType type) {
        delegate.notify(message, type);
    }

    @Override
    public void setStatus(String key, String text) {
        delegate.setStatus(key, text);
    }

    @Override
    public void setWidget(String key, ExtensionWidgetContent content,
                          ExtensionWidgetOptions options) {
        delegate.setWidget(key, content, options);
    }

    @Override
    public void setFooter(ExtensionFooterFactory factory) {
        delegate.setFooter(factory);
    }

    @Override
    public void setHeader(ExtensionSlotFactory factory) {
        delegate.setHeader(factory);
    }

    @Override
    public void setTitle(String title) {
        delegate.setTitle(title);
    }

    @Override
    public void setWorkingMessage(String message) {
        delegate.setWorkingMessage(message);
    }

    @Override
    public void setWorkingVisible(boolean visible) {
        delegate.setWorkingVisible(visible);
    }

    @Override
    public void setWorkingIndicator(WorkingIndicatorOptions options) {
        delegate.setWorkingIndicator(options);
    }

    @Override
    public void setHiddenThinkingLabel(String label) {
        delegate.setHiddenThinkingLabel(label);
    }

    @Override
    public void pasteToEditor(String text) {
        delegate.pasteToEditor(text);
    }

    @Override
    public void setEditorText(String text) {
        delegate.setEditorText(text);
    }

    @Override
    public String getEditorText() {
        return delegate.getEditorText();
    }

    @Override
    public void addAutocompleteProvider(
            java.util.function.UnaryOperator<ExtensionAutocompleteProvider> factory) {
        delegate.addAutocompleteProvider(factory);
    }

    @Override
    public void setEditorComponent(ExtensionEditorFactory factory) {
        delegate.setEditorComponent(factory);
    }

    @Override
    public ExtensionEditorFactory getEditorComponent() {
        return delegate.getEditorComponent();
    }

    @Override
    public String theme() {
        return delegate.theme();
    }

    @Override
    public List<ThemeEntry> getAllThemes() {
        return delegate.getAllThemes();
    }

    @Override
    public ThemeEntry getTheme(String name) {
        return delegate.getTheme(name);
    }

    @Override
    public SetThemeResult setTheme(String theme) {
        return delegate.setTheme(theme);
    }

    @Override
    public boolean getToolsExpanded() {
        return delegate.getToolsExpanded();
    }

    @Override
    public void setToolsExpanded(boolean expanded) {
        delegate.setToolsExpanded(expanded);
    }

    @Override
    public Runnable onTerminalInput(TerminalInputHandler handler) {
        return delegate.onTerminalInput(handler);
    }

    @Override
    public com.pijava.coding.agent.rpc.RpcExtensionUIResponse request(
            com.pijava.coding.agent.rpc.RpcExtensionUIRequest request) {
        return delegate.request(request);
    }

    /** 未包装的委托（恒等比较/测试面）。 */
    ExtensionUI delegate() {
        return delegate;
    }
}
