package com.pijava.coding.agent.core.slash.builtin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import static com.pijava.coding.agent.core.slash.builtin.CommandUtil.simple;

/**
 * Session lifecycle slash commands (Phase 3 design §14.2
 * #8/#9/#12/#13/#14/#18/#20).
 */
public final class SessionCommands {

    private SessionCommands() {}

    /** Register {@code /name /session /fork /clone /tree /new /resume}. */
    public static void registerAll(CommandRegistry registry) {
        registry.register(simple("name", "Set session display name", "<name>",
            (args, ctx) -> {
                if (args.isBlank()) {
                    return "Usage: /name <name>";
                }
                ctx.session().setSessionName(args.trim());
                return "Session renamed to \"" + args.trim() + "\"";
            }));
        registry.register(simple("session", "Show session info and stats", "",
            (args, ctx) -> sessionInfo(ctx)));
        registry.register(new SlashCommand() {
            @Override public String name() { return "fork"; }
            @Override public String description() {
                // pi slash-commands.ts:34 逐字【模型可见】（docs/21 §3.4）。
                return "Create a new fork from a previous user message";
            }
            @Override public String argumentHint() { return "[branch-name]"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                if (args.isBlank()) {
                    // docs/18（§3.8）：/fork（空参）与 /tree 拆 marker——这里仍开 fork 选择器。
                    return CompletableFuture.completedFuture(CommandRegistry.UI_FORK_SELECTOR);
                }
                var forked = ctx.session().forkCopy(args.trim());
                if (forked.isEmpty()) {
                    // docs/15：session_before_fork cancel ⇒ pi 的 {cancelled:true}。
                    return CompletableFuture.completedFuture("Fork cancelled by extension");
                }
                ctx.onSwitchSession().accept(forked.get());
                return CompletableFuture.completedFuture(
                    "Forked to \"" + args.trim() + "\"");
            }
        });
        registry.register(simple("clone", "Clone session at current position", "",
            (args, ctx) -> {
                var name = ctx.session().sessionName() + " (clone)";
                var forked = ctx.session().forkCopy(name);
                forked.ifPresent(ctx.onSwitchSession());
                return forked.map(f -> "Cloned to \"" + name + "\"")
                    .orElse("Clone cancelled by extension");
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "tree"; }
            @Override public String description() { return "Navigate session tree"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                return CompletableFuture.completedFuture(CommandRegistry.UI_TREE_SELECTOR);
            }
        });
        registry.register(simple("new", "Start a new session", "",
            (args, ctx) -> {
                // docs/15（B250）＋docs/16（B252）：替换式新建 —— 新会话 start{new,
                // previous}，旧会话真正关闭（closeForReplacement 内含 shutdown{new,
                // 新 id}；pi newSession 序列，序差在册 D20）。
                var previousId = ctx.session().sessionId();
                var fresh = AgentSession.createReplacing(ctx.session().sessionArgs(),
                    previousId);
                ctx.session().closeForReplacement("new", fresh.sessionId());
                ctx.onSwitchSession().accept(fresh);
                return "Started new session";
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "resume"; }
            @Override public String description() { return "Resume another session"; }
            @Override public String argumentHint() { return "[session-id]"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                if (args.isBlank()) {
                    return CompletableFuture.completedFuture(CommandRegistry.UI_SESSION_SELECTOR);
                }
                // docs/15（B250）：switchTo 集中 before_switch/shutdown/start 序列
                // （pi switchSession 对位）。
                var result = ctx.session().switchTo(args.trim());
                return switch (result.outcome()) {
                    case SWITCHED -> {
                        ctx.onSwitchSession().accept(result.session());
                        yield CompletableFuture.completedFuture(
                            "Resumed session \"" + result.session().sessionName() + "\"");
                    }
                    case CANCELLED -> CompletableFuture.completedFuture(
                        "Resume cancelled by extension");
                    case NOT_FOUND -> CompletableFuture.completedFuture(
                        "Session not found: " + args.trim());
                };
            }
        });
    }

