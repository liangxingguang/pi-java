package com.pijava.agent.session;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.message.Message;

/**
 * Non-main lane view delegating to a {@link SessionStorage} with the lane's
 * leaf as the default branch start (aligned with pi's {@code Session.view}).
 */
final class LaneView implements SessionTree {

    private final SessionStorage<?> storage;
    private final String lane;
    private final Session<?> session;

    LaneView(SessionStorage<?> storage, String lane, Session<?> session) {
        this.storage = storage;
        this.lane = lane;
        this.session = session;
    }

    private String leaf() {
        var pointer = storage.getLanes().stream()
            .filter(p -> lane.equals(p.lane()))
            .findFirst()
            .orElseThrow(() -> new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane));
        return pointer.leafId();
    }

    @Override
    public String getLeafId() {
        return leaf();
    }

    @Override
    public Entry getEntry(String id) {
        return storage.getEntry(id);
    }

    @Override
    public SessionStats getStats() {
        return storage.getStats();
    }

    @Override
    public String getName() {
        return storage.getName();
    }

    @Override
    public void setName(String name) {
        storage.setName(name);
    }

    @Override
    public String getLabel(String targetId) {
        return storage.getLabel(targetId);
    }

    @Override
    public void setLabel(String targetId, String label) {
        storage.setLabel(targetId, label);
    }

    @Override
    public List<Entry> findEntries(EntryQuery query) {
        return storage.findEntries(query);
    }

    @Override
    public Entry findEntry(EntryQuery query) {
        var entries = storage.findEntries(withLimit(query, 1));
        return entries.isEmpty() ? null : entries.get(0);
    }

    @Override
    public List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds) {
        return queryBranch(query, bounds, 0);
    }

    @Override
    public Entry findEntryOnBranch(EntryQuery query, BranchBounds bounds) {
        var entries = queryBranch(query, bounds, 1);
        return entries.isEmpty() ? null : entries.get(0);
    }

    @Override
    public String appendMessage(Message message) {
        var provisioned = new ProvisionedEntry<Entry.Message>(
            new Entry.Message(session.idGenerator().next(), 0, null, null, message, null));
        return storage.appendEntry(provisioned, lane).id();
    }

    @Override
    public String appendCustomEntry(String customType, Map<String, Object> data) {
        var provisioned = new ProvisionedEntry<Entry.Custom>(
            new Entry.Custom(session.idGenerator().next(), 0, null, null, customType, data));
        return storage.appendEntry(provisioned, lane).id();
    }

    private List<Entry> queryBranch(EntryQuery query, BranchBounds bounds, int resultLimit) {
        var effectiveBounds = bounds == null ? BranchBounds.none() : bounds;
        String start = effectiveBounds.start();
        if (start == null) {
            start = leaf();
            if (start == null) {
                return List.of();
            }
        }
        var effectiveQuery = resultLimit > 0 ? withLimit(query, resultLimit) : query;
        return storage.findEntriesOnBranch(effectiveQuery, effectiveBounds, start);
    }

    private static EntryQuery withLimit(EntryQuery query, int limit) {
        if (query == null) {
            return new EntryQuery(null, null, EntryOrder.NEWEST_FIRST, limit, null);
        }
        return new EntryQuery(query.type(), query.customType(), query.order(), limit, query.cursor());
    }
}
