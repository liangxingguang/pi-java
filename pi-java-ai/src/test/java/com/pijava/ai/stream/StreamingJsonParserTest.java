package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Pinned oracles from pi {@code streaming-json.test.ts}. */
class StreamingJsonParserTest {

    @Test
    void readsNestedJsonAtEverySplitPoint() {
        String text = "{\"path\":\"a.tex\",\"items\":[1,{\"x\":\"é😀\",\"n\":null}],\"done\":true}";
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("x", "é😀");
        nested.put("n", null);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("path", "a.tex");
        expected.put("items", List.of(1, nested));
        expected.put("done", true);

        for (int split = 0; split <= text.length(); split++) {
            StreamingJsonParser parser = new StreamingJsonParser();
            parser.append(text.substring(0, split));
            parser.snapshot();
            parser.append(text.substring(split));
            assertThat(parser.snapshot()).as("split %d", split).isEqualTo(expected);
            assertThat(parser.hasValue()).isTrue();
        }
    }

    @Test
    void neverChangesReturnedSnapshotsAndRetainsJsonNulls() {
        StreamingJsonParser parser = new StreamingJsonParser();
        parser.append("{\"a\":{\"b\":\"x");
        Object first = parser.snapshot();
        assertThat(first).isEqualTo(Map.of("a", Map.of("b", "x")));

        parser.append("y\",\"c\":[1,null");
        Object second = parser.snapshot();
        Map<String, Object> secondExpected = new LinkedHashMap<>();
        secondExpected.put("b", "xy");
        List<Object> secondItems = new ArrayList<>();
        secondItems.add(1);
        secondItems.add(null);
        secondExpected.put("c", secondItems);
        assertThat(second).isEqualTo(Map.of("a", secondExpected));

        parser.append("],\"d\":2}");
        Object third = parser.snapshot();
        Map<String, Object> thirdExpected = new LinkedHashMap<>();
        thirdExpected.put("b", "xy");
        thirdExpected.put("c", secondItems);
        thirdExpected.put("d", 2);
        assertThat(first).isEqualTo(Map.of("a", Map.of("b", "x")));
        assertThat(second).isEqualTo(Map.of("a", secondExpected));
        assertThat(third).isEqualTo(Map.of("a", thirdExpected));
        assertThatJsonNullsRetained(third);
        assertThatThrownByUnsupported(second);
    }

    @Test
    void omitsUnfinishedMembersAndShowsPendingScalars() {
        assertThat(snapshot("{\"key\":")).isEqualTo(Map.of());
        assertThat(snapshot("{\"k1\":1,\"k2")).isEqualTo(Map.of("k1", 1));
        List<Object> pendingNull = new ArrayList<>();
        pendingNull.add(null);
        assertThat(snapshot("[[false],[nu")).isEqualTo(List.of(List.of(false), pendingNull));
        assertThat(snapshot("n")).isNull();
        assertThat(snapshot("t")).isEqualTo(true);
        assertThat(snapshot("fals")).isEqualTo(false);
        assertThat(snapshot("-1.25e+")).isEqualTo(-1.25);
        assertThat(snapshot("12.")).isNull();
        assertThat(snapshot("12.e")).isNull();
        assertThat(snapshot("-")).isNull();
        assertThat(snapshot("[1, ")).isEqualTo(List.of(1));
    }

    @Test
    void distinguishesNoValueFromJsonNull() {
        StreamingJsonParser parser = new StreamingJsonParser();
        assertThat(parser.hasValue()).isFalse();
        assertThat(parser.snapshot()).isNull();
        parser.append("nu");
        assertThat(parser.hasValue()).isTrue();
        assertThat(parser.snapshot()).isNull();
        parser.append("ll ");
        assertThat(parser.hasValue()).isTrue();
        assertThat(parser.snapshot()).isNull();
    }

    @Test
    void holdsEscapesAndSurrogatesAcrossEverySplit() {
        String text = "{\"a\":\"x\\u00e9\\n\\\\\\\"\\ud83d\\ude00\\q\"}";
        for (int split = 0; split <= text.length(); split++) {
            StreamingJsonParser parser = new StreamingJsonParser();
            parser.append(text.substring(0, split));
            parser.snapshot();
            parser.append(text.substring(split));
            assertThat(parser.snapshot()).as("split %d", split)
                    .isEqualTo(Map.of("a", "xé\n\\\"😀\\q"));
        }

        StreamingJsonParser incomplete = new StreamingJsonParser();
        incomplete.append("{\"text\":\"ab\\u00");
        assertThat(incomplete.snapshot()).isEqualTo(Map.of("text", "ab"));
    }

