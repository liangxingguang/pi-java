package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.harness.PromptImage;
import com.pijava.agent.harness.WatchHandle;
import com.pijava.agent.session.Session;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ImagePolicy;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ScopedModel;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.session.SessionInfo;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.core.virtual.VirtualModelRegistry;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEntryRenderer;
import com.pijava.coding.agent.extension.ExtensionManager;
import com.pijava.coding.agent.extension.ExtensionMessageRenderer;
import com.pijava.coding.agent.extension.ExtensionShortcuts;
import com.pijava.coding.agent.extension.ExtensionToolInfo;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.MarkdownTransformer;
import com.pijava.agent.tool.ToolExposure;
import com.pijava.coding.agent.extension.ToolRendererRegistry;
import com.pijava.coding.agent.extension.SessionEventFirer;
import com.pijava.coding.agent.theme.Theme;

/**
 * Session orchestration: wraps an {@link AgentHarness} with settings, trust,
 * providers and slash commands, and exposes prompt execution with live
 * streaming (Phase 3 design §9.5/§11.5). Since Phase 4, sessions are backed
 * by a persistent {@link com.pijava.agent.session.SessionRepository}
 * (JSONL default, SQLite opt-in)
 * so {@code -c/-r} and {@code /resume} survive process restarts.
 *
 * <p>批 29（结构债拆分）：实现迁同包包私有协作者（纯移动，行为零变化）；本类留壳——
 * 共享字段、构造器、事件核心、公开嵌套类型、包私有访问器、public 方法的一行委托。</p>
 */
public final class AgentSession implements AutoCloseable {

    // ── 批 29：包私有协作者（实现簇的新家；壳内仅委托）──
    private final ModelToolControl modelTools = new ModelToolControl(this);
    private final VirtualModelRouting routing = new VirtualModelRouting(this);
    private final PromptPipeline prompts = new PromptPipeline(this);
    private final TreeCompaction tree = new TreeCompaction(this);
    private final SessionDiscoveryFork discovery = new SessionDiscoveryFork(this);
    private final SessionMessagingLifecycle messaging = new SessionMessagingLifecycle(this);
    private final ExtensionLoading extensions = new ExtensionLoading(this);
    private final SessionDataViews views = new SessionDataViews(this);

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
    /** 批 18（评审 M4）：本会话装配期的 resolve 结果（wireSession 现算一份，
     *  技能/模板/主题/扩展四消费面共享；/reload 重算）。存于 {@link ExtensionLoading}。 */
    // pi agent-session.ts:1083/2434-2436/1823：settled 时读旗；abort() 置位；run 起手清零。
    // 包内可见：SessionRunner 在驱动起手清零、收尾读值。
    final AtomicBoolean agentRunAbortRequested = new AtomicBoolean();
    /** pi {@code _agentRunAbortRequested}：settled 事件的 aborted 载荷源（包内可见）。 */
    boolean agentRunAbortRequested() { return agentRunAbortRequested.get(); }
    /** pi {@code _isEmittingAgentSettled}（:433）。包内可见：SessionRunner 双发射点共享。
     *  volatile：写在 run 线程（发射窗），读可在扩展调用线程（RPC/TUI 事件）。 */
    volatile boolean emittingAgentSettled;
    // docs/16（T1）：装配期的**原始** ToolContext（图片策略/PI_* 晚绑定包装之前）——
    // fork 重建会话时 wireSession 的原料（pi createRuntime 重装配 cwd 绑定服务对位）。
    // 存于 SessionDiscoveryFork（fork 臂消费）。
    // docs/15（B249/B250）：宿主发射器 —— 生命周期/可取消 before 族/结果臂的统一
    // 发射口（pi 的 extensionRunner 宿主发射法对位）。--no-extensions 时为 null，
    // 全部 firing 点判空快走（pi 无扩展＝runner 空表，行为等价）。
    private SessionEventFirer extensionFirer;
    /** docs/16（B252，裁决⑥）：替换/退出关闭幂等门（存于 {@link SessionMessagingLifecycle}）。 */

