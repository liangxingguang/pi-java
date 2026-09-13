package com.pijava.agent.harness.conformance;

import java.util.ArrayList;
import java.util.List;

/**
 * 帧序列比对（{@code docs/23c §2.4}）：**严格**逐行比较，当前没有任何放宽条款。
 *
 * <p>历史上只存在过一条放宽规则 {@code PARALLEL_TOOL_END_ORDER}（P1）：pi 的并行分支在
 * 准备循环内就给准备相失败的调用收尾（{@code agent-loop.ts:506-517}），已准备好的调用
 * 到 {@code Promise.all} 才各自收尾，而被拒绝调用的 end 因此排在所有真正执行过的调用
 * 之前；Java 侧的 {@code PiLoop.ToolRunner} 曾是单相同步端口，没有「未执行 vs 已执行」
 * 的区分，end 恒按源序 —— 该次序无法复现，只能豁免。端口拆成 {@code prepare} /
 * {@code execute} 两相后豁免不复存在，S4 自该步起走严格比对。</p>
 *
 * <p><b>若将来要新增放宽规则</b>：放回这里，带归类（P1 = 结构性差异不追，P2 = pi 侧无
 * 对应运行时）、理由，并在 {@code ConformanceTest} 里恢复「规则必须真实生效」的守卫
 * （见 {@code 9a51b94} 之前的版本）—— 否则放宽条款会烂成没人敢删的豁免。</p>
 */
final class ConformanceDiff {

    /** 一处差异。 */
    record Difference(int line, String expected, String actual) {
        @Override
        public String toString() {
            return "第 " + (line + 1) + " 帧\n  pi   : " + expected + "\n  java : " + actual;
        }
    }

    private ConformanceDiff() {}

    /** 逐行严格比较；行数不同时多出来的行也各记一条差异。 */
    static List<Difference> compare(List<String> expected, List<String> actual) {
        var differences = new ArrayList<Difference>();
        int lines = Math.max(expected.size(), actual.size());
        for (int i = 0; i < lines; i++) {
            var want = i < expected.size() ? expected.get(i) : "<缺失>";
            var got = i < actual.size() ? actual.get(i) : "<缺失>";
            if (!want.equals(got)) {
                differences.add(new Difference(i, want, got));
            }
        }
        return List.copyOf(differences);
    }
}
