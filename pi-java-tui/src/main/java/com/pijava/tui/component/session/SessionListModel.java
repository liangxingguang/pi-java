package com.pijava.tui.component.session;

import java.util.List;
import java.util.Objects;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;
import com.pijava.tui.component.session.SessionTreeBuilder.FlatSessionNode;

/**
 * pi {@code SessionList} 的列表状态机（session-selector.ts:283-647 拆出，
 * 批 14，docs/25 T10）：{@code allSessions/filtered/selectedIndex/selectionTouched/
 * showCwd/sortMode/nameFilter/showPath/confirmingDeletePath}＋
 * {@code filterSessions}（:375-395 逐字）＋{@code setSessions}（:362-373 逐字）＋
 * 导航 clamp/pageUp/pageDown＋删除确认态转移。
 *
 * <p>渲染在 {@link SessionListRenderer}；键分派在 {@link SessionSelectorComponent}。</p>
 */
public final class SessionListModel {

    /** pi {@code maxVisible}（:311）。 */
    public static final int MAX_VISIBLE = 10;

    /** {@link #startDeleteConfirmationForSelectedSession()} 的三结局。 */
    public enum DeleteStart { STARTED, NO_SELECTION, CURRENT_SESSION }

    private List<RichSessionInfo> allSessions = List.of();
    private List<FlatSessionNode> filtered = List.of();
    private int selectedIndex;
    private boolean selectionTouched;
    private boolean showCwd;
    private SortMode sortMode = SortMode.THREADED;
    private NameFilter nameFilter = NameFilter.ALL;
    private boolean showPath;
    private String confirmingDeletePath;
    /** picker 恒 null（裁决⑦）——「当前会话」高亮与删除守卫同形留白。 */
    private final String currentSessionCanonicalPath;

    /**
     * @param currentSessionFilePath pi {@code currentSessionFilePath} 对位；
     *        picker 语境 pi 不传（session-picker.ts:91-99）⇒ 此处恒 {@code null}
     */
    public SessionListModel(String currentSessionFilePath) {
        this.currentSessionCanonicalPath =
            SessionTreeBuilder.canonicalizePath(currentSessionFilePath);
    }

    // ── 只读探针 ─────────────────────────────────────────────

