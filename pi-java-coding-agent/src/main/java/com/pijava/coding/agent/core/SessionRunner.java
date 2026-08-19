package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;

/**
 * Drives one harness run on a virtual thread and persists the produced
 * transcript/records into the session (Phase 4 §13.1).
 */
final class SessionRunner {

    private SessionRunner() {}

    static void drive(
            AgentSession owner,
            String prompt,
            LinkedBlockingQueue<StreamEvent> queue,
            CompletableFuture<List<Entry>> entriesFuture,
            CompletableFuture<RunStatus> statusFuture,
            StreamObserver streamObserver,
            EntryObserver entryObserver) {
        var laneName = owner.laneName();
        var stopReason = new AtomicReference<>("completed");
        try (var registration = owner.harness().onStreamEvent(event -> {
            if (event instanceof StreamEvent.StreamDone done && done.reason() != null) {
                stopReason.set(done.reason());
            }
            if (event instanceof StreamEvent.StreamError) {
                stopReason.set("error");
            }
            owner.emitSessionEvent(new AgentSessionEvent.MessageUpdate(event));
            if (streamObserver == null) {
                queue.add(event);
            } else {
                streamObserver.onStreamEvent(event);
            }
        })) {
            Action action = owner.harness().run(laneName, prompt);
            while (action != null) {
                action = owner.harness().executeAction(laneName, action);
            }
            var lane = owner.harness().snapshot(laneName);
            var transcript = List.copyOf(lane.transcript());
            entriesFuture.complete(transcript);
            if (entryObserver != null) {
                for (var entry : transcript) {
                    entryObserver.onEntry(entry);
                }
            }
            for (var entry : transcript) {
                owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
            }
            if (owner.session() != null) {
                SessionPersistence.persistPending(owner, owner.session(), laneName);
            }
            owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(
                transcript.stream()
                    .filter(Entry.Message.class::isInstance)
                    .map(e -> ((Entry.Message) e).message())
                    .toList(), false));
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled());
            statusFuture.complete(new RunStatus(
                exitCode(stopReason.get()), stopReason.get()));
        } catch (Exception e) {
            var error = new StreamEvent.StreamError(
                "error", e, AssistantMessage.empty());
            if (streamObserver != null) {
                streamObserver.onStreamEvent(error);
            } else {
                queue.add(error);
            }
            owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled());
            statusFuture.complete(new RunStatus(1, "error"));
            entriesFuture.complete(List.of());
        } finally {
            if (streamObserver == null) {
                queue.add(null);
            }
        }
    }

    private static int exitCode(String stopReason) {
        return switch (stopReason) {
            case "error" -> 1;
            case "aborted" -> 130;
            default -> 0;
        };
    }
}
