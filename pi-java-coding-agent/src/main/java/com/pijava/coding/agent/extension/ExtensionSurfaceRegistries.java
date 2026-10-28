package com.pijava.coding.agent.extension;

import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.coding.agent.extension.ExtensionContext.SendMessageOptions;
import com.pijava.coding.agent.extension.ExtensionContext.SendUserMessageOptions;

/**
 * 「按扩展名分桶的注册表集合」（结构债拆分：{@link DefaultExtensionContext} 的
 * flag／快捷键／渲染器／MCP／通道五簇＋{@code getCommands}＋模型面整块迁出，
 * 行为零变化）。五簇同构：extensionPath → 表；共享基础上下文上的无归属调用 ⇒ 抛
 * （抛错 stub 留主类的 {@code ExtensionContext} 实现面）。
 */
final class ExtensionSurfaceRegistries {

    private final DefaultExtensionContext base;

    // ── 批 16（T1b）：flag 面——声明存本扩展（pi extension.flags）、值存共享表
    // （pi runtime.flagValues）。pi 的 pending/commit 两阶段不移植：java 加载与
    // 绑定同线程顺序执行（register 全在 loadAll 内、CLI 绑定在其后），
    // set-if-absent 一刀即同净效果（docs/27 T1b 注记）。
    private final java.util.Map<String, Object> flagValues =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 全局名 → 声明（pi registeredFlags；同名后到胜出）。 */
    private final java.util.Map<String, ExtensionFlag> flagsByName =
        new java.util.LinkedHashMap<>();
    /** 扩展名 → 该扩展的声明表（pi extension.flags；getFlag 所有权门的数据源）。 */
    private final java.util.Map<String, java.util.Map<String, ExtensionFlag>> flagDeclarations =
        new java.util.LinkedHashMap<>();
    /**
     * 批 23（docs/34 T1，裁决⑦）：MCP 注册表（pi {@code runtime.mcpServers} 对位，
     * 每会话一份随上下文生死）。装载期注册静默落表——changeListener 由
     * {@code AgentSession.loadExtensions} 尾部接线（裁决⑧：java 同步装载＋
     * listener 后装 ≡ pi 的 pendingRuntimeChanges/commit 两阶段净效果）。
     */
    private final com.pijava.mcp.config.McpServerRegistry mcpServers =
        new com.pijava.mcp.config.McpServerRegistry();
    /** 批 26（docs/37 T7）：pi {@code eventBus}（每会话一条，loader.ts:688 对位）。 */
    private final ExtensionChannelBus channelBus = new ExtensionChannelBus();
    /** pi {@code eventBusUnsubscribers}（loader.ts:161）——跟踪表，失效时统一退订。 */
    private final java.util.List<AutoCloseable> channelSubscriptions =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /** pi 的 {@code api.events}（跟踪视图，loader.ts:517-522 对位）。 */
    private final ExtensionChannelBus eventsView =
        channelBus.trackedView(channelSubscriptions::add);
    /**
     * 批 26（docs/37 T9）：pi {@code extension.shortcuts} 对位——按扩展加载序的
     * 快捷键表（extensionPath →〔keyId 原串 → 注册〕；注册点已解析校验 keyId）。
     */
    private final java.util.LinkedHashMap<String,
        java.util.LinkedHashMap<String, ExtensionShortcut>> shortcutsByExtension =
            new java.util.LinkedHashMap<>();
    /**
     * 批 26（docs/37 T10）：pi {@code extension.messageRenderers}/{@code entryRenderers}
     * 对位——按扩展加载序（first-wins 解决序，runner.ts:775-805）。
     */
    private final java.util.LinkedHashMap<String,
        java.util.Map<String, ExtensionMessageRenderer>> messageRenderersByExtension =
            new java.util.LinkedHashMap<>();
    /** 见 {@link #messageRenderersByExtension}。 */
    private final java.util.LinkedHashMap<String,
        java.util.Map<String, ExtensionEntryRenderer>> entryRenderersByExtension =
            new java.util.LinkedHashMap<>();
    /**
     * 批 26（docs/37 T11）：pi {@code extension.markdownTransformer} 对位——
     * <b>单槽</b>（loader.ts:361 赋值语义：同扩展重复注册后者覆盖）；跨扩展按
     * 加载序链式（runner.ts:785-787 的 flatMap 对位）。
     */
    private final java.util.LinkedHashMap<String, MarkdownTransformer> markdownTransformers =
        new java.util.LinkedHashMap<>();

    ExtensionSurfaceRegistries(DefaultExtensionContext base) {
        this.base = base;
    }

