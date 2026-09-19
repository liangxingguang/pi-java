package com.pijava.coding.agent.mode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
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
                // pi 恒写顶层 usage（json-event.ts:60 `usage: event.message.usage`），
                // 且**永不为 undefined** —— 流起点就被初始化成零值对象
                // （anthropic-messages.ts:518-525、openai-completions.ts:325-332）。
                // ⇒ 缺 UsageInfo 时兜零，**不省键**。注意这与 B41（终局 assistant
                // 消息的 usage 可空、缺则整键消失）是两条不同的口径，见 docs/33 §8.0 裁决 C。
                var partial = u.streamEvent().partial();   // ⚠️ UsageInfo 变体可为 null
                var info = partial == null ? null : partial.usage();
                node.set("usage", MAPPER.valueToTree(
                    info == null ? Usage.of(0, 0) : info.toUsage()));
                node.set("assistantMessageEvent", assistantMessageEvent(u.streamEvent()));
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
            // ── 工具执行生命周期（包⑦，docs/34）──────────────────────────────
            // pi 对非 message_update 事件**原样透传**（json-event.ts:48-51 的
            // `return event;`）⇒ 这三条的载荷在 pi 的线上逐字节可见。三条变体的
            // 字段**全部必填、pi 侧一个 `?` 都没有**（types.ts:443-446）⇒ 一个键
            // 都不许省 —— 这与上面那套「按 `?` 省略可空键」的纪律相反，别照抄邻行。
            case AgentSessionEvent.ToolExecutionStart s -> {
                node.put("type", "tool_execution_start");
                node.put("toolCallId", s.toolCallId());
                node.put("toolName", s.toolName());
                node.set("args", MAPPER.valueToTree(s.args()));
            }
            case AgentSessionEvent.ToolExecutionUpdate u -> {
                node.put("type", "tool_execution_update");
                node.put("toolCallId", u.toolCallId());
                node.put("toolName", u.toolName());
                node.set("args", MAPPER.valueToTree(u.args()));
                node.set("partialResult", toolPayload(u.partialResult()));
            }
            case AgentSessionEvent.ToolExecutionEnd e -> {
                node.put("type", "tool_execution_end");
                node.put("toolCallId", e.toolCallId());
                node.put("toolName", e.toolName());
                node.set("result", toolPayload(e.result()));
                // isError 是**另立的**字段，不在 result 里面（pi 同）。
                node.put("isError", e.isError());
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

    /**
     * 工具载荷（{@code result} / {@code partialResult}）的显式投影（包⑦ 裁决 B）。
     *
     * <p>pi 的 {@code AgentToolResult} 是 {@code {content, details, usage?,
     * addedToolNames?, terminate?}}（{@code agent/src/types.ts:362-376}）—— <b>后三个
     * 带 {@code ?}</b> ⇒ 缺席即省略。pi-java 的 {@link ToolResult} 是 record，
     * {@code terminate} 是原始 {@code boolean}、{@code addedToolNames} 恒 {@code []}，
     * 且全类零 Jackson 注解、全仓零序列化点（{@code docs/34 §5-N2}）⇒ <b>拿默认
     * Jackson 直接落线会多出三个 pi 没有的键</b>（{@code terminate:false}、
     * {@code addedToolNames:[]}、{@code details:null}），会误导 RPC 客户端。
     * 故在此显式投影，<b>不动 {@code ToolResult} 本体</b>（那是 agent-core 的公共
     * 类型，加注解会改它的语义）。</p>
     *
     * <p>非 {@link ToolResult} 的载荷（静态类型是 {@code Object}，≙ pi 的 {@code any}）
     * 按普通对象落线 —— 防御，不是主路径。</p>
     *
     * <p><b>public 且被 web 面复用</b>（包⑦）：两个面（RPC 与 web）必须给出**同一个**
     * 形状，而「省哪三个键」是个容易两边写歪的细节 ⇒ 只留一处定义。</p>
     */
    public static com.fasterxml.jackson.databind.JsonNode toolPayload(Object payload) {
        if (!(payload instanceof ToolResult<?> result)) {
            return MAPPER.valueToTree(payload);
        }
        var node = MAPPER.createObjectNode();
        node.set("content", MAPPER.valueToTree(result.content()));
        if (result.details() != null) {
            node.set("details", MAPPER.valueToTree(result.details()));
        }
        if (result.usage() != null) {
            node.set("usage", MAPPER.valueToTree(result.usage()));
        }
        if (!result.addedToolNames().isEmpty()) {
            node.set("addedToolNames", MAPPER.valueToTree(result.addedToolNames()));
        }
        if (result.terminate()) {
            node.put("terminate", true);
        }
        return node;
    }

    /**
     * 增量的线格式：剥 {@code partial}（由 {@link StreamEventMixin} 完成），
     * 并给 {@code toolcall_start} **补身份**。
     *
     * <p>pi {@code modes/json-event.ts:19-31}：起点事件从
     * {@code event.partial.content[event.contentIndex]} 读 id/name 缀上去，
     * 那个位置**不是 toolCall 就抛** —— 这是「partial 与 contentIndex 必须自洽」
     * 的不变量断言，不是可降级的容错点。可空链 {@code toolCall?.type} 意味着
     * **越界也算错位**，故这里把下界与上界一并判掉。</p>
     *
     * <p>抛出的后果已核（docs/33 §5-N1）：{@code SessionEventHub} 逐个 listener
     * {@code catch (RuntimeException)} ⇒ 丢该客户端的这一帧 ＋ 一条 warning，
     * 连接与其它监听器不受影响。</p>
     */
    private static ObjectNode assistantMessageEvent(StreamEvent event) {        var delta = (ObjectNode) MAPPER.valueToTree(event);
        if (event instanceof StreamEvent.ToolCallStart start) {
            var content = start.partial().content();
            int index = start.contentIndex();
            if (index < 0 || index >= content.size()
                    || !(content.get(index) instanceof ContentBlock.ToolUseContent toolCall)) {
                throw new IllegalStateException(
                    "toolcall_start content at index " + index + " is not a tool call");
            }
            delta.put("id", toolCall.id());
            delta.put("toolName", toolCall.name());
        }
        return delta;
    }
}
