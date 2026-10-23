package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import com.pijava.coding.agent.rpc.RpcExtensionUIRequest;
import com.pijava.coding.agent.rpc.RpcExtensionUIResponse;

/**
 * 批 17（T1，docs/28 裁决①）：扩展 UI 服务——pi {@code ExtensionUIContext}
 * （types.ts:149-323）**全 28 成员**的 java 对位。每个成员的 default 实现逐字对位
 * pi 的 {@code noOpUIContext}（runner.ts:324-352：无 UI 环境下全部立即返回
 * 取消值/空操作）。
 *
 * <p>各模式的实现面：TUI 经 {@code TuiExtensionUI}（docs/28 T4–T6）、RPC 经
 * {@code RpcExtensionUI} 九方法通道（T3）；余 15 成员的 TUI 臂随后续批
 * （docs/28 §4）。</p>
 *
 * <p>pi 的异步 Promise 在 java 收敛为<b>阻塞语义</b>（调用线程等结果——
 * {@code select/confirm/input/editor/custom} 五件）。{@code null ≙ pi 的
 * undefined}。</p>
 */
public interface ExtensionUI {

    // ── 阻塞对话四件套（pi Promise<string|undefined>/Promise<boolean>）──

    /** pi {@code select}（:161）：选择器；取消 ⇒ {@code null}。 */
    default @Nullable String select(String title, List<String> options,
                                    @Nullable ExtensionUiDialogOptions opts) {
        return null;
    }

    /** pi {@code confirm}（:164）：确认框；取消 ⇒ {@code false}。 */
    default boolean confirm(String title, String message,
                            @Nullable ExtensionUiDialogOptions opts) {
        return false;
    }

    /** pi {@code input}（:167）：单行输入；取消 ⇒ {@code null}。 */
    default @Nullable String input(String title, @Nullable String placeholder,
                                   @Nullable ExtensionUiDialogOptions opts) {
        return null;
    }

    /** pi {@code editor}（:228）：多行编辑器；取消 ⇒ {@code null}。 */
    default @Nullable String editor(String title, @Nullable String prefill) {
        return null;
    }

    /**
     * pi {@code custom<T>}（:213-227）：扩展自有组件带键盘焦点挂上屏；
     * {@code done(result)} 回送的结果即返回值；取消/不支持 ⇒ {@code null}。
     */
    default <T> @Nullable T custom(ExtensionComponentFactory<T> factory,
                                   @Nullable ExtensionCustomOptions options) {
        return null;
    }

    // ── 通知与状态 ──

    /** pi {@code notify}（:170）：通知；{@code type} null ⇒ info。 */
    default void notify(String message, @Nullable NotifyType type) { }

    /** pi {@code setStatus}（:176）：footer 扩展状态段；{@code text} null ⇒ 删键。 */
    default void setStatus(String key, @Nullable String text) { }

    /** pi {@code setWidget}（:181-187）：编辑器上/下 widget 槽；{@code content} null ⇒ 清。 */
    default void setWidget(String key, @Nullable ExtensionWidgetContent content,
                           @Nullable ExtensionWidgetOptions options) { }

    /** pi {@code setFooter}（:189-196）：自定义 footer；null ⇒ 回内置。 */
    default void setFooter(@Nullable ExtensionFooterFactory factory) { }

    /** pi {@code setHeader}（:198）：自定义 header；null ⇒ 回内置。 */
    default void setHeader(@Nullable ExtensionSlotFactory factory) { }

    /** pi {@code setTitle}（:201）：终端窗口/标签页标题。 */
    default void setTitle(String title) { }

    // ── working 指示器与思考标签（TUI 臂随后续批，docs/28 §4）──

    /** pi {@code setWorkingMessage}（:179）；null ⇒ 恢复默认。 */
    default void setWorkingMessage(@Nullable String message) { }

    /** pi {@code setWorkingVisible}（:182）。 */
    default void setWorkingVisible(boolean visible) { }

    /** pi {@code setWorkingIndicator}（:193）；null ⇒ 恢复默认动画。 */
    default void setWorkingIndicator(@Nullable WorkingIndicatorOptions options) { }

