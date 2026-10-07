package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩窗口内的用户输入缓冲（B175，docs/26；pi {@code compactionQueuedMessages}，
 * {@code interactive-mode.ts:4688-4694}）。
 *
 * <p>压缩中 TUI 提交不抛错：文本按 mode 进本缓冲，压缩结束由
 * {@link #flush} 自动重放。<b>纯逻辑、无 TUI/IO</b>；投递经 {@link Replayer}，
 * 生产实现由 {@code InteractiveMode} 给出。</p>
 *
 * <p><b>线程</b>：{@code add} 通常在 TUI 渲染线程，而 {@link #flush} 由
 * CompactionEnd 监听器触发 —— 自动压缩时事件发自 run 的虚拟线程，与渲染线程
 * 并发。pi 靠 JS 单线程免争用；本类全部方法 {@code synchronized}（对象极小）。</p>
 */
public final class CompactionInputBuffer {

    /** One buffered submission (pi {@code {text, mode}}，type :265-268). */
    public record Queued(String text, StreamingBehavior mode) {}

    private final List<Queued> queued = new ArrayList<>();

    /** Buffer one submission. */
    public synchronized void add(String text, StreamingBehavior mode) {
        queued.add(new Queued(text, mode));
    }

    /** Whether nothing is buffered. */
    public synchronized boolean isEmpty() {
        return queued.isEmpty();
    }

    /** Drop the buffer ({@code /new} / session switch). */
    public synchronized void clear() {
        queued.clear();
    }

    /** Snapshot of buffered submissions in insertion order. */
    public synchronized List<Queued> contents() {
        return List.copyOf(queued);
    }

    /** Buffered texts queued as steer, in insertion order. */
    public synchronized List<String> steeringTexts() {
        return texts(StreamingBehavior.STEER);
    }

    /** Buffered texts queued as followUp, in insertion order. */
    public synchronized List<String> followUpTexts() {
        return texts(StreamingBehavior.FOLLOW_UP);
    }

    private List<String> texts(StreamingBehavior mode) {
        var result = new ArrayList<String>();
        for (var item : queued) {
            if (item.mode() == mode) {
                result.add(item.text());
            }
        }
        return result;
    }

    /**
     * 重放缓冲 —— pi {@code flushCompactionQueue}（interactive-mode.ts:4706-4783，
     * 无扩展命令分支）。
     *
     * <p>{@code willRetry=true}（overflow 重试回合）：run 仍在跑，逐条经
     * {@link Replayer#steer}/{@link Replayer#followUp} 入引擎队列，重试 pass 消费。
     * {@code willRetry=false}：第一条经 {@link Replayer#prompt}（空闲则起新 run，
     * 仍在收尾的 run 则排队），其余按 mode 入队。</p>
     *
     * <p>任一步抛 ⇒ pi {@code restoreQueue}：先 {@code cancelQueued} 撤两个引擎队列
     * （期间入的全部撤回），缓冲全量还原（含已投递的，与 pi 同），再原样抛。</p>
     */
    public synchronized void flush(boolean willRetry, Replayer sink) {
        if (queued.isEmpty()) {
            return;
        }
        var pending = List.copyOf(queued);
        queued.clear();
        try {
            if (willRetry) {
                for (var message : pending) {
                    deliverQueued(message, sink);
                }
                return;
            }
            var first = pending.get(0);
            sink.prompt(first.text(), first.mode());
            for (int i = 1; i < pending.size(); i++) {
                deliverQueued(pending.get(i), sink);
            }
        } catch (RuntimeException e) {
            // pi restoreQueue（:4710-4719）：撤引擎两队列，缓冲全量还原后再抛。
            sink.cancelQueued("followUp");
            sink.cancelQueued("steer");
            queued.addAll(pending);
            throw e;
        }
    }

    private static void deliverQueued(Queued message, Replayer sink) {
        if (message.mode() == StreamingBehavior.FOLLOW_UP) {
            sink.followUp(message.text());
        } else {
            sink.steer(message.text());
        }
    }

    /** Delivery port for {@link #flush} (production = InteractiveMode; tests stub it). */
    public interface Replayer {

        /** Submit the first buffered message (start a run when idle, queue otherwise). */
        void prompt(String text, StreamingBehavior mode);

        /** Enqueue a steer into the active run. */
        void steer(String text);

        /** Enqueue a follow-up for after the active run. */
        void followUp(String text);

        /** Cancel everything enqueued in one engine queue ("steer"/"followUp"). */
        void cancelQueued(String queueType);
    }
}
