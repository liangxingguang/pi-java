package com.pijava.tui.component.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.spi.SessionListLoader;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.component.session.SessionListRenderer.Span;
import com.pijava.tui.component.session.SessionSelectorHeader.Scope;
import com.pijava.tui.component.session.SessionSelectorHeader.StatusMessage;
import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * pi {@code SessionSelectorComponent}（session-selector.ts:694-1045）的 java 落形
 * （批 14，docs/25 T10）：装配（header＋搜索输入＋列表）＋双 scope 加载状态机
 * （:941-1007 逐字）＋删除流（:842-866）＋键分派（:540-646 分派序逐字）。
 *
 * <p>picker 语境留白（裁决⑦，与 pi 逐字节一致）：rename 回调恒 null（ctrl+r
 * 键臂消费 no-op）、当前会话高亮/删除守卫不可达、rename 面板 UI 不移植（D72）、
 * {@code onExit} 死钩不移植（pi :302 赋值无调用点）。</p>
 *
 * <p>线程模型：{@code loadScope} 在注入的 {@code loadExecutor}（生产＝虚拟线程）
 * 跑 loader，回调经 {@code uiDispatcher} 编排到 UI 线程（生产＝runner 每 33ms
 * drain；测试＝直跑 executor/dispatcher，全同步确定）。</p>
 */
public final class SessionSelectorComponent {

    /** pi 状态消息三档时长（:858/:862/:1003）。 */
    private static final int STATUS_DELETED_MS = 2000;
    private static final int STATUS_ERROR_MS = 3000;
    private static final int STATUS_LOAD_FAILED_MS = 4000;

    /** 在飞加载的句柄（pi AbortController 对位；identity 比较即 pi :955 的 isActive）。 */
    private record LoadHandle(AbortSignal signal) {}

    private final SessionSelectorHeader header;
    private final SessionListModel model;
    private final SessionListRenderer renderer;
    private final EditorComponent searchEditor = new EditorComponent();
    private final KeybindingsManager keybindings;
    private final SessionListLoader currentLoader;
    private final SessionListLoader allLoader;
    private final Executor loadExecutor;
    private final Consumer<Runnable> uiDispatcher;

    private Scope scope = Scope.CURRENT;
    private SortMode sortMode = SortMode.THREADED;
    private NameFilter nameFilter = NameFilter.ALL;
    private List<RichSessionInfo> currentSessions;   // null＝未载（pi :716-717）
    private List<RichSessionInfo> allSessions;
    private LoadHandle currentLoad;
    private LoadHandle allLoad;

    private String selectedPath;
    private boolean cancelled;
    private boolean done;

    /**
     * @param currentLoader    项目内加载器（pi {@code SessionManager.list(cwd, ...)}）
     * @param allLoader        跨项目加载器（pi {@code SessionManager.listAll(...)}）
     * @param keybindings      键位表（选择器六键在 overlay 表，T3）
     * @param loadExecutor     加载执行器（生产＝虚拟线程；测试＝直跑）
     * @param uiDispatcher     UI 线程编排（生产＝runner drain；测试＝直跑）
     * @param clock            状态消息 deadline 时钟
     * @param currentSessionFilePath picker 恒 {@code null}（裁决⑦）
     */
    public SessionSelectorComponent(SessionListLoader currentLoader, SessionListLoader allLoader,
                                    KeybindingsManager keybindings, Executor loadExecutor,
                                    Consumer<Runnable> uiDispatcher, LongSupplier clock,
                                    String currentSessionFilePath) {
        this.currentLoader = currentLoader;
        this.allLoader = allLoader;
        this.keybindings = keybindings;
        this.loadExecutor = loadExecutor;
        this.uiDispatcher = uiDispatcher;
        this.model = new SessionListModel(currentSessionFilePath);
        this.header = new SessionSelectorHeader(scope, sortMode, nameFilter,
            false, keybindings, clock);   // showRenameHint: picker 恒 false（pi :98）
        this.renderer = new SessionListRenderer(model, KeybindingHints.keyText(
            keybindings.strokeFor(KeybindingsManager.SESSION_TOGGLE_NAMED_FILTER)));
        this.model.filterSessions("");
        // pi :869：构造即加载 current scope。
        loadScope(Scope.CURRENT);
    }

    // ── 结局探针 ─────────────────────────────────────────────