    /** pi {@code setHiddenThinkingLabel}（:196）；null ⇒ 恢复默认。 */
    default void setHiddenThinkingLabel(@Nullable String label) { }

    // ── 编辑器面 ──

    /** pi {@code pasteToEditor}（:230）：粘贴进编辑器（大内容触发折叠处理）。 */
    default void pasteToEditor(String text) { }

    /** pi {@code setEditorText}（:233）。 */
    default void setEditorText(String text) { }

    /** pi {@code getEditorText}（:236）；noOp ⇒ {@code ""}。 */
    default String getEditorText() {
        return "";
    }

    /** pi {@code addAutocompleteProvider}（:240）：叠一层补全行为。 */
    default void addAutocompleteProvider(
            UnaryOperator<ExtensionAutocompleteProvider> factory) { }

    /** pi {@code setEditorComponent}（:270）；null ⇒ 恢复默认编辑器。 */
    default void setEditorComponent(@Nullable ExtensionEditorFactory factory) { }

    /** pi {@code getEditorComponent}（:273）；未自定义 ⇒ {@code null}。 */
    default @Nullable ExtensionEditorFactory getEditorComponent() {
        return null;
    }

    // ── 主题面（批 20 联动，docs/28 §4）──

    /**
     * pi {@code theme}（:276）的窄化投影：当前主题<b>名</b>（java 主题系统是
     * TCSS 名/路径，结构化 Theme 对象随批 20）。
     */
    default @Nullable String theme() {
        return null;
    }

    /** pi {@code getAllThemes}（:279）；noOp ⇒ 空表。 */
    default List<ThemeEntry> getAllThemes() {
        return List.of();
    }

    /** pi {@code getTheme}（:282）：按名加载（不切换）；未找到 ⇒ {@code null}。 */
    default @Nullable ThemeEntry getTheme(String name) {
        return null;
    }

    /** pi {@code setTheme}（:285）；noOp ⇒ {@code {success:false, error:"UI not available"}}（逐字）。 */
    default SetThemeResult setTheme(String theme) {
        return new SetThemeResult(false, "UI not available");
    }

    /** pi {@code getToolsExpanded}（:288）。 */
    default boolean getToolsExpanded() {
        return false;
    }

    /** pi {@code setToolsExpanded}（:291）。 */
    default void setToolsExpanded(boolean expanded) { }

    // ── 终端输入 ──

    /**
     * pi {@code onTerminalInput}（:173）：原始终端输入监听（interactive 限定）。
     *
     * @return 退订闭包（noOp ⇒ 空闭包）
     */
    default Runnable onTerminalInput(TerminalInputHandler handler) {
        return () -> { };
    }

    // ── RPC 臂内部通道（docs/28 §3-T1.1：不再是主接口面）──

    /**
     * RPC 九方法通道的原始请求口（{@code RpcDispatcher} 的阻塞 poll 对端）。
     * noOp 语义沿批前：立即回 {@code confirm(id, false)}（≙ 取消）。
     */
    default RpcExtensionUIResponse request(RpcExtensionUIRequest request) {
        return RpcExtensionUIResponse.confirm(request.id(), false);
    }

    // ── 主题条目与 setTheme 结果 ──

    /** pi {@code { name: string; path: string | undefined }}（types.ts:279）。 */
    record ThemeEntry(String name, @Nullable String path) {}

    /** pi {@code { success: boolean; error?: string }}（types.ts:285）。 */
    record SetThemeResult(boolean success, @Nullable String error) {}

    // ── noOp 单例 ──

    /**
     * 无 UI 环境的默认实现（pi {@code noOpUIContext} 单例对位，runner.ts:324-352）
     * ——<b>恒等比较</b>需要单例（pi {@code uiContext !== noOpUIContext}，
     * runner.ts:622 ⇒ java {@code ui == ExtensionUI.NOOP}）。
     */
    ExtensionUI NOOP = new ExtensionUI() { };

    /** 无 UI 通道时的 no-op 实现（批 17 起委托 {@link #NOOP} 单例）。 */
    static ExtensionUI noop() {
        return NOOP;
    }
}
