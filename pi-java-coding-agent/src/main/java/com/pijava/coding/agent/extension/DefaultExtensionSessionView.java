package com.pijava.coding.agent.extension;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.BranchBounds;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.coding.agent.core.SessionTreeNode;

/**
 * docs/50 §3.1 B285：{@link ExtensionSessionView} 默认实现——晚绑定宿主会话；
 * 无持久面时（{@code --no-session}）沿 {@code accumulatedEntries()} 兜底
 * （与既有 {@code getEntries} 同一语义）。
 */
final class DefaultExtensionSessionView implements ExtensionSessionView {

    private final DefaultExtensionContext context;

    DefaultExtensionSessionView(DefaultExtensionContext context) {
        this.context = context;
    }

    @Override
    public List<Entry> getEntries() {
        return List.copyOf(context.requireToolControl().accumulatedEntries());
    }

    @Override
    public List<Entry> getBranch() {
        var session = context.requireToolControl();
        var real = session.session();
        if (real != null) {
            return real.findEntriesOnBranch(
                new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null),
                BranchBounds.none());
        }
        return ContextEntries.pathToLeaf(session.accumulatedEntries(), session.currentLeafId());
    }

    @Override
    public Entry getEntry(String id) {
        var session = context.requireToolControl();
        var real = session.session();
        if (real != null) {
            return real.getEntry(id);
        }
        return session.accumulatedEntries().stream()
            .filter(entry -> entry.id().equals(id))
            .findFirst().orElse(null);
    }

    @Override
    public String getLabel(String targetId) {
        return context.requireToolControl().getLabel(targetId);
    }

    @Override
    public Entry getLeafEntry() {
        var leafId = getLeafId();
        return leafId == null ? null : getEntry(leafId);
    }

    @Override
    public String getSessionFile() {
        return context.requireToolControl().sessionFile();
    }

    @Override
    public String getCwd() {
        return context.requireToolControl().cwd();
    }

    @Override
    public String getSessionId() {
        return context.requireToolControl().sessionId();
    }

    @Override
    public String getLeafId() {
        var session = context.requireToolControl();
        var real = session.session();
        return real != null ? real.getLeafId() : session.currentLeafId();
    }

    @Override
    public List<SessionTreeNode> getTree() {
        return context.requireToolControl().getTree();
    }
}
