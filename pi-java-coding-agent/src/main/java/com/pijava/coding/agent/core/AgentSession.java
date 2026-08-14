package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.harness.Action;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.DriveMode;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.agent.harness.LaneConfig;
import com.pijava.agent.harness.QueueMode;
import com.pijava.agent.harness.ToolExecution;
import com.pijava.agent.harness.WatchHandle;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolSetFactory;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.session.SessionInfo;
import com.pijava.coding.agent.core.slash.CommandRegistry;

/**
 * Session orchestration: wraps an {@link AgentHarness} with settings, trust,
 * providers and slash commands, and exposes prompt execution with live
 * streaming (Phase 3 design §9.5/§11.5).
 */
public final class AgentSession implements AutoCloseable {

    /** Default system prompt when no CLI/system prompt is configured. */
    public static final String DEFAULT_SYSTEM_PROMPT =
        "You are pi-java, an AI coding assistant. Help the user write, read, "
            + "and understand code. Use the provided tools when useful.";

    private final AgentHarness harness;
    private final SessionServices services;
    private String name;
    private final Instant createdAt = Instant.now();
    private final Args args;
    private String laneName = AgentHarness.DEFAULT_LANE;
    private InMemorySessionRepository repository = InMemorySessionRepository.shared();

    private AgentSession(AgentHarness harness, SessionServices services,
                         Args args, String name) {
        this.harness = harness;
        this.services = services;
        this.args = args;
        this.name = name;
    }

    /**
     * Assemble a session from CLI arguments: settings → services → harness
     * (Phase 3 design §9.5). The new session is registered in the process-local
     * repository so {@code -c/-r} and {@code /session} can find it.
     */
    public static AgentSession create(Args args) {
        return create(args, InMemorySessionRepository.shared());
    }

    /**
     * Assemble a session against a specific repository. Tests pass a fresh
     * repository to isolate process-wide state; production uses the shared one.
     */
    static AgentSession create(Args args, InMemorySessionRepository repository) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        var providers = DefaultProviders.defaultProviders();
        var models = new DefaultModelResolver(BuiltinCatalog.all());
        var tools = new ToolRegistry(null);
        var toolList = ToolSetFactory.createCodingTools("");
        tools.registerAll(toolList);

        var services = new SessionServices(
            settings,
            new TrustManager(effective.defaultProjectTrust),
            providers,
            models,
            tools,
            CommandRegistry.withBuiltins());

