package com.pijava.tui.screen;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.SessionShareService;
import com.pijava.coding.agent.core.ShareCancellation;
import com.pijava.coding.agent.core.ShareOutcome;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/** Runs session sharing on a virtual thread with a cancellable loader. */
public final class ShareFlow {

    /** Host effects for loader and final status. */
    public interface Host {
        void open(ScreenOverlay overlay);
        void dismiss(ScreenOverlay overlay);
        void status(String message);
        void error(String message);
    }

    @FunctionalInterface
    interface ShareExecutor {
        ShareOutcome share(ShareCancellation cancellation,
                           Consumer<SessionShareService.Stage> stages) throws Exception;
    }

    /** Cancellable text loader shared by both upload paths. */
    static final class Loader implements ScreenOverlay {
        private volatile String message;
        private final Runnable onAbort;
        private volatile boolean aborted;
        private volatile boolean done;

        private Loader(String message, Runnable onAbort) {
            this.message = message;
            this.onAbort = onAbort;
        }

        String message() {
            return message;
        }

        void message(String value) {
            message = value;
        }

        boolean aborted() {
            return aborted;
        }

        @Override
        public boolean onKeyEvent(KeyEvent event) {
            if (event.isCancel() && !done) {
                aborted = true;
                done = true;
                onAbort.run();
            }
            return true;
        }

        @Override public boolean isDone() { return done; }
        @Override public void apply(AgentSession session, Consumer<AgentSession> switcher) {}

        @Override
        public Element render() {
            return TamboUIAdapter.column(TamboUIAdapter.text(message),
                TamboUIAdapter.text("(esc to cancel)"));
        }
    }

    private final ShareExecutor executor;
    private final Host host;
    private final Consumer<Runnable> dispatcher;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread worker;
    private volatile Loader loader;

    /** Production flow using the real session-share service. */
    public ShareFlow(SessionShareService service, Host host, Consumer<Runnable> dispatcher) {
        this((cancellation, stages) -> service.share(cancellation, stages), host, dispatcher);
    }

    ShareFlow(ShareExecutor executor, Host host, Consumer<Runnable> dispatcher) {
        this.executor = executor;
        this.host = host;
        this.dispatcher = dispatcher;
    }

    /** Start one sharing attempt. */
    public void begin() {
        loader = new Loader("Preparing share...", this::cancel);
        host.open(loader);
        ShareCancellation cancellation = cancelled::get;
        worker = Thread.startVirtualThread(() -> {
            ShareOutcome outcome;
            try {
                outcome = executor.share(cancellation, stage -> dispatcher.accept(() -> {
                    if (!cancelled.get()) {
                        loader.message(stage == SessionShareService.Stage.RADIUS_UPLOAD
                            ? "Uploading to Radius..." : "Creating gist...");
                    }
                }));
            } catch (Exception e) {
                outcome = new ShareOutcome(ShareOutcome.Kind.ERROR,
                    e.getMessage() == null ? e.toString() : e.getMessage());
            }
            var finished = outcome;
            dispatcher.accept(() -> finish(finished));
        });
    }

    private void cancel() {
        cancelled.set(true);
        var running = worker;
        if (running != null) {
            running.interrupt();
        }
    }

    private void finish(ShareOutcome outcome) {
        var current = loader;
        if (current != null) {
            host.dismiss(current);
        }
        if (cancelled.get() || current.aborted() || outcome.kind() == ShareOutcome.Kind.CANCELLED) {
            host.status("Share cancelled");
        } else if (outcome.kind() == ShareOutcome.Kind.SUCCESS) {
            host.status(outcome.message());
        } else {
            host.error(outcome.message());
        }
    }
}
