package com.pijava.tui.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W2（R26，docs/23 §4.6）：pi {@code formatToolCallWithArgs} 对位——
 * collapsed {@code title k=v …} 100 截断、expanded {@code   key: value} 多行、
 * 复合值 JSON 两空格缩进＋续行缩 4。
 */
class FormatToolArgsTest {

    @Test
    void nullOrEmptyArgsYieldTitleOnly() {
        assertThat(FormatToolArgs.lines("read", null, false)).containsExactly("read");
        assertThat(FormatToolArgs.lines("read", Map.of(), true)).containsExactly("read");
    }

    /** collapsed：key=value 空格连接，超 100 截＋`...`（pi COLLAPSED_ARGS_CHARS）。 */
    @Test
    void collapsedJoinsPairsAndTruncatesAt100() {
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("path", "a.txt");
        ordered.put("limit", 2);
        assertThat(FormatToolArgs.lines("read", ordered, false))
            .containsExactly("read path=a.txt limit=2");

        var longValue = "x".repeat(120);
        var line = FormatToolArgs.lines("read", Map.of("path", longValue), false).get(0);
        // "read path=" (10) + 90 个 x ＝ 100 截断位，加 "..."。
        assertThat(line).hasSize(103);
        assertThat(line).endsWith("...");
        assertThat(line).startsWith("read path=xxxxxxxxxx");
    }

    /** expanded：每 entry `  key: value`；字符串原样；复合值 pretty JSON。 */
    @Test
    void expandedRendersKeyValueLinesWithPrettyJson() {
        var args = new LinkedHashMap<String, Object>();
        args.put("path", "a.txt");
        args.put("opts", Map.of("n", 1));

        var lines = FormatToolArgs.lines("read", args, true);
        // pi：值 JSON.stringify(v,null,2)（基础两空格）后逐续行再缩 4。
        assertThat(lines).containsExactly(
            "  path: a.txt",
            "  opts: {",
            "      \"n\": 1",
            "    }");
    }

    /** expanded 列表值。 */
    @Test
    void expandedListValue() {
        var lines = FormatToolArgs.lines("t", Map.of("items", List.of("a", "b")), true);
        assertThat(lines).containsExactly(
            "  items: [",
            "      \"a\",",
            "      \"b\"",
            "    ]");
    }

    /** 复合 value 经 {@code escapeMarkup} 由调用方处理——这里保证原始形不被二次转义。 */
    @Test
    void stringValuesPassThroughUnescaped() {
        assertThat(FormatToolArgs.lines("t", Map.of("q", "<skill>&"), false))
            .containsExactly("t q=<skill>&");
    }
}