    /** 定案/取消后 true（runner 据此 quit）。 */
    public boolean isDone() {
        return done;
    }

    /** 选中会话 path；取消 ⇒ empty。 */
    public Optional<String> selectedPath() {
        return Optional.ofNullable(selectedPath);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 状态消息自动隐（runner 每 tick 调）。 */
    public void onTick() {
        header.tick();
    }

    // ── 测试探针（同包断言面）────────────────────────────────

    SessionListModel model() {
        return model;
    }

    SessionSelectorHeader header() {
        return header;
    }

    String searchText() {
        return searchEditor.getText();
    }

    // ── 键分派（pi handleInput :540-646 分派序逐字）────────────

    /** 分派一个键事件（选择器全吞——pi 组件是焦点唯一持有者）。 */
    public boolean onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);

        // 1. 删除确认态拦截一切（pi :543-557）。
        if (model.confirmingDeletePath() != null) {
            if (event.isConfirm()) {
                var pathToDelete = model.confirmingDeletePath();
                model.clearConfirmingDelete();
                header.setConfirmingDeletePath(null);
                deleteSession(pathToDelete);
                return true;
            }
            if (event.isCancel() || event.isCtrlC()) {
                model.clearConfirmingDelete();
                header.setConfirmingDeletePath(null);
                return true;
            }
            return true;   // 其余键吞掉
        }

        // 2. Tab ⇒ scope 切换（pi :559-564；在此不是补全）。
        if (event.isKey(KeyCode.TAB) && !event.hasShift()) {
            toggleScope();
            return true;
        }
        // 3-7. 五 toggles＋rename（pi :566-596；rename 在 picker 无回调 ⇒ 消费 no-op，裁决⑦）。
        if (keybindings.matches(KeybindingsManager.SESSION_TOGGLE_SORT, stroke)) {
            toggleSortMode();
            return true;
        }
        if (keybindings.matches(KeybindingsManager.SESSION_TOGGLE_NAMED_FILTER, stroke)) {
            toggleNameFilter();
            return true;
        }
        if (keybindings.matches(KeybindingsManager.SESSION_TOGGLE_PATH, stroke)) {
            model.setShowPath(!model.showPath());
            header.setShowPath(model.showPath());
            return true;
        }
        if (keybindings.matches(KeybindingsManager.SESSION_DELETE, stroke)) {
            startDeleteConfirmationForSelected();
            return true;
        }
        if (keybindings.matches(KeybindingsManager.SESSION_RENAME, stroke)) {
            return true;   // picker：pi onRenameSession undefined ⇒ 消费 no-op（:590-595）
        }
        // 8. ctrl+backspace（pi :600-609）：查询非空 ⇒ 转发输入框；空 ⇒ 同 delete。
        if (keybindings.matches(KeybindingsManager.SESSION_DELETE_NONINVASIVE, stroke)) {
            if (!searchEditor.getText().isEmpty()) {
                searchEditor.onKeyEvent(event);
                model.filterSessions(searchEditor.getText());
            } else {
                startDeleteConfirmationForSelected();
            }
            return true;
        }

