package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.QueueMode;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.SessionTreeNode;
import com.pijava.coding.agent.core.WireJson;
import com.pijava.coding.agent.export.HtmlExporter;
import com.pijava.coding.agent.mode.JsonEventMapper;
import com.pijava.coding.agent.cli.Args;

/**
 * RPC 命令 → {@link AgentSession} 分发（对齐 pi {@code rpc-mode}）。
 *
 * <p>{@code prompt} / {@code steer} / {@code follow_up} / {@code abort} 是异步
 * 命令：立即回 {@code success:true}，实际内容经订阅的事件流推送。解析失败或未知
 * 命令回 {@code success:false} 而非抛出。</p>
 */
public final class RpcDispatcher {

    /**
     * in-memory 会话（{@code --no-session}）的 {@code export_html}/{@code export_jsonl}
     * 会**内联**序列化条目（{@link #entryWithRole}）⇒ 带上身份缝挂的 {@code Instant}。
     * 此前这里是**裸** ObjectMapper ⇒ {@code export_html} 抛（台账 B52）。同形止血见
     * {@link WireJson}。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(WireJson.instantAsEpochMillis());

    private final JsonlWriter out;
    private final Args args;
    private AgentSession session;
    private AutoCloseable eventSubscription;
    private volatile boolean streaming;
    /**
     * 批 17 评审回补（Important #1）：扩展 UI 应答<b>按 id 路由</b>——pi
     * {@code pendingExtensionRequests} map（rpc-mode.ts:80-84）对位。旧广播队列
     * 失配即弃 ⇒ 超时/abort 的孤儿通道线程会偷走后发对话的应答。
     */
    private final java.util.concurrent.ConcurrentMap<String,
        java.util.concurrent.CompletableFuture<RpcExtensionUIResponse>> pendingUiRequests =
        new java.util.concurrent.ConcurrentHashMap<>();
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
        // （晚绑定修真：context 现读会话字段，后注入不再落空，E5-32）。
        this.extensionUI =
            new RpcExtensionUI(this::extensionUiRequest, this::writeExtensionUiRequest);
        session.extensionUI(extensionUI, "rpc");
    }

    /** 解析并分发一行；解析失败或处理器异常都回 success:false 响应而非抛出。 */
    public void handleLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String type = extractField(line, "type");
        String id = extractField(line, "id");
        if ("extension_ui_response".equals(type)) {
            // UI 响应 → 按 id 路由到挂起对话（pi pendingExtensionRequests 对位；
            // 无 waiter 的迟到应答丢弃——裁决⑦）。
            try {
                var response = JSON.readValue(line, RpcExtensionUIResponse.class);
                var pending = pendingUiRequests.remove(response.id());
                if (pending != null) {
                    pending.complete(response);
                }
            } catch (Exception ignored) {
                // 畸形响应忽略
            }
            return;
        }
        try {
            handle(JSON.readValue(line, RpcCommand.class));
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
                    out.write(RpcResponse.ok(g.id(), "get_state", buildState()));
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
                    out.write(RpcResponse.ok(m.id(), "get_messages", buildMessages()));
                case RpcCommand.GetLastAssistantText t ->
                    out.write(RpcResponse.ok(t.id(), "get_last_assistant_text",
                        session.lastAssistantText()));
                // ── 次批：模型/思考等级/压缩控制面 ──
                case RpcCommand.SetModel m -> {
                    // 批 11 T2：走会话包装（pi AgentSession.setModel）——per-model
                    // 思考档覆盖随之应用；直调 harness 会绕过该解析。
                    session.setModel(resolveModel(m.model()));
                    out.write(RpcResponse.ok(m.id(), "set_model"));
                }
                case RpcCommand.CycleModel c -> {
                    // B137：scope 非空在 scope 内轮转，空 scope 全目录轮转（ScopedModelCycler）。
                    session.cycleModelForward();
                    out.write(RpcResponse.ok(c.id(), "cycle_model"));
                }
                case RpcCommand.GetAvailableModels g ->
                    out.write(RpcResponse.ok(g.id(), "get_available_models",
                        availableModels()));
                case RpcCommand.SetThinkingLevel s -> {
                    session.setThinkingLevel(ThinkingLevels.parse(s.level()));
                    out.write(RpcResponse.ok(s.id(), "set_thinking_level"));
                }
                case RpcCommand.CycleThinkingLevel c -> {
                    session.setThinkingLevel(cycleThinking(session));
                    out.write(RpcResponse.ok(c.id(), "cycle_thinking_level"));
                }
                case RpcCommand.GetAvailableThinkingLevels g ->
                    out.write(RpcResponse.ok(g.id(), "get_available_thinking_levels",
                        availableThinkingLevels()));
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
                    session.services().settings().accessors()
                        .setCompactionEnabled(a.enabled());
                    out.write(RpcResponse.ok(a.id(), "set_auto_compaction"));
                }
                case RpcCommand.GetSessionStats g ->
                    out.write(RpcResponse.ok(g.id(), "get_session_stats",
                        buildStats()));
                case RpcCommand.SetSessionName s -> {
                    session.setSessionName(s.name());
                    out.write(RpcResponse.ok(s.id(), "set_session_name"));
                }
                case RpcCommand.GetCommands g ->
                    // B247（pi rpc-types.ts:241 / rpc-client.ts:459 两证）：
                    // data 包 {commands} 键（批 2 偏差②回本——彼时按裸数组钉死）。
                    out.write(RpcResponse.ok(g.id(), "get_commands",
                        java.util.Map.of("commands", buildCommands())));
                // ── 末批（P6-5d）：队列模式 / 重试 / bash / 会话 / 导出 ──
                case RpcCommand.SetSteeringMode s -> {
                    session.harness().steeringMode(fromWire(s.mode()));
                    out.write(RpcResponse.ok(s.id(), "set_steering_mode"));
                }
                case RpcCommand.SetFollowUpMode f -> {
                    session.harness().followUpMode(fromWire(f.mode()));
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
                    var path = exportHtml(e.outputPath());
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
                    var leaf = leafId();
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
                    var entries = harnessTranscript();
                    if (g.since() == null || g.since().isBlank()) {
                        out.write(RpcResponse.ok(g.id(), "get_entries",
                            new RpcPayloads.RpcEntriesData(entries, leafId())));
                    } else {
                        int idx = indexOfEntry(entries, g.since());
                        if (idx < 0) {
                            out.write(RpcResponse.fail(g.id(), "get_entries",
                                "Entry not found: " + g.since()));
                        } else {
                            out.write(RpcResponse.ok(g.id(), "get_entries",
                                new RpcPayloads.RpcEntriesData(
                                    List.copyOf(entries.subList(idx + 1, entries.size())),
                                    leafId())));
                        }
                    }
                }
                case RpcCommand.GetTree t ->
                    // docs/19 裁决③：数据源＝session.getTree()（持久会话全分支＋解析
                    // label，pi sessionManager.getTree 对位），不再是 harness 单车道转录。
                    out.write(RpcResponse.ok(t.id(), "get_tree",
                        new RpcPayloads.RpcTreeData(toRpcTree(session.getTree()), leafId())));
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
            out.write(RpcResponse.ok(prompt.id(), "prompt",
                new RpcPayloads.DispositionData(result.disposition())));
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

    /** 批 17（T3）：fire-and-forget 写出（pi notify/setStatus/setTitle/set_editor_text/setWidget 臂——不等应答）。 */
    private void writeExtensionUiRequest(RpcExtensionUIRequest request) {
        try {
            out.write(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 扩展 UI 请求：写 extension_ui_request 到 stdout，阻塞等 extension_ui_response。 */
    private RpcExtensionUIResponse extensionUiRequest(RpcExtensionUIRequest request) {
        var pending = new java.util.concurrent.CompletableFuture<RpcExtensionUIResponse>();
        pendingUiRequests.put(request.id(), pending);
        try {
            out.write(request);
        } catch (IOException e) {
            pendingUiRequests.remove(request.id());
            throw new UncheckedIOException(e);
        }
        try {
            return pending.get(5, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Extension UI request timed out: " + request.method());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Extension UI interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            // 孤儿防护：超时/中断后摘除挂起项——迟到的应答自此无 waiter 可偷
            // （pi cleanup() 的 pendingExtensionRequests.delete(id) 对位）。
            pendingUiRequests.remove(request.id());
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

    // ── get_state / get_messages 载荷 ────────────────────────────────────

    private RpcSessionState buildState() {
        var harness = session.harness();
        var model = harness.getModel();
        // B223（原 docs/47，pi rpc-mode.ts:459）：读 settings getter（?? true 默认开），
        // 不再读引擎内存态（旧实现全新会话恒报 false）。
        boolean autoCompactionEnabled =
            session.services().settings().accessors().getCompactionEnabled();
        // 批 20（B31，pi rpc-mode.ts:450）：model 发整个 Model 对象（目录解析；
        // 未命中 ⇒ ModelInfo.minimal 容忍形状，窄差 D121），无模型 ⇒ 键省略
        //（pi undefined 同形；旧 "" 修真）。
        RpcModel wireModel = null;
        if (model != null) {
            var info = com.pijava.ai.provider.ModelsJsonConfig.allModels().find(model)
                .orElseGet(() -> com.pijava.ai.catalog.ModelInfo.minimal(model));
            wireModel = RpcModel.of(info, providerConfigOf(model.provider()), JSON);
        }
        // 逐字段取自会话，与 pi rpc-mode.ts:450-465 的取值表一一对应（包④，原 docs/31 §8.37）。
        // 此前 isCompacting / sessionFile / sessionId / pendingMessageCount 是写死的
        // false / null / null / 0。
        // 批 20：sessionName 读 stored（B32——无 session_info ⇒ 键省略，pi
        // getSessionName 同形）；messageCount 改工作副本口径（B33——pi
        // agent.state.messages.length，压缩后与转录分叉正是要义）。
        return new RpcSessionState(
            wireModel,
            thinkingWire(harness.getThinkingLevel()),
            streaming,
            session.isCompacting(),
            queueWire(harness.steeringMode()),
            queueWire(harness.followUpMode()),
            session.sessionFile(),
            session.sessionId(),
            session.storedSessionName(),
            autoCompactionEnabled,
            harness.getMessages().size(),
            session.pendingMessageCount());
    }

    /**
     * 批 20（B31/T6 共用）：provider 的静态配置（api/baseUrl 缺省源＝pi
     * {@code model.baseUrl} 的目录值语义——<b>不取</b> CLI/settings 覆盖，裁决⑥）。
     * 非 {@link com.pijava.ai.provider.ConfigurableProvider}（测试 FauxProvider 等）
     * ⇒ {@code null}（两键省略）。
     */
    private com.pijava.ai.provider.ProviderConfig providerConfigOf(String providerName) {
        return session.services().providers().get(providerName)
            .filter(com.pijava.ai.provider.ConfigurableProvider.class::isInstance)
            .map(p -> ((com.pijava.ai.provider.ConfigurableProvider) p).providerConfig())
            .orElse(null);
    }

    private List<Message> buildMessages() {
        var transcript = harnessTranscript();
        return transcript.stream()
            .filter(e -> e instanceof com.pijava.agent.entry.Entry.Message m)
            .map(e -> ((com.pijava.agent.entry.Entry.Message) e).message())
            .toList();
    }

    private List<com.pijava.agent.entry.Entry> harnessTranscript() {
        return session.harness().snapshot(session.laneName()).transcript();
    }

    // ── 次批辅助 ─────────────────────────────────────────────────────────

    /** 解析模型模式（"provider/model" 或纯 modelName）→ ModelId。 */
    private ModelId<?> resolveModel(String pattern) {
        var models = com.pijava.ai.provider.ModelsJsonConfig.allModels().listModels();
        String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("model pattern required");
        }
        if (p.contains("/")) {
            var parts = p.split("/", 2);
            return models.stream().map(com.pijava.ai.catalog.ModelInfo::id)
                .filter(id -> id.provider().equals(parts[0])
                    && id.modelName().equals(parts[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + pattern));
        }
        return models.stream().map(com.pijava.ai.catalog.ModelInfo::id)
            .filter(id -> id.modelName().equals(p))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + pattern));
    }

    private ModelThinkingLevel cycleThinking(AgentSession s) {
        var levels = ThinkingLevel.ordered();
        ModelThinkingLevel current = s.harness().getThinkingLevel();
        int idx = current instanceof ModelThinkingLevel.Enabled e
            ? levels.indexOf(e.level()) : -1;
        return ModelThinkingLevel.of(levels.get((idx + 1) % levels.size()));
    }

    private List<String> availableModels() {
        return com.pijava.ai.provider.ModelsJsonConfig.allModels()
            .listModels().stream()
            .map(m -> m.id().provider() + "/" + m.id().modelName())
            .sorted().toList();
    }

    private List<String> availableThinkingLevels() {
        return ThinkingLevel.ordered().stream()
            .map(ThinkingLevel::label).toList();
    }

    private RpcSessionStats buildStats() {
        var snapshot = session.watchSession().current();
        ModelId<?> model = session.harness().getModel();
        return new RpcSessionStats(
            model == null ? "" : model.provider() + "/" + model.modelName(),
            snapshot == null ? 0 : snapshot.totalTokens(),
            snapshot == null ? 0 : snapshot.turnCount(),
            (int) session.entryCount(),
            snapshot == null ? "idle" : snapshot.phase());
    }

    private List<RpcSlashCommand> buildCommands() {
        var commands = new ArrayList<RpcSlashCommand>();
        // B247（pi rpc-mode.ts:691-697）：模板臂。sourceInfo 由加载期打标
        //（B247 §3.2）；手造实例无 sourceInfo ⇒ 组件 null、线格式省略
        //（pi 恒有，此臂仅测试/手工注册路）。pi 第一臂是扩展注册命令——
        // java 扩展命令与内置同池、无 sourceInfo 溯源，随 B221（窄差 D5）；
        // 内置不收（pi 语义＝可经 prompt 调用，rpc-types.ts:73-74）。
        for (var template : session.services().promptTemplates().all()) {
            commands.add(new RpcSlashCommand(
                template.name(), template.description(),
                RpcSlashCommand.Source.PROMPT, template.sourceInfo()));
        }
        // B244/B247（pi rpc-mode.ts:699-707）：skill 臂，不受
        // enableSkillCommands 门（pi get_commands 无门——开关只管交互补全）。
        for (var skill : session.harness().skillManager().all()) {
            commands.add(new RpcSlashCommand(
                "skill:" + skill.name(), skill.description(),
                RpcSlashCommand.Source.SKILL, skillSourceInfo(skill)));
        }
        return commands;
    }

    /**
     * pi {@code createSkillSourceInfo}（skills.ts:136-157）的 java 三面映射：
     * USER/PROJECT→同值 scope，EXPLICIT→temporary（pi {@code "path"} 源 scope
     * 缺省落 temporary）；{@code source} 恒 {@code "local"}、{@code origin}
     * 恒 {@code "top-level"}。
     */
    private static com.pijava.coding.agent.core.source.SourceInfo skillSourceInfo(
            com.pijava.agent.skill.Skill skill) {
        var scope = switch (skill.sourceInfo()) {
            case USER -> com.pijava.coding.agent.core.source.SourceInfo.Scope.USER;
            case PROJECT -> com.pijava.coding.agent.core.source.SourceInfo.Scope.PROJECT;
            case EXPLICIT -> com.pijava.coding.agent.core.source.SourceInfo.Scope.TEMPORARY;
        };
        var filePath = skill.filePath();
        var baseDir = skill.baseDir();
        return new com.pijava.coding.agent.core.source.SourceInfo(
            // pi path 必填；非文件技能（java 独有边）给空串。
            filePath == null ? "" : filePath.toString(),
            "local", scope,
            com.pijava.coding.agent.core.source.SourceInfo.Origin.TOP_LEVEL,
            baseDir == null ? null : baseDir.toString());
    }

    /**
     * 线格式的思考级别（{@code "off"|"minimal"|…|"max"}）。
     *
     * <p>包H5：改用 {@link ModelThinkingLevel#label()} —— 它逐字对齐 pi 的字面量
     * （{@code types.ts:84-85}）。此前是手写 switch，且 {@code "max"} 缺分支
     * （java 那时只有 5 级、把 max 并进 xhigh）。</p>
     */
    private static String thinkingWire(ModelThinkingLevel level) {
        return level == null ? "off" : level.label();
    }

    private static String queueWire(QueueMode mode) {
        return mode instanceof QueueMode.All ? "all" : "one-at-a-time";
    }

    // ── 末批（P6-5d）辅助 ──────────────────────────────────────────────

    /** 线格式 "all" | "one-at-a-time" → QueueMode（对齐 pi QueueMode 判别）。 */
    private static QueueMode fromWire(String mode) {
        return "all".equals(mode) ? new QueueMode.All() : new QueueMode.OneAtATime();
    }

    /** 当前主 lane 的 leaf entry id（末条 transcript entry），空会话为 null。 */
    private String leafId() {
        var entries = harnessTranscript();
        return entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
    }

    /** 导出当前会话为 HTML（P6-12），返回生成的文件路径。 */
    private String exportHtml(String outputPath) {
        try {
            var tmp = Files.createTempFile("pi-java-export", ".jsonl");
            writeSessionJsonl(tmp);
            var html = outputPath == null || outputPath.isBlank()
                ? new HtmlExporter().export(tmp)
                : new HtmlExporter().export(tmp, java.nio.file.Path.of(outputPath));
            return html.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 写会话 JSONL：持久化会话走 {@code exportJsonl}；in-memory 会话内联写
     *  header + transcript（HtmlExporter 兼容格式）。 */
    private void writeSessionJsonl(java.nio.file.Path target) throws IOException {
        try {
            session.exportJsonl(target);
            return;
        } catch (IllegalStateException e) {
            // in-memory 会话（--no-session）：无持久化会话，内联序列化
        }
        var header = JSON.createObjectNode();
        header.put("id", session.sessionName());
        header.put("name", session.sessionName());
        var lines = new ArrayList<String>();
        lines.add(JSON.writeValueAsString(header));
        for (var entry : harnessTranscript()) {
            lines.add(JSON.writeValueAsString(entryWithRole(entry)));
        }
        Files.write(target, lines);
    }

    /** Entry JSON 补 message.role（HtmlExporter 按 role 渲染；Message.role() 派生不序列化）。 */
    private com.fasterxml.jackson.databind.node.ObjectNode entryWithRole(Entry entry) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(entry);
        if (entry instanceof Entry.Message m && node.get("message") instanceof ObjectNode msg) {
            msg.put("role", m.message().role());
        }
        return node;
    }

    /** 按 entry id 找索引（get_entries since 过滤）；找不到回 -1。 */
    private static int indexOfEntry(List<Entry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** {@link SessionTreeNode} → RPC 线形浅映射（label 从此有值）。 */
    private static List<RpcPayloads.RpcSessionTreeNode> toRpcTree(List<SessionTreeNode> nodes) {
        return nodes.stream()
            .map(n -> new RpcPayloads.RpcSessionTreeNode(n.entry(), toRpcTree(n.children()), n.label()))
            .toList();
    }

    private static String extractField(String line, String field) {
        try {
            JsonNode node = JSON.readTree(line);
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
