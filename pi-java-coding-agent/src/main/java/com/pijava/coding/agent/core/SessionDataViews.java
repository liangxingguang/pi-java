package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的只读投影簇 —— 车道/会话身份
 * （laneName/isRunning/sessionId/sessionFile/cwd/storedSessionName）、转录读面
 * （entryCount/accumulatedEntries/accumulatedMessages/lastAssistantText/
 * currentLeafId）与扩展面只读出口（startupDiagnostics/toolRenderers/渲染器与
 * 变换器 resolver/extensionResourcePaths）。自 {@code AgentSession} 纯移动
 * （行为零变化）；javadoc/注释逐字随迁。
 */
final class SessionDataViews {

    private static final Logger LOG = LoggerFactory.getLogger(SessionDataViews.class);

    private final AgentSession owner;

    // Phase 6: 会话级事件广播（多监听器）。
    private final SessionEventHub eventHub = new SessionEventHub();

    SessionDataViews(AgentSession owner) {
        this.owner = owner;
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
    AutoCloseable subscribe(java.util.function.Consumer<AgentSessionEvent> listener) {
        return eventHub.subscribe(listener);
    }

    /** 广播会话事件给所有监听器；单个监听器异常被隔离。 */
    void emit(AgentSessionEvent event) {
        eventHub.emit(event);
    }

    /**
     * 会话车道的名字 —— 恒为 {@link com.pijava.agent.harness.AgentHarness#DEFAULT_LANE}
     * （{@code 原 docs/31 §4.3}）。
     *
     * <p>会话不再有「当前车道」这个可变字段：运行时多车道容器已删除，分支是**新会话**
     * （每个会话持有自己的 harness）。名字由 harness 给出。</p>
     */
    String laneName() {
        return owner.harness().laneName();
    }

    /**
     * 是否有 agent run 在飞（A5，{@code 原 docs/25}；pi {@code isStreaming}，
     * {@code agent-session.ts:1430-1432}）。{@code InteractiveMode} 提交门消费者。
     */
    boolean isRunning() {
        return owner.harness().isRunning(laneName());
    }

    /** 会话持久化 id（pi {@code AgentSession.sessionId}）；in-memory 会话回退显示名。 */
    String sessionId() {
        return owner.session() == null ? owner.sessionName() : owner.session().getMetadata().id();
    }

    /**
     * 会话的 JSONL 落盘路径（pi {@code AgentSession.sessionFile}，{@code :1008-1010}）。
     *
     * <p>pi 的类型是 {@code string | undefined} —— 非文件后端（sqlite / in-memory /
     * {@code --no-session}）取不到路径，回 {@code null} ≙ undefined，
     * 线格式上**省略该键**（{@code 原 docs/31 §8.37.4}）。</p>
     */
    String sessionFile() {
        var metadata = owner.session() == null ? null : owner.session().getMetadata();
        return metadata instanceof JsonlSessionMetadata jsonl ? jsonl.path().toString() : null;
    }

    /**
     * 会话工作目录（pi {@code sessionManager.getCwd()} 对位；批 15 崩溃记录与
     * bug 上报包的操作数）。
     */
    String cwd() {
        return owner.harness().toolContext().cwd();
    }

    /**
     * 批 20（B32）：pi {@code sessionManager.getSessionName()}
     * （session-manager.ts:1318-1327）对位——<b>stored</b> 名（最新 session_info
     * 的值），无 ⇒ {@code null}（RPC get_state 键省略；三存储的
     * {@code SessionStorage.getName()} 已 null-when-absent）。与显示名
     * {@code sessionName()}（{@code "session"} 回落）是两件事：显示层继续读后者。
     */
    String storedSessionName() {
        return owner.session() == null ? null : owner.session().getName();
    }

    /** Approximate transcript size for session listings. */
    long entryCount() {
        return owner.harness().snapshot(laneName()).transcript().size();
    }

    /** 全文对话条目（持久会话提交序），供跨回合/重连展示完整历史。 */
    List<Entry> accumulatedEntries() {
        if (owner.session() != null) {
            var entries = owner.session().findEntries(
                new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null));
            if (!entries.isEmpty()) {
                return entries;
            }
            LOG.debug("[session] accumulatedEntries: storage empty ({} entries), falling back to harness recent", 0);
        } else {
            LOG.warn("[session] accumulatedEntries: persistent session is null, using harness recent");
        }
        return owner.harness().snapshot(laneName()).transcript();
    }

