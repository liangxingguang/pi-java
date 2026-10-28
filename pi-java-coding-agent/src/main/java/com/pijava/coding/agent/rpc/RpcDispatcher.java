package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.mode.JsonEventMapper;

/**
 * RPC 命令 → {@link AgentSession} 分发（对齐 pi {@code rpc-mode}）。
 *
 * <p>{@code prompt} / {@code steer} / {@code follow_up} / {@code abort} 是异步
 * 命令：立即回 {@code success:true}，实际内容经订阅的事件流推送。解析失败或未知
 * 命令回 {@code success:false} 而非抛出。</p>
 *
 * <p>批 29（docs/41 结构债清空批）：状态/查询载荷族移 {@link RpcSessionPayloads}、
 * 导出族与共享 JSON mapper 移 {@link RpcExportSupport}（同包包私有，纯移动；
 * 行级注释逐字随迁）。</p>
 */
public final class RpcDispatcher {

    private final JsonlWriter out;
    private final Args args;
    private AgentSession session;
    private AutoCloseable eventSubscription;
    private volatile boolean streaming;
    /** 批 17（T3）：扩展 UI 应答通道（批 29 拆出 {@link RpcExtensionUiChannel}）。 */
    private final RpcExtensionUiChannel uiChannel;
    /** 批 17（T3/评审回补 #2）：RPC 臂 typed UI 实例（构造一次，new_session 重绑复用）。 */
    private final RpcExtensionUI extensionUI;

    /**
     * @param session 目标会话（new_session 会重建）
     * @param out     响应/事件写出
     * @param args    CLI 参数（new_session 需要）
     */
    public RpcDispatcher(AgentSession session, JsonlWriter out, Args args) {
        this.session = session;
        this.out = out;
        this.args = args;
        this.eventSubscription = session.subscribe(this::emitEvent);
        // 批 17（T3）：RPC 臂 typed 实现——九方法通道的 java 生产者自此接通
        //（晚绑定修真：context 现读会话字段，后注入不再落空，E5-32）。
        this.uiChannel = new RpcExtensionUiChannel(out);
        this.extensionUI = new RpcExtensionUI(uiChannel::request, uiChannel::write);
        session.extensionUI(extensionUI, "rpc");
    }

    /** 解析并分发一行；解析失败或处理器异常都回 success:false 响应而非抛出。 */
    public void handleLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String type = RpcExportSupport.extractField(line, "type");
        String id = RpcExportSupport.extractField(line, "id");
        if ("extension_ui_response".equals(type)) {
            uiChannel.routeResponse(line);
            return;
        }
        try {
            handle(RpcExportSupport.mapper().readValue(line, RpcCommand.class));
        } catch (Exception e) {
            String message = e.getMessage();
            if (type == null) {
                message = "Unknown command: <unparseable>";
            } else if (message == null || isTypeIdError(message)) {
                message = "Unknown command: " + type;
            }
            try {
                out.write(RpcResponse.fail(id, type == null ? "unknown" : type, message));
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
        }
    }

    /** 类型解析失败（未知 type）与处理器异常区分开。 */
    private static boolean isTypeIdError(String message) {
        return message.contains("type id") || message.contains("known type ids");
    }

