package com.pijava.web;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.SettingsAccessors;
import com.pijava.web.WebProtocol.ModelInfo;
import com.pijava.web.WebProtocol.SerializedAgentState;
import com.pijava.web.WebProtocol.SessionListItem;
import com.pijava.web.WebProtocol.WebClientMessage;
import com.pijava.web.WebProtocol.WebServerMessage;

/**
 * WebSocket 连接 → {@link AgentSession} 分发（Phase 7 设计 §3）。每连接单活跃会话；
 * {@code newSession} / {@code loadSession} 切换当前会话并重建事件订阅。
 */
final class WebDispatcher {



    private final Consumer<WebServerMessage> send;
    private final AgentEventTranslator translator;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileBrowserService files;
    private final GitService git = new GitService();
    private AgentSession session;
    private AutoCloseable eventSubscription;
    private final WebSessionCommands commands;

    WebDispatcher(AgentSession session, Args args, Consumer<WebServerMessage> send) {
        this.session = session;
        this.send = send;
        // 批 2：agent_end 帧的 messages 由全量转录源供（会话面载荷自 #52 起是本 pass
        // 新增）。lambda 迟读字段 —— newSession/loadSession 会换 session 实例，
        // 方法引用会把旧实例钉死。
        this.translator = new AgentEventTranslator(() -> this.session.accumulatedMessages());
        this.files = new FileBrowserService(Path.of(System.getProperty("user.dir")));
        this.commands = new WebSessionCommands(this, args, send, translator);
    }

    // ── WebSessionCommands 钩子（包私有）─────────────────────────────────

    AgentSession session() {
        return session;
    }

    void session(AgentSession newSession) {
        this.session = newSession;
    }

    AutoCloseable eventSubscription() {
        return eventSubscription;
    }

    void eventSubscription(AutoCloseable subscription) {
        this.eventSubscription = subscription;
    }

    /** 建立事件订阅并推送初始 {@code ready}。 */
    void start() {
        commands.resubscribe();
        send.accept(new WebServerMessage.Ready());
    }

