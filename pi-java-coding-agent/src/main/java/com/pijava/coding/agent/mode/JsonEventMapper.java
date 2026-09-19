package com.pijava.coding.agent.mode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;

/**
 * {@link AgentSessionEvent} → RPC/print 线格式（对齐 pi {@code json-event.ts}）。
 *
 * <p>关键规则：{@code message_update} 事件中的 {@code assistantMessageEvent}
 * 去掉累积快照 {@code partial}（每个 {@code StreamEvent} 变体都带），只留增量 ——
 * 否则每个 delta 都会重复整条消息。其余事件按字段原样透传。</p>
 *
 * <p><b>可空键的纪律（{@code docs/31 §8.37.4}）</b>：pi 对非 {@code message_update}
 * 事件是<b>原样透传</b>（{@code json-event.ts:48-51}），所以线上「有没有这个键」
 * 完全由 {@code JSON.stringify} 决定 —— <b>{@code undefined} 被省略，{@code null} 被保留</b>。
 * 于是 Java 的 {@code null} 该不该写，取决于 pi 那个字段的类型<b>有没有 {@code ?}</b>：
 * 有 {@code ?} ⇒ 主动省略（本文件里 {@code finalError} / {@code result} /
 * {@code errorMessage} / {@code id} / {@code reason} 五处）；是必填 ⇒ 照写。
 * <b>新加一个事件时，先去看 pi 的类型声明，不要跟着邻行照抄。</b></p>
 */
public final class JsonEventMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .addMixIn(StreamEvent.class, StreamEventMixin.class)
        .addMixIn(Message.class, MessageMixin.class);

    /** 对 StreamEvent 全变体忽略 {@code partial} 字段。 */
    @JsonIgnoreProperties("partial")
    abstract static class StreamEventMixin {
    }

    /** Omits null components so the wire shape only grows when a field is set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class MessageMixin {
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
                // pi 线格式是小写字面量 "manual"/"threshold"/"overflow"
                // （枚举名只是 Java 侧形状；与 compaction_end 同法 toLowerCase）。
                node.put("reason", c.reason().name().toLowerCase());
            }
            case AgentSessionEvent.CompactionEnd c -> {
                node.put("type", "compaction_end");
                // 与 compaction_start 同理：pi 线上是小写字面量。
                node.put("reason", c.reason().name().toLowerCase());
                // pi 的 payload 里 result 是 `CompactionResult | undefined`（:164），
                // 取消/中止/失败/R1 闩锁四路都显式写 undefined ⇒ JSON.stringify **省略键**。
                if (c.result() != null) {
                    node.set("result", MAPPER.valueToTree(c.result()));
                }
                node.put("aborted", c.aborted());
                node.put("willRetry", c.willRetry());
                // 同理：`errorMessage?: string`（:167）—— 成功路（CompactionExecutor
                // 的 applyCompaction 成功分支）也传 null，故缺省即不写。
                if (c.errorMessage() != null) {
                    node.put("errorMessage", c.errorMessage());
                }
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
                // pi 的 `finalError?: string`（agent-session.ts:170）。三个发射点里
                // **成功复位**（:700-706）根本不写这个键 ⇒ 每次重试成功都会多一个
                // "finalError":null。Java 的可空引用 ≙ pi 的 `string | undefined`。
                if (r.finalError() != null) {
                    node.put("finalError", r.finalError());
                }
            }
            case AgentSessionEvent.SummarizationRetryScheduled s -> {
                node.put("type", "summarization_retry_scheduled");
                node.put("attempt", s.attempt());
                node.put("maxAttempts", s.maxAttempts());
                node.put("delayMs", s.delayMs());
                node.put("errorMessage", s.errorMessage());
            }
            case AgentSessionEvent.SummarizationRetryAttemptStart s -> {
                node.put("type", "summarization_retry_attempt_start");
                node.put("source", s.source());
                // pi 的载荷 = source 对象展开：compaction 路含 reason，
                // branchSummary 路没有这个键（null ⇒ 省略）。
                if (s.reason() != null) {
                    node.put("reason", s.reason());
                }
            }
            case AgentSessionEvent.SummarizationRetryFinished f ->
                node.put("type", "summarization_retry_finished");
            case AgentSessionEvent.BashExecutionUpdate b -> {
                node.put("type", "bash_execution_update");
                // pi 的 `id?: string`（agent-session.ts:185，取值 `options?.id`，
                // :3027）：RPC 的 bash 命令 id 可选，缺省即无该键。
                if (b.id() != null) {
                    node.put("id", b.id());
                }
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