    // ── flag 面 ──

    /** 共享 flag 值表（pi {@code runtime.flagValues}；binder 写入、getFlag 读）。 */
    java.util.Map<String, Object> flagValues() {
        return flagValues;
    }

    /** 全部已声明 flag（pi --help 面的拍平，注册序）。 */
    java.util.List<ExtensionFlag> allFlags() {
        return java.util.List.copyOf(flagsByName.values());
    }

    /** 按名查声明（pi registeredFlags.get）；未注册 ⇒ {@code null}。 */
    ExtensionFlag findFlag(String name) {
        return flagsByName.get(name);
    }

    /** pi loader.ts:331-349 的本体（per-extension 委托转到这里）。 */
    void registerFlag(String extensionName, String name, ExtensionFlagOptions options) {
        var defaultValue = options.defaultValue();
        if (defaultValue != null && !typeMatches(options.type(), defaultValue)) {
            throw new IllegalArgumentException(
                "Invalid default for flag \"" + name + "\": expected "
                    + jsType(options.type()) + ", got " + jsTypeOf(defaultValue));
        }
        var flag = new ExtensionFlag(name, options.description(), options.type(),
            defaultValue, extensionName);
        flagDeclarations.computeIfAbsent(extensionName, k -> new java.util.LinkedHashMap<>())
            .put(name, flag);
        flagsByName.put(name, flag);
        if (defaultValue != null) {
            // pi loader.ts:342-348：default set-if-absent（CLI 值/先到 default 不被覆盖）。
            flagValues.putIfAbsent(name, defaultValue);
        }
    }

    /** pi loader.ts:373-378 的本体：所有权门（只能读本扩展注册的名）。 */
    Object getFlag(String extensionName, String name) {
        var declared = flagDeclarations.get(extensionName);
        if (declared == null || !declared.containsKey(name)) {
            return null;
        }
        return flagValues.get(name);
    }

