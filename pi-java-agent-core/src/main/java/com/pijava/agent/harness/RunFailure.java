package com.pijava.agent.harness;

import java.time.Instant;
import java.util.List;

import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * pi {@code Agent.handleRunFailure}（{@code agent.ts:506-525}）的逐条移植：
 * 一个 pass 的驱动**抛出**时，把异常收成一条**失败助手消息**，再照常走事件链
 * （{@code message_start} → {@code message_end} → {@code turn_end} → {@code agent_end}）。
 *
 * <p><b>为什么在引擎、为什么在 {@code while} 之内</b>（{@code docs/31 §8.36.5}）：
 * pi 把它放在 {@code Agent}（= 本包），且 {@code runWithLifecycle} 包的是
 * {@code _runAgentPrompt}（**一个 pass**）而不是整个 {@code prompt()} 循环。更决定性的是
 * {@code _handlePostAgentRun:1116-1123} 读的 {@code _lastAssistantMessage} 由
 * {@code message_end} 监听器在<b>这条合成消息</b>上赋值（{@code agent-session.ts:685-691}）
 * ⇒ **pi 的失败消息会进重试判定**。调用方（{@link PiLaneEngine#drive}）因此把
 * {@link #settle} 放在重试 {@code while} 的<b>体内</b>：放循环外，合成的失败消息就进不了
 * {@code postRun.checkAfterRun}，与 pi 分家。</p>
 *
 * <p><b>失败怎么变成「退出码」</b>：宿主不看流事件，它在 {@code prompt()} 返回后读尾
 * assistant（pi {@code modes/print-mode.ts:139-155}）—— 这条消息的
 * {@code stopReason = "error"|"aborted"} 就是宿主唯一的失败信号
 * （{@code SessionRunner} 的 {@code sawTerminal} 判定）。</p>
 *
 * <p><b>两处记录在案的偏差</b>：</p>
 * <ul>
 *   <li><b>identity 三元组的来源</b>：pi 取 {@code this._state.model.api/provider/id}
 *       —— pi 的 {@code Model} 把 api 当<b>数据</b>。pi-java 的 model→api 绑定是
 *       {@code StreamFn} 在路由时**导出**的（{@code docs/31 §8.31}），车道配置里只有
 *       {@code (provider, modelName)}。⇒ 这里优先取 {@code lane.partial} 的三元组
 *       （它由 {@code AbstractChatApi} 在 api 边界挂上，正是「本车道的模型」的权威来源），
 *       取不到再退回 {@code lane.model}（此时 api 为 {@code null}）。
 *       不新造 provider→api 表 —— 那张表的权威在 {@code StreamFn} 里，复制一份必然漂移。</li>
 *   <li><b>{@code errorMessage}</b>：pi 是 {@code error instanceof Error ? error.message : String(error)}。
 *       该分支在 Java <b>不成立</b> —— {@code Throwable} 恒有 {@code getMessage()}
 *       （{@code AssertionError("boom")} 也取得到 "boom"）；两语言的 {@code Error} 只是同名不同物。
 *       Java 落法：{@code getMessage()} 为空时退回 {@code toString()}（{@code 类名: message}）。</li>
 * </ul>
 */
final class RunFailure {

    private RunFailure() {}

    /**
     * 合成失败消息并把四个事件发进 sink（pi {@code agent.ts:522-525}）。
     *
     * <p><b>⚠️ 本方法自己也可能抛</b>：四个事件要过下游 sink，而下游正是上次抛出者
     * （会话监听者）时会再抛一次 —— 此时本方法从 catch 体里冒出去、合成消息落不了盘，
     * 与 pi 同形（{@code handleRunFailure} 里 {@code await this.processEvents} 再抛就没人接了）。
     * 引擎的 {@code finally} 仍会收口车道；宿主侧的两处 {@code catch (Throwable)} 是这一层的兜底
     * （{@code docs/31 §8.36.4}）。</p>
     */
    static void settle(LaneState lane, PiLaneSink sink, Throwable thrown) {
        var failure = synthesize(lane, thrown);
        sink.emit(new PiLoop.Event.MessageStart(failure));
        sink.emit(new PiLoop.Event.MessageEnd(failure));
        sink.emit(new PiLoop.Event.TurnEnd(failure, List.of()));
        sink.emit(new PiLoop.Event.AgentEnd(List.of(failure)));
    }

    /** pi {@code agent.ts:507-521} 的消息字面量。 */
    private static Message.AssistantMessage synthesize(LaneState lane, Throwable thrown) {
        var partial = lane.partial;
        var model = lane.model;
        String api = partial == null ? null : partial.api();
        String provider = partial != null && partial.provider() != null
            ? partial.provider()
            : model == null ? null : model.provider();
        String modelName = partial != null && partial.model() != null
            ? partial.model()
            : model == null ? null : model.modelName();
        // pi 的 aborted 来自 abortController.signal.aborted；本处与 PiLoopTools.aborted(config)
        // 同一判据、同一对象。catch 在 while 内 ⇒ activeRun 尚未被 finishRun 卸下，取得到。
        var signal = lane.abortSignal();
        boolean aborted = signal != null && signal.isAborted();
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("")),
            aborted ? "aborted" : "error",
            null,
            api,
            provider,
            modelName,
            // pi 的 EMPTY_USAGE（agent.ts:39-46）没有 reasoning 分量 ⇒ Usage.of 正好对上。
            Usage.of(0, 0),
            Instant.now(),
            thrown.getMessage() != null ? thrown.getMessage() : thrown.toString(),
            null);
    }
}
