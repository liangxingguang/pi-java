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
}
