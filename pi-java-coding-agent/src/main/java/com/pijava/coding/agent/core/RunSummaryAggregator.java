package com.pijava.coding.agent.core;

import java.io.PrintStream;
import java.util.List;
import java.util.Set;

import com.pijava.agent.record.LaneRecord;

/**
 * Aggregates one {@code processPrompt} run's observable outcome into a
 * printable summary (observability design §8). Consumes the lane records
 * written by the harness plus usage events observed during the drive loop;
 * knows nothing about the harness, telemetry or output channels.
 *
 * <p>A single {@code processPrompt} may span several harness attempts (auto
 * retry, pi {@code continueRun} re-rolls the run id), so every counter filters
 * by the set of runIds the drive collected — otherwise a retry would double
 * count its tool/step records against the previous attempt's {@code runId}.</p>
 */
public final class RunSummaryAggregator {

    private RunSummaryAggregator() {}

    /** Token/cost totals accumulated across the drive's {@code UsageInfo} events. */
    public record Totals(long inputTokens, long outputTokens, double costUsd) {}

    /** The printable outcome of one drive. */
    public record Summary(
        int attempts,
        long durationMs,
        String stopReason,
        Totals totals,
        int toolOk,
        int toolFailed,
        int steps
    ) {

        /** Copy with totals (usage accumulated in the drive loop). */
        public Summary withTotals(Totals totals) {
            return new Summary(attempts, durationMs, stopReason,
                totals, toolOk, toolFailed, steps);
        }

        /** Copy with run-level metadata (attempts, wall clock, stop reason). */
        public Summary withMeta(int attempts, long durationMs, String stopReason) {
            return new Summary(attempts, durationMs, stopReason,
                totals, toolOk, toolFailed, steps);
        }

        /**
         * Print the summary to the given stream.  One line per aspect, aligned
         * with design §8.2; cost is currency-formatted with two decimals, token
         * counts thousands-grouped.
         */
        public void printTo(PrintStream out) {
            out.printf("[pi-java] run summary: attempts=%d durationMs=%dms stopReason=%s%n",
                attempts, durationMs, stopReason);
            out.printf("  tokens: in=%,d out=%,d cost=$%.2f%n",
                totals.inputTokens(), totals.outputTokens(), totals.costUsd());
            out.printf("  tools: %d ok, %d failed | steps: %d%n",
                toolOk, toolFailed, steps);
        }
    }

    /**
     * Aggregate the tool/step counters from lane records for the given run ids.
     * Pure and side-effect free so it can be unit tested without a session.
     *
     * @param records the lane records snapshot ({@code LaneSnapshot.records()})
     * @param runIds  the run ids belonging to this drive
     * @return tool/step counters (zeroed when no records match)
     */
    public static Summary aggregate(List<LaneRecord> records, Set<String> runIds) {
        int toolOk = 0;
        int toolFailed = 0;
        int steps = 0;
        for (var record : records) {
            switch (record) {
                case LaneRecord.ToolFinished t -> {
                    if (!runIds.contains(t.runId())) {
                        break;
                    }
                    if (t.isError()) {
                        toolFailed++;
                    } else {
                        toolOk++;
                    }
                }
                case LaneRecord.StepAttempt s -> {
                    if (runIds.contains(s.runId())) {
                        steps++;
                    }
                }
                default -> { }
            }
        }
        return new Summary(0, 0, null,
            new Totals(0, 0, 0), toolOk, toolFailed, steps);
    }
}
