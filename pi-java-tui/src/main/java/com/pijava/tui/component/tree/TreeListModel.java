package com.pijava.tui.component.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.SessionTreeNode;

/**
 * pi {@code TreeList}（{@code tree-selector.ts:106-1162}）的状态机对位——
 * 零 TamboUI 依赖的纯 java 模型（docs/19 §T2）。过滤/搜索/折叠/环绕导航/
 * 段跳逐字移植；平铺与可见结构重算在 {@link TreeStructure}（500 行纪律
 * 拆分）；键分派在 {@code TreeSelectorOverlay}。
 *
 * <p>java 差异定点：label/session_info 不是树内条目（裁决①，D43——
 * {@code isSettingsEntry} 只有四型）；bashExecution 是独立 entry 变体；
 * {@code node} 是不可变 record，{@code updateNodeLabel} 整体替换。</p>
 */
public final class TreeListModel {

    /** pi {@code ToolCallInfo}（:101-104）：toolResult 行反查用。 */
    public record ToolCallInfo(String name, Map<String, Object> arguments) {}

    private List<FlatNode> flatNodes = new ArrayList<>();
    private List<FlatNode> filteredNodes = new ArrayList<>();
    private int selectedIndex;
    private final String currentLeafId;
    private final int maxVisibleLines;
    private FilterMode filterMode = FilterMode.DEFAULT;
    private String searchQuery = "";
    private final Map<String, ToolCallInfo> toolCallMap = new HashMap<>();
    private boolean multipleRoots;
    private boolean showLabelTimestamps;
    private final Set<String> activePathIds = new HashSet<>();
    private Map<String, String> visibleParentMap = new HashMap<>();
    private Map<String, List<String>> visibleChildrenMap = new HashMap<>();
    private String lastSelectedId;
    private final Set<String> foldedNodes = new HashSet<>();

    /**
     * @param tree              entry 树（{@code AgentSession.getTree()}）
     * @param currentLeafId     当前叶（活动路径锚点；可空）
     * @param maxVisibleLines   视口行数（overlay 由终端高算：max(5, rows/2)）
     * @param initialSelectedId 恢复选中（pi {@code initialSelectedId}；可空）
     * @param initialFilterMode 初值过滤（settings 读入；可空 ⇒ default）
     */
    public TreeListModel(List<SessionTreeNode> tree, String currentLeafId,
                         int maxVisibleLines, String initialSelectedId,
                         FilterMode initialFilterMode) {
        this.currentLeafId = currentLeafId;
        this.maxVisibleLines = maxVisibleLines;
        this.filterMode = initialFilterMode == null ? FilterMode.DEFAULT : initialFilterMode;
        this.multipleRoots = tree.size() > 1;
        this.flatNodes = TreeStructure.flattenTree(tree, currentLeafId, toolCallMap);
        buildActivePath();
        applyFilter();
        // pi :143-146：initialSelectedId ?? currentLeafId，最近可见祖先回退。
        String targetId = initialSelectedId != null ? initialSelectedId : currentLeafId;
        this.selectedIndex = findNearestVisibleIndex(targetId);
        var selected = selectedFlat();
        this.lastSelectedId = selected == null ? null : selected.node().entry().id();
    }

    // ── pi findNearestVisibleIndex（:153-177）─────────────────

    private int findNearestVisibleIndex(String entryId) {
        if (filteredNodes.isEmpty()) {
            return 0;
        }
        var entryMap = new HashMap<String, FlatNode>();
        for (var flatNode : flatNodes) {
            entryMap.put(flatNode.node().entry().id(), flatNode);
        }
        var visibleIdToIndex = new HashMap<String, Integer>();
        for (int i = 0; i < filteredNodes.size(); i++) {
            visibleIdToIndex.put(filteredNodes.get(i).node().entry().id(), i);
        }
        String currentId = entryId;
        while (currentId != null) {
            var index = visibleIdToIndex.get(currentId);
            if (index != null) {
                return index;
            }
            var node = entryMap.get(currentId);
            if (node == null) {
                break;
            }
            currentId = node.node().entry().parentId();
        }
        return filteredNodes.size() - 1;
    }

    // ── pi buildActivePath（:180-198）────────────────────────

