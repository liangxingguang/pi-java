package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.coding.agent.export.HtmlExporter;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
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

    private final Args args;
    private final Consumer<WebServerMessage> send;
    private final AgentEventTranslator translator = new AgentEventTranslator();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileBrowserService files;
    private final GitService git = new GitService();
    private AgentSession session;
    private AutoCloseable eventSubscription;
    private long bashSeq;

    WebDispatcher(AgentSession session, Args args, Consumer<WebServerMessage> send) {
        this.session = session;
        this.args = args;
        this.send = send;
        this.files = new FileBrowserService(Path.of(System.getProperty("user.dir")));
    }

    /** 建立事件订阅并推送初始 {@code ready}。 */
    void start() {
        resubscribe();
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
                    session.harness().setModel(resolveModelId(m.provider(), m.modelId()));
                    send.accept(new WebServerMessage.ModelChanged(
                        currentModelInfo(), thinkingWire(session.harness().getThinkingLevel())));
                }
                case WebClientMessage.SetThinkingLevel t -> {
                    session.harness().setThinkingLevel(ThinkingLevels.parse(t.level()));
                    send.accept(new WebServerMessage.ModelChanged(
                        currentModelInfo(), thinkingWire(session.harness().getThinkingLevel())));
                }
                case WebClientMessage.GetState ignored -> send.accept(stateSync());
                case WebClientMessage.NewSession ignored -> newSession();
                case WebClientMessage.GetSessions ignored -> send.accept(sessions());
                case WebClientMessage.LoadSession l -> loadSession(l.sessionPath());
                case WebClientMessage.ListDir l -> send.accept(dirListing(l.path()));
                case WebClientMessage.ReadFile f -> send.accept(fileContent(f.path()));
                case WebClientMessage.GitStatus ignored -> send.accept(gitStatus());
                case WebClientMessage.GitDiff d -> send.accept(gitDiff(d.file(), d.staged()));
                case WebClientMessage.GitHistory h -> send.accept(gitHistory(h.file(), h.limit()));
                case WebClientMessage.Bash b -> bash(b.command());
                case WebClientMessage.AbortBash ignored -> session.abortBash();
                case WebClientMessage.GetTree ignored -> send.accept(buildTree());
                case WebClientMessage.Fork f -> forkFrom(f.entryId());
                case WebClientMessage.Clone ignored -> cloneSession();
                case WebClientMessage.ExportHtml ignored -> exportHtml();
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
        closeSubscription();
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

    private void newSession() {
        closeSubscription();
        var previous = session;
        session = AgentSession.create(args);
        try {
            previous.close();
        } catch (Exception ignored) {
            // 忽略旧会话关闭失败
        }
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(session.sessionId()));
        send.accept(stateSync());
    }

    private void loadSession(String sessionPath) {
        if (sessionPath == null || sessionPath.isBlank()) {
            send.accept(new WebServerMessage.Error("Session path required"));
            return;
        }
        var found = session.findSession(idFromPath(sessionPath));
        if (found.isEmpty()) {
            send.accept(new WebServerMessage.Error("Session not found: " + sessionPath));
            return;
        }
        closeSubscription();
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(session.sessionId()));
        send.accept(stateSync());
    }

    private void resubscribe() {
        closeSubscription();
        eventSubscription = session.subscribe(ev -> {
            for (var msg : translator.translate(ev)) {
                send.accept(msg);
            }
        });
    }

    private void closeSubscription() {
        if (eventSubscription != null) {
            try {
                eventSubscription.close();
            } catch (Exception ignored) {
                // 忽略退订失败
            }
            eventSubscription = null;
        }
    }

    // ── 状态 / 载荷 ──────────────────────────────────────────────────────

    private WebServerMessage.StateSync stateSync() {
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
        var all = ProviderCatalog.allModels().listModels().stream()
            .map(m -> new ModelInfo(m.id().provider(), m.id().modelName(),
                m.displayName() == null ? m.id().modelName() : m.displayName()))
            .toList();
        return new WebServerMessage.Models(all, currentModelInfo(),
            thinkingWire(session.harness().getThinkingLevel()));
    }

    private WebServerMessage.Sessions sessions() {
        var items = session.listSessionsSummary(System.getProperty("user.dir")).stream()
            .map(s -> new SessionListItem(
                s.id(), s.path(), s.name(), s.cwd(),
                s.created().toString(),
                Instant.ofEpochMilli(s.modifiedMs()).toString(),
                s.messageCount(), s.firstMessage()))
            .toList();
        return new WebServerMessage.Sessions(items, session.sessionId());
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

    // ── Stage C：终端 / fork / 导出 / skills ─────────────────────────────

    private void bash(String command) {
        if (command == null || command.isBlank()) {
            send.accept(new WebServerMessage.Error("Command required"));
            return;
        }
        String id = "web-bash-" + (bashSeq++);
        Thread.startVirtualThread(() -> {
            try {
                var result = session.executeBash(id, command, false);
                send.accept(new WebServerMessage.BashResult(
                    command, result.exitCode(), result.output(),
                    result.truncated(), session.bashAborted()));
            } catch (Exception e) {
                send.accept(new WebServerMessage.BashResult(
                    command, -1,
                    e.getMessage() == null ? String.valueOf(e) : e.getMessage(),
                    false, false));
            }
        });
    }

    /** fork：{@code entryId} 之后的会话拷贝为新会话（共享 harness，不关旧会话）。 */
    private void forkFrom(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            send.accept(new WebServerMessage.Error("Entry id required"));
            return;
        }
        closeSubscription();
        session = session.forkFromEntry(entryId);
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(session.sessionId()));
        send.accept(stateSync());
    }

    /** clone：整会话拷贝（无 leaf 时用当前会话名 clone）。 */
    private void cloneSession() {
        var entries = session.harness().snapshot(session.laneName()).transcript();
        String leaf = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        closeSubscription();
        session = leaf == null
            ? session.forkCopy(session.sessionName() + "-clone")
            : session.forkFromEntry(leaf);
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(session.sessionId()));
        send.accept(stateSync());
    }

    private void exportHtml() {
        try {
            var tmp = Files.createTempFile("pi-java-web-export", ".jsonl");
            session.exportJsonl(tmp);
            var html = new HtmlExporter().export(tmp);
            send.accept(new WebServerMessage.ExportPath(html.toString()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Export failed: " + e.getMessage());
        }
    }

    private WebServerMessage.Skills listSkills() {
        var skills = session.harness().skillManager().all().stream()
            .map(s -> new WebProtocol.SkillInfo(s.name(), s.description()))
            .toList();
        return new WebServerMessage.Skills(skills);
    }

    /** 会话树（节点 {@code {entry, children}}），对齐 pi {@code getTree}。 */
    private WebServerMessage.Tree buildTree() {
        var entries = session.harness().snapshot(session.laneName()).transcript();
        var byParent = new LinkedHashMap<String, List<Entry>>();
        for (var e : entries) {
            byParent.computeIfAbsent(e.parentId(), k -> new ArrayList<>()).add(e);
        }
        List<Entry> roots = entries.stream()
            .filter(e -> e.parentId() == null)
            .toList();
        if (roots.isEmpty() && !entries.isEmpty()) {
            roots = List.of(entries.get(0));
        }
        var tree = roots.stream().map(r -> treeNode(r, byParent)).toList();
        String leafId = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        return new WebServerMessage.Tree(tree, leafId);
    }

    private ObjectNode treeNode(Entry entry, Map<String, List<Entry>> byParent) {
        var node = JSON.createObjectNode();
        node.set("entry", SessionJson.mapper().valueToTree(entry));
        var children = node.putArray("children");
        for (var child : byParent.getOrDefault(entry.id(), List.of())) {
            children.add(treeNode(child, byParent));
        }
        return node;
    }

    // ── 模型辅助 ─────────────────────────────────────────────────────────

    private ModelId<?> resolveModelId(String provider, String modelId) {
        return ProviderCatalog.allModels().listModels().stream()
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
        var name = ProviderCatalog.allModels().listModels().stream()
            .filter(m -> m.id().equals(model))
            .findFirst()
            .map(m -> m.displayName() == null ? m.id().modelName() : m.displayName())
            .orElse(model.modelName());
        return new ModelInfo(model.provider(), model.modelName(), name);
    }

    private static String thinkingWire(ModelThinkingLevel level) {
        if (level instanceof ModelThinkingLevel.Enabled e) {
            return switch (e.level()) {
                case ThinkingLevel.Minimal() -> "minimal";
                case ThinkingLevel.Low() -> "low";
                case ThinkingLevel.Medium() -> "medium";
                case ThinkingLevel.High() -> "high";
                case ThinkingLevel.XHigh() -> "xhigh";
            };
        }
        return "off";
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
