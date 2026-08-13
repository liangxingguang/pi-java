package com.pijava.coding.agent.core.slash.builtin;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Session lifecycle slash commands (Phase 3 design §14.2
 * #8/#9/#12/#13/#14/#18/#20).
 */
public final class SessionCommands {

    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME;

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
                    return CompletableFuture.completedFuture(CommandRegistry.UI_TREE_SELECTOR);
                }
                var forked = ctx.session().forkCopy(args.trim());
                ctx.onSwitchSession().accept(forked);
                return CompletableFuture.completedFuture(
                    "Forked to \"" + args.trim() + "\"");
            }
        });
        registry.register(simple("clone", "Clone session at current position", "",
            (args, ctx) -> {
                var name = ctx.session().sessionName() + " (clone)";
                var forked = ctx.session().forkCopy(name);
                ctx.onSwitchSession().accept(forked);
                return "Cloned to \"" + name + "\"";
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
                var fresh = AgentSession.create(ctx.session().sessionArgs());
                ctx.onSwitchSession().accept(fresh);
                return "Started new session (in-memory; persistence in Phase 4)";
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "resume"; }
            @Override public String description() { return "Resume another session"; }
            @Override public String argumentHint() { return "[session-id]"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                if (args.isBlank()) {
                    return CompletableFuture.completedFuture(CommandRegistry.UI_SESSION_SELECTOR);
                }
                return ctx.session().findSession(args.trim())
                    .<CompletionStage<String>>map(found -> {
                        ctx.onSwitchSession().accept(found);
                        return CompletableFuture.completedFuture(
                            "Resumed session \"" + found.sessionName() + "\"");
                    })
                    .orElseGet(() -> CompletableFuture.completedFuture(
                        "Session not found: " + args.trim()));
            }
        });
    }

    private static String sessionInfo(SlashContext ctx) {
        var session = ctx.session();
        var sessions = session.listSessions();
        var info = session.listSessions().stream()
            .filter(s -> session.latestSession().map(l -> l == session).orElse(false))
            .findFirst();
        var builder = new StringBuilder();
        builder.append("Session: ").append(session.sessionName()).append('\n');
        builder.append("Lane: ").append(session.laneName()).append('\n');
        builder.append("Entries: ").append(session.entryCount()).append('\n');
        builder.append("Sessions in process: ").append(sessions.size());
        return builder.toString();
    }

    private interface SimpleBody {
        String run(String args, SlashContext ctx);
    }

    private static SlashCommand simple(String name, String description,
                                       String hint, SimpleBody body) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public String argumentHint() { return hint; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                return CompletableFuture.completedFuture(body.run(args, ctx));
            }
        };
    }
}
