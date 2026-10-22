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

    private static String sessionInfo(SlashContext ctx) {
        var session = ctx.session();
        var builder = new StringBuilder();
        builder.append("Session: ").append(session.sessionName()).append('\n');
        builder.append("Lane: ").append(session.laneName()).append('\n');
        builder.append("Entries: ").append(session.entryCount()).append('\n');
        builder.append("Sessions in process: ")
            .append(session.listSessions().size());

        // 批 13（docs/24 T12，pi interactive-mode.ts:6739-6746 逐字）：Cache Warming
        // 段——无条件显示，不吃 showCacheMissNotices 门（pi 同）；java 无主题色面，
        // 纯文本同既有 4 行风格。
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
        // pi :6757-6764 的 Cache Re-billed 行（D68：java /session 无 Cost/token
        // 统计块，挂 Cache Warming 段尾——结构差随窄差登记）。
        var waste = com.pijava.coding.agent.core.CacheStats.computeCacheWaste(
            session.accumulatedEntries(), key -> {
                var slash = key.indexOf('/');
                return slash < 0 ? java.util.Optional.empty()
                    : session.modelCatalog().find(com.pijava.ai.model.ModelId.of(
                        key.substring(0, slash), key.substring(slash + 1)));
            });
        if (waste.missedTokens() > 0) {
            var missLabel = waste.missCount() == 1 ? "1 miss" : waste.missCount() + " misses";
            var detail = String.format(java.util.Locale.US, "%,d", waste.missedTokens())
                + " tokens, " + missLabel;
            if (waste.missedCost() >= 0.0001) {
                builder.append("Cache Re-billed: $").append(fmt3(waste.missedCost()))
                    .append(" (").append(detail).append(")\n");
            } else {
                builder.append("Cache Re-billed: ").append(detail).append('\n');
            }
        }
        return builder.toString();
    }

    /** pi {@code toFixed(3)}（/session 金额行）。 */
    private static String fmt3(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