    @Test
    void keepsRawControlsAndInvalidUnicodeEscapesLiteral() {
        assertThat(snapshot("{\"path\":\"a.tex\",\"content\":\"\\usepackage{x}\",\"mode\":\"w\"}"))
                .isEqualTo(Map.of("path", "a.tex", "content", "\\usepackage{x}", "mode", "w"));
        assertThat(snapshot("{\"text\":\"caf\\u00e9 \\url\"}"))
                .isEqualTo(Map.of("text", "café \\url"));
        assertThat(snapshot("{\"a\":\"x\ny \\d \\u12\"}"))
                .isEqualTo(Map.of("a", "x\ny \\d \\u12"));
        assertThat(snapshot("{\"a\":\"x\000y\"}")).isEqualTo(Map.of("a", "x\000y"));
    }

    @Test
    void toleratesMalformedStructureWithoutThrowing() {
        assertThat(snapshot("{\"a\":1 \"b\":2}")).isEqualTo(Map.of("a", 1, "b", 2));
        assertThat(snapshot("{\"a\" 1, \"b\": 2}")).isEqualTo(Map.of("a", 1, "b", 2));
        assertThat(snapshot("[1 2, , 3,]")).isEqualTo(List.of(1, 2, 3));
        assertThat(snapshot("{\"a\":NaN,\"b\":Infinity,\"e\":1}")).isEqualTo(Map.of("e", 1));
        assertThat(snapshot("{\"a\":\"x\"} trailing garbage {\"b\":2}"))
                .isEqualTo(Map.of("a", "x"));
        assertThat(snapshot("{\"a\":1,\"b\":2,\"a\":\"three\"}"))
                .isEqualTo(Map.of("a", "three", "b", 2));
        assertThat(snapshot("{\"__proto__\":{\"polluted\":true}}"))
                .isEqualTo(Map.of("__proto__", Map.of("polluted", true)));

        List<String> malformed = List.of("{a:1, 'b': 2}", "{\"a\":1,,\"b\":2,}",
                "{\"a\":[1,2}, \"b\":3]", "]]]}}}{", "\"unterminated \\", "// comment\n{\"a\":1}");
        for (String text : malformed) {
            for (int split = 0; split <= text.length(); split++) {
                StreamingJsonParser parser = new StreamingJsonParser();
                String prefix = text.substring(0, split);
                String suffix = text.substring(split);
                assertThatCode(() -> {
                    parser.append(prefix);
                    parser.snapshot();
                    parser.append(suffix);
                    parser.snapshot();
                }).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void letsLargeOpenContainerPreviewLagButFinishesExactly() {
        StringBuilder text = new StringBuilder("{\"items\":[");
        for (int index = 0; index < 2_000; index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(index);
        }
        text.append("]}");

        StreamingJsonParser parser = new StreamingJsonParser();
        int previousSize = 0;
        boolean lagged = false;
        for (int offset = 0; offset < text.length(); offset += 16) {
            parser.append(text.substring(offset, Math.min(text.length(), offset + 16)));
            Object snapshot = parser.snapshot();
            if (snapshot instanceof Map<?, ?> map && map.get("items") instanceof List<?> items) {
                int currentSize = items.size();
                assertThat(currentSize).isGreaterThanOrEqualTo(previousSize);
                if (offset > 2_000 && currentSize == previousSize) {
                    lagged = true;
                }
                previousSize = currentSize;
            }
        }
        assertThat(lagged).isTrue();
        assertThat(parser.snapshot()).isInstanceOf(Map.class);
        assertThat((List<?>) ((Map<?, ?>) parser.snapshot()).get("items")).hasSize(2_000);
    }

    private static Object snapshot(String text) {
        StreamingJsonParser parser = new StreamingJsonParser();
        for (int offset = 0; offset < text.length(); offset++) {
            parser.append(text.substring(offset, offset + 1));
        }
        return parser.snapshot();
    }

    // Casts verify the runtime immutability and null retention of nested JSON containers.
    @SuppressWarnings("unchecked")
    private static void assertThatJsonNullsRetained(Object value) {
        Map<String, Object> root = (Map<String, Object>) value;
        Map<String, Object> a = (Map<String, Object>) root.get("a");
        List<Object> c = (List<Object>) a.get("c");
        assertThat(c).hasSize(2);
        assertThat(c.contains(null)).isTrue();
    }

    // Casts verify the runtime immutability and null retention of nested JSON containers.
    @SuppressWarnings("unchecked")
    private static void assertThatThrownByUnsupported(Object value) {
        Map<String, Object> root = (Map<String, Object>) value;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> root.put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        Map<String, Object> a = (Map<String, Object>) root.get("a");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ((List<Object>) a.get("c")).add(2))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
