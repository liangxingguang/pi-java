package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Incremental, tolerant JSON parser whose returned snapshots never change afterwards. */
public final class StreamingJsonParser {

    private static final int CREDIT_PER_APPEND = 256;
    private static final int CREDIT_PER_CHARACTER = 8;
    private static final int MAX_PENDING_SCALAR_LENGTH = 64;
    private static final Slot ROOT = new Slot.Root();

    private Object root;
    private boolean rootPresent;
    private boolean done;
    private final List<Frame> stack = new ArrayList<>();
    private OpenString string;
    private String scalar = "";
    private Slot scalarSlot;
    private int pathCost;
    private int credit;
    private boolean dirty;
    private Object snapshot;
    private final List<JsonChange> changes = new ArrayList<>();

    /** Read one piece once and return its ordered public changes. */
    public List<JsonChange> append(String delta) {
        credit += CREDIT_PER_APPEND + CREDIT_PER_CHARACTER * delta.length();
        changes.clear();
        if (string != null) {
            string.changeIndex = -1;
        }
        int index = 0;
        while (index < delta.length() && !done) {
            if (string != null) {
                index = readString(delta, index);
            } else if (!scalar.isEmpty()) {
                index = readScalar(delta, index);
            } else {
                structural(delta.charAt(index++));
            }
        }
        return List.copyOf(changes);
    }

    /** The value read so far, or null both before a value and for a JSON null; use hasValue to distinguish. */
    @Nullable
    public Object snapshot() {
        if (!dirty) {
            return snapshot;
        }
        PendingScalar pending = pendingScalar();
        int cost = pathCost + (pending == null ? 0 : 1);
        if (cost > credit) {
            return snapshot;
        }
        credit -= cost;
        dirty = false;
        snapshot = copyOpenPath(pending);
        return snapshot;
    }

    /** Whether a root value has started, including a pending or completed JSON null. */
    public boolean hasValue() {
        if (rootPresent) {
            return true;
        }
        PendingScalar pending = pendingScalar();
        return pending != null && pending.slot instanceof Slot.Root;
    }

    private int readString(String text, int start) {
        OpenString open = string;
        int runStart = start;
        for (int index = start; index < text.length(); index++) {
            char code = text.charAt(index);
            if (!open.escape.isEmpty()) {
                if (continueEscape(open, text.charAt(index))) {
                    runStart = index + 1;
                    continue;
                }
                runStart = index;
            }
            if (code == '"') {
                if (index > runStart) {
                    appendString(open, text.substring(runStart, index));
                }
                endString(open);
                return index + 1;
            }
            if (code == '\\') {
                if (index > runStart) {
                    appendString(open, text.substring(runStart, index));
                }
                open.escape = "\\";
                runStart = index + 1;
            }
        }
        if (text.length() > runStart) {
            appendString(open, text.substring(runStart));
        }
        return text.length();
    }

    private int readScalar(String text, int start) {
        int end = start;
        while (end < text.length() && !StreamingJsonScalars.isDelimiter(text.charAt(end))) {
            end++;
        }
        if (end > start) {
            scalar += text.substring(start, end);
            if (scalarSlot != null) {
                dirty = true;
            }
        }
        if (end < text.length()) {
            endScalar();
        }
        return end;
    }

    private void structural(char character) {
        switch (character) {
            case ' ', '\t', '\n', '\r' -> {
            }
            case '"' -> startString();
            case '{', '[' -> openContainer(character == '{');
            case '}', ']' -> closeContainer();
            case ':' -> {
                Frame frame = lastFrame();
                if (frame != null && !frame.isArray && frame.phase == Phase.COLON) {
                    frame.phase = Phase.VALUE;
                }
            }
            case ',' -> {
                Frame frame = lastFrame();
                if (frame != null) {
                    frame.phase = frame.isArray ? Phase.VALUE : Phase.KEY;
                }
            }
            default -> {
                scalar = String.valueOf(character);
                scalarSlot = valueSlot();
                if (scalarSlot != null) {
                    dirty = true;
                }
            }
        }
    }

    private Slot valueSlot() {
        Frame frame = lastFrame();
        if (frame == null) {
            return rootPresent ? null : ROOT;
        }
        if (frame.isArray) {
            return new Slot.Element(frame.elements().size());
        }
        if ((frame.phase == Phase.VALUE || frame.phase == Phase.COLON) && frame.key != null) {
            return new Slot.Member(frame.key);
        }
        return null;
    }

