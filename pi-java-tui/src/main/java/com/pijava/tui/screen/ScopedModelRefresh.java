package com.pijava.tui.screen;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.coding.agent.core.ModelCatalogRefresh;

/** Bounded catalog refresh whose late outcomes can be invalidated on close. */
public final class ScopedModelRefresh implements AutoCloseable {

    /** Completed refresh outcome delivered to the selector. */
    public record Outcome(String status, List<ModelInfo> models) {}

    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Future<ModelCatalogRefresh.Result> task;
    private Thread watcher;

    /** Use the supplied bound; production passes 15 seconds, tests a short duration. */
    public ScopedModelRefresh(Duration timeout) {
        this.timeout = timeout;
    }

    /** Start one refresh; callback is skipped after close. */
    public synchronized void start(Supplier<ModelCatalogRefresh.Result> action,
                                   Supplier<List<ModelInfo>> availableAfter,
                                   Consumer<Outcome> callback) {
        if (task != null) {
            throw new IllegalStateException("refresh already started");
        }
        task = executor.submit(action::get);
        watcher = Thread.startVirtualThread(() -> {
            Outcome outcome;
            try {
                var result = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                outcome = new Outcome(status(result), availableAfter.get());
            } catch (TimeoutException e) {
                task.cancel(true);
                outcome = new Outcome("Model refresh timed out; showing cached models.", availableAfter.get());
            } catch (java.util.concurrent.CancellationException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                var cause = e.getCause();
                outcome = new Outcome("Could not refresh model catalogs: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), availableAfter.get());
            }
            if (!closed.get()) {
                callback.accept(outcome);
            }
        });
    }

    private static String status(ModelCatalogRefresh.Result result) {
        return result.hasErrors()
            ? "Could not refresh " + String.join(", ", result.errors().keySet())
                + "; showing cached models."
            : "Model catalogs refreshed.";
    }

    /** Invalidate the callback and interrupt outstanding work. */
    @Override
    public synchronized void close() {
        closed.set(true);
        if (watcher != null) {
            watcher.interrupt();
        }
        if (task != null) {
            task.cancel(true);
        }
        executor.shutdownNow();
    }
}
