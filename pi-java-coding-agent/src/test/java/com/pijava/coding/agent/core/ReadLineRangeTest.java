package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W8（docs/25 §9；pi {@code export-html/template.js:908-923}）：read 行区间
 * 显示——数字字符串可解析、非法值不产畸形区间、只有 limit 时从 1 起。
 */
class ReadLineRangeTest {

    @Test
    void absentArgumentsProduceEmptyString() {
        assertThat(ReadLineRange.format(null, null)).isEmpty();
    }

    @Test
    void numericStringsAreParsed() {
        assertThat(ReadLineRange.format("5", "10")).isEqualTo(":5-14");
        assertThat(ReadLineRange.format(5, 10)).isEqualTo(":5-14");
        assertThat(ReadLineRange.format(" 5 ", " 10 ")).isEqualTo(":5-14");
    }

    @Test
    void offsetOnlyGivesStart() {
        assertThat(ReadLineRange.format(5, null)).isEqualTo(":5");
    }

    @Test
    void limitOnlyStartsAtOne() {
        assertThat(ReadLineRange.format(null, 10)).isEqualTo(":1-10");
    }

    @Test
    void invalidValuesProduceNoRange() {
        assertThat(ReadLineRange.format("abc", 10)).isEmpty();
        assertThat(ReadLineRange.format(5, "abc")).isEmpty();
        assertThat(ReadLineRange.format("", 10)).isEmpty();
        assertThat(ReadLineRange.format(Double.NaN, 10)).isEmpty();
        assertThat(ReadLineRange.format(java.util.List.of(1), 10)).isEmpty();
    }

    @Test
    void fractionalNumbersKeepTheirFraction() {
        assertThat(ReadLineRange.format(2.5, 3)).isEqualTo(":2.5-4.5");
    }
}
