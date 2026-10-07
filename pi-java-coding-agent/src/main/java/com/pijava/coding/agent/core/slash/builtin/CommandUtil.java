package com.pijava.coding.agent.core.slash.builtin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Shared helper for built-in slash commands: reduces the boilerplate of a
 * synchronous command body to a single lambda (Phase 3 design §14).
 */
final class CommandUtil {

    private CommandUtil() {}

    /** Command body executed synchronously. */
    interface SimpleBody {
        String run(String args, SlashContext ctx);
    }

    /** Build a command whose body returns its result text directly. */
    static SlashCommand simple(String name, String description,
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

    /** Command body executed on a worker virtual thread — dispatch returns at once. */
    interface AsyncBody {
        String run(String args, SlashContext ctx) throws Exception;
    }

    /**
     * Build a command whose body runs on a worker virtual thread and completes
     * the returned stage when done (B176, docs/27): pi command handlers are
     * async functions, so a long body (compaction) never blocks the
     * render/input thread.
     */
    static SlashCommand async(String name, String description,
                              String hint, AsyncBody body) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public String argumentHint() { return hint; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                var result = new CompletableFuture<String>();
                Thread.startVirtualThread(() -> {
                    try {
                        result.complete(body.run(args, ctx));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
                return result;
            }
        };
    }
}
