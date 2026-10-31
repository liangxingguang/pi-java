package com.pijava.evals.plan;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 批 32（docs/44 §5.1）：{@link TaskPlan} 的展开、排序与臂 id 语义。 */
class TaskPlanTest {

    @Test
    void expandsEveryCaseForBothVariantsAndRuns() {
        var plan = TaskPlan.expand(
            List.of(new EvalCaseDescriptor("set", "case-a")), 2);
        assertThat(plan.size()).isEqualTo(4);
        assertThat(plan.arms()).extracting(TaskPlan.Arm::variant)
            .containsExactly(DocumentationVariant.WITH_DOCS,
                DocumentationVariant.WITHOUT_DOCS,
                DocumentationVariant.WITHOUT_DOCS,
                DocumentationVariant.WITH_DOCS);
        assertThat(plan.arms()).extracting(TaskPlan.Arm::run)
            .containsExactly(1, 1, 2, 2);
    }

    @Test
    void deduplicatesAndSortsCases() {
        var plan = TaskPlan.expand(List.of(
            new EvalCaseDescriptor("b", "case"),
            new EvalCaseDescriptor("a", "zeta"),
            new EvalCaseDescriptor("a", "alpha"),
            new EvalCaseDescriptor("a", "alpha")), 1);
        assertThat(plan.size()).isEqualTo(6);
        assertThat(plan.arms()).extracting(arm -> arm.evalCase().id())
            .containsExactly("a/alpha", "a/alpha", "a/zeta", "a/zeta",
                "b/case", "b/case");
    }

    @Test
    void armIdsAreUniqueAndRoundTrip() {
        var plan = TaskPlan.expand(
            List.of(new EvalCaseDescriptor("set", "case")), 2);
        var ids = plan.ids();
        assertThat(ids).doesNotHaveDuplicates()
            .containsExactly("set/case#with_docs#1", "set/case#without_docs#1",
                "set/case#without_docs#2", "set/case#with_docs#2");
        for (var id : ids) {
            var arm = TaskPlan.Arm.parse(id);
            assertThat(arm.id()).isEqualTo(id);
        }
    }

    @Test
    void rejectsNonPositiveRunsPerVariant() {
        assertThatThrownBy(() -> TaskPlan.expand(
            List.of(new EvalCaseDescriptor("set", "case")), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runsPerVariant");
    }

    @Test
    void rejectsMalformedArmId() {
        assertThatThrownBy(() -> TaskPlan.Arm.parse("set/case#with_docs"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Malformed arm id");
    }
}
