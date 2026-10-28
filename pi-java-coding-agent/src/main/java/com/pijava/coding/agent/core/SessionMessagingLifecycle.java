package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ShellResult;
import com.pijava.ai.AbortSignal;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的消息/生命周期簇 —— bash/条目追加簇、
 * custom 消息簇（sendCustomMessage 五臂）、cache warming 会话面、自动重试簇，
 * 以及 bashAbortSignal／retrySleepActive+retryCancel／cacheWarmer／cacheWarmingBridge
 * 字段。自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class SessionMessagingLifecycle {

    private final AgentSession owner;

    private volatile AbortSignal bashAbortSignal;
    /** docs/16（B252，裁决⑥）：替换/退出关闭幂等门。 */
    private volatile boolean closed;
    // 3d（原 docs/31 §8.22）：pi 的重试中止是「仅退避睡眠存活」的
    // _retryAbortController（agent-session.ts:2948 建、:2963 finally 弃）——
    // 会话侧等价的窗口模拟：onAutoRetryStart 开窗（先清残留计数，≙ 新 controller）、
    // onAutoRetryEnd 关窗；abortRetry() 只在窗口内置旗（窗口外是 no-op，与 pi 一致，
    // 不再有「下次 run 前复位」的 resetRetryAbort —— pi 没有这个概念）。
    // enabled 不再存字段：pi 的 autoRetryEnabled 存取都直通 settingsManager
    // （读 effective.retry.enabled ?? true，写 global + markModified + save）。
    private volatile boolean retrySleepActive;
    private final AtomicBoolean retryCancel = new AtomicBoolean();
    /** 批 13（docs/24 T6）：cache warmer（pi sdk.ts:334-339 对位）——volatile：
     *  读侧是 SessionRunner 驱动线程与 TUI/RPC 线程，写侧仅装配/fork 重建。 */
    private volatile CacheWarmer cacheWarmer;
    /** 批 13（T3）：decide 钩桥——loadExtensions 回填（providerBridge 同点）。 */
    private com.pijava.coding.agent.extension.bridge.CacheWarmingBridge cacheWarmingBridge;

    SessionMessagingLifecycle(AgentSession owner) {
        this.owner = owner;
    }

    /** 装配期写面（{@link SessionWiring}）。 */
    void cacheWarmer(CacheWarmer warmer) {
        this.cacheWarmer = warmer;
    }

    /** 装配期写面（{@link SessionWiring}）。 */
    void cacheWarmingBridge(
            com.pijava.coding.agent.extension.bridge.CacheWarmingBridge bridge) {
        this.cacheWarmingBridge = bridge;
    }

    /** decide 钩桥读面（{@link ExtensionLoading} 回填点）。 */
    com.pijava.coding.agent.extension.bridge.CacheWarmingBridge cacheWarmingBridge() {
        return cacheWarmingBridge;
    }

    /** 关闭幂等门读写面（closeWith 专用）。 */
    boolean closed() { return closed; }
    void closed(boolean value) { this.closed = value; }

    /** closeWith 的停暖臂（pi :1409-1412 对位）——close 组在 {@link SessionDiscoveryFork}。 */
    void cancelCacheWarmer() {
        var warmer = cacheWarmer;
        if (warmer != null) {
            warmer.cancel();
        }
    }

    /** 会话级 bash 执行（RPC {@code bash}）：经 harness shell 运行并发射一条
     *  {@link AgentSessionEvent.BashExecutionUpdate}。v1 阻塞执行，不增量流式。 */
    ShellResult executeBash(String id, String command, boolean excludeFromContext) {
        // docs/15：pi emitUserBash 拦截（rpc-mode.ts:561-580 同位）——{result} 臂
        // 直接落盘返回、不执行；处理器异常沿调用方错误链（不得回落本地执行，pi
        // interactive-mode.ts:6994-6999 注释逐字）。{operations} 臂不移植（窄差 D10）。
        if (owner.extensionFirer() != null) {
            var replaced = owner.extensionFirer().userBash(command, excludeFromContext,
                owner.harness().toolContext().cwd());
            if (replaced.isPresent()) {
                var extensionResult = replaced.get();
                recordBashResult(command, extensionResult, excludeFromContext);
                return extensionResult;
            }
        }
        var toolContext = owner.harness().toolContext();
        var signal = AbortSignal.create();
        bashAbortSignal = signal;
        try {
            var result = toolContext.shell().execute(command, new ShellOptions(
                toolContext.cwd(), toolContext.env(), true,
                java.util.OptionalLong.empty(), signal));
            owner.emitSessionEvent(new AgentSessionEvent.BashExecutionUpdate(id, result.output()));
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
        owner.harness().appendLaneEntry(owner.laneName(), entry);
        owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
        if (owner.session() != null) {
            SessionPersistence.persistPending(owner, owner.session(), owner.laneName());
        }
    }

    /**
     * 批 13（docs/24 T5，pi session-manager.ts:1244-1258 的 {@code appendUsage}
     * 对位；收口样板照 {@link #appendBoundaryEntry}）：模型归因用量条目
     * （不进 LLM 上下文）——追加 ⇒ {@link AgentSessionEvent.EntryAppended} 广播 ⇒
     * 落盘。生产者：{@link CacheWarmer}（kind="cache_warm"）。footer 计入
     * （pi getSessionStats :4192-4193 的 usage 条目臂对位）。
     *
     * <p>双态：run 中经 {@code appendDeferredLaneEntry} 记 deferred write
     * （与 PiLaneSink 的 assistant usage 条目同生命周期），idle 纯追加。</p>
     */
    Entry.Usage appendUsage(String kind, String provider, String model,
                            com.pijava.ai.Usage usage, String note) {
        var entry = owner.harness().appendDeferredLaneEntry(owner.laneName(),
            (seq, parentId) -> new Entry.Usage(
                java.util.UUID.randomUUID().toString(), seq, parentId,
                Instant.now(), kind, provider, model, usage, note,
                null, null, null, null, null));     // 审计五键空（非车道产出）
        owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
        if (owner.session() != null) {
            SessionPersistence.persistPending(owner, owner.session(), owner.laneName());
        }
        owner.harness().addTokens((long) usage.totalTokens());
        return entry;
    }

    /**
     * pi {@code sessionManager.appendCustomEntry} 对位（批 15：bug 上报的会话尾
     * 记录生产者）：追加 {@code custom} 条目（不进 LLM 上下文）⇒
     * {@link AgentSessionEvent.EntryAppended} 广播 ⇒ 落盘。收口样板照
     * {@link #appendUsage}。
     */
    void appendCustomEntry(String customType, Map<String, Object> data) {
        var entry = owner.harness().appendDeferredLaneEntry(owner.laneName(),
            (seq, parentId) -> new Entry.Custom(
                java.util.UUID.randomUUID().toString(), seq, parentId,
                Instant.now(), customType, data));
        owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
        if (owner.session() != null) {
            SessionPersistence.persistPending(owner, owner.session(), owner.laneName());
        }
	}

    // ── 批 26（docs/37 T2）：pi sendCustomMessage 五臂（agent-session.ts:2293-2329）──

    /**
     * pi {@code sendCustomMessage} 全行为对位（批 26，E5-12）。臂序即语义（pi 是
     * if-else 链，次序不可换）：
     *
     * <ol>
     *   <li>{@code deliverAs == NEXT_TURN} ⇒ 进 pendingNextTurnMessages
     *       （无视流式态与 triggerTurn，pi :2306-2307）；</li>
     *   <li>流式中且 {@code triggerTurn != false} ⇒ followUp/steer 队列（默认 steer，
     *       pi :2308-2314）；</li>
     *   <li>{@code triggerTurn == true}（非流式）⇒ settled 发射窗内进
     *       deferredSettledActions，否则异步起一轮（pi :2315-2320；异步与
     *       错误通道对位 pi 的 actions 包装 :3389-3396——失败经 emitError 上报，
     *       {@code event: "send_message"}）；</li>
     *   <li>流式中且 triggerTurn=false ⇒ pendingCustomMessages（此刻不发
     *       任何消息事件，pi :2321-2326）；</li>
     *   <li>其余 ⇒ 立即落盘＋广播（pi :2327-2328）。</li>
     * </ol>
     *
     * @return 立即落盘臂的条目 id；其余臂无即时条目 ⇒ {@code null}
     */
    String sendCustomMessage(String customType,
                             com.pijava.agent.entry.CustomMessageContent content,
                             boolean display, Map<String, Object> details,
                             Boolean triggerTurn, CustomMessageDelivery deliverAs) {
        var payload = new QueuedCustomMessage(customType, content, display, details);
        if (deliverAs == CustomMessageDelivery.NEXT_TURN) {
            synchronized (owner.prompts().pendingNextTurnMessages()) {
                owner.prompts().pendingNextTurnMessages().add(payload);
            }
            return null;
        }
        boolean streaming = owner.harness().isRunning(owner.laneName());
        if (streaming && !Boolean.FALSE.equals(triggerTurn)) {
            if (deliverAs == CustomMessageDelivery.FOLLOW_UP) {
                owner.harness().followUpCustom(owner.laneName(), customType, payload.content(),
                    display, details);
            } else {
                owner.harness().steerCustom(owner.laneName(), customType, payload.content(),
                    display, details);
            }
            return null;
        }
        if (Boolean.TRUE.equals(triggerTurn)) {
            if (owner.emittingAgentSettled) {
                synchronized (owner.prompts().deferredSettledActions()) {
                    owner.prompts().deferredSettledActions().add(() -> driveCustomTurn(payload));
                }
                return null;
            }
            Thread.startVirtualThread(() -> {
                try {
                    driveCustomTurn(payload);
                } catch (Throwable t) {
                    // pi agent-session.ts:3389-3396：runtime 面 catch ⇒ emitError
                    if (owner.extensionFirer() != null) {
                        owner.extensionFirer().bus().emitError(
                            com.pijava.coding.agent.extension.ExtensionError.of(
                                "<runtime>", "send_message", t));
                    }
                }
            });
            return null;
        }
        if (streaming) {
            synchronized (owner.prompts().pendingCustomMessages()) {
                owner.prompts().pendingCustomMessages().add(payload);
            }
            return null;
        }
        return appendCustomMessageNow(payload);
    }

    /**
     * pi {@code _appendCustomMessage}（:2331-2341）对位：落 custom_message 条目＋
     * 重建工作副本（pi {@code _refreshFinalizedContext}）＋广播＋落盘。
     *
     * <p>事件面窄差：pi 发 {@code message_start}/{@code message_end} 对（载荷是
     * custom 消息本体）；java 的 {@code AgentSessionEvent.MessageStart/End} 走
     * {@code ai.Message} 通道载不动 custom role ⇒ 以 {@code EntryAppended} 载
     * （TUI/RPC 消费面同达，docs/37 §5 登记）。</p>
     *
     * @return 条目 id
     */
    private String appendCustomMessageNow(QueuedCustomMessage p) {
        Entry.CustomMessage entry;
        if (owner.harness().isRunning(owner.laneName())) {
            entry = owner.harness().appendDeferredLaneEntry(owner.laneName(),
                (seq, parentId) -> new Entry.CustomMessage(
                    java.util.UUID.randomUUID().toString(), seq, parentId, Instant.now(),
                    p.customType(), p.content(), p.display(), p.details()));
            // pi _refreshFinalizedContext（:938-944）：flush 点可在 run 中途。
            owner.harness().refreshLaneMessages(owner.laneName());
        } else {
            entry = new Entry.CustomMessage(
                java.util.UUID.randomUUID().toString(), 0, null, Instant.now(),
                p.customType(), p.content(), p.display(), p.details());
            owner.harness().appendLaneEntry(owner.laneName(), entry);
        }
        owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
        if (owner.session() != null) {
            SessionPersistence.persistPending(owner, owner.session(), owner.laneName());
        }
        return entry.id();
    }

    /**
     * pi {@code _flushPendingCustomMessages}（:2343-2355）对位。四个调用点：
     * turn_end 派发后（SessionRunner）、run 收尾（SessionRunner）、before_settle
     * 边界（BoundaryBridge）、prompt 主路径（processPrompt）。
     */
    void flushPendingCustomMessages() {
        final List<QueuedCustomMessage> pending;
        synchronized (owner.prompts().pendingCustomMessages()) {
            if (owner.prompts().pendingCustomMessages().isEmpty()) {
                return;
            }
            pending = List.copyOf(owner.prompts().pendingCustomMessages());
            owner.prompts().pendingCustomMessages().clear();
        }
        for (var p : pending) {
            appendCustomMessageNow(p);
        }
    }

    /** pi :2088-2092 的取出半：清空并返回 nextTurn 缓冲（包内可见，processPrompt 用）。 */
    List<QueuedCustomMessage> drainPendingNextTurnMessages() {
        synchronized (owner.prompts().pendingNextTurnMessages()) {
            var drained = List.copyOf(owner.prompts().pendingNextTurnMessages());
            owner.prompts().pendingNextTurnMessages().clear();
            return drained;
        }
    }

    /**
     * pi {@code _runAgentPrompt(appMessage)}（:1822-1851）对位：条目先落（idle 臂
     * 自带重建，尾部即 user 投影）⇒ 续跑（{@code continueRun} 走
     * {@code runContinuation}——尾部非助手 ⇒ 空 prompt 续跑，pi continue 同门）。
     * 调用方保证车道空闲（五臂结构保证：triggerTurn 臂只在非流式到达）。
     */
    private void driveCustomTurn(QueuedCustomMessage p) {
        appendCustomMessageNow(p);
        var queue = new LinkedBlockingQueue<Optional<com.pijava.ai.stream.StreamEvent>>();
        var entriesFuture = new CompletableFuture<List<Entry>>();
        var statusFuture = new CompletableFuture<RunStatus>();
        SessionRunner.driveContinuation(owner, queue, entriesFuture, statusFuture,
            event -> { }, null);
    }

    /**
     * pi {@code _emitAgentSettled}（:1078-1099）对位：发 {@code AgentSettled}（窗内
     * triggerTurn 进延迟表）⇒ 快照延迟表顺序执行。SessionRunner 的两个发射点共用
     * （正常路/catch 路）；cache-warmer 调和在调用点先行（pi :1079 同序）。
     */
    void emitAgentSettled() {
        owner.emittingAgentSettled = true;
        try {
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled(owner.agentRunAbortRequested()));
        } finally {
            owner.emittingAgentSettled = false;
        }
        final List<Runnable> deferred;
        synchronized (owner.prompts().deferredSettledActions()) {
            if (owner.prompts().deferredSettledActions().isEmpty()) {
                return;
            }
            deferred = List.copyOf(owner.prompts().deferredSettledActions());
            owner.prompts().deferredSettledActions().clear();
        }
        for (var action : deferred) {
            action.run();
        }
    }


    // ── 批 13（T8）：cache-warmer 会话四面 ─────────────────────────

    /**
     * pi agent-session.ts:1079（{@code _emitAgentSettled} 第一步）对位——
     * {@code SessionRunner} 在每一处 AgentSettled 发射前调用（包私有）。
     */
    void notifyCacheWarmerSettled() {
        var warmer = cacheWarmer;
        if (warmer != null) {
            warmer.onAgentSettled();
        }
    }

    /**
     * pi :1436-1439 对位：写设置后<b>立即重调和</b>（设置页走这里，不走裸 accessor）。
     */
    void setCacheWarmingMode(String mode) {
        owner.services().settings().network().setCacheWarmingMode(mode);
        var warmer = cacheWarmer;
        if (warmer != null) {
            warmer.onModeChanged();
        }
    }

    /** pi :1431-1433 对位（{@code /session} 的 Cache Warming 段消费）；未装配 ⇒ null。 */
    CacheWarming.Status cacheWarmingStatus() {
        var warmer = cacheWarmer;
        return warmer == null ? null : warmer.status();
    }

    /**
     * docs/15（before_agent_start 的 result.message 落盘点，pi :2092-2104 对位）：
     * 追加 custom message 条目。持久会话走 {@code SessionTree.appendCustomMessageEntry}
     * （含落盘）；无持久会话时退化为 harness 追加（内存态）。
     */
    void appendCustomDraft(
            com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft draft) {
        var content = draft.content() != null ? draft.content()
            : new com.pijava.agent.entry.CustomMessageContent.Text("");
        if (owner.session() != null) {
            owner.session().appendCustomMessageEntry(draft.customType(), content, draft.display(),
                draft.details());
            return;
        }
        owner.harness().appendLaneEntry(owner.laneName(), new Entry.CustomMessage(
            java.util.UUID.randomUUID().toString(), 0, null, Instant.now(),
            draft.customType(), content, draft.display(), draft.details()));
    }

    /**
     * pi {@code recordBashResult}（agent-session.ts:3878-3899）：会话级 bash 结果落会话——
     * 空闲 ⇒ 追加 bashExecution 条目＋刷新工作副本＋即刻落盘；运行中 ⇒ 暂存
     * （pendingBashMessages），settled 时由 {@code SessionRunner} flush
     * （pi 的 {@code _pendingBashMessages}/agent_end，:421,3925-3940，
     * 保 tool_use/tool_result 次序）。
     *
     * <p>ShellResult 语义（#57 现场确认）：{@code cancelled} 引擎无字段 ⇒ 取会话侧
     * {@link #bashAborted}；{@code truncated} 引擎恒 false（截断在工具层，会话级
     * bash 绕开）⇒ 照传、{@code fullOutputPath} 恒 null；中断兜底的 {@code exitCode=-1}
     * ⇒ {@code null} ≙ pi 的 undefined。</p>
     */
    void recordBashResult(String command, ShellResult result, boolean excludeFromContext) {
        var entry = new Entry.BashExecution(
            java.util.UUID.randomUUID().toString(), 0, null, Instant.now(),
            command, result.output(),
            result.exitCode() >= 0 ? result.exitCode() : null,
            bashAborted(), result.truncated(), null,
            excludeFromContext ? Boolean.TRUE : null);
        if (owner.harness().isRunning(owner.laneName())) {
            owner.prompts().pendingBashMessages().add(entry);
        } else {
            owner.harness().appendLaneEntry(owner.laneName(), entry);
            if (owner.session() != null) {
                SessionPersistence.persistPending(owner, owner.session(), owner.laneName());
            }
        }
    }

    /** pi {@code _flushPendingBashMessages} 的取出半：清空并返回暂存队列（包内可见）。 */
    List<Entry.BashExecution> drainPendingBashMessages() {
        var drained = List.copyOf(owner.prompts().pendingBashMessages());
        owner.prompts().pendingBashMessages().clear();
        return drained;
    }

    /** 中止正在执行的会话级 bash（RPC {@code abort_bash}）。 */
    void abortBash() {
        var signal = bashAbortSignal;
        if (signal != null) {
            signal.abort();
        }
    }

    /** 最近一次会话级 bash 是否被中止（RPC {@code bash} 响应 {@code cancelled}）。 */
    boolean bashAborted() {
        var signal = bashAbortSignal;
        return signal != null && signal.isAborted();
    }

    /**
     * 启用/停用自动重试（RPC {@code set_auto_retry}）—— pi
     * {@code setAutoRetryEnabled}（{@code agent-session.ts:2988-2990}）直通
     * {@code settingsManager.setRetryEnabled}：写<b>全局</b> retry.enabled +
     * markModified + save（即时落盘，不同于其余攒到 close 的 setter）。
     */
    void setAutoRetryEnabled(boolean enabled) {
        owner.services().settings().accessors().setRetryEnabled(enabled);
    }

    /** 自动重试是否启用 —— pi {@code get autoRetryEnabled} = {@code getRetryEnabled()}（默认 true）。 */
    boolean autoRetryEnabled() {
        return owner.services().settings().accessors().getRetryEnabled();
    }

    /**
     * 中止进行中的重试退避睡眠（RPC {@code abort_retry}）。pi
     * {@code abortRetry}（:2972-2974）= {@code _retryAbortController?.abort()} ——
     * controller 只在 ① 的退避睡眠内存活，<b>窗口外调用是 no-op</b>（不会顺延
     * 取消下一次重试）。3d 起由 observer 的窗口簿记等价模拟。
     */
    void abortRetry() {
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
}
