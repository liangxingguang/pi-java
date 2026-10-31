package com.pijava.evals.api;

import java.nio.file.Path;
import java.util.List;

import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * A named collection of {@link EvalCase} instances.
 */
public interface EvalSuite {

    /** Human-readable suite name. */
    String name();

    /** Cases that make up this suite, in execution order. */
    List<EvalCase> cases();

    /**
     * Judges applied to each harness run (批 32，docs/44 §4)。空表 ⇒ 由用例自判。
     *
     * @return judge list, never {@code null}
     */
    default List<Judge> judges() {
        return List.of();
    }

    /**
     * Minimum score for {@link EvalResult#passed()}; {@code null} ⇒ 只记录分数、
     * 不判失败（pi README:129 语义）。
     *
     * @return threshold or {@code null}
     */
    default Double judgeThreshold() {
        return null;
    }

    /**
     * Harness-backed cases discovered for this suite (批 32，docs/44 §6）。
     * 空表 ⇒ 该 suite 只走旧 {@link EvalCase} 路径，CLI 不生成任何臂。
     *
     * @return harness case definitions, never {@code null}
     */
    default List<HarnessCase> harnessCases() {
        return List.of();
    }

    /**
     * Discovered case identities for cohort validation.
     *
     * @param variant docs variant under discovery
     * @return descriptors in discovery order
     */
    default List<EvalCaseDescriptor> discoverCases(DocumentationVariant variant) {
        return harnessCases().stream().map(HarnessCase::descriptor).toList();
    }

    /**
     * Harness options for one variant (provider/model 由 CLI 覆盖).
     *
     * @param variant          docs variant
     * @param sessionArtifactDir session snapshot destination, {@code null} ⇒ 不落盘
     * @return harness options
     */
    default HarnessOptions harnessOptions(DocumentationVariant variant,
                                          Path sessionArtifactDir) {
        return HarnessOptions.defaults();
    }
}
