package com.pijava.tui.component.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.SessionTreeNode;
import com.pijava.tui.component.tree.TreeListModel.ToolCallInfo;

/**
 * pi {@code flattenTree}（tree-selector.ts:200-328）与
 * {@code recalculateVisualStructure}（:436-559）的纯函数对位——树→平铺行
 * 与「过滤后按最近可见祖先重挂」两套视觉结构算法（自 {@code TreeListModel}
 * 拆出，500 行纪律）。缩进/连接符/gutter 规则两处严格同套。
 */
final class TreeStructure {

    private TreeStructure() {}

    /** pi flattenTree 的栈项（:210）。 */
    private record StackItem(SessionTreeNode node, int indent, boolean justBranched,
                             boolean showConnector, boolean isLast,
                             List<GutterInfo> gutters, boolean isVirtualRootChild) {}

    /** pi recalculateVisualStructure 的 id 栈项（:489）。 */
    private record IdStackItem(String nodeId, int indent, boolean justBranched,
                               boolean showConnector, boolean isLast,
                               List<GutterInfo> gutters, boolean isVirtualRootChild) {}

    /** 可见结构重算的产出（pi :556-558 的三处字段写）。 */
    record VisualMaps(Map<String, String> parentMap,
                      Map<String, List<String>> childrenMap,
                      boolean multipleRoots) {}

    /** pi containsActive 后序计算（:214-239）：每棵子树是否含当前叶。 */
    private static IdentityHashMap<SessionTreeNode, Boolean> computeContainsActive(
            List<SessionTreeNode> roots, String currentLeafId) {
        var containsActive = new IdentityHashMap<SessionTreeNode, Boolean>();
        var allNodes = new ArrayList<SessionTreeNode>();
        var preOrder = new ArrayDeque<>(roots);
        while (!preOrder.isEmpty()) {
            var node = preOrder.pop();
            allNodes.add(node);
            for (int i = node.children().size() - 1; i >= 0; i--) {
                preOrder.push(node.children().get(i));
            }
        }
        for (int i = allNodes.size() - 1; i >= 0; i--) {
            var node = allNodes.get(i);
            boolean has = currentLeafId != null && node.entry().id().equals(currentLeafId);
            for (var child : node.children()) {
                if (Boolean.TRUE.equals(containsActive.get(child))) {
                    has = true;
                }
            }
            containsActive.put(node, has);
        }
        return containsActive;
    }

    /** pi :287-298 缩进规则：分支点 +1；分支后第一代 +1；单链持平。 */
    private static int childIndent(int indent, boolean justBranched, boolean multipleChildren) {
        if (multipleChildren) {
            return indent + 1;
        }
        if (justBranched && indent > 0) {
            return indent + 1;
        }
        return indent;
    }

    /** pi :300-310 gutter 传递：connector 实际显示才追加。 */
    private static List<GutterInfo> childGutters(List<GutterInfo> gutters,
                                                 boolean showConnector,
                                                 boolean isVirtualRootChild,
                                                 boolean isLast, int indent,
                                                 boolean multipleRoots) {
        boolean connectorDisplayed = showConnector && !isVirtualRootChild;
        if (!connectorDisplayed) {
            return gutters;
        }
        int currentDisplayIndent = multipleRoots ? Math.max(0, indent - 1) : indent;
        int connectorPosition = Math.max(0, currentDisplayIndent - 1);
        var childGutters = new ArrayList<>(gutters);
        childGutters.add(new GutterInfo(connectorPosition, !isLast));
        return childGutters;
    }

    /**
     * pi {@code flattenTree}（:200-328）逐字：迭代栈防爆栈；含活动叶的根/子
     * 分支排前（稳定）；多根 ⇒ 虚拟根一层；toolCallMap 顺手收集。
     */
    static List<FlatNode> flattenTree(List<SessionTreeNode> roots, String currentLeafId,
                                      Map<String, ToolCallInfo> toolCallMap) {
        var result = new ArrayList<FlatNode>();
        toolCallMap.clear();
        var containsActive = computeContainsActive(roots, currentLeafId);

        boolean multi = roots.size() > 1;
        var orderedRoots = new ArrayList<>(roots);
        orderedRoots.sort(Comparator.comparing(
            (SessionTreeNode n) -> containsActive.get(n)).reversed());
        var stack = new ArrayDeque<StackItem>();
        for (int i = orderedRoots.size() - 1; i >= 0; i--) {
            boolean isLast = i == orderedRoots.size() - 1;
            stack.push(new StackItem(orderedRoots.get(i), multi ? 1 : 0, multi, multi,
                isLast, List.of(), multi));
        }

        while (!stack.isEmpty()) {
            var item = stack.pop();
            var node = item.node();

            // pi :254-266：assistant 消息的 toolCall 块顺手收集。
            if (node.entry() instanceof Entry.Message m
                    && m.message() instanceof Message.AssistantMessage am) {
                for (var block : am.content()) {
                    if (block instanceof com.pijava.ai.message.ContentBlock.ToolUseContent tc) {
                        toolCallMap.put(tc.id(), new ToolCallInfo(tc.name(), tc.arguments()));
                    }
                }
            }

            result.add(new FlatNode(node, item.indent(), item.showConnector(),
                item.isLast(), item.gutters(), item.isVirtualRootChild()));

            var children = node.children();
            boolean multipleChildren = children.size() > 1;

            // pi :274-285：含活动叶的子分支排前，其余保持原序。
            var orderedChildren = new ArrayList<SessionTreeNode>();
            var rest = new ArrayList<SessionTreeNode>();
            for (var child : children) {
                if (Boolean.TRUE.equals(containsActive.get(child))) {
                    orderedChildren.add(child);
                } else {
                    rest.add(child);
                }
            }
            orderedChildren.addAll(rest);

            int childIndent = childIndent(item.indent(), item.justBranched(), multipleChildren);
            var childGutters = childGutters(item.gutters(), item.showConnector(),
                item.isVirtualRootChild(), item.isLast(), item.indent(), multi);

            for (int i = orderedChildren.size() - 1; i >= 0; i--) {
                boolean childIsLast = i == orderedChildren.size() - 1;
                stack.push(new StackItem(orderedChildren.get(i), childIndent,
                    multipleChildren, multipleChildren, childIsLast, childGutters, false));
            }
        }
        return result;
    }

