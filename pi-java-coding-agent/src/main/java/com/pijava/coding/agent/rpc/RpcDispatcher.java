package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.agent.harness.QueueMode;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
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
    }

    /** 解析并分发一行；解析失败也要回 success:false 响应而非抛出。 */
    public void handleLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            handle(JSON.readValue(line, RpcCommand.class));
        } catch (Exception e) {
            String type = extractField(line, "type");
            String id = extractField(line, "id");
            try {
                out.write(RpcResponse.fail(id,
                    type == null ? "unknown" : type,
                    "Unknown command: " + (type == null ? "<unparseable>" : type)));
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
        }
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