    /** Full-history messages (accumulatedEntries projected to their payloads). */
    List<com.pijava.ai.message.Message> accumulatedMessages() {
        return accumulatedEntries().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
    }

    /** The most recent assistant text (for {@code /copy}). */
    String lastAssistantText() {
        var transcript = owner.harness().snapshot(laneName()).transcript();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            var entry = transcript.get(i);
            if (entry instanceof Entry.Message message
                    && "assistant".equals(message.message().role())) {
                var builder = new StringBuilder();
                for (var block : message.message().content()) {
                    if (block instanceof com.pijava.ai.message.ContentBlock.TextContent text) {
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

    /** 批 13（T6）：当前叶 id——{@code CacheWarmer.SessionAccess.getBranch} 的操作数。 */
    String currentLeafId() {
        return owner.harness().snapshot(laneName()).leafId();
    }

    /**
     * 批 19（裁决④善后）：装配期 resolve 的启用扩展资源路径（pi resource-loader
     * 合并臂 :577-578 同序：CLI 先行）——crash-log/bug 上报的扩展枚举源，取代旧
     * {@code ExtensionPackageManager} 目录扫描。装配未完成 ⇒ 空表。
     */
    List<String> extensionResourcePaths() {
        if (owner.resolveAssembly() == null) {
            return List.of();
        }
        return SessionSetup.mergePaths(
            SessionSetup.enabledPaths(owner.resolveAssembly().cli().extensions()),
            SessionSetup.enabledPaths(owner.resolveAssembly().resolved().extensions()));
    }

    /** 批 16（T1d，裁决⑧）：启动期扩展 flag 绑定诊断（只读面）。 */
    java.util.List<com.pijava.coding.agent.cli.Args.ArgDiagnostic> startupDiagnostics() {
        return java.util.List.copyOf(owner.startupDiagnosticsInternal());
    }

    /** 批 16（T4d）：工具渲染器责任链（无扩展装配 ⇒ {@code null}，消费方回落默认）。 */
    com.pijava.coding.agent.extension.ToolRendererRegistry toolRenderers() {
        var context = owner.extensionContext();
        return context == null ? null : context.toolRenderers();
    }

    /**
     * 批 26（docs/37 T9）：pi {@code runner.getShortcuts} 的会话出口——冲突三规则
     * 解决后的扩展快捷键表（TUI 在 attach/switch 时取；无扩展装配 ⇒ 空）。
     * 诊断随 {@code Resolution} 返回（TUI 有 UI ⇒ 不打 stderr，pi 同门）。
     */
    com.pijava.coding.agent.extension.ExtensionShortcuts.Resolution extensionShortcuts(
            KeybindingsManager keys) {
        var context = owner.extensionContext();
        if (context == null) {
            return new com.pijava.coding.agent.extension.ExtensionShortcuts.Resolution(
                List.of(), List.of());
        }
        return context.resolvedShortcuts(keys, true);
    }

    /**
     * 批 26（docs/37 T9）：快捷键 handler 的会话级上下文（pi setupExtensionShortcuts
     * 的 createContext 对位——中性会话 ctx，非 per-extension API；无装配 ⇒ null）。
     */
    com.pijava.coding.agent.extension.ExtensionContext extensionShortcutContext() {
        return owner.extensionContext();
    }

    /**
     * 批 26（docs/37 T10）：pi {@code runner.getMessageRenderer} 的会话出口
     * （first-wins；无装配/无命中 ⇒ null）。
     */
    com.pijava.coding.agent.extension.ExtensionMessageRenderer messageRendererFor(
            String customType) {
        var context = owner.extensionContext();
        return context == null ? null : context.resolveMessageRenderer(customType);
    }

    /**
     * 批 26（docs/37 T10）：pi {@code runner.getEntryRenderer} 的会话出口
     * （first-wins；无装配/无命中 ⇒ null）。
     */
    com.pijava.coding.agent.extension.ExtensionEntryRenderer entryRendererFor(
            String customType) {
        var context = owner.extensionContext();
        return context == null ? null : context.resolveEntryRenderer(customType);
    }

    /**
     * 批 26（docs/37 T11）：pi {@code runner.getMarkdownTransformers} 的会话出口
     * （扩展加载序的变换链；无装配 ⇒ 空表）。
     */
    java.util.List<com.pijava.coding.agent.extension.MarkdownTransformer>
            markdownTransformers() {
        var context = owner.extensionContext();
        return context == null
            ? java.util.List.of() : context.markdownTransformers();
    }
}
