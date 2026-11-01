package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the public incremental JsonChange mirror contract. */
class JsonChangeTest {

    @Test
    void reportsScalarAndStringChangesInOrder() {
        StreamingJsonParser parser = new StreamingJsonParser();
        assertThat(parser.append("{\"n\":1"))
                .containsExactly(new JsonChange.Set(List.of(), Map.of()));
        assertThat(parser.append("2,\"s\":\"ab"))
                .containsExactly(new JsonChange.Set(List.of("n"), 12),
                        new JsonChange.Set(List.of("s"), "ab"));
        assertThat(parser.append("c\",\"t\":tru"))
                .containsExactly(new JsonChange.Append(List.of("s"), "c"));
        assertThat(parser.append("e}"))
                .containsExactly(new JsonChange.Set(List.of("t"), true));
        assertThat(parser.append(" {\"ignored\":1}")).isEmpty();
    }

    @Test
    void reportsNestedContainersThenTheirContents() {
        StreamingJsonParser parser = new StreamingJsonParser();
        assertThat(parser.append("{\"a\":[1,{\"b\":[\"x\""))
                .containsExactly(new JsonChange.Set(List.of(), Map.of()),
                        new JsonChange.Set(List.of("a"), List.of()),
                        new JsonChange.Set(List.of("a", 0), 1),
                        new JsonChange.Set(List.of("a", 1), Map.of()),
                        new JsonChange.Set(List.of("a", 1, "b"), List.of()),
                        new JsonChange.Set(List.of("a", 1, "b", 0), "x"));
        assertThat(parser.append(",\"y"))
                .containsExactly(new JsonChange.Set(List.of("a", 1, "b", 1), "y"));
        assertThat(parser.append("z\"]}],\"a\":\"again\"}"))
                .containsExactly(new JsonChange.Append(List.of("a", 1, "b", 1), "z"),
                        new JsonChange.Set(List.of("a"), "again"));
    }

    @Test
    void mirrorsSplitEscapesAtEveryPairOfSplits() {
        String text = "{\"a\":\"x\\u00e9\\n\\\\\\\"\\ud83d\\ude00\\q\"}";
        for (int first = 1; first < text.length(); first++) {
            for (int second = first; second < text.length(); second++) {
                StreamingJsonParser parser = new StreamingJsonParser();
                Mirror mirror = new Mirror();
                mirror.apply(parser.append(text.substring(0, first)));
                mirror.apply(parser.append(text.substring(first, second)));
                mirror.apply(parser.append(text.substring(second)));
                assertThat(mirror.value).as("splits %d/%d", first, second)
                        .isEqualTo(Map.of("a", "xé\n\\\"😀\\q"));
            }
        }

        StreamingJsonParser parser = new StreamingJsonParser();
        parser.append("{\"a\":\"x");
        assertThat(parser.append("\\u00")).isEmpty();
        assertThat(parser.append("e9"))
                .containsExactly(new JsonChange.Append(List.of("a"), "é"));
    }

    @Test
    void reportsDeepContainerWholeAtDepth32() {
        int depth = 40;
        String text = "{\"a\":".repeat(depth) + "\"deep\"" + "}".repeat(depth);
        StreamingJsonParser streaming = new StreamingJsonParser();
        Mirror mirror = new Mirror();
        for (int offset = 0; offset < text.length(); offset += 3) {
            mirror.apply(streaming.append(text.substring(offset, Math.min(text.length(), offset + 3))));
        }
        assertThat(mirror.value).isEqualTo(streaming.snapshot());

        List<JsonChange> changes = new StreamingJsonParser().append(text);
        assertThat(changes).hasSize(34);
        JsonChange.Set last = (JsonChange.Set) changes.getLast();
        assertThat(last.path()).hasSize(32);
        Object expected = "deep";
        for (int level = 0; level < depth - 32; level++) {
            expected = Map.of("a", expected);
        }
        assertThat(last.value()).isEqualTo(expected);
    }

    @Test
    void reportsNothingForInvalidPositions() {
        StreamingJsonParser parser = new StreamingJsonParser();
        assertThat(parser.append("{\"a\":1 2, \"b\" \"x\"}"))
                .containsExactly(new JsonChange.Set(List.of(), Map.of()),
                        new JsonChange.Set(List.of("a"), 1),
                        new JsonChange.Set(List.of("b"), "x"));
        assertThat(parser.append("{\"c\":3}")).isEmpty();
    }

    @Test
    void changePathsAndValuesAreDefensivelyCopied() {
        List<Object> path = new ArrayList<>();
        path.add("a");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("items", new ArrayList<>(List.of(1)));
        JsonChange.Set set = new JsonChange.Set(path, value);
        path.add("b");
        value.put("other", 2);
        assertThat(set.path()).containsExactly("a");
        assertThat(set.value()).isEqualTo(Map.of("items", List.of(1)));
        assertThatThrownBy(() -> set.path().add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<String, Object>) set.value()).put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);

        List<Object> tooDeep = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            tooDeep.add("a");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new JsonChange.Set(tooDeep, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new JsonChange.Append(List.of(true), "x"));
    }

    /** A real consumer of the public changes, independent of parser snapshots. */
    private static final class Mirror {
        private Object value;

        void apply(List<JsonChange> changes) {
            for (JsonChange change : changes) {
                if (change instanceof JsonChange.Set set) {
                    set(set.path(), mutableCopy(set.value()));
                } else if (change instanceof JsonChange.Append append) {
                    append(append.path(), append.text());
                }
            }
        }

        private static Object mutableCopy(Object source) {
            if (source instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put((String) entry.getKey(), mutableCopy(entry.getValue()));
                }
                return copy;
            }
            if (source instanceof List<?> list) {
                List<Object> copy = new ArrayList<>();
                for (Object item : list) {
                    copy.add(mutableCopy(item));
                }
                return copy;
            }
            return source;
        }

        // Mirror updates require casts to the JSON container shape selected by each path.
        @SuppressWarnings("unchecked")
        private void set(List<Object> path, Object newValue) {
            if (path.isEmpty()) {
                value = newValue;
                return;
            }
            Object parent = parent(path);
            Object key = path.getLast();
            if (parent instanceof Map<?, ?>) {
                ((Map<String, Object>) parent).put((String) key, newValue);
            } else {
                ((List<Object>) parent).set(((Number) key).intValue(), newValue);
            }
        }

        // Mirror updates require casts to the JSON container shape selected by each path.
        @SuppressWarnings("unchecked")
        private void append(List<Object> path, String text) {
            if (path.isEmpty()) {
                value = (String) value + text;
                return;
            }
            Object parent = parent(path);
            Object key = path.getLast();
            if (parent instanceof Map<?, ?>) {
                Map<String, Object> map = (Map<String, Object>) parent;
                map.put((String) key, map.get((String) key) + text);
            } else {
                List<Object> list = (List<Object>) parent;
                int index = ((Number) key).intValue();
                list.set(index, list.get(index) + text);
            }
        }

        private Object parent(List<Object> path) {
            Object parent = value;
            for (int index = 0; index < path.size() - 1; index++) {
                Object key = path.get(index);
                if (parent instanceof Map<?, ?> map) {
                    parent = map.get((String) key);
                } else {
                    parent = ((List<?>) parent).get(((Number) key).intValue());
                }
            }
            return parent;
        }
    }
}
