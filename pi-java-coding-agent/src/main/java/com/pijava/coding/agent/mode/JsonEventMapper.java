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
            default -> node.put("type", "unsupported_event");
        }
        return node;
    }
}
