package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.agent.harness.LaneConfig;
import com.pijava.agent.harness.ToolExecution;
import com.pijava.agent.harness.WatchHandle;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;

import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.memory.MemorySessionRepository;

import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolSetFactory;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ShellOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pijava.agent.tool.ShellResult;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.session.SessionInfo;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionManager;
import com.pijava.coding.agent.extension.ExtensionPackageManager;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.ResourcePaths;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;
import com.pijava.coding.agent.prompt.PromptTemplates;
import com.pijava.coding.agent.skill.SkillDiscovery;
import com.pijava.coding.agent.core.slash.CommandRegistry;

/**
 * Session orchestration: wraps an {@link AgentHarness} with settings, trust,
 * providers and slash commands, and exposes prompt execution with live
 * streaming (Phase 3 design §9.5/§11.5). Since Phase 4, sessions are backed
 * by a persistent {@link SessionRepository} (JSONL default, SQLite opt-in)
 * so {@code -c/-r} and {@code /resume} survive process restarts.
 */
public final class AgentSession implements AutoCloseable {

    /** Default system prompt when no CLI/system prompt is configured. */
    public static final String DEFAULT_SYSTEM_PROMPT =
        "You are pi-java, an AI coding assistant. Help the user write, read, "
            + "and understand code. Use the provided tools when useful.\n\n"
            + "Communication style:\n"
            + "- Be concise in your responses.\n"
            + "- Tool calls are displayed to the user as cards with their results; "
            + "do not repeat their contents in text.\n"
            + "- Before the first tool call, say in one sentence what you are about to do.\n"
            + "- While working, give a one-sentence update at key moments only "
            + "(a finding, a direction change, a blocker).\n"
            + "- Never end a sentence with a colon right before a tool call; "
            + "use a period instead.\n"
            + "- End each turn with a one- or two-sentence summary: what changed "
            + "and what is next.\n"
            + "- Show file paths clearly when working with files.";

    private static final Logger LOG = LoggerFactory.getLogger(AgentSession.class);

    private final AgentHarness harness;
    private final SessionServices services;
    private String name;
    private final Instant createdAt = Instant.now();
    private final Args args;
    private String laneName = AgentHarness.DEFAULT_LANE;
    private InMemorySessionRepository repository = InMemorySessionRepository.shared();
    private PersistentSessionRepositories.RepositoryHandle persistentRepository;
    private Session<?> session;
    private final Set<String> persistedEntryIds = new HashSet<>();
    private final Set<String> persistedRecordIds = new HashSet<>();
    // Entry ids already delivered via end-of-run EntryAppended/entryObserver.
    // Session-scoped because consecutive runs append to one lane transcript
    // (pi alignment) and each run's delivery replays the full transcript.
    private final Set<String> deliveredEntryIds = new HashSet<>();
    // Phase 6: 会话级事件广播（多监听器）。
    private final SessionEventHub eventHub = new SessionEventHub();
    // Phase 6 (P6-7b): 扩展 UI 服务（RPC 模式注入，缺省 noop）。
    private ExtensionUI extensionUI = ExtensionUI.noop();
    // resources_discover：扩展贡献的主题候选（启动时选第一个可用）。
    private List<Path> themePaths = List.of();
    // P6-5d: auto-retry / 会话级 bash 状态（RPC 末批命令）。
    private volatile boolean autoRetryEnabled;
    private volatile boolean retryAborted;
    private volatile AbortSignal bashAbortSignal;

    private AgentSession(AgentHarness harness, SessionServices services,
                         Args args, String name) {
        this.harness = harness;
        this.services = services;
        this.args = args;
        this.name = name;
    }

