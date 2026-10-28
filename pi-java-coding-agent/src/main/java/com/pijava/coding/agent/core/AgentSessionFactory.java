package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.memory.MemorySessionRepository;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionUI;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的装配/工厂簇 —— create/createWeb/
 * createReplacing 全重载、assemble×3、resumeLike 与 SessionStartInfo。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class AgentSessionFactory {

    private AgentSessionFactory() {
    }

    /**
     * 批 23（T3）：信任解析告警并入启动诊断（pi {@code projectTrustDiagnostics}
     * 对位——main.ts:773 的 warning 收集）。
     */
    static void attachTrustWarnings(AgentSession session, java.util.List<String> warnings) {
        for (var warning : warnings) {
            session.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", warning));
        }
    }

	/** Web 默认入口：恢复最近一个持久会话，仅当无会话时新建（刷新/重连不丢历史）。 */
    static AgentSession createWeb(Args args) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        // 批 23（T3）：信任七级链先于 settings 消费（pi main.ts:740-777 序对位）；
        // web 无弹窗（prompter null＝pi 无 UI 臂）。
        var trustWarnings = ProjectTrustSetup.apply(args, settings, null);
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
        session.persistentRepository(handle);
        var resolved = SessionPersistence.resolvePersistentWeb(session, args);
        attachTrustWarnings(resolved, trustWarnings);
        return resolved;
    }

    /**
     * Web 入口的**测试缝**（台账 A13）：与 {@code createWeb(Args)} 同形，但注入
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
        // 批 23（T3）：信任七级链先于 settings 消费（cwd 取 settings 存储面项目目录）。
        var trustWarnings = ProjectTrustSetup.apply(args, settings, null);
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
        session.persistentRepository(handle);
        var resolvedWeb = SessionPersistence.resolvePersistentWeb(session, args);
        attachTrustWarnings(resolvedWeb, trustWarnings);
        return resolvedWeb;
    }

    static AgentSession create(Args args) {
        return create(args, (SessionStartInfo) null);
    }

    /**
     * 批 17（T7，docs/28 §3-T7.1）：交互模式的扩展 UI 构造期注入——pi
     * {@code bindings.uiContext} 构造期传入（agent-session.ts:3260）对位：
     * 扩展在 create 内的 loadExtensions 加载，UI/mode 必须先烘进会话字段。
     *
     * @param ui   扩展 UI 实现（TUI ⇒ {@code TuiExtensionUI}；null ⇒ noop）
     * @param mode pi {@code ExtensionMode}（{@code "tui"} 等；null ⇒ "print"）
     */
    static AgentSession create(Args args, ExtensionUI ui, String mode) {
        return create(args, null, ui, mode, null);
    }

    /**
     * 批 23（docs/34 T3）：带信任弹窗缝的 TUI 入口——pi 的 interactive 启动
     * 信任提示（project-trust.ts 弹窗臂）经 {@code prompter} 注入；其余模式
     * 传 {@code null}（＝pi 无 UI 恒 false 臂）。
     */
    static AgentSession create(Args args, ExtensionUI ui, String mode,
                               ProjectTrustPrompter prompter) {
        return create(args, null, ui, mode, prompter);
    }

    private static AgentSession create(Args args, SessionStartInfo startInfo,
                                       ExtensionUI ui, String mode,
                                       ProjectTrustPrompter prompter) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        // 批 23（T3）：信任七级链先于 settings 消费（pi main.ts:740-777 序对位）。
        var trustWarnings = ProjectTrustSetup.apply(args, settings, prompter);
        var effective = settings.effective();
        var backend = effective.sessionBackend == null ? "jsonl" : effective.sessionBackend;
        var sessionsRoot = Path.of(args.sessionDir() != null ? args.sessionDir()
            : (effective.sessionDir != null ? effective.sessionDir
                : Path.of(System.getProperty("user.home"), ".pi-java", "agent", "sessions").toString()));
        var handle = "sqlite".equals(backend)
            ? PersistentSessionRepositories.sqlite(sessionsRoot)
            : PersistentSessionRepositories.jsonl(sessionsRoot);
        var session = create(args, handle, DefaultProviders.defaultProviders(effective.catalogBaseUrl),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            settings, startInfo, ui, mode);
        attachTrustWarnings(session, trustWarnings);
        return session;
    }

    /**
     * docs/15（B250）：替换式新建（/new、RPC new_session、Web newSession）——
     * 新会话装配时 {@code session_start} 带 {@code {reason:"new",
     * previousSessionFile:旧会话 id}}。旧侧关闭由调用方在拿到新会话后
     * {@code closeForReplacement}（目标 id 先知才行，窄差 D20；docs/16 起
     * 内含 shutdown{new}＋真正关闭）。
     */
    static AgentSession createReplacing(Args args, String previousSessionId) {
        return createReplacing(args, previousSessionId, null);
    }

    /**
     * 批 20（B257，pi rpc-mode.ts:433-435＋agent-session-runtime.ts:241-243）：
     * {@code parentSession} 落新会话 header（{@code null} ⇒ 不写）——与
     * {@code previousSessionId}（session_start 事件载荷）是<b>两个</b>字段。
     */
    static AgentSession createReplacing(Args args, String previousSessionId,
                                        String parentSession) {
        return create(args, new SessionStartInfo("new", previousSessionId), parentSession);
    }

    private static AgentSession create(Args args, SessionStartInfo startInfo) {
        return create(args, startInfo, null);
    }

    private static AgentSession create(Args args, SessionStartInfo startInfo,
                                       String parentSessionId) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        // 批 23（T3）：替换式新建经 cwd 缓存短路（pi projectTrustByCwd——
        // /new 不重问）；缓存未中走完整七级链（无弹窗缝——初始装配已问过）。
        var trustWarnings = ProjectTrustSetup.apply(args, settings, null);
        var effective = settings.effective();
        var backend = effective.sessionBackend == null ? "jsonl" : effective.sessionBackend;
        var sessionsRoot = Path.of(args.sessionDir() != null ? args.sessionDir()
            : (effective.sessionDir != null ? effective.sessionDir
                : Path.of(System.getProperty("user.home"), ".pi-java", "agent", "sessions").toString()));
        var handle = "sqlite".equals(backend)
            ? PersistentSessionRepositories.sqlite(sessionsRoot)
            : PersistentSessionRepositories.jsonl(sessionsRoot);
        var session = create(args, handle, DefaultProviders.defaultProviders(effective.catalogBaseUrl),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            settings, startInfo, parentSessionId);
        attachTrustWarnings(session, trustWarnings);
        return session;
    }

    /** Assemble a session against a specific in-memory repository (tests). */
    static AgentSession create(Args args, InMemorySessionRepository repository) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        var trustWarnings = ProjectTrustSetup.apply(args, settings, null);
        var effective = settings.effective();
        var session = create(args, repository, DefaultProviders.defaultProviders(effective.catalogBaseUrl),
            new ToolContext(
                System.getProperty("user.dir"),
                Map.of(),
                new DefaultShellExecutor(effective.shellPath),
                new DefaultFileSystem()),
            settings);
        attachTrustWarnings(session, trustWarnings);
        return session;
    }

    /** Create with injected providers and tool context (RPC/testing). */
    static AgentSession create(Args args, ProviderRegistry providers,
                               ToolContext toolContext) {
        return create(args, InMemorySessionRepository.create(), providers, toolContext);
    }

    /** Test entry point with injected providers and tool context. */
    static AgentSession create(Args args, InMemorySessionRepository repository,
                               ProviderRegistry providers,
                               ToolContext toolContext) {
        var settings = SettingsManager.load(args.projectTrustOverride());
        // 批 23（T3）：信任链用注入 ToolContext 的 cwd（夹具 temp 目录）。
        var trustWarnings = ProjectTrustSetup.apply(args, settings, null);
        var session = create(args, repository, providers, toolContext, settings);
        attachTrustWarnings(session, trustWarnings);
        return session;
    }
    /** 批 16（T5/T6 夹具缝）：注入 settings 的 in-memory 形态（防真 home 继承坑）。 */
    static AgentSession create(Args args, InMemorySessionRepository repository,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings) {
        var session = assemble(args, settings, providers, toolContext,
            null, new MemorySessionRepository(), resumeLike(args));
        session.inMemoryRepository(repository);
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
        // 初始装配路：--name 写回开启（pi main.ts:709 一次性语义）。
        return create(args, handle, providers, toolContext, settings, startInfo,
            ExtensionUI.noop(), "print", null, true);
    }

    /** 批 20（B257）：带 {@code parentSessionId} 的形态（RPC new_session）。 */
    private static AgentSession create(Args args,
                                       PersistentSessionRepositories.RepositoryHandle handle,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings,
                                       SessionStartInfo startInfo,
                                       String parentSessionId) {
        // 评审回补（I-2）：createReplacing 专属链——替换会话不重放 --name。
        return create(args, handle, providers, toolContext, settings, startInfo,
            ExtensionUI.noop(), "print", parentSessionId, false);
    }

    /** 批 17（T7）：带扩展 UI/mode 的持久仓装配（TUI 构造期注入路径）。 */
    private static AgentSession create(Args args,
                                       PersistentSessionRepositories.RepositoryHandle handle,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings,
                                       SessionStartInfo startInfo,
                                       ExtensionUI ui, String mode) {
        return create(args, handle, providers, toolContext, settings, startInfo, ui, mode,
            null, true);
    }

    /**
     * 批 20 评审回补（I-2）：全参＋{@code applyInitialName} 末端——createReplacing
     * 链（/new、RPC new_session）传 {@code false}：pi 的 {@code --name} 只在启动
     * 会话解析后应用一次（main.ts:709-715），替换会话不重放。
     */
    private static AgentSession create(Args args,
                                       PersistentSessionRepositories.RepositoryHandle handle,
                                       ProviderRegistry providers,
                                       ToolContext toolContext,
                                       SettingsManager settings,
                                       SessionStartInfo startInfo,
                                       ExtensionUI ui, String mode,
                                       String parentSessionId, boolean applyInitialName) {
        var session = assemble(args, settings, providers, toolContext,
            handle, handle.repository(), resumeLike(args), startInfo, ui, mode);
        session.persistentRepository(handle);
        return SessionSetup.resolveSession(session, args, parentSessionId, applyInitialName);
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
    record SessionStartInfo(String reason, String previousSessionFile) {}

    static AgentSession assemble(Args args, SettingsManager settings,
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
        return assemble(args, settings, providers, toolContext, handle, repository,
            existingSession, startInfo, ExtensionUI.noop(), "print");
    }

    /** 批 17（T7）：带扩展 UI/mode 的装配（初始装配即烘——pi bindings.uiContext 对位）。 */
    private static AgentSession assemble(Args args, SettingsManager settings,
                                         ProviderRegistry providers,
                                         ToolContext toolContext,
                                         PersistentSessionRepositories.RepositoryHandle handle,
                                         SessionRepository<?, ?, ?> repository,
                                         boolean existingSession,
                                         SessionStartInfo startInfo,
                                         ExtensionUI ui, String mode) {
        var agentSession = SessionWiring.wireSession(args, settings, providers, toolContext,
            repository, existingSession, args.name() != null ? args.name() : "session", null, null);
        agentSession.persistentRepository(handle);
        agentSession.extensions().extensionMode(mode != null ? mode : "print");
        // 初始装配立即加载扩展（pi 初始序）；fork 臂在旧会话 teardown 后另发
        //（docs/16 §3.2/§3.4）。
        ExtensionLoading.loadExtensions(args, agentSession.services(), agentSession.harness(),
            ui, agentSession, startInfo);
        return agentSession;
    }
}
