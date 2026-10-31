package com.pijava.evals.judge;

import com.pijava.evals.harness.HarnessRun;

/**
 * 批 32（docs/44 §4）：对一个 {@link HarnessRun} 产出的确定性判分器。
 *
 * <p>对位 pi {@code vitest-evals} 的 judge 语义：输入运行结果与用例期望，输出
 * 0..1 分与理由。所有实现都必须**确定性**（不调用模型、不联网），因为判分发生在
 * 运行之后、且同一 observation 可能被重放比较。</p>
 */
public interface Judge {

    /**
     * Score a run against the case expectation.
     *
     * @param run      the harness run to evaluate
     * @param expected case-specific expectation (shape depends on the judge)
     * @return score in {@code 0..1} plus rationale and metadata
     */
    JudgeResult evaluate(HarnessRun run, Object expected);
}