    private void startString() {
        Frame frame = lastFrame();
        if (frame != null && !frame.isArray && (frame.phase == Phase.KEY || frame.phase == Phase.COMMA)) {
            string = new OpenString(Target.KEY, null, null);
            return;
        }
        Slot slot = valueSlot();
        List<Object> path = reportedPath(slot);
        string = new OpenString(Target.VALUE, slot, path);
        place(slot, "");
        if (path != null) {
            report(new JsonChange.Set(path, ""));
            string.changeIndex = changes.size() - 1;
        }
    }

    private List<Object> reportedPath(Slot slot) {
        if (slot == null) {
            return null;
        }
        if (slot instanceof Slot.Root) {
            return List.of();
        }
        Frame frame = lastFrame();
        if (!frame.reportsInside) {
            return null;
        }
        List<Object> path = new ArrayList<>(frame.path);
        path.add(slot instanceof Slot.Member member ? member.key() : ((Slot.Element) slot).index());
        return path;
    }

    private void report(JsonChange change) {
        changes.add(change);
    }

    private boolean continueEscape(OpenString open, char character) {
        if (open.escape.equals("\\")) {
            String decoded = StreamingJsonScalars.simpleEscape(character);
            if (decoded != null) {
                open.escape = "";
                appendString(open, decoded);
                return true;
            }
            if (character == 'u') {
                open.escape = "\\u";
                return true;
            }
            open.escape = "";
            appendString(open, "\\");
            return false;
        }
        if (StreamingJsonScalars.isHexDigit(character)) {
            open.escape += character;
            if (open.escape.length() < 6) {
                return true;
            }
            String decoded = String.valueOf((char) Integer.parseInt(open.escape.substring(2), 16));
            open.escape = "";
            appendString(open, decoded);
            return true;
        }
        String literal = open.escape;
        open.escape = "";
        appendString(open, literal);
        return false;
    }

    private void appendString(OpenString open, String text) {
        open.text.append(text);
        if (open.target != Target.VALUE) {
            return;
        }
        place(open.slot, open.text.toString());
        if (open.path == null) {
            return;
        }
        int last = changes.size() - 1;
        if (open.changeIndex == last && last >= 0) {
            JsonChange change = changes.get(last);
            if (change instanceof JsonChange.Set set) {
                changes.set(last, new JsonChange.Set(set.path(), set.value() + text));
            } else {
                JsonChange.Append append = (JsonChange.Append) change;
                changes.set(last, new JsonChange.Append(append.path(), append.text() + text));
            }
            return;
        }
        report(new JsonChange.Append(open.path, text));
        open.changeIndex = changes.size() - 1;
    }

    private void endString(OpenString open) {
        string = null;
        Frame frame = lastFrame();
        if (open.target == Target.KEY) {
            if (frame != null) {
                frame.key = open.text.toString();
                frame.phase = Phase.COLON;
            }
            return;
        }
        valueEnded(frame);
    }

    private void endScalar() {
        String token = scalar;
        Slot slot = scalarSlot;
        scalar = "";
        scalarSlot = null;
        Object value = StreamingJsonScalars.complete(token);
        if (value != StreamingJsonScalars.UNDEFINED) {
            place(slot, value);
            List<Object> path = reportedPath(slot);
            if (path != null) {
                report(new JsonChange.Set(path, value));
            }
        }
        valueEnded(lastFrame());
    }

    private PendingScalar pendingScalar() {
        if (scalarSlot == null || scalar.isEmpty() || scalar.length() > MAX_PENDING_SCALAR_LENGTH) {
            return null;
        }
        Object value = StreamingJsonScalars.pending(scalar);
        return value == StreamingJsonScalars.UNDEFINED ? null : new PendingScalar(scalarSlot, value);
    }

    private void openContainer(boolean object) {
        Slot slot = valueSlot();
        List<Object> path = reportedPath(slot);
        Object container = object ? new LinkedHashMap<String, Object>() : new ArrayList<>();
        place(slot, container);
        stack.add(new Frame(container, slot, path, path != null && path.size() < JsonChange.MAX_REPORTED_DEPTH));
        pathCost++;
        dirty = true;
        if (path != null) {
            report(new JsonChange.Set(path, object ? Map.of() : List.of()));
        }
    }

    private void closeContainer() {
        if (stack.isEmpty()) {
            return;
        }
        Frame frame = stack.removeLast();
        pathCost -= frame.size + 1;
        dirty = true;
        Object completed = JsonSnapshots.sealContainer(frame.container);
        replace(frame.slot, completed);
        if (frame.path != null && !frame.reportsInside) {
            report(new JsonChange.Set(frame.path, completed));
        }
        valueEnded(lastFrame());
    }

