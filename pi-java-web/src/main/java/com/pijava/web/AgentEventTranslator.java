package com.pijava.web;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

/**
 * pi-java {@link AgentSessionEvent} → pi-webui 前端 {@code agentEvent} 词汇翻译层
 * （Phase 7 设计 §4）。前端不改：{@code agent_start} / {@code message_update} /
 * {@code tool_execution_*} / {@code error} / {@code agent_end} / {@code turn_end}。
 *
 * <p>策略：流式阶段仅推 {@code message_update}（累积 {@code partial}）与工具事件；
 * 回合结束由 {@code AgentEnd} 推带完整 messages 的 {@code agent_end}（前端整表替换），
 * 避免逐条 {@code message_end} 的去重竞态。</p>
 */
final class AgentEventTranslator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private volatile boolean streaming;

    /** 当前是否在流式运行中（getState 用）。 */
    boolean isStreaming() {
        return streaming;
    }

    /** 把会话事件翻译为前端消息列表（多事件一次返回，保持顺序）。 */
    List<WebServerMessage> translate(AgentSessionEvent event) {
        var out = new ArrayList<WebServerMessage>();
        switch (event) {
            case AgentSessionEvent.MessageUpdate u -> translateStream(u.streamEvent(), out);
            case AgentSessionEvent.AgentEnd e -> {
                streaming = false;
                out.add(agentEnd(e));
            }
            case AgentSessionEvent.AgentSettled ignored -> {
                streaming = false;
                out.add(new WebServerMessage.AgentEvent(typeNode("turn_end")));
            }
            case AgentSessionEvent.BashExecutionUpdate b -> out.add(bashOutput(b));
            default -> {
                // queue_update / compaction_* / auto_retry_* 等暂不推前端
            }
        }
        return out;
    }

    // ── StreamEvent 增量 ────────────────────────────────────────────────

    private void translateStream(StreamEvent ev, List<WebServerMessage> out) {
        switch (ev) {
            case StreamEvent.Start ignored -> {
                streaming = true;
                out.add(new WebServerMessage.AgentEvent(typeNode("agent_start")));
            }
            case StreamEvent.TextDelta delta -> out.add(messageUpdate(delta.partial()));
            case StreamEvent.ThinkingDelta delta -> out.add(messageUpdate(delta.partial()));
            case StreamEvent.ToolCallStart ignored ->
                out.add(new WebServerMessage.AgentEvent(typeNode("tool_execution_start")));
            case StreamEvent.ToolCallDelta ignored ->
                out.add(new WebServerMessage.AgentEvent(typeNode("tool_execution_update")));
            case StreamEvent.ToolCallEnd ignored ->
                out.add(new WebServerMessage.AgentEvent(typeNode("tool_execution_end")));
            case StreamEvent.StreamError err -> {
                streaming = false;
                out.add(new WebServerMessage.Error(errorText(err)));
            }
            default -> {
                // Start/TextStart/TextEnd/ThinkingStart/End/UsageInfo/StreamDone
                // 由 agent_end 权威收口，不逐条推
            }
        }
    }

    private WebServerMessage messageUpdate(com.pijava.ai.message.AssistantMessage partial) {
        var node = typeNode("message_update");
        node.set("message", assistantNode(partial));
        return new WebServerMessage.AgentEvent(node);
    }

    /** 流式快照 {@code AssistantMessage} → pi 形状 {@code {role:"assistant", content:[...]}}。 */
    private static com.fasterxml.jackson.databind.node.ObjectNode assistantNode(
            com.pijava.ai.message.AssistantMessage partial) {
        var node = JSON.createObjectNode();
        node.put("role", "assistant");
        var content = node.putArray("content");
        for (var block : partial.content()) {
            content.add(SessionJson.blockNode(block));
        }
        return node;
    }

    private WebServerMessage agentEnd(AgentSessionEvent.AgentEnd e) {
        var node = typeNode("agent_end");
        node.put("willRetry", e.willRetry());
        // 空消息（错误/中止路径）不带 messages，避免前端整表清空
        if (!e.messages().isEmpty()) {
            var arr = node.putArray("messages");
            for (var m : e.messages()) {
                arr.add(SessionJson.messageNode(m));
            }
        }
        return new WebServerMessage.AgentEvent(node);
    }

    private WebServerMessage bashOutput(AgentSessionEvent.BashExecutionUpdate b) {
        var node = typeNode("bashOutput");
        node.put("id", b.id());
        node.put("delta", b.delta());
        return new WebServerMessage.AgentEvent(node);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static ObjectNode typeNode(String type) {
        var node = JSON.createObjectNode();
        node.put("type", type);
        return node;
    }

    private static String errorText(StreamEvent.StreamError err) {
        var t = err.error();
        if (t != null) {
            return t.getMessage() == null ? String.valueOf(t) : t.getMessage();
        }
        return "Stream error: " + err.reason();
    }
}
