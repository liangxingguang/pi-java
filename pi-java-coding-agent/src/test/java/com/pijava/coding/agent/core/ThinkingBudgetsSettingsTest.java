package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B128（docs/13 §3.1，pi {@code settings-schema.ts:121-133} ＋
 * {@code settings-manager.ts:1166-1168}）：{@code settings.thinkingBudgets}
 * 四段（minimal/low/medium/high，全 optional 无默认、无范围校验）→
 * {@code SettingsAccessors.getThinkingBudgets()} 转 ai 层
 * {@link com.pijava.ai.thinking.ThinkingBudgets}；缺席 ⇒ {@code null}
 * （≙ pi 的 {@code undefined}，ai 侧按默认表浅合并）。
 */
class ThinkingBudgetsSettingsTest {

    private static FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    /** 全缺席 ⇒ null（不是四档全缺的空表 —— pi 返回 undefined）。 */
    @Test
    void absentBlockResolvesToNull(@TempDir Path dir) {
        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getThinkingBudgets()).isNull();
    }

    /** 四段直取；只给部分段 ⇒ 其余段 empty（浅合并在 ai 侧 budgetFor 做）。 */
    @Test
    void partialBlockMapsPresentLevelsAndEmptiesTheRest(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            "{\"thinkingBudgets\":{\"minimal\":512,\"high\":32768}}");

        var budgets = SettingsManager.withStorage(storage(dir)).accessors()
            .getThinkingBudgets();

        assertThat(budgets).isNotNull();
        assertThat(budgets.minimal()).isEqualTo(OptionalInt.of(512));
        assertThat(budgets.low()).isEmpty();
        assertThat(budgets.medium()).isEmpty();
        assertThat(budgets.high()).isEqualTo(OptionalInt.of(32768));
    }

    /** 四段齐全。 */
    @Test
    void allFourLevelsMap(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            "{\"thinkingBudgets\":{"
                + "\"minimal\":1024,\"low\":2048,\"medium\":8192,\"high\":16384}}");

        var budgets = SettingsManager.withStorage(storage(dir)).accessors()
            .getThinkingBudgets();

        assertThat(budgets.minimal()).isEqualTo(OptionalInt.of(1024));
        assertThat(budgets.low()).isEqualTo(OptionalInt.of(2048));
        assertThat(budgets.medium()).isEqualTo(OptionalInt.of(8192));
        assertThat(budgets.high()).isEqualTo(OptionalInt.of(16384));
    }

    /** pi schema 只验 number 不验范围 ⇒ 负数也原样解析上线（服务端拒就拒）。 */
    @Test
    void negativeValuesPassThroughUnvalidated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            "{\"thinkingBudgets\":{\"medium\":-1}}");

        var budgets = SettingsManager.withStorage(storage(dir)).accessors()
            .getThinkingBudgets();

        assertThat(budgets.medium()).isEqualTo(OptionalInt.of(-1));
    }
}
