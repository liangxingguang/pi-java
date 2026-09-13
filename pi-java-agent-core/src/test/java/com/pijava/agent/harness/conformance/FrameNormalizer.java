package com.pijava.agent.harness.conformance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

/**
 * {@link PiLoop.Event} → 一行归一化 JSON（{@code docs/23c §2.3}），与 pi 侧
 * {@code conformance/pi/run.test.ts} 的 {@code Normalizer} 逐条对应。
 *
 * <p><b>抹平的事</b>：{@code toolCallId} 按首次出现次序改名 {@code tc1,tc2,…}；
 * 丢弃 id / runId / timestamp 等不稳定字段。
 * <b>不抹平的事</b>：文本逐字比较，{@code arguments} 按整棵结构比较（键序已由
 * {@link CanonicalJson} 抹平）；{@code tool_execution_end} 携带**完整结果对象**、
 * {@code tool_execution_update} 携带**完整部分结果** —— 曾几何时这两处载荷被归一化
 * 直接丢掉，pi 与 pi-java 的 wire 形状差异（{@code result} 是整棵树而非 {@code details}、
 * 流式更新干脆缺席）因此在差分里完全隐身。丢字段的豁免必须**两侧同时**做，否则
 * 「比对通过」只是「没在看」。</p>
 *
 * <p>停因是唯一的**枚举名**差异：pi 用 {@code toolUse}，pi-java 用 {@code tool_use}。
 * 这是命名约定而非行为差异，归一化时映射回 pi 的写法。</p>
 */
final class FrameNormalizer {

    /** {@code toolCallId} → {@code tcN}，按首次出现次序编号。 */
    private final Map<String, String> toolCallIds = new HashMap<>();

    /** 把一个事件归一化成键序稳定的 JSON 单行。 */
    String frame(PiLoop.Event event) {
        return CanonicalJson.render(frameOf(event));
    }

    private Object frameOf(PiLoop.Event event) {
        return switch (event) {
            case PiLoop.Event.AgentStart e -> CanonicalJson.obj("type", "agent_start");
            case PiLoop.Event.TurnStart e -> CanonicalJson.obj("type", "turn_start");
            case PiLoop.Event.MessageStart e ->
                CanonicalJson.obj("type", "message_start", "message", messageOf(e.message()));
            case PiLoop.Event.MessageEnd e ->
                CanonicalJson.obj("type", "message_end", "message", messageOf(e.message()));
            case PiLoop.Event.MessageUpdate e -> CanonicalJson.obj(
                "type", "message_update", "evt", evtOf(e.assistantMessageEvent()),
                "detail", detailOf(e.assistantMessageEvent()));
            case PiLoop.Event.ToolExecutionStart e -> CanonicalJson.obj(
                "type", "tool_execution_start",
                "id", toolCallId(e.toolCallId()), "name", e.toolName());
            case PiLoop.Event.ToolExecutionUpdate e -> CanonicalJson.obj(
                "type", "tool_execution_update",
                "id", toolCallId(e.toolCallId()), "name", e.toolName(),
                "partialResult", e.partialResult());
            case PiLoop.Event.ToolExecutionEnd e -> CanonicalJson.obj(
                "type", "tool_execution_end",
                "id", toolCallId(e.toolCallId()), "name", e.toolName(),
                "result", resultOf(e.result()), "isError", e.isError());
            case PiLoop.Event.TurnEnd e -> CanonicalJson.obj(
                "type", "turn_end", "stopReason", stopReasonOf(e.message().stopReason()),
                "toolResults", toolNames(e.toolResults()));
            case PiLoop.Event.AgentEnd e -> CanonicalJson.obj(
                "type", "agent_end", "messages", messagesOf(e.messages()));
        };
    }

    /**
     * pi 的 {@code tool_execution_end.result} = {@code finalized.result} **整棵树**
     * （{@code agent-loop.ts:774-782}），逐字段规范化：
     * <ul>
     *   <li>{@code content} 走与消息内容块同一渲染器（pi 的 {@code ?? []} 规则同此）；</li>
     *   <li>{@code details} 原样进树 —— 两侧都是剧本 JSON 的解析产物，键序由
     *       {@link CanonicalJson#canonical} 抹平；null 对应 pi 的 undefined（线上缺席）；</li>
     *   <li>{@code terminate} 只在**为真**时保留 —— pi 未设置（undefined）与显式 false
     *       在批次门上同义（{@code result.terminate === true} 才终止），Java 的原始
     *       boolean false 对应两者；true/缺席的差异仍是可比的，一侧多真一侧没真立即变红；</li>
     *   <li>{@code addedToolNames} 只保留非空（pi 空数组/undefined 同样丢）；</li>
     *   <li>{@code usage} 原样透传：剧本从不设置它，一旦有剧本设置，两侧字段名不同
     *       （Java 的 {@code inputTokens}/{@code outputTokens}）会让差分立刻变红 ——
     *       这是**故意留响**，不是豁免。</li>
     * </ul>
     */
    private Object resultOf(Object raw) {
        var out = new LinkedHashMap<String, Object>();
        if (raw instanceof ToolResult<?> result) {
            out.put("content", blocksOf(result.content() == null
                ? List.of() : result.content()));
            if (result.details() != null) {
                out.put("details", result.details());
            }
            if (result.usage() != null) {
                out.put("usage", result.usage());
            }
            if (result.terminate()) {
                out.put("terminate", true);
            }
            if (!result.addedToolNames().isEmpty()) {
                out.put("addedToolNames", result.addedToolNames());
            }
        } else {
            // 载荷不是结果对象（不该发生）：原样进树，让差分红给开发者看
            out.put("raw", raw);
        }
        return out;
    }