    private void valueEnded(Frame frame) {
        if (frame == null) {
            if (rootPresent) {
                done = true;
            }
        } else {
            frame.phase = Phase.COMMA;
        }
    }

    private void place(Slot slot, Object value) {
        if (slot == null) {
            return;
        }
        dirty = true;
        if (slot instanceof Slot.Root) {
            root = value;
            rootPresent = true;
            return;
        }
        Frame frame = lastFrame();
        if (slot instanceof Slot.Element element) {
            List<Object> elements = frame.elements();
            if (element.index() == elements.size()) {
                frame.size++;
                pathCost++;
                elements.add(value);
            } else {
                elements.set(element.index(), value);
            }
            return;
        }
        String key = ((Slot.Member) slot).key();
        Map<String, Object> members = frame.members();
        if (!members.containsKey(key)) {
            frame.size++;
            pathCost++;
        }
        members.put(key, value);
    }

    private void replace(Slot slot, Object value) {
        if (slot == null) {
            return;
        }
        if (slot instanceof Slot.Root) {
            root = value;
            return;
        }
        Frame parent = lastFrame();
        if (slot instanceof Slot.Element element) {
            parent.elements().set(element.index(), value);
        } else {
            parent.members().put(((Slot.Member) slot).key(), value);
        }
    }

    private Object copyOpenPath(PendingScalar pending) {
        if (stack.isEmpty()) {
            return pending != null && pending.slot instanceof Slot.Root ? pending.value : root;
        }
        Object child = null;
        Slot childSlot = null;
        for (int depth = stack.size() - 1; depth >= 0; depth--) {
            Frame frame = stack.get(depth);
            Object copy = JsonSnapshots.emptyContainer(frame.container);
            if (frame.isArray) {
                ((List<Object>) copy).addAll(frame.elements());
            } else {
                ((Map<String, Object>) copy).putAll(frame.members());
            }
            if (depth == stack.size() - 1 && pending != null) {
                setSlot(copy, pending.slot, pending.value);
            }
            if (child != null && childSlot != null) {
                setSlot(copy, childSlot, child);
            }
            child = JsonSnapshots.sealContainer(copy);
            childSlot = frame.slot;
        }
        return childSlot instanceof Slot.Root ? child : root;
    }

    @SuppressWarnings("unchecked") // Every slot matches the container shape selected by the parser.
    private static void setSlot(Object container, Slot slot, Object value) {
        if (slot instanceof Slot.Member member) {
            ((Map<String, Object>) container).put(member.key(), value);
        } else {
            List<Object> list = (List<Object>) container;
            int index = ((Slot.Element) slot).index();
            if (index == list.size()) {
                list.add(value);
            } else {
                list.set(index, value);
            }
        }
    }

    private Frame lastFrame() {
        return stack.isEmpty() ? null : stack.getLast();
    }

    private sealed interface Slot {
        record Root() implements Slot { }
        record Member(String key) implements Slot { }
        record Element(int index) implements Slot { }
    }

    private enum Phase { KEY, COLON, VALUE, COMMA }
    private enum Target { KEY, VALUE }

    private static final class Frame {
        private final Object container;
        private final Slot slot;
        private final List<Object> path;
        private final boolean reportsInside;
        private final boolean isArray;
        private int size;
        private String key;
        private Phase phase;

        private Frame(Object container, Slot slot, List<Object> path, boolean reportsInside) {
            this.container = container;
            this.slot = slot;
            this.path = path;
            this.reportsInside = reportsInside;
            this.isArray = container instanceof List<?>;
            this.phase = isArray ? Phase.VALUE : Phase.KEY;
        }

        @SuppressWarnings("unchecked") // Frame shape is fixed at construction.
        private Map<String, Object> members() {
            return (Map<String, Object>) container;
        }

        @SuppressWarnings("unchecked") // Frame shape is fixed at construction.
        private List<Object> elements() {
            return (List<Object>) container;
        }
    }

    private static final class OpenString {
        private final Target target;
        private final Slot slot;
        private final StringBuilder text = new StringBuilder();
        private String escape = "";
        private final List<Object> path;
        private int changeIndex = -1;

        private OpenString(Target target, Slot slot, List<Object> path) {
            this.target = target;
            this.slot = slot;
            this.path = path;
        }
    }

    private record PendingScalar(Slot slot, Object value) { }
}
