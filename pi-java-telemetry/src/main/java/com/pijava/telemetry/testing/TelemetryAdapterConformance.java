package com.pijava.telemetry.testing;

import com.pijava.telemetry.RecordedTelemetrySpan;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.SpanStatus;
import com.pijava.telemetry.TelemetrySpan;

import java.util.*;

/**
 * pi {@code testing/conformance.ts:60-315} 的移植：回调式遥测适配器契约的
 * runner 无关一致性用例。7 用例 / 5 组。
 *
 * <p>与 pi 9 用例的差异（docs/23 §4.2，均登记于此）：</p>
 * <ul>
 * <li>「async rejection」子场景与同步路径合并——java 回调是同步的
 * （docs/23 §7.3 结构性裁决）；</li>
 * <li>{@code Promise.reject(undefined)} 子场景不移植——java 无空异常；</li>
 * <li>「unreadable {@code SpanOptions}」子场景不移植——{@link SpanOptions} 的
 * 紧凑构造器在调用点就完成读取（{@code Map.copyOf}），炸弹载荷到不了
 * adapter，这是 TS Proxy 特有场景；</li>
 * <li>「unreadable {@code SpanStatus}」子场景不移植——sealed record 的组件
 * 访问器不会抛；其核心断言（显式 status 调用失败后自动派生仍生效）已由
 * status 组用例覆盖；</li>
 * <li>parentage 用例同步化改写：pi 用 gate promise 制造并发，此处以
 * {@code openSpan} 挂起第一个子跨度，断言的 endSequence 序不变式相同。</li>
 * </ul>
 */
public final class TelemetryAdapterConformance {

    private TelemetryAdapterConformance() { }

    /** pi {@code createTelemetryAdapterConformance}（{@code conformance.ts:61-63}）。 */
    public static List<TelemetryAdapterConformanceCase> createTelemetryAdapterConformance(
        TelemetryAdapterFixtureFactory factory
    ) {
        return List.of(
            createCase(factory, "callback lifecycle",
                "admits once synchronously and preserves the result",
                TelemetryAdapterConformance::admitsOnceSynchronously),
            createCase(factory, "callback lifecycle",
                "preserves rejection values and derives error status",
                TelemetryAdapterConformance::preservesRejectionValues),
            createCase(factory, "status",
                "uses last explicit status without automatic overwrite",
                TelemetryAdapterConformance::lastExplicitStatusWins),
            createCase(factory, "recording",
                "merges attributes and records ordered events",
                TelemetryAdapterConformance::mergesAttributesAndRecordsEvents),
            createCase(factory, "recording",
                "ignores failed attribute and event calls atomically",
                TelemetryAdapterConformance::ignoresFailedRecordingAtomically),
            createCase(factory, "recording",
                "makes calls after settlement inert",
                TelemetryAdapterConformance::callsAfterSettlementInert),
            createCase(factory, "parentage",
                "records nested child relationships and end ordering",
                TelemetryAdapterConformance::recordsParentageAndEndOrder)
        );
    }

    /** pi {@code createCase}（{@code conformance.ts:12-26}）：用例自建自关 fixture。 */
    private static TelemetryAdapterConformanceCase createCase(
        TelemetryAdapterFixtureFactory factory, String group, String name, CaseBody test
    ) {
        return new TelemetryAdapterConformanceCase(group, name, () -> {
            try (var fixture = factory.create()) {
                test.run(fixture);
            }
        });
    }

    @FunctionalInterface
    private interface CaseBody {
        void run(TelemetryAdapterFixture fixture) throws Exception;
    }

    /** pi {@code conformance.ts:65-85}。 */
    private static void admitsOnceSynchronously(TelemetryAdapterFixture fixture) throws Exception {
        boolean[] admitted = {false};
        int[] calls = {0};
        var expected = Map.of("value", 42L);
        var result = fixture.context().startSpan(new SpanOptions("success"), span -> {
            admitted[0] = true;
            calls[0]++;
            return expected;
        });

        check(admitted[0], "callback admitted synchronously");
        checkEquals(1, calls[0], "callback call count");
        check(result == expected, "startSpan preserves the result reference");
        var span = findSpan(fixture.getSpans(), "success");
        checkEquals(new SpanStatus.Ok(), span.status(), "success span status");
        check(span.settled(), "success span settled");
    }

