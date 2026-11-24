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
    void cleanStepTextKeepsFullText() {
        var longText = "x".repeat(60);
        assertThat(PlanModeUtils.cleanStepText(longText)).hasSize(60).doesNotEndWith("...");
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
        // 3 号行内代码已由 cleanStepText 去掉反引号，整行捕获后不再被过滤
        assertThat(items).hasSize(3);
        assertThat(items.get(0).step()).isEqualTo(1);
        assertThat(items.get(0).text()).isEqualTo("Configuration loader");
        assertThat(items.get(1).step()).isEqualTo(2);
        assertThat(items.get(1).text()).isEqualTo("Migration script");
        assertThat(items.get(2).text()).isEqualTo("Skip this placeholder");
        assertThat(items).allSatisfy(item -> assertThat(item.completed()).isFalse());
    }

    @Test
    void extractTodoItemsUsesLastPlanHeader() {
        var message = """
            Plan:
            1. Draft from thinking
            2. Draft second

            Final plan:

            Plan:
            1. Ship the real change
            2. Verify the clean build""";
        var items = PlanModeUtils.extractTodoItems(message);
        assertThat(items).extracting(TodoItem::text)
            .containsExactly("Ship the real change", "Clean build");
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
        // 整行捕获＋尾部星号剥离；"Write the " 仍由动作前缀表去掉
        assertThat(PlanModeUtils.extractTodoItems("Plan:\n1. **Write** the migration script"))
            .extracting(TodoItem::text).containsExactly("Migration script");
    }

    // ── extractDoneSteps / markCompletedSteps ───────────────────

    @Test
    void extractDoneStepsCollectsAllMarkerVariants() {
        assertThat(PlanModeUtils.extractDoneSteps(
            "done 1 [DONE:1], 3 [done:3] [DONE 2] Done: 4 Step 5 is done step 6 complete"))
            .containsExactly(1, 3, 2, 4, 5, 6);
        assertThat(PlanModeUtils.extractDoneSteps("no markers")).isEmpty();
    }

    @Test
    void extractDoneStepsDedupesInFirstSeenOrder() {
        assertThat(PlanModeUtils.extractDoneSteps("[DONE:2] [DONE:1] [DONE:2] Step 1 done"))
            .containsExactly(2, 1);
    }

    @Test
    void markCompletedStepsCountsOnlyNewlyMarkedItems() {
        var items = List.of(
            new TodoItem(1, "first", false),
            new TodoItem(2, "second", false));
        var count = PlanModeUtils.markCompletedSteps("[DONE:1] [DONE:9]", items);
        // pi 逐字：只计新置真的条目；未命中的 9 不计
        assertThat(count).isEqualTo(1);
        assertThat(items.get(0).completed()).isTrue();
        assertThat(items.get(1).completed()).isFalse();
        assertThat(PlanModeUtils.markCompletedSteps("[DONE:1]", items)).isZero();
    }

    @Test
    void allStepsCompleteMatchesWholePlanWording() {
        // pi 正则里 are/is 分支后不带空白：只命中 "All steps complete" 与 "Every step is done"
        assertThat(PlanModeUtils.allStepsComplete("All steps complete.")).isTrue();
        assertThat(PlanModeUtils.allStepsComplete("Every step is done")).isTrue();
        assertThat(PlanModeUtils.allStepsComplete("All steps are complete")).isFalse();
        assertThat(PlanModeUtils.allStepsComplete("The plan is complete.")).isTrue();
        assertThat(PlanModeUtils.allStepsComplete("plan completed")).isTrue();
        assertThat(PlanModeUtils.allStepsComplete("[DONE:1]")).isFalse();
        assertThat(PlanModeUtils.allStepsComplete("still working")).isFalse();
    }
}
