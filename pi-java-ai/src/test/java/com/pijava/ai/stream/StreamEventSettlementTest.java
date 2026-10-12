package com.pijava.ai.stream;

import java.io.IOException;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 批次（{@code 原 docs/55}）：终局载荷的**落定**契约。
 *
 * <p>{@code done.partial} / {@code err.partial} 就是 pi 的 {@code done.message} /
 * {@code error.error}（{@code ai/src/types.ts:652-668}），且**必定已落定**：
 * {@code stopReason} 不是占位值、错误路另有非空 {@code errorMessage} 文本，
 * 流到故障点为止的内容原样保留 —— pi 在车道里就地写这三样
 * （{@code anthropic-messages.ts:817-826}），本仓由 {@code settle} 这一个工厂写。</p>
 *
 * <p>⚠️ 本类**不**从 {@code FauxProvider} 的桩推契约（{@code 原 docs/55 §3.1-1}：faux 的
 * 中间帧推副本、终局推排队对象，与真车道不同形）；夹具一律走
 * {@link StreamPartialBuilder}（生产车道的唯一出口）。</p>
 */
class StreamEventSettlementTest {

    // ═══════════════════════════════════════════════════════════
    // 车道侧单点：StreamPartialBuilder.emitError
    // ═══════════════════════════════════════════════════════════

    @Test
    void builderErrorCarriesTheText() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        var err = builder.emitError(StopReason.ERROR, new IOException("kaboom"));

        assertThat(err.reason()).isEqualTo(StopReason.ERROR);
        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(err.partial().errorMessage()).isEqualTo("kaboom");
    }

    /** 对照：内容保留这一半**修复前就是绿的**（防「修坏了」）。 */
    @Test
    void builderErrorKeepsWhatStreamedBeforeTheFailure() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitTextStart();
        builder.emitTextDelta("partial text");

        var err = builder.emitError(StopReason.ERROR, new IOException("kaboom"));

        assertThat(err.partial().content())
            .containsExactly(new ContentBlock.TextContent("partial text"));
        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ERROR);
    }

    @Test
    void builderErrorWithoutACauseStillSettlesTheReason() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        var err = builder.emitError(StopReason.ABORTED, null);

        assertThat(err.reason()).isEqualTo(StopReason.ABORTED);
        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ABORTED);
        assertThat(err.partial().errorMessage()).isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // 工厂：StreamError.settle
    // ═══════════════════════════════════════════════════════════

    @Test
    void errorSettleFillsTheBarePartial() {
        var err = StreamEvent.StreamError.settle(
            StopReason.ABORTED, new IOException("Request was aborted"), AssistantMessage.empty());

        assertThat(err.reason()).isEqualTo(StopReason.ABORTED);
        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ABORTED);
        assertThat(err.partial().errorMessage()).isEqualTo("Request was aborted");
    }

    @Test
    void errorSettleDefaultsABlankReasonToError() {
        // 批 1 ⑥ 桥接期：String/StopReason 双重载下 null 首参须显式选 String 版（步 5 迁枚举）。
        var err = StreamEvent.StreamError.settle(
            (String) null, new IOException("boom"), AssistantMessage.empty());

        assertThat(err.reason()).isEqualTo(StopReason.ERROR);
        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ERROR);
    }

    /**
     * 已落定的消息是**权威**（pi 的 {@code reason} 只是 {@code output.stopReason}
     * 的冗余投影，{@code anthropic-messages.ts:815}）⇒ 两者冲突时消息胜，不覆写。
     */
    @Test
    void errorSettleLetsTheSettledPartialWin() {
        var partial = AssistantMessage.empty()
            .withStopReason(StopReason.ABORTED)
            .withErrorMessage("from the message");

        var err = StreamEvent.StreamError.settle(
            StopReason.ERROR, new IOException("from the throwable"), partial);

        assertThat(err.reason()).isEqualTo(StopReason.ABORTED);
        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ABORTED);
        assertThat(err.partial().errorMessage()).isEqualTo("from the message");
    }

    /** 取文本口径与 {@code RunFailure:90} 同：{@code getMessage()}，空则 {@code toString()}。 */
    @Test
    void errorSettleFallsBackToToStringWhenTheMessageIsAbsent() {
        var err = StreamEvent.StreamError.settle(
            StopReason.ERROR, new RuntimeException(), AssistantMessage.empty());

        assertThat(err.partial().errorMessage()).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void errorSettleWithoutACauseLeavesTheTextAbsent() {
        var err = StreamEvent.StreamError.settle(StopReason.ERROR, null, AssistantMessage.empty());

        assertThat(err.partial().stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(err.partial().errorMessage()).isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // 工厂：StreamDone.settle
    // ═══════════════════════════════════════════════════════════

    @Test
    void doneSettleSettlesTheStopReason() {
        var done = StreamEvent.StreamDone.settle(StopReason.LENGTH, AssistantMessage.empty());

        assertThat(done.reason()).isEqualTo(StopReason.LENGTH);
        assertThat(done.partial().stopReason()).isEqualTo(StopReason.LENGTH);
    }

    @Test
    void doneSettleTakesTheReasonFromTheMessageWhenAbsent() {
        // 批 1 ⑥ 桥接期：显式选 String 版（步 5 随测试枚举化删除）。
        var done = StreamEvent.StreamDone.settle(
            (String) null, AssistantMessage.empty().withStopReason(StopReason.TOOL_USE));

        assertThat(done.reason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(done.partial().stopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void doneSettleDoesNotClobberASettledMessage() {
        var done = StreamEvent.StreamDone.settle(
            StopReason.STOP, AssistantMessage.empty().withStopReason(StopReason.LENGTH));

        assertThat(done.reason()).isEqualTo(StopReason.LENGTH);
        assertThat(done.partial().stopReason()).isEqualTo(StopReason.LENGTH);
    }

    // ═══════════════════════════════════════════════════════════
    // 消费侧取文本：消息优先，Throwable 仅兜底（R3 的「6 处降到 1 处」）
    // ═══════════════════════════════════════════════════════════

    @Test
    void textPrefersTheMessageAndFallsBackToTheThrowable() {
        var settled = StreamEvent.StreamError.settle(
            StopReason.ERROR, new IOException("boom"), AssistantMessage.empty());
        assertThat(StreamEvent.StreamError.textOf(settled)).isEqualTo("boom");

        // 未经 settle 的裸事件（旧夹具、conformance 桩）⇒ 回落 Throwable。
        var bare = new StreamEvent.StreamError(
            StopReason.ERROR, new IOException("legacy"), AssistantMessage.empty());
        assertThat(StreamEvent.StreamError.textOf(bare)).isEqualTo("legacy");

        // 两者皆无 ⇒ null（调用方自己决定兜底文案）。
        var empty = new StreamEvent.StreamError(StopReason.ERROR, null, AssistantMessage.empty());
        assertThat(StreamEvent.StreamError.textOf(empty)).isNull();
    }

    @Test
    void textOfATerminalEventIsNullForNonErrors() {
        assertThat(StreamEvent.StreamError.textOf(null)).isNull();
    }
}
