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
                return "Fork from a history user message";
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
        return builder.toString();
    }
}
