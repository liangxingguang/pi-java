package com.pijava.evals.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 批 32（docs/44 §5.1）：eval 任务的臂表 —— pi
 * {@code createTaskPlan}/{@code parseDiscoveredCases} 对位。
 *
 * <p>每个 case 每个 run 展开成两个 arm（两变体各一）。每个 run 交替顺序以减少
 * 顺序偏差（pi README:80）：奇数 run 先 {@code with_docs}，偶数 run 先
 * {@code without_docs}。臂 id＝{@code <evalSet>/<case>#<variant>#<run>}，
 * {@code run} 从 1 开始。</p>
 *
 * IMPORTANT: {@link #expand} 会先去重再按 eval set、case name 字典序稳定排序；
 * 发现到的用例集合必须唯一，否则配对会静默错位（pi {@code parseDiscoveredCases}
 * 的同一约束）。
 */
public record TaskPlan(List<Arm> arms) {

    /** Validates and defensively copies the arm list. */
    public TaskPlan {
        arms = List.copyOf(Objects.requireNonNull(arms, "arms"));
    }

    /**
     * One execution unit.
     *
     * @param evalCase evaluated case
     * @param variant  docs variant
     * @param run      1-based run number
     */
    public record Arm(EvalCaseDescriptor evalCase, DocumentationVariant variant, int run) {

        /** Rejects nulls and non-positive run numbers. */
        public Arm {
            Objects.requireNonNull(evalCase, "evalCase");
            Objects.requireNonNull(variant, "variant");
            if (run < 1) {
                throw new IllegalArgumentException("run must be >= 1");
            }
        }

        /**
         * Arm identifier used for pairing and artifact paths.
         *
         * @return {@code <evalSet>/<case>#<variant>#<run>}
         */
        public String id() {
            return evalCase.id() + "#" + variant.wireName() + "#" + run;
        }

        /**
         * Parse an arm identifier produced by {@link #id()}.
         *
         * @param armId arm identifier
         * @return parsed arm
         * @throws IllegalArgumentException if the identifier is malformed
         */
        public static Arm parse(String armId) {
            Objects.requireNonNull(armId, "armId");
            var parts = armId.split("#");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed arm id: " + armId);
            }
            var slash = parts[0].lastIndexOf('/');
            if (slash <= 0 || slash == parts[0].length() - 1) {
                throw new IllegalArgumentException("Malformed arm id: " + armId);
            }
            var descriptor = new EvalCaseDescriptor(parts[0].substring(0, slash),
                parts[0].substring(slash + 1));
            var variant = DocumentationVariant.fromWire(parts[1]);
            int run;
            try {
                run = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Malformed arm id: " + armId, e);
            }
            return new Arm(descriptor, variant, run);
        }

        @Override
        public String toString() {
            return id();
        }
    }

    /**
     * Expand cases into arms for every run and both variants.
     *
     * @param cases         discovered cases (duplicates tolerated, sorted by descriptor)
     * @param runsPerVariant runs per variant, must be {@code >= 1}
     * @return task plan in stable order
     * @throws IllegalArgumentException if {@code runsPerVariant < 1}
     */
    public static TaskPlan expand(Collection<EvalCaseDescriptor> cases, int runsPerVariant) {
        Objects.requireNonNull(cases, "cases");
        if (runsPerVariant < 1) {
            throw new IllegalArgumentException("runsPerVariant must be >= 1");
        }
        var sorted = cases.stream().distinct().sorted().toList();
        var arms = new ArrayList<Arm>(sorted.size() * runsPerVariant * 2);
        for (var descriptor : sorted) {
            for (int run = 1; run <= runsPerVariant; run++) {
                var first = run % 2 != 0
                    ? DocumentationVariant.WITH_DOCS : DocumentationVariant.WITHOUT_DOCS;
                var second = run % 2 != 0
                    ? DocumentationVariant.WITHOUT_DOCS : DocumentationVariant.WITH_DOCS;
                arms.add(new Arm(descriptor, first, run));
                arms.add(new Arm(descriptor, second, run));
            }
        }
        return new TaskPlan(arms);
    }

    /**
     * Arm identifiers in plan order.
     *
     * @return arm id list
     */
    public List<String> ids() {
        return arms.stream().map(Arm::id).toList();
    }

    /**
     * Arm count.
     *
     * @return number of arms
     */
    public int size() {
        return arms.size();
    }
}
