package com.pijava.tui.component.tree;

import java.util.List;

import com.pijava.coding.agent.core.SessionTreeNode;

/** pi {@code GutterInfo}（tree-selector.ts:21-24）：祖先分支点传下的竖线位。 */
record GutterInfo(int position, boolean show) {}

/**
 * pi {@code FlatNode}（tree-selector.ts:27-39）对位：平铺后的树节点＋
 * 视觉结构（缩进/连接符/gutter/虚拟根子标记）。视觉字段由
 * {@code recalculateVisualStructure} 重算，故可变；{@code node} 在
 * {@code updateNodeLabel} 时整体替换（record 不可变）。
 */
final class FlatNode {

    private SessionTreeNode node;
    private int indent;
    private boolean showConnector;
    private boolean isLast;
    private List<GutterInfo> gutters;
    private boolean isVirtualRootChild;

    FlatNode(SessionTreeNode node, int indent, boolean showConnector, boolean isLast,
             List<GutterInfo> gutters, boolean isVirtualRootChild) {
        this.node = node;
        this.indent = indent;
        this.showConnector = showConnector;
        this.isLast = isLast;
        this.gutters = gutters;
        this.isVirtualRootChild = isVirtualRootChild;
    }

    SessionTreeNode node() {
        return node;
    }

    void node(SessionTreeNode node) {
        this.node = node;
    }

    int indent() {
        return indent;
    }

    boolean showConnector() {
        return showConnector;
    }

    boolean isLast() {
        return isLast;
    }

    List<GutterInfo> gutters() {
        return gutters;
    }

    boolean isVirtualRootChild() {
        return isVirtualRootChild;
    }

    /** pi recalculateVisualStructure 的重写点（:513-517）。 */
    void updateVisual(int indent, boolean showConnector, boolean isLast,
                      List<GutterInfo> gutters, boolean isVirtualRootChild) {
        this.indent = indent;
        this.showConnector = showConnector;
        this.isLast = isLast;
        this.gutters = gutters;
        this.isVirtualRootChild = isVirtualRootChild;
    }
}
