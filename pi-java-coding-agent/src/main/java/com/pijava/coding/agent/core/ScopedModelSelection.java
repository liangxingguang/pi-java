package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Pure operations from pi scoped-models-selector.ts:21-70. */
public final class ScopedModelSelection {

    private ScopedModelSelection() {}

    /** All is enabled everywhere; Explicit checks membership. */
    public static boolean isEnabled(EnabledModelSet enabled, String id) {
        return switch (enabled) {
            case EnabledModelSet.All ignored -> true;
            case EnabledModelSet.Explicit(var ids) -> ids.contains(id);
        };
    }

    /** Toggle one id and collapse only a complete explicit cover back to All. */
    public static EnabledModelSet toggle(EnabledModelSet enabled, List<String> allIds, String id) {
        if (enabled instanceof EnabledModelSet.All) {
            return new EnabledModelSet.Explicit(allIds.stream().filter(modelId -> !modelId.equals(id)).toList());
        }
        var ids = new ArrayList<>(((EnabledModelSet.Explicit) enabled).orderedIds());
        int index = ids.indexOf(id);
        if (index >= 0) {
            ids.remove(index);
            return new EnabledModelSet.Explicit(ids);
        }
        ids.add(id);
        return normalize(ids, allIds);
    }

    /** Enable all ids, or only targetIds when a search/provider filter is active. */
    public static EnabledModelSet enableAll(EnabledModelSet enabled, List<String> allIds,
                                            List<String> targetIds) {
        if (enabled instanceof EnabledModelSet.All) {
            return enabled;
        }
        var result = new ArrayList<>(((EnabledModelSet.Explicit) enabled).orderedIds());
        for (var id : targetIds == null ? allIds : targetIds) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return normalize(result, allIds);
    }

    /** Clear all ids, or only targetIds; unlike enableAll this never collapses to All. */
    public static EnabledModelSet clearAll(EnabledModelSet enabled, List<String> allIds,
                                           List<String> targetIds) {
        if (enabled instanceof EnabledModelSet.All) {
            return targetIds == null
                ? new EnabledModelSet.Explicit(List.of())
                : new EnabledModelSet.Explicit(allIds.stream().filter(id -> !targetIds.contains(id)).toList());
        }
        var ids = ((EnabledModelSet.Explicit) enabled).orderedIds();
        var targets = new HashSet<>(targetIds == null ? ids : targetIds);
        return new EnabledModelSet.Explicit(ids.stream().filter(id -> !targets.contains(id)).toList());
    }

    /** Reorder an explicit enabled id by one position; All and out-of-range moves are no-ops. */
    public static EnabledModelSet move(EnabledModelSet enabled, String id, int delta) {
        if (!(enabled instanceof EnabledModelSet.Explicit explicit)) {
            return enabled;
        }
        var result = new ArrayList<>(explicit.orderedIds());
        int index = result.indexOf(id);
        int newIndex = index + delta;
        if (index < 0 || newIndex < 0 || newIndex >= result.size()) {
            return explicit;
        }
        var moved = result.remove(index);
        result.add(newIndex, moved);
        return new EnabledModelSet.Explicit(result);
    }

    /** Explicit ids first in their custom order, then remaining catalog ids. */
    public static List<String> sortedIds(EnabledModelSet enabled, List<String> allIds) {
        if (enabled instanceof EnabledModelSet.All) {
            return List.copyOf(allIds);
        }
        var ids = ((EnabledModelSet.Explicit) enabled).orderedIds();
        var enabledSet = new HashSet<>(ids);
        var result = new ArrayList<>(ids);
        allIds.stream().filter(id -> !enabledSet.contains(id)).forEach(result::add);
        return List.copyOf(result);
    }

    private static EnabledModelSet normalize(List<String> result, List<String> allIds) {
        return result.size() == allIds.size() && result.stream().allMatch(allIds::contains)
            ? new EnabledModelSet.All() : new EnabledModelSet.Explicit(result);
    }
}
