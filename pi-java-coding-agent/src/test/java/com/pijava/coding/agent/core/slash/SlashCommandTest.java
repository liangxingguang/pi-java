package com.pijava.coding.agent.core.slash;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: 22 built-in commands register, dispatch hits/misses, and the
 * quit/hotkeys flows work.
 */
class SlashCommandTest {

    @Test
    void registersAll22BuiltinCommands() {
        var registry = CommandRegistry.withBuiltins();
        var names = registry.names();

        assertThat(names).hasSize(22);
        assertThat(names).contains(
            "settings", "model", "scoped-models", "export", "import", "share",
            "copy", "name", "session", "changelog", "hotkeys", "fork", "clone",
            "tree", "trust", "login", "logout", "new", "compact", "resume",
            "reload", "quit");
    }

    @Test
    void plainMessageIsNotACommand() {
        var registry = CommandRegistry.withBuiltins();
        var context = SlashContext.of(null);

        assertThat(registry.dispatch("hello world", context)).isNull();
    }

    @Test
    void unknownCommandReturnsErrorText() {
        var registry = CommandRegistry.withBuiltins();
        var context = SlashContext.of(null);

        var result = registry.dispatch("/bogus", context).toCompletableFuture().join();
        assertThat(result).contains("Unknown command");
    }

    @Test
    void hotkeysListsBindings() {
        var registry = CommandRegistry.withBuiltins();
        var context = SlashContext.of(null);

        var result = registry.dispatch("/hotkeys", context).toCompletableFuture().join();
        assertThat(result).contains("Keybindings", "interrupt", "ESC");
    }

    @Test
    void quitInvokesCallback() {
        var registry = CommandRegistry.withBuiltins();
        var quitCalled = new AtomicBoolean();
        var context = new SlashContext(null, null,
            () -> quitCalled.set(true), ignored -> { });

        var result = registry.dispatch("/quit", context).toCompletableFuture().join();
        assertThat(result).contains("Bye");
        assertThat(quitCalled).isTrue();
    }

    @Test
    void modelOpensSelector() {
        var registry = CommandRegistry.withBuiltins();
        var result = registry.dispatch("/model", SlashContext.of(null))
            .toCompletableFuture().join();
        assertThat(result).isEqualTo(CommandRegistry.UI_MODEL_SELECTOR);
    }

    @Test
    void nameChangesSessionDisplayName() {
        var registry = CommandRegistry.withBuiltins();
        // Use a session stub: AgentSession cannot be null here, so we build the
        // context with a real session created with minimal args.
        var session = AgentSession.create(
            com.pijava.coding.agent.cli.ArgsParser.parse(new String[] {}));
        var context = SlashContext.of(session);
        CompletionStage<String> stage = registry.dispatch("/name demo", context);
        var result = stage.toCompletableFuture().join();

        assertThat(result).contains("demo");
        assertThat(session.sessionName()).isEqualTo("demo");
        session.close();
    }
}
