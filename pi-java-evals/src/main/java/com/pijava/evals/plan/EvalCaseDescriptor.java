package com.pijava.evals.plan;

import java.util.Objects;

/**
 * 批 32（docs/44 §5.1）：eval set 内的一个用例标识 —— pi
 * {@code <eval set> > <case>} 的扁平平铺。
 *
 * <p>自然序先按 {@code evalSet} 再按 {@code caseName} 字典序，供
 * {@link TaskPlan} 稳定排序。</p>
 *
 * @param evalSet  eval set name
 * @param caseName case name
 */
public record EvalCaseDescriptor(String evalSet, String caseName)
    implements Comparable<EvalCaseDescriptor> {

    /** Rejects null/blank names. */
    public EvalCaseDescriptor {
        evalSet = requireName(evalSet, "evalSet");
        caseName = requireName(caseName, "caseName");
    }

    /**
     * Slash-joined identifier used as the arm-id prefix.
     *
     * @return {@code <evalSet>/<caseName>}
     */
    public String id() {
        return evalSet + "/" + caseName;
    }

    /**
     * Order by eval set, then case name.
     *
     * @param other other descriptor
     * @return comparison result
     */
    @Override
    public int compareTo(EvalCaseDescriptor other) {
        var bySet = evalSet.compareTo(other.evalSet);
        return bySet != 0 ? bySet : caseName.compareTo(other.caseName);
    }

    private static String requireName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