        // 9. 以上均未命中 ⇒ selectionTouched=true，然后导航/定案/取消/转发（pi :611-645）。
        model.touchSelection();
        if (event.isUp()) {
            model.moveUp();
            return true;
        }
        if (event.isDown()) {
            model.moveDown();
            return true;
        }
        if (event.isPageUp()) {
            model.pageUp();
            return true;
        }
        if (event.isPageDown()) {
            model.pageDown();
            return true;
        }
        if (event.isConfirm()) {
            var selected = model.selectedNode();
            if (selected != null) {
                finish(selected.session().path());
            }
            return true;
        }
        if (event.isCancel() || event.isCtrlC()) {
            cancelSelection();
            return true;
        }
        // 其余 ⇒ 全转发搜索输入框并重新过滤（pi :642-645）。
        searchEditor.onKeyEvent(event);
        model.filterSessions(searchEditor.getText());
        return true;
    }

    // ── 结局（pi :800-814）───────────────────────────────────

    private void finish(String path) {
        header.setStatusMessage(null, 0);
        cancelLoads();
        this.selectedPath = path;
        this.done = true;
    }

    private void cancelSelection() {
        header.setStatusMessage(null, 0);
        cancelLoads();
        this.cancelled = true;
        this.done = true;
    }

    // ── toggles（pi :1009-1022）──────────────────────────────

    private void toggleSortMode() {
        // pi :1010-1011：threaded → recent → relevance → threaded。
        sortMode = switch (sortMode) {
            case THREADED -> SortMode.RECENT;
            case RECENT -> SortMode.RELEVANCE;
            case RELEVANCE -> SortMode.THREADED;
        };
        header.setSortMode(sortMode);
        model.setSortMode(sortMode, searchEditor.getText());
    }

    private void toggleNameFilter() {
        nameFilter = nameFilter == NameFilter.ALL ? NameFilter.NAMED : NameFilter.ALL;
        header.setNameFilter(nameFilter);
        model.setNameFilter(nameFilter, searchEditor.getText());
    }

    /** pi {@code toggleScope}（:1031-1040）逐字：目标有缓存 ⇒ 立即 setSessions；无缓存且未在载 ⇒ 懒载。 */
    private void toggleScope() {
        scope = scope == Scope.CURRENT ? Scope.ALL : Scope.CURRENT;
        var sessions = scope == Scope.CURRENT ? currentSessions : allSessions;
        var loading = (scope == Scope.CURRENT ? currentLoad : allLoad) != null;
        header.setScope(scope);
        header.setLoading(loading);
        model.setSessions(sessions == null ? List.of() : sessions,
            scope == Scope.ALL, searchEditor.getText());
        if (sessions == null && !loading) {
            loadScope(scope);
        }
    }

    // ── 删除流（pi :402-413＋:842-866）───────────────────────

    private void startDeleteConfirmationForSelected() {
        switch (model.startDeleteConfirmationForSelectedSession()) {
            case STARTED -> header.setConfirmingDeletePath(model.confirmingDeletePath());
            case CURRENT_SESSION ->
                // pi onError（:407-409）——picker 语境不可达（裁决⑦，结构留白）。
                header.setStatusMessage(new StatusMessage(true,
                    "Cannot delete the currently active session"), STATUS_ERROR_MS);
            case NO_SELECTION -> { }
        }
    }

    /** pi {@code onDeleteSession}（:842-866）：unlink 臂（trash 臂无——窄差 D69）。 */
    private void deleteSession(String sessionPath) {
        try {
            Files.delete(Path.of(sessionPath));
        } catch (IOException | SecurityException e) {
            header.setStatusMessage(new StatusMessage(true,
                "Failed to delete: " + e.getMessage()), STATUS_ERROR_MS);
            return;
        }
        if (currentSessions != null) {
            currentSessions = removeByPath(currentSessions, sessionPath);
        }
        if (allSessions != null) {
            allSessions = removeByPath(allSessions, sessionPath);
        }
        model.setSessions(scope == Scope.ALL
                ? (allSessions == null ? List.<RichSessionInfo>of() : allSessions)
                : (currentSessions == null ? List.<RichSessionInfo>of() : currentSessions),
            scope == Scope.ALL, searchEditor.getText());
        header.setStatusMessage(new StatusMessage(false, "Session deleted"), STATUS_DELETED_MS);
        refreshSessionsAfterMutation();
    }

    private static List<RichSessionInfo> removeByPath(List<RichSessionInfo> sessions, String path) {
        return sessions.stream().filter(s -> !s.path().equals(path)).toList();
    }

    /** pi {@code refreshSessionsAfterMutation}（:1024-1029）逐字。 */
    private void refreshSessionsAfterMutation() {
        cancelLoads();
        currentSessions = null;
        allSessions = null;
        loadScope(scope);
    }

    // ── 双 scope 加载状态机（pi :941-1007 逐字）───────────────

    private void loadScope(Scope target) {
        if (target == Scope.CURRENT ? currentLoad != null : allLoad != null) {
            return;   // pi :942：已在载 ⇒ return
        }
        var handle = new LoadHandle(AbortSignal.create());
        if (target == Scope.CURRENT) {
            currentLoad = handle;
        } else {
            allLoad = handle;
        }
        header.setScope(target);
        header.setLoading(true);   // pi :951-952（setLoading 清进度）

        var loader = target == Scope.CURRENT ? currentLoader : allLoader;
        loadExecutor.execute(() -> {
            try {
                var sessions = loader.load(
                    (loaded, total, partial) -> uiDispatcher.accept(
                        () -> onProgress(target, handle, loaded, total, partial)),
                    handle.signal());
                uiDispatcher.accept(() -> onLoadComplete(target, handle, sessions));
            } catch (RuntimeException e) {
                uiDispatcher.accept(() -> onLoadFailed(target, handle, e));
            }
        });
    }

    private boolean isActive(Scope target, LoadHandle handle) {
        // pi :955：句柄 identity 比较。
        return (target == Scope.CURRENT ? currentLoad : allLoad) == handle;
    }

    private void onProgress(Scope target, LoadHandle handle, int loaded, int total,
                            List<RichSessionInfo> partial) {
        if (!isActive(target, handle)) {
            return;
        }
        if (partial != null) {
            var sessions = List.copyOf(partial);
            if (target == Scope.CURRENT) {
                currentSessions = sessions;
            } else {
                allSessions = sessions;
            }
            if (target == scope) {
                model.setSessions(sessions, target == Scope.ALL, searchEditor.getText());
            }
        }
        if (target != scope) {
            return;   // pi :967：非当前 scope 不再碰 header/render
        }
        header.setProgress(loaded, total);
    }

    private void onLoadComplete(Scope target, LoadHandle handle, List<RichSessionInfo> sessions) {
        if (!isActive(target, handle)) {
            return;
        }
        if (target == Scope.CURRENT) {
            currentSessions = sessions;
            currentLoad = null;
        } else {
            allSessions = sessions;
            allLoad = null;
        }
        if (target != scope) {
            return;
        }
        header.setLoading(false);
        model.setSessions(sessions, target == Scope.ALL, searchEditor.getText());
    }

    private void onLoadFailed(Scope target, LoadHandle handle, RuntimeException error) {
        if (!isActive(target, handle)) {
            return;
        }
        if (target == Scope.CURRENT) {
            currentLoad = null;
            currentSessions = null;
        } else {
            allLoad = null;
            allSessions = null;
        }
        if (target != scope) {
            return;
        }
        var message = error.getMessage() == null ? String.valueOf(error) : error.getMessage();
        header.setLoading(false);
        header.setStatusMessage(new StatusMessage(true,
            "Failed to load sessions: " + message), STATUS_LOAD_FAILED_MS);
        model.setSessions(List.of(), target == Scope.ALL, searchEditor.getText());
    }

    /** pi {@code cancelLoads}（:872-883）逐字：两臂 abort＋清句柄清缓存。 */
    private void cancelLoads() {
        if (currentLoad != null) {
            currentLoad.signal().abort();
            currentLoad = null;
            currentSessions = null;
        }
        if (allLoad != null) {
            allLoad.signal().abort();
            allLoad = null;
            allSessions = null;
        }
    }

    // ── 渲染（pi buildBaseLayout :743-755 容器形）──────────────

    /** 全部可视行的 span 模型（测试断言面）。 */
    public List<List<Span>> layout(int width) {
        var rows = new ArrayList<List<Span>>();
        rows.add(List.of());
        rows.add(borderRow(width));
        rows.add(List.of());
        rows.addAll(header.layout(width));
        rows.add(List.of());
        // pi :426-427：搜索输入行＋其下空行。输入行沿 EditorComponent 的 "> "
        // 提示符约定（批 9 label 同手法；pi 的裸 Input 无提示符——视觉窄差）。
        rows.add(List.of(
            new Span("> ", SemanticColors.ACCENT),
            new Span(searchEditor.getText(), SemanticColors.PLAIN)));
        rows.add(List.of());
        rows.addAll(renderer.layout(width));
        rows.add(List.of());
        rows.add(borderRow(width));
        return rows;
    }

    /** span 模型 → TamboUI Element（render 只翻译，不另建行形）。 */
    public Element render(int width) {
        var children = new ArrayList<Element>();
        for (var row : layout(width)) {
            if (row.isEmpty()) {
                children.add(TamboUIAdapter.spacer(1));
            } else {
                children.add(TamboUIAdapter.richText(Text.from(Line.from(row.stream()
                    .map(s -> new dev.tamboui.text.Span(s.text(), s.style()))
                    .toList()))));
            }
        }
        return TamboUIAdapter.column(children);
    }

    /** pi DynamicBorder 对位（批 10 裁决⑤同形）：{@code "─" × max(1, width)}，accent。 */
    private static List<Span> borderRow(int width) {
        return List.of(new Span("─".repeat(Math.max(1, width)), SemanticColors.ACCENT));
    }
}
