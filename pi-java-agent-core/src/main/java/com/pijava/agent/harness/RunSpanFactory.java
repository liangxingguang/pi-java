package com.pijava.agent.harness;

import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Telemetry-span helpers for the run lifecycle ({@code harness.run} span).
 *
 * <p>Extracted from the former step-chain executor in the agent-loop L1 cleanup
 * to keep files under the 500-line limit (docs/20 §8). Opens/closes the per-run span,
 * logs the run start/end lines, and formats model/thinking labels shared by
 * records and attributes.</p>
 */
final class RunSpanFactory {

    private static final Logger LOG = LoggerFactory.getLogger(RunSpanFactory.class);

    private final ExecutionContext ctx;

    RunSpanFactory(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /** Open the {@code harness.run} span and record the run-start counter. */
    TelemetrySpan openRunSpan(String laneName, LaneState lane, int promptChars) {
        ctx.telemetry().incrementCounter("harness.run", 1);
        var span = ctx.telemetry().openSpan(new SpanOptions("harness.run",
            java.util.Map.of("lane", laneName, "promptChars", promptChars)));
        LOG.info("[agent] run start lane={} runId={} promptChars={}",
            laneName, lane.runId, promptChars);
        return span;
    }

    /** Close the {@code harness.run} span with terminal attributes. */
    void closeRunSpan(LaneState lane, String outcome) {
        var span = lane.runSpan;
        if (span == null) {
            return;
        }
        lane.runSpan = null;
        String stopReason = lane.partial != null ? lane.partial.stopReason() : null;
        if (stopReason != null) {
            span.addAttribute("stopReason", stopReason);
        }
        span.addAttribute("outcome", outcome);
        LOG.info("[agent] run end lane={} runId={} outcome={} durationMs={}",
            lane.laneName, lane.runId, outcome,
            (System.nanoTime() - lane.runStartNanos) / 1_000_000);
        span.close();
    }

    /** Format the thinking mode as a label for attrs/records. */
    static String thinkingLabel(ModelThinkingLevel level) {
        return level instanceof ModelThinkingLevel.Enabled en
            ? en.level().label() : "off";
    }

    /** Format the model as {@code provider/name}. */
    static String modelLabel(ModelId<?> model) {
        return model.provider() + "/" + model.modelName();
    }

    /** Wall-clock milliseconds since the run started (for OperationFinished.durationMs). */
    static Long runDurationMs(LaneState lane) {
        return lane.runStartNanos == 0 ? null : (System.nanoTime() - lane.runStartNanos) / 1_000_000;
    }
}
