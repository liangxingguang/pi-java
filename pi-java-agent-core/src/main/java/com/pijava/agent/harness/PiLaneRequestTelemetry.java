package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.agent.hook.ResponseContext;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

/**
 * {@link PiLaneSink} 的**请求遥测族**抽离（#94，docs/10 §8）：一轮 provider 请求的
 * {@code llm.request} 跨度、token 旁路累计与原始帧旁路（{@code lane.partial} 维护）。
 * 纯移动、零行为变化 —— 方法体与 javadoc 原样来自 {@code PiLaneSink}。
 *
 * <p><b>为什么在 sink 里而不是 {@link PiLoop}</b>：这原本长在
 * {@code AssistantStreamExecutor.execute()} 里 —— 它是**执行步**的开销，不是驱动循环的。
 * 切换驱动时必须显式搬过来，否则 {@code llm.request} 跨度消失
 * （{@code PayloadRecordingStreamFnTest} 与 {@code ModelSwitchRealReproTest} 抓的就是这个）。</p>
 *
 * <p>请求钩子不在这里：pi 的 {@code prepareRequest}（{@code agent-loop.ts:219}）
 * 是<b>可改写</b>的运行时钩子，归 {@code PiLoop.Config.prepareRequest} 槽
 * （引擎接线 {@code HookSystem.firePrepareRequest}）；本类只剩跨度职责。</p>
 */
final class PiLaneRequestTelemetry {

    private final ExecutionContext ctx;
    private final String laneName;

    /** 本次助手流的用量：pi-java 的 {@link StreamEvent.UsageInfo} 是独立帧，只能旁路累计。 */
    private long inputTokens;
    private long outputTokens;

    /** 本轮 {@code llm.request} 遥测跨度，由 {@link #beginRequest} 打开、{@link #endRequest} 关闭。 */
    private TelemetrySpan llmSpan;
    private long llmStartNanos;

    PiLaneRequestTelemetry(ExecutionContext ctx, String laneName) {
        this.ctx = ctx;
        this.laneName = laneName;
    }

    long inputTokens() {
        return inputTokens;
    }

    long outputTokens() {
        return outputTokens;
    }

    /** 本次 {@code llm.request} 已经过的毫秒数。 */
    long llmDurationMs() {
        return (System.nanoTime() - llmStartNanos) / 1_000_000;
    }

    /** 一轮 provider 请求开始：打开 {@code llm.request} 跨度。 */
    void beginRequest(LaneState lane, int messageCount, int toolCount, int attempt) {
        llmStartNanos = System.nanoTime();
        var parent = lane.runSpan != null ? lane.runSpan : ctx.telemetry();
        llmSpan = parent.openSpan(new SpanOptions("llm.request", Map.of(
            "attempt", attempt,
            "model", RunSpanFactory.modelLabel(ctx.model().get()),
            "messageCount", messageCount,
            "toolCount", toolCount,
            "thinking", RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()))));
        ctx.telemetry().pushCurrent(llmSpan);
    }

    /**
     * 一轮请求结束：发 {@code after_response}、关跨度、记指标。
     *
     * <p>3c 之前这里还挂着「最后一请求停因」的旁路字段供运行后的溢出判定；
     * 现在溢出判定读的是<b>终局助手消息本身</b>（pi {@code _checkCompaction} 读
     * {@code assistantMessage} 的 stopReason/errorMessage/usage/timestamp，
     * {@code agent-session.ts:2154}），旁路字段删除。</p>
     */
    void endRequest(LaneState lane, Message.AssistantMessage assistant, long durationMs) {
        StopReason stop = assistant.stopReason();
        if (llmSpan != null) {
            llmSpan.addAttribute("inputTokens", inputTokens);
            llmSpan.addAttribute("outputTokens", outputTokens);
            if (stop != null) {
                // OTel 属性是字符串边界：显式出 wire 字面（camelCase）。
                llmSpan.addAttribute("stopReason", stop.wireName());
            }
            // C5（原 docs/23 §6）：先 pop 后 close，与 JsonlFileTelemetry.startSpan
            // 对称——close 期间的 recordEvent 无归属（可检测的缺席），而不是
            // 错挂到已闭跨度上。
            ctx.telemetry().popCurrent(llmSpan);
            llmSpan.close();
            llmSpan = null;
        }
        ctx.telemetry().incrementCounter("llm.requests", 1);
        ctx.telemetry().recordTiming("llm.request.duration", durationMs);
        if (inputTokens > 0) {
            ctx.telemetry().incrementCounter("llm.tokens.input", inputTokens);
        }
        if (outputTokens > 0) {
            ctx.telemetry().incrementCounter("llm.tokens.output", outputTokens);
        }

        var usage = new StreamEvent.UsageInfo(inputTokens, outputTokens, lane.partial);
        ctx.hookSystem().fireAfterResponse(laneName,
            new ResponseContext(laneName, lane.runId, lane.partial, usage));
    }

    /**
     * 原始帧旁路：维护 {@code lane.partial} 与 token 记账。
     *
     * <p>两点都只能在这里做：① pi-java 把用量做成独立的
     * {@link StreamEvent.UsageInfo} 帧，而它不属于生命周期事件，{@link PiLoop} 不会为它发
     * {@code message_update}；② {@code lane.partial} 必须是**流式 partial**
     * （{@code com.pijava.ai.message.AssistantMessage}），与消息记录
     * （{@link Message.AssistantMessage}）是两个类型，只有原始帧才携带前者。
     * 旧路径 {@code AssistantStreamExecutor:114-121} 读的正是同一对字段。</p>
     */
    void noteStreamEvent(LaneState lane, StreamEvent event) {
        if (event.partial() != null) {
            lane.partial = event.partial();
        }
        if (event instanceof StreamEvent.Start) {
            inputTokens = 0;
            outputTokens = 0;
        } else if (event instanceof StreamEvent.UsageInfo usage) {
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
        }
    }
}
