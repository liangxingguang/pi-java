package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.harness.QueueMode;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.PromptConfig;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonlWriter out;
    private final Args args;
    private AgentSession session;
    private AutoCloseable eventSubscription;
    private volatile boolean streaming;
    private final LinkedBlockingQueue<RpcExtensionUIResponse> uiQueue =
        new LinkedBlockingQueue<>();

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
        session.extensionUI(this::extensionUiRequest);
    }

    /** 解析并分发一行；解析失败或处理器异常都回 success:false 响应而非抛出。 */
    public void handleLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String type = extractField(line, "type");
        String id = extractField(line, "id");
        if ("extension_ui_response".equals(type)) {
            // UI 响应 → 路由到阻塞队列，非命令
            try {
                uiQueue.offer(JSON.readValue(line, RpcExtensionUIResponse.class));
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
                    out.write(RpcResponse.ok(s.id(), "steer"));
                    session.steer(s.message());
                }
                case RpcCommand.FollowUp f -> {
                    out.write(RpcResponse.ok(f.id(), "follow_up"));
                    session.followUp(f.message());
                }
                case RpcCommand.Abort a -> {
                    out.write(RpcResponse.ok(a.id(), "abort"));
                    session.abort();
                }
                case RpcCommand.GetState g ->
                    out.write(RpcResponse.ok(g.id(), "get_state", buildState()));
                case RpcCommand.NewSession n -> {
                    closeSession();
                    session = AgentSession.create(args);
                    eventSubscription = session.subscribe(this::emitEvent);
                    out.write(RpcResponse.ok(n.id(), "new_session", buildState()));
                }
                case RpcCommand.GetMessages m ->
                    out.write(RpcResponse.ok(m.id(), "get_messages", buildMessages()));
                case RpcCommand.GetLastAssistantText t ->
                    out.write(RpcResponse.ok(t.id(), "get_last_assistant_text",
                        session.lastAssistantText()));
                // ── 次批：模型/思考等级/压缩控制面 ──
                case RpcCommand.SetModel m -> {
                    session.harness().setModel(resolveModel(m.model()));
                    out.write(RpcResponse.ok(m.id(), "set_model"));
                }
                case RpcCommand.CycleModel c -> {
                    session.harness().setModel(cycleModel(session));
                    out.write(RpcResponse.ok(c.id(), "cycle_model"));
                }
                case RpcCommand.GetAvailableModels g ->
                    out.write(RpcResponse.ok(g.id(), "get_available_models",
                        availableModels()));
                case RpcCommand.SetThinkingLevel s -> {
                    session.harness().setThinkingLevel(ThinkingLevels.parse(s.level()));
                    out.write(RpcResponse.ok(s.id(), "set_thinking_level"));
                }
                case RpcCommand.CycleThinkingLevel c -> {
                    session.harness().setThinkingLevel(cycleThinking(session));
                    out.write(RpcResponse.ok(c.id(), "cycle_thinking_level"));
                }
                case RpcCommand.GetAvailableThinkingLevels g ->
                    out.write(RpcResponse.ok(g.id(), "get_available_thinking_levels",
                        availableThinkingLevels()));
                case RpcCommand.Compact c -> {
                    session.compact(CompactionSettings.defaults());
                    out.write(RpcResponse.ok(c.id(), "compact"));
                }
                case RpcCommand.SetAutoCompaction a ->
                    out.write(RpcResponse.fail(a.id(), "set_auto_compaction",
                        "Not implemented"));
                case RpcCommand.GetSessionStats g ->
                    out.write(RpcResponse.ok(g.id(), "get_session_stats",
                        buildStats()));
                case RpcCommand.SetSessionName s -> {
                    session.setSessionName(s.name());
                    out.write(RpcResponse.ok(s.id(), "set_session_name"));
                }
                case RpcCommand.GetCommands g ->
                    out.write(RpcResponse.ok(g.id(), "get_commands",
                        buildCommands()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 关闭当前会话与其事件订阅。 */
    public void close() {
        closeSession();
    }

    // ── 命令实现 ─────────────────────────────────────────────────────────

    private void handlePrompt(RpcCommand.Prompt prompt) throws IOException {
        out.write(RpcResponse.ok(prompt.id(), "prompt"));
        String text = prompt.message() == null ? "" : prompt.message();
        // 异步命令：不阻塞等待结果，事件经订阅推送。
        streaming = true;
        session.processPrompt(text, PromptConfig.defaults());
    }

    /** 扩展 UI 请求：写 extension_ui_request 到 stdout，阻塞等 extension_ui_response。 */
    private RpcExtensionUIResponse extensionUiRequest(RpcExtensionUIRequest request) {
        try {
            out.write(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            RpcExtensionUIResponse response;
            try {
                response = uiQueue.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Extension UI interrupted", e);
            }
            if (response != null && request.id().equals(response.id())) {
                return response;
            }
        }
        throw new RuntimeException("Extension UI request timed out: " + request.method());
    }

    /** 事件 → 线格式写 stdout。 */
    private void emitEvent(AgentSessionEvent event) {
        try {
            if (event instanceof AgentSessionEvent.AgentEnd
                    || event instanceof AgentSessionEvent.AgentSettled) {
                streaming = false;
            }
            out.write(JsonEventMapper.toWire(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void closeSession() {
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
        String modelId = model == null ? "" : model.provider() + "/" + model.modelName();
        return new RpcSessionState(
            modelId,
            thinkingWire(harness.getThinkingLevel()),
            streaming,
            false,
            queueWire(harness.steeringMode()),
            queueWire(harness.followUpMode()),
            null,
            null,
            session.sessionName(),
            false,
            (int) session.entryCount(),
            0);
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
        var models = com.pijava.ai.provider.builtin.ProviderCatalog.allModels().listModels();
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

    private ModelId<?> cycleModel(AgentSession s) {
        var models = com.pijava.ai.provider.builtin.ProviderCatalog.allModels().listModels();
        if (models.isEmpty()) {
            return s.harness().getModel();
        }
        ModelId<?> current = s.harness().getModel();
        int idx = -1;
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().equals(current)) {
                idx = i;
                break;
            }
        }
        return models.get((idx + 1) % models.size()).id();
    }

    private ModelThinkingLevel cycleThinking(AgentSession s) {
        var levels = ThinkingLevel.ordered();
        ModelThinkingLevel current = s.harness().getThinkingLevel();
        int idx = current instanceof ModelThinkingLevel.Enabled e
            ? levels.indexOf(e.level()) : -1;
        return ModelThinkingLevel.of(levels.get((idx + 1) % levels.size()));
    }

    private List<String> availableModels() {
        return com.pijava.ai.provider.builtin.ProviderCatalog.allModels()
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
        var registry = session.services().slashCommands();
        var commands = new ArrayList<RpcSlashCommand>();
        for (var name : registry.names()) {
            var cmd = registry.get(name);
            if (cmd != null) {
                commands.add(new RpcSlashCommand(
                    name, cmd.description(), cmd.argumentHint()));
            }
        }
        return commands;
    }

    private static String thinkingWire(ModelThinkingLevel level) {
        if (level instanceof ModelThinkingLevel.Off) {
            return "off";
        }
        if (level instanceof ModelThinkingLevel.Enabled e) {
            return thinkingWire(e.level());
        }
        return "off";
    }

    private static String thinkingWire(ThinkingLevel level) {
        return switch (level) {
            case ThinkingLevel.Minimal() -> "minimal";
            case ThinkingLevel.Low() -> "low";
            case ThinkingLevel.Medium() -> "medium";
            case ThinkingLevel.High() -> "high";
            case ThinkingLevel.XHigh() -> "xhigh";
        };
    }

    private static String queueWire(QueueMode mode) {
        return mode instanceof QueueMode.All ? "all" : "one-at-a-time";
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