    /** 分发一条已解析命令。 */
    public void handle(RpcCommand command) {
        try {
            switch (command) {
                case RpcCommand.Prompt p -> handlePrompt(p);
                case RpcCommand.Steer s -> {
                    // 批 20（B237，pi rpc-mode.ts:416-419）：disposition 透传
                    //（input 事件 handled / 正常 queued）＋images 透传；
                    // 扩展命令入队 ⇒ IllegalStateException → 通用 fail 响应。
                    var steerDisposition =
                        session.steer(s.message(), toPromptImages(s.images()), "rpc");
                    out.write(RpcResponse.ok(s.id(), "steer",
                        new RpcPayloads.DispositionData(steerDisposition)));
                }
                case RpcCommand.FollowUp f -> {
                    var followUpDisposition =
                        session.followUp(f.message(), toPromptImages(f.images()), "rpc");
                    out.write(RpcResponse.ok(f.id(), "follow_up",
                        new RpcPayloads.DispositionData(followUpDisposition)));
                }
                case RpcCommand.Abort a -> {
                    out.write(RpcResponse.ok(a.id(), "abort"));
                    session.abort();
                }
                case RpcCommand.GetState g ->
                    out.write(RpcResponse.ok(g.id(), "get_state",
                        RpcSessionPayloads.buildState(session, streaming)));
                case RpcCommand.NewSession n -> {
                    // 批 20（B257，pi rpc-mode.ts:432-441＋agent-session-runtime.ts:226-258
                    // 逐字序）：before_switch("new") 取消 ⇒ {cancelled:true} **不替换**；
                    // 响应形 {cancelled}（旧实现发全量 state——形状差修真）。
                    if (session.fireBeforeSwitch("new", null)) {
                        out.write(RpcResponse.ok(n.id(), "new_session",
                            new RpcPayloads.CancelledData(true)));
                    } else {
                        // docs/15（B250）＋docs/16（B252）：替换式新建 —— start{new,
                        // previous}＋旧会话真正关闭（closeForReplacement 内含
                        // shutdown{new, 新 id}；pi newSession 序列，序差在册 D20）。
                        var old = session;
                        var previousId = old.sessionId();
                        unsubscribe();
                        session = AgentSession.createReplacing(args, previousId, n.parentSession());
                        // 批 17 评审回补（Important #2）：新会话重绑 RPC 扩展 UI 通道
                        //（pi finishSessionReplacement→rebindSession 对位，
                        // agent-session-runtime.ts:187-191／rpc-mode.ts:313-321）。
                        session.extensionUI(extensionUI, "rpc");
                        old.closeForReplacement("new", session.sessionId());
                        eventSubscription = session.subscribe(this::emitEvent);
                        out.write(RpcResponse.ok(n.id(), "new_session",
                            new RpcPayloads.CancelledData(false)));
                    }
                }
                case RpcCommand.GetMessages m ->
                    out.write(RpcResponse.ok(m.id(), "get_messages",
                        RpcSessionPayloads.buildMessages(session)));
                case RpcCommand.GetLastAssistantText t ->
                    out.write(RpcResponse.ok(t.id(), "get_last_assistant_text",
                        session.lastAssistantText()));
                // ── 次批：模型/思考等级/压缩控制面 ──
                case RpcCommand.SetModel m -> {
                    // 批 11 T2：走会话包装（pi AgentSession.setModel）——per-model
                    // 思考档覆盖随之应用；直调 harness 会绕过该解析。
                    session.setModel(RpcSessionPayloads.resolveModel(m.model()));
                    out.write(RpcResponse.ok(m.id(), "set_model"));
                }
                case RpcCommand.CycleModel c -> {
                    // B137：scope 非空在 scope 内轮转，空 scope 全目录轮转（ScopedModelCycler）。
                    session.cycleModelForward();
                    out.write(RpcResponse.ok(c.id(), "cycle_model"));
                }
                case RpcCommand.GetAvailableModels g ->
                    out.write(RpcResponse.ok(g.id(), "get_available_models",
                        RpcSessionPayloads.availableModels()));
                case RpcCommand.SetThinkingLevel s -> {
                    session.setThinkingLevel(ThinkingLevels.parse(s.level()));
                    out.write(RpcResponse.ok(s.id(), "set_thinking_level"));
                }
                case RpcCommand.CycleThinkingLevel c -> {
                    session.setThinkingLevel(RpcSessionPayloads.cycleThinking(session));
                    out.write(RpcResponse.ok(c.id(), "cycle_thinking_level"));
                }
                case RpcCommand.GetAvailableThinkingLevels g ->
                    out.write(RpcResponse.ok(g.id(), "get_available_thinking_levels",
                        RpcSessionPayloads.availableThinkingLevels()));
                case RpcCommand.Compact c -> {
                    // B176（原 docs/27）：pi void handleInputLine(line)（rpc-mode.ts:809）
                    // —— 压缩跑 worker 虚拟线程，读线程立即处理后续命令；响应压缩
                    // 完成时写出。
                    Thread.startVirtualThread(() -> {
                        try {
                            // B223（原 docs/47）：预算按会话当前模型现解析。
                            var result = session.compact(c.customInstructions());
                            out.write(RpcResponse.ok(c.id(), "compact",
                                CompactResultWire.of(result)));
                        } catch (RuntimeException e) {
                            try {
                                // pi 外层 catch（:789-797）：fail(id,"compact",message)。
                                out.write(RpcResponse.fail(c.id(), "compact", e.getMessage()));
                            } catch (IOException io) {
                                throw new UncheckedIOException(io);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
                case RpcCommand.SetAutoCompaction a -> {
                    // B223（原 docs/47）：pi setCompactionEnabled 写全局 settings 文件
                    // 并即时 save；引擎晚读口下次决策自然生效（旧实现只改内存态）。
                    session.services().settings().compaction()
                        .setCompactionEnabled(a.enabled());
                    out.write(RpcResponse.ok(a.id(), "set_auto_compaction"));
                }
                case RpcCommand.GetSessionStats g ->
                    out.write(RpcResponse.ok(g.id(), "get_session_stats",
                        RpcSessionPayloads.buildStats(session)));
                case RpcCommand.SetSessionName s -> {
                    session.setSessionName(s.name());
                    out.write(RpcResponse.ok(s.id(), "set_session_name"));
                }
                case RpcCommand.GetCommands g ->
                    // B247（pi rpc-types.ts:241 / rpc-client.ts:459 两证）：
                    // data 包 {commands} 键（批 2 偏差②回本——彼时按裸数组钉死）。
                    out.write(RpcResponse.ok(g.id(), "get_commands",
                        java.util.Map.of("commands", RpcSessionPayloads.buildCommands(session))));
                // ── 末批（P6-5d）：队列模式 / 重试 / bash / 会话 / 导出 ──
                case RpcCommand.SetSteeringMode s -> {
                    session.harness().steeringMode(RpcSessionPayloads.fromWire(s.mode()));
                    out.write(RpcResponse.ok(s.id(), "set_steering_mode"));
                }
                case RpcCommand.SetFollowUpMode f -> {
                    session.harness().followUpMode(RpcSessionPayloads.fromWire(f.mode()));
                    out.write(RpcResponse.ok(f.id(), "set_follow_up_mode"));
                }
                case RpcCommand.SetAutoRetry r -> {
                    session.setAutoRetryEnabled(r.enabled());
                    out.write(RpcResponse.ok(r.id(), "set_auto_retry"));
                }
                case RpcCommand.AbortRetry r -> {
                    session.abortRetry();
                    out.write(RpcResponse.ok(r.id(), "abort_retry"));
                }
                case RpcCommand.AbortBash b -> {
                    session.abortBash();
                    out.write(RpcResponse.ok(b.id(), "abort_bash"));
                }
                case RpcCommand.Bash b -> {
                    var result = session.executeBash(b.id(), b.command(),
                        b.excludeFromContext() != null && b.excludeFromContext());
                    out.write(RpcResponse.ok(b.id(), "bash",
                        new RpcPayloads.RpcBashResult(
                            result.output(), result.exitCode(),
                            session.bashAborted(), result.truncated(), null)));
                }
                case RpcCommand.ExportHtml e -> {
                    var path = RpcExportSupport.exportHtml(session, e.outputPath());
                    out.write(RpcResponse.ok(e.id(), "export_html",
                        Map.of("path", path)));
                }
                case RpcCommand.SwitchSession s -> {
                    // docs/15（B250）：switchTo 集中 before_switch/shutdown/start
                    // 序列；cancel ⇒ pi 同形回 {cancelled:true}（rpc-mode.ts:591-596）。
                    var result = session.switchTo(s.sessionPath());
                    switch (result.outcome()) {
                        case NOT_FOUND -> out.write(RpcResponse.fail(s.id(), "switch_session",
                            "Session not found: " + s.sessionPath()));
                        case CANCELLED -> out.write(RpcResponse.ok(s.id(), "switch_session",
                            Map.of("cancelled", true)));
                        case SWITCHED -> out.write(RpcResponse.ok(s.id(), "switch_session",
                            Map.of("cancelled", false)));
                    }
                }
                case RpcCommand.Fork fk -> {
                    // docs/21（T1）：pi rpc-mode.ts:611-617——text 载荷＝selectedText
                    // （目标 user 消息的 text，position "before"）；fork 后旧会话已关，
                    // 必须先查表（与该表同一条目的 text 同序同源，裁决②）。
                    var selectedText = session.getUserMessagesForForking().stream()
                        .filter(m -> m.entryId().equals(fk.entryId()))
                        .map(AgentSession.ForkMessage::text).findFirst().orElse("");
                    var forked = session.forkFromEntry(fk.entryId());
                    if (forked.isEmpty()) {
                        // docs/15：session_before_fork cancel ⇒ pi 的 {cancelled:true}。
                        out.write(RpcResponse.ok(fk.id(), "fork",
                            Map.of("text", "", "cancelled", true)));
                    } else {
                        // docs/16（B252）：旧会话关闭已收进 fork 内部（closeForFork）。
                        unsubscribe();
                        session = forked.get();
                        eventSubscription = session.subscribe(this::emitEvent);
                        out.write(RpcResponse.ok(fk.id(), "fork",
                            Map.of("text", selectedText, "cancelled", false)));
                    }
                }
                case RpcCommand.Clone c -> {
                    var leaf = RpcSessionPayloads.leafId(session);
                    var forked = leaf == null
                        ? session.forkCopy(session.sessionName() + "-clone")
                        : session.forkFromEntry(leaf);
                    if (forked.isEmpty()) {
                        out.write(RpcResponse.ok(c.id(), "clone",
                            Map.of("cancelled", true)));
                    } else {
                        unsubscribe();
                        session = forked.get();
                        eventSubscription = session.subscribe(this::emitEvent);
                        out.write(RpcResponse.ok(c.id(), "clone",
                            Map.of("cancelled", false)));
                    }
                }
                case RpcCommand.GetForkMessages g -> {
                    // docs/21（T1）：pi rpc-mode.ts:631-634——数据源即
                    // session.getUserMessagesForForking()（修真后＝全分支＋空串分隔
                    // ＋空文本滤除），text 不再在 RPC 侧另提（forkMessageText 已删）。
                    var messages = session.getUserMessagesForForking().stream()
                        .map(m -> new RpcPayloads.RpcForkMessage(m.entryId(), m.text()))
                        .toList();
                    out.write(RpcResponse.ok(g.id(), "get_fork_messages",
                        new RpcPayloads.RpcForkMessagesData(messages)));
                }
                case RpcCommand.GetEntries g -> {
                    var entries = RpcSessionPayloads.harnessTranscript(session);
                    if (g.since() == null || g.since().isBlank()) {
                        out.write(RpcResponse.ok(g.id(), "get_entries",
                            new RpcPayloads.RpcEntriesData(
                                entries, RpcSessionPayloads.leafId(session))));
                    } else {
                        int idx = RpcSessionPayloads.indexOfEntry(entries, g.since());
                        if (idx < 0) {
                            out.write(RpcResponse.fail(g.id(), "get_entries",
                                "Entry not found: " + g.since()));
                        } else {
                            out.write(RpcResponse.ok(g.id(), "get_entries",
                                new RpcPayloads.RpcEntriesData(
                                    java.util.List.copyOf(
                                        entries.subList(idx + 1, entries.size())),
                                    RpcSessionPayloads.leafId(session))));
                        }
                    }
                }
                case RpcCommand.GetTree t ->
                    // docs/19 裁决③：数据源＝session.getTree()（持久会话全分支＋解析
                    // label，pi sessionManager.getTree 对位），不再是 harness 单车道转录。
                    out.write(RpcResponse.ok(t.id(), "get_tree",
                        new RpcPayloads.RpcTreeData(
                            RpcSessionPayloads.toRpcTree(session.getTree()),
                            RpcSessionPayloads.leafId(session))));
                case RpcCommand.ClearQueue c -> {
                    // B34（原 docs/26）：pi clear_queue —— data 即被清文本两数组。
                    var cleared = session.clearQueue();
                    out.write(RpcResponse.ok(c.id(), "clear_queue",
                        new RpcPayloads.ClearQueueData(
                            cleared.steering(), cleared.followUp())));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 关闭当前会话与其事件订阅（docs/16 T3：pi rpc-mode.ts:736 对位 —— EOF 时
     * dispose <b>当前</b>会话，shutdown{quit} 自此在 RPC 退出路径发射；替换过的
     * run 里关的是新会话，旧会话已在替换路径关过、幂等门下 no-op）。
     */
    public void close() {
        unsubscribe();
        try {
            session.close();
        } catch (Exception e) {
            // 退出期关闭失败忽略（pi dispose 的 try/catch 同旨）
        }
    }

    // ── 命令实现 ─────────────────────────────────────────────────────────

    private void handlePrompt(RpcCommand.Prompt prompt) throws IOException {
        String text = prompt.message() == null ? "" : prompt.message();
        // A5（原 docs/25）：pi rpc-mode.ts:398-412 —— 响应在预检之后写，
        // data.disposition = started | queued | handled（批 20 B237 三态齐）；
        // 路由门拒绝 ⇒ 恰一条失败响应。
        try {
            var result = session.processPrompt(text,
                // docs/15：input source="rpc"；批 20：images 透传（pi :399 对位）
                PromptConfig.rpc(prompt.streamingBehavior(), toPromptImages(prompt.images())));
            if ("started".equals(result.disposition())) {
                // 异步 run：事件经订阅推送；AgentSettled 复位。
                streaming = true;
            }
            if (result.commandCompletion() != null) {
                // 批 20 评审回补（I-1，pi rpc-mode.ts:398-412 时序对位）：void
                // prompt——响应在命令执行完成后才写（preflightResult 时序），
                // 读线程不阻塞（后续命令照读照护）。
                result.commandCompletion().thenRun(() -> writeQuietly(
                    RpcResponse.ok(prompt.id(), "prompt",
                        new RpcPayloads.DispositionData("handled"))));
            } else {
                out.write(RpcResponse.ok(prompt.id(), "prompt",
                    new RpcPayloads.DispositionData(result.disposition())));
            }
        } catch (IllegalStateException e) {
            // 压缩中 / 运行中未指定 streamingBehavior（pi 在调用线程同步抛）。
            out.write(RpcResponse.fail(prompt.id(), "prompt", e.getMessage()));
        }
    }

    /**
     * 批 20（B237）：线格式图片 → {@link com.pijava.agent.harness.PromptImage}
     * （null/空 ⇒ {@code null} ≙ pi {@code images?: ImageContent[]} 缺席）。
     */
    private static java.util.List<com.pijava.agent.harness.PromptImage> toPromptImages(
            java.util.List<com.pijava.ai.message.ContentBlock.ImageContent> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream()
            .map(i -> new com.pijava.agent.harness.PromptImage(i.mediaType(), i.data()))
            .toList();
    }

    /** 批 20 评审回补（I-1）：后台线程写出（与 emitEvent 同一容错形状）。 */
    private void writeQuietly(RpcResponse response) {
        try {
            out.write(response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 当前会话（批 17 评审回补 #2 的测试探针；new_session 后是新对象）。 */
    AgentSession currentSession() {
        return session;
    }

    /** 事件 → 线格式写 stdout。 */
    private void emitEvent(AgentSessionEvent event) {
        try {
            // B175（原 docs/26）：FlashStatus 是 TUI 方言事件，不上 RPC 线。
            if (event instanceof AgentSessionEvent.FlashStatus) {
                return;
            }
            if (event instanceof AgentSessionEvent.AgentEnd
                    || event instanceof AgentSessionEvent.AgentSettled) {
                streaming = false;
            }
            out.write(JsonEventMapper.toWire(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 仅退订事件通道（docs/16 改名：会话关闭归 closeForReplacement/closeForFork/close）。 */
    private void unsubscribe() {
        try {
            if (eventSubscription != null) {
                eventSubscription.close();
                eventSubscription = null;
            }
        } catch (Exception e) {
            // 忽略退订失败
        }
    }
}
