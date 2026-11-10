package com.pijava.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.ToolCallArgumentsStream;

/**
 * Anthropic Messages event assembler for the {@code custom-provider-anthropic} example.
 *
 * <p>Ports pi's block-index streaming loop: each wire block index opens exactly one
 * content block and tool-call argument stream. Text, thinking and tool-call deltas
 * are forwarded as {@code *_delta} events with a fresh partial snapshot; the tool
 * argument stream is appended once per {@code input_json_delta} and finalized once
 * on {@code content_block_stop}. Malformed final arguments are surfaced through
 * {@code ToolCallEnd.argumentsError} with empty arguments.</p>
 */
public final class CustomAnthropicStream {

    private CustomAnthropicStream() {
    }

    /** Block kind carried by {@link Input.BlockStart}. */
    public enum BlockKind {
        TEXT,
        THINKING,
        TOOL_USE
    }

    /** One Anthropic wire event consumed by {@link #assemble}. */
    public sealed interface Input {

        /** {@code content_block_start}. */
        record BlockStart(int index, BlockKind kind, String id, String name, boolean redacted)
                implements Input {
        }

        /** {@code content_block_delta} with {@code text_delta}. */
        record TextDelta(int index, String text) implements Input {
        }

        /** {@code content_block_delta} with {@code thinking_delta}. */
        record ThinkingDelta(int index, String thinking) implements Input {
        }

        /** {@code content_block_delta} with {@code signature_delta}. */
        record SignatureDelta(int index, String signature) implements Input {
        }

        /** {@code content_block_delta} with {@code input_json_delta}. */
        record InputJsonDelta(int index, String partialJson) implements Input {
        }

        /** {@code content_block_stop}. */
        record BlockStop(int index) implements Input {
        }

        /** {@code message_delta} (only the stop reason is used). */
        record MessageDelta(String stopReason) implements Input {
        }

        /** {@code message_stop}. */
        record MessageStop() implements Input {
        }
    }

