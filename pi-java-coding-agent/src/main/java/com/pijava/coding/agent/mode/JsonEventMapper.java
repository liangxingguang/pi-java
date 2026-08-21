package com.pijava.coding.agent.mode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;

/**
 * {@link AgentSessionEvent} → RPC/print 线格式（对齐 pi {@code json-event.ts}）。
 *
 * <p>关键规则：{@code message_update} 事件中的 {@code assistantMessageEvent}
 * 去掉累积快照 {@code partial}（每个 {@code StreamEvent} 变体都带），只留增量 ——
 * 否则每个 delta 都会重复整条消息。其余事件按字段原样透传。</p>
 */
public final class JsonEventMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .addMixIn(StreamEvent.class, StreamEventMixin.class);

    /** 对 StreamEvent 全变体忽略 {@code partial} 字段。 */
    @JsonIgnoreProperties("partial")
    abstract static class StreamEventMixin {
    }

    private JsonEventMapper() {}

    /** 把会话事件映射为线格式 JSON 对象。 */
    public static ObjectNode toWire(AgentSessionEvent event) {
        var node = MAPPER.createObjectNode();
        switch (event) {
            case AgentSessionEvent.MessageUpdate u -> {
                node.put("type", "message_update");
                node.set("assistantMessageEvent", MAPPER.valueToTree(u.streamEvent()));
            }
            case AgentSessionEvent.AgentEnd e -> {
                node.put("type", "agent_end");
                node.put("willRetry", e.willRetry());
                var messages = node.putArray("messages");
                for (var m : e.messages()) {
                    messages.add(MAPPER.valueToTree(m));
                }
            }
            case AgentSessionEvent.AgentSettled ignored ->
                node.put("type", "agent_settled");
            case AgentSessionEvent.EntryAppended a -> {
                node.put("type", "entry_appended");
                node.set("entry", MAPPER.valueToTree(a.entry()));
            }
            case AgentSessionEvent.QueueUpdate q -> {
                node.put("type", "queue_update");
                node.set("steering", MAPPER.valueToTree(q.steering()));
                node.set("followUp", MAPPER.valueToTree(q.followUp()));
            }
            case AgentSessionEvent.SessionInfoChanged s -> {
                node.put("type", "session_info_changed");
                node.put("name", s.name());
            }
            case AgentSessionEvent.ThinkingLevelChanged t -> {
                node.put("type", "thinking_level_changed");
                node.set("level", MAPPER.valueToTree(t.level()));
            }
            case AgentSessionEvent.CompactionStart c -> {
                node.put("type", "compaction_start");
                node.put("reason", c.reason().name());
            }
            case AgentSessionEvent.CompactionEnd c -> {
                node.put("type", "compaction_end");
                node.put("reason", c.reason().name());
                node.set("result", MAPPER.valueToTree(c.result()));
                node.put("aborted", c.aborted());
                node.put("willRetry", c.willRetry());
                node.put("errorMessage", c.errorMessage());
            }
            case AgentSessionEvent.AutoRetryStart r -> {
                node.put("type", "auto_retry_start");
                node.put("attempt", r.attempt());
                node.put("maxAttempts", r.maxAttempts());
                node.put("delayMs", r.delayMs());
                node.put("errorMessage", r.errorMessage());
            }
            case AgentSessionEvent.AutoRetryEnd r -> {
                node.put("type", "auto_retry_end");
                node.put("success", r.success());
                node.put("attempt", r.attempt());
                node.put("finalError", r.finalError());
            }
            case AgentSessionEvent.BashExecutionUpdate b -> {
                node.put("type", "bash_execution_update");
                node.put("id", b.id());
                node.put("delta", b.delta());
            }
            default -> node.put("type", "unsupported_event");
        }
        return node;
    }

    /** 把单个 {@link StreamEvent} 序列化为线格式 JSON（剥除 partial）。 */
    public static String toStreamEventWire(StreamEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
