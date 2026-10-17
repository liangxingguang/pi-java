package com.pijava.telemetry;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * pi {@code memory.ts} 参考实现的行为钉床；逐条对应 pi
 * {@code telemetry.test.ts} 与 {@code conformance.test.ts} 中不经套件覆盖的部分。
 * 契约级行为（settled inert、显式状态、parentage、被动记录）由
 * {@code TelemetryAdapterConformance} 套件统一钉（docs/22 §4）。
 */
class InMemoryTelemetryContextTest {

    /**
     * pi {@code conformance.test.ts:23-45}：快照 detached——进行中的跨度
     * settled=false 且 endSequence 缺席；返回的快照不可变，后续读取不受
     * 篡改影响。
     */
    @Test
    void returnsDetachedSnapshotsWithoutExposingMutableState() {
        var context = new InMemoryTelemetryContext();
        boolean[] openSettled = new boolean[1];
        OptionalLong[] openEndSequence = new OptionalLong[1];
        context.startSpan(new SpanOptions("snapshot", Map.of("tags", List.of("initial"))), span -> {
            span.addEvent("event", Map.of("value", 1));
            var open = context.getSpans().get(0);
            openSettled[0] = open.settled();
            openEndSequence[0] = open.endSequence();
            return null;
        });

        assertThat(openSettled[0]).isFalse();
        assertThat(openEndSequence[0]).isEmpty();

        var first = context.getSpans().get(0);
        assertThat(first.settled()).isTrue();
        assertThat(first.endSequence()).hasValue(1);

        // java 比 pi 更强：快照不可变（pi 测试是篡改后验证内部不受影响）。
        assertThatThrownBy(() -> first.attributes().put("tags", List.of("mutated")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.events().get(0).attributes().put("value", 2))
            .isInstanceOf(UnsupportedOperationException.class);

        var second = context.getSpans().get(0);
        assertThat(second.attributes()).isEqualTo(Map.of("tags", List.of("initial")));
        assertThat(second.events()).isEqualTo(List.of(
            new RecordedTelemetryEvent("event", Map.of("value", 1))));
    }

    /** pi {@code memory.ts:195-196}：跨度 id 与 endSequence 各自自 1 自增。 */
    @Test
    void assignsSequentialIdsAndEndSequences() {
        var context = new InMemoryTelemetryContext();
        context.startSpan(new SpanOptions("first"), span -> null);
        context.startSpan(new SpanOptions("second"), span -> null);

        var spans = context.getSpans();
        assertThat(spans).extracting(RecordedTelemetrySpan::id).containsExactly(1L, 2L);
        assertThat(spans).extracting(RecordedTelemetrySpan::endSequence)
            .containsExactly(OptionalLong.of(1), OptionalLong.of(2));
        assertThat(spans).extracting(RecordedTelemetrySpan::parentId)
            .containsExactly(null, null);
    }

    /** pi {@code memory.ts:96}：显式状态赢过失败时的自动派生。 */
    @Test
    void explicitStatusWinsOverAutomaticErrorDerivation() {
        var context = new InMemoryTelemetryContext();
        var boom = new RuntimeException("after explicit status");
        assertThatThrownBy(() -> context.startSpan(new SpanOptions("explicit"), span -> {
            span.setStatus(new SpanStatus.Ok());
            throw boom;
        })).isSameAs(boom);

        assertThat(context.getSpans().get(0).status()).isEqualTo(new SpanStatus.Ok());
    }

    /** pi {@code memory.ts:78-87}：无显式状态时失败自动派生 error（带异常详情）。 */
    @Test
    void failureDerivesErrorStatusWithExceptionDetails() {
        var context = new InMemoryTelemetryContext();
        var boom = new IllegalStateException("derived");
        assertThatThrownBy(() -> context.startSpan(new SpanOptions("auto"), span -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(context.getSpans().get(0).status())
            .isEqualTo(new SpanStatus.Error("IllegalStateException", "derived"));
    }

    /** pi {@code memory.ts:126}：settled 父跨度的子跨度降级 NOOP——回调执行、无记录。 */
    @Test
    void childOfSettledSpanDowngradesToNoop() {
        var context = new InMemoryTelemetryContext();
        TelemetrySpan[] captured = new TelemetrySpan[1];
        context.startSpan(new SpanOptions("parent"), span -> {
            captured[0] = span;
            return null;
        });

        var childResult = captured[0].startSpan(new SpanOptions("late-child"), child -> 7);
        assertThat(childResult).isEqualTo(7);
        // 降级目标即 NOOP 单例跨度（pi memory.ts:126 → noop.ts:20）。
        assertThat(captured[0].openSpan(new SpanOptions("late-open")))
            .isSameAs(NoopTelemetryContext.INSTANCE.openSpan(new SpanOptions("probe")));

        assertThat(context.getSpans()).hasSize(1);
    }

    /** pi {@code memory.ts:50-52}：数组/列表值在快照间深拷。 */
    @Test
    void copiesListValuesIntoSnapshots() {
        var context = new InMemoryTelemetryContext();
        var tags = new java.util.ArrayList<>(List.of("a"));
        context.startSpan(new SpanOptions("list", Map.of("tags", tags)), span -> {
            tags.add("b"); // 录制后外部再改，不应影响已录制快照
            return null;
        });

        assertThat(context.getSpans().get(0).attributes())
            .isEqualTo(Map.of("tags", List.of("a")));
    }

    /** pi {@code memory.ts:95}：close 幂等，settle 只发生一次。 */
    @Test
    void closeIsIdempotentAndSettlesOnce() {
        var context = new InMemoryTelemetryContext();
        var span = context.openSpan(new SpanOptions("open"));
        span.close();
        span.close();

        assertThat(context.getSpans().get(0).endSequence()).hasValue(1);
    }
}
