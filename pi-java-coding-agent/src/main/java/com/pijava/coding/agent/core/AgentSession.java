package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.compaction.CompactionObserver;
import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.DefaultStreamFn;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.ToolExecution;
import com.pijava.agent.harness.WatchHandle;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.prompt.ContextFileDiscovery;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;

import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.memory.MemorySessionRepository;

import com.pijava.agent.tool.ImagePolicy;
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
import com.pijava.ai.thinking.ModelThinkingLevel;
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
import com.pijava.coding.agent.compaction.BranchSummarization;
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

    private static final Logger LOG = LoggerFactory.getLogger(AgentSession.class);

    /** A5（原 docs/25）：并发 prompt 路由门，无状态。 */
    private static final PromptRouter PROMPT_ROUTER = new PromptRouter();

    private final AgentHarness harness;
    private final SessionServices services;
    private String name;
    private final Instant createdAt = Instant.now();
    private final Args args;
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
    // 3d（原 docs/31 §8.22）：pi 的重试中止是「仅退避睡眠存活」的
    // _retryAbortController（agent-session.ts:2948 建、:2963 finally 弃）——
    // 会话侧等价的窗口模拟：onAutoRetryStart 开窗（先清残留计数，≙ 新 controller）、
    // onAutoRetryEnd 关窗；abortRetry() 只在窗口内置旗（窗口外是 no-op，与 pi 一致，
    // 不再有「下次 run 前复位」的 resetRetryAbort —— pi 没有这个概念）。
    // enabled 不再存字段：pi 的 autoRetryEnabled 存取都直通 settingsManager
    // （读 effective.retry.enabled ?? true，写 global + markModified + save）。
    private volatile boolean retrySleepActive;
    private final AtomicBoolean retryCancel = new AtomicBoolean();

    // pi agent-session.ts:1083/2434-2436/1823：settled 时读旗；abort() 置位；run 起手清零。
    // 包内可见：SessionRunner 在驱动起手清零、收尾读值。
    final AtomicBoolean agentRunAbortRequested = new AtomicBoolean();

    /** pi {@code _agentRunAbortRequested}：settled 事件的 aborted 载荷源（包内可见）。 */
    boolean agentRunAbortRequested() {
        return agentRunAbortRequested.get();
    }
    private volatile AbortSignal bashAbortSignal;
    // pi _pendingBashMessages（agent-session.ts:421）：运行中暂存的会话级 bash 结果，
    // settled 时 flush（保 tool_use/tool_result 次序）。
    private final List<Entry.BashExecution> pendingBashMessages = new ArrayList<>();
    // B137：--models/settings patterns 解析出的 scope（Ctrl+P / cycle_model 在其间轮转）。
    private List<com.pijava.ai.model.ScopedModel> scopedModels = List.of();
    // scope 解析与轮转都对全目录（assemble 构造的同一实例）。
    private com.pijava.ai.catalog.ModelCatalog modelCatalog =
        com.pijava.ai.catalog.ModelCatalog.empty();
    // B1（map #24/#25）：分支摘要生成器与模型解析器（create 装配）。
    private com.pijava.coding.agent.compaction.BranchSummarization branchSummarization;
    private com.pijava.ai.model.DefaultModelResolver modelResolver;
    /** pi {@code _branchSummaryAbortController}（agent-session.ts:4018 创建/:4155 清）。 */
    private volatile com.pijava.agent.harness.CompactionAbortToken branchSummaryAbort;
    // docs/15（B249/B250）：宿主发射器 —— 生命周期/可取消 before 族/结果臂的统一
    // 发射口（pi 的 extensionRunner 宿主发射法对位）。--no-extensions 时为 null，
    // 全部 firing 点判空快走（pi 无扩展＝runner 空表，行为等价）。
    private com.pijava.coding.agent.extension.SessionEventFirer extensionFirer;

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
        var session = assemble(args, settings,
            DefaultProviders.defaultProviders(effective.catalogBaseUrl),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            handle, handle.repository(),
            // pi hasExistingSession＝buildSessionContext().messages.length>0；assemble
            // 前查 latest 等价（裁决⑥：resume 存量会话时不应用 scoped 初始模型）。
            handle.latest(System.getProperty("user.dir")).isPresent());
        session.persistentRepository = handle;
        return SessionPersistence.resolvePersistentWeb(session, args);
    }

    /**
     * Web 入口的**测试缝**（台账 A13）：与 {@link #createWeb(Args)} 同形，但注入
     * providers 与 toolContext。持久化后端仍由 {@code args.sessionDir()} 决定 ——
     * 测试靠它做 resume。
     *
     * <p>存在的理由：{@code createWeb} 用 {@code DefaultProviders} ⇒ 走**真实
     * provider（网络）**，把单测变成网络依赖（{@code SessionResumeFoldTest} 曾因此在
     * 全树负载下 311 s 才失败、断言因重试环多出一条续跑 operation）。既有重载里
     * **没有**「持久化仓库 ＋ 注入 provider」这个组合。</p>
     */
    static AgentSession createWeb(Args args, ProviderRegistry providers,
                                  ToolContext toolContext) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        var backend = effective.sessionBackend == null ? "jsonl" : effective.sessionBackend;
        var sessionsRoot = Path.of(args.sessionDir() != null ? args.sessionDir()
            : (effective.sessionDir != null ? effective.sessionDir
                : Path.of(System.getProperty("user.home"), ".pi-java", "agent", "sessions").toString()));
        var handle = "sqlite".equals(backend)
            ? PersistentSessionRepositories.sqlite(sessionsRoot)
            : PersistentSessionRepositories.jsonl(sessionsRoot);
        var session = assemble(args, settings, providers, toolContext,
            handle, handle.repository(),
            handle.latest(System.getProperty("user.dir")).isPresent());
        session.persistentRepository = handle;
        return SessionPersistence.resolvePersistentWeb(session, args);
    }

    public static AgentSession create(Args args) {
        return create(args, (SessionStartInfo) null);
    }

    /**
     * docs/15（B250）：替换式新建（/new、RPC new_session、Web newSession）——
     * 新会话装配时 {@code session_start} 带 {@code {reason:"new",
     * previousSessionFile:旧会话 id}}。旧侧的 {@code session_shutdown} 由调用方
     * 在拿到新会话后 {@link #fireSessionShutdown}（目标 id 先知才行，窄差 D20）。
     */
    public static AgentSession createReplacing(Args args, String previousSessionId) {
        return create(args, new SessionStartInfo("new", previousSessionId));
    }

    private static AgentSession create(Args args, SessionStartInfo startInfo) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        var backend = effective.sessionBackend == null ? "jsonl" : effective.sessionBackend;
        var sessionsRoot = Path.of(args.sessionDir() != null ? args.sessionDir()
            : (effective.sessionDir != null ? effective.sessionDir
                : Path.of(System.getProperty("user.home"), ".pi-java", "agent", "sessions").toString()));
        var handle = "sqlite".equals(backend)
            ? PersistentSessionRepositories.sqlite(sessionsRoot)
            : PersistentSessionRepositories.jsonl(sessionsRoot);
        return create(args, handle, DefaultProviders.defaultProviders(effective.catalogBaseUrl),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            settings, startInfo);
    }

    /** Assemble a session against a specific in-memory repository (tests). */
    static AgentSession create(Args args, InMemorySessionRepository repository) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var effective = settings.effective();
        return create(args, repository, DefaultProviders.defaultProviders(effective.catalogBaseUrl),
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
            null, new MemorySessionRepository(), resumeLike(args));
        session.repository = repository;
        return SessionSetup.resolveSession(session, args);
    }
    /** 持久仓＋注入 provider 组合（NavigateTreeTest 等同包夹具用，createWeb 的 CLI 对应形）。 */
    static AgentSession create(Args args, PersistentSessionRepositories.RepositoryHandle handle,
                               ProviderRegistry providers,
                               ToolContext toolContext,
                               SettingsManager settings) {
        return create(args, handle, providers, toolContext, settings, null);
    }

    private static AgentSession create(Args args,
                                       PersistentSessionRepositories.RepositoryHandle handle,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings,
                                       SessionStartInfo startInfo) {
        var session = assemble(args, settings, providers, toolContext,
            handle, handle.repository(), resumeLike(args), startInfo);
        session.persistentRepository = handle;
        return SessionSetup.resolveSession(session, args);
    }

    /**
     * pi {@code hasExistingSession}（main.ts:826：buildSessionContext 有消息）的 args
     * 侧近似（docs/12 §4⑥）：任一 resume/fork 旗标出线即视为存量会话，scope 初始模型
     * 不应用（pi 门控同形）；scope 列表本身仍装配，Ctrl+P 轮转照常用。
     */
    private static boolean resumeLike(Args args) {
        return args.continue_() || args.resume() || args.session() != null
            || args.sessionId() != null || args.fork() != null;
    }

    /** docs/15（B250）：替换式新建的 {@code session_start} 载荷（null ⇒ 初始 {@code "startup"}）。 */
    private record SessionStartInfo(String reason, String previousSessionFile) {}

    private static AgentSession assemble(Args args, SettingsManager settings,
                                         ProviderRegistry providers,
                                         ToolContext toolContext,
                                         PersistentSessionRepositories.RepositoryHandle handle,
                                         SessionRepository<?, ?, ?> repository,
                                         boolean existingSession) {
        return assemble(args, settings, providers, toolContext, handle, repository,
            existingSession, null);
    }

    private static AgentSession assemble(Args args, SettingsManager settings,
                                         ProviderRegistry providers,
                                         ToolContext toolContext,
                                         PersistentSessionRepositories.RepositoryHandle handle,
                                         SessionRepository<?, ?, ?> repository,
                                         boolean existingSession,
                                         SessionStartInfo startInfo) {
        var effective = settings.effective();
        var catalog = com.pijava.ai.provider.ModelsJsonConfig.allModels();
        var models = new DefaultModelResolver(catalog);
        // B137：--models ?? settings.enabledModels → scope（诊断此处打 stderr）。
        var scope = ModelScoping.resolve(args, settings, catalog);
        var tools = new ToolRegistry(null);
        var commandPrefix = effective.shellCommandPrefix == null
            ? "" : effective.shellCommandPrefix;
        // B236（docs/11 §3.6）：注册面 = pi createAllTools 八件（tools/index.ts:213-224，
        // 含 powershell——批 4 只落了本体，注册面在本批）经 pi _isAllowedTool 门过滤
        //（agent-session.ts:1537-1541 → _refreshToolRegistry :3514）：allowlist 不匹配或
        // excluded 的内置工具**不注册**；空名单（--no-tools / --tools ""）注册面空
        //（pi getAllTools()==[]，default-tools-setting.test.ts:155）。
        var selection = SessionSetup.resolveToolSelection(args, settings);
        var toolList = ToolSetFactory.createAllTools(commandPrefix).stream()
            .filter(t -> selection.isAllowed(t.name()))
            .toList();
        tools.registerAll(toolList);

        var promptTemplates = new PromptTemplateRegistry();
        if (!args.noPromptTemplates()) {
            // pi loadPromptTemplates includeDefaults（prompt-templates.ts:262-265）：
            // 默认两目录 agentDir/prompts（user）+ cwd/.pi-java/prompts（project）。
            var defaults = PromptTemplates.loadDefaults(new DefaultFileSystem(),
                FileSettingsStorage.defaultAgentDir().toString(),
                System.getProperty("user.dir"));
            promptTemplates.registerAll(defaults.templates());
            // CLI --prompt-template 路径再载入（同名后注册胜出，pi 同序）。
            if (args.promptTemplates() != null && !args.promptTemplates().isEmpty()) {
                var load = PromptTemplates.load(
                    new DefaultFileSystem(), args.promptTemplates());
                promptTemplates.registerAll(load.templates());
            }
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
        // pi buildSessionOptions:499-516：未显式 --model 且非 resume 存量会话时，
        // saved 默认在 scope 内则用之，否则 scoped[0]；scope 空回退既有解析。
        com.pijava.ai.model.ScopedModel scopedInitial = null;
        if ((args.model() == null || args.model().isBlank()) && !existingSession) {
            scopedInitial = ModelScoping.initial(
                scope, effective.defaultProvider, effective.defaultModel, catalog)
                .orElse(null);
        }
        String scopeLevel = scopedInitial != null
            ? scopedInitial.thinkingLevel().orElse(null) : null;
        var streamFn = DefaultProviders.streamFnFor(
            args, effective.defaultProvider, providers, effective, args.sessionId());
        // pi sdk.ts:43-46 的模块顶层副作用对位（setDefaultStreamFn(streamSimple)）：
        // 进程级缺省兜底，给省略 streamFn 的 HarnessConfig.build() 回落（#69）。
        // 窄差：pi 装的 streamSimple 每次调用现解析环境凭证，这里装的是本次会话
        // 装配的解析结果（后续会话覆盖安装）——单会话场景行为等价（docs/09 §3.3）。
        DefaultStreamFn.set(streamFn);
        // Trace exporter: same instance powers harness telemetry (spans/counters)
        // and payload recording (wrapper), so events bind to the llm.request span.
        var telemetry = PayloadRecordingStreamFn.exporter(args.sessionId(), args.tracePayloads());
        var recordingStreamFn = new PayloadRecordingStreamFn(streamFn, telemetry);
        var model = scopedInitial != null
            ? scopedInitial.model().id()
            : models.resolve(modelPattern, providerName);
        // 3c（原 docs/31 §8.21，裁决③）：宿主层的自动压缩事件走轻量 observer，不占
        // HookSystem。harness 先于会话构造 ⇒ 用引用晚绑定：observer 在压缩真正
        // 发生时（必晚于 create() 返回）才读得到会话。pi 的 compaction_start/end
        // 就发在会话层，这里经 emitSessionEvent 进同一条会话事件流。
        var sessionRef = new java.util.concurrent.atomic.AtomicReference<AgentSession>();
        // B85（批 9，docs/18 §4.3/§4.5）：图片策略晚绑定——read 与工具结果收口点在
        // 执行期现读，设置改动 / setModel 后下次工具调用即生效（pi _limitsModel 语义）。
        var policyToolContext = toolContext.withImagePolicy(() -> {
            var s = sessionRef.get();
            return s == null ? ImagePolicy.defaults() : s.currentImagePolicy();
        });
        // B242（docs/09 §8.3）：PI_* 会话身份晚绑定（pi bash.ts:199-208 的 ctx 三面）。
        // 执行期现读 ⇒ setModel/setThinkingLevel 后下次工具调用即拿到新值。
        policyToolContext = policyToolContext.withSessionEnvironment(() -> {
            var s = sessionRef.get();
            if (s == null) {
                return null;
            }
            var m = s.harness.getModel();
            return new com.pijava.agent.tool.SessionEnvironment(
                s.sessionId(), s.sessionFile(),
                m == null ? null : m.provider(),
                m == null ? null : m.modelName(),
                s.harness.getThinkingLevel() == null ? null
                    : s.harness.getThinkingLevel().label());
        });
        var compactionObserver = new CompactionObserver() {
            @Override
            public void onStart(String reason) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(
                        new AgentSessionEvent.CompactionStart(compactionReasonOf(reason)));
                }
            }

            @Override
            public void onEnd(String reason, CompactionResult result, boolean aborted,
                              boolean willRetry, String errorMessage,
                              com.pijava.agent.entry.Entry.Compaction compactionEntry) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.CompactionEnd(
                        compactionReasonOf(reason), result, aborted, willRetry, errorMessage,
                        compactionEntry));
                }
            }
        };
        // 3d（原 docs/31 §8.22）：两环的运行时设置与事件。设置 = 每决策现读
        // settings.accessors().getRetrySettings()（pi 的 getRetrySettings() 不缓存）；
        // 中止旗与事件仍经 sessionRef 晚绑定（harness 先于会话构造）。
        // 环 A 的 auto_retry_* 由引擎 ①（PostRunRetry）发，环 B 的
        // summarization_retry_* 由摘要生成器内的 retryAssistantCall 环发 ——
        // 同一份 observer 接两路。窗口簿记（begin/endRetrySleep）≙ pi 的
        // _retryAbortController 建/弃。
        Supplier<RetrySettings> retrySettings = settings.accessors()::getRetrySettings;
        BooleanSupplier retryAborted = () -> {
            var session = sessionRef.get();
            return session != null && session.retryAborted();
        };
        var retryObserver = new RetryObserver() {
            @Override
            public void onAutoRetryStart(int attempt, int maxAttempts, long delayMs,
                                         String errorMessage) {
                var session = sessionRef.get();
                if (session != null) {
                    session.beginRetrySleep();
                    session.emitSessionEvent(new AgentSessionEvent.AutoRetryStart(
                        attempt, maxAttempts, delayMs, errorMessage));
                }
            }

            @Override
            public void onAutoRetryEnd(boolean success, int attempt, String finalError) {
                var session = sessionRef.get();
                if (session != null) {
                    session.endRetrySleep();
                    session.emitSessionEvent(new AgentSessionEvent.AutoRetryEnd(
                        success, attempt, finalError));
                }
            }

            @Override
            public void onSummarizationRetryScheduled(int attempt, int maxAttempts,
                                                      long delayMs, String errorMessage) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.SummarizationRetryScheduled(
                        attempt, maxAttempts, delayMs, errorMessage));
                }
            }

            @Override
            public void onSummarizationRetryAttemptStart(String source, String reason) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.SummarizationRetryAttemptStart(
                        source, reason));
                }
            }

            @Override
            public void onSummarizationRetryFinished() {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.SummarizationRetryFinished());
                }
            }
        };
        // B173（docs/22）：摘要请求要读会话当前思考级别，而 harness 在 generator
        // 构造之后才诞生 —— 按 sessionRef/compactionObserver 同款引用晚绑定；
        // supplier 只在摘要请求时（必晚于 create 返回）才读。
        var harnessRef = new java.util.concurrent.atomic.AtomicReference<AgentHarness>();
        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(recordingStreamFn)
            .model(model)
            // 3b（原 docs/31 §8.20）：阈值压缩的门读**当前模型**的上下文窗口
            // （pi model.contextWindow，agent-session.ts:548）；目录字段
            // maxInputTokens 的释义即窗口大小。setModel 后随之动态变。
            .contextWindow(models::contextWindow)
            // 3c（原 docs/31 §8.21，裁决④）：isRecoverableLength 的操作数 =
            // pi model.maxTokens = 目录 maxOutputTokens；未编目 ⇒ 0 ⇒ 判据恒 false。
            .maxOutputTokens(models::maxOutputTokens)
            // 3d 环 B：摘要调用包在 retryAssistantCall 同形环里 —— 与环 A
            // 同一份 retry 预算、同一个中止旗观察口、同一个 observer（§8.22.3）。
            .summaryGenerator(new LlmSummaryGenerator(recordingStreamFn, () -> model,
                // B171（docs/18）：pi model.maxTokens 从目录取值 —— turn-prefix
                // 摘要的输出上限被当前模型的 maxOutputTokens 封顶（cap 在调用点
                // 读当前 model，故经零参 lambda 传入，不直传一参方法引用）。
                retrySettings, retryAborted, retryObserver,
                () -> models.maxOutputTokens(model),
                () -> harnessRef.get().getThinkingLevel(),
                models::supportsThinking))
            .thinkingLevel(SessionSetup.thinkingLevelFor(
                args, settings.effective().defaultThinkingLevel, scopeLevel))
            .systemPrompt(SessionSetup.customPromptFor(args))
            .promptGuidelines(SessionSetup.DEFAULT_PROMPT_GUIDELINES)
            .appendSystemPrompt(SessionSetup.appendSystemPromptFor(args))
            .activeTools(SessionSetup.activeTools(selection, toolList))
            .toolRegistry(tools)
            .toolContext(policyToolContext)
            .steeringMode(SessionSetup.queueMode(effective.steeringMode))
            .followUpMode(SessionSetup.queueMode(effective.followUpMode))
            .toolExecution(ToolExecution.defaultMode())
            .skills(SessionSetup.discoverSkills(args))
            .telemetry(telemetry)
            .compactionObserver(compactionObserver)
            // 3d 环 A/B 三件套（原 docs/31 §8.22.3）：settings 晚读口（每次 run 现取，
            // 覆盖 setAutoRetryEnabled 之后的路径）、中止观察口（退避睡眠窗口）、
            // 事件观察器（auto_retry_* / summarization_retry_* 映射到会话事件面）。
            // 缺了这三行不会编译报错 —— 槽位有静默默认值，但设置与事件全断。
            .retrySettings(retrySettings)
            .retryAborted(retryAborted)
            .retryObserver(retryObserver)
            // B223（原 docs/47）：压缩设置晚读口 —— 引擎每次决策按 lane 当前模型
            // 回调本 resolver（pi getCompactionSettings(this.model) 四调用点）；
            // setModel/modelOverrides/设置文件改动下次决策即生效，fork 不串模型。
            // 不接线 ⇒ 自动压缩恒关（B223 原状）；接了线 ⇒ pi 的「默认开」
            // 由 settings 的 ?? true 语义在 resolver 内实现。
            .compactionResolver(m -> settings.accessors().getCompactionSettings(m))
            // B128：settings.thinkingBudgets（pi sdk.ts:444 new Agent({thinkingBudgets})）；
            // 缺席为 null，ai 侧按默认表合并。
            .thinkingBudgets(settings.accessors().getThinkingBudgets())
            // B64（原 docs/24）：AGENTS.md/CLAUDE.md 发现；-nc 关闭。
            // 晚读口：每次 run 重新遍历（与 pi 同），不缓存。
            .contextFiles(() -> args.noContextFiles()
                ? List.of()
                : ContextFileDiscovery.discover(
                    Path.of(System.getProperty("user.dir")),
                    FileSettingsStorage.defaultAgentDir()))
            .build());
        harnessRef.set(harness);
        var agentSession = new AgentSession(
            harness, services, args,
            args.name() != null ? args.name() : "session");
        sessionRef.set(agentSession);
        agentSession.persistentRepository = handle;
        agentSession.setScopedModels(scope.models());
        agentSession.modelCatalog = catalog;
        // B1（map #24/#25）：分支摘要生成器 —— 与压缩同源的 StreamFn/retry 环，
        // reason 传 "branchSummary"（重试事件线已预留该源，docs/09 §7.2#6）。
        agentSession.modelResolver = models;
        agentSession.branchSummarization = new BranchSummarization(
            new LlmSummaryGenerator(recordingStreamFn,
                () -> agentSession.harness.getModel(),
                retrySettings, retryAborted, retryObserver,
                () -> models.maxOutputTokens(agentSession.harness.getModel()),
                () -> agentSession.harness.getThinkingLevel(),
                models::supportsThinking));
        loadExtensions(args, services, harness, ExtensionUI.noop(), agentSession, startInfo);
        return agentSession;
    }

    /**
     * observer 的 reason 字符串（pi 的字面量 {@code "manual"}/{@code "threshold"}/
     * {@code "overflow"}）→ 会话事件的枚举。未知值归 OVERFLOW 是防御：observer 的
     * 生产点只有这三个字面量（agent-core 的压缩三路）。
     */
    private static AgentSessionEvent.CompactionReason compactionReasonOf(String reason) {
        return switch (reason) {
            case "manual" -> AgentSessionEvent.CompactionReason.MANUAL;
            case "threshold" -> AgentSessionEvent.CompactionReason.THRESHOLD;
            default -> AgentSessionEvent.CompactionReason.OVERFLOW;
        };
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

    /**
     * 设置思考级别，**变化时**广播 {@link AgentSessionEvent.ThinkingLevelChanged}
     * （pi {@code agent-session.ts:1934-1962} 的 {@code setThinkingLevel}）。
     *
     * <p>⚠️ 包H5 步8：此前这个事件<b>零生产者</b> —— 声明了、被 {@code JsonEventMapper}
     * 消费了，但没人发。生产调用点（RPC 的 {@code set_thinking_level} ／
     * {@code cycle_thinking_level}、Web 的 {@code SetThinkingLevel}、{@code processPrompt}）
     * 原先都直呼 {@code harness().setThinkingLevel(...)}，绕过了会话事件流。</p>
     *
     * <p>pi 的 {@code isChanging} 门：级别没变就<b>不发</b>（也不写 transcript）。</p>
     *
     * <p>⚠️ pi 还会在这里把级别<b>夹到模型可用集</b>（{@code clampThinkingLevel}）并落
     * transcript。夹取在 pi-java 由车道侧的 {@code AnthropicThinking.resolve} 按模型能力
     * 处理（{@code 原 docs/46 §3-D2} 的范围裁决）；transcript 落盘属另一条线，本包不做。</p>
     */
    public ModelThinkingLevel setThinkingLevel(ModelThinkingLevel level) {
        var previous = harness.getThinkingLevel();
        harness.setThinkingLevel(level);
        if (!java.util.Objects.equals(previous, level)) {
            emitSessionEvent(new AgentSessionEvent.ThinkingLevelChanged(level));
        }
        return level;
    }

    /** 装配期写入 scope（B137）；不可变拷贝。 */
    void setScopedModels(List<com.pijava.ai.model.ScopedModel> models) {
        this.scopedModels = List.copyOf(models);
    }

    /** 当前 scope（测试/RPC 只读面）。 */
    public List<com.pijava.ai.model.ScopedModel> scopedModels() {
        return scopedModels;
    }

    /**
     * B85（批 9，docs/18 §4.5）：工具执行期的图片入模策略，经
     * {@link com.pijava.agent.tool.ToolContext#withImagePolicy} 晚绑定。
     *
     * <ul>
     *   <li>{@code autoResize}：{@code settings.images.autoResize ?? true}（pi settings-manager
     *       :1298-1300），每次现读，设置文件改动下次工具执行即生效；</li>
     *   <li>{@code resize}：当前车道模型的 {@code inputLimits.images.resize}，缺席 null
     *       （引擎走 2000/2000/4.5MiB/80 默认档，等同 pi 无模型档位时）；</li>
     *   <li>{@code supportsImages}：命中目录读 {@link com.pijava.ai.catalog.ModelInfo#supportsImageInput()}
     *       （未知按支持的既有裁决），未编目模型 ⇒ true。</li>
     * </ul>
     */
    com.pijava.agent.tool.ImagePolicy currentImagePolicy() {
        return ImagePolicies.resolve(
            services().settings().effective(), modelCatalog, harness.getModel());
    }

    /**
     * Ctrl+P / {@code cycle_model}（pi agent-session.ts:2519-2560）：scope 非空在其间
     * 正向轮转，空 scope 在全目录轮转；≤1/无变化不动。切到带显式级别 scope 条目的
     * 模型时，显式级别覆盖当前思考档；无显式级别则继承（pi undefined 语义）。
     */
    public void cycleModelForward() {
        var current = harness.getModel();
        var next = ScopedModelCycler.next(current, scopedModels, modelCatalog);
        if (next.equals(current)) {
            return;
        }
        harness.setModel(next);
        ScopedModelCycler.levelFor(next, scopedModels)
            .ifPresent(level -> setThinkingLevel(
                com.pijava.coding.agent.cli.ThinkingLevels.parse(level)));
    }

    /** The assembled services. */
    public SessionServices services() {
        return services;
    }

    /** Session display name. */
    public String sessionName() {
        return name;
    }

    /**
     * Rename the session ({@code /name})。
     *
     * <p>pi {@code agent-session.ts:3942-3947}：落盘 → 读回规范化名 → 发事件。
     * 规范化对位 {@code session-manager.ts:1305}（换行消毒 + trim）；空名 ⇒ 清除
     * （:1326-1328，≙ pi 的 undefined）。事件载荷是**规范化后的名字**，不是入参
     * （3686/5996 回归对位）；扩展路 emit 随扩展面包（docs/09 §1.2#3）。</p>
     */
    public void setSessionName(String newName) {
        String normalized = newName == null ? null
            : newName.replaceAll("[\\r\\n]+", " ").trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        this.name = normalized;
        if (session != null) {
            // appendSessionInfo 对位（PiV3Wire 已映射 session_info 线格式）。
            session.setName(normalized);
        }
        emitSessionEvent(new AgentSessionEvent.SessionInfoChanged(normalized));
    }

    /** 会话持久化 id（pi {@code AgentSession.sessionId}）；in-memory 会话回退显示名。 */
    public String sessionId() {
        return session == null ? name : session.getMetadata().id();
    }

    /**
     * 是否有压缩在飞（pi {@code AgentSession.isCompacting}，{@code agent-session.ts:983-990}）。
     *
     * <p>pi 读三个专用中止控制器；pi-java 的等价窗口由压缩的两个入口置/清，
     * 见 {@link AgentHarness#isCompacting(String)}。消费者：RPC {@code get_state}
     * 与 {@code InteractiveMode} 的提交门（A5，{@code 原 docs/25}）。</p>
     */
    public boolean isCompacting() {
        return harness.isCompacting(laneName());
    }

    /**
     * 是否有 agent run 在飞（A5，{@code 原 docs/25}；pi {@code isStreaming}，
     * {@code agent-session.ts:1430-1432}）。{@code InteractiveMode} 提交门消费者。
     */
    public boolean isRunning() {
        return harness.isRunning(laneName());
    }

    /**
     * 会话的 JSONL 落盘路径（pi {@code AgentSession.sessionFile}，{@code :1008-1010}）。
     *
     * <p>pi 的类型是 {@code string | undefined} —— 非文件后端（sqlite / in-memory /
     * {@code --no-session}）取不到路径，回 {@code null} ≙ undefined，
     * 线格式上**省略该键**（{@code 原 docs/31 §8.37.4}）。</p>
     */
    public String sessionFile() {
        var metadata = session == null ? null : session.getMetadata();
        return metadata instanceof JsonlSessionMetadata jsonl ? jsonl.path().toString() : null;
    }

    /**
     * 待处理消息数 = steer 队列 + followUp 队列（pi {@code pendingMessageCount}，
     * {@code :1619-1621}）。
     *
     * <p><b>不含</b> nextRun 队列 —— pi 的 {@code _steeringMessages.length +
     * _followUpMessages.length} 只数那两个数组。队列的只读视图由
     * {@link com.pijava.agent.harness.LaneSnapshot#queues()} 给出，无需新增访问器。</p>
     */
    public int pendingMessageCount() {
        var queues = harness.snapshot(laneName()).queues();
        return queues.steer().size() + queues.followUp().size();
    }

    /** The CLI arguments this session was assembled from ({@code /new}). */
    public Args sessionArgs() {
        return args;
    }

    /** 扩展贡献的主题候选（resources_discover themePaths）；无则空列表。 */
    public List<Path> themePaths() {
        return themePaths;
    }

    /**
     * 会话车道的名字 —— 恒为 {@link AgentHarness#DEFAULT_LANE}（{@code 原 docs/31 §4.3}）。
     *
     * <p>会话不再有「当前车道」这个可变字段：运行时多车道容器已删除，分支是**新会话**
     * （每个会话持有自己的 harness）。名字由 harness 给出。</p>
     */
    public String laneName() {
        return harness.laneName();
    }

    /** Approximate transcript size for session listings. */
    public long entryCount() {
        return harness.snapshot(laneName()).transcript().size();
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
        return harness.snapshot(laneName()).transcript();
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
        // docs/15：pi :1991-2003 —— input 事件在 skill/模板展开**之前**
        // （pi 注释逐字："Emit input event for extension interception (before
        // skill/template expansion)"）；handled ⇒ 不展开、不排队、不驱动。
        String inputText = prompt;
        if (extensionFirer != null) {
            var outcome = extensionFirer.input(prompt,
                config.source() == null ? "interactive" : config.source(),
                config.streamingBehavior() == null ? null
                    : config.streamingBehavior().wireName());
            if (outcome.handled()) {
                return SessionResult.handled();
            }
            inputText = outcome.text();
        }
        // pi :2008-2009（prompt 主路径）与 :2191-2192（队列路径同序）：先 /skill: 后模板。
        var expandedPrompt = expandUserPrompt(inputText);
        // A5（原 docs/25）：路由门在任何副作用之前 —— 压缩中/运行中未指定
        // streamingBehavior 的 prompt 在调用线程同步抛出（pi 同形状，
        // agent-session.ts:1939-1979）；运行中显式 steer/followUp 仅排队。
        var verdict = PROMPT_ROUTER.route(
            harness.isCompacting(laneName()),
            harness.isRunning(laneName()),
            config.streamingBehavior());
        if (verdict == PromptRouter.Verdict.QUEUE_STEER) {
            harness.steer(laneName(), expandedPrompt);
            // B34（原 docs/26）：pi _queueSteer 排队后发 queue_update（:2193）。
            emitQueueUpdate();
            return SessionResult.queued();
        }
        if (verdict == PromptRouter.Verdict.QUEUE_FOLLOW_UP) {
            harness.followUp(laneName(), expandedPrompt);
            emitQueueUpdate();
            return SessionResult.queued();
        }
        if (config.systemPrompt() != null) {
            harness.setSystemPrompt(config.systemPrompt());
        }
        if (config.thinkingLevel() != null) {
            setThinkingLevel(config.thinkingLevel());
        }
        // docs/15：pi :2061-2110 —— before_agent_start（仅非流式驱动路；流式排队路
        // 在上面路由门已返回）。systemPrompt 覆盖是 harness 全局态（窄差 D11）；
        // result.message 落盘序为 custom 条目先于用户消息（窄差 D19）。
        if (extensionFirer != null) {
            var start = extensionFirer.beforeAgentStart(expandedPrompt,
                harness::getSystemPrompt);
            for (var draft : start.messages()) {
                appendCustomDraft(draft);
            }
            if (start.systemPrompt() != null) {
                harness.setSystemPrompt(start.systemPrompt());
            }
        }

        var queue = new LinkedBlockingQueue<Optional<StreamEvent>>();
        var entriesFuture = new CompletableFuture<List<Entry>>();
        var statusFuture = new CompletableFuture<RunStatus>();

        Thread.startVirtualThread(() -> SessionRunner.drive(
            this, expandedPrompt, queue, entriesFuture, statusFuture,
            streamObserver, entryObserver));

        Stream<StreamEvent> stream = Stream.generate(() -> {
            try {
                return queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.<StreamEvent>empty();
            }
        }).takeWhile(Optional::isPresent).map(Optional::get);
        return new SessionResult(stream, entriesFuture, statusFuture, "started");
    }

    /** Convenience overload with default prompt config. */
    public SessionResult processPrompt(String prompt) {
        return processPrompt(prompt, PromptConfig.defaults());
    }

    /**
     * pi {@code abortCompaction()}（agent-session.ts:2914-2917，#98/B36）：
     * 中止在飞压缩（无在飞 ⇒ no-op）。
     */
    public void abortCompaction() {
        harness.abortCompaction(laneName());
    }

    /** Abort the current run (cross-thread safe via the harness AbortSignal). */
    public void abort() {
        // pi :2438-2440：abort() 内含 abortCompaction()＋abortBranchSummary()。
        abortCompaction();
        abortBranchSummary();
        // pi abort()（:1639-1646）第一步就是 abortRetry() —— 若正卡在 ① 的
        // 退避睡眠里，先把它掐掉（"Retry cancelled"），再中止 run 本身。
        abortRetry();
        // pi :2434-2436：abort 请求旗置位（pi 有 _isAgentRunActive 门；java 侧旗在
        // 驱动起手清零，空转期置位无害）。settled 事件的 aborted 载荷读它。
        agentRunAbortRequested.set(true);
        harness.abort(laneName());
    }

    /** Queue a follow-up message (processed when the current run finishes). */
    public String followUp(String prompt) {
        // pi :2191-2192（_queueUserInput 队列路径）：先 /skill: 后模板。
        var id = harness.followUp(laneName(), expandUserPrompt(prompt));
        // B34（原 docs/26）：pi followUp 入队后发 queue_update（agent-session.ts:2210）。
        emitQueueUpdate();
        return id;
    }

    /**
     * pi {@code AgentSession.compact(customInstructions)}（agent-session.ts:2898-2907，
     * B223/{@code 原 docs/47}）：手动压缩的预算按<b>当前模型</b>现解析
     * （modelOverrides 对手动路同样生效）；enabled 不门手动路
     * （pi 手动 compact 不检查 enabled，关自动压缩时 /compact 仍可用）。
     */
    public com.pijava.agent.compaction.CompactionResult compact(String customInstructions) {
        abortAndWaitForIdle();
        var resolved = services.settings().accessors()
            .getCompactionSettings(harness.getModel());
        return harness.compact(laneName(), resolved, customInstructions);
    }

    /** Trigger a manual context compaction without customInstructions ({@code /compact}). */
    public com.pijava.agent.compaction.CompactionResult compact(CompactionSettings settings) {
        return compact(settings, null);
    }

    /**
     * Trigger a manual context compaction with customInstructions (B174,
     * {@code docs/23}；pi {@code AgentSession.compact(customInstructions)}).
     */
    public com.pijava.agent.compaction.CompactionResult compact(
            CompactionSettings settings, String customInstructions) {
        abortAndWaitForIdle();
        return harness.compact(laneName(), settings, customInstructions);
    }

    // ── navigateTree（pi agent-session.ts:3964-4156，B1，map #25）──────

    /** pi {@code navigateTree} 的返回：取消旗 + 编辑器回填文本 + 摘要 entry（可无）。 */
    public record TreeNavigation(boolean cancelled, String editorText,
                                 Entry.BranchSummary summaryEntry) {}

    /**
     * pi {@code navigateTree}（agent-session.ts:3964-4156）核心流（B1，map #25）。
     *
     * <p>双门错文逐字（:3977-3982）；目标 = 当前叶 ⇒ 不动；user/custom_message 目标
     * ⇒ 新叶 = 父 ＋ 回填 editorText；有摘要 ⇒ {@code branchWithSummary}，无摘要到根
     * ⇒ {@code resetLeaf}，否则 {@code branch}（moveLane）；label 贴摘要 entry 或
     * targetId（:4118-4126）。{@code session_before_tree}/{@code session_tree} 扩展
     * 事件与 TUI 三选/Esc 随 B245（docs/09 §13.5）。</p>
     */
    public TreeNavigation navigateTree(String targetId, boolean summarize,
                                       String customInstructions, boolean replaceInstructions,
                                       String label) {
        if (isRunning()) {
            throw new IllegalStateException(
                "Wait for the current response to finish before navigating the session tree.");
        }
        if (isCompacting()) {
            throw new IllegalStateException("Wait for the current compaction or tree navigation "
                + "to finish before navigating the session tree.");
        }
        if (session == null) {
            throw new IllegalStateException("Tree navigation requires a persistent session");
        }
        var tree = session.view(laneName());
        String oldLeafId = tree.getLeafId();
        if (targetId.equals(oldLeafId)) {
            return new TreeNavigation(false, null, null); // pi :3989-3991
        }
        Entry target = tree.getEntry(targetId);
        if (target == null) {
            throw new IllegalStateException("Entry " + targetId + " not found");
        }

        branchSummaryAbort = new com.pijava.agent.harness.CompactionAbortToken(); // pi :4018
        try {
            String summaryText = null;
            com.pijava.ai.Usage summaryUsage = null;
            java.util.Map<String, Object> summaryDetails = null;
            if (summarize) {
                var collected = BranchSummarization.collectEntries(oldLeafId, targetId, session);
                if (!collected.entries().isEmpty()) {
                    var model = harness.getModel();
                    int contextWindow = modelResolver.contextWindow(model);
                    if (contextWindow <= 0) {
                        contextWindow = 128_000; // pi :306 `model.contextWindow || 128000`
                    }
                    var outcome = branchSummarization.generate(collected.entries(),
                        new BranchSummarization.Options(model, contextWindow,
                            modelResolver.maxOutputTokens(model),
                            services.settings().accessors().getBranchSummaryReserveTokens(),
                            customInstructions, replaceInstructions));
                    switch (outcome) {
                        case BranchSummarization.Outcome.Aborted a -> {
                            return new TreeNavigation(true, null, null); // pi cancelled:true
                        }
                        case BranchSummarization.Outcome.Failed f ->
                            throw new IllegalStateException(f.error());
                        case BranchSummarization.Outcome.Summary s -> {
                            summaryText = s.text();
                            summaryUsage = s.usage();
                            summaryDetails = BranchSummarization.detailsOf(
                                s.readFiles(), s.modifiedFiles());
                        }
                    }
                }
            }

            // 新叶按目标类型（pi :4094-4103 逐字序）：user 消息/custom_message ⇒
            // 叶=父＋回填 editorText；其他 ⇒ 叶=targetId。
            String newLeafId;
            String editorText = null;
            if (target instanceof Entry.Message m && "user".equals(m.message().role())) {
                newLeafId = m.parentId();
                editorText = textOf(m.message().content());
            } else if (target instanceof Entry.CustomMessage cm) {
                newLeafId = cm.parentId();
                editorText = textOf(cm.content().toBlocks());
            } else {
                newLeafId = targetId;
            }

            Entry.BranchSummary summaryEntry = null;
            if (summaryText != null) {
                // pi :4107-4115：摘要挂在目标位置（可在根），fromHook=false（pi 生成）。
                summaryEntry = session.branchWithSummary(laneName(), newLeafId, oldLeafId,
                    summaryText, summaryDetails, Boolean.FALSE, summaryUsage);
                if (label != null) {
                    session.setLabel(summaryEntry.id(), label);
                }
            } else if (newLeafId == null) {
                session.resetLeaf(laneName());
            } else {
                session.moveLane(laneName(), newLeafId);
            }
            if (label != null && summaryText == null) {
                session.setLabel(targetId, label);
            }

            // pi :4136-4137（_refreshFinalizedContext/_restoreToolsFromTranscript）对位：
            // 从新叶重建车道上下文；已落盘 id 重标记，防 persistPending 复写。
            String leafId = summaryEntry != null ? summaryEntry.id() : newLeafId;
            List<Entry> entries = session.findEntries(
                new EntryQuery(null, null, com.pijava.agent.session.EntryOrder.OLDEST_FIRST,
                    null, null));
            persistedEntryIds().clear();
            entries.forEach(e -> persistedEntryIds().add(e.id()));
            // replaceTranscript（非 seedTranscript）：导航是整体换上下文，
            // seed 的「首填才生效」门会在已有转录上静默不播。
            harness.replaceTranscript(laneName(),
                leafId == null ? List.of()
                    : ContextEntries.contextEntries(ContextEntries.pathToLeaf(entries, leafId)));
            // pi :4138（#77）：导航后按新叶最新系统消息的 toolsAdded 恢复激活装配。
            harness.restoreToolsFromTranscript(laneName());
            return new TreeNavigation(false, editorText, summaryEntry);
        } finally {
            branchSummaryAbort = null; // pi :4155
        }
    }

    /** pi {@code abortBranchSummary()}（agent-session.ts:2922-2924）。 */
    public void abortBranchSummary() {
        var token = branchSummaryAbort;
        if (token != null) {
            token.abort();
        }
    }

    /** 提取消息纯文本（pi {@code contentText(..., "")}：text 块拼接）。 */
    private static String textOf(List<com.pijava.ai.message.ContentBlock> content) {
        var parts = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof com.pijava.ai.message.ContentBlock.TextContent t) {
                parts.add(t.text());
            }
        }
        return String.join("\n", parts);
    }

    /**
     * #17（pi {@code agent-session.ts:2764-2765}）：手动压缩开头即 {@code await this.abort()}
     * —— 中止在飞 run 并等它落地，压缩读到的转录不含半截消息。{@link #abort()} 本身保持
     * 非阻塞（UI Esc 同一条路），等待只加在压缩路，净效果与 pi 的 {@code await abort()}
     * 相同。pi 的 abort() 还中止在飞压缩/分支摘要 —— abortCompaction 已随
     * #98/B36 落地（本类 {@link #abort()} 首步），分支摘要随 B1（docs/09 §7）。
     * 自动压缩走引擎内部直路，不经这里。
     */
    private void abortAndWaitForIdle() {
        abort();
        harness.waitForIdle(laneName());
    }

    /** Queue a steering message (injected into the current run's next round). */
    public String steer(String prompt) {
        // pi :2191-2192（_queueUserInput 队列路径）：先 /skill: 后模板。
        var id = harness.steer(laneName(), expandUserPrompt(prompt));
        emitQueueUpdate();
        return id;
    }

    /**
     * pi 展开序（:2008-2009 / :2191-2192）：先 {@code /skill:} 后模板，两步都是
     * 「未命中 ⇒ 原文透传」。
     */
    private String expandUserPrompt(String prompt) {
        var expanded = com.pijava.coding.agent.skill.SkillCommandExpansion.expand(
            prompt, skillsByName());
        return com.pijava.coding.agent.prompt.PromptTemplateExpansion.expand(
            expanded, services.promptTemplates());
    }

    private Map<String, com.pijava.agent.skill.Skill> skillsByName() {
        var map = new LinkedHashMap<String, com.pijava.agent.skill.Skill>();
        for (var skill : harness.skillManager().all()) {
            map.put(skill.name(), skill);
        }
        return map;
    }

    /** The most recent assistant text (for {@code /copy}). */
    public String lastAssistantText() {
        var transcript = harness.snapshot(laneName()).transcript();
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

    /**
     * 交互层状态文本广播（B175，原 docs/26 §3.4）—— 发射
     * {@link AgentSessionEvent.FlashStatus} 方言事件，RPC 侧忽略。
     */
    public void flashStatus(String message) {
        emitSessionEvent(new AgentSessionEvent.FlashStatus(message));
    }

    /**
     * 发射 queue_update：引擎队列文本聚合 {@code InteractiveMode} 的缓冲内容
     * （B34/B175，原 docs/26 §3.3；pi {@code _emitQueueUpdate}，
     * agent-session.ts:1017-1023）。
     *
     * @param bufferedSteering 缓冲中待 steer 的文本（压缩窗口内）；无则空列表
     * @param bufferedFollowUp 缓冲中待 followUp 的文本；无则空列表
     */
    public void emitQueueUpdate(List<String> bufferedSteering,
                                List<String> bufferedFollowUp) {
        var steering = new ArrayList<String>(harness.queuedSteering(laneName()));
        steering.addAll(bufferedSteering);
        var followUp = new ArrayList<String>(harness.queuedFollowUp(laneName()));
        followUp.addAll(bufferedFollowUp);
        emitSessionEvent(new AgentSessionEvent.QueueUpdate(
            List.copyOf(steering), List.copyOf(followUp)));
    }

    /** 引擎队列无缓冲聚合时的便捷口。 */
    public void emitQueueUpdate() {
        emitQueueUpdate(List.of(), List.of());
    }

    /**
     * 清空两个引擎队列并返回被清掉的文本（B34，原 docs/26；pi {@code clearQueue}，
     * agent-session.ts:2355-2364），随后发射 queue_update{空}。
     */
    public ClearedQueue clearQueue() {
        var steering = harness.queuedSteering(laneName());
        var followUp = harness.queuedFollowUp(laneName());
        harness.cancelQueued(laneName(), "steer");
        harness.cancelQueued(laneName(), "followUp");
        emitSessionEvent(new AgentSessionEvent.QueueUpdate(List.of(), List.of()));
        return new ClearedQueue(steering, followUp);
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
     * 列出 {@code cwd} 下的持久化会话摘要。
     *
     * <p>#79/B55：JSONL 后端走富列表（pi {@code SessionManager.list} —
     * {@code buildSessionInfo} 全文件逐行读，不再逐会话 writer lease）；
     * 其它后端留旧路（逐会话 open，失败降级元数据摘要）。</p>
     */
    public List<SessionSummary> listSessionsSummary(String cwd) {
        if (persistentRepository == null) {
            return List.of();
        }
        String dir = cwd == null || cwd.isBlank() ? System.getProperty("user.dir") : cwd;
        if (persistentRepository.repository()
                instanceof com.pijava.agent.session.jsonl.JsonlSessionRepository repo) {
            return repo.listRich(dir, null, null).stream()
                .map(info -> new SessionSummary(info.id(), info.path(),
                    info.name() == null ? "" : info.name(), info.cwd(),
                    java.time.Instant.ofEpochMilli(info.createdMs()), info.modifiedMs(),
                    info.messageCount(), info.firstMessage()))
                .toList();
        }
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
            return persistentRepository.latest(System.getProperty("user.dir")).map(meta -> {
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

    /** docs/15：{@link #switchTo} 的结局（pi 的 {@code {cancelled}} 对位）。 */
    public enum SwitchOutcome { SWITCHED, CANCELLED, NOT_FOUND }

    /** docs/15：切换结局＋切换后的会话（持久路原地 attach ⇒ 即本会话）。 */
    public record SwitchResult(SwitchOutcome outcome, AgentSession session) {}

    /**
     * docs/15（B249④/B250）：pi {@code AgentSessionRuntime.switchSession}/
     * {@code importFromJsonl}（agent-session-runtime.ts:194-214, 355-397）的序列集中点——
     * {@code session_before_switch}（cancel ⇒ 旧会话不动）→ 预检目标存在（pi 先开
     * SessionManager 再 teardown）→ {@code session_shutdown{resume, target}} → 原地
     * attach → {@code session_start{resume, previousSessionFile}}。原地换装下
     * shutdown/start 在同一条 bus 上发射（pi 是旧/新两条 runner，窄差 D23）。
     */
    public SwitchResult switchTo(String idOrPrefix) {
        if (extensionFirer != null
                && extensionFirer.sessionBeforeSwitch("resume", idOrPrefix)) {
            return new SwitchResult(SwitchOutcome.CANCELLED, this);
        }
        boolean exists = persistentRepository != null
            ? persistentRepository.find(idOrPrefix).isPresent()
            : repository.find(idOrPrefix).isPresent();
        if (!exists) {
            return new SwitchResult(SwitchOutcome.NOT_FOUND, this);
        }
        var previousId = sessionId();
        fireSessionShutdown("resume", idOrPrefix);
        var found = findSession(idOrPrefix).orElseThrow();
        if (extensionFirer != null) {
            extensionFirer.sessionStart("resume", previousId);
        }
        return new SwitchResult(SwitchOutcome.SWITCHED, found);
    }

    /** Export the current session to a JSONL file ({@code /export}). */
    public void exportJsonl(java.nio.file.Path target) {
        SessionPersistence.exportJsonl(this, target);
    }

    /** Import a JSONL file into a new persistent session ({@code /import}). */
    public AgentSession importJsonl(java.nio.file.Path source) {
        return SessionPersistence.importJsonl(this, source);
    }

    /**
     * Create a forked copy ({@code /fork /clone --fork}）：整棵树都复制（pi {@code ForkOptions.Tree}）。
     *
     * <p>docs/15（B249④）：先发射 {@code session_before_fork{entryId:当前叶,
     * position:"at"}}（整枝拷贝的映射，窄差 D17）——cancel ⇒ {@code Optional.empty}
     * （pi 的 {@code {cancelled:true}}）；建成后旧侧发射
     * {@code session_shutdown{fork, 新会话 id}}（pi teardownCurrent 对位）。</p>
     */
    public Optional<AgentSession> forkCopy(String branchName) {
        var leafId = harness.snapshot(laneName()).leafId();
        if (extensionFirer != null && extensionFirer.sessionBeforeFork(leafId, "at")) {
            return Optional.empty();
        }
        var copy = forkCopyInternal(branchName);
        fireSessionShutdown("fork", copy.sessionId());
        return Optional.of(copy);
    }

    private AgentSession forkCopyInternal(String branchName) {
        if (persistentRepository != null && session != null) {
            var metadata = session.getMetadata();
            var forked = persistentRepository.fork(metadata, new ForkOptions.Tree(),
                System.getProperty("user.dir"));
            var copy = new AgentSession(harness.fork(), services, args, branchName);
            copy.persistentRepository = persistentRepository;
            copy.session = forked;
            SessionPersistence.attach(copy, persistentRepository, forked.getMetadata());
            copy.name = branchName;
            return copy;
        }
        // 内存态：没有独立会话文件可 fork，于是**给它一个自己的 harness**再从本会话日志播种
        // —— 这正是「分支归会话层」的落点（原 docs/31 §4.3）。此前这里靠共享父 harness + 新建
        // lane 假装隔离，而那条新 lane 是**空的**：分支会话拿不到任何历史。
        return forkInMemory(branchName, entries());
    }

    /**
     * Fork a new session branching from a specific entry（RPC {@code fork}/{@code clone}）。
     *
     * <p>持久化路径按 pi {@code ForkOptions.Branch} 在 entry **前**分支；内存路径同样在
     * entry 前截断，再播种给新 harness —— 与 {@code selectBranchFork} 的
     * {@code position: "before"} 一致（{@code harness/session/fork-policy.ts:25-27}）。</p>
     */
    public Optional<AgentSession> forkFromEntry(String entryId) {
        if (extensionFirer != null
                && extensionFirer.sessionBeforeFork(entryId, "before")) {
            return Optional.empty();
        }
        var copy = forkFromEntryInternal(entryId);
        fireSessionShutdown("fork", copy.sessionId());
        return Optional.of(copy);
    }

    private AgentSession forkFromEntryInternal(String entryId) {
        String branchName = name + "-fork";
        if (persistentRepository != null && session != null) {
            var metadata = session.getMetadata();
            var forked = persistentRepository.fork(metadata,
                new ForkOptions.Branch(entryId, ForkOptions.Branch.Position.BEFORE),
                System.getProperty("user.dir"));
            var copy = new AgentSession(harness.fork(), services, args, branchName);
            copy.persistentRepository = persistentRepository;
            copy.session = forked;
            SessionPersistence.attach(copy, persistentRepository, forked.getMetadata());
            copy.name = branchName;
            return copy;
        }
        return forkInMemory(branchName, entriesBefore(entryId));
    }

    /** 本会话车道上的 entry 日志（副本）。 */
    private List<Entry> entries() {
        return harness.snapshot(laneName()).transcript();
    }

    /**
     * 分支点**之前**的那条路径（pi {@code position: "before"}：请求的 entry 本身不入选）。
     *
     * <p>找不到该 entry 即抛 —— pi 的 {@code selectBranchFork} 同样拒绝不在分支上的 entry
     * （{@code "Fork entry … is not on source branch"}），静默给一个空会话更糟。</p>
     */
    private List<Entry> entriesBefore(String entryId) {
        var all = entries();
        var target = all.stream().filter(e -> e.id().equals(entryId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Fork entry " + entryId + " is not on this session's branch"));
        return ContextEntries.pathToLeaf(all, target.parentId());
    }

    /**
     * 内存态分支：新会话持**自己的** harness，日志从父会话播种。
     *
     * <p>播种走 {@code seedTranscript}，它同时把 entry 日志与消息工作副本一起立起来
     * （{@code 原 docs/31 §4.2} 的重建点）。</p>
     */
    private AgentSession forkInMemory(String branchName, List<Entry> seedEntries) {
        var forkedHarness = harness.fork();
        forkedHarness.seedTranscript(AgentHarness.DEFAULT_LANE, seedEntries);
        var forked = new AgentSession(forkedHarness, services, args, branchName);
        forked.repository = repository;
        repository.create(forked);
        return forked;
    }

    /** 会话级 bash 执行（RPC {@code bash}）：经 harness shell 运行并发射一条
     *  {@link AgentSessionEvent.BashExecutionUpdate}。v1 阻塞执行，不增量流式。 */
    public ShellResult executeBash(String id, String command, boolean excludeFromContext) {
        // docs/15：pi emitUserBash 拦截（rpc-mode.ts:561-580 同位）——{result} 臂
        // 直接落盘返回、不执行；处理器异常沿调用方错误链（不得回落本地执行，pi
        // interactive-mode.ts:6994-6999 注释逐字）。{operations} 臂不移植（窄差 D10）。
        if (extensionFirer != null) {
            var replaced = extensionFirer.userBash(command, excludeFromContext,
                harness.toolContext().cwd());
            if (replaced.isPresent()) {
                var extensionResult = replaced.get();
                recordBashResult(command, extensionResult, excludeFromContext);
                return extensionResult;
            }
        }
        var toolContext = harness.toolContext();
        var signal = AbortSignal.create();
        bashAbortSignal = signal;
        try {
            var result = toolContext.shell().execute(command, new ShellOptions(
                toolContext.cwd(), toolContext.env(), true,
                java.util.OptionalLong.empty(), signal));
            eventHub.emit(new AgentSessionEvent.BashExecutionUpdate(id, result.output()));
            // pi executeBash 同位调用（agent-session.ts:3878-3899）：结果落会话。
            recordBashResult(command, result, excludeFromContext);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Bash command failed: " + command, e);
        }
    }

    /**
     * docs/15（boundary 提交臂，pi {@code _commitBoundaryDrafts} :1024-1028 对位）：
     * 追加 entry ⇒ 广播 {@link AgentSessionEvent.EntryAppended} ⇒ 落盘。
     * {@code BoundaryBridge} 专用（包内可见）。
     */
    void appendBoundaryEntry(Entry entry) {
        harness.appendLaneEntry(laneName(), entry);
        emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
        if (session != null) {
            SessionPersistence.persistPending(this, session, laneName());
        }
    }

    /**
     * docs/15（before_agent_start 的 result.message 落盘点，pi :2092-2104 对位）：
     * 追加 custom message 条目。持久会话走 {@code SessionTree.appendCustomMessageEntry}
     * （含落盘）；无持久会话时退化为 harness 追加（内存态）。
     */
    private void appendCustomDraft(
            com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft draft) {
        var content = draft.content() != null ? draft.content()
            : new com.pijava.agent.entry.CustomMessageContent.Text("");
        if (session != null) {
            session.appendCustomMessageEntry(draft.customType(), content, draft.display(),
                draft.details());
            return;
        }
        harness.appendLaneEntry(laneName(), new Entry.CustomMessage(
            java.util.UUID.randomUUID().toString(), 0, null, Instant.now(),
            draft.customType(), content, draft.display(), draft.details()));
    }

    /**
     * pi {@code recordBashResult}（agent-session.ts:3878-3899）：会话级 bash 结果落会话——
     * 空闲 ⇒ 追加 bashExecution 条目＋刷新工作副本＋即刻落盘；运行中 ⇒ 暂存
     * （{@link #pendingBashMessages}），settled 时由 {@code SessionRunner} flush
     * （pi 的 {@code _pendingBashMessages}/agent_end，:421,3925-3940，
     * 保 tool_use/tool_result 次序）。
     *
     * <p>ShellResult 语义（#57 现场确认）：{@code cancelled} 引擎无字段 ⇒ 取会话侧
     * {@link #bashAborted()}；{@code truncated} 引擎恒 false（截断在工具层，会话级
     * bash 绕开）⇒ 照传、{@code fullOutputPath} 恒 null；中断兜底的 {@code exitCode=-1}
     * ⇒ {@code null} ≙ pi 的 undefined。</p>
     */
    public void recordBashResult(String command, ShellResult result, boolean excludeFromContext) {
        var entry = new Entry.BashExecution(
            java.util.UUID.randomUUID().toString(), 0, null, Instant.now(),
            command, result.output(),
            result.exitCode() >= 0 ? result.exitCode() : null,
            bashAborted(), result.truncated(), null,
            excludeFromContext ? Boolean.TRUE : null);
        if (harness.isRunning(laneName())) {
            pendingBashMessages.add(entry);
        } else {
            harness.appendLaneEntry(laneName(), entry);
            if (session != null) {
                SessionPersistence.persistPending(this, session, laneName());
            }
        }
    }

    /** pi {@code _flushPendingBashMessages} 的取出半：清空并返回暂存队列（包内可见）。 */
    List<Entry.BashExecution> drainPendingBashMessages() {
        var drained = List.copyOf(pendingBashMessages);
        pendingBashMessages.clear();
        return drained;
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

    /**
     * 启用/停用自动重试（RPC {@code set_auto_retry}）—— pi
     * {@code setAutoRetryEnabled}（{@code agent-session.ts:2988-2990}）直通
     * {@code settingsManager.setRetryEnabled}：写<b>全局</b> retry.enabled +
     * markModified + save（即时落盘，不同于其余攒到 close 的 setter）。
     */
    public void setAutoRetryEnabled(boolean enabled) {
        services.settings().accessors().setRetryEnabled(enabled);
    }

    /** 自动重试是否启用 —— pi {@code get autoRetryEnabled} = {@code getRetryEnabled()}（默认 true）。 */
    public boolean autoRetryEnabled() {
        return services.settings().accessors().getRetryEnabled();
    }

    /**
     * 中止进行中的重试退避睡眠（RPC {@code abort_retry}）。pi
     * {@code abortRetry}（:2972-2974）= {@code _retryAbortController?.abort()} ——
     * controller 只在 ① 的退避睡眠内存活，<b>窗口外调用是 no-op</b>（不会顺延
     * 取消下一次重试）。3d 起由 observer 的窗口簿记等价模拟。
     */
    public void abortRetry() {
        if (retrySleepActive) {
            retryCancel.set(true);
        }
    }

    /** 退避睡眠中引擎轮询的中止观察口。 */
    boolean retryAborted() {
        return retryCancel.get();
    }

    /** 打开退避睡眠窗口（{@code auto_retry_start} 处，≙ pi :2948 建 controller；先清残留旗）。 */
    void beginRetrySleep() {
        retryCancel.set(false);
        retrySleepActive = true;
    }

    /** 关闭退避睡眠窗口（{@code auto_retry_end} 处，≙ pi :2963 finally —— 睡完/取消都弃）。 */
    void endRetrySleep() {
        retrySleepActive = false;
        retryCancel.set(false);
    }

    /** 可供 fork 的用户消息（RPC {@code get_fork_messages}，对齐 pi
     *  {@code getUserMessagesForForking}）。 */
    public List<Entry.Message> getUserMessagesForForking() {
        return harness().snapshot(laneName()).transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .filter(e -> "user".equals(e.message().role()))
            .toList();
    }

    /** Flush settings, persist pending writes and close the harness. */
    @Override
    public void close() {
        closeWith("quit", null);
    }

    /**
     * docs/15（B250）：替换式关闭（pi {@code teardownCurrent}，
     * agent-session-runtime.ts:168-178）——先发射 {@code session_shutdown{reason,
     * targetSessionFile}}，再走原关闭流程。
     */
    public void closeForReplacement(String reason, String targetSessionFile) {
        closeWith(reason, targetSessionFile);
    }

    /**
     * docs/15（B249④）：仅发射 {@code session_shutdown}（不关闭）——/new、fork 等
     * 路径的旧会话在 java 不 close（存量，B252 在册），但扩展运行时该收到拆解通知
     * （pi 的 teardownCurrent 语义）。
     */
    public void fireSessionShutdown(String reason, String targetSessionFile) {
        if (extensionFirer != null) {
            extensionFirer.sessionShutdown(reason, targetSessionFile);
        }
    }

    private void closeWith(String reason, String targetSessionFile) {
        // pi dispose/teardownCurrent：shutdown 事件在 dispose 之前（:258-267）。
        fireSessionShutdown(reason, targetSessionFile);
        // pi dispose()（:876-885）第一步 abortRetry() —— 关窗时掐掉待完成的退避睡眠。
        abortRetry();
        if (session != null) {
            SessionPersistence.persistPending(this, session, laneName());
            session.close();
        }
        if (persistentRepository != null) {
            persistentRepository.close();
        }
        services.settings().flush();
        harness.close();
    }

    // ── Internals ────────────────────────────────────────────

    /**
     * 加载扩展（ServiceLoader + 扩展目录 JAR），--no-extensions 时跳过
     * （pi 无扩展＝runner 空表，行为等价）。
     *
     * <p>docs/14 §3.6：事件面接线 —— 总线随上下文建；引擎钩子桥**恒装**（裁决②）；
     * 宿主事件桥在装配期订阅（裁决⑤：先于模式层 TUI/RPC，与 pi「扩展先」同向）；
     * 扩展错误经 {@link AgentSessionEvent.ExtensionError} 进会话事件流（裁决⑥：
     * RPC 同管道上线，pi 的 {@code emitError} 独立 listener 对位）。</p>
     */
    private static void loadExtensions(Args args, SessionServices services,
                                       AgentHarness harness, ExtensionUI ui,
                                       AgentSession owner, SessionStartInfo startInfo) {
        if (args.noExtensions()) {
            return;
        }
        var bus = new com.pijava.coding.agent.extension.ExtensionEventBus();
        var context = new DefaultExtensionContext(services, harness.skillManager(), ui, bus);
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
        com.pijava.coding.agent.extension.bridge.EngineHookBridge.install(
            harness.hookSystem(), owner.laneName(), bus, context);
        owner.subscribe(new com.pijava.coding.agent.extension.bridge.SessionEventBridge(
            bus, context, harness.getThinkingLevel())::onEvent);
        bus.onError(err ->
            owner.emitSessionEvent(new AgentSessionEvent.ExtensionError(err)));
        // docs/15（B250）：firer 提为会话字段（close/switch/fork/prompt/bash 都要发）；
        // session_start 在桥与错误通道接好后、resources_discover 之前发射
        // （pi bindExtensions 的逐字序，agent-session.ts:3279-3281）。初始装配恒
        // "startup"（CLI resume 也是——pi 逐字）；替换式新建由 createReplacing 穿载荷。
        owner.extensionFirer = new com.pijava.coding.agent.extension.SessionEventFirer(
            bus, context);
        owner.extensionFirer.sessionStart(
            startInfo == null ? "startup" : startInfo.reason(),
            startInfo == null ? null : startInfo.previousSessionFile());
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