    /** pi {@code conformance.ts:87-140} 的同步化＋可表达子场景。 */
    private static void preservesRejectionValues(TelemetryAdapterFixture fixture) throws Exception {
        var syncError = new RuntimeException("sync");
        expectThrowsSame(syncError, () ->
            fixture.context().startSpan(new SpanOptions("sync-error"), span -> {
                throw syncError;
            }));

        // getMessage() 会抛的异常：派生检查必须被动（pi memory.ts:78-87）。
        var unreadableError = new RuntimeException("unreadable") {
            @Override
            public String getMessage() {
                throw new IllegalStateException("inspect");
            }
        };
        expectThrowsSame(unreadableError, () ->
            fixture.context().startSpan(new SpanOptions("unreadable-error"), span -> {
                throw unreadableError;
            }));

        var spans = fixture.getSpans();
        // pi 只断言 status 字段为 error（conformance.ts:137），不钉派生详情——
        // 详情是参考实现的增量行为（由 InMemoryTelemetryContextTest 单钉），
        // 行式导出器允许只落 status 串。
        check(findSpan(spans, "sync-error").status() instanceof SpanStatus.Error,
            "sync-error status is error, got " + findSpan(spans, "sync-error").status());
        // unreadable error 的派生必须退化为无详情 error（pi memory.ts:83-86）。
        checkEquals(new SpanStatus.Error(null, null),
            findSpan(spans, "unreadable-error").status(), "unreadable-error status");
    }

    /** pi {@code conformance.ts:142-182}。 */
    private static void lastExplicitStatusWins(TelemetryAdapterFixture fixture) throws Exception {
        fixture.context().startSpan(new SpanOptions("last-status"), span -> {
            span.setStatus(new SpanStatus.Error("Expected", "first"));
            span.setStatus(new SpanStatus.Ok());
            return null;
        });

        var thrown = new RuntimeException("after explicit status");
        expectThrowsSame(thrown, () ->
            fixture.context().startSpan(new SpanOptions("explicit-before-throw"), span -> {
                span.setStatus(new SpanStatus.Ok());
                throw thrown;
            }));

        var rejected = new RuntimeException("after async explicit status");
        expectThrowsSame(rejected, () ->
            fixture.context().startSpan(new SpanOptions("explicit-before-rejection"), span -> {
                span.setStatus(new SpanStatus.Error("Expected", "async failure"));
                throw rejected;
            }));

        fixture.context().startSpan(new SpanOptions("expected-failure"), span -> {
            span.setStatus(new SpanStatus.Error("Expected", "returned failure"));
            return Boolean.FALSE;
        });

        var spans = fixture.getSpans();
        checkEquals(new SpanStatus.Ok(), findSpan(spans, "last-status").status(), "last-status");
        checkEquals(new SpanStatus.Ok(),
            findSpan(spans, "explicit-before-throw").status(), "explicit-before-throw");
        checkEquals(new SpanStatus.Error("Expected", "async failure"),
            findSpan(spans, "explicit-before-rejection").status(), "explicit-before-rejection");
        checkEquals(new SpanStatus.Error("Expected", "returned failure"),
            findSpan(spans, "expected-failure").status(), "expected-failure");
    }

    /** pi {@code conformance.ts:184-204}。 */
    private static void mergesAttributesAndRecordsEvents(TelemetryAdapterFixture fixture)
        throws Exception
    {
        fixture.context().startSpan(
            new SpanOptions("recording", Map.of("start", "value", "overwrite", "start")),
            span -> {
                span.setAttributes(Map.of("count", 1L, "overwrite", "middle"));
                span.setAttributes(Map.of("overwrite", "end"));
                span.addEvent("first", Map.of("index", 1L));
                span.addEvent("second", Map.of("index", 2L));
                return null;
            });

        var span = findSpan(fixture.getSpans(), "recording");
        checkEquals(Map.of("start", "value", "overwrite", "end", "count", 1L),
            span.attributes(), "merged attributes");
        checkEquals(2, span.events().size(), "event count");
        checkEquals("first", span.events().get(0).name(), "first event name");
        checkEquals(Map.of("index", 1L), span.events().get(0).attributes(), "first event attrs");
        checkEquals("second", span.events().get(1).name(), "second event name");
        checkEquals(Map.of("index", 2L), span.events().get(1).attributes(), "second event attrs");
    }

    /**
     * pi {@code conformance.ts:206-218}（属性原子忽略）＋ {@code :274-299} 的可表达
     * 半（炸弹载荷作 {@code setAttributes}/{@code addEvent} 参数被压制）。
     */
    private static void ignoresFailedRecordingAtomically(TelemetryAdapterFixture fixture)
        throws Exception
    {
        fixture.context().startSpan(
            new SpanOptions("atomic-attributes", Map.of("retained", "value")),
            span -> {
                span.setAttributes(bombAfterFirstEntry());
                span.addEvent("bomb-event", bombAfterFirstEntry());
                return null;
            });

        var span = findSpan(fixture.getSpans(), "atomic-attributes");
        checkEquals(Map.of("retained", "value"), span.attributes(),
            "failed setAttributes leaves prior attributes untouched");
        checkEquals(0, span.events().size(), "failed addEvent records nothing");
    }

