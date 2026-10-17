package com.pijava.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pi-java {@link AgentSessionEvent} → pi-webui 前端 {@code agentEvent} 词汇翻译层
 * （Phase 7 设计 §4）。前端不改：{@code agent_start} / {@code turn_start} /
 * {@code message_start} / {@code message_update} / {@code message_end} /
 * {@code tool_execution_*} / {@code error} / {@code turn_end} / {@code agent_end}。
 *
 * <p><b>批 2 直译化</b>：会话事件面已是 pi 的单一漏斗（{@code agent-session.ts:1139-1142}
 * 的全量转发），本层逐帧透传，只剩两处适配 —— ① {@code agent_end} 的
 * {@code messages} 数组由构造注入的<b>全量转录源</b>供（会话面的载荷自 #52 起是
 * 本 pass 的 newMessages，前端的整表替换语义不变）；② {@code agent_settled}
 * 不发帧（{@code main.ts} 无此消费），只保留它对 streaming 的兜底收束。</p>
 */
final class AgentEventTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(AgentEventTranslator.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 全量转录源（agent_end 帧的 messages 数组；前端整表替换语义靠它维持）。 */
    private final Supplier<List<Message>> transcriptSource;

    private volatile boolean streaming;

    AgentEventTranslator(Supplier<List<Message>> transcriptSource) {
        this.transcriptSource = transcriptSource;
    }

    /** 当前是否在流式运行中（getState 用）。 */
    boolean isStreaming() {
        return streaming;
    }

    /** 把会话事件翻译为前端消息列表（多事件一次返回，保持顺序）。 */
    List<WebServerMessage> translate(AgentSessionEvent event) {
        var out = new ArrayList<WebServerMessage>();
        switch (event) {
            case AgentSessionEvent.MessageUpdate u -> translateStream(u.streamEvent(), out);
            case AgentSessionEvent.AgentStart ignored -> {
                streaming = true;
                out.add(new WebServerMessage.AgentEvent(typeNode("agent_start")));
            }
            // main.ts:317 仅 renderApp()，无副作用。
            case AgentSessionEvent.TurnStart ignored ->
                out.add(new WebServerMessage.AgentEvent(typeNode("turn_start")));
            // 每回合一条；main.ts:341-353 按 toolCallId 去重追加，天然耐受多条。
            // ⚠️ 不动 streaming —— 多回合 run 的第一个 turn_end 不能收流式态。
            case AgentSessionEvent.TurnEnd e -> out.add(turnEnd(e));
            case AgentSessionEvent.MessageStart m -> {
                var node = typeNode("message_start");
                node.set("message", WebWireJson.messageNode(m.message()));
                out.add(new WebServerMessage.AgentEvent(node));
            }
            // main.ts:327-339：user 回显路径不变；assistant 的 streamingMessage = null
            // 语义正确。
            case AgentSessionEvent.MessageEnd m -> {
                var node = typeNode("message_end");
                node.set("message", WebWireJson.messageNode(m.message()));
                out.add(new WebServerMessage.AgentEvent(node));
            }
            case AgentSessionEvent.AgentEnd e -> {
                streaming = false;
                out.add(agentEnd(e));
            }
            // main.ts 无 agent_settled 消费；保留它对 streaming 的兜底收束，不发帧。
            case AgentSessionEvent.AgentSettled ignored -> streaming = false;
            case AgentSessionEvent.BashExecutionUpdate b -> out.add(bashOutput(b));
            // 包⑦（原 docs/34）：工具执行生命周期事件的**真源**。载荷按裁决 A 照 pi 透传。
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
            // 会话面的 AgentStart 恒先到（引擎 :117/:146 在流开始之前发射）⇒
            // 此前 StreamEvent.Start → agent_start 的合成支已删（防双发）。
            case StreamEvent.TextDelta delta -> out.add(messageUpdate(delta.partial()));
            case StreamEvent.ThinkingDelta delta -> out.add(messageUpdate(delta.partial()));
            // 包⑥（B40）：工具增量上推 message_update —— 前端在 tool_execution_*
            // 上只 renderApp()、不读载荷，它手上最后一条 message_update 此前是工具
            // 调用**之前**那条 ⇒ 工具卡要等 agent_end 整表替换才出现。
            //
            // 包⑦（原 docs/34）**换源**：这里**不再**伪造 tool_execution_*（那三条改由
            // 真正的工具执行事件驱动，见上面 AgentSessionEvent.ToolExecution* 的三支）。
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

    /**
     * {@code agent_end} 帧：messages 数组由全量转录源供 —— 前端整表替换语义逐字节
     * 不动（会话面载荷自 #52 起是本 pass 的 newMessages，不能直接上这条线）。
     * 事件本体只取 {@code willRetry}。空 ⇒ 省键＋warn 的现状保留。
     */
    private WebServerMessage agentEnd(AgentSessionEvent.AgentEnd e) {
        var node = typeNode("agent_end");
        node.put("willRetry", e.willRetry());
        var transcript = transcriptSource.get();
        if (!transcript.isEmpty()) {
            var arr = node.putArray("messages");
            for (var m : transcript) {
                arr.add(WebWireJson.messageNode(m));
            }
            LOG.debug("[web] agent_end translated with {} messages", transcript.size());
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

    /**
     * {@code turn_end} 帧 —— pi {@code { message, toolResults }} 两个字段都必填
     * （{@code agent/src/types.ts:517}）。前端按 {@code toolCallId} 去重后把
     * {@code toolResults} 追加进消息列表（{@code client/main.ts:341-353}）。
     * 批 2 起每回合一条（引擎直供），message 恒非 null（失败路是合成失败消息）。
     */
    private WebServerMessage turnEnd(AgentSessionEvent.TurnEnd e) {
        var node = typeNode("turn_end");
        node.set("message", WebWireJson.messageNode(e.message()));
        var results = node.putArray("toolResults");
        for (var m : e.toolResults()) {
            results.add(WebWireJson.messageNode(m));
        }
        return new WebServerMessage.AgentEvent(node);
    }

    // ── 工具执行生命周期（包⑦，原 docs/34）────────────────────────────────
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
        // B234（pi json.md:107）：execute() 本体耗时；未执行 ⇒ 键省略。
        if (e.durationMs() != null) {
            node.put("durationMs", e.durationMs());
        }
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

    /**
     * C 批次（{@code 原 docs/55 §6.3-14}）：文本正源是**消息**（{@code partial.errorMessage()}，
     * 由生产者落定），{@code Throwable} 仅兜底；两者皆无时才退回 reason 文案。
     */
    private static String errorText(StreamEvent.StreamError err) {
        var text = StreamEvent.StreamError.textOf(err);
        // 生产路的 StreamError 必经营建（reason 非 null）；裸事件夹具可能给 null，
        // 保留旧版字符串拼接的 "Stream error: null" 兜底。
        return text != null
            ? text
            : "Stream error: " + (err.reason() == null ? null : err.reason().wireName());
    }
}
