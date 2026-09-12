package com.pijava.agent.harness.conformance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 帧序列比对（{@code docs/23c §2.4}）：先按**显式声明**的放宽规则处理两侧，再逐行严格比较。
 *
 * <p>放宽只覆盖「pi 的帧序在 Java 侧结构上无法复现」的部分。每条规则都带归类（P1/P2）与
 * 理由，并被要求**真实生效** —— {@link #changes} 会让失效的规则当场暴露，避免放宽条款
 * 长期留存却早已不适用。</p>
 */
final class ConformanceDiff {

    /** 一处差异。 */
    record Difference(int line, String expected, String actual) {
        @Override
        public String toString() {
            return "第 " + (line + 1) + " 帧\n  pi   : " + expected + "\n  java : " + actual;
        }
    }

    /**
     * 显式放宽规则。归类口径取自 {@code docs/23c §2.4}：
     * <b>P1</b> = 结构性差异，不追；<b>P2</b> = pi 侧无对应运行时，无从比较。
     */
    enum Relaxation {

        /**
         * 并行批次内 {@code tool_execution_end} 的相对次序。
         *
         * <p>pi 的并行分支先在准备循环里把**全部** {@code tool_execution_start} 按源序发出
         * （{@code agent-loop.ts:547}），随后在 {@code Promise.all} 里按各自完成序发 end
         * （{@code :550-553}）；被 {@code beforeToolCall} 拦下的调用在准备循环内**立即**收尾
         * （{@code :534-542}），因此它的 end 反而排在所有真正执行过的调用之前。pi-java 的
         * {@code PiLoop.ToolRunner} 是单相同步端口，没有「未执行 vs 已执行」的区分，完成序
         * 恒等于源序 —— 该次序在同步实现里没有对应物。</p>
         *
         * <p>「所有 start 都早于任何 end」这一**结构保证**不受影响，两侧一致，属于严格比较
         * 的范围。</p>
         */
        PARALLEL_TOOL_END_ORDER("P1");

        private final String severity;

        Relaxation(String severity) {
            this.severity = severity;
        }

        /** 归档归类（{@code P1} / {@code P2}）。 */
        String severity() {
            return severity;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** 对两侧施加同一组放宽规则（口径必须一致，否则比较失去意义）。 */
    static List<String> relax(List<String> frames, List<Relaxation> relaxations) {
        if (!relaxations.contains(Relaxation.PARALLEL_TOOL_END_ORDER)) {
            return frames;
        }
        return sortToolExecutionEndRuns(frames);
    }

    /** 该规则是否真的改变了帧序 —— 否则说明放宽条款已失效、应当删除。 */
    static boolean changes(List<String> frames, Relaxation relaxation) {
        return !relax(frames, List.of(relaxation)).equals(frames);
    }

    /**
     * 把每一段**连续**的 {@code tool_execution_end} 帧按调用编号排序。
     *
     * <p>只处理连续段：被结果消息或 {@code turn_end} 隔开的 end 不属于同一批次，不应跨段重排。</p>
     */
    private static List<String> sortToolExecutionEndRuns(List<String> frames) {
        var out = new ArrayList<>(frames);
        int runStart = -1;
        for (int i = 0; i <= out.size(); i++) {
            boolean isEnd = i < out.size() && isToolExecutionEnd(out.get(i));
            if (isEnd && runStart < 0) {
                runStart = i;
            } else if (!isEnd && runStart >= 0) {
                out.subList(runStart, i).sort(Comparator.comparing(ConformanceDiff::toolCallId));
                runStart = -1;
            }
        }
        return out;
    }

    private static boolean isToolExecutionEnd(String frame) {
        return "tool_execution_end".equals(parse(frame).path("type").asText());
    }

    private static String toolCallId(String frame) {
        return parse(frame).path("id").asText();
    }

    private static JsonNode parse(String frame) {
        try {
            return MAPPER.readTree(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("帧不是合法 JSON：" + frame, e);
        }
    }
}