    /** pi {@code conformance.ts:220-244}。 */
    private static void callsAfterSettlementInert(TelemetryAdapterFixture fixture) throws Exception {
        TelemetrySpan[] captured = new TelemetrySpan[1];
        fixture.context().startSpan(new SpanOptions("settled", Map.of("value", "initial")),
            span -> {
                captured[0] = span;
                return null;
            });
        var settledSpan = Objects.requireNonNull(captured[0], "callback span");

        settledSpan.setAttributes(Map.of("value", "late"));
        settledSpan.addEvent("late", Map.of("value", true));
        settledSpan.setStatus(new SpanStatus.Error(null, null));
        boolean[] childAdmitted = {false};
        var childResult = settledSpan.startSpan(new SpanOptions("late-child"), child -> {
            childAdmitted[0] = true;
            return 7;
        });
        check(childAdmitted[0], "late child callback still runs");
        checkEquals(7, childResult, "late child result");

        var spans = fixture.getSpans();
        checkEquals(1, spans.size(), "recorded span count");
        checkEquals(Map.of("value", "initial"), spans.getFirst().attributes(), "settled attributes");
        checkEquals(0, spans.getFirst().events().size(), "settled events");
        checkEquals(new SpanStatus.Ok(), spans.getFirst().status(), "settled status");
    }

    /**
     * pi {@code conformance.ts:246-272} 的同步化改写：{@code openSpan} 挂起
     * first-child，second-child 先完成，断言同一组 endSequence 序不变式。
     */
    private static void recordsParentageAndEndOrder(TelemetryAdapterFixture fixture)
        throws Exception
    {
        fixture.context().startSpan(new SpanOptions("parent"), parent -> {
            var firstChild = parent.openSpan(new SpanOptions("first-child"));
            var second = parent.startSpan(new SpanOptions("second-child"), child -> "done");
            checkEquals("done", second, "second child result");
            firstChild.close();
            return null;
        });

        var spans = fixture.getSpans();
        var parent = findSpan(spans, "parent");
        var first = findSpan(spans, "first-child");
        var second = findSpan(spans, "second-child");
        check(parent.parentId() == null, "parent is a root span");
        checkEquals(parent.id(), first.parentId(), "first-child parentId");
        checkEquals(parent.id(), second.parentId(), "second-child parentId");
        check(first.endSequence().isPresent() && second.endSequence().isPresent()
            && parent.endSequence().isPresent(), "all spans settled with end sequences");
        check(second.endSequence().getAsLong() < first.endSequence().getAsLong(),
            "second child ends before first child");
        check(first.endSequence().getAsLong() < parent.endSequence().getAsLong(),
            "children end before parent");
    }

    /** pi {@code findSpan}（{@code conformance.ts:28-32}）。 */
    private static RecordedTelemetrySpan findSpan(List<RecordedTelemetrySpan> spans, String name) {
        return spans.stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected recorded span " + name));
    }

    /** pi {@code rejectsWithSameValue}（{@code conformance.ts:34-41}）的同步版。 */
    private static void expectThrowsSame(Throwable expected, CaseRunnable operation) {
        try {
            operation.run();
        } catch (RuntimeException | Error actual) {
            if (actual == expected) {
                return;
            }
            throw new AssertionError("Expected identical throwable " + expected + ", got " + actual);
        }
        throw new AssertionError("Expected operation to throw " + expected);
    }

    @FunctionalInterface
    private interface CaseRunnable {
        void run();
    }

    /**
     * 一张先给出一个真条目、随后任何读取都抛的 Map——pi 用 Proxy 模拟的
     * 「部分可读载荷」（{@code conformance.ts:43-58}）的 java 等价物。
     */
    private static Map<String, Object> bombAfterFirstEntry() {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Object>> iterator() {
                        return new Iterator<>() {
                            private boolean first = true;

                            @Override
                            public boolean hasNext() {
                                return true;
                            }

                            @Override
                            public Entry<String, Object> next() {
                                if (first) {
                                    first = false;
                                    return Map.entry("partial", "must not survive");
                                }
                                throw new IllegalStateException("read");
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return 2;
                    }
                };
            }
        };
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Expected " + what);
        }
    }

    private static void checkEquals(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + what + " = " + expected + ", got " + actual);
        }
    }
}