    AgentSession(AgentHarness harness, SessionServices services, Args args, String name) {
        this.harness = harness;
        this.services = services;
        this.args = args;
        this.name = name;
    }
    // ── 事件核心（hub 在 views，壳内为委托）──
    /** 订阅会话级事件（委托 {@link SessionDataViews}，对齐 pi {@code AgentSession.subscribe}）。 */
    public AutoCloseable subscribe(Consumer<AgentSessionEvent> listener) { return views.subscribe(listener); }
    /** 广播会话事件给所有监听器；单个监听器异常被隔离。 */
    void emitSessionEvent(AgentSessionEvent event) { views.emit(event); }
    /** Subscribe to live session snapshots (status bar, tree selector). */
    public WatchHandle<com.pijava.agent.harness.SessionSnapshot> watchSession() {
        return harness.watchSession();
    }
    // ── 壳自留的核心访问面（字段即壳字段）──
    /** The underlying harness (used by the session repository and TUI). */
    public AgentHarness harness() { return harness; }
    /** The assembled services. */
    public SessionServices services() { return services; }
    /** Session display name. */
    public String sessionName() { return name; }
    /** The CLI arguments this session was assembled from ({@code /new}). */
    public Args sessionArgs() { return args; }

    // ── 公开嵌套类型（API 面，留壳）──
    /**
     * pi {@code navigateTree} 的返回：取消旗＋摘要中止旗（pi 区分
     * {@code {cancelled:true}} 扩展取消与 {@code {cancelled:true, aborted:true}}
     * 摘要中止，:4032/:4069）＋编辑器回填文本＋摘要 entry（可无）。
     */
    public record TreeNavigation(boolean cancelled, boolean aborted, String editorText,
                                 Entry.BranchSummary summaryEntry) {}
    /** 会话列表摘要（web UI 用；对齐 pi {@code SessionManager.list}）。 */
    public record SessionSummary(
            String id, String path, String name, String cwd, Instant created,
            long modifiedMs, int messageCount, String firstMessage) {}
    /**
     * 批 22（T1，W15）：摘要级渐进发布回调（pi {@code SessionListProgress}
     * 在 {@link AgentSession} 摘要面的投影——partial 仅周期发布点非 null）。
     */
    @FunctionalInterface
    public interface SessionSummaryProgress {
        /** @param partials 已加载会话的活动序快照；非发布点为 null（pi 同） */
        void onProgress(int loaded, int total, List<SessionSummary> partials);
    }
    /** docs/15：{@code switchTo} 的结局（pi 的 {@code {cancelled}} 对位）。 */
    public enum SwitchOutcome { SWITCHED, CANCELLED, NOT_FOUND }
    /** docs/15：切换结局＋切换后的会话（持久路原地 attach ⇒ 即本会话）。 */
    public record SwitchResult(SwitchOutcome outcome, AgentSession session) {}
    /** pi {@code getUserMessagesForForking} 的返回项（{@code {entryId, text}}，docs/21 裁决①）。 */
    public record ForkMessage(String entryId, String text) {}
    // ── 工厂委托（→ AgentSessionFactory）──
    /** Web 默认入口（→ {@link AgentSessionFactory}）。 */
    public static AgentSession createWeb(Args args) { return AgentSessionFactory.createWeb(args); }
    static AgentSession createWeb(Args args, ProviderRegistry providers, ToolContext toolContext) {
        return AgentSessionFactory.createWeb(args, providers, toolContext);
    }
    /** 委托 {@link AgentSessionFactory}。 */
    public static AgentSession create(Args args) { return AgentSessionFactory.create(args); }
    /** 委托 {@link AgentSessionFactory}。 */
    public static AgentSession create(Args args, ExtensionUI ui, String mode) {
        return AgentSessionFactory.create(args, ui, mode);
    }
    /** 委托 {@link AgentSessionFactory}。 */
    public static AgentSession create(Args args, ExtensionUI ui, String mode,
                                      ProjectTrustPrompter prompter) {
        return AgentSessionFactory.create(args, ui, mode, prompter);
    }
    /** 委托 {@link AgentSessionFactory}。 */
    public static AgentSession createReplacing(Args args, String previousSessionId) {
        return AgentSessionFactory.createReplacing(args, previousSessionId);
    }
    /** 委托 {@link AgentSessionFactory}。 */
    public static AgentSession createReplacing(Args args, String previousSessionId,
                                               String parentSession) {
        return AgentSessionFactory.createReplacing(args, previousSessionId, parentSession);
    }
    /** 委托 {@link AgentSessionFactory}。 */
    public static AgentSession create(Args args, ProviderRegistry providers,
                                      ToolContext toolContext) {
        return AgentSessionFactory.create(args, providers, toolContext);
    }
    static AgentSession create(Args args, InMemorySessionRepository repository) {
        return AgentSessionFactory.create(args, repository);
    }
    static AgentSession create(Args args, InMemorySessionRepository repository,
                               ProviderRegistry providers, ToolContext toolContext) {
        return AgentSessionFactory.create(args, repository, providers, toolContext);
    }
    static AgentSession create(Args args, InMemorySessionRepository repository,
                               ProviderRegistry providers, ToolContext toolContext,
                               SettingsManager settings) {
        return AgentSessionFactory.create(args, repository, providers, toolContext, settings);
    }
    static AgentSession create(Args args, PersistentSessionRepositories.RepositoryHandle handle,
                               ProviderRegistry providers, ToolContext toolContext,
                               SettingsManager settings) {
        return AgentSessionFactory.create(args, handle, providers, toolContext, settings);
    }
    // ── 只读投影委托（→ SessionDataViews；含扩展面只读出口）──
    /** 委托 {@link SessionDataViews}。 */ public String laneName() { return views.laneName(); }
    /** 委托 {@link SessionDataViews}。 */ public boolean isRunning() { return views.isRunning(); }
    /** 委托 {@link SessionDataViews}。 */ public String sessionId() { return views.sessionId(); }
    /** 委托 {@link SessionDataViews}。 */ public String sessionFile() { return views.sessionFile(); }
    /** 委托 {@link SessionDataViews}。 */ public String cwd() { return views.cwd(); }
    /** 委托 {@link SessionDataViews}。 */ public String storedSessionName() { return views.storedSessionName(); }
    /** 委托 {@link SessionDataViews}。 */ public long entryCount() { return views.entryCount(); }
    /** 委托 {@link SessionDataViews}。 */ public List<Entry> accumulatedEntries() { return views.accumulatedEntries(); }
    /** 委托 {@link SessionDataViews}。 */
    public List<Message> accumulatedMessages() { return views.accumulatedMessages(); }
    /** 委托 {@link SessionDataViews}。 */ public String lastAssistantText() { return views.lastAssistantText(); }
    /** 委托 {@link SessionDataViews}。 */
    public List<String> extensionResourcePaths() { return views.extensionResourcePaths(); }
    /** 委托 {@link SessionDataViews}。 */
    public List<Args.ArgDiagnostic> startupDiagnostics() { return views.startupDiagnostics(); }
    /** 委托 {@link SessionDataViews}。 */
    public ToolRendererRegistry toolRenderers() { return views.toolRenderers(); }
    /** 委托 {@link SessionDataViews}。 */
    public ExtensionShortcuts.Resolution extensionShortcuts(KeybindingsManager keys) {
        return views.extensionShortcuts(keys);
    }
    /** 委托 {@link SessionDataViews}。 */
    public ExtensionContext extensionShortcutContext() { return views.extensionShortcutContext(); }
    /** 委托 {@link SessionDataViews}。 */
    public ExtensionMessageRenderer messageRendererFor(String customType) {
        return views.messageRendererFor(customType);
    }
    /** 委托 {@link SessionDataViews}。 */
    public ExtensionEntryRenderer entryRendererFor(String customType) {
        return views.entryRendererFor(customType);
    }
    /** 委托 {@link SessionDataViews}。 */
    public List<MarkdownTransformer> markdownTransformers() { return views.markdownTransformers(); }
    // ── 模型/工具委托（→ ModelToolControl）──
    /** 委托 {@link ModelToolControl}。 */
    public ModelThinkingLevel setThinkingLevel(ModelThinkingLevel level) {
        return modelTools.setThinkingLevel(level);
    }
    /** 委托 {@link ModelToolControl}。 */
    public ModelCatalog modelCatalog() { return modelTools.modelCatalog(); }
    /** 委托 {@link ModelToolControl}。 */
    public List<ScopedModel> scopedModels() { return modelTools.scopedModels(); }
    /** 委托 {@link ModelToolControl}。 */ public void cycleModelForward() { modelTools.cycleModelForward(); }
    /** 委托 {@link ModelToolControl}。 */ public void setModel(ModelId<?> model) { modelTools.setModel(model); }
    public List<String> getActiveToolNames() { return modelTools.getActiveToolNames(); }
    /** 委托 {@link ModelToolControl}。 */
    public void setActiveToolsByName(java.util.List<String> names) {
        modelTools.setActiveToolsByName(names);
    }
    /** 委托 {@link ModelToolControl}。 */
    public void activateRuntimeTools(java.util.Collection<String> names) {
        modelTools.activateRuntimeTools(names);
    }
    /** 委托 {@link ModelToolControl}。 */
    public void deactivateRuntimeTools(java.util.Collection<String> names) {
        modelTools.deactivateRuntimeTools(names);
    }
    public List<ExtensionToolInfo> getAllTools() { return modelTools.getAllTools(); }
    /** 委托 {@link ModelToolControl}。 */
    public ToolExposure toolExposureOf(String name) { return modelTools.toolExposureOf(name); }
    public boolean isDeclarableTool(String name) { return modelTools.isDeclarableTool(name); }
    public boolean isActivatedOnRegistration(String name) { return modelTools.isActivatedOnRegistration(name); }
    public boolean isActivatableTool(String name) { return modelTools.isActivatableTool(name); }
    /** 委托 {@link ModelToolControl}。 */
    public java.util.List<AgentTool<?, ?>> callableTools() { return modelTools.callableTools(); }
    // ── 虚拟模型/嵌套调用委托（→ VirtualModelRouting）──
    /** 委托 {@link VirtualModelRouting}。 */
    public VirtualModelRegistry virtualModels() { return routing.virtualModels(); }
    /** 委托 {@link VirtualModelRouting}。 */
    public boolean isVirtualModel(ModelId<?> model) { return routing.isVirtualModel(model); }
    /** 委托 {@link VirtualModelRouting}。 */
    public ModelId<?> routeDirectIfVirtual(ModelId<?> model, List<Message> messages) {
        return routing.routeDirectIfVirtual(model, messages);
    }
    /** 委托 {@link VirtualModelRouting}。 */
    public PiLoop.ToolOutcome executeNestedToolCall(
            String parentToolCallId, String name, Map<String, Object> args,
            NestedToolCallRunner.Options options) {
        return routing.executeNestedToolCall(parentToolCallId, name, args, options);
    }
    // ── prompt/队列委托（→ PromptPipeline）──
    /** 委托 {@link PromptPipeline}。 */
    public SessionResult processPrompt(String prompt, PromptConfig config) {
        return prompts.processPrompt(prompt, config, null, null);
    }
    /** 委托 {@link PromptPipeline}。 */
    public SessionResult processPrompt(String prompt, PromptConfig config,
                                       StreamObserver streamObserver, EntryObserver entryObserver) {
        return prompts.processPrompt(prompt, config, streamObserver, entryObserver);
    }
    /** 委托 {@link PromptPipeline}。 */
    public SessionResult processPrompt(String prompt) {
        return prompts.processPrompt(prompt, PromptConfig.defaults(), null, null);
    }
    /** 委托 {@link PromptPipeline}。 */ public void abortCompaction() { prompts.abortCompaction(); }
    /** 委托 {@link PromptPipeline}。 */ public void abort() { prompts.abort(); }
    /** 委托 {@link PromptPipeline}。 */ public String followUp(String prompt) { return prompts.followUp(prompt); }
    /** 委托 {@link PromptPipeline}。 */
    public String followUp(String prompt, List<PromptImage> images, String source) {
        return prompts.followUp(prompt, images, source);
    }
    /** 委托 {@link PromptPipeline}。 */ public String steer(String prompt) { return prompts.steer(prompt); }
    /** 委托 {@link PromptPipeline}。 */
    public String steer(String prompt, List<PromptImage> images, String source) {
        return prompts.steer(prompt, images, source);
    }
    /** 委托 {@link PromptPipeline}。 */ public int pendingMessageCount() { return prompts.pendingMessageCount(); }
    /** 委托 {@link PromptPipeline}。 */ public void flashStatus(String message) { prompts.flashStatus(message); }
    /** 委托 {@link PromptPipeline}。 */
    public void emitQueueUpdate(List<String> bufferedSteering, List<String> bufferedFollowUp) {
        prompts.emitQueueUpdate(bufferedSteering, bufferedFollowUp);
    }
    /** 委托 {@link PromptPipeline}。 */ public void emitQueueUpdate() { prompts.emitQueueUpdate(); }
    /** 委托 {@link PromptPipeline}。 */ public ClearedQueue clearQueue() { return prompts.clearQueue(); }
    /** 委托 {@link PromptPipeline}。 */
    public void slashContextSupplier(Supplier<SlashContext> supplier) {
        prompts.slashContextSupplier(supplier);
    }
    // ── 压缩/树导航委托（→ TreeCompaction）──
    /** 委托 {@link TreeCompaction}。 */
    public CompactionResult compact(String customInstructions) {
        return tree.compact(customInstructions);
    }
    /** 委托 {@link TreeCompaction}。 */
    public CompactionResult compact(CompactionSettings settings) { return tree.compact(settings); }
    /** 委托 {@link TreeCompaction}。 */
    public CompactionResult compact(CompactionSettings settings, String customInstructions) {
        return tree.compact(settings, customInstructions);
    }
    /** 委托 {@link TreeCompaction}。 */
    public TreeNavigation navigateTree(String targetId, boolean summarize,
                                       String customInstructions, boolean replaceInstructions,
                                       String label) {
        return tree.navigateTree(targetId, summarize, customInstructions, replaceInstructions,
            label);
    }
    /** 委托 {@link TreeCompaction}。 */ public void abortBranchSummary() { tree.abortBranchSummary(); }
    /** 委托 {@link TreeCompaction}。 */ public boolean isCompacting() { return tree.isCompacting(); }
    /** 委托 {@link TreeCompaction}。 */ public List<SessionTreeNode> getTree() { return tree.getTree(); }
    /** 委托 {@link TreeCompaction}。 */ public String getLabel(String entryId) { return tree.getLabel(entryId); }
    public void setLabel(String entryId, String label) { tree.setLabel(entryId, label); }
    /** 委托 {@link TreeCompaction}。 */
    public String summarizeForBugReport(String hint) { return tree.summarizeForBugReport(hint); }
    // ── 发现/切换/fork/关闭委托（→ SessionDiscoveryFork）──
    /** 委托 {@link SessionDiscoveryFork}。 */
    public List<SessionInfo> listSessions() { return discovery.listSessions(); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public List<SessionSummary> listSessionsSummary(String cwd) {
        return discovery.listSessionsSummary(cwd);
    }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public List<SessionSummary> listSessionsSummary(
            String cwd, SessionSummaryProgress progress, AbortSignal signal) {
        return discovery.listSessionsSummary(cwd, progress, signal);
    }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public Optional<AgentSession> latestSession() { return discovery.latestSession(); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public Optional<AgentSession> findSession(String idOrPrefix) {
        return discovery.findSession(idOrPrefix);
    }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public boolean fireBeforeSwitch(String reason, String targetSessionFile) {
        return discovery.fireBeforeSwitch(reason, targetSessionFile);
    }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public SwitchResult switchTo(String idOrPrefix) { return discovery.switchTo(idOrPrefix); }
    public void setSessionName(String newName) { discovery.setSessionName(newName); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public void exportJsonl(Path target) { discovery.exportJsonl(target); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public AgentSession importJsonl(Path source) { return discovery.importJsonl(source); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public Optional<AgentSession> forkCopy(String branchName) { return discovery.forkCopy(branchName); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public Optional<AgentSession> forkFromEntry(String entryId) {
        return discovery.forkFromEntry(entryId);
    }
    public List<ForkMessage> getUserMessagesForForking() { return discovery.getUserMessagesForForking(); }
    /** Flush settings, persist pending writes and close the harness. */
    @Override
    public void close() { discovery.close(); }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public void closeForReplacement(String reason, String targetSessionFile) {
        discovery.closeForReplacement(reason, targetSessionFile);
    }
    /** 委托 {@link SessionDiscoveryFork}。 */
    public void fireSessionShutdown(String reason, String targetSessionFile) {
        discovery.fireSessionShutdown(reason, targetSessionFile);
    }
    // ── 消息/生命周期委托（→ SessionMessagingLifecycle）──
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public ShellResult executeBash(String id, String command, boolean excludeFromContext) {
        return messaging.executeBash(id, command, excludeFromContext);
    }
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public Entry.Usage appendUsage(String kind, String provider, String model,
                                   Usage usage, String note) {
        return messaging.appendUsage(kind, provider, model, usage, note);
    }
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public void appendCustomEntry(String customType, Map<String, Object> data) {
        messaging.appendCustomEntry(customType, data);
    }
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public void recordBashResult(String command, ShellResult result, boolean excludeFromContext) {
        messaging.recordBashResult(command, result, excludeFromContext);
    }
    /** 委托 {@link SessionMessagingLifecycle}。 */ public void abortBash() { messaging.abortBash(); }
    /** 委托 {@link SessionMessagingLifecycle}。 */ public boolean bashAborted() { return messaging.bashAborted(); }
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public String sendCustomMessage(String customType, CustomMessageContent content,
                                    boolean display, Map<String, Object> details,
                                    Boolean triggerTurn, CustomMessageDelivery deliverAs) {
        return messaging.sendCustomMessage(customType, content, display, details, triggerTurn,
            deliverAs);
    }
    public void setCacheWarmingMode(String mode) { messaging.setCacheWarmingMode(mode); }
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public CacheWarming.Status cacheWarmingStatus() { return messaging.cacheWarmingStatus(); }
    public void setAutoRetryEnabled(boolean enabled) { messaging.setAutoRetryEnabled(enabled); }
    /** 委托 {@link SessionMessagingLifecycle}。 */
    public boolean autoRetryEnabled() { return messaging.autoRetryEnabled(); }
    /** 委托 {@link SessionMessagingLifecycle}。 */ public void abortRetry() { messaging.abortRetry(); }
    // ── 扩展 UI/mode/reload 委托（→ ExtensionLoading）──
    /** 注入扩展 UI 服务（覆盖 loadExtensions 时的 noop）；mode 维持原值。 */
    public void extensionUI(ExtensionUI ui) { extensions.extensionUI(ui); }
    /** 批 17（T1/T3）：pi {@code setUIContext(uiContext, mode)}（runner.ts:580-584）对位。 */
    public void extensionUI(ExtensionUI ui, String mode) { extensions.extensionUI(ui, mode); }
    /** 当前扩展 UI 服务。 */ public ExtensionUI extensionUI() { return extensions.extensionUI(); }
    /** 批 17（T1）：pi {@code runner.mode} 对位（现读）。 */
    public String extensionMode() { return extensions.extensionMode(); }
    /** 委托 {@link ExtensionLoading}。 */ public void reload() { extensions.reload(); }
    /** 委托 {@link ExtensionLoading}。 */ public List<Path> themePaths() { return extensions.themePaths(); }
    /** 委托 {@link ExtensionLoading}。 */
    public List<Theme> themes() { return extensions.themes(); }
    /** 委托 {@link ExtensionLoading}。 */
    public void addReloadListener(Runnable listener) { extensions.addReloadListener(listener); }
    // ── Package-private accessors ──
    Session<?> session() { return session; }
    void session(Session<?> session) { this.session = session; }
    SessionEventFirer extensionFirer() { return extensionFirer; }
    void extensionFirer(SessionEventFirer firer) { this.extensionFirer = firer; }
    ToolSelection.Resolved toolSelection() { return modelTools.toolSelection(); }
    PersistentSessionRepositories.RepositoryHandle persistentRepository() { return persistentRepository; }
    void persistentRepository(PersistentSessionRepositories.RepositoryHandle h) { this.persistentRepository = h; }
    InMemorySessionRepository inMemoryRepository() { return repository; }
    void inMemoryRepository(InMemorySessionRepository repository) { this.repository = repository; }
    java.util.Set<String> persistedEntryIds() { return persistedEntryIds; }
    java.util.Set<String> persistedRecordIds() { return persistedRecordIds; }
    java.util.Set<String> deliveredEntryIds() { return deliveredEntryIds; }
    void name(String name) { this.name = name; }
    DefaultExtensionContext extensionContext() { return extensions.extensionContext(); }
    ExtensionManager extensionManager() { return extensions.extensionManager(); }
    java.util.Set<String> extensionProviderNames() { return extensions.extensionProviderNames(); }
    List<Args.ArgDiagnostic> startupDiagnosticsInternal() { return extensions.startupDiagnosticsInternal(); }
    SessionSetup.ResolvedAssembly resolveAssembly() { return extensions.resolveAssembly(); }
    // ── 协作者出口（包私有；跨簇互访的通道）与包私有委托（存量消费方）──
    ModelToolControl modelTools() { return modelTools; }
    VirtualModelRouting routing() { return routing; }
    PromptPipeline prompts() { return prompts; }
    TreeCompaction tree() { return tree; }
    SessionDiscoveryFork discovery() { return discovery; }
    SessionMessagingLifecycle messaging() { return messaging; }
    ExtensionLoading extensions() { return extensions; }
    SessionDataViews views() { return views; }
    void setScopedModels(List<ScopedModel> models) { modelTools.setScopedModels(models); }
    ImagePolicy currentImagePolicy() { return modelTools.currentImagePolicy(); }
    String thinkingLevelForModelSwitch(ModelId<?> target, String explicitLevel) {
        return extensions.thinkingLevelForModelSwitch(target, explicitLevel);
    }
    void setThemePathsForReload(List<String> paths) { extensions.setThemePathsForReload(paths); }
    void fireReloaded() { extensions.fireReloaded(); }
    void reloadThemes() { extensions.reloadThemes(); }
    List<Entry.BashExecution> drainPendingBashMessages() { return messaging.drainPendingBashMessages(); }
    void flushPendingCustomMessages() { messaging.flushPendingCustomMessages(); }
    List<QueuedCustomMessage> drainPendingNextTurnMessages() { return messaging.drainPendingNextTurnMessages(); }
    void emitAgentSettled() { messaging.emitAgentSettled(); }
    void notifyCacheWarmerSettled() { messaging.notifyCacheWarmerSettled(); }
    void appendBoundaryEntry(Entry entry) { messaging.appendBoundaryEntry(entry); }
    boolean retryAborted() { return messaging.retryAborted(); }
    void beginRetrySleep() { messaging.beginRetrySleep(); }
    void endRetrySleep() { messaging.endRetrySleep(); }
    void clearNestedToolCalls() { routing.clearNestedToolCalls(); }
    String currentLeafId() { return views.currentLeafId(); }
}
