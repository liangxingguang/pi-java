package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionManager;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.ResourcePaths;
import com.pijava.coding.agent.prompt.PromptTemplates;
import com.pijava.coding.agent.skill.SkillDiscovery;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的扩展加载簇 —— loadExtensions
 * （四条搭车钩次序逐字保持：虚拟路由钩／MCP renderer／嵌套挂载／MCP
 * changeListener）＋applySessionStartResources＋loadResolvedExtension，
 * 以及扩展 UI/mode 注入态（pi setUIContext 的落点——loadExtensions 本即写点）
 * 和 reload/主题小段（themePaths/themes/reloadListeners 字段随此簇）。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class ExtensionLoading {

    private final AgentSession owner;

    // Phase 6 (P6-7b): 扩展 UI 服务（RPC/TUI 模式注入，缺省 noop）。
    // 批 17（T1，裁决②）：volatile——晚绑定后写侧是模式层装配线程，读侧是扩展线程。
    private volatile ExtensionUI extensionUI = ExtensionUI.noop();
    /** 批 17（T1）：pi {@code ExtensionMode}（"tui"|"rpc"|"json"|"print"）——随 UI 注入。 */
    private volatile String extensionMode = "print";
    /** docs/17（B248）：provider 请求面桥（pi extensionRunnerRef 对位）——wireSession
     *  随 StreamFn 建、loadExtensions 回填 bus/ctx；null ⇒ 无扩展装配（测试形状）。 */
    private com.pijava.coding.agent.extension.bridge.ProviderEventBridge providerBridge;
    /** T1d：扩展 flag 绑定诊断（pi AgentSessionRuntimeDiagnostic 对位，裁决⑧落点）。 */
    private final List<com.pijava.coding.agent.cli.Args.ArgDiagnostic> startupDiagnostics =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /** T1b/T4b：扩展上下文（loadExtensions 回填；flag 值表与渲染器注册表的宿主）。 */
    private com.pijava.coding.agent.extension.DefaultExtensionContext extensionContext;
    /** T5b：扩展管理器（/reload 的 resources_discover 重放口）。 */
    private com.pijava.coding.agent.extension.ExtensionManager extensionManager;
    /** T5b（裁决④）：扩展注册的 provider 名集（loadExtensions 按 diff 簿记，reload 快照保留）。 */
    private java.util.Set<String> extensionProviderNames = java.util.Set.of();
    /** 批 18（评审 M4）：本会话装配期的 resolve 结果（wireSession 现算一份，
     *  技能/模板/主题/扩展四消费面共享；/reload 重算）。 */
    private SessionSetup.ResolvedAssembly resolveAssembly;
    // resources_discover：扩展贡献的主题候选（启动时选第一个可用）。
    private List<Path> themePaths = List.of();
    /** 批 21（T8）：themePaths 的装载结果（pi resourceLoader.getThemes() 对位）。 */
    private List<com.pijava.coding.agent.theme.Theme> themes = List.of();
    /** 批 21（T8）：reload 完成监听者（模式层主题收尾口）。 */
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> reloadListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    ExtensionLoading(AgentSession owner) {
        this.owner = owner;
    }

    /** 注入扩展 UI 服务（覆盖 loadExtensions 时的 noop）；mode 维持原值。 */
    void extensionUI(ExtensionUI ui) {
        if (ui != null) {
            this.extensionUI = ui;
        }
    }

    /**
     * 批 17（T1/T3）：双参 setter——pi {@code setUIContext(uiContext, mode)}
     * （runner.ts:580-584）对位；mode ∈ {@code "tui"|"rpc"|"json"|"print"}。
     */
    void extensionUI(ExtensionUI ui, String mode) {
        if (ui != null) {
            this.extensionUI = ui;
        }
        if (mode != null) {
            this.extensionMode = mode;
        }
    }

    /** 当前扩展 UI 服务。 */
    ExtensionUI extensionUI() {
        return extensionUI;
    }

    /** 批 17（T1）：pi {@code runner.mode} 对位（现读）。 */
    String extensionMode() {
        return extensionMode;
    }

    /** mode 写面（assemble 初始烘值／fork 臂 mode 携带）。 */
    void extensionMode(String mode) {
        this.extensionMode = mode;
    }

    /** provider 请求面桥读写面（wireSession 建／loadExtensions 回填）。 */
    com.pijava.coding.agent.extension.bridge.ProviderEventBridge providerBridge() {
        return providerBridge;
    }
    void providerBridge(com.pijava.coding.agent.extension.bridge.ProviderEventBridge bridge) {
        this.providerBridge = bridge;
    }
    /** 启动诊断的活列表（append 面：attachTrustWarnings/loadExtensions/reloadThemes）。 */
    List<com.pijava.coding.agent.cli.Args.ArgDiagnostic> startupDiagnosticsInternal() {
        return startupDiagnostics;
    }
    /** 扩展上下文读写面（loadExtensions 回填；消费方判空快走）。 */
    com.pijava.coding.agent.extension.DefaultExtensionContext extensionContext() {
        return extensionContext;
    }
    void extensionContext(com.pijava.coding.agent.extension.DefaultExtensionContext context) {
        this.extensionContext = context;
    }
    /** 扩展管理器读写面（loadExtensions 回填；{@link SessionReload} 的读取面）。 */
    com.pijava.coding.agent.extension.ExtensionManager extensionManager() {
        return extensionManager;
    }
    void extensionManager(com.pijava.coding.agent.extension.ExtensionManager manager) {
        this.extensionManager = manager;
    }
    /** 扩展注册的 provider 名集读写面（loadExtensions 簿记；reload 快照保留）。 */
    java.util.Set<String> extensionProviderNames() {
        return extensionProviderNames;
    }
    void extensionProviderNames(java.util.Set<String> names) {
        this.extensionProviderNames = names;
    }
    /** 装配期 resolve 结果读写面（wireSession 回填；loadExtensions/资源路径枚举读）。 */
    SessionSetup.ResolvedAssembly resolveAssembly() { return resolveAssembly; }
    void resolveAssembly(SessionSetup.ResolvedAssembly assembly) { this.resolveAssembly = assembly; }

    /**
     * 加载扩展（ServiceLoader + 扩展目录 JAR），--no-extensions 时跳过
     * （pi 无扩展＝runner 空表，行为等价）。
     *
     * <p>docs/14 §3.6：事件面接线 —— 总线随上下文建；引擎钩子桥**恒装**（裁决②）；
     * 宿主事件桥在装配期订阅（裁决⑤：先于模式层 TUI/RPC，与 pi「扩展先」同向）；
     * 扩展错误经 {@link AgentSessionEvent.ExtensionError} 进会话事件流（裁决⑥：
     * RPC 同管道上线，pi 的 {@code emitError} 独立 listener 对位）。</p>
     */
    static void loadExtensions(Args args, SessionServices services,
                               AgentHarness harness, ExtensionUI ui,
                               AgentSession owner, AgentSessionFactory.SessionStartInfo startInfo) {
        // 批 17（T1）：UI/mode 烘值独立于扩展加载——pi {@code setUIContext}
        // 是模式层装配而非扩展加载（runner.ts:580；早退臂曾把它跳过 ⇒
        // --no-extensions 下 TUI/RPC 注入落空）。
        owner.extensionUI(ui == null ? ExtensionUI.noop() : ui);
        // 批 27（docs/38 T10）：虚拟模型路由钩——pi 的 _installAgentRequestProjection
        // 在会话建立时恒装（agent-session.ts:787 与扩展加载无关；虚拟模型只能经
        // 扩展注册，但装钩不依赖扩展面——-ne 早退臂之前挂上）。
        harness.hookSystem().onPrepareRequestRouted(owner.laneName(),
            owner.routing()::routeVirtualRequest);
        // 批 18（T6）：CLI 显式源 temporary resolve＋主题候选基线（pi
        // resource-loader.ts:530-585 合并臂：themes 独立于 noExtensions，
        // noThemes 只裁 resolve 臂）。在 -ne 早退臂之前，主题不被扩展开关误裁。
        // 评审 M4：resolve 结果复用 wireSession 的装配期单遍（owner.resolveAssembly）。
        var assembly = owner.resolveAssembly();
        var cliResolved = assembly.cli();
        // 批 21（T9，A-29）：CLI --theme 车道先行（pi cliEnabledThemes 序对位）。
        var cliThemePaths = SessionSetup.mergePaths(SessionSetup.cliThemePaths(args),
            SessionSetup.enabledPaths(cliResolved.themes()));
        var themeStrings = args.noThemes()
            ? cliThemePaths
            : SessionSetup.mergePaths(cliThemePaths,
                SessionSetup.enabledPaths(assembly.resolved().themes()));
        owner.extensions().themePaths(themeStrings.stream().map(java.nio.file.Path::of).toList());
        // 批 21（T8）：基线路径即装（pi resourceLoader 初始 reload 对位）——
        // 在 -ne 早退臂之前，主题不被扩展开关误裁（noThemes 只裁 resolve 臂）。
        owner.reloadThemes();
        // 批 14（docs/25 T15，A-25）：早退臂收紧为「-ne 且无显式路径」——
        // pi resource-loader.ts:578/690 的 noExtensions ? cliEnabledExtensions : merge(...)
        // 对位：显式 -e 路径恒加载（-ne 旁路），且扩展事件面随加载活着。
        if (args.noExtensions() && args.extensions().isEmpty()) {
            // 批 11 T6：pi 的 blockImages 过滤独立于扩展面（sdk.ts:296-329 的
            // convertToLlm 包装与扩展加载无关）⇒ --no-extensions 也裸注册过滤钩。
            harness.hookSystem().onTransformContext(owner.laneName(), messages ->
                services.settings().compaction().getBlockImages()
                    ? com.pijava.coding.agent.core.ImageBlockFilter.apply(messages)
                    : null);
            // 批 16（T1d）：无注册面 ⇒ 所有未知 --flag 报未知（pi：registeredFlags
            // 空 ⇒ unknownFlags 全收，agent-session-services.ts:107-111）。
            owner.extensions().startupDiagnosticsInternal().addAll(
                com.pijava.coding.agent.extension.ExtensionFlagBinder.apply(
                    args.unmatched(), null));
            return;
        }
        // docs/16（§2.6）：UI 烘值已上提至早退臂之前（批 17）——fork 重建时 mode 层
        // 注入的 UI 经父会话字段传入，forked 的字段与 context 自此一致。
        var bus = new com.pijava.coding.agent.extension.ExtensionEventBus();
        // 批 17（T1，裁决②）：晚绑定——context 持 owner 字段的供给器而非烘死值，
        // RPC/TUI 后注入的 UI 自此到得了扩展（pi runner.ts:876-884 getter 逐字）。
        var context = new DefaultExtensionContext(
            services, harness.skillManager(), owner::extensionUI, owner::extensionMode, bus);
        // 批 16（T4c）：内置 MCP 渲染器 resolver 先注册（pi 内置 MCP 扩展随运行时
        // 先载，extensions/mcp/index.ts:384-386）；其 next() ?? 委托优先逐字。
        context.toolRenderers().register(
            com.pijava.coding.agent.extension.McpToolCallRenderer.resolver());
        // 批 16（T2c）：激活门三件套晚绑定（pi runner.bindCore 先于扩展加载对位）。
        context.bindToolControl(() -> owner);
        owner.extensions().extensionContext(context);
        // docs/17（B248）：provider 请求面桥回填（pi extensionRunnerRef.current = runner
        // 对位）——此后该会话的每一次 provider 请求过四事件线。
        if (owner.extensions().providerBridge() != null) {
            owner.extensions().providerBridge().install(bus, context);
        }
        // 批 13（T3）：decide 钩桥回填（同点同晚绑定形）。
        if (owner.messaging().cacheWarmingBridge() != null) {
            owner.messaging().cacheWarmingBridge().install(bus, context);
        }
        // session 由 SessionPersistence 稍后赋值，惰性求值绑定供 sendMessage 落盘用。
        context.bindSession(owner::session);
        var manager = new ExtensionManager(context);
        // 批 16（T2d/T5b）：加载前基线——diff 出扩展贡献的工具/provider 名。
        var baselineToolNames = new java.util.HashSet<>(services.tools().toolNames());
        var baselineProviderNames = services.providers().listAll().stream()
            .map(com.pijava.ai.provider.Provider::name)
            .collect(java.util.stream.Collectors.toSet());
        if (!args.noExtensions()) {
            // 批 24（docs/35 T2）：pi disabledBuiltinExtensions（main.ts:787）——
            // --no-mcp 从 ServiceLoader 自动发现里摘掉内置 MCP 扩展。
            manager.loadAll(args.noMcp()
                ? java.util.Set.of(com.pijava.coding.agent.extension.mcp.McpExtension.NAME)
                : java.util.Set.of());
            // 批 18（T6）：resolve() 输出取代「扫全局/项目扩展目录」两臂（pi
            // mergePaths(cliEnabledExtensions, enabledExtensions) :577-578 对位；
            // 自动发现臂覆盖原两目录扫描，settings 数组/包自此生效——裁决⑨）。
            for (var path : SessionSetup.mergePaths(
                    SessionSetup.enabledPaths(cliResolved.extensions()),
                    SessionSetup.enabledPaths(assembly.resolved().extensions()))) {
                loadResolvedExtension(manager, path, args.noMcp());
            }
        } else {
            // 批 18（T6）：pi noExtensions ⇒ cliEnabledExtensions（:577）——
            // -ne 下 CLI 显式源仍加载；-e 本地目录/npm:/git: 经 temporary resolve。
            for (var path : SessionSetup.enabledPaths(cliResolved.extensions())) {
                loadResolvedExtension(manager, path, args.noMcp());
            }
        }
        owner.extensions().extensionManager(manager);
        // 批 16（T1d）：CLI 未知 --flag 绑定进共享值表（pi applyExtensionFlagValues，
        // agent-session-services.ts:82-128）；诊断落 startupDiagnostics（裁决⑧）。
        owner.extensions().startupDiagnosticsInternal().addAll(
            com.pijava.coding.agent.extension.ExtensionFlagBinder.apply(
                args.unmatched(), context));
        // 批 16（T2d，pi「注册即激活」臂 agent-session.ts:3603 对位）：装配后新注册的
        // 扩展工具经注册面门（isAllowed，含 mcp__ 保留臂）并入 activeTools。
        // 批 27（docs/38 T6）：defaultActive:false 闸落地（pi _isActivatedOnRegistration
        // :3601-3604——declarable 且 defaultActive≠false 才激活；tool_search 因此
        // 注册不激活），D89 收窄（余：无元数据的通用扩展工具仍按默认全量激活）。
        var selection = owner.toolSelection();
        var newToolNames = services.tools().toolNames().stream()
            .filter(name -> !baselineToolNames.contains(name))
            .filter(name -> selection == null || selection.isAllowed(name))
            .filter(owner::isActivatedOnRegistration)
            .toList();
        if (!newToolNames.isEmpty()) {
            harness.activateTools(newToolNames);
        }
        // 批 16（T5b 裁决④）：扩展注册的 provider 名簿记（reload 重置时快照保留）。
        owner.extensions().extensionProviderNames(services.providers().listAll().stream()
            .map(com.pijava.ai.provider.Provider::name)
            .filter(name -> !baselineProviderNames.contains(name))
            .collect(java.util.stream.Collectors.toSet()));
        // 批 27（docs/38 T3）：嵌套调用记录挂载钩——先于扩展桥注册（pi 在
        // message_start 挂载、扩展 message_end 处理器看到已挂载消息的次序对位；
        // sink 在 message_end 钩链后追加条目 ⇒ 挂载先于落盘）。
        harness.hookSystem().onMessageEnd(owner.laneName(),
            ctx -> owner.routing().mountNestedCalls(ctx.message()));
        com.pijava.coding.agent.extension.bridge.EngineHookBridge.install(
            harness.hookSystem(), owner.laneName(), bus, context,
            // 批 11 T6：blockImages 晚读口（pi 注释：mid-session changes take effect）。
            () -> services.settings().compaction().getBlockImages());
        // docs/15 §3.5：boundary 两适配器恒装（turn_end 挂 should_stop_after_turn、
        // agent_before_settle 挂 before_settle）；turnIndex 与宿主桥共享
        // （pi _turnIndex 同字段——boundary 在宿主 turn_end 事件之前读）。
        var turnIndex =
            new com.pijava.coding.agent.extension.bridge.BoundaryBridge.TurnIndex();
        com.pijava.coding.agent.extension.bridge.EngineHookBridge.installBoundary(
            harness.hookSystem(), harness, owner.laneName(), bus, context,
            owner::appendBoundaryEntry, owner.agentRunAbortRequested::get, turnIndex,
            owner::flushPendingCustomMessages);
        owner.subscribe(new com.pijava.coding.agent.extension.bridge.SessionEventBridge(
            bus, context, harness.getThinkingLevel(), turnIndex)::onEvent);
        bus.onError(err ->
            owner.emitSessionEvent(new AgentSessionEvent.ExtensionError(err)));
        // docs/15（B250）：firer 提为会话字段（close/switch/fork/prompt/bash 都要发）；
        // session_start 在桥与错误通道接好后、resources_discover 之前发射
        // （pi bindExtensions 的逐字序，agent-session.ts:3279-3281）。初始装配恒
        // "startup"（CLI resume 也是——pi 逐字）；替换式新建由 createReplacing 穿载荷。
        owner.extensionFirer(new com.pijava.coding.agent.extension.SessionEventFirer(
            bus, context));
        // 批 17（T2）：ui_prompt 发射器回填（firer 晚于 context 诞生；包装器现读）。
        context.bindUiPromptFirer(owner.extensionFirer());
        // 批 23（docs/34 T2）：pi runner.ts:457-461 的 bind 期接线——changeListener
        // 在扩展装载完成后才装 ⇒ 装载期注册静默落表（session_start 处理器经
        // getMcpServers 读取），之后的注册/注销即时广播＋无处理器臂。
        var unhandledMcpReporter =
            new com.pijava.coding.agent.extension.UnhandledMcpServerReporter(bus);
        context.mcpServers().setChangeListener(() -> {
            var registered = context.mcpServers().list();
            owner.extensionFirer().mcpServersChange(registered);
            unhandledMcpReporter.report(registered);
        });
        owner.extensionFirer().sessionStart(
            startInfo == null ? "startup" : startInfo.reason(),
            startInfo == null ? null : startInfo.previousSessionFile());
        applySessionStartResources(harness, services,
            manager.sessionStartResources(), args, owner);
        // 批 21（T8）：themePaths 定稿（resolve 基线＋resources_discover 扩展贡献）
        // 后统一装载主题；诊断进 startupDiagnostics（pi resourceLoader 的
        // themeDiagnostics 对位）。
        owner.reloadThemes();
    }

    /**
     * 收集扩展贡献的会话开始资源（resources_discover）并接入会话。
     *
     * <p>加载顺序使扩展在 harness 构造之后运行，故钩子贡献的技能经共享
     * {@code SkillManager} 在首轮 LLM 请求前注册即生效（技能在请求期惰性读取）。
     * promptPaths 经 {@code PromptTemplates} 载入 {@code SessionServices} 的
     * 注册表；themePaths 作为候选列表存于会话，供 TUI 启动时选第一个。</p>
     */
    static void applySessionStartResources(AgentHarness harness,
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
        // themes: 扩展贡献追加到 resolve 基线之后（批 18 T6：pi 合并序——
        // CLI/resolve 先行，resources_discover 的扩展贡献随后），候选去重。
        if (!paths.themePaths().isEmpty() && !args.noThemes()) {
            var merged = new java.util.ArrayList<>(owner.extensions().themePaths());
            for (var p : paths.themePaths()) {
                if (!merged.contains(p)) {
                    merged.add(p);
                }
            }
            owner.extensions().themePaths(List.copyOf(merged));
        }
    }

    /**
     * 批 18（T6）：resolve 输出的扩展路径消费——目录源先解析显式入口
     * （{@code package.json} pi 清单/extensions 条目或 {@code index.jar} 回落，
     * 裁决⑤），无入口则按 classpath 根加载目录。
     *
     * <p>批 24（docs/35 T2，裁决①）：{@code builtin:<name>} 从「跳过」改为按名
     * 加载 ServiceLoader 发现件（pi {@code -e builtin:mcp} 对位）；被
     * {@code --no-mcp} 禁用的名跳过（pi disabledBuiltinExtensions 恒生效）。</p>
     */
    static void loadResolvedExtension(
            com.pijava.coding.agent.extension.ExtensionManager manager, String path,
            boolean noMcp) {
        var prefix = com.pijava.coding.agent.pkg.PackageManager.BUILTIN_PATH_PREFIX;
        if (path.startsWith(prefix)) {
            var name = path.substring(prefix.length());
            if (!(noMcp && "mcp".equals(name))) {
                manager.loadBuiltin(name);
            }
            return;
        }
        var p = java.nio.file.Path.of(path);
        if (java.nio.file.Files.isDirectory(p)) {
            var entries = com.pijava.coding.agent.pkg.PackageResourceScanner
                .resolveExtensionEntries(p);
            if (entries != null) {
                for (var entry : entries) {
                    manager.loadJar(java.nio.file.Path.of(entry));
                }
                return;
            }
        }
        manager.loadJar(p);
    }

    // ── reload/主题小段（批 21 T8/T9；主题候选的写点在 loadExtensions 本簇）──

    /** 扩展贡献的主题候选（resources_discover themePaths）；无则空列表。 */
    List<Path> themePaths() {
        return themePaths;
    }

    /** 主题候选写面（loadExtensions 基线／resources_discover 合并臂）。 */
    void themePaths(List<Path> paths) {
        this.themePaths = paths;
    }

    /**
     * 批 21（T8）：已装载的主题表（pi {@code resourceLoader.getThemes().themes}
     * 对位——装配期与 /reload 经 {@link #reloadThemes()} 重算）。
     */
    List<com.pijava.coding.agent.theme.Theme> themes() {
        return themes;
    }

    /**
     * 批 16（T5b，C-23/E5-29/B246）：pi {@code AgentSession.reload()}
     * （agent-session.ts:3660-3701）的 java 对位——七步序见 {@link SessionReload}。
     * 双门（isStreaming/isCompacting）在命令层（pi interactive-mode.ts:6430-6440 同位）。
     */
    void reload() {
        SessionReload.reload(owner);
    }

    /** pi {@code _getThinkingLevelForModelSwitch}（:2669-2681）逐字三臂。 */
    String thinkingLevelForModelSwitch(ModelId<?> target, String explicitLevel) {
        if (explicitLevel != null) {
            return explicitLevel;
        }
        if (target != null) {
            var perModel = owner.services().settings().accessors()
                .getModelThinkingLevel(target.provider(), target.modelName());
            if (perModel != null) {
                return perModel;
            }
        }
        return owner.services().settings().accessors().getDefaultThinkingLevel();
    }

    /** 批 21（T8）：{@link SessionReload} ⑤ 的写面——重设 themePaths 并重装载。 */
    void setThemePathsForReload(List<String> paths) {
        themePaths = paths.stream().map(Path::of).toList();
        reloadThemes();
    }

    /**
     * 批 21（T8，裁决⑩）：reload 完成监听——pi 的 setRegisteredThemes＋
     * {@code themeController.applyFromSettings()} 在 InteractiveMode 收尾
     * （interactive-mode.ts:6490-6492）；java 的 /reload 封在 slash 命令里，
     * 模式层经此口接收尾（注册表写入/控制器应用都在监听者侧，会话层保持
     * toolkit 中立）。
     */
    void addReloadListener(Runnable listener) {
        reloadListeners.addIfAbsent(listener);
    }

    /** {@link SessionReload} 收尾回调。 */
    void fireReloaded() {
        for (var listener : reloadListeners) {
            listener.run();
        }
    }

    /**
     * 批 21（T8）：装载 themePaths 指向的主题文件（pi
     * {@code updateThemesFromPaths} 对位——resolve 的自动发现条目已含
     * {@code <agentDir>/themes} 与项目 {@code .pi-java/themes}）。装载/碰撞诊断
     * 进 {@code startupDiagnostics}（pi 的 themeDiagnostics 对位；模式层随
     * startupDiagnostics 既有通道上报）。
     */
    void reloadThemes() {
        var result = com.pijava.coding.agent.theme.ThemeLoader.loadThemes(
            themePaths.stream().map(Path::toString).toList(),
            com.pijava.coding.agent.theme.Themes.colorMode());
        themes = List.copyOf(result.themes());
        for (var diagnostic : result.diagnostics()) {
            owner.extensions().startupDiagnosticsInternal().add(new Args.ArgDiagnostic(
                "collision".equals(diagnostic.level()) ? "collision" : diagnostic.level(),
                diagnostic.filePath() != null
                    ? diagnostic.filePath() + ": " + diagnostic.message()
                    : diagnostic.message()));
        }
    }
}
