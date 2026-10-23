package com.pijava.tui.component.session;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.pijava.agent.session.jsonl.RichSessionInfo;

/**
 * pi session-selector.ts 的树构建与行工具逐字 port（批 14，docs/25 T10）：
 * {@code shortenPath}（:26-33）、{@code formatSessionDate}（:35-49）、
 * {@code canonicalizePath}（:51-54）、{@code buildSessionTree}（:209-254）、
 * {@code flattenSessionTree}（:259-278）。
 */
public final class SessionTreeBuilder {

    /** pi {@code SessionTreeNode}（:190-194）；children 已按 latestActivity 降序冻结。 */
    public record SessionTreeNode(RichSessionInfo session, List<SessionTreeNode> children,
                                  long latestActivity) {}

    /** pi {@code FlatSessionNode}（:197-203）。 */
    public record FlatSessionNode(RichSessionInfo session, int depth, boolean isLast,
                                  List<Boolean> ancestorContinues) {}

    private SessionTreeBuilder() {}

    /** 构建期的可变节点（pi 就地 push/sort 的对位；出口处冻结为 record）。 */
    private static final class MutableNode {
        private final RichSessionInfo session;
        private final List<MutableNode> children = new ArrayList<>();
        private long latestActivity;

        private MutableNode(RichSessionInfo session) {
            this.session = session;
            this.latestActivity = session.modifiedMs();
        }
    }

    /**
     * pi {@code buildSessionTree}（:209-254）逐字：按 {@code canonicalizePath(
     * parentSessionPath)} 认父（父不在集合 ⇒ 根）；{@code latestActivity}＝子树最大
     * modified；根与子层均按它降序。
     */
    public static List<SessionTreeNode> buildSessionTree(List<RichSessionInfo> sessions) {
        var byPath = new HashMap<String, MutableNode>();
        for (var session : sessions) {
            byPath.put(canonicalizePath(session.path()), new MutableNode(session));
        }

        var roots = new ArrayList<MutableNode>();
        for (var session : sessions) {
            var node = byPath.get(canonicalizePath(session.path()));
            var parentPath = canonicalizePath(session.parentSessionPath());
            var parent = parentPath == null ? null : byPath.get(parentPath);
            if (parent != null) {
                parent.children.add(node);
            } else {
                roots.add(node);
            }
        }

        for (var root : roots) {
            updateLatestActivity(root);
        }
        sortNodes(roots);
        return roots.stream().map(SessionTreeBuilder::freeze).toList();
    }

    private static long updateLatestActivity(MutableNode node) {
        long latest = node.session.modifiedMs();
        for (var child : node.children) {
            latest = Math.max(latest, updateLatestActivity(child));
        }
        node.latestActivity = latest;
        return latest;
    }

    /** pi {@code sortNodes}（:245-251）：每层按 latestActivity 降序。 */
    private static void sortNodes(List<MutableNode> nodes) {
        nodes.sort((a, b) -> Long.compare(b.latestActivity, a.latestActivity));
        for (var node : nodes) {
            sortNodes(node.children);
        }
    }

    private static SessionTreeNode freeze(MutableNode node) {
        return new SessionTreeNode(node.session,
            node.children.stream().map(SessionTreeBuilder::freeze).toList(),
            node.latestActivity);
    }

    /**
     * pi {@code flattenSessionTree}（:259-278）逐字：深度先序；
     * {@code ancestorContinues} 规则——{@code depth > 0 ? !isLast : false}（:268，
     * 只有非根祖级才画延续线）。
     */
    public static List<FlatSessionNode> flattenSessionTree(List<SessionTreeNode> roots) {
        var result = new ArrayList<FlatSessionNode>();
        for (int i = 0; i < roots.size(); i++) {
            walk(roots.get(i), 0, List.of(), i == roots.size() - 1, result);
        }
        return result;
    }

    private static void walk(SessionTreeNode node, int depth, List<Boolean> ancestorContinues,
                             boolean isLast, List<FlatSessionNode> out) {
        out.add(new FlatSessionNode(node.session(), depth, isLast, ancestorContinues));
        for (int i = 0; i < node.children().size(); i++) {
            boolean childIsLast = i == node.children().size() - 1;
            boolean continues = depth > 0 && !isLast;
            var childAncestors = new ArrayList<>(ancestorContinues);
            childAncestors.add(continues);
            walk(node.children().get(i), depth + 1, List.copyOf(childAncestors), childIsLast, out);
        }
    }

    /**
     * pi {@code formatSessionDate}（:35-49）逐字：&lt;1m {@code now}；&lt;60m {@code m}；
     * &lt;24h {@code h}；&lt;7d {@code d}；&lt;30d {@code w}；&lt;365d {@code mo}；
     * 否则 {@code y}。
     */
    public static String formatSessionDate(long modifiedMs) {
        return formatSessionDate(modifiedMs, System.currentTimeMillis());
    }

    /** 注入 now 的测试缝。 */
    static String formatSessionDate(long modifiedMs, long nowMs) {
        long diffMs = nowMs - modifiedMs;
        long diffMins = diffMs / 60000;
        long diffHours = diffMs / 3600000;
        long diffDays = diffMs / 86400000;
        if (diffMins < 1) {
            return "now";
        }
        if (diffMins < 60) {
            return diffMins + "m";
        }
        if (diffHours < 24) {
            return diffHours + "h";
        }
        if (diffDays < 7) {
            return diffDays + "d";
        }
        if (diffDays < 30) {
            return (diffDays / 7) + "w";
        }
        if (diffDays < 365) {
            return (diffDays / 30) + "mo";
        }
        return (diffDays / 365) + "y";
    }

    /** pi {@code shortenPath}（:26-33）：home 前缀 ⇒ {@code ~} 起头；空串原样返回。 */
    public static String shortenPath(String path) {
        var home = System.getProperty("user.home", "");
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (!home.isEmpty() && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    /** pi {@code canonicalizePath}（:51-54＋utils/paths.ts）：realpath，失败回落原串。 */
    public static String canonicalizePath(String path) {
        if (path == null) {
            return null;
        }
        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException | InvalidPathException | SecurityException e) {
            return path;
        }
    }
}
