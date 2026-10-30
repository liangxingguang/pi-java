package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.LoginInteraction;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/** Text-level OAuth/API-key dialog with masked secrets and cancellable prompts. */
public final class LoginDialogScreen implements ScreenOverlay, LoginInteraction {

    private final LoginProviderOption option;
    private final Consumer<Runnable> dispatcher;
    private final Runnable onCancel;
    private final EditorComponent editor = new EditorComponent();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private CompletableFuture<String> pending;
    private List<LoginOption> selectOptions = List.of();
    private int selected;
    private boolean inputActive;
    private boolean inputMasked;
    private boolean selectionActive;
    private boolean done;
    private boolean cancelled;

    /** Create a dialog; asynchronous state changes are marshalled through dispatcher. */
    public LoginDialogScreen(LoginProviderOption option,
                             Consumer<Runnable> dispatcher, Runnable onCancel) {
        this.option = option;
        this.dispatcher = dispatcher;
        this.onCancel = onCancel;
    }

    @Override
    public void notify(String message) {
        dispatcher.accept(() -> messages.add(message));
    }

    @Override
    public String prompt(String message) {
        return requestInput(message, false);
    }

    @Override
    public String secret(String message) {
        return requestInput(message, true);
    }

    @Override
    public String select(String message, List<LoginOption> options) {
        var answer = new CompletableFuture<String>();
        dispatcher.accept(() -> {
            synchronized (this) {
                if (cancelled) {
                    answer.completeExceptionally(new CancellationException("Login cancelled"));
                    return;
                }
                messages.add(message);
                pending = answer;
                selectOptions = List.copyOf(options);
                selected = 0;
                selectionActive = true;
                inputActive = false;
            }
        });
        return await(answer);
    }

    private String requestInput(String message, boolean masked) {
        var answer = new CompletableFuture<String>();
        dispatcher.accept(() -> {
            synchronized (this) {
                if (cancelled) {
                    answer.completeExceptionally(new CancellationException("Login cancelled"));
                    return;
                }
                messages.add(message);
                editor.setText("");
                pending = answer;
                inputMasked = masked;
                inputActive = true;
                selectionActive = false;
            }
        });
        return await(answer);
    }

    private static String await(CompletableFuture<String> answer) {
        try {
            return answer.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof CancellationException cancelled) {
                throw cancelled;
            }
            throw e;
        }
    }

    @Override
    public synchronized boolean onKeyEvent(KeyEvent event) {
        if (done) {
            return true;
        }
        if (event.isCancel() || event.isCtrlC()) {
            cancelled = true;
            done = true;
            if (pending != null) {
                pending.completeExceptionally(new CancellationException("Login cancelled"));
            }
            return true;
        }
        if (selectionActive) {
            if (event.isUp()) {
                selected = Math.max(0, selected - 1);
            } else if (event.isDown()) {
                selected = Math.min(selectOptions.size() - 1, selected + 1);
            } else if (event.isConfirm() && !selectOptions.isEmpty()) {
                complete(selectOptions.get(selected).id());
            }
            return true;
        }
        if (inputActive) {
            if (event.isConfirm()) {
                complete(editor.getText());
            } else {
                editor.onKeyEvent(event);
            }
            return true;
        }
        return true;
    }

    private void complete(String value) {
        var answer = pending;
        pending = null;
        inputActive = false;
        selectionActive = false;
        if (answer != null) {
            answer.complete(value);
        }
    }

    @Override
    public synchronized boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (cancelled) {
            onCancel.run();
        }
    }

    @Override
    public synchronized Element render() {
        var rows = new ArrayList<Element>();
        rows.add(TamboUIAdapter.text(option.name()));
        for (var message : messages) {
            for (var line : message.split("\n", -1)) {
                rows.add(TamboUIAdapter.text(line));
            }
        }
        if (selectionActive) {
            for (int i = 0; i < selectOptions.size(); i++) {
                rows.add(TamboUIAdapter.text((i == selected ? "> " : "  ")
                    + selectOptions.get(i).label()));
            }
        } else if (inputActive) {
            rows.add(inputMasked ? TamboUIAdapter.text(inputDisplay()) : editor.render());
        } else {
            rows.add(TamboUIAdapter.text("Waiting for authentication..."));
        }
        return TamboUIAdapter.column(rows);
    }

    synchronized boolean awaitingInput() {
        return inputActive;
    }

    synchronized String inputDisplay() {
        return inputMasked ? "*".repeat(editor.getText().length()) : editor.getText();
    }
}
