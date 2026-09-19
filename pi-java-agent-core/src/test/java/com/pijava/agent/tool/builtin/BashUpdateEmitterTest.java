package com.pijava.agent.tool.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.builtin.BashTool.BashDetails;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑧（docs/35）：bash 的部分结果发射器 —— 对齐 pi {@code bash.ts:255-314}。
 *
 * <p>⚠️ **节流用可注入的时钟 + 调度器驱动，不做「等 100 ms 再看」的断言** ——
 * 本仓口径认定「靠等延迟定序」的断言是运气断言（{@code docs/31 §8.23.8 ⑥}）。
 * 这里把 pi 的三个位（{@code updateDirty} / {@code lastUpdateAt} / {@code updateTimer}）
 * 逐一对上，并用假时钟与假调度器把每条边都钉死。</p>
 */
class BashUpdateEmitterTest {

    private final List<ToolResult<BashDetails>> updates = new ArrayList<>();
    /**
     * 假时钟。⚠️ 初值取**很大的数**是刻意的：pi 的 {@code lastUpdateAt} 初值是
     * {@code 0}，在纪元毫秒下意味着「很久以前」⇒ 第一块**总是**走前沿立即发
     * （{@code bash.ts:258} + {@code :286} 的 {@code delay <= 0} 分支）。
     * 若假时钟从 0 起，「初值 0」会被算成「刚刚发过」，语义就反了。
     */
    private long nowMillis = 1_000_000;

    /** 假调度器：只记录被挂上的任务，由夹具决定何时跑。 */
    private static final class FakeScheduler implements BashUpdateEmitter.Scheduler {
        final AtomicReference<Runnable> armed = new AtomicReference<>();

        @Override
        public Runnable schedule(Runnable task, long delayMs) {
            armed.set(task);
            return () -> armed.compareAndSet(task, null);
        }
    }

    private BashUpdateEmitter emitter(FakeScheduler scheduler) {
        return new BashUpdateEmitter(updates::add, 100L, () -> nowMillis * 1_000_000L,
            scheduler);
    }

    private static String text(ToolResult<BashDetails> update) {
        if (update.content().isEmpty()) {
            return "";
        }
        return ((ContentBlock.TextContent) update.content().get(0)).text();
    }

    // ── ④ 起手那条空载荷 ────────────────────────────────────────────

