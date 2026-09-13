package com.pijava.agent.harness.conformance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;

/**
 * 把剧本里的一段响应翻译成 {@link StreamEvent} 序列 —— pi 侧 {@code ScriptedStream} 的对偶。
 *
 * <p>三条必须与 pi 逐字一致、否则会污染差分的规则：</p>
 * <ol>
 *   <li>{@code partial} 始终是**空快照**，只有终止事件（{@code done}/{@code error}）才携带完整
 *       内容。因此助手 {@code message_start} 发的是空消息，上下文直到流结束才被填满。</li>
 *   <li>文本块的 {@code chunks} 决定 delta：**首个分块不发 delta**，其后每块发一条
 *       {@code text_delta}。缺省（无 {@code chunks}）表示整段一次成型、零 delta。</li>
 *   <li>3a（docs/31 §8.19）：每个快照携带与 pi 侧 {@code createAssistantMessage}
 *       （run.test.ts）逐字对应的身份与计量 —— {@code api="openai-responses"}、
 *       {@code provider="openai"}、{@code model="mock"}（**恒定**，脚本换模型只影响
 *       请求、不改消息上的 model 字段）、全零 {@code usage}、{@code timestamp=now}
 *       （不进帧，两侧都不可复现）。真实 adapter 走
 *       {@code AbstractChatApi} 出口挂载，桩这里直接挂。</li>
 * </ol>
 */
final class ScriptedStreams {

    /** pi run.test.ts {@code createUsage()} 的逐字对偶。 */
    private static final Usage ZERO_USAGE =
        new Usage(0, 0, 0, 0, null, null, 0, Usage.Cost.zero());

    private ScriptedStreams() {}

    /** 与 pi 侧 {@code createAssistantMessage} 同形的桩快照。 */
    private static AssistantMessage scripted(String stopReason, List<ContentBlock> content) {
        return AssistantMessage.empty()
            .withContent(content)
            .withStopReason(stopReason)
            .withIdentity("openai-responses", "openai", "mock", Instant.now())
            .withUsage(new StreamEvent.UsageInfo(0, 0, null, ZERO_USAGE));
    }

    /**
     * @param response 剧本里的一段响应
     * @param callIds  全程共享的调用编号计数器，保证每个 {@code toolCallId} 唯一
     * @return 该响应的完整流事件序列
     */
    static List<StreamEvent> eventsFor(ConformanceScript.Response response, AtomicInteger callIds) {
        var blank = scripted("stop", List.of());
        var events = new ArrayList<StreamEvent>();
        var content = new ArrayList<ContentBlock>();
        events.add(new StreamEvent.Start(blank));

        for (int index = 0; index < response.content().size(); index++) {
            var block = response.content().get(index);
            switch (block.type()) {
                case "text" -> {
                    var text = block.text() == null ? "" : block.text();
                    events.add(new StreamEvent.TextStart(index, blank));
                    var chunks = block.chunks();
                    if (chunks != null) {
                        for (var chunk : chunks.subList(1, chunks.size())) {
                            events.add(new StreamEvent.TextDelta(index, chunk, blank));
                        }
                    }
                    events.add(new StreamEvent.TextEnd(index, text, blank));
                    content.add(new ContentBlock.TextContent(text));
                }
                case "thinking" -> {
                    var text = block.thinking() == null ? "" : block.thinking();
                    events.add(new StreamEvent.ThinkingStart(index, blank));
                    events.add(new StreamEvent.ThinkingEnd(index, text, blank));
                    content.add(new ContentBlock.ThinkingContent(text));
                }
                case "toolCall" -> {
                    var name = block.name();
                    var arguments = block.arguments() == null ? Map.<String, Object>of()
                        : block.arguments();
                    var id = "call_" + name + "_" + callIds.incrementAndGet();
                    events.add(new StreamEvent.ToolCallStart(index, blank));
                    events.add(new StreamEvent.ToolCallEnd(index, id, name, arguments, blank));
                    content.add(new ContentBlock.ToolUseContent(id, name, arguments));
                }
                default -> throw new IllegalStateException(
                    "unknown content type: " + block.type());
            }
        }

        var stopReason = internalStopReason(response.stopReason());
        var terminal = scripted(stopReason, List.copyOf(content));
        if ("aborted".equals(response.stopReason()) || "error".equals(response.stopReason())) {
            // pi: aborted 走 error 事件，而不是 done —— done.reason 的闭集里没有 aborted
            events.add(new StreamEvent.StreamError(response.stopReason(), null, terminal));
        } else {
            events.add(new StreamEvent.StreamDone(stopReason, null, terminal));
        }
        return List.copyOf(events);
    }

    /** 剧本用 pi 的写法（{@code toolUse}），pi-java 的停因枚举用 {@code tool_use}。 */
    private static String internalStopReason(String scriptStopReason) {
        return "toolUse".equals(scriptStopReason) ? "tool_use" : scriptStopReason;
    }
}
