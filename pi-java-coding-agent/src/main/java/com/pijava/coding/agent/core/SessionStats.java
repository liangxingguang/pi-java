package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * pi {@code getSessionStats}（agent-session.ts:4185-4230）对位（批 15 T11）：
 * usage 条目＋branch_summary/compaction usage＋assistant message usage 全计入；
 * toolResult message.usage 也计。数据源＝{@link AgentSession#accumulatedEntries()}。
 */
public record SessionStats(
    String sessionFile,
    String sessionId,
    long totalMessages,
    long userMessages,
    long assistantMessages,
    long toolCalls,
    long toolResults,
    UsageTokens tokens,
    double cost
) {

    /** pi {@code stats.tokens}：total＝四件之和。 */
    public record UsageTokens(double input, double output, double cacheRead,
                              double cacheWrite, double total) {}

    /** pi 逐循环对位（条目序即提交序）。 */
    public static SessionStats of(AgentSession session) {
        var totals = new Totals();
        long userMessages = 0;
        long assistantMessages = 0;
        long toolResults = 0;
        long totalMessages = 0;
        long toolCalls = 0;
        for (var entry : session.accumulatedEntries()) {
            if (entry instanceof Entry.Usage usageEntry) {
                totals.add(usageEntry.usage());
            } else if (entry instanceof Entry.BranchSummary bs && bs.usage() != null) {
                totals.add(bs.usage());
            } else if (entry instanceof Entry.Compaction c && c.usage() != null) {
                totals.add(c.usage());
            }
            // pi：bashExecution 也是 type:"message" 条目 ⇒ 计入 totalMessages。
            if (entry instanceof Entry.BashExecution) {
                totalMessages++;
                continue;
            }
            if (!(entry instanceof Entry.Message messageEntry)) {
                continue;
            }
            totalMessages++;
            var message = messageEntry.message();
            if (message instanceof Message.UserMessage) {
                userMessages++;
            } else if (message instanceof Message.ToolResultMessage toolResult) {
                toolResults++;
                if (toolResult.usage() instanceof Usage usage) {
                    totals.add(usage);
                }
            } else if (message instanceof Message.AssistantMessage assistant) {
                assistantMessages++;
                toolCalls += assistant.content().stream()
                    .filter(ContentBlock.ToolUseContent.class::isInstance).count();
                if (assistant.usage() != null) {
                    totals.add(assistant.usage());
                }
            }
        }
        return new SessionStats(session.sessionFile(), session.sessionId(),
            totalMessages, userMessages, assistantMessages, toolCalls, toolResults,
            new UsageTokens(totals.input, totals.output, totals.cacheRead,
                totals.cacheWrite,
                totals.input + totals.output + totals.cacheRead + totals.cacheWrite),
            totals.cost);
    }

    /** pi {@code createUsageTotals}/{@code addUsageToTotals}（usage-totals.ts:18-30）。 */
    static final class Totals {
        double input;
        double output;
        double cacheRead;
        double cacheWrite;
        double cost;

        void add(Usage usage) {
            input += usage.input();
            output += usage.output();
            cacheRead += usage.cacheRead();
            cacheWrite += usage.cacheWrite();
            cost += usage.cost() != null ? usage.cost().total() : 0;
        }

        long tokens() {
            return (long) (input + output + cacheRead + cacheWrite);
        }
    }
}
