package com.pijava.evals.judge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.ToolCallRecord;

/**
 * 批 32（docs/44 §4）：工具调用判分器 —— 对位 pi {@code ToolCallJudge({expectedTools})}。
 *
 * <p>期望按序匹配 {@link HarnessRun#successfulToolCalls()}：数量、工具名、参数（JSON 深比较，
 * 严格无多余键）都一致才得 1.0；缺失、多出、顺序错误或调用了失败的工具一律 0。期望条目可以是
 * {@code "name"}、{@code Map.of("name", n, "arguments", args)} 或现成的 {@link ToolCallRecord}。</p>
 */
public final class ToolCallJudge implements Judge {

    @Override
    public JudgeResult evaluate(HarnessRun run, Object expected) {
        List<ExpectedTool> expectations;
        try {
            expectations = parseExpected(expected);
        } catch (IllegalArgumentException e) {
            return new JudgeResult(0.0, e.getMessage(), Map.of("judge", "tool-call"));
        }
        var actual = run == null ? List.<ToolCallRecord>of() : run.successfulToolCalls();
        if (expectations.size() != actual.size()) {
            return new JudgeResult(0.0,
                "expected " + expectedNames(expectations) + " but observed " + actualNames(actual),
                Map.of("expectedCount", expectations.size(), "actualCount", actual.size()));
        }
        for (int index = 0; index < expectations.size(); index++) {
            var expectation = expectations.get(index);
            var call = actual.get(index);
            if (!expectation.name().equals(call.name())) {
                return new JudgeResult(0.0, "call #" + index + " expected tool '" + expectation.name()
                    + "' but observed '" + call.name() + "'", Map.of("index", index));
            }
            if (expectation.arguments() != null) {
                var diff = StructuredOutputJudge.compareNodes("$[" + index + "].arguments",
                    StructuredOutputJudge.toTree(expectation.arguments()),
                    StructuredOutputJudge.toTree(call.arguments()),
                    false, 1e-9);
                if (diff != null) {
                    return new JudgeResult(0.0, diff.describe(), Map.of("index", index));
                }
            }
        }
        return new JudgeResult(1.0, "tool calls match", Map.of());
    }

    private static List<ExpectedTool> parseExpected(Object expected) {
        var raw = expected;
        if (raw instanceof Map<?, ?> map && map.containsKey("expectedTools")) {
            raw = map.get("expectedTools");
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("expected must be a list of tool calls");
        }
        var result = new ArrayList<ExpectedTool>(list.size());
        for (var item : list) {
            result.add(parseTool(item));
        }
        return result;
    }

    private static ExpectedTool parseTool(Object item) {
        if (item instanceof String name && !name.isBlank()) {
            return new ExpectedTool(name, null);
        }
        if (item instanceof ToolCallRecord record) {
            return new ExpectedTool(record.name(), record.arguments());
        }
        if (item instanceof Map<?, ?> map) {
            return parseMapTool(map);
        }
        throw new IllegalArgumentException("unsupported expected tool entry: " + item);
    }

    private static ExpectedTool parseMapTool(Map<?, ?> map) {
        if (!(map.get("name") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("expected tool entry is missing a string 'name'");
        }
        var rawArguments = map.get("arguments");
        if (rawArguments == null) {
            return new ExpectedTool(name, null);
        }
        if (!(rawArguments instanceof Map<?, ?> arguments)) {
            throw new IllegalArgumentException("expected tool arguments must be an object");
        }
        var copied = new LinkedHashMap<String, Object>();
        for (var entry : arguments.entrySet()) {
            copied.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return new ExpectedTool(name, copied);
    }

    private static String expectedNames(List<ExpectedTool> expectations) {
        return expectations.stream().map(ExpectedTool::name).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String actualNames(List<ToolCallRecord> calls) {
        return calls.stream().map(ToolCallRecord::name).collect(Collectors.joining(", ", "[", "]"));
    }

    private record ExpectedTool(String name, Map<String, Object> arguments) {}
}