    /** pi 的 {@code AgentEventSink} 事件名 ⇒ 归一化后的 {@code evt} 字段。 */
    private static String evtOf(StreamEvent event) {
        return switch (event) {
            case StreamEvent.TextStart e -> "text_start";
            case StreamEvent.TextDelta e -> "text_delta";
            case StreamEvent.TextEnd e -> "text_end";
            case StreamEvent.ThinkingStart e -> "thinking_start";
            case StreamEvent.ThinkingDelta e -> "thinking_delta";
            case StreamEvent.ThinkingEnd e -> "thinking_end";
            case StreamEvent.ToolCallStart e -> "toolcall_start";
            case StreamEvent.ToolCallDelta e -> "toolcall_delta";
            case StreamEvent.ToolCallEnd e -> "toolcall_end";
            default -> event.getClass().getSimpleName();
        };
    }

    /**
     * pi 侧取 detail 的规则：{@code "delta" in e ? e.delta : "content" in e ? e.content
     * : "toolCall" in e ? e.toolCall.name : null} —— 即 start 类事件没有 detail。
     */
    private static Object detailOf(StreamEvent event) {
        return switch (event) {
            case StreamEvent.TextDelta e -> e.delta();
            case StreamEvent.TextEnd e -> e.text();
            case StreamEvent.ThinkingDelta e -> e.delta();
            case StreamEvent.ThinkingEnd e -> e.thinking();
            case StreamEvent.ToolCallDelta e -> e.jsonDelta();
            case StreamEvent.ToolCallEnd e -> e.name();
            default -> null;
        };
    }

    /**
     * 消息帧。toolResult 一支过去只留 {@code (role, toolName, isError)} —— 与
     * {@code resultOf} 曾经的盲点同型：**消息**载荷上的 {@code content}/
     * {@code details}/{@code usage}/{@code addedToolNames} 差异在差分里隐身
     * （pi 的 {@code createToolResultMessage} 是带它们的，{@code agent-loop.ts:784-797}）。
     * 省略规则与 {@link #resultOf} 一致（null/空 ⇒ 键缺席；usage 原样透传留响），
     * 差别只有：消息**没有** {@code terminate} 字段（pi 的结果树才有），
     * {@code toolCallId} 不上帧（id 不稳定，且顺序已足以定位）。
     */
    private Object messageOf(Message message) {
        return switch (message) {
            case Message.UserMessage user ->
                CanonicalJson.obj("role", "user", "content", textOf(user.content()));
            case Message.AssistantMessage assistant -> CanonicalJson.obj(
                "role", "assistant", "content", blocksOf(assistant.content()),
                "stopReason", stopReasonOf(assistant.stopReason()));
            case Message.ToolResultMessage result -> {
                var out = new LinkedHashMap<String, Object>();
                out.put("role", "toolResult");
                out.put("toolName", result.toolName());
                out.put("content", blocksOf(result.content()));
                if (result.details() != null) {
                    out.put("details", result.details());
                }
                if (result.usage() != null) {
                    out.put("usage", result.usage());
                }
                if (!result.addedToolNames().isEmpty()) {
                    out.put("addedToolNames", result.addedToolNames());
                }
                out.put("isError", result.isError());
                yield out;
            }
        };
    }

    private List<Object> messagesOf(List<Message> messages) {
        var out = new ArrayList<Object>(messages.size());
        for (var message : messages) {
            out.add(messageOf(message));
        }
        return out;
    }

    private static List<Object> blocksOf(List<ContentBlock> blocks) {
        var out = new ArrayList<Object>(blocks.size());
        for (var block : blocks) {
            out.add(blockOf(block));
        }
        return out;
    }

    private static Object blockOf(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextContent text ->
                CanonicalJson.obj("type", "text", "text", text.text());
            case ContentBlock.ThinkingContent thinking ->
                CanonicalJson.obj("type", "thinking", "thinking", thinking.text());
            case ContentBlock.ToolUseContent call ->
                CanonicalJson.obj("type", "toolCall", "name", call.name(),
                    "arguments", call.arguments());
            default -> CanonicalJson.obj("type", block.getClass().getSimpleName());
        };
    }

    /** pi 的用户消息内容是字符串；pi-java 用内容块表示，按序拼接回字符串。 */
    private static String textOf(List<ContentBlock> blocks) {
        var text = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent content) {
                text.append(content.text());
            }
        }
        return text.toString();
    }

    private static List<String> toolNames(List<Message.ToolResultMessage> results) {
        var names = new ArrayList<String>(results.size());
        for (var result : results) {
            names.add(result.toolName());
        }
        return names;
    }

    /** pi-java 的 {@code tool_use} 归一化回 pi 的 {@code toolUse}；其余逐字相同。 */
    private static String stopReasonOf(String stopReason) {
        return "tool_use".equals(stopReason) ? "toolUse" : stopReason;
    }

    private String toolCallId(String id) {
        var name = toolCallIds.get(id);
        if (name == null) {
            name = "tc" + (toolCallIds.size() + 1);
            toolCallIds.put(id, name);
        }
        return name;
    }
}