        var providerName = DefaultProviders.resolveProviderName(
            args, effective.defaultProvider);
        var modelPattern = args.model() != null
            ? args.model() : effective.defaultModel;
        if (modelPattern == null || modelPattern.isBlank()) {
            // No explicit model: fall back to the provider's first model.
            modelPattern = providerName;
        }
        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(DefaultProviders.streamFnFor(
                args, effective.defaultProvider, providers))
            .model(models.resolve(modelPattern, providerName))
            .thinkingLevel(thinkingLevelFor(args))
            .systemPrompt(systemPromptFor(args))
            .activeTools(activeTools(args, toolList))
            .toolRegistry(tools)
            .toolContext(new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(),
                new DefaultFileSystem()))
            .driveMode(new DriveMode.Manual())
            .steeringMode(queueMode(effective.steeringMode))
            .followUpMode(queueMode(effective.followUpMode))
            .toolExecution(ToolExecution.defaultMode())
            .build());

        var session = new AgentSession(
            harness, services, args,
            args.name() != null ? args.name() : "session");
        session.repository = repository;
        return resolveSession(session, args);
    }

    /** The underlying harness (used by the session repository and TUI). */
    public AgentHarness harness() {
        return harness;
    }

    /** The assembled services. */
    public SessionServices services() {
        return services;
    }

    /** Session display name. */
    public String sessionName() {
        return name;
    }

    /** Rename the session ({@code /name}). */
    public void setSessionName(String newName) {
        this.name = newName;
    }

    /** The CLI arguments this session was assembled from ({@code /new}). */
    public Args sessionArgs() {
        return args;
    }

    /** The active lane name. */
    public String laneName() {
        return laneName;
    }

    /** Approximate transcript size for session listings. */
    public long entryCount() {
        return harness.snapshot(laneName).transcript().size();
    }

    /**
     * Run a prompt and return a live result: incremental stream events feed
     * {@link SessionResult#stream()} as the harness drives on a virtual thread;
     * entries and status complete when the run finishes. One virtual thread is
     * started per run (Phase 3 design §11.1).
     */
    public SessionResult processPrompt(String prompt, PromptConfig config) {
        return processPrompt(prompt, config, null, null);
    }

    /**
     * Run a prompt and forward events/entries to observers on the drive
     * thread. Used by {@code InteractiveMode} so a run uses exactly one
     * virtual thread; {@link SessionResult#stream()} is still available for
     * consumers without observers (e.g. print mode).
     */
    public SessionResult processPrompt(
            String prompt,
            PromptConfig config,
            StreamObserver streamObserver,
            EntryObserver entryObserver) {
        if (config.systemPrompt() != null) {
            harness.setSystemPrompt(config.systemPrompt());
        }
        if (config.thinkingLevel() != null) {
            harness.setThinkingLevel(config.thinkingLevel());
        }

        var queue = new LinkedBlockingQueue<StreamEvent>();
        var entriesFuture = new CompletableFuture<List<Entry>>();
        var statusFuture = new CompletableFuture<RunStatus>();

        Thread.startVirtualThread(() -> driveRun(
            prompt, queue, entriesFuture, statusFuture,
            streamObserver, entryObserver));

        Stream<StreamEvent> stream = Stream.generate(() -> {
            try {
                return queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }).takeWhile(Objects::nonNull);
        return new SessionResult(stream, entriesFuture, statusFuture);
    }

    /** Convenience overload with default prompt config. */
    public SessionResult processPrompt(String prompt) {
        return processPrompt(prompt, PromptConfig.defaults());
    }

    /** Abort the current run (cross-thread safe via the harness AbortSignal). */
    public void abort() {
        harness.abort(laneName);
    }

    /** Queue a follow-up message (processed when the current run finishes). */
    public String followUp(String prompt) {
        return harness.followUp(laneName, prompt);
    }

    /** Trigger a manual context compaction ({@code /compact}). */
    public void compact(CompactionSettings settings) {
        harness.compact(laneName, settings);
    }

    /** Queue a steering message (injected into the current run's next round). */
    public String steer(String prompt) {
        return harness.steer(laneName, prompt);
    }

    /** The most recent assistant text (for {@code /copy}). */
    public String lastAssistantText() {
        var transcript = harness.snapshot(laneName).transcript();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            var entry = transcript.get(i);
            if (entry instanceof Entry.Message message
                    && "assistant".equals(message.role())) {
                var builder = new StringBuilder();
                for (var block : message.blocks()) {
                    if (block instanceof ContentBlock.TextContent text) {
                        builder.append(text.text());
                    }
                }
                if (!builder.isEmpty()) {
                    return builder.toString();
                }
            }
        }
        return null;
    }

    /** Subscribe to live session snapshots (status bar, tree selector). */
    public WatchHandle<com.pijava.agent.harness.SessionSnapshot> watchSession() {
        return harness.watchSession();
    }

    /** List process-local sessions for {@code /session} and the resume picker. */
    public List<SessionInfo> listSessions() {
        return repository.list();
    }

    /** The most recent session, or empty ({@code -c}). */
    public java.util.Optional<AgentSession> latestSession() {
        return repository.latest();
    }

    /** Find a session by ID/prefix ({@code -r/--session}). */
    public java.util.Optional<AgentSession> findSession(String idOrPrefix) {
        return repository.find(idOrPrefix);
    }

    /** Create a forked copy on a new lane ({@code /fork /clone --fork}). */
    public AgentSession forkCopy(String branchName) {
        harness.createLane(LaneConfig.of(branchName));
        var forked = new AgentSession(harness, services, args, branchName);
        forked.laneName = branchName;
        forked.repository = repository;
        repository.create(forked);
        return forked;
    }

    /** Flush settings and close the harness. */
    @Override
    public void close() {
        services.settings().flush();
        harness.close();
    }

    // ── Internals ────────────────────────────────────────────

    private void driveRun(
            String prompt,
            LinkedBlockingQueue<StreamEvent> queue,
            CompletableFuture<List<Entry>> entriesFuture,
            CompletableFuture<RunStatus> statusFuture,
            StreamObserver streamObserver,
            EntryObserver entryObserver) {
        var stopReason = new AtomicReference<>("completed");
        try (var registration = harness.onStreamEvent(event -> {
            if (event instanceof StreamEvent.StreamDone done && done.reason() != null) {
                stopReason.set(done.reason());
            }
            if (event instanceof StreamEvent.StreamError) {
                stopReason.set("error");
            }
            if (streamObserver == null) {
                queue.add(event);
            } else {
                streamObserver.onStreamEvent(event);
            }
        })) {
            Action action = harness.run(laneName, prompt);
            while (action != null) {
                action = harness.executeAction(laneName, action);
            }
            var lane = harness.snapshot(laneName);
            var transcript = List.copyOf(lane.transcript());
            entriesFuture.complete(transcript);
            if (entryObserver != null) {
                for (var entry : transcript) {
                    entryObserver.onEntry(entry);
                }
            }
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

    private static Set<AgentTool<?, ?>> activeTools(
            Args args, List<AgentTool<?, ?>> toolList) {
        if (args.noTools() || args.noBuiltinTools()) {
            return Set.of();
        }
        if (args.tools() != null && !args.tools().isEmpty()) {
            var allow = Set.copyOf(args.tools());
            return toolList.stream()
                .filter(t -> allow.contains(t.name()))
                .collect(java.util.stream.Collectors.toSet());
        }
        if (args.excludeTools() != null && !args.excludeTools().isEmpty()) {
            var deny = Set.copyOf(args.excludeTools());
            return toolList.stream()
                .filter(t -> !deny.contains(t.name()))
                .collect(java.util.stream.Collectors.toSet());
        }
        return Set.copyOf(toolList);
    }

    private static ModelThinkingLevel thinkingLevelFor(Args args) {
        if (args.thinking() != null) {
            return ThinkingLevels.parse(args.thinking());
        }
        var fromModel = ThinkingLevels.parseFromModelPattern(args.model());
        return fromModel != null ? fromModel : ModelThinkingLevel.off();
    }

    private static String systemPromptFor(Args args) {
        var base = args.systemPrompt() != null
            ? args.systemPrompt() : DEFAULT_SYSTEM_PROMPT;
        if (args.appendSystemPrompt().isEmpty()) {
            return base;
        }
        return base + "\n\n" + String.join("\n\n", args.appendSystemPrompt());
    }

    private static AgentSession resolveSession(AgentSession session, Args args) {
        if (args.noSession()) {
            return session; // ephemeral: not registered in the repository
        }
        var repository = session.repository;
        if (args.continue_()) {
            return repository.latest().orElseThrow(() -> new IllegalStateException(
                "No previous session to continue in this process "
                    + "(session persistence lands in Phase 4)"));
        }
        if (args.sessionId() != null) {
            // --session-id: exact project session ID, create if missing (§9.1).
            return repository.find(args.sessionId()).orElseGet(() ->
                repository.createWithId(session, args.sessionId()));
        }
        if (args.resume() || args.session() != null) {
            return repository.find(args.session()).orElseThrow(() -> new IllegalStateException(
                "Session not found: " + args.session()
                    + " (in-process registry only; persistence in Phase 4)"));
        }
        if (args.fork() != null) {
            return repository.find(args.fork())
                .map(source -> repository.fork(source, session.name))
                .orElseThrow(() -> new IllegalStateException(
                    "Session not found: " + args.fork()));
        }
        repository.create(session);
        return session;
    }

    private static QueueMode queueMode(String mode) {
        if ("all".equals(mode)) {
            return new QueueMode.All();
        }
        return new QueueMode.OneAtATime();
    }
}
