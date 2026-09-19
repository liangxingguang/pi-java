package com.pijava.web;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(AgentEventTranslator.class);

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
            // 即时回显：run 启动即推 message_end(user)，前端收到即上列表，
            // 不等 agent_end 整表替换（对齐 pi agent-loop 的 message_end）。
            case AgentSessionEvent.UserMessageReceived u ->
                out.add(userMessageEnd(u.message()));
            case AgentSessionEvent.AgentEnd e -> {
                streaming = false;
                out.add(agentEnd(e));
            }
            case AgentSessionEvent.AgentSettled ignored -> {
                streaming = false;
                out.add(new WebServerMessage.AgentEvent(typeNode("turn_end")));
            }
            case AgentSessionEvent.BashExecutionUpdate b -> out.add(bashOutput(b));
            // 包⑦（docs/34）：工具执行生命周期事件的**真源**。此前 web 的
            // tool_execution_* 由 StreamEvent.ToolCall* 伪造 —— 那是「模型把调用
            // 吐完」的时刻，不是工具执行的生命周期（工具跑 30 秒，前端此前在这
            // 30 秒里收不到任何东西）。载荷按裁决 A 照 pi 的透传带上。
            case AgentSessionEvent.ToolExecutionStart s -> out.add(toolExecutionStart(s));
            case AgentSessionEvent.ToolExecutionUpdate u -> out.add(toolExecutionUpdate(u));
            case AgentSessionEvent.ToolExecutionEnd e -> out.add(toolExecutionEnd(e));
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
            // 包⑥（B40）：工具增量上推 message_update —— 前端在 tool_execution_*
            // 上只 renderApp()、不读载荷，它手上最后一条 message_update 此前是工具
            // 调用**之前**那条 ⇒ 工具卡要等 agent_end 整表替换才出现。
            //
            // 包⑦（docs/34）**换源**：这里**不再**伪造 tool_execution_*（那三条改由
            // 真正的工具执行事件驱动，见下面 AgentSessionEvent.ToolExecution* 的三支）。
            case StreamEvent.ToolCallStart s -> out.add(messageUpdate(s.partial()));
            case StreamEvent.ToolCallDelta s -> out.add(messageUpdate(s.partial()));
            case StreamEvent.ToolCallEnd s -> out.add(messageUpdate(s.partial()));
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
        node.set("message", WebWireJson.assistantNode(partial));
        return new WebServerMessage.AgentEvent(node);
    }

    /** user 消息 wire 帧：{type:"message_end", message:{role:"user",content:[...]}}。 */
    private WebServerMessage userMessageEnd(com.pijava.ai.message.Message userMsg) {
        var node = typeNode("message_end");
        node.set("message", WebWireJson.messageNode(userMsg));
        return new WebServerMessage.AgentEvent(node);
    }

    private WebServerMessage agentEnd(AgentSessionEvent.AgentEnd e) {
        var node = typeNode("agent_end");
        node.put("willRetry", e.willRetry());
        // 空消息（错误/中止路径）不带 messages，避免前端整表清空
        if (!e.messages().isEmpty()) {
            var arr = node.putArray("messages");
            for (var m : e.messages()) {
                arr.add(WebWireJson.messageNode(m));
            }
            LOG.debug("[web] agent_end translated with {} messages", e.messages().size());
        } else {
            LOG.warn("[web] agent_end has EMPTY messages — payload omits messages; "
                + "frontend will keep stale list. willRetry={}", e.willRetry());
        }
        return new WebServerMessage.AgentEvent(node);
    }

    private WebServerMessage bashOutput(AgentSessionEvent.BashExecutionUpdate b) {
        var node = typeNode("bashOutput");
        node.put("id", b.id());
        node.put("delta", b.delta());
        return new WebServerMessage.AgentEvent(node);
    }

    // ── 工具执行生命周期（包⑦，docs/34）────────────────────────────────

    private WebServerMessage toolExecutionStart(AgentSessionEvent.ToolExecutionStart s) {
        var node = typeNode("tool_execution_start");
        node.put("toolCallId", s.toolCallId());
        node.put("toolName", s.toolName());
        node.set("args", JSON.valueToTree(s.args()));
        return new WebServerMessage.AgentEvent(node);
    }

    private WebServerMessage toolExecutionUpdate(AgentSessionEvent.ToolExecutionUpdate u) {
        var node = typeNode("tool_execution_update");
        node.put("toolCallId", u.toolCallId());
        node.put("toolName", u.toolName());
        node.set("args", JSON.valueToTree(u.args()));
        node.set("partialResult", toolPayload(u.partialResult()));
        return new WebServerMessage.AgentEvent(node);
    }

    private WebServerMessage toolExecutionEnd(AgentSessionEvent.ToolExecutionEnd e) {
        var node = typeNode("tool_execution_end");
        node.put("toolCallId", e.toolCallId());
        node.put("toolName", e.toolName());
        node.set("result", toolPayload(e.result()));
        // isError 是**另立的**字段，不在 result 里面（pi 同）。
        node.put("isError", e.isError());
        return new WebServerMessage.AgentEvent(node);
    }

    /**
     * 复用 RPC 侧那条 {@code ToolResult → 线形状}的显式投影（包⑦ 裁决 B）。
     *
     * <p>两个面必须**给出同一个形状**（都要照 pi 的 {@code AgentToolResult} 省略
     * `usage`/`addedToolNames`/`terminate` 三个可选键），所以只留**一处**定义 ——
     * 复制一份才是真风险（改了这头忘那头）。`pi-java-web` 本就依赖
     * `pi-java-coding-agent`，不引入新依赖方向。</p>
     */
    private static com.fasterxml.jackson.databind.JsonNode toolPayload(Object payload) {
        return com.pijava.coding.agent.mode.JsonEventMapper.toolPayload(payload);
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
