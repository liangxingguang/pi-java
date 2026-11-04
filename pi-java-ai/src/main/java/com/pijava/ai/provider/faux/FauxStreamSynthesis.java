package com.pijava.ai.provider.faux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.ToolCallArgumentsStream;

/**
 * docs/20 §5：pi {@code streamWithDeltas}（{@code providers/faux.ts:358-457}）的 Java 落点。
 *
 * <p>合成起点是<b>已解析消息</b>（身份、usage 已估算），因此这里不用
 * {@code StreamPartialBuilder}：那个 builder 从零累加，会丢掉预置消息上的
 * {@code responseId}／{@code errorMessage} 等字段。快照逐帧重建（record 不可变），
 * 旧快照不会被后续 delta 改写。</p>
 *
 * <p>abort 信号分支不移植（D216）：Java 的 {@code StreamApi} 没有 signal 通道。</p>
 */
final class FauxStreamSynthesis {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 把一条已解析消息合成事件流，直到 {@code done}／{@code error}。
     *
     * @param resolved  已带身份与估算 usage 的最终消息
     * @param publisher 接收事件的发布器
     * @param chunking  块大小与节奏
     * @throws InterruptedException 节奏等待被中断
     */
    void emit(AssistantMessage resolved, SubmissionPublisher<StreamEvent> publisher,
              FauxChunking chunking) throws InterruptedException {
        var blocks = new ArrayList<ContentBlock>();
        var partial = resolved.withContent(List.of()).withStopReason(StopReason.PENDING);
        publisher.submit(new StreamEvent.Start(partial));

        for (int index = 0; index < resolved.content().size(); index++) {
            var block = resolved.content().get(index);
            switch (block) {
                case ContentBlock.ThinkingContent thinking -> {
                    blocks.add(new ContentBlock.ThinkingContent(""));
                    publisher.submit(new StreamEvent.ThinkingStart(index, snapshot(partial, blocks)));
                    for (String chunk : chunking.split(nullToEmpty(thinking.text()))) {
                        chunking.schedule(chunk);
                        var current = (ContentBlock.ThinkingContent) blocks.get(index);
                        blocks.set(index, new ContentBlock.ThinkingContent(
                            nullToEmpty(current.text()) + chunk, current.signature(), current.redacted()));
                        publisher.submit(new StreamEvent.ThinkingDelta(
                            index, chunk, snapshot(partial, blocks)));
                    }
                    publisher.submit(new StreamEvent.ThinkingEnd(
                        index, nullToEmpty(thinking.text()), snapshot(partial, blocks)));
                }
                case ContentBlock.TextContent text -> {
                    blocks.add(new ContentBlock.TextContent(""));
                    publisher.submit(new StreamEvent.TextStart(index, snapshot(partial, blocks)));
                    for (String chunk : chunking.split(nullToEmpty(text.text()))) {
                        chunking.schedule(chunk);
                        var current = (ContentBlock.TextContent) blocks.get(index);
                        blocks.set(index, new ContentBlock.TextContent(
                            nullToEmpty(current.text()) + chunk, current.textSignature()));
                        publisher.submit(new StreamEvent.TextDelta(
                            index, chunk, snapshot(partial, blocks)));
                    }
                    publisher.submit(new StreamEvent.TextEnd(
                        index, nullToEmpty(text.text()), snapshot(partial, blocks)));
                }
                case ContentBlock.ToolUseContent call -> {
                    blocks.add(new ContentBlock.ToolUseContent(call.id(), call.name(), Map.of()));
                    publisher.submit(new StreamEvent.ToolCallStart(
                        index, snapshot(partial, blocks)));
                    var arguments = new ToolCallArgumentsStream();
                    for (String chunk : chunking.split(json(call.arguments()))) {
                        chunking.schedule(chunk);
                        blocks.set(index, new ContentBlock.ToolUseContent(
                            call.id(), call.name(), arguments.append(chunk)));
                        publisher.submit(new StreamEvent.ToolCallDelta(
                            index, call.id(), chunk, snapshot(partial, blocks)));
                    }
                    // 结束时用权威参数字节回填块（pi `partial.content[index] = block`）。
                    blocks.set(index, call);
                    publisher.submit(new StreamEvent.ToolCallEnd(index, call.id(), call.name(),
                        call.arguments(), null, null, snapshot(partial, blocks)));
                }
                default -> {
                    // pi 的助手内容闭集只有 text／thinking／toolCall；Java 扩展块（如
                    // DiffContent）不参与 faux 合成，也不冒充 toolCall。
                }
            }
        }

        if (resolved.stopReason() == StopReason.PENDING) {
            throw new IllegalStateException("Faux response ended without a stop reason");
        }
        if (resolved.stopReason() == StopReason.ERROR
                || resolved.stopReason() == StopReason.ABORTED) {
            publisher.submit(StreamEvent.StreamError.settle(
                resolved.stopReason(), null, resolved));
        } else {
            publisher.submit(StreamEvent.StreamDone.settle(resolved.stopReason(), resolved));
        }
    }

    private static AssistantMessage snapshot(AssistantMessage partial,
                                             List<ContentBlock> blocks) {
        return partial.withContent(List.copyOf(blocks));
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** pi {@code JSON.stringify(block.arguments)} 的紧凑形态。 */
    private static String json(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Faux tool arguments must be JSON values", e);
        }
    }
}
