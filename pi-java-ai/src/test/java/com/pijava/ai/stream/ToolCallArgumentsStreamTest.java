package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Final tool argument parsing and authoritative replacement tests. */
class ToolCallArgumentsStreamTest {

    @Test
    void showsPartialObjectAndFinishesWithOneFullParse() {
        ToolCallArgumentsStream args = new ToolCallArgumentsStream();
        assertThat(args.append("  ")).isEqualTo(Map.of());
        assertThat(args.append("{\"path\":\"a.ts\",\"content\":\"x"))
                .isEqualTo(Map.of("path", "a.ts", "content", "x"));
        Map<String, Object> before = args.append("y");
        assertThat(args.append("")).isSameAs(before);
        assertThat(args.text()).isEqualTo("  {\"path\":\"a.ts\",\"content\":\"xy");

        ToolCallArgumentsStream.Finish unfinished = args.finish();
        assertThat(unfinished.arguments()).isEqualTo(Map.of());
        assertThat(unfinished.argumentsError()).endsWith("\n\nReceived arguments:\n"
                + "  {\"path\":\"a.ts\",\"content\":\"xy");

        args.append("\"}");
        ToolCallArgumentsStream.Finish finished = args.finish();
        assertThat(finished.arguments()).isEqualTo(Map.of("path", "a.ts", "content", "xy"));
        assertThat(finished.argumentsError()).isNull();
    }

    @Test
    void nonObjectRootsAreNoPartialArgumentsAndFailAtFinish() {
        for (String text : List.of("[1,2]", "null", "true", "12", "\"text\"")) {
            ToolCallArgumentsStream args = new ToolCallArgumentsStream();
            assertThat(args.append(text)).isEqualTo(Map.of());
            ToolCallArgumentsStream.Finish finish = args.finish();
            assertThat(finish.arguments()).isEqualTo(Map.of());
            assertThat(finish.argumentsError()).isEqualTo("Tool arguments must be a JSON object"
                    + "\n\nReceived arguments:\n" + text);
        }
    }

    @Test
    void prefixReplaceReturnsTailOnceAndNonPrefixResetsWithoutDelta() {
        ToolCallArgumentsStream args = new ToolCallArgumentsStream();
        args.append("{\"a\":\"x");
        ToolCallArgumentsStream.Replacement extended = args.replace("{\"a\":\"xyz\"}");
        assertThat(extended.delta()).isEqualTo("yz\"}");
        assertThat(extended.arguments()).isEqualTo(Map.of("a", "xyz"));
        ToolCallArgumentsStream.Replacement same = args.replace("{\"a\":\"xyz\"}");
        assertThat(same.delta()).isEmpty();
        assertThat(same.arguments()).isEqualTo(extended.arguments());

        ToolCallArgumentsStream.Replacement replaced = args.replace("{\"b\":2}");
        assertThat(replaced.delta()).isNull();
        assertThat(replaced.arguments()).isEqualTo(Map.of("b", 2));
        assertThat(args.text()).isEqualTo("{\"b\":2}");
    }

    @Test
    void blankFinishUsesEcmaWhitespaceOnly() {
        for (String blank : List.of("", " \t\r\n", "\u00a0\ufeff\u2028\u2029\u3000")) {
            ToolCallArgumentsStream.Finish finish = finish(blank);
            assertThat(finish.arguments()).isEqualTo(Map.of());
            assertThat(finish.argumentsError()).isNull();
        }
        ToolCallArgumentsStream.Finish nonBlank = finish("\u200b");
        assertThat(nonBlank.argumentsError()).isNotNull();
    }

    @Test
    void finishRepairsOnlyThePinnedStringFamily() {
        String text = "{\"path\":\"a.tex\",\"content\":\"\\usepackage{x}\","
                + "\"unicode\":\"caf\\u00e9\",\"raw\":\"x\ny\000z\"}";
        ToolCallArgumentsStream.Finish repaired = finish(text);
        assertThat(repaired.argumentsError()).isNull();
        assertThat(repaired.arguments()).isEqualTo(Map.of("path", "a.tex",
                "content", "\\usepackage{x}", "unicode", "café", "raw", "x\ny\000z"));

        for (String malformed : List.of("{'a':1}", "{a:1}", "{\"a\":1,}",
                "// comment\n{\"a\":1}", "{\"a\":1} trailing")) {
            ToolCallArgumentsStream.Finish finish = finish(malformed);
            assertThat(finish.arguments()).isEqualTo(Map.of());
            assertThat(finish.argumentsError()).endsWith("\n\nReceived arguments:\n" + malformed);
        }
    }

    @Test
    void validPrefixIsNotRetainedAfterMalformedSuffix() {
        ToolCallArgumentsStream args = new ToolCallArgumentsStream();
        args.append("{\"valid\":true} broken");
        assertThat(args.finish().arguments()).isEqualTo(Map.of());
        assertThat(args.finish().argumentsError()).contains("Received arguments:\n{\"valid\":true} broken");
    }

    @Test
    void finishRetainsNestedNullsAndReturnsDeeplyImmutableArguments() {
        ToolCallArgumentsStream.Finish finish = finish("{\"value\":null,\"nested\":[null,{\"x\":null}]}");
        assertThat(finish.argumentsError()).isNull();
        assertThat(finish.arguments()).containsKey("value");
        assertThat(finish.arguments().get("value")).isNull();
        List<?> nested = (List<?>) finish.arguments().get("nested");
        assertThat(nested).hasSize(2);
        assertThat(nested.get(0)).isNull();
        assertThat(((Map<?, ?>) nested.get(1)).containsKey("x")).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> finish.arguments().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ((List<Object>) nested).add(1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void largeDeltasDoNotChangeEarlierSnapshots() {
        ToolCallArgumentsStream args = new ToolCallArgumentsStream();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String prefix = index == 0 ? "{" : ",";
            snapshots.add(args.append(prefix + "\"key" + index + "\":\"value"));
            args.append(" " + index + "\"");
        }
        for (int index = 0; index < snapshots.size(); index++) {
            String key = "key" + index;
            assertThat(snapshots.get(index)).containsEntry(key, "value");
            assertThat(snapshots.get(index).get(key)).isNotEqualTo("value " + index);
        }
        Map<String, Object> expected = new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            expected.put("key" + index, "value " + index);
        }
        args.append("}");
        assertThat(args.finish().arguments()).isEqualTo(expected);
    }

    private static ToolCallArgumentsStream.Finish finish(String text) {
        ToolCallArgumentsStream stream = new ToolCallArgumentsStream();
        stream.append(text);
        return stream.finish();
    }
}