    private static boolean typeMatches(ExtensionFlag.Type type, Object value) {
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String;
        };
    }

    private static String jsType(ExtensionFlag.Type type) {
        return switch (type) {
            case BOOLEAN -> "boolean";
            case STRING -> "string";
        };
    }

    /** pi {@code typeof default} 的 java 近似（文案逐字的第二操作数）。 */
    private static String jsTypeOf(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        return "object";
    }

    // ── 快捷键面 ──

    /**
     * pi {@code registerShortcut}（loader.ts:320-323）——写本扩展的表
     * （keyId 在注册点解析校验，裁决⑫）；同名键再注册＝覆盖本扩展旧注册
     * （pi 的 Map.set 同形）。
     */
    void registerShortcut(String extensionPath, String keyId, String description,
                          ExtensionShortcut.ShortcutHandler handler) {
        com.pijava.coding.agent.core.KeyIdParser.parse(keyId); // 裁决⑫：注册点校验
        shortcutsByExtension
            .computeIfAbsent(extensionPath, k -> new java.util.LinkedHashMap<>())
            .put(keyId, new ExtensionShortcut(keyId, description, handler, extensionPath));
    }

    /**
     * pi {@code runner.getShortcuts}（runner.ts:673-716）——冲突三规则解决。
     *
     * @param keys    内置键位表；{@code null} ⇒ 空表（无键位面的宿主：RPC/headless）
     * @param hasUi   pi 的「无 UI ⇒ console.warn」门：false ⇒ 诊断写 stderr
     */
    ExtensionShortcuts.Resolution resolvedShortcuts(
            com.pijava.coding.agent.core.KeybindingsManager keys, boolean hasUi) {
        var resolution = ExtensionShortcuts.resolve(keys, isWindowsPlatform(), shortcutsByExtension);
        if (!hasUi) {
            for (var diagnostic : resolution.diagnostics()) {
                System.err.println(diagnostic); // pi console.warn 对位（runner.ts:683-686）
            }
        }
        return resolution;
    }

    /** pi {@code useWindowsKeybindings} 对位（keybindings.ts:15-18）。 */
    private static boolean isWindowsPlatform() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
            || System.getenv("WSL_DISTRO_NAME") != null
            || System.getenv("WSL_INTEROP") != null;
    }

    // ── message/entry 渲染器面 ──

    /** pi {@code extension.messageRenderers.set}（loader.ts:351-354）。 */
    void registerMessageRenderer(String extensionPath, String customType,
                                 ExtensionMessageRenderer renderer) {
        messageRenderersByExtension
            .computeIfAbsent(extensionPath, k -> new java.util.LinkedHashMap<>())
            .put(customType, renderer);
    }

    /** pi {@code extension.entryRenderers ??= new Map(); .set}（loader.ts:361-365）。 */
    void registerEntryRenderer(String extensionPath, String customType,
                               ExtensionEntryRenderer renderer) {
        entryRenderersByExtension
            .computeIfAbsent(extensionPath, k -> new java.util.LinkedHashMap<>())
            .put(customType, renderer);
    }

    /** pi {@code getMessageRenderer}（runner.ts:775-782）：扩展加载序 first-wins。 */
    ExtensionMessageRenderer resolveMessageRenderer(String customType) {
        for (var renderers : messageRenderersByExtension.values()) {
            var renderer = renderers.get(customType);
            if (renderer != null) {
                return renderer;
            }
        }
        return null;
    }

    /** pi {@code getEntryRenderer}（runner.ts:798-805）：扩展加载序 first-wins。 */
    ExtensionEntryRenderer resolveEntryRenderer(String customType) {
        for (var renderers : entryRenderersByExtension.values()) {
            var renderer = renderers.get(customType);
            if (renderer != null) {
                return renderer;
            }
        }
        return null;
    }

    // ── markdown 变换器面 ──

    /** pi {@code extension.markdownTransformer = transformer}（loader.ts:361，单槽覆盖）。 */
    void registerMarkdownTransformer(String extensionPath, MarkdownTransformer transformer) {
        markdownTransformers.put(extensionPath, transformer);
    }

    /** pi {@code getMarkdownTransformers}（runner.ts:785-787）：扩展加载序的链。 */
    java.util.List<MarkdownTransformer> markdownTransformers() {
        return java.util.List.copyOf(markdownTransformers.values());
    }

    // ── 批 23（docs/34 T1）：MCP 注册面 ──

    /** MCP 注册表（装配接线用——{@code AgentSession.loadExtensions} 尾部接 changeListener）。 */
    com.pijava.mcp.config.McpServerRegistry mcpServers() {
        return mcpServers;
    }

    java.util.List<com.pijava.mcp.config.RegisteredMcpServer> getMcpServers() {
        // pi loader.ts:496-498 无归属门槛——list 是谁都能读的全量视图。
        return mcpServers.list();
    }

    /** pi loader.ts:471-489 的本体（per-extension 委托转入；三守卫逐字）。 */
    void registerMcpServer(String extensionPath, String name,
                           com.pijava.mcp.config.McpServerConfig config) {
        var validated = com.pijava.mcp.config.McpServerConfigs.validate(
            name, com.pijava.mcp.McpJson.mapper().valueToTree(config));
        switch (validated) {
            case com.pijava.mcp.config.McpConfigValidation.Invalid invalid ->
                throw new IllegalArgumentException(
                    "Invalid MCP server registered by extension \"" + extensionPath
                        + "\": " + invalid.message());
            case com.pijava.mcp.config.McpConfigValidation.Valid valid -> {
                var owner = mcpServers.get(name);
                if (owner != null && !owner.extensionPath().equals(extensionPath)) {
                    throw new IllegalStateException(
                        "MCP server \"" + name + "\" is already registered by extension \""
                            + owner.extensionPath() + "\"");
                }
                // pi：`-`/`_` 归一后同命名空间即冲突（mcpNamespace 比较）。
                var namespace = com.pijava.mcp.config.McpServerConfigs.namespace(name);
                for (var server : mcpServers.list()) {
                    if (!server.name().equals(name)
                            && com.pijava.mcp.config.McpServerConfigs.namespace(server.name())
                                .equals(namespace)) {
                        throw new IllegalStateException(
                            "MCP server \"" + name + "\" conflicts with registered server \""
                                + server.name() + "\"");
                    }
                }
                mcpServers.register(
                    new com.pijava.mcp.config.RegisteredMcpServer(name, valid.config(),
                        extensionPath));
            }
        }
    }

    /** per-extension 委托转入（所有权门在注册表层 {@code unregister(name, extensionPath)}）。 */
    void unregisterMcpServer(String extensionPath, String name) {
        mcpServers.unregister(name, extensionPath);
    }

    // ── 批 26（docs/37 T7）：通道面 ──

    /** 批 26（docs/37 T7，E5-28）：pi {@code events}——跟踪视图（失效退订的载体）。 */
    ExtensionChannelBus events() {
        return eventsView;
    }

    /**
     * pi {@code runtime.invalidate} 的 eventBus 半（loader.ts:193-196 逐字）：
     * 会话失效（替换/reload）时统一退订全部通道订阅。
     */
    void invalidateChannels() {
        for (var subscription : channelSubscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                // pi 的 unsubscribe 不抛；java 侧隔离（单个退订失败不挡其余）。
                java.util.logging.Logger.getLogger(DefaultExtensionContext.class.getName())
                    .warning("channel unsubscribe failed: " + e);
            }
        }
        channelSubscriptions.clear();
    }

    // ── 批 23（T4，E5-20）：getCommands ──

    /**
     * 批 23（T4，E5-20）：pi {@code getCommands}（agent-session.ts:3361-3381
     * 三源序逐字）——扩展编号名 → 模板 → {@code skill:} 前缀技能。
     */
    java.util.List<com.pijava.coding.agent.core.slash.SlashCommandInfo> getCommands() {
        var out = new java.util.ArrayList<com.pijava.coding.agent.core.slash.SlashCommandInfo>();
        for (var cmd : base.services().slashCommands().resolvedExtensionCommands()) {
            out.add(new com.pijava.coding.agent.core.slash.SlashCommandInfo(
                cmd.invocationName(), cmd.command().description(), "extension",
                cmd.sourceInfo()));
        }
        for (var template : base.services().promptTemplates().all()) {
            out.add(new com.pijava.coding.agent.core.slash.SlashCommandInfo(
                template.name(), template.description(), "prompt", template.sourceInfo()));
        }
        for (var skill : base.skills().all()) {
            out.add(new com.pijava.coding.agent.core.slash.SlashCommandInfo(
                "skill:" + skill.name(), skill.description(), "skill",
                com.pijava.coding.agent.core.source.SourceInfos.fromSkill(skill)));
        }
        return java.util.List.copyOf(out);
    }

    // ── 批 27（B162/T11）：虚拟模型注册面 ──

    /**
     * 批 27（B162/T11）：pi loader.ts:500-514 对位——把扩展的
     * {@code route(request, ctx)} 适配成核心 {@code route(request)}（ctx 用注册时
     * 捕获的调用方上下文）。java 的装载/绑定同线程顺序执行（flagValues 先例），
     * pi 的 pending/commit 两阶段不移植——注册直进会话注册表；冲突/空 id 的
     * {@code IllegalArgumentException} 由 {@code ExtensionManager} 的装载隔离
     * 收成扩展错误（pi 的冲刷诊断臂对位）。
     */
    void registerVirtualModel(ExtensionVirtualModel model, ExtensionContext routeContext) {
        var session = base.requireToolControl();
        var definition = new com.pijava.coding.agent.core.virtual.VirtualModelDefinition(
            model.provider(), model.id(), model.name(), model.thinkingLevels(),
            model.contextWindow(), model.maxTokens(), model.input(),
            request -> model.route().apply(request, routeContext));
        session.virtualModels().register(definition);
        // pi 的 _refreshCurrentModelFromRegistry（:3483-3490）不移植：java 的
        // ModelId 是值对象，注册表重建无引用可刷（方言，内部）。
    }

    /** 批 27（B162/T11，E5-27）：pi loader.ts:511-513 对位。 */
    void unregisterVirtualModel(String provider, String id) {
        base.requireToolControl().virtualModels().unregister(provider, id);
    }

    // ── 批 26（docs/37 T3）：消息面 ──

    String sendMessage(String customType, CustomMessageContent content, boolean display,
                       Map<String, Object> details, SendMessageOptions options) {
        // 批 26（docs/37 T3）：委托 AgentSession.sendCustomMessage 五臂
        //（pi agent-session.ts:2293-2329 逐字；UOE 占位退役）。
        var host = base.hostSessionOrNull();
        if (host != null) {
            var opts = options == null ? new SendMessageOptions(null, null) : options;
            return host.sendCustomMessage(customType, content, display, details,
                opts.triggerTurn(), opts.deliverAs());
        }
        // 轻量夹具回落（无宿主会话、仅绑会话树）：仅落盘臂（批 26 前全量行为）；
        // triggerTurn 无车道可驱动 ⇒ 维持 UOE（本批的 triggerTurn 落地在宿主路径）。
        if (options != null && Boolean.TRUE.equals(options.triggerTurn())) {
            throw new UnsupportedOperationException(
                "sendMessage(triggerTurn=true) requires a bound host session");
        }
        var supplier = base.sessionSupplier();
        var tree = supplier == null ? null : supplier.get();
        if (tree == null) {
            throw new IllegalStateException(
                "sendMessage requires a bound session; none is available yet");
        }
        return tree.appendCustomMessageEntry(customType, content, display, details);
    }

    /**
     * 批 26（docs/37 T3，E5-13）：pi {@code sendUserMessage}（agent-session.ts:2365-2393
     * 逐字）——归一后走 {@code processPrompt}（source "extension"、deliverAs 作
     * streamingBehavior、expandPromptTemplates 默认 false 经 PromptConfig 门）。
     */
    void sendUserMessage(CustomMessageContent content, SendUserMessageOptions options) {
        var host = base.hostSessionOrNull();
        if (host == null) {
            throw new IllegalStateException(
                "sendUserMessage requires a bound session; none is available yet");
        }
        var opts = options == null ? new SendUserMessageOptions(null, null) : options;
        String text;
        java.util.List<com.pijava.agent.harness.PromptImage> images = null;
        switch (content) {
            case CustomMessageContent.Text t -> text = t.text();
            case CustomMessageContent.Blocks b -> {
                var textParts = new java.util.ArrayList<String>();
                var imageParts = new java.util.ArrayList<com.pijava.agent.harness.PromptImage>();
                for (var block : b.blocks()) {
                    if (block instanceof com.pijava.ai.message.ContentBlock.TextContent text2) {
                        textParts.add(text2.text());
                    } else if (block instanceof com.pijava.ai.message.ContentBlock.ImageContent img) {
                        imageParts.add(new com.pijava.agent.harness.PromptImage(
                            img.mediaType(), img.data()));
                    }
                }
                text = String.join("\n", textParts);
                if (!imageParts.isEmpty()) {
                    images = imageParts;
                }
            }
        }
        host.processPrompt(text, com.pijava.coding.agent.core.PromptConfig.extension(
            opts.deliverAs(), images, opts.expandPromptTemplates()));
    }

    // ── 批 26（docs/37 T4）：会话元数据面（委托宿主会话，pi types.ts:1719-1732）──

    /** pi {@code appendEntry}——custom 条目＋entry_appended＋落盘（AgentSession 既有全链）。 */
    void appendEntry(String customType, Map<String, Object> data) {
        base.requireToolControl().appendCustomEntry(customType, data);
    }

    /** pi {@code setSessionName}——规范化＋落 session_info＋session_info_changed 双发。 */
    void setSessionName(String name) {
        base.requireToolControl().setSessionName(name);
    }

    /** pi {@code getSessionName}——stored 名（批 20 B32 面），未命名 ⇒ null。 */
    String getSessionName() {
        return base.requireToolControl().storedSessionName();
    }

    /** pi {@code setLabel}——目标不存在抛 {@code Entry <id> not found}（文案批 26 修真）。 */
    void setLabel(String entryId, String label) {
        base.requireToolControl().setLabel(entryId, label);
    }

    // ── 批 26（docs/37 T5）：模型与思考级别面（pi types.ts:1763-1772）──

    /**
     * pi 扩展面 {@code setModel}（agent-session.ts:3429-3432 逐字）：auth 未配置 ⇒
     * false 不抛。auth 判据对位 pi {@code hasConfiguredAuth}（配置快照）——java 读
     * 活凭证链（{@code Credentials.resolveCredential}：环境变量＋存储；比快照更准，
     * 同真值域）。
     */
    boolean setModel(com.pijava.ai.model.ModelId<?> model) {
        var host = base.requireToolControl();
        // 批 27（B162 裁决⑩）：注册过虚拟模型的 provider 视为已配置（pi
        // model-runtime.ts:967-973 的 snapshot 标记对位——provider 级豁免）。
        if (!host.virtualModels().isAuthExempt(model.provider())
                && com.pijava.ai.auth.Credentials.resolveCredential(model.provider()).isEmpty()) {
            return false;
        }
        host.setModel(model);
        return true;
    }

    /** pi {@code getThinkingLevel}——harness 车道当前档。 */
    com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel() {
        return base.requireToolControl().harness().getThinkingLevel();
    }

    /** pi {@code setThinkingLevel}——会话内设级（变化才发双事件，桥已在）。 */
    void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level) {
        base.requireToolControl().setThinkingLevel(level);
    }

    // ── 批 26（docs/37 T6，E5-17）：exec ──

    /**
     * 批 26（docs/37 T6，E5-17）：pi {@code exec}——cwd 回落宿主会话 cwd
     * （loader.ts:413 的 {@code options?.cwd ?? cwd} 对位）；无宿主 ⇒ 进程当前目录。
     */
    ExtensionExec.ExecResult exec(String command, java.util.List<String> args,
                                  ExtensionExec.ExecOptions options) {
        var host = base.hostSessionOrNull();
        return ExtensionExec.execCommand(command, args == null ? java.util.List.of() : args,
            host == null ? null : host.cwd(), options);
    }
}
