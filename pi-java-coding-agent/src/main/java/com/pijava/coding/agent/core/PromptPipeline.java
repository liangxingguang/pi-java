package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.stream.StreamEvent;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的 prompt 驱动/队列簇 ——
 * processPrompt 三形态、abort、steer/followUp、扩展命令簇
 * （throwIfExtensionCommand/executeExtensionCommand）、queue_update/clearQueue，
 * 以及 pendingBashMessages／pendingNextTurnMessages／pendingCustomMessages／
 * deferredSettledActions 四缓冲。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 *
 * <p>⚠️ 批 20 评审 Critical：executeExtensionCommand 的同步 join 包装在独立
 * 虚拟线程（processPrompt 的 {@code SessionResult.handledBy(() -> ...)}）——
 * 该包装逐字随行，死锁不得复活。</p>
 */
final class PromptPipeline {

    /** A5（原 docs/25）：并发 prompt 路由门，无状态。 */
    private static final PromptRouter PROMPT_ROUTER = new PromptRouter();

    private final AgentSession owner;

    // pi _pendingBashMessages（agent-session.ts:421）：运行中暂存的会话级 bash 结果，
    // settled 时 flush（保 tool_use/tool_result 次序）。
    private final List<Entry.BashExecution> pendingBashMessages = new ArrayList<>();
    // ── 批 26（docs/37 T2）：pi sendCustomMessage 的三缓冲对位 ──
    // （java 多线程方言：pi 单线程数组 ⇒ CopyOnWriteArrayList——写稀读快照，扩展
    // 调用线程与 run 线程并发安全）
    /** pi {@code _pendingNextTurnMessages}（:397）：随下一条用户 prompt 注入的 custom 消息。 */
    private final List<QueuedCustomMessage> pendingNextTurnMessages =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /** pi {@code _pendingCustomMessages}（:400）：流式中 triggerTurn=false 的延迟落盘缓冲。 */
    private final List<QueuedCustomMessage> pendingCustomMessages =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /** pi {@code _deferredSettledActions}（:434）：settled 发射窗内 triggerTurn 的延迟动作。 */
    private final List<Runnable> deferredSettledActions =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * 批 20（B237，裁决②）：扩展命令执行件的 {@code SlashContext} 供应器——
     * pi {@code createCommandContext}（runner.ts:990-1024）的 java 最低可行为形：
     * TUI 装配期注入真回调（quit/switchSession），RPC/print 维持默认
     * （{@code SlashContext.of}，no-op 回调——窄差 D119）。volatile：写侧是
     * 模式层装配线程，读侧是命令执行线程（批 17 extensionUI 同手法）。
     */
    private volatile java.util.function.Supplier<com.pijava.coding.agent.core.slash.SlashContext>
        slashContextSupplier;

    PromptPipeline(AgentSession owner) {
        this.owner = owner;
        // 默认臂在此赋值而非字段初始化器——blank final owner 在初始化器时点未赋值（DU 分析）。
        this.slashContextSupplier =
            () -> com.pijava.coding.agent.core.slash.SlashContext.of(owner);
    }

    /** {@link SessionMessagingLifecycle} 的 bash/custom 缓冲共享面（同一对象，保序语义随迁）。 */
    List<Entry.BashExecution> pendingBashMessages() {
        return pendingBashMessages;
    }

    /** pi {@code _pendingNextTurnMessages} 共享面。 */
    List<QueuedCustomMessage> pendingNextTurnMessages() {
        return pendingNextTurnMessages;
    }

    /** pi {@code _pendingCustomMessages} 共享面。 */
    List<QueuedCustomMessage> pendingCustomMessages() {
        return pendingCustomMessages;
    }

    /** pi {@code _deferredSettledActions} 共享面。 */
    List<Runnable> deferredSettledActions() {
        return deferredSettledActions;
    }

    /**
     * 批 20（B237，裁决②）：注入扩展命令的 {@code SlashContext} 供应器
     * （TUI 装配期调用；{@code null} 忽略）。会话替换后须对新会话重注
     * （批 17 extensionUI 重绑同缝）。
     */
    void slashContextSupplier(
            java.util.function.Supplier<com.pijava.coding.agent.core.slash.SlashContext> supplier) {
        if (supplier != null) {
            this.slashContextSupplier = supplier;
        }
    }