    private void buildActivePath() {
        activePathIds.clear();
        if (currentLeafId == null) {
            return;
        }
        var entryMap = new HashMap<String, FlatNode>();
        for (var flatNode : flatNodes) {
            entryMap.put(flatNode.node().entry().id(), flatNode);
        }
        String currentId = currentLeafId;
        while (currentId != null) {
            activePathIds.add(currentId);
            var node = entryMap.get(currentId);
            if (node == null) {
                break;
            }
            currentId = node.node().entry().parentId();
        }
    }

    // ── pi applyFilter（:330-428）────────────────────────────

    private void applyFilter() {
        // pi :331-335：非空时才更新 lastSelectedId（空过滤往返保选中）。
        if (!filteredNodes.isEmpty()) {
            var selected = selectedFlat();
            if (selected != null) {
                lastSelectedId = selected.node().entry().id();
            }
        }

        var tokens = java.util.Arrays.stream(
                searchQuery.toLowerCase(java.util.Locale.ROOT).split("\\s+"))
            .filter(t -> !t.isEmpty()).toList();

        var filtered = new ArrayList<FlatNode>();
        for (var flatNode : flatNodes) {
            if (passesFilter(flatNode, tokens)) {
                filtered.add(flatNode);
            }
        }
        filteredNodes = filtered;

        // pi :401-411：折叠传染 skipSet（沿 parentId）滤除后代。
        if (!foldedNodes.isEmpty()) {
            var skipSet = new HashSet<String>();
            for (var flatNode : flatNodes) {
                var entry = flatNode.node().entry();
                if (entry.parentId() != null && (foldedNodes.contains(entry.parentId())
                        || skipSet.contains(entry.parentId()))) {
                    skipSet.add(entry.id());
                }
            }
            filteredNodes = filteredNodes.stream()
                .filter(n -> !skipSet.contains(n.node().entry().id()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        recalculateVisualStructure();

        // pi :416-422：选中保持——lastSelectedId 优先，否则钳位。
        if (lastSelectedId != null) {
            selectedIndex = findNearestVisibleIndex(lastSelectedId);
        } else if (selectedIndex >= filteredNodes.size()) {
            selectedIndex = Math.max(0, filteredNodes.size() - 1);
        }

        // pi :424-427：lastSelectedId 落回实际选中（父遍历可能改了它）。
        if (!filteredNodes.isEmpty()) {
            var selected = selectedFlat();
            if (selected != null) {
                lastSelectedId = selected.node().entry().id();
            }
        }
    }

    /** pi applyFilter 谓词（:339-399）逐字。 */
    private boolean passesFilter(FlatNode flatNode, List<String> tokens) {
        var entry = flatNode.node().entry();
        // usage 恒隐。
        if (entry instanceof Entry.Usage) {
            return false;
        }
        boolean isCurrentLeaf = entry.id().equals(currentLeafId);

        // 纯工具调用 assistant（无文本、非 error/aborted）默认隐；当前叶恒显。
        if (entry instanceof Entry.Message m
                && m.message() instanceof Message.AssistantMessage am
                && !isCurrentLeaf) {
            boolean hasText = TreeTextSupport.hasTextContent(am.content());
            boolean isErrorOrAborted = am.stopReason() != null
                && am.stopReason() != StopReason.STOP
                && am.stopReason() != StopReason.TOOL_USE;
            if (!hasText && !isErrorOrAborted) {
                return false;
            }
        }

        // 裁决①：java 无 label/session_info 条目 ⇒ 四型（D43）。
        boolean isSettingsEntry = entry instanceof Entry.ContextEdit
            || entry instanceof Entry.Custom
            || entry instanceof Entry.ModelChange
            || entry instanceof Entry.ThinkingLevelChange;

        boolean passes = switch (filterMode) {
            case USER_ONLY -> entry instanceof Entry.Message m
                && m.message() instanceof Message.UserMessage;
            case NO_TOOLS -> !isSettingsEntry && !(entry instanceof Entry.Message m
                && m.message() instanceof Message.ToolResultMessage);
            case LABELED_ONLY -> flatNode.node().label() != null;
            case ALL -> true;
            case DEFAULT -> !isSettingsEntry;
        };
        if (!passes) {
            return false;
        }
        if (!tokens.isEmpty()) {
            String nodeText = TreeTextSupport.searchableText(flatNode.node())
                .toLowerCase(java.util.Locale.ROOT);
            return tokens.stream().allMatch(nodeText::contains);
        }
        return true;
    }

    /** pi :413-414/:556-558：可见结构重算（算法在 {@link TreeStructure}）。 */
    private void recalculateVisualStructure() {
        if (filteredNodes.isEmpty()) {
            return;
        }
        var maps = TreeStructure.recalculate(flatNodes, filteredNodes);
        visibleParentMap = maps.parentMap();
        visibleChildrenMap = maps.childrenMap();
        multipleRoots = maps.multipleRoots();
    }

    // ── 键语义方法（pi handleInput :1004-1110 各臂）───────────

    /** pi :1006-1007：上移，首行环绕到末行。 */
    public void moveUp() {
        if (filteredNodes.isEmpty()) {
            return;
        }
        selectedIndex = selectedIndex == 0 ? filteredNodes.size() - 1 : selectedIndex - 1;
    }

    /** pi :1008-1009：下移，末行环绕到首行。 */
    public void moveDown() {
        if (filteredNodes.isEmpty()) {
            return;
        }
        selectedIndex = selectedIndex == filteredNodes.size() - 1 ? 0 : selectedIndex + 1;
    }

    /** pi :1026-1028：左/pageUp ⇒ 上翻 maxVisibleLines。 */
    public void pageUp() {
        selectedIndex = Math.max(0, selectedIndex - maxVisibleLines);
    }

    /** pi :1029-1031：右/pageDown ⇒ 下翻 maxVisibleLines。 */
    public void pageDown() {
        if (filteredNodes.isEmpty()) {
            return;
        }
        selectedIndex = Math.min(filteredNodes.size() - 1, selectedIndex + maxVisibleLines);
    }

    /** pi :1010-1017：可折且未折 ⇒ 折叠＋applyFilter，否则段首上跳。 */
    public void foldOrUp() {
        String currentId = selectedEntryId();
        if (currentId != null && isFoldable(currentId) && !foldedNodes.contains(currentId)) {
            foldedNodes.add(currentId);
            applyFilter();
        } else {
            selectedIndex = findBranchSegmentStart("up");
        }
    }

    /** pi :1018-1025：已折 ⇒ 展开，否则段首下跳。 */
    public void unfoldOrDown() {
        String currentId = selectedEntryId();
        if (currentId != null && foldedNodes.contains(currentId)) {
            foldedNodes.remove(currentId);
            applyFilter();
        } else {
            selectedIndex = findBranchSegmentStart("down");
        }
    }

    /**
     * 直设过滤模式＋清折叠＋applyFilter（pi 五键臂 :1047-1071）。互切语义
     * （非 default 键再按回 default）在 overlay 分派层。
     */
    public void setFilter(FilterMode mode) {
        this.filterMode = mode;
        foldedNodes.clear();
        applyFilter();
    }

    /** pi :1072-1085：五模式环（forward：default→no-tools→user-only→labeled→all）。 */
    public void cycleFilter(boolean forward) {
        var modes = FilterMode.cycleOrder();
        int current = java.util.Arrays.asList(modes).indexOf(filterMode);
        int next = forward
            ? (current + 1) % modes.length
            : (current - 1 + modes.length) % modes.length;
        filterMode = modes[next];
        foldedNodes.clear();
        applyFilter();
    }

    /** pi :1086-1091：删搜索尾字符＋清折叠。 */
    public void backspaceSearch() {
        if (!searchQuery.isEmpty()) {
            searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
            foldedNodes.clear();
            applyFilter();
        }
    }

    /** pi :1099-1108：追加搜索串＋清折叠。 */
    public void appendSearch(String text) {
        searchQuery += text;
        foldedNodes.clear();
        applyFilter();
    }

    /**
     * pi cancel 第一级（:1039-1046）：有搜索串 ⇒ 清搜索＋清折叠，返回 true；
     * 否则返回 false（调用方走 onCancel）。
     */
    public boolean clearSearchIfAny() {
        if (!searchQuery.isEmpty()) {
            searchQuery = "";
            foldedNodes.clear();
            applyFilter();
            return true;
        }
        return false;
    }

    /** pi :1097-1098。 */
    public void toggleLabelTimestamps() {
        showLabelTimestamps = !showLabelTimestamps;
    }

    /** pi updateNodeLabel（:637-645）：本地即时反映（record 整体替换）。 */
    public void updateNodeLabel(String entryId, String label, Instant timestamp) {
        for (var flatNode : flatNodes) {
            if (flatNode.node().entry().id().equals(entryId)) {
                var node = flatNode.node();
                flatNode.node(new SessionTreeNode(node.entry(), node.children(), label,
                    label != null ? (timestamp != null ? timestamp : Instant.now()) : null));
                break;
            }
        }
    }

    // ── pi isFoldable（:1117-1124）／findBranchSegmentStart（:1133-1161）──

    /** 有可见子节点，且（根）或（可见父多子＝段首）。 */
    public boolean isFoldable(String entryId) {
        var children = visibleChildrenMap.get(entryId);
        if (children == null || children.isEmpty()) {
            return false;
        }
        String parentId = visibleParentMap.get(entryId);
        if (parentId == null) {
            return true;
        }
        var siblings = visibleChildrenMap.get(parentId);
        return siblings != null && siblings.size() > 1;
    }

    private int findBranchSegmentStart(String direction) {
        var selected = selectedFlat();
        if (selected == null) {
            return selectedIndex;
        }
        String selectedId = selected.node().entry().id();
        var indexByEntryId = new HashMap<String, Integer>();
        for (int i = 0; i < filteredNodes.size(); i++) {
            indexByEntryId.put(filteredNodes.get(i).node().entry().id(), i);
        }
        String currentId = selectedId;
        if ("down".equals(direction)) {
            while (true) {
                var children = visibleChildrenMap.getOrDefault(currentId, List.of());
                if (children.isEmpty()) {
                    return indexByEntryId.get(currentId);
                }
                if (children.size() > 1) {
                    return indexByEntryId.get(children.get(0));
                }
                currentId = children.get(0);
            }
        }
        // up：沿可见父链上爬，停在「父多子且段首小于当前」处。
        while (true) {
            String parentId = visibleParentMap.get(currentId);
            if (parentId == null) {
                return indexByEntryId.get(currentId);
            }
            var children = visibleChildrenMap.getOrDefault(parentId, List.of());
            if (children.size() > 1) {
                int segmentStart = indexByEntryId.get(currentId);
                if (segmentStart < selectedIndex) {
                    return segmentStart;
                }
            }
            currentId = parentId;
        }
    }

    // ── pi getStatusLabels（:647-667）────────────────────────

    /** 状态行过滤标（"[no-tools][user][labeled][all][+label time]"）。 */
    public String statusLabels() {
        String labels = switch (filterMode) {
            case NO_TOOLS -> " [no-tools]";
            case USER_ONLY -> " [user]";
            case LABELED_ONLY -> " [labeled]";
            case ALL -> " [all]";
            case DEFAULT -> "";
        };
        if (showLabelTimestamps) {
            labels += " [+label time]";
        }
        return labels;
    }

    // ── 只读探针（渲染/测试）─────────────────────────────────

    private FlatNode selectedFlat() {
        if (selectedIndex < 0 || selectedIndex >= filteredNodes.size()) {
            return null;
        }
        return filteredNodes.get(selectedIndex);
    }

    /** pi getSelectedNode（:628-630）：空表 ⇒ {@code null}。 */
    public SessionTreeNode selectedNode() {
        var selected = selectedFlat();
        return selected == null ? null : selected.node();
    }

    /** 选中行 entry id（空表 ⇒ {@code null}）。 */
    public String selectedEntryId() {
        var selected = selectedFlat();
        return selected == null ? null : selected.node().entry().id();
    }

    /** pi copySelected（:632-635）的纯函数形：选中行复制文本（无 ⇒ {@code null}）。 */
    public String selectedCopyText() {
        var node = selectedNode();
        return node == null ? null : TreeTextSupport.copyText(node);
    }

    /** 过滤后的可见行（视觉结构已重算）。 */
    public List<FlatNode> filteredNodes() {
        return java.util.Collections.unmodifiableList(filteredNodes);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public FilterMode filterMode() {
        return filterMode;
    }

    public boolean showLabelTimestamps() {
        return showLabelTimestamps;
    }

    public boolean isFolded(String entryId) {
        return foldedNodes.contains(entryId);
    }

    public boolean isOnActivePath(String entryId) {
        return activePathIds.contains(entryId);
    }

    public boolean multipleRoots() {
        return multipleRoots;
    }

    public int maxVisibleLines() {
        return maxVisibleLines;
    }

    public Map<String, ToolCallInfo> toolCallMap() {
        return java.util.Collections.unmodifiableMap(toolCallMap);
    }
}