    /**
     * pi {@code recalculateVisualStructure}（:436-559）逐字：过滤藏掉中间条目
     * ⇒ 后代挂最近可见祖先下，按同套缩进规则重写视觉字段；同时产出
     * visibleParent/visibleChildren 映射与 multipleRoots 重判。
     */
    static VisualMaps recalculate(List<FlatNode> flatNodes, List<FlatNode> filteredNodes) {
        var visibleIds = new HashSet<String>();
        for (var n : filteredNodes) {
            visibleIds.add(n.node().entry().id());
        }
        var entryMap = new HashMap<String, FlatNode>();
        for (var flatNode : flatNodes) {
            entryMap.put(flatNode.node().entry().id(), flatNode);
        }

        var visibleParent = new HashMap<String, String>();
        var visibleChildren = new HashMap<String, List<String>>();
        visibleChildren.put(null, new ArrayList<>());
        for (var flatNode : filteredNodes) {
            String nodeId = flatNode.node().entry().id();
            String ancestorId = findVisibleAncestor(nodeId, entryMap, visibleIds);
            visibleParent.put(nodeId, ancestorId);
            visibleChildren.computeIfAbsent(ancestorId, k -> new ArrayList<>()).add(nodeId);
        }

        var visibleRootIds = visibleChildren.get(null);
        boolean multipleRoots = visibleRootIds.size() > 1;

        var filteredNodeMap = new HashMap<String, FlatNode>();
        for (var flatNode : filteredNodes) {
            filteredNodeMap.put(flatNode.node().entry().id(), flatNode);
        }

        var idStack = new ArrayDeque<IdStackItem>();
        for (int i = visibleRootIds.size() - 1; i >= 0; i--) {
            boolean isLast = i == visibleRootIds.size() - 1;
            idStack.push(new IdStackItem(visibleRootIds.get(i), multipleRoots ? 1 : 0,
                multipleRoots, multipleRoots, isLast, List.of(), multipleRoots));
        }
        while (!idStack.isEmpty()) {
            var item = idStack.pop();
            var flatNode = filteredNodeMap.get(item.nodeId());
            if (flatNode == null) {
                continue;
            }
            flatNode.updateVisual(item.indent(), item.showConnector(), item.isLast(),
                item.gutters(), item.isVirtualRootChild());

            var children = visibleChildren.getOrDefault(item.nodeId(), List.of());
            boolean multipleChildren = children.size() > 1;
            int childIndent = childIndent(item.indent(), item.justBranched(), multipleChildren);
            var childGutters = childGutters(item.gutters(), item.showConnector(),
                item.isVirtualRootChild(), item.isLast(), item.indent(), multipleRoots);
            for (int i = children.size() - 1; i >= 0; i--) {
                boolean childIsLast = i == children.size() - 1;
                idStack.push(new IdStackItem(children.get(i), childIndent, multipleChildren,
                    multipleChildren, childIsLast, childGutters, false));
            }
        }
        return new VisualMaps(visibleParent, visibleChildren, multipleRoots);
    }

    /** pi findVisibleAncestor（:448-457）：沿 parentId 找最近可见祖先。 */
    private static String findVisibleAncestor(String nodeId, Map<String, FlatNode> entryMap,
                                              Set<String> visibleIds) {
        var node = entryMap.get(nodeId);
        String currentId = node == null ? null : node.node().entry().parentId();
        while (currentId != null) {
            if (visibleIds.contains(currentId)) {
                return currentId;
            }
            var parent = entryMap.get(currentId);
            currentId = parent == null ? null : parent.node().entry().parentId();
        }
        return null;
    }
}