    /**
     * 批 15（T11，pi {@code handleSessionCommand} interactive-mode.ts:6693-6772
     * 逐段对位；D68 销号——Cache Re-billed 从 Cache Warming 段尾搬入 Cost 段）：
     * 段序 Session Info → Messages → Tokens → Cache Warming → Cost。
     * java 无主题色面，纯文本同 pi 行形。
     */
    private static String sessionInfo(SlashContext ctx) {
        var session = ctx.session();
        var stats = com.pijava.coding.agent.core.SessionStats.of(session);
        var usageBreakdown = com.pijava.coding.agent.core.UsageCostBreakdown.of(
            session.accumulatedEntries());
        var waste = com.pijava.coding.agent.core.CacheStats.computeCacheWaste(
            session.accumulatedEntries(), key -> {
                var slash = key.indexOf('/');
                return slash < 0 ? java.util.Optional.empty()
                    : session.modelCatalog().find(com.pijava.ai.model.ModelId.of(
                        key.substring(0, slash), key.substring(slash + 1)));
            });

        var builder = new StringBuilder("Session Info\n\n");
        var name = session.sessionName();
        if (name != null && !name.isBlank()) {
            builder.append("Name: ").append(name).append('\n');
        }
        builder.append("File: ").append(
            stats.sessionFile() != null ? stats.sessionFile() : "In-memory").append('\n');
        builder.append("ID: ").append(stats.sessionId()).append("\n\n");

        builder.append("Messages\n");
        builder.append("Total: ").append(stats.totalMessages()).append('\n');
        builder.append("User: ").append(stats.userMessages()).append('\n');
        builder.append("Assistant: ").append(stats.assistantMessages()).append('\n');
        builder.append("Tools: ").append(stats.toolCalls()).append(" calls, ")
            .append(stats.toolResults()).append(" results\n\n");

        // "Input" 是全 prompt 量；有缓存活动时拆 Cached/Uncached（pi 注释原意）。
        builder.append("Tokens\n");
        double input = stats.tokens().input();
        double cacheRead = stats.tokens().cacheRead();
        double cacheWrite = stats.tokens().cacheWrite();
        double promptTokens = input + cacheRead + cacheWrite;
        builder.append("Input: ").append(thousands(promptTokens)).append('\n');
        if (promptTokens > 0 && (cacheRead > 0 || cacheWrite > 0)) {
            builder.append("  Cached: ").append(thousands(cacheRead))
                .append(String.format(java.util.Locale.US, " (%.1f%%)",
                    cacheRead / promptTokens * 100)).append('\n');
            builder.append("  Uncached: ").append(thousands(input + cacheWrite));
            if (cacheWrite > 0) {
                builder.append(" (").append(thousands(cacheWrite))
                    .append(" written to cache)");
            }
            builder.append('\n');
        }
        builder.append("Output: ").append(thousands(stats.tokens().output())).append('\n');
        builder.append("Total: ").append(thousands(stats.tokens().total())).append('\n');

        // 批 13 段（pi :6739-6746）原样。
        builder.append("\nCache Warming\n");
        builder.append("Mode: ").append(
            session.services().settings().network().getCacheWarmingMode()).append('\n');
        var status = session.cacheWarmingStatus();
        builder.append("Status: ").append(status != null
            ? com.pijava.coding.agent.core.CacheWarmingFormatting.formatStatus(status)
            : "Inactive (cache warming unavailable)").append('\n');
        if (status != null && status.decision() != null
                && status.decision().economicsAvailable()) {
            builder.append("Cache miss penalty: $")
                .append(fmt3(status.decision().missCost())).append('\n');
            builder.append("Refresh cost: $")
                .append(fmt3(status.decision().warmCost())).append('\n');
        }

        // pi :6749-6767：Cost 段（门 cost>0 || missedTokens>0）；Cache Re-billed
        // 在本段内（D68 销号——批 13 挂 Cache Warming 段尾的结构差消除）。
        if (stats.cost() > 0 || waste.missedTokens() > 0) {
            builder.append("\nCost\n");
            builder.append("Total: $").append(fmt3(stats.cost()));
            var model = session.harness().getModel();
            var selectedModelKey = (model != null ? model.provider() : "null")
                + "/" + (model != null ? model.modelName() : "null");
            // 单桶且即当前模型键 ⇒ 重复总额不列行（pi :6754-6758）。
            if (usageBreakdown.size() > 1
                    || (!usageBreakdown.isEmpty()
                        && !usageBreakdown.getFirst().key().equals(selectedModelKey))) {
                for (var bucket : usageBreakdown) {
                    builder.append("\n  ").append(bucket.key()).append(": $")
                        .append(fmt3(bucket.cost())).append(" (")
                        .append(com.pijava.coding.agent.core.CacheWarmingFormatting
                            .formatTokens(bucket.tokens()))
                        .append(" tokens)");
                }
            }
            if (waste.missedTokens() > 0) {
                var missLabel = waste.missCount() == 1
                    ? "1 miss" : waste.missCount() + " misses";
                var detail = String.format(java.util.Locale.US, "%,d", waste.missedTokens())
                    + " tokens, " + missLabel;
                if (waste.missedCost() >= 0.0001) {
                    builder.append("\nCache Re-billed: $").append(fmt3(waste.missedCost()))
                        .append(" (").append(detail).append(")");
                } else {
                    builder.append("\nCache Re-billed: ").append(detail);
                }
            }
        }
        return builder.toString();
    }

    /** pi {@code toLocaleString()}（/session token 行千分位）。 */
    private static String thousands(double value) {
        return String.format(java.util.Locale.US, "%,d", (long) value);
    }

    /** pi {@code toFixed(3)}（/session 金额行）。 */
    private static String fmt3(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
