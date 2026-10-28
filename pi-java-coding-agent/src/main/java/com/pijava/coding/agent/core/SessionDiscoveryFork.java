package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.core.AgentSession.ForkMessage;
import com.pijava.coding.agent.core.AgentSession.SessionSummary;
import com.pijava.coding.agent.core.AgentSession.SessionSummaryProgress;
import com.pijava.coding.agent.core.AgentSession.SwitchOutcome;
import com.pijava.coding.agent.core.AgentSession.SwitchResult;
import com.pijava.coding.agent.core.session.SessionInfo;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的会话发现/切换簇＋fork/导入导出簇
 * （两簇共享 entries()/repositories 访问）＋关闭簇（close/closeForReplacement/
 * closeForFork/fireSessionShutdown/closeWith——fork 臂与切换臂的拆解点同簇）。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class SessionDiscoveryFork {

    private final AgentSession owner;

    // docs/16（T1）：装配期的**原始** ToolContext（图片策略/PI_* 晚绑定包装之前）——
    // fork 重建会话时 wireSession 的原料（pi createRuntime 重装配 cwd 绑定服务对位）。
    private ToolContext baseToolContext;

    SessionDiscoveryFork(AgentSession owner) {
        this.owner = owner;
    }

    /** 装配原料读写面（wireSession 回填；fork 臂与嵌套调用管线的消费点）。 */
    ToolContext baseToolContext() { return baseToolContext; }
    void baseToolContext(ToolContext toolContext) { this.baseToolContext = toolContext; }

    /**
     * Rename the session ({@code /name}）。
     *
     * <p>pi {@code agent-session.ts:3942-3947}：落盘 → 读回规范化名 → 发事件。
     * 规范化对位 {@code session-manager.ts:1305}（换行消毒 + trim）；空名 ⇒ 清除
     * （:1326-1328，≙ pi 的 undefined）。事件载荷是**规范化后的名字**，不是入参
     * （3686/5996 回归对位）；扩展路 emit 随扩展面包（docs/09 §1.2#3）。</p>
     */
    void setSessionName(String newName) {
        String normalized = newName == null ? null
            : newName.replaceAll("[\\r\\n]+", " ").trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        owner.name(normalized);
        if (owner.session() != null) {
            // appendSessionInfo 对位（PiV3Wire 已映射 session_info 线格式）。
            owner.session().setName(normalized);
        }
        owner.emitSessionEvent(new AgentSessionEvent.SessionInfoChanged(normalized));
    }

    /** List sessions for {@code /session} and the resume picker. */
    List<SessionInfo> listSessions() {
        if (owner.persistentRepository() != null) {
            return owner.persistentRepository().list(System.getProperty("user.dir")).stream()
                .map(meta -> new SessionInfo(meta.id(), "session",
                    meta.createdAt(), 0))
                .toList();
        }
        return owner.inMemoryRepository().list();
    }

    /**
     * 列出 {@code cwd} 下的持久化会话摘要。
     *
     * <p>#79/B55：JSONL 后端走富列表（pi {@code SessionManager.list} —
     * {@code buildSessionInfo} 全文件逐行读，不再逐会话 writer lease）；
     * 其它后端留旧路（逐会话 open，失败降级元数据摘要）。</p>
     */
    List<SessionSummary> listSessionsSummary(String cwd) {
        return listSessionsSummary(cwd, null, null);
    }

    /**
     * 批 22（T1，W15）：带进度回调与中止信号的会话摘要列表（pi
     * {@code SessionManager.list(path, onProgress, signal)} 对位）——
     * JSONL 臂透传 {@code listRich} 的渐进发布（partial 快照逐条映射为
     * {@link SessionSummary}）；其它后端退化臂＝一次性全量＋无中间回调。
     */
    List<SessionSummary> listSessionsSummary(
            String cwd, SessionSummaryProgress progress,
            com.pijava.ai.AbortSignal signal) {
        if (owner.persistentRepository() == null) {
            return List.of();
        }
        String dir = cwd == null || cwd.isBlank() ? System.getProperty("user.dir") : cwd;
        if (owner.persistentRepository().repository()
                instanceof com.pijava.agent.session.jsonl.JsonlSessionRepository repo) {
            return repo.listRich(dir, progress == null ? null
                : (loaded, total, partials) -> progress.onProgress(loaded, total,
                    partials == null ? null
                        : partials.stream().map(SessionDiscoveryFork::toSummary).toList()),
                signal).stream().map(SessionDiscoveryFork::toSummary).toList();
        }
        var result = new java.util.ArrayList<SessionSummary>();
        for (var meta : owner.persistentRepository().list(dir)) {
            result.add(summaryFor(meta));
        }
        return result;
    }

    /** 批 22（T1）：RichSessionInfo ⇒ SessionSummary 的共享映射。 */
    private static SessionSummary toSummary(
            com.pijava.agent.session.jsonl.RichSessionInfo info) {
        return new SessionSummary(info.id(), info.path(),
            info.name() == null ? "" : info.name(), info.cwd(),
            java.time.Instant.ofEpochMilli(info.createdMs()), info.modifiedMs(),
            info.messageCount(), info.firstMessage());
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
            opened = owner.persistentRepository().open(meta);
            name = opened.getName();
            messageCount = (int) opened.getStats().messageCount();
            var first = opened.findEntries(
                new EntryQuery("message", null, EntryOrder.OLDEST_FIRST, 1, null));
            if (!first.isEmpty() && first.getFirst() instanceof Entry.Message m
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
    Optional<AgentSession> latestSession() {
        if (owner.persistentRepository() != null) {
            return owner.persistentRepository().latest(System.getProperty("user.dir")).map(meta -> {
            SessionPersistence.attach(owner, owner.persistentRepository(), meta);
            return owner;
        });
        }
        return owner.inMemoryRepository().latest();
    }

    /** Find a session by ID/prefix ({@code -r/--session}). */
    Optional<AgentSession> findSession(String idOrPrefix) {
        if (owner.persistentRepository() != null) {
            return owner.persistentRepository().find(idOrPrefix).map(meta -> {
            SessionPersistence.attach(owner, owner.persistentRepository(), meta);
            return owner;
        });
        }
        return owner.inMemoryRepository().find(idOrPrefix);
    }

    /**
     * 批 20（B257）：pi {@code emitBeforeSwitch}（agent-session-runtime.ts:230-234）
     * 的公开发射口——{@code session_before_switch} 可取消事件；返回 {@code true}
     * 即<b>已被扩展取消</b>（调用方不得替换会话）。{@code switchTo} 与 RPC
     * new_session 共用（后者 pi 序：取消 ⇒ {@code {cancelled:true}} 不替换）。
     *
     * @param reason           pi 线值（{@code "new"|"resume"|"fork"|"import"}…）
     * @param targetSessionFile 目标会话（{@code "new"} 无目标 ⇒ {@code null}，pi 同形）
     */
    boolean fireBeforeSwitch(String reason, String targetSessionFile) {
        return owner.extensionFirer() != null
            && owner.extensionFirer().sessionBeforeSwitch(reason, targetSessionFile);
    }

    /**
     * docs/15（B249④/B250）：pi {@code AgentSessionRuntime.switchSession}/
     * {@code importFromJsonl}（agent-session-runtime.ts:194-214, 355-397）的序列集中点——
     * {@code session_before_switch}（cancel ⇒ 旧会话不动）→ 预检目标存在（pi 先开
     * SessionManager 再 teardown）→ {@code session_shutdown{resume, target}} → 原地
     * attach → {@code session_start{resume, previousSessionFile}}。原地换装下
     * shutdown/start 在同一条 bus 上发射（pi 是旧/新两条 runner，窄差 D23）。
     */
    SwitchResult switchTo(String idOrPrefix) {
        // 批 20：上提共用 fireBeforeSwitch（同码）。
        if (fireBeforeSwitch("resume", idOrPrefix)) {
            return new SwitchResult(SwitchOutcome.CANCELLED, owner);
        }
        boolean exists = owner.persistentRepository() != null
            ? owner.persistentRepository().find(idOrPrefix).isPresent()
            : owner.inMemoryRepository().find(idOrPrefix).isPresent();
        if (!exists) {
            return new SwitchResult(SwitchOutcome.NOT_FOUND, owner);
        }
        var previousId = owner.sessionId();
        // 批 17（T7）：pi setBeforeSessionInvalidate 对位——切换前复位扩展 UI。
        owner.extensionUI().onSessionInvalidated();
        // 批 26（docs/37 T7）：pi runtime.invalidate 的 eventBus 半——通道订阅统一退订。
        if (owner.extensionContext() != null) {
            owner.extensionContext().invalidateChannels();
        }
        fireSessionShutdown("resume", idOrPrefix);
        var found = findSession(idOrPrefix).orElseThrow();
        // 批 17 评审回补（Important #2）：模式层 UI/mode 注入换入会话（pi
        // rebindSession 对位）——内存路 found 是另一对象（其字段是 noop），
        // 持久路原地 attach ⇒ found==this（自赋恒等无害）。
        found.extensionUI(owner.extensionUI(), owner.extensionMode());
        // session_start 在**新侧**发射（pi 的 createRuntime 对位）：持久路原地
        // attach ⇒ found == this；内存路是另一会话对象，firer 在那一侧。
        var startFirer = found.extensionFirer() != null ? found.extensionFirer()
            : owner.extensionFirer();
        if (startFirer != null) {
            startFirer.sessionStart("resume", previousId);
        }
        return new SwitchResult(SwitchOutcome.SWITCHED, found);
    }

    /** Export the current session to a JSONL file ({@code /export}). */
    void exportJsonl(java.nio.file.Path target) {
        SessionPersistence.exportJsonl(owner, target);
    }

    /** Import a JSONL file into a new persistent session ({@code /import}). */
    AgentSession importJsonl(java.nio.file.Path source) {
        return SessionPersistence.importJsonl(owner, source);
    }

    /**
     * Create a forked copy ({@code /fork /clone --fork}）：整棵树都复制（pi {@code ForkOptions.Tree}）。
     *
     * <p>docs/15（B249④）＋docs/16（B252）：先发射 {@code session_before_fork
     * {entryId:当前叶, position:"at"}}（整枝拷贝的映射，窄差 D17）——cancel ⇒
     * {@code Optional.empty}（pi 的 {@code {cancelled:true}}）；建成后旧会话经
     * {@code closeForFork} 真正关闭（内含 {@code session_shutdown{fork, 新会话 id}}，
     * pi teardownCurrent 对位），forked 再全量重装扩展（start{fork}）。</p>
     */
    Optional<AgentSession> forkCopy(String branchName) {
        var leafId = owner.harness().snapshot(owner.laneName()).leafId();
        if (owner.extensionFirer() != null && owner.extensionFirer().sessionBeforeFork(leafId, "at")) {
            return Optional.empty();
        }
        var previousId = owner.sessionId();
        var copy = forkCopyInternal(branchName);
        // docs/16（T2，pi teardownCurrent 对位）：旧会话真正关闭（abort→shutdown→
        // 关独占资源），共享的 repository 句柄留给 forked。
        closeForFork(copy.sessionId());
        // docs/16（T1，pi §1.2 序：teardown → createRuntime → rebind）：forked 会话
        // 全量重装扩展并发射 session_start{fork, previousSessionFile} —— 与全新会话
        // 享有完全相同的扩展面（pi bindExtensions 同一口，agent-session.ts:3258-3281）。
        // 批 17（T1）：mode 随 UI 字段一同携带（pi 的 runner mode 重建对位）。
        copy.extensions().extensionMode(owner.extensionMode());
        ExtensionLoading.loadExtensions(owner.sessionArgs(), copy.services(), copy.harness(),
            owner.extensionUI(), copy,
            new AgentSessionFactory.SessionStartInfo("fork", previousId));
        return Optional.of(copy);
    }

    /**
     * docs/16（T1）：fork 臂的 wireSession 调用 —— 携带父会话当前 model/thinkingLevel
     * （裁决①），注册表 fresh、进程级件共享（裁决②）。
     */
    private AgentSession wireForkSession(String name) {
        var currentModel = owner.harness().getModel();
        return SessionWiring.wireSession(owner.sessionArgs(), owner.services().settings(),
            owner.services().providers(), baseToolContext,
            owner.services().sessionRepository(), true, name,
            currentModel == null ? null
                : currentModel.provider() + "/" + currentModel.modelName(),
            owner.harness().getThinkingLevel());
    }

    private AgentSession forkCopyInternal(String branchName) {
        if (owner.persistentRepository() != null && owner.session() != null) {
            var metadata = owner.session().getMetadata();
            var forked = owner.persistentRepository().fork(metadata, new ForkOptions.Tree(),
                System.getProperty("user.dir"));
            var copy = wireForkSession(branchName);
            copy.persistentRepository(owner.persistentRepository());
            copy.session(forked);
            // forked 已是开着的 storage（attachOpened 恒等跳过 §3.7 的旧 storage 关闭）。
            SessionPersistence.attachOpened(copy, forked, forked.getMetadata());
            copy.name(branchName);
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
    Optional<AgentSession> forkFromEntry(String entryId) {
        if (owner.extensionFirer() != null
                && owner.extensionFirer().sessionBeforeFork(entryId, "before")) {
            return Optional.empty();
        }
        var previousId = owner.sessionId();
        var copy = forkFromEntryInternal(entryId);
        closeForFork(copy.sessionId());
        // docs/16（T1）：与 forkCopy 同序 —— forked 全量重装扩展＋start{fork}。
        // 批 17（T1）：mode 随 UI 字段一同携带。
        copy.extensions().extensionMode(owner.extensionMode());
        ExtensionLoading.loadExtensions(owner.sessionArgs(), copy.services(), copy.harness(),
            owner.extensionUI(), copy,
            new AgentSessionFactory.SessionStartInfo("fork", previousId));
        return Optional.of(copy);
    }

    private AgentSession forkFromEntryInternal(String entryId) {
        String branchName = owner.sessionName() + "-fork";
        if (owner.persistentRepository() != null && owner.session() != null) {
            var metadata = owner.session().getMetadata();
            var forked = owner.persistentRepository().fork(metadata,
                new ForkOptions.Branch(entryId, ForkOptions.Branch.Position.BEFORE),
                System.getProperty("user.dir"));
            var copy = wireForkSession(branchName);
            copy.persistentRepository(owner.persistentRepository());
            copy.session(forked);
            SessionPersistence.attachOpened(copy, forked, forked.getMetadata());
            copy.name(branchName);
            return copy;
        }
        return forkInMemory(branchName, entriesBefore(entryId));
    }

    /** 本会话车道上的 entry 日志（副本）。 */
    private List<Entry> entries() {
        return owner.harness().snapshot(owner.laneName()).transcript();
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
        var forked = wireForkSession(branchName);
        forked.harness().seedTranscript(AgentHarness.DEFAULT_LANE, seedEntries);
        forked.inMemoryRepository(owner.inMemoryRepository());
        owner.inMemoryRepository().create(forked);
        return forked;
    }

    /**
     * 可供 fork 的用户消息（docs/21 T1；pi {@code getUserMessagesForForking}，
     * agent-session.ts:4161-4178 逐字）：数据源＝{@code accumulatedEntries()}
     * （持久会话<b>全分支</b>提交序，pi {@code sessionManager.getEntries()} 对位；
     * 无持久面 harness 转录兜底——既有兜底语义保留）；text 块空串拼接
     * （pi {@code contentText(content, "")}），空文本滤掉（pi {@code if (text)}）。
     */
    List<ForkMessage> getUserMessagesForForking() {
        var result = new ArrayList<ForkMessage>();
        for (var entry : owner.accumulatedEntries()) {
            if (entry instanceof Entry.Message m && "user".equals(m.message().role())) {
                var text = TreeCompaction.textOf(m.message().content(), "");
                if (!text.isEmpty()) {
                    result.add(new ForkMessage(m.id(), text));
                }
            }
        }
        return result;
    }

    /** Flush settings, persist pending writes and close the harness. */
    void close() {
        closeWith("quit", null, true);
    }

    /**
     * docs/15（B250）：替换式关闭（pi {@code teardownCurrent}，
     * agent-session-runtime.ts:168-178）——先发射 {@code session_shutdown{reason,
     * targetSessionFile}}，再走原关闭流程。
     */
    void closeForReplacement(String reason, String targetSessionFile) {
        // 批 17（T7，docs/28 §3-T7.2）：pi setBeforeSessionInvalidate 对位——
        // 会话失效前复位扩展 UI（活动对话框/注入槽位/状态段）。
        owner.extensionUI().onSessionInvalidated();
        // 批 26（docs/37 T7）：pi runtime.invalidate 的 eventBus 半——通道订阅统一退订。
        if (owner.extensionContext() != null) {
            owner.extensionContext().invalidateChannels();
        }
        closeWith(reason, targetSessionFile, true);
    }

    /**
     * docs/16（B252）：fork 臂拆解 —— persistentRepository 句柄与 services 由
     * forked 继承，不关（父关即杀子，§2.5③）。
     */
    void closeForFork(String forkedSessionId) {
        // 批 17（T7）：fork 同属会话失效（pi 的 invalidate 覆盖一切 swap）——
        // 扩展 UI 先复位（Ruling：设计稿 T7.2 只列 closeForReplacement/switchTo，
        // fork 臂补上以对齐 pi「一切 swap 前复位」语义）。
        owner.extensionUI().onSessionInvalidated();
        // 批 26（docs/37 T7）：pi runtime.invalidate 的 eventBus 半——通道订阅统一退订。
        if (owner.extensionContext() != null) {
            owner.extensionContext().invalidateChannels();
        }
        closeWith("fork", forkedSessionId, false);
    }

    /**
     * docs/15（B249④）：仅发射 {@code session_shutdown}（不关闭）——switchTo 的
     * 原地 attach 路会话对象不换（持久路），只发拆解通知（pi teardownCurrent 语义）。
     */
    void fireSessionShutdown(String reason, String targetSessionFile) {
        if (owner.extensionFirer() != null) {
            owner.extensionFirer().sessionShutdown(reason, targetSessionFile);
        }
    }

    /**
     * pi teardownCurrent（agent-session-runtime.ts:167-178）＋ dispose
     * （agent-session.ts:1393-1414）对位：abort 在途（让被中止的 turn 落盘到出站
     * 会话，:168/:1395-1401 第一步）→ shutdown 事件 → 关独占资源。
     * 幂等（裁决⑥）：二次调用零效果（不重发事件、不双关句柄）。
     */
    private void closeWith(String reason, String targetSessionFile, boolean closeHandle) {
        if (owner.messaging().closed()) {
            return;
        }
        owner.messaging().closed(true);
        // docs/16 裁决⑤：abort() 内含 abortCompaction/abortBranchSummary/abortRetry
        // 并置中止旗 —— pi 的 settle 在途 turn 第一步（原实现只 abortRetry）。
        owner.abort();
        // 批 13（T8-②，pi :1409-1412 对位）：dispose 停暖（onWarmed 无——裁决⑧，
        // 广播内建于 appendUsage 收口）。
        owner.messaging().cancelCacheWarmer();
        // pi dispose/teardownCurrent：shutdown 事件在 dispose 之前（:258-267）。
        fireSessionShutdown(reason, targetSessionFile);
        if (owner.session() != null) {
            SessionPersistence.persistPending(owner, owner.session(), owner.laneName());
            owner.session().close();
        }
        if (closeHandle && owner.persistentRepository() != null) {
            owner.persistentRepository().close();
        }
        owner.services().settings().flush();
        owner.harness().close();
    }
}