    /** Assemble from CLI args with the configured persistent backend. */
    /** Web 默认入口：恢复最近一个持久会话，仅当无会话时新建（刷新/重连不丢历史）。 */
    public static AgentSession createWeb(Args args) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        var backend = effective.sessionBackend == null ? "jsonl" : effective.sessionBackend;
        var sessionsRoot = Path.of(args.sessionDir() != null ? args.sessionDir()
            : (effective.sessionDir != null ? effective.sessionDir
                : Path.of(System.getProperty("user.home"), ".pi-java", "agent", "sessions").toString()));
        var handle = "sqlite".equals(backend)
            ? PersistentSessionRepositories.sqlite(sessionsRoot)
            : PersistentSessionRepositories.jsonl(sessionsRoot);
        var session = assemble(args, settings, DefaultProviders.defaultProviders(),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            handle, handle.repository());
        session.persistentRepository = handle;
        return SessionPersistence.resolvePersistentWeb(session, args);
    }

    public static AgentSession create(Args args) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        var backend = effective.sessionBackend == null ? "jsonl" : effective.sessionBackend;
        var sessionsRoot = Path.of(args.sessionDir() != null ? args.sessionDir()
            : (effective.sessionDir != null ? effective.sessionDir
                : Path.of(System.getProperty("user.home"), ".pi-java", "agent", "sessions").toString()));
        var handle = "sqlite".equals(backend)
            ? PersistentSessionRepositories.sqlite(sessionsRoot)
            : PersistentSessionRepositories.jsonl(sessionsRoot);
        return create(args, handle, DefaultProviders.defaultProviders(),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            settings);
    }

    /** Assemble a session against a specific in-memory repository (tests). */
    static AgentSession create(Args args, InMemorySessionRepository repository) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        return create(args, repository, DefaultProviders.defaultProviders(),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            settings);
    }

    /** Create with injected providers and tool context (RPC/testing). */
    public static AgentSession create(Args args, ProviderRegistry providers,
                                      ToolContext toolContext) {
        return create(args, InMemorySessionRepository.create(), providers, toolContext);
    }

    /** Test entry point with injected providers and tool context. */
    static AgentSession create(Args args, InMemorySessionRepository repository,
                               ProviderRegistry providers,
                               ToolContext toolContext) {
        return create(args, repository, providers, toolContext,
            SettingsManager.load(args.projectTrustOverride()));
    }
    private static AgentSession create(Args args, InMemorySessionRepository repository,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings) {
        var session = assemble(args, settings, providers, toolContext,
            null, new MemorySessionRepository());
        session.repository = repository;
        return SessionSetup.resolveSession(session, args);
    }
    private static AgentSession create(Args args, PersistentSessionRepositories.RepositoryHandle handle,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings) {
        var session = assemble(args, settings, providers, toolContext,
            handle, handle.repository());
        session.persistentRepository = handle;
        return SessionSetup.resolveSession(session, args);
    }
    private static AgentSession assemble(Args args, SettingsManager settings,
                                         ProviderRegistry providers,
                                         ToolContext toolContext,
                                         PersistentSessionRepositories.RepositoryHandle handle,
                                         SessionRepository<?, ?, ?> repository) {
        var effective = settings.effective();
        var models = new DefaultModelResolver(
            com.pijava.ai.provider.ModelsJsonConfig.allModels());
        var tools = new ToolRegistry(null);
        var commandPrefix = effective.shellCommandPrefix == null
            ? "" : effective.shellCommandPrefix;
        var toolList = ToolSetFactory.createCodingTools(commandPrefix);
        tools.registerAll(toolList);

        var promptTemplates = new PromptTemplateRegistry();
        // CLI --prompt-template 路径先载入注册表（--no-prompt-templates 时为空）。
        if (!args.noPromptTemplates() && args.promptTemplates() != null
                && !args.promptTemplates().isEmpty()) {
            var load = PromptTemplates.load(
                new DefaultFileSystem(), args.promptTemplates());
            promptTemplates.registerAll(load.templates());
        }
        var services = new SessionServices(
            settings,
            new TrustManager(effective.defaultProjectTrust),
            providers,
            models,
            tools,
            CommandRegistry.withBuiltins(),
            repository,
            promptTemplates);

        var providerName = DefaultProviders.resolveProviderName(
            args, effective.defaultProvider);
        var modelPattern = args.model() != null
            ? args.model() : effective.defaultModel;
        if (modelPattern == null || modelPattern.isBlank()) {
            modelPattern = providerName;
        }
        var streamFn = DefaultProviders.streamFnFor(
            args, effective.defaultProvider, providers, effective);
        // Trace exporter: same instance powers harness telemetry (spans/counters)
        // and payload recording (wrapper), so events bind to the llm.request span.
        var telemetry = PayloadRecordingStreamFn.exporter(args.sessionId(), args.tracePayloads());
        var recordingStreamFn = new PayloadRecordingStreamFn(streamFn, telemetry);
        var model = models.resolve(modelPattern, providerName);
        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(recordingStreamFn)
            .model(model)
            .summaryGenerator(new LlmSummaryGenerator(recordingStreamFn, () -> model))
            .thinkingLevel(SessionSetup.thinkingLevelFor(args))
            .systemPrompt(SessionSetup.systemPromptFor(args))
            .activeTools(SessionSetup.activeTools(args, toolList))
            .toolRegistry(tools)
            .toolContext(toolContext)
            .steeringMode(SessionSetup.queueMode(effective.steeringMode))
            .followUpMode(SessionSetup.queueMode(effective.followUpMode))
            .toolExecution(ToolExecution.defaultMode())
            .skills(SessionSetup.discoverSkills(args))
            .telemetry(telemetry)
            .build());
        var agentSession = new AgentSession(
            harness, services, args,
            args.name() != null ? args.name() : "session");
        agentSession.persistentRepository = handle;
        loadExtensions(args, services, harness, ExtensionUI.noop(), agentSession);
        return agentSession;
    }

    /**
     * 收集扩展贡献的会话开始资源（resources_discover）并接入会话。
     *
     * <p>加载顺序使扩展在 harness 构造之后运行，故钩子贡献的技能经共享
     * {@code SkillManager} 在首轮 LLM 请求前注册即生效（技能在请求期惰性读取）。
     * promptPaths 经 {@code PromptTemplates} 载入 {@code SessionServices} 的
     * 注册表；themePaths 作为候选列表存于会话，供 TUI 启动时选第一个。</p>
     */
    private static void applySessionStartResources(AgentHarness harness,
            SessionServices services, ResourcePaths paths, Args args,
            AgentSession owner) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        // skills: 加载钩子贡献的路径并入 harness 的 SkillManager（与 --skills 同容器）。
        if (!paths.skillPaths().isEmpty() && !args.noSkills()) {
            var cwd = Path.of(System.getProperty("user.dir"));
            var discovery = new SkillDiscovery(
                cwd, FileSettingsStorage.defaultAgentDir());
            var result = discovery.discoverAll(false, paths.skillPaths());
            for (var skill : result.skills()) {
                harness.skillManager().register(skill);
            }
        }
        // prompts: 加载钩子贡献的路径并入 SessionServices 的注册表。
        if (!paths.promptPaths().isEmpty() && !args.noPromptTemplates()) {
            var load = PromptTemplates.load(
                new DefaultFileSystem(),
                paths.promptPaths().stream().map(Path::toString).toList());
            services.promptTemplates().registerAll(load.templates());
        }
        // themes: 扩展贡献的候选列表（TUI 启动时选第一个可用）。
        if (!paths.themePaths().isEmpty() && !args.noThemes()) {
            owner.themePaths = List.copyOf(paths.themePaths());
        }
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
        if (session != null) {
            session.setName(newName);
        }
    }

    /** 会话持久化 id（pi {@code AgentSession.sessionId}）；in-memory 会话回退显示名。 */
    public String sessionId() {
        return session == null ? name : session.getMetadata().id();
    }

    /** The CLI arguments this session was assembled from ({@code /new}). */
    public Args sessionArgs() {
        return args;
    }

    /** 扩展贡献的主题候选（resources_discover themePaths）；无则空列表。 */
    public List<Path> themePaths() {
        return themePaths;
    }

    /** The active lane name. */
    public String laneName() {
        return laneName;
    }

    /** Approximate transcript size for session listings. */
    public long entryCount() {
        return harness.snapshot(laneName).transcript().size();
    }

    /** 全文对话条目（持久会话提交序），供跨回合/重连展示完整历史。 */
    public List<Entry> accumulatedEntries() {
        if (session != null) {
            var entries = session.findEntries(
                new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null));
            if (!entries.isEmpty()) {
                return entries;
            }
            LOG.debug("[session] accumulatedEntries: storage empty ({} entries), falling back to harness recent",
                entries.size());
        } else {
            LOG.warn("[session] accumulatedEntries: persistent session is null, using harness recent");
        }
        return harness.snapshot(laneName).transcript();
    }

    /** Full-history messages (accumulatedEntries projected to their payloads). */
    public List<com.pijava.ai.message.Message> accumulatedMessages() {
        return accumulatedEntries().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
    }

    /** Run a prompt, returning a live {@link SessionResult} (Phase 3 §11.1). */
    public SessionResult processPrompt(String prompt, PromptConfig config) {
        return processPrompt(prompt, config, null, null);
    }

    /** Run a prompt, forwarding events/entries to observers (one virtual thread). */
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

        var queue = new LinkedBlockingQueue<Optional<StreamEvent>>();
        var entriesFuture = new CompletableFuture<List<Entry>>();
        var statusFuture = new CompletableFuture<RunStatus>();

        Thread.startVirtualThread(() -> SessionRunner.drive(
            this, prompt, queue, entriesFuture, statusFuture,
            streamObserver, entryObserver));

        Stream<StreamEvent> stream = Stream.generate(() -> {
            try {
                return queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.<StreamEvent>empty();
            }
        }).takeWhile(Optional::isPresent).map(Optional::get);
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
                    && "assistant".equals(message.message().role())) {
                var builder = new StringBuilder();
                for (var block : message.message().content()) {
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

    /**
     * 订阅会话级事件（对齐 pi {@code AgentSession.subscribe}）。
     *
     * <p>支持多监听器；返回的句柄关闭时只摘除本监听器。P6-5a 首批发射
     * {@code MessageUpdate} / {@code AgentEnd} / {@code AgentSettled} /
     * {@code EntryAppended} 四种事件。</p>
     *
     * @param listener 事件监听器
     * @return 注册句柄；关闭它只摘除本监听器
     */
    public AutoCloseable subscribe(Consumer<AgentSessionEvent> listener) {
        return eventHub.subscribe(listener);
    }

    /** 广播会话事件给所有监听器；单个监听器异常被隔离。 */
    void emitSessionEvent(AgentSessionEvent event) {
        eventHub.emit(event);
    }

    /** 注入扩展 UI 服务（RPC 模式用；覆盖 loadExtensions 时的 noop）。 */
    public void extensionUI(ExtensionUI ui) {
        if (ui != null) {
            this.extensionUI = ui;
        }
    }

    /** 当前扩展 UI 服务。 */
    public ExtensionUI extensionUI() {
        return extensionUI;
    }

    /** List sessions for {@code /session} and the resume picker. */
    public List<SessionInfo> listSessions() {
        if (persistentRepository != null) {
            return persistentRepository.list(System.getProperty("user.dir")).stream()
                .map(meta -> new SessionInfo(meta.id(), "session",
                    meta.createdAt(), 0))
                .toList();
        }
        return repository.list();
    }

    /** 会话列表摘要（web UI 用；对齐 pi {@code SessionManager.list}）。 */
    public record SessionSummary(
            String id, String path, String name, String cwd, Instant created,
            long modifiedMs, int messageCount, String firstMessage) {
    }

    /**
     * 列出 {@code cwd} 下的持久化会话摘要。name/messageCount/firstMessage 需
     * 打开会话读取（逐会话 writer lease），本地工具场景可接受；打开失败降级为
     * 元数据摘要。
     */
    public List<SessionSummary> listSessionsSummary(String cwd) {
        if (persistentRepository == null) {
            return List.of();
        }
        String dir = cwd == null || cwd.isBlank() ? System.getProperty("user.dir") : cwd;
        var result = new java.util.ArrayList<SessionSummary>();
        for (var meta : persistentRepository.list(dir)) {
            result.add(summaryFor(meta));
        }
        return result;
    }

    private SessionSummary summaryFor(SessionMetadata meta) {
        String path = meta.id();
        String cwd = "";
        long modifiedMs = meta.createdAt().toEpochMilli();
        if (meta instanceof JsonlSessionMetadata j) {
            path = j.path().toString();
            cwd = j.cwd();
            modifiedMs = j.modifiedAtMs();
        }
        String name = "";
        int messageCount = 0;
        String firstMessage = "";
        Session<?> opened = null;
        try {
            opened = persistentRepository.open(meta);
            name = opened.getName();
            messageCount = (int) opened.getStats().messageCount();
            var first = opened.findEntries(
                new EntryQuery("message", null, EntryOrder.OLDEST_FIRST, 1, null));
            if (!first.isEmpty() && first.get(0) instanceof Entry.Message m
                    && m.message() != null) {
                firstMessage = firstMessageText(m.message());
            }
        } catch (Exception ignored) {
            // 打开失败（并发写锁等）降级为元数据摘要
        } finally {
            if (opened != null) {
                try {
                    opened.close();
                } catch (Exception ignored) {
                    // 忽略关闭失败
                }
            }
        }
        return new SessionSummary(meta.id(), path, name, cwd,
            meta.createdAt(), modifiedMs, messageCount, firstMessage);
    }

    /** 取消息纯文本（首个 text 内容块；无则空串）。 */
    private static String firstMessageText(com.pijava.ai.message.Message message) {
        return message.content().stream()
            .filter(com.pijava.ai.message.ContentBlock.TextContent.class::isInstance)
            .map(com.pijava.ai.message.ContentBlock.TextContent.class::cast)
            .map(com.pijava.ai.message.ContentBlock.TextContent::text)
            .findFirst()
            .orElse("");
    }

    /** The most recent session, or empty ({@code -c}). */
    public Optional<AgentSession> latestSession() {
        if (persistentRepository != null) {
            return persistentRepository.latest().map(meta -> {
            SessionPersistence.attach(this, persistentRepository, meta);
            return this;
        });
        }
        return repository.latest();
    }

    /** Find a session by ID/prefix ({@code -r/--session}). */
    public Optional<AgentSession> findSession(String idOrPrefix) {
        if (persistentRepository != null) {
            return persistentRepository.find(idOrPrefix).map(meta -> {
            SessionPersistence.attach(this, persistentRepository, meta);
            return this;
        });
        }
        return repository.find(idOrPrefix);
    }

    /** Export the current session to a JSONL file ({@code /export}). */
    public void exportJsonl(java.nio.file.Path target) {
        SessionPersistence.exportJsonl(this, target);
    }

    /** Import a JSONL file into a new persistent session ({@code /import}). */
    public AgentSession importJsonl(java.nio.file.Path source) {
        return SessionPersistence.importJsonl(this, source);
    }

    /** Create a forked copy on a new lane ({@code /fork /clone --fork}). */
    public AgentSession forkCopy(String branchName) {
        if (persistentRepository != null && session != null) {
            var metadata = session.getMetadata();
            var forked = persistentRepository.fork(metadata, new ForkOptions.Tree(),
                System.getProperty("user.dir"));
            var copy = new AgentSession(harness, services, args, branchName);
            copy.persistentRepository = persistentRepository;
            copy.session = forked;
            SessionPersistence.attach(copy, persistentRepository, forked.getMetadata());
            copy.name = branchName;
            copy.laneName = AgentHarness.DEFAULT_LANE;
            return copy;
        }
        harness.createLane(LaneConfig.of(branchName));
        var forked = new AgentSession(harness, services, args, branchName);
        forked.laneName = branchName;
        forked.repository = repository;
        repository.create(forked);
        return forked;
    }

    /** Fork a new session branching from a specific entry (RPC {@code fork}/{@code clone}).
     *  持久化路径按 pi {@code ForkOptions.Branch} 在 entry 前分支；内存路径经
     *  {@link LaneConfig#parentLeafId()} 在指定 entry 处建新 lane。 */
    public AgentSession forkFromEntry(String entryId) {
        String branchName = name + "-fork";
        if (persistentRepository != null && session != null) {
            var metadata = session.getMetadata();
            var forked = persistentRepository.fork(metadata,
                new ForkOptions.Branch(entryId, ForkOptions.Branch.Position.BEFORE),
                System.getProperty("user.dir"));
            var copy = new AgentSession(harness, services, args, branchName);
            copy.persistentRepository = persistentRepository;
            copy.session = forked;
            SessionPersistence.attach(copy, persistentRepository, forked.getMetadata());
            copy.name = branchName;
            copy.laneName = AgentHarness.DEFAULT_LANE;
            return copy;
        }
        harness.createLane(new LaneConfig(branchName, entryId, null, null));
        var forked = new AgentSession(harness, services, args, branchName);
        forked.laneName = branchName;
        forked.repository = repository;
        repository.create(forked);
        return forked;
    }

    /** 会话级 bash 执行（RPC {@code bash}）：经 harness shell 运行并发射一条
     *  {@link AgentSessionEvent.BashExecutionUpdate}。v1 阻塞执行，不增量流式。 */
    public ShellResult executeBash(String id, String command, boolean excludeFromContext) {
        var toolContext = harness.toolContext();
        var signal = AbortSignal.create();
        bashAbortSignal = signal;
        try {
            var result = toolContext.shell().execute(command, new ShellOptions(
                toolContext.cwd(), toolContext.env(), true,
                java.util.OptionalLong.empty(), signal));
            eventHub.emit(new AgentSessionEvent.BashExecutionUpdate(id, result.output()));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Bash command failed: " + command, e);
        }
    }

    /** 中止正在执行的会话级 bash（RPC {@code abort_bash}）。 */
    public void abortBash() {
        var signal = bashAbortSignal;
        if (signal != null) {
            signal.abort();
        }
    }

    /** 最近一次会话级 bash 是否被中止（RPC {@code bash} 响应 {@code cancelled}）。 */
    public boolean bashAborted() {
        var signal = bashAbortSignal;
        return signal != null && signal.isAborted();
    }

    /** 启用/停用自动重试（RPC {@code set_auto_retry}）。 */
    public void setAutoRetryEnabled(boolean enabled) {
        autoRetryEnabled = enabled;
    }

    /** 自动重试是否启用。 */
    public boolean autoRetryEnabled() {
        return autoRetryEnabled;
    }

    /** 中止当前 run 的待处理重试（RPC {@code abort_retry}）。 */
    public void abortRetry() {
        retryAborted = true;
    }

    /** 是否已请求中止重试（SessionRunner 轮询）；每次 drive 启动前重置。 */
    boolean retryAborted() {
        return retryAborted;
    }

    /** 重置重试中止标志（SessionRunner 每次 run 前调用）。 */
    void resetRetryAbort() {
        retryAborted = false;
    }

    /** 可供 fork 的用户消息（RPC {@code get_fork_messages}，对齐 pi
     *  {@code getUserMessagesForForking}）。 */
    public List<Entry.Message> getUserMessagesForForking() {
        return harness().snapshot(laneName).transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .filter(e -> "user".equals(e.message().role()))
            .toList();
    }

    /** Flush settings, persist pending writes and close the harness. */
    @Override
    public void close() {
        if (session != null) {
            SessionPersistence.persistPending(this, session, laneName);
            session.close();
        }
        if (persistentRepository != null) {
            persistentRepository.close();
        }
        services.settings().flush();
        harness.close();
    }

    // ── Internals ────────────────────────────────────────────

    /** 加载扩展（ServiceLoader + 扩展目录 JAR），--no-extensions 时跳过。 */
    private static void loadExtensions(Args args, SessionServices services,
                                       AgentHarness harness, ExtensionUI ui,
                                       AgentSession owner) {
        if (args.noExtensions()) {
            return;
        }
        var context = new DefaultExtensionContext(services, harness.skillManager(), ui);
        // session 由 SessionPersistence 稍后赋值，惰性求值绑定供 sendMessage 落盘用。
        context.bindSession(() -> owner.session());
        var manager = new ExtensionManager(context);
        manager.loadAll();
        for (var jar : ExtensionPackageManager.global().installedJars()) {
            manager.loadJar(jar);
        }
        for (var jar : ExtensionPackageManager.project().installedJars()) {
            manager.loadJar(jar);
        }
        applySessionStartResources(harness, services,
            manager.sessionStartResources(), args, owner);
    }

    // ── Package-private accessors ───────────────────────────
    Session<?> session() {
        return session;
    }
    void session(Session<?> session) {
        this.session = session;
    }

    PersistentSessionRepositories.RepositoryHandle persistentRepository() {
        return persistentRepository;
    }
    InMemorySessionRepository inMemoryRepository() {
        return repository;
    }
    java.util.Set<String> persistedEntryIds() {
        return persistedEntryIds;
    }
    java.util.Set<String> persistedRecordIds() {
        return persistedRecordIds;
    }
    java.util.Set<String> deliveredEntryIds() {
        return deliveredEntryIds;
    }

    void name(String name) {
        this.name = name;
    }
}
