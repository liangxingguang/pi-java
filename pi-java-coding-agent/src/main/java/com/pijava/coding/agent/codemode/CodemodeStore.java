package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionSessionView;

/** Session-backed {@code store()} values for codemode scripts. */
public final class CodemodeStore {
    /** Custom entry type holding one script's writes. */
    public static final String ENTRY_TYPE = "codemode-store";

    private CodemodeStore() {
    }

    /**
     * Replays store entries on the current branch from root to leaf.
     *
     * @param branch branch entries in submission order
     * @return the values visible to {@code load()}
     */
    public static Map<String, Object> read(List<Entry> branch) {
        Map<String, Object> store = new LinkedHashMap<>();
        if (branch == null) {
            return store;
        }
        for (Entry entry : branch) {
            if (!(entry instanceof Entry.Custom custom)
                    || !ENTRY_TYPE.equals(custom.customType())
                    || !(custom.data() instanceof Map<?, ?> data)) {
                continue;
            }
            // pi 的形状里 `set`/`delete` 均可缺省；字段类型不符时忽略该字段。
            Object set = data.get("set");
            Object deleted = data.get("delete");
            if (deleted instanceof List<?> keys) {
                for (Object key : keys) {
                    if (key instanceof String name) {
                        store.remove(name);
                    }
                }
            }
            if (set instanceof Map<?, ?> values) {
                for (Map.Entry<?, ?> value : values.entrySet()) {
                    if (value.getKey() instanceof String name) {
                        store.put(name, value.getValue());
                    }
                }
            }
        }
        return store;
    }

    /**
     * Reads the store from {@code ctx.sessionManager().getBranch()}.
     *
     * @param ctx extension context
     * @return branch-local store values
     */
    public static Map<String, Object> read(ExtensionContext ctx) {
        ExtensionSessionView view = ctx == null ? null : ctx.sessionManager();
        return read(view == null ? List.of() : view.getBranch());
    }

    /**
     * Appends one successful script's writes to the session.
     *
     * @param ctx extension context
     * @param set values to write
     * @param deleted keys to delete
     */
    public static void write(ExtensionContext ctx, Map<String, Object> set, List<String> deleted) {
        if (ctx == null || (set.isEmpty() && deleted.isEmpty())) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("set", new LinkedHashMap<>(set));
        data.put("delete", new ArrayList<>(deleted));
        ctx.appendEntry(ENTRY_TYPE, data);
    }
}