    /** Run a prompt, forwarding events/entries to observers (one virtual thread). */
    SessionResult processPrompt(
            String prompt,
            PromptConfig config,
            StreamObserver streamObserver,
            EntryObserver entryObserver) {
        // 批 26（docs/37 T3，pi :1968-1972 对位）：settled 发射窗内的 prompt 延迟到
        // settled 收尾后执行（pi 进 _deferredSettledActions）。外层 SessionResult 的
        // stream/futures 经桥接跟随内层。
        if (owner.emittingAgentSettled) {
            var deferredQueue = new LinkedBlockingQueue<Optional<StreamEvent>>();
            var deferredEntries = new CompletableFuture<List<Entry>>();
            var deferredStatus = new CompletableFuture<RunStatus>();
            deferredSettledActions.add(() -> {
                var inner = processPrompt(prompt, config,
                    streamObserver == null
                        ? event -> deferredQueue.add(Optional.of(event)) : streamObserver,
                    entryObserver);
                inner.statusFuture().whenComplete((st, err) -> {
                    if (err != null) {
                        deferredStatus.completeExceptionally(err);
                        deferredEntries.completeExceptionally(err);
                    }
                });
                deferredEntries.complete(inner.entries());
                deferredStatus.complete(inner.status());
                deferredQueue.add(Optional.empty());
            });
            Stream<StreamEvent> stream = Stream.generate(() -> {
                try {
                    return deferredQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.<StreamEvent>empty();
                }
            }).takeWhile(Optional::isPresent).map(Optional::get);
            return new SessionResult(stream, deferredEntries, deferredStatus, "deferred");
        }
        boolean expandPromptTemplates = config.expandOrDefault();
        // 批 20（B237，pi agent-session.ts:1978-1984 逐字序）：扩展命令臂在
        // input 事件与压缩门**之前**——"Handle extension commands first (execute
        // immediately, even during streaming)"（pi 注释逐字）；handled ⇒ 不展开、
        // 不排队、不驱动。批 26（docs/37 T3，裁决⑨）：expandPromptTemplates 门落地
        //（pi :1977——false ⇒ 不派发扩展命令；CLI/TUI 存量路径恒 true）。
        if (expandPromptTemplates && prompt.startsWith("/")) {
            var command = owner.services().slashCommands() == null ? null
                : owner.services().slashCommands().extensionCommand(prompt);
            if (command != null) {
                // 批 20 评审回补（I-1）：命令体独立虚拟线程执行（pi 的 void 异步
                // 语义）——同步 join 会死锁 RPC 读线程（ctx.ui 对话的应答线由同一
                // 线程读）并冻结 TUI 渲染线程。
                return SessionResult.handledBy(() -> executeExtensionCommand(command, prompt));
            }
        }
        // docs/15：pi :1991-2003 —— input 事件在 skill/模板展开**之前**
        // （pi 注释逐字："Emit input event for extension interception (before
        // skill/template expansion)"）；handled ⇒ 不展开、不排队、不驱动。
        String inputText = prompt;
        if (owner.extensionFirer() != null) {
            var outcome = owner.extensionFirer().input(prompt,
                config.source() == null ? "interactive" : config.source(),
                // 批 20 评审回补（I-3，pi agent-session.ts:1996 逐字）：
                // streamingBehavior 仅流式中传（this.isStreaming ? … : undefined）——
                // 与 queueUserInput 同一门；空闲时带 behavior 的 prompt（RPC 常见）
                // 不再把 "steer" 泄给 input 处理器。
                config.streamingBehavior() == null || !owner.harness().isRunning(owner.laneName())
                    ? null : config.streamingBehavior().wireName());
            if (outcome.handled()) {
                return SessionResult.handled();
            }
            inputText = outcome.text();
        }
        // pi :2008-2009（prompt 主路径）与 :2191-2192（队列路径同序）：先 /skill: 后模板。
        // 批 26（docs/37 T3，裁决⑨）：expandPromptTemplates 门（pi :2007）——false ⇒
        // 原文透传（扩展 sendUserMessage 默认 false，pi :2390）。
        var expandedPrompt = expandPromptTemplates ? expandUserPrompt(inputText) : inputText;
        // A5（原 docs/25）：路由门在任何副作用之前 —— 压缩中/运行中未指定
        // streamingBehavior 的 prompt 在调用线程同步抛出（pi 同形状，
        // agent-session.ts:1939-1979）；运行中显式 steer/followUp 仅排队。
        var verdict = PROMPT_ROUTER.route(
            // docs/18 终审①：走会话级 isCompacting（含分支摘要在飞，pi getter 同形），
            // 不直达 harness——否则摘要窗口期 prompt 起跑新 run 撕转录。
            owner.isCompacting(),
            owner.harness().isRunning(owner.laneName()),
            config.streamingBehavior());
        if (verdict == PromptRouter.Verdict.QUEUE_STEER) {
            // 批 20（B237）：images 随排队走（pi prompt 流式臂 :2016-2023
            // _queueSteer(expandedText, currentImages) 对位）。
            owner.harness().steer(owner.laneName(), expandedPrompt, config.images());
            // B34（原 docs/26）：pi _queueSteer 排队后发 queue_update（:2193）。
            emitQueueUpdate();
            return SessionResult.queued();
        }
        if (verdict == PromptRouter.Verdict.QUEUE_FOLLOW_UP) {
            owner.harness().followUp(owner.laneName(), expandedPrompt, config.images());
            emitQueueUpdate();
            return SessionResult.queued();
        }
        if (config.systemPrompt() != null) {
            owner.harness().setSystemPrompt(config.systemPrompt());
        }
        if (config.thinkingLevel() != null) {
            owner.setThinkingLevel(config.thinkingLevel());
        }
        // docs/15：pi :2061-2110 —— before_agent_start（仅非流式驱动路；流式排队路
        // 在上面路由门已返回）。systemPrompt 覆盖是 harness 全局态（窄差 D11）；
        // result.message 落盘序为 custom 条目先于用户消息（窄差 D19）。
        // 批 26（docs/37 T2，pi :2028-2030 对位）：新 prompt 驱动前 flush 延迟
        // custom 消息（在 before_agent_start 与模型校验之前，pi 同序）。
        owner.flushPendingCustomMessages();
        if (owner.extensionFirer() != null) {
            // 批 16（T3c）：sections 基线＝harness 当前自定义段的拷贝，处理器改写
            // 的终态写回 harness——下一 run 的 promptLoadout diff 自动产补丁
            //（pi systemPromptOptions.sections 贯穿处理器、写回装配输入的链对位）。
            var start = owner.extensionFirer().beforeAgentStart(expandedPrompt,
                owner.harness()::getSystemPrompt, owner.harness().getPromptSections());
            for (var draft : start.messages()) {
                owner.messaging().appendCustomDraft(draft);
            }
            if (start.systemPrompt() != null) {
                owner.harness().setSystemPrompt(start.systemPrompt());
            }
            owner.harness().setPromptSections(start.sections());
        }
        // 批 24（docs/35 D4 臂4，pi agent-session.ts:1829 逐字）：run 记装配进转录
        // ——到此仍未注册的恢复工具放弃等待（永不注册的工具不留 pending）。
        owner.harness().pendingToolNames().clear();

        var queue = new LinkedBlockingQueue<Optional<StreamEvent>>();
        var entriesFuture = new CompletableFuture<List<Entry>>();
        var statusFuture = new CompletableFuture<RunStatus>();

        // 批 26（docs/37 T2，pi :2088-2092 对位）：nextTurn custom 消息随本条
        // prompt 注入（排在用户消息之后，经 initialMessages 进待发列表）。
        var nextTurnCustoms = owner.drainPendingNextTurnMessages().stream()
            .map(QueuedCustomMessage::toQueuedItem).toList();

        Thread.startVirtualThread(() -> SessionRunner.drive(
            owner, expandedPrompt, config.images(), nextTurnCustoms, queue, entriesFuture,
            statusFuture, streamObserver, entryObserver));

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

    /**
     * pi {@code abortCompaction()}（agent-session.ts:2914-2917，#98/B36）：
     * 中止在飞压缩（无在飞 ⇒ no-op）。
     */
    void abortCompaction() {
        owner.harness().abortCompaction(owner.laneName());
    }

    /** Abort the current run (cross-thread safe via the harness AbortSignal). */
    void abort() {
        // pi :2438-2440：abort() 内含 abortCompaction()＋abortBranchSummary()。
        abortCompaction();
        owner.abortBranchSummary();
        // pi abort()（:1639-1646）第一步就是 abortRetry() —— 若正卡在 ① 的
        // 退避睡眠里，先把它掐掉（"Retry cancelled"），再中止 run 本身。
        owner.abortRetry();
        // pi :2434-2436：abort 请求旗置位（pi 有 _isAgentRunActive 门；java 侧旗在
        // 驱动起手清零，空转期置位无害）。settled 事件的 aborted 载荷读它。
        owner.agentRunAbortRequested.set(true);
        owner.harness().abort(owner.laneName());
    }

    /**
     * Queue a follow-up message (processed when the current run finishes).
     *
     * <p>批 20（B237）：返回 pi {@code QueuedInputDisposition}
     * （{@code "handled"|"queued"}，agent-session.ts:310），不再是排队 id——
     * 全仓无调用方消费旧 id（InteractiveMode/RpcDispatcher/CompactionInputBuffer
     * 均语句位）。</p>
     */
    String followUp(String prompt) {
        return queueUserInput(prompt, null, StreamingBehavior.FOLLOW_UP, "interactive");
    }

    /** pi {@code followUp(text, images, {source})}（agent-session.ts:2227-2234）全参形。 */
    String followUp(String prompt,
                    List<com.pijava.agent.harness.PromptImage> images, String source) {
        return queueUserInput(prompt, images, StreamingBehavior.FOLLOW_UP, source);
    }

    /**
     * pi {@code _queueUserInput}（agent-session.ts:2173-2200 逐字序）：扩展命令抛 →
     * input 事件（handled 短路／transform 换文）→ 先 /skill: 后模板展开 → 排队 →
     * queue_update。
     */
    private String queueUserInput(String text,
                                  List<com.pijava.agent.harness.PromptImage> images,
                                  StreamingBehavior behavior, String source) {
        if (text.startsWith("/")) {
            throwIfExtensionCommand(text);
        }
        String current = text;
        if (owner.extensionFirer() != null) {
            // pi :2183-2188：streamingBehavior 仅流式中传入（this.isStreaming ?
            // behavior : undefined 逐字）。
            var outcome = owner.extensionFirer().input(text, source,
                owner.harness().isRunning(owner.laneName()) ? behavior.wireName() : null);
            if (outcome.handled()) {
                return "handled";   // pi :2189
            }
            current = outcome.text();   // images 不换（D12 延伸，窄差 D120）
        }
        // pi :2191-2192：先 /skill: 后模板。
        var expanded = expandUserPrompt(current);
        if (behavior == StreamingBehavior.STEER) {
            owner.harness().steer(owner.laneName(), expanded, images);
        } else {
            owner.harness().followUp(owner.laneName(), expanded, images);
        }
        // B34（原 docs/26）：pi _queueSteer/_queueFollowUp 入队后发 queue_update
        //（agent-session.ts:2243/:2259）。
        emitQueueUpdate();
        return "queued";
    }

    /**
     * pi {@code _throwIfExtensionCommand}（agent-session.ts:2268-2279 逐字，
     * 错文逐字）：扩展命令不可入队——名字从原文空格两刀切出（非注册件回读）。
     */
    private void throwIfExtensionCommand(String text) {
        var command = owner.services().slashCommands() == null ? null
            : owner.services().slashCommands().extensionCommand(text);
        if (command != null) {
            int spaceIndex = text.indexOf(" ");
            String commandName = spaceIndex == -1 ? text.substring(1)
                : text.substring(1, spaceIndex);
            throw new IllegalStateException(
                "Extension command \"/" + commandName + "\" cannot be queued."
                    + " Use prompt() or execute the command when not streaming.");
        }
    }

    /**
     * pi {@code _tryExecuteExtensionCommand}（agent-session.ts:2116-2141 逐字序）：
     * 名字/参数空格两刀（args 不 trim）；执行异常 ⇒ extension_error
     * （extensionPath 逐字 {@code "command:<name>"}、event {@code "command"}），
     * <b>出错也是 handled</b>。返回值（java {@code SlashCommand} 契约的结果文本）
     * 随 pi 的 void 丢弃——扩展命令经 {@code ctx.ui} 管理自己的输出（批 17 全面化
     * 已就位）。
     */
    private void executeExtensionCommand(
            com.pijava.coding.agent.core.slash.SlashCommand command, String text) {
        int spaceIndex = text.indexOf(" ");
        String commandArgs = spaceIndex == -1 ? "" : text.substring(spaceIndex + 1);
        try {
            command.execute(commandArgs, slashContextSupplier.get())
                .toCompletableFuture().join();
        } catch (Throwable e) {
            // join 的包装层（CompletionException）取因——pi err.message 口径。
            var cause = e instanceof java.util.concurrent.CompletionException
                    && e.getCause() != null ? e.getCause() : e;
            if (owner.extensionFirer() != null) {
                owner.extensionFirer().bus().emitError(
                    com.pijava.coding.agent.extension.ExtensionError.of(
                        "command:" + command.name(), "command", cause));
            }
        }
    }

    /**
     * #17（pi {@code agent-session.ts:2764-2765}）：手动压缩开头即 {@code await this.abort()}
     * —— 中止在飞 run 并等它落地，压缩读到的转录不含半截消息。{@link AgentSession#abort()}
     * 本身保持非阻塞（UI Esc 同一条路），等待只加在压缩路，净效果与 pi 的
     * {@code await abort()} 相同。pi 的 abort() 还中止在飞压缩/分支摘要 ——
     * abortCompaction 已随 #98/B36 落地（{@link AgentSession#abort()} 首步），分支摘要
     * 随 B1（docs/09 §7）。自动压缩走引擎内部直路，不经这里。
     */
    void abortAndWaitForIdle() {
        abort();
        owner.harness().waitForIdle(owner.laneName());
    }

    /**
     * Queue a steering message (injected into the current run's next round).
     *
     * <p>批 20（B237）：返回 pi {@code QueuedInputDisposition}（同
     * {@code followUp(String)} 注记）。</p>
     */
    String steer(String prompt) {
        return queueUserInput(prompt, null, StreamingBehavior.STEER, "interactive");
    }

    /** pi {@code steer(text, images, {source})}（agent-session.ts:2211-2217）全参形。 */
    String steer(String prompt,
                 List<com.pijava.agent.harness.PromptImage> images, String source) {
        return queueUserInput(prompt, images, StreamingBehavior.STEER, source);
    }

    /**
     * pi 展开序（:2008-2009 / :2191-2192）：先 {@code /skill:} 后模板，两步都是
     * 「未命中 ⇒ 原文透传」。
     */
    private String expandUserPrompt(String prompt) {
        var expanded = com.pijava.coding.agent.skill.SkillCommandExpansion.expand(
            prompt, skillsByName());
        return com.pijava.coding.agent.prompt.PromptTemplateExpansion.expand(
            expanded, owner.services().promptTemplates());
    }

    private Map<String, com.pijava.agent.skill.Skill> skillsByName() {
        var map = new LinkedHashMap<String, com.pijava.agent.skill.Skill>();
        for (var skill : owner.harness().skillManager().all()) {
            map.put(skill.name(), skill);
        }
        return map;
    }

    /**
     * 待处理消息数 = steer 队列 + followUp 队列（pi {@code pendingMessageCount}，
     * {@code :1619-1621}）。
     *
     * <p><b>不含</b> nextRun 队列 —— pi 的 {@code _steeringMessages.length +
     * _followUpMessages.length} 只数那两个数组。队列的只读视图由
     * {@link com.pijava.agent.harness.LaneSnapshot#queues()} 给出，无需新增访问器。</p>
     */
    int pendingMessageCount() {
        var queues = owner.harness().snapshot(owner.laneName()).queues();
        return queues.steer().size() + queues.followUp().size();
    }

    /**
     * 交互层状态文本广播（B175，原 docs/26 §3.4）—— 发射
     * {@link AgentSessionEvent.FlashStatus} 方言事件，RPC 侧忽略。
     */
    void flashStatus(String message) {
        owner.emitSessionEvent(new AgentSessionEvent.FlashStatus(message));
    }

    /**
     * 发射 queue_update：引擎队列文本聚合 {@code InteractiveMode} 的缓冲内容
     * （B34/B175，原 docs/26 §3.3；pi {@code _emitQueueUpdate}，
     * agent-session.ts:1017-1023）。
     *
     * @param bufferedSteering 缓冲中待 steer 的文本（压缩窗口内）；无则空列表
     * @param bufferedFollowUp 缓冲中待 followUp 的文本；无则空列表
     */
    void emitQueueUpdate(List<String> bufferedSteering,
                         List<String> bufferedFollowUp) {
        var steering = new ArrayList<String>(owner.harness().queuedSteering(owner.laneName()));
        steering.addAll(bufferedSteering);
        var followUp = new ArrayList<String>(owner.harness().queuedFollowUp(owner.laneName()));
        followUp.addAll(bufferedFollowUp);
        owner.emitSessionEvent(new AgentSessionEvent.QueueUpdate(
            List.copyOf(steering), List.copyOf(followUp)));
    }

    /** 引擎队列无缓冲聚合时的便捷口。 */
    void emitQueueUpdate() {
        emitQueueUpdate(List.of(), List.of());
    }

    /**
     * 清空两个引擎队列并返回被清掉的文本（B34，原 docs/26；pi {@code clearQueue}，
     * agent-session.ts:2355-2364），随后发射 queue_update{空}。
     */
    ClearedQueue clearQueue() {
        var steering = owner.harness().queuedSteering(owner.laneName());
        var followUp = owner.harness().queuedFollowUp(owner.laneName());
        owner.harness().cancelQueued(owner.laneName(), "steer");
        owner.harness().cancelQueued(owner.laneName(), "followUp");
        owner.emitSessionEvent(new AgentSessionEvent.QueueUpdate(List.of(), List.of()));
        return new ClearedQueue(steering, followUp);
    }
}