    @Test
    void startEmitsAnEmptyPayloadBeforeAnyOutput() {
        // pi bash.ts:296-298：{ content: [], details: undefined } —— content 是
        // **空数组**（不是空文本块），details 是 undefined。
        var emitter = emitter(new FakeScheduler());

        emitter.start();

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).content()).as("content 是空数组").isEmpty();
        assertThat(updates.get(0).details()).as("details 为 null").isNull();
    }

    // ── ⑤ 载荷是**累积快照**，不是增量 ──────────────────────────────

    @Test
    void payloadIsTheAccumulatedSnapshotNotTheDelta() {
        // 官方文档原文 rpc.md:1055「accumulated output so far (not just the delta)」，
        // 消费者**整块替换**（interactive-mode.ts:3348-3353）。
        var emitter = emitter(new FakeScheduler());
        emitter.start();
        updates.clear();

        emitter.onOutput("first ");
        emitter.onOutput("second");

        // 第一块：距上次（初值 0）早已 ≥100ms ⇒ **前沿立即发**
        assertThat(updates).hasSize(1);
        assertThat(text(updates.get(0))).isEqualTo("first ");

        emitter.flush();
        assertThat(text(updates.get(updates.size() - 1)))
            .as("收尾的载荷是完整累积，不是最后一块增量")
            .isEqualTo("first second");
    }

    // ── ⑨ 前沿：距上次 ≥100ms 的第一块立即发 ─────────────────────────

    @Test
    void firstChunkAfterTheWindowEmitsImmediately() {
        var scheduler = new FakeScheduler();
        var emitter = emitter(scheduler);
        emitter.start();
        updates.clear();

        nowMillis += 1_000;             // 远超过 100ms 窗口
        emitter.onOutput("a");

        assertThat(updates).as("前沿语义：立即发，不等定时器").hasSize(1);
        assertThat(scheduler.armed.get()).as("没有挂定时器").isNull();
    }

    // ── ⑧ 窗口内的第二块被合并，由尾沿定时器发出 ────────────────────

    @Test
    void chunkInsideTheWindowIsCoalescedAndFlushedByTheTrailingTimer() {
        var scheduler = new FakeScheduler();
        var emitter = emitter(scheduler);
        emitter.start();
        updates.clear();

        nowMillis += 1_000;
        emitter.onOutput("a");          // 前沿：立即发
        assertThat(updates).hasSize(1);

        nowMillis += 20;                // 窗口内
        emitter.onOutput("b");
        assertThat(updates).as("窗口内不发，挂尾沿").hasSize(1);
        var trailing = scheduler.armed.get();
        assertThat(trailing).isNotNull();

        trailing.run();                 // 尾沿触发
        assertThat(updates).hasSize(2);
        assertThat(text(updates.get(1))).as("发的是合并后的累积快照").isEqualTo("ab");
    }

    @Test
    void repeatedChunksInsideTheWindowArmOnlyOneTimer() {
        var scheduler = new FakeScheduler();
        var emitter = emitter(scheduler);
        emitter.start();
        updates.clear();

        nowMillis += 1_000;
        emitter.onOutput("a");
        nowMillis += 10;
        emitter.onOutput("b");
        nowMillis += 10;
        emitter.onOutput("c");
        nowMillis += 10;
        emitter.onOutput("d");
        assertThat(updates).as("窗口内一直不发").hasSize(1);

        nowMillis += 80;
        scheduler.armed.get().run();
        assertThat(updates).hasSize(2);
        assertThat(text(updates.get(1)))
            .as("一次尾沿把所有合并的增量一并带出").isEqualTo("abcd");
    }

    // ── ⑦ 收尾冲一次，且清掉尾沿定时器（pi 的 finishOutput）──────────

    @Test
    void flushEmitsTheTailAndDisarmsTheTrailingTimer() {
        var scheduler = new FakeScheduler();
        var emitter = emitter(scheduler);
        emitter.start();
        updates.clear();

        nowMillis += 1_000;
        emitter.onOutput("a");
        nowMillis += 20;
        emitter.onOutput("b");
        var trailing = scheduler.armed.get();

        emitter.flush();
        assertThat(text(updates.get(updates.size() - 1))).isEqualTo("ab");
        assertThat(scheduler.armed.get()).as("收尾必须清掉尾沿定时器").isNull();
        assertThat(trailing).isNotNull();
    }

    @Test
    void flushWithoutPendingChangesEmitsNothing() {
        // pi 的 updateDirty 闩：没有新输出就不发（免得制造「有更新但内容没变」的帧）。
        var emitter = emitter(new FakeScheduler());
        emitter.start();

        emitter.flush();

        assertThat(updates).as("只有起手那条").hasSize(1);
    }

    // ── ⑥ 截断只在真发生时进 details ────────────────────────────────

    @Test
    void truncationAppearsInDetailsOnlyWhenItActuallyHappened() {
        var emitter = emitter(new FakeScheduler());
        emitter.start();

        emitter.onOutput("short");
        emitter.flush();
        assertThat(updates.get(updates.size() - 1).details())
            .as("未截断 ⇒ details 为 null（pi 赋 undefined ⇒ 线上省略该键）")
            .isNull();

        // 超过 pi 的 2000 行上限
        var emitter2 = emitter(new FakeScheduler());
        emitter2.start();
        var big = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            big.append("line ").append(i).append('\n');
        }
        emitter2.onOutput(big.toString());
        emitter2.flush();

        var last = updates.get(updates.size() - 1);
        assertThat(last.details()).as("真截断 ⇒ details 非 null").isNotNull();
        assertThat(last.details().truncation().truncated()).isTrue();
        assertThat(text(last)).as("载荷里带的是截断后的尾窗").contains("line 2499");
    }
}