    /** 分发一条客户端消息；异常回 {@code error} 而非抛出（对齐 pi-webui 前端）。 */
    void handle(WebClientMessage msg) {
        try {
            switch (msg) {
                case WebClientMessage.Prompt p -> handlePrompt(p);
                case WebClientMessage.Steer s -> session.steer(s.text());
                case WebClientMessage.FollowUp f -> session.followUp(f.text());
                case WebClientMessage.Abort ignored -> {
                    session.abort();
                    send.accept(stateSync());
                }
                case WebClientMessage.GetModels ignored -> send.accept(models());
                case WebClientMessage.SetModel m -> {
                    // 批 11 T2：走会话包装（pi AgentSession.setModel）——per-model
                    // 思考档覆盖随之应用（与 RpcDispatcher/ModelSelectorScreen 同改道）。
                    session.setModel(resolveModelId(m.provider(), m.modelId()));
                    send.accept(new WebServerMessage.ModelChanged(
                        currentModelInfo(), thinkingWire(session.harness().getThinkingLevel())));
                }
                case WebClientMessage.SetThinkingLevel t -> {
                    session.setThinkingLevel(ThinkingLevels.parse(t.level()));
                    send.accept(new WebServerMessage.ModelChanged(
                        currentModelInfo(), thinkingWire(session.harness().getThinkingLevel())));
                }
                case WebClientMessage.GetState ignored -> send.accept(stateSync());
                case WebClientMessage.NewSession ignored -> commands.newSession();
                case WebClientMessage.GetSessions ignored -> startSessionsListing();
                case WebClientMessage.CancelSessions ignored -> cancelSessionsListing();
                case WebClientMessage.LoadSession l -> commands.loadSession(l.sessionPath());
                case WebClientMessage.ListDir l -> send.accept(dirListing(l.path()));
                case WebClientMessage.ReadFile f -> send.accept(fileContent(f.path()));
                case WebClientMessage.GitStatus ignored -> send.accept(gitStatus());
                case WebClientMessage.GitDiff d -> send.accept(gitDiff(d.file(), d.staged()));
                case WebClientMessage.GitHistory h -> send.accept(gitHistory(h.file(), h.limit()));
                case WebClientMessage.Bash b -> commands.bash(b.command());
                case WebClientMessage.AbortBash ignored -> session.abortBash();
                case WebClientMessage.GetTree ignored -> send.accept(buildTree());
                case WebClientMessage.Fork f -> commands.forkFrom(f.entryId());
                case WebClientMessage.Clone ignored -> commands.cloneSession();
                case WebClientMessage.ExportHtml ignored -> commands.exportHtml();
                case WebClientMessage.ListSkills ignored -> send.accept(listSkills());
                case WebClientMessage.SetSessionName n -> {
                    session.setSessionName(n.name());
                    send.accept(new WebServerMessage.SessionNameChanged(
                        session.sessionId(), session.sessionName()));
                }
                case WebClientMessage.GetSessionStats ignored -> send.accept(sessionStats());
                case WebClientMessage.GetSettings ignored -> send.accept(settingsState());
                case WebClientMessage.SetSetting s -> {
                    setSetting(s.key(), s.value());
                    send.accept(settingsState());
                }
            }
        } catch (Exception e) {
            send.accept(new WebServerMessage.Error(
                e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        }
    }

    /** 关闭当前事件订阅与会话。 */
    void close() {
        commands.closeSubscription();
        try {
            session.close();
        } catch (Exception ignored) {
            // 忽略关闭失败
        }
    }

    // ── 命令实现 ─────────────────────────────────────────────────────────

    private void handlePrompt(WebClientMessage.Prompt p) {
        String text = p.text() == null ? "" : p.text();
        // 异步：事件经订阅推送；结果由 agent_end 收口。
        session.processPrompt(text, PromptConfig.defaults());
    }

    // ── 状态 / 载荷 ──────────────────────────────────────────────────────

    WebServerMessage.StateSync stateSync() {
        var harness = session.harness();
        var model = harness.getModel();
        var messages = session.accumulatedEntries().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> WebWireJson.messageNode(((Entry.Message) e).message()))
            .toList();
        var state = new SerializedAgentState(
            messages,
            model == null ? null : toModelInfo(model),
            thinkingWire(harness.getThinkingLevel()),
            harness.getSystemPrompt(),
            translator.isStreaming(),
            null,
            null,
            harness.getActiveTools().stream().map(t -> t.name()).toList(),
            session.sessionId(),
            session.sessionName());
        return new WebServerMessage.StateSync(state);
    }

    private WebServerMessage.Models models() {
        var all = ModelsJsonConfig.allModels().listModels().stream()
            .map(m -> new ModelInfo(m.id().provider(), m.id().modelName(),
                m.displayName() == null ? m.id().modelName() : m.displayName()))
            .toList();
        return new WebServerMessage.Models(all, currentModelInfo(),
            thinkingWire(session.harness().getThinkingLevel()));
    }

    private WebServerMessage.Sessions sessions() {
        var items = session.listSessionsSummary(System.getProperty("user.dir")).stream()
            .map(WebDispatcher::toSessionListItem)
            .toList();
        return new WebServerMessage.Sessions(items, session.sessionId());
    }

    /** 批 22（T1）：SessionSummary ⇒ 线格的共享映射。 */
    private static SessionListItem toSessionListItem(AgentSession.SessionSummary s) {
        return new SessionListItem(s.id(), s.path(), s.name(), s.cwd(),
            s.created().toString(), Instant.ofEpochMilli(s.modifiedMs()).toString(),
            s.messageCount(), s.firstMessage());
    }

    // ── 批 22（T1，W15）：渐进会话列表（pi SessionListProgress/AbortSignal 对位）──

    /** 进行中的列表任务（代次闸：新 GetSessions 作废旧轮）。 */
    private final java.util.concurrent.atomic.AtomicLong sessionsGeneration =
        new java.util.concurrent.atomic.AtomicLong();
    private volatile com.pijava.ai.AbortSignal sessionsAbort;

    /**
     * pi 选择器重开即新扫的同族语义（裁决④）：每轮列表在虚拟线程上跑富列表；
     * 周期发布点（partial 非空）发 {@code sessionsProgress}，完成发终态
     * {@code sessions}；新一轮/取消作废旧轮（旧轮此后一帧都不发）。
     */
    private void startSessionsListing() {
        cancelSessionsListing();
        long generation = sessionsGeneration.incrementAndGet();
        var signal = com.pijava.ai.AbortSignal.create();
        sessionsAbort = signal;
        Thread.startVirtualThread(() -> {
            try {
                var items = session.listSessionsSummary(System.getProperty("user.dir"),
                    (loaded, total, partials) -> {
                        if (partials == null || generation != sessionsGeneration.get()) {
                            return;   // 非发布点不携快照（pi 同）；代次过期整帧弃
                        }
                        send.accept(new WebServerMessage.SessionsProgress(loaded, total,
                            partials.stream().map(WebDispatcher::toSessionListItem).toList()));
                    }, signal);
                if (generation == sessionsGeneration.get()) {
                    send.accept(new WebServerMessage.Sessions(
                        items.stream().map(WebDispatcher::toSessionListItem).toList(),
                        session.sessionId()));
                }
            } catch (RuntimeException e) {
                if (generation == sessionsGeneration.get()) {
                    send.accept(new WebServerMessage.Error(
                        "Failed to list sessions: " + e.getMessage()));
                }
            }
        });
    }

    /** 测试面：当前列表代次（代次闸断言用）。 */
    long sessionsListingGeneration() {
        return sessionsGeneration.get();
    }

    /** pi 选择器关闭 ⇒ AbortSignal 中止对位。 */
    private void cancelSessionsListing() {
        var signal = sessionsAbort;
        sessionsAbort = null;
        sessionsGeneration.incrementAndGet();   // 代次闸先翻，在飞回调自弃
        if (signal != null) {
            signal.abort();
        }
    }

    /** 当前会话统计快照（{@code getSessionStats} 响应）。 */
    private WebServerMessage.SessionStats sessionStats() {
        var harness = session.harness();
        var transcript = harness.snapshot(session.laneName()).transcript();
        int messageCount = (int) transcript.stream().filter(Entry.Message.class::isInstance).count();
        return new WebServerMessage.SessionStats(
            session.sessionId(), session.sessionName(), messageCount, transcript.size(),
            currentModelInfo(), thinkingWire(harness.getThinkingLevel()));
    }

    // ── Phase 3：设置（web 相关子集，对齐 TUI /settings）────────────────

    private SettingsAccessors settings() {
        return session.services().settings().accessors();
    }

    /** 当前有效设置快照（web 相关 key，null 归默认）。 */
    private WebServerMessage.SettingsState settingsState() {
        var a = settings();
        var m = new LinkedHashMap<String, String>();
        m.put("theme", firstNonNull(a.getTheme(), "dark"));
        m.put("defaultThinkingLevel", firstNonNull(a.getDefaultThinkingLevel(), "off"));
        m.put("steeringMode", firstNonNull(a.getSteeringMode(), "one-at-a-time"));
        m.put("followUpMode", firstNonNull(a.getFollowUpMode(), "one-at-a-time"));
        m.put("defaultProjectTrust", firstNonNull(a.getDefaultProjectTrust(), "prompt"));
        m.put("hideThinkingBlock", String.valueOf(Boolean.TRUE.equals(a.getHideThinkingBlock())));
        return new WebServerMessage.SettingsState(m);
    }

    /** 写单个设置（写入全局 scope 并持久化）。 */
    private void setSetting(String key, String value) {
        var a = settings();
        switch (key == null ? "" : key) {
            case "theme" -> a.setTheme(value);
            case "defaultThinkingLevel" -> a.setDefaultThinkingLevel(value);
            case "steeringMode" -> a.setSteeringMode(value);
            case "followUpMode" -> a.setFollowUpMode(value);
            case "defaultProjectTrust" -> a.setDefaultProjectTrust(value);
            case "hideThinkingBlock" -> a.setHideThinkingBlock(Boolean.parseBoolean(value));
            default -> throw new IllegalArgumentException("Unknown setting: " + key);
        }
        session.services().settings().flush();
    }

    private static String firstNonNull(String v, String dflt) {
        return v == null ? dflt : v;
    }

    // ── Stage B：文件 / git（代码调试）────────────────────────────────────

    private WebServerMessage dirListing(String path) {
        var l = files.listDir(path);
        return new WebServerMessage.DirListing(l.path(), l.entries());
    }

    private WebServerMessage fileContent(String path) {
        var c = files.readFile(path);
        return new WebServerMessage.FileContent(c.path(), c.content(), c.truncated());
    }

    private WebServerMessage gitStatus() {
        var s = git.status(workspace());
        return new WebServerMessage.GitStatusResult(s.cwd(), s.entries());
    }

    private WebServerMessage gitDiff(String file, Boolean staged) {
        var d = git.diff(workspace(), file, Boolean.TRUE.equals(staged));
        return new WebServerMessage.GitDiffResult(d.file(), d.staged(), d.text());
    }

    private WebServerMessage gitHistory(String file, Integer limit) {
        var h = git.history(workspace(), file, limit);
        return new WebServerMessage.GitHistoryResult(h.file(), h.commits());
    }

    private static Path workspace() {
        return Path.of(System.getProperty("user.dir"));
    }

    // ── Stage C：skills（终端 / fork / 导出迁 WebSessionCommands）─────────

    private WebServerMessage.Skills listSkills() {
        var skills = session.harness().skillManager().all().stream()
            .map(s -> new WebProtocol.SkillInfo(s.name(), s.description()))
            .toList();
        return new WebServerMessage.Skills(skills);
    }

    /**
     * 会话树（节点 {@code {entry, children, label?}}），对齐 pi {@code getTree}。
     * docs/19 裁决③：数据源＝{@code session.getTree()}（持久会话全分支＋解析 label），
     * 不再是 harness 单车道转录。
     */
    private WebServerMessage.Tree buildTree() {
        var tree = session.getTree().stream().map(WebDispatcher::treeNode).toList();
        var leafId = session.harness().snapshot(session.laneName()).transcript();
        return new WebServerMessage.Tree(tree,
            leafId.isEmpty() ? null : leafId.get(leafId.size() - 1).id());
    }

    private static ObjectNode treeNode(com.pijava.coding.agent.core.SessionTreeNode treeNode) {
        var node = JSON.createObjectNode();
        node.set("entry", SessionJson.mapper().valueToTree(treeNode.entry()));
        if (treeNode.label() != null) {
            node.put("label", treeNode.label());
        }
        if (treeNode.labelTimestamp() != null) {
            // pi SessionTreeNode.labelTimestamp 是 ISO 串。
            node.put("labelTimestamp", treeNode.labelTimestamp().toString());
        }
        var children = node.putArray("children");
        for (var child : treeNode.children()) {
            children.add(treeNode(child));
        }
        return node;
    }

    // ── 模型辅助 ─────────────────────────────────────────────────────────

    private ModelId<?> resolveModelId(String provider, String modelId) {
        return ModelsJsonConfig.allModels().listModels().stream()
            .map(com.pijava.ai.catalog.ModelInfo::id)
            .filter(id -> id.provider().equals(provider) && id.modelName().equals(modelId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Model not found: " + provider + "/" + modelId));
    }

    private ModelInfo currentModelInfo() {
        var model = session.harness().getModel();
        return model == null ? null : toModelInfo(model);
    }

    private ModelInfo toModelInfo(ModelId<?> model) {
        var name = ModelsJsonConfig.allModels().listModels().stream()
            .filter(m -> m.id().equals(model))
            .findFirst()
            .map(m -> m.displayName() == null ? m.id().modelName() : m.displayName())
            .orElse(model.modelName());
        return new ModelInfo(model.provider(), model.modelName(), name);
    }

    /**
     * 线格式的思考级别（{@code "off"|"minimal"|…|"max"}）。
     *
     * <p>包H5：改用 {@link ModelThinkingLevel#label()}（逐字对齐 pi 的
     * {@code types.ts:84-85}），取代手写 switch —— 后者缺 {@code "max"} 分支。</p>
     */
    private static String thinkingWire(ModelThinkingLevel level) {
        return level == null ? "off" : level.label();
    }

    /** 从会话文件路径提取 id（JSONL 文件名 {@code <iso>_<id>.jsonl}）。 */
    static String idFromPath(String path) {
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        if (base.endsWith(".jsonl")) {
            base = base.substring(0, base.length() - 6);
        }
        int underscore = base.lastIndexOf('_');
        return underscore >= 0 ? base.substring(underscore + 1) : base;
    }
}