    /**
     * Assemble the full event stream from an ordered list of wire events.
     *
     * @param template partial carrying identity/usage fields supplied by the caller
     * @param inputs   wire events in arrival order
     * @return ordered {@link StreamEvent}s ending with {@code done} (or {@code error})
     */
    public static List<StreamEvent> assemble(AssistantMessage template, List<Input> inputs) {
        List<ContentBlock> content = new ArrayList<>(template.content());
        Map<Integer, ToolCallArgumentsStream> tools = new HashMap<>();
        List<StreamEvent> events = new ArrayList<>();
        events.add(new StreamEvent.Start(template));
        String rawStopReason = null;

        for (Input input : inputs) {
            switch (input) {
                case Input.BlockStart start -> {
                    int contentIndex = content.size();
                    switch (start.kind()) {
                        case TEXT -> content.add(new ContentBlock.TextContent(""));
                        case THINKING ->
                            content.add(new ContentBlock.ThinkingContent("", "", start.redacted()));
                        case TOOL_USE ->
                            content.add(new ContentBlock.ToolUseContent(start.id(), start.name(), Map.of()));
                    }
                    if (start.kind() == BlockKind.TOOL_USE) {
                        tools.put(start.index(), new ToolCallArgumentsStream());
                    }
                    AssistantMessage partial = snapshot(template, content);
                    events.add(switch (start.kind()) {
                        case TEXT -> new StreamEvent.TextStart(contentIndex, partial);
                        case THINKING -> new StreamEvent.ThinkingStart(contentIndex, partial);
                        case TOOL_USE -> new StreamEvent.ToolCallStart(contentIndex, partial);
                    });
                }
                case Input.TextDelta delta -> {
                    int contentIndex = contentIndex(content, delta.index());
                    ContentBlock.TextContent block = (ContentBlock.TextContent) content.get(contentIndex);
                    content.set(contentIndex, new ContentBlock.TextContent(
                            block.text() + delta.text(), block.textSignature()));
                    events.add(new StreamEvent.TextDelta(
                            contentIndex, delta.text(), snapshot(template, content)));
                }
                case Input.ThinkingDelta delta -> {
                    int contentIndex = contentIndex(content, delta.index());
                    ContentBlock.ThinkingContent block =
                            (ContentBlock.ThinkingContent) content.get(contentIndex);
                    content.set(contentIndex, new ContentBlock.ThinkingContent(
                            block.text() + delta.thinking(), block.signature(), block.redacted()));
                    events.add(new StreamEvent.ThinkingDelta(
                            contentIndex, delta.thinking(), snapshot(template, content)));
                }
                case Input.SignatureDelta delta -> {
                    int contentIndex = contentIndex(content, delta.index());
                    ContentBlock.ThinkingContent block =
                            (ContentBlock.ThinkingContent) content.get(contentIndex);
                    content.set(contentIndex, new ContentBlock.ThinkingContent(
                            block.text(), delta.signature(), block.redacted()));
                }
                case Input.InputJsonDelta delta -> {
                    int contentIndex = contentIndex(content, delta.index());
                    ContentBlock.ToolUseContent block = (ContentBlock.ToolUseContent) content.get(contentIndex);
                    Map<String, Object> arguments = tools.get(delta.index()).append(delta.partialJson());
                    content.set(contentIndex, new ContentBlock.ToolUseContent(
                            block.id(), block.name(), arguments,
                            block.thoughtSignature(), block.namespace(), block.argumentsError()));
                    events.add(new StreamEvent.ToolCallDelta(
                            contentIndex, block.id(), delta.partialJson(), snapshot(template, content)));
                }
                case Input.BlockStop stop -> {
                    int contentIndex = contentIndex(content, stop.index());
                    ContentBlock block = content.get(contentIndex);
                    AssistantMessage partial = snapshot(template, content);
                    switch (block) {
                        case ContentBlock.TextContent text ->
                            events.add(new StreamEvent.TextEnd(contentIndex, text.text(), partial));
                        case ContentBlock.ThinkingContent thinking ->
                            events.add(new StreamEvent.ThinkingEnd(contentIndex, thinking.text(), partial));
                        case ContentBlock.ToolUseContent toolUse -> {
                            ToolCallArgumentsStream.Finish finish =
                                    tools.remove(stop.index()).finish();
                            ContentBlock.ToolUseContent finalized = new ContentBlock.ToolUseContent(
                                    toolUse.id(), toolUse.name(), finish.arguments(),
                                    toolUse.thoughtSignature(), toolUse.namespace(),
                                    finish.argumentsError());
                            content.set(contentIndex, finalized);
                            events.add(new StreamEvent.ToolCallEnd(
                                    contentIndex, finalized.id(), finalized.name(),
                                    finalized.arguments(), finalized.namespace(),
                                    finalized.argumentsError(), snapshot(template, content)));
                        }
                        default -> throw new IllegalStateException(
                                "Unsupported open block at index " + contentIndex);
                    }
                }
                case Input.MessageDelta delta -> rawStopReason = delta.stopReason();
                case Input.MessageStop ignored -> {
                    AssistantMessage partial = snapshot(template, content);
                    if (rawStopReason == null) {
                        events.add(StreamEvent.StreamError.settle(StopReason.ERROR,
                                new IllegalStateException("Anthropic stream ended without a stop reason"),
                                partial));
                    } else {
                        events.add(StreamEvent.StreamDone.settle(mapStopReason(rawStopReason), partial));
                    }
                }
            }
        }
        return List.copyOf(events);
    }

    private static int contentIndex(List<ContentBlock> content, int blockIndex) {
        if (blockIndex < 0 || blockIndex >= content.size()) {
            throw new IllegalStateException("Unknown content block index " + blockIndex);
        }
        return blockIndex;
    }

    private static AssistantMessage snapshot(AssistantMessage template, List<ContentBlock> content) {
        return template.withContent(List.copyOf(content));
    }

    private static StopReason mapStopReason(String raw) {
        return switch (raw) {
            case "end_turn", "pause_turn", "stop_sequence" -> StopReason.STOP;
            case "max_tokens" -> StopReason.LENGTH;
            case "tool_use" -> StopReason.TOOL_USE;
            default -> StopReason.ERROR;
        };
    }
}
