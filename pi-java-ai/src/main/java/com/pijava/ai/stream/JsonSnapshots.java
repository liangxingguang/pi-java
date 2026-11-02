package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Deep, null-preserving immutable copies of ordinary JSON values. */
public final class JsonSnapshots {

    private JsonSnapshots() {
    }

    /** Copy a JSON object, retaining insertion order and explicit null values. */
    public static Map<String, Object> copyObject(Map<String, Object> value) {
        Objects.requireNonNull(value, "value");
        return castObject(copyValue(value));
    }

    /** Copy any JSON value without recursion, so deeply nested provider input remains safe. */
    @Nullable
    public static Object copyValue(@Nullable Object value) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            return value;
        }
        Object copy = emptyContainer(value);
        CopyFrame root = CopyFrame.root(value, copy);
        List<CopyFrame> stack = new ArrayList<>();
        IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        active.put(value, Boolean.TRUE);
        stack.add(root);
        while (!stack.isEmpty()) {
            CopyFrame frame = stack.getLast();
            if (frame.hasNext()) {
                Object child = frame.next();
                if (child instanceof Map<?, ?> || child instanceof List<?>) {
                    if (active.containsKey(child)) {
                        throw new IllegalArgumentException("JSON arguments must not contain cycles");
                    }
                    Object childCopy = emptyContainer(child);
                    frame.attach(childCopy);
                    active.put(child, Boolean.TRUE);
                    stack.add(CopyFrame.child(child, childCopy, frame, frame.lastSlot));
                } else {
                    frame.attach(child);
                }
                continue;
            }
            Object sealed = sealContainer(frame.copy);
            active.remove(frame.original);
            stack.removeLast();
            if (frame.parent == null) {
                return sealed;
            }
            frame.parent.replace(frame.slot, sealed);
        }
        throw new IllegalStateException("unreachable");
    }

    @SuppressWarnings("unchecked") // JSON object keys are guaranteed to be strings by the parser and codecs.
    static Map<String, Object> castObject(Object value) {
        return (Map<String, Object>) value;
    }

    static Object sealContainer(Object container) {
        if (container instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(map);
        }
        if (container instanceof List<?> list) {
            return Collections.unmodifiableList(list);
        }
        throw new IllegalArgumentException("Not a JSON container");
    }

    static Object emptyContainer(Object container) {
        if (container instanceof Map<?, ?>) {
            return new LinkedHashMap<String, Object>();
        }
        if (container instanceof List<?>) {
            return new ArrayList<>();
        }
        throw new IllegalArgumentException("Not a JSON container");
    }

    /** Iterator state for an iterative deep copy; completed children are replaced by sealed copies. */
    private static final class CopyFrame {
        private final Object original;
        private final Object copy;
        private final CopyFrame parent;
        private final Object slot;
        private Iterator<?> iterator;
        private int index;
        private Object lastSlot;

        private CopyFrame(Object original, Object copy, CopyFrame parent, Object slot) {
            this.original = original;
            this.copy = copy;
            this.parent = parent;
            this.slot = slot;
            if (original instanceof Map<?, ?> map) {
                iterator = map.entrySet().iterator();
            } else {
                iterator = ((List<?>) original).iterator();
            }
        }

        static CopyFrame root(Object original, Object copy) {
            return new CopyFrame(original, copy, null, null);
        }

        static CopyFrame child(Object original, Object copy, CopyFrame parent, Object slot) {
            return new CopyFrame(original, copy, parent, slot);
        }

        boolean hasNext() {
            return iterator.hasNext();
        }

        Object next() {
            Object next = iterator.next();
            if (original instanceof Map<?, ?>) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) next;
                lastSlot = entry.getKey();
                return entry.getValue();
            }
            lastSlot = index++;
            return next;
        }

        void attach(Object value) {
            replace(lastSlot, value);
        }

        @SuppressWarnings("unchecked") // The copy has the same JSON container shape as its source.
        void replace(Object key, Object value) {
            if (copy instanceof Map<?, ?>) {
                ((Map<String, Object>) copy).put((String) key, value);
            } else {
                List<Object> list = (List<Object>) copy;
                int element = (Integer) key;
                if (element == list.size()) {
                    list.add(value);
                } else {
                    list.set(element, value);
                }
            }
        }
    }
}