    public List<FlatSessionNode> filtered() {
        return filtered;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public boolean selectionTouched() {
        return selectionTouched;
    }

    public boolean showCwd() {
        return showCwd;
    }

    public SortMode sortMode() {
        return sortMode;
    }

    public NameFilter nameFilter() {
        return nameFilter;
    }

    public boolean showPath() {
        return showPath;
    }

    public String confirmingDeletePath() {
        return confirmingDeletePath;
    }

    public void setShowPath(boolean showPath) {
        this.showPath = showPath;
    }

    /** pi :611：导航/定案/取消/输入转发前统一置位。 */
    public void touchSelection() {
        this.selectionTouched = true;
    }

    /** 当前选中节点；空表 ⇒ {@code null}（pi {@code filtered[selectedIndex]} 的 undefined 对位）。 */
    public FlatSessionNode selectedNode() {
        return filtered.isEmpty() ? null : filtered.get(selectedIndex);
    }

    /** pi {@code getSelectedSessionPath}（:284-287）。 */
    public String selectedSessionPath() {
        var selected = selectedNode();
        return selected == null ? null : selected.session().path();
    }

    // ── 过滤/排序（pi :375-395 逐字）─────────────────────────

    /**
     * pi {@code filterSessions}：threaded 无查询 ⇒ 树形；其余（或有查询）⇒ 平表。
     * 尾部 clamp：{@code selectedIndex = min(selectedIndex, max(0, len-1))}。
     */
    public void filterSessions(String query) {
        var trimmed = query.strip();
        var nameFiltered = nameFilter == NameFilter.ALL
            ? allSessions
            : allSessions.stream().filter(SessionSelectorSearch::hasSessionName).toList();
        if (sortMode == SortMode.THREADED && trimmed.isEmpty()) {
            // Threaded mode without search: show tree structure
            filtered = SessionTreeBuilder.flattenSessionTree(
                SessionTreeBuilder.buildSessionTree(nameFiltered));
        } else {
            // Other modes or with search: flat list
            filtered = SessionSelectorSearch.filterAndSortSessions(
                nameFiltered, query, sortMode, NameFilter.ALL).stream()
                .map(s -> new FlatSessionNode(s, 0, true, List.of()))
                .toList();
        }
        selectedIndex = Math.min(selectedIndex, Math.max(0, filtered.size() - 1));
    }

    /**
     * pi {@code setSessions}（:362-373）逐字：未摸过选择 ⇒ 重置 0；摸过 ⇒
     * 按 path 找回选中项（找不到保持当前下标——已被 clamp）。
     */
    public void setSessions(List<RichSessionInfo> sessions, boolean showCwd, String query) {
        var selectedPath = selectionTouched ? selectedSessionPath() : null;
        this.allSessions = List.copyOf(sessions);
        this.showCwd = showCwd;
        filterSessions(query);
        if (!selectionTouched) {
            selectedIndex = 0;
        } else if (selectedPath != null) {
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).session().path().equals(selectedPath)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
    }

    /** pi {@code setSortMode}（:352-355）：换档即按当前查询重滤。 */
    public void setSortMode(SortMode sortMode, String query) {
        this.sortMode = sortMode;
        filterSessions(query);
    }

    /** pi {@code setNameFilter}（:357-360）。 */
    public void setNameFilter(NameFilter nameFilter, String query) {
        this.nameFilter = nameFilter;
        filterSessions(query);
    }

    // ── 导航（pi :613-627，clamp 不环绕）─────────────────────

    /** pi :613-615。 */
    public void moveUp() {
        selectedIndex = Math.max(0, selectedIndex - 1);
    }

    /** pi :617-619（空表钉 0——java 下标无 pi 的 undefined 容错，行为等价）。 */
    public void moveDown() {
        selectedIndex = filtered.isEmpty() ? 0
            : Math.min(filtered.size() - 1, selectedIndex + 1);
    }

    /** pi :621-623。 */
    public void pageUp() {
        selectedIndex = Math.max(0, selectedIndex - MAX_VISIBLE);
    }

    /** pi :625-627。 */
    public void pageDown() {
        selectedIndex = filtered.isEmpty() ? 0
            : Math.min(filtered.size() - 1, selectedIndex + MAX_VISIBLE);
    }

    // ── 删除确认态（pi :397-418）─────────────────────────────

    /** pi {@code startDeleteConfirmationForSelectedSession}（:402-413）。 */
    public DeleteStart startDeleteConfirmationForSelectedSession() {
        var selected = selectedNode();
        if (selected == null) {
            return DeleteStart.NO_SELECTION;
        }
        // pi :406-410：当前会话删除守卫——picker 语境 currentSessionCanonicalPath
        // 恒 null ⇒ 永不命中（裁决⑦，结构与 pi 同形留白）。
        if (isCurrentSessionPath(selected.session().path())) {
            return DeleteStart.CURRENT_SESSION;
        }
        confirmingDeletePath = selected.session().path();
        return DeleteStart.STARTED;
    }

    /** pi {@code setConfirmingDeletePath(null)} 对位（:397-400 的退出臂）。 */
    public void clearConfirmingDelete() {
        confirmingDeletePath = null;
    }

    /** pi {@code isCurrentSessionPath}（:415-418）逐字。 */
    public boolean isCurrentSessionPath(String path) {
        if (currentSessionCanonicalPath == null) {
            return false;
        }
        return Objects.equals(SessionTreeBuilder.canonicalizePath(path),
            currentSessionCanonicalPath);
    }
}
