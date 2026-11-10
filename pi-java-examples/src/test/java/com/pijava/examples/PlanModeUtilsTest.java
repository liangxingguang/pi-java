package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.pijava.examples.PlanModeUtils.TodoItem;

import org.junit.jupiter.api.Test;

/**
 * docs/51 §4：{@code plan-mode/utils.ts} 的纯函数验收——命令安全判定、步骤文本
 * 清洗（去格式/去前缀/截断）、{@code Plan:} 段编号提取、{@code [DONE:n]} 标记。
 */
class PlanModeUtilsTest {

    // ── isSafeCommand ───────────────────────────────────────────

    @Test
    void destructiveCommandsAreBlocked() {
        assertThat(PlanModeUtils.isSafeCommand("rm -rf build")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("mv a b")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("chmod 777 x")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("sudo ls")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("git commit -m x")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("git push origin main")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("npm install")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("vim notes.txt")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("kill 1234")).isFalse();
    }

    @Test
    void redirectionsAreBlockedEvenForReadOnlyCommands() {
        // pi 的两条重定向模式不带 /i：`>file`（含前导空格）与 `>>` 都算破坏性
        assertThat(PlanModeUtils.isSafeCommand("echo hi > out.txt")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("cat a >> b")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("ls | tee out")).isFalse();
        // pi 的重定向模式 `(^|[^<])>(?!>)` 无 /i 也无 `2>` 豁免：`2>&1` 同样被阻断
        assertThat(PlanModeUtils.isSafeCommand("ls -la 2>&1")).isFalse();
    }

    @Test
    void allowlistedReadOnlyCommandsPass() {
        assertThat(PlanModeUtils.isSafeCommand("ls -la")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("cat README.md")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("grep -rn TODO src")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("git status")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("git log --oneline")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("curl https://example.com")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("jq . package.json")).isTrue();
        assertThat(PlanModeUtils.isSafeCommand("  ls")).isTrue();
    }

    @Test
    void nonAllowlistedCommandsAreBlocked() {
        assertThat(PlanModeUtils.isSafeCommand("python script.py")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("./build.sh")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand("")).isFalse();
        assertThat(PlanModeUtils.isSafeCommand(null)).isFalse();
    }

    // ── cleanStepText ───────────────────────────────────────────

    @Test
    void cleanStepTextStripsFormattingAndPrefixes() {
        assertThat(PlanModeUtils.cleanStepText("**Implement** the `parser` module"))
            .isEqualTo("Implement the parser module");
        // pi 的 PREFIX 表含 Run/Update 等动词，连带其后的 "the " 一并去掉
        assertThat(PlanModeUtils.cleanStepText("Run the test suite"))
            .isEqualTo("Test suite");
        assertThat(PlanModeUtils.cleanStepText("update   the   docs"))
            .isEqualTo("Docs");
        assertThat(PlanModeUtils.cleanStepText("the docs"))
            .isEqualTo("The docs");
    }

    @Test
    void cleanStepTextTruncatesBeyondFiftyChars() {
        var longText = "x".repeat(60);
        var cleaned = PlanModeUtils.cleanStepText(longText);
        assertThat(cleaned).hasSize(50).endsWith("...");
        assertThat(PlanModeUtils.cleanStepText("Read config")).isEqualTo("Config");
    }

    // ── extractTodoItems ────────────────────────────────────────

    @Test
    void extractTodoItemsOnlyTakesNumberedItemsAfterPlanHeader() {
        var message = """
            Here is the plan:

            Plan:
            1. Read the configuration loader
            2. Write the migration script
            3. `skip` this placeholder
            4. - dash prefixed item
            5. short

            Done.""";
        var items = PlanModeUtils.extractTodoItems(message);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).step()).isEqualTo(1);
        assertThat(items.get(0).text()).isEqualTo("Configuration loader");
        assertThat(items.get(1).step()).isEqualTo(2);
        assertThat(items.get(1).text()).isEqualTo("Migration script");
        assertThat(items).allSatisfy(item -> assertThat(item.completed()).isFalse());
    }

    @Test
    void extractTodoItemsWithoutHeaderYieldsNothing() {
        var message = "1. nobody asked\n2. for this list";
        assertThat(PlanModeUtils.extractTodoItems(message)).isEmpty();
        assertThat(PlanModeUtils.extractTodoItems("")).isEmpty();
    }

    @Test
    void extractTodoItemsAcceptsBoldHeader() {
        var items = PlanModeUtils.extractTodoItems("**Plan:**\n1. Verify the output format");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).text()).isEqualTo("Output format");
        // 加粗的步骤文本在 `[^*\n]+` 处就被截断：只有 "Write" 且长度 ≤5 ⇒ 不收（pi 同）
        assertThat(PlanModeUtils.extractTodoItems("Plan:\n1. **Write** the migration script"))
            .isEmpty();
    }

    // ── extractDoneSteps / markCompletedSteps ───────────────────

    @Test
    void extractDoneStepsCollectsAllMarkers() {
        assertThat(PlanModeUtils.extractDoneSteps("done 1 [DONE:1], 3 [done:3]"))
            .containsExactly(1, 3);
        assertThat(PlanModeUtils.extractDoneSteps("no markers")).isEmpty();
    }

    @Test
    void markCompletedStepsMarksHitItemsAndReturnsAllMarkerCount() {
        var items = List.of(
            new TodoItem(1, "first", false),
            new TodoItem(2, "second", false));
        var count = PlanModeUtils.markCompletedSteps("[DONE:1] [DONE:9]", items);
        // pi 逐字：返回值是标记总数，含未命中任何条目的标记
        assertThat(count).isEqualTo(2);
        assertThat(items.get(0).completed()).isTrue();
        assertThat(items.get(1).completed()).isFalse();
    }
}
