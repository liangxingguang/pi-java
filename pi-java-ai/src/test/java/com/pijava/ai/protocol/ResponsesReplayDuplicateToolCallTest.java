package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

/**
 * <b>docs/32 B152 的回放侧去重</b>：修复**之前**落盘的转录里已经带着重复的 tool call
 * （同 {@code call_id}），只靠流解析的去重救不回来 —— 那种会话会**永远** 400。
 *
 * <p>判据与流侧一致：同 {@code call_id} 只认第一条（合法调用会有**不同**的 call_id）。
 * 被丢掉的调用对应的 {@code function_call_output} 也要一起丢，否则输出会引用一个
 * 输入里不存在的 {@code call_id}，照样 400。</p>
 */
class ResponsesReplayDuplicateToolCallTest {

    private static final ModelId<?> TARGET = ModelId.of("openai", "gpt-5-mini");
    private static final String DUP_ID = "call_1|fc_1";

    private static ResponseCreateParams buildParams(List<Message> messages) throws Exception {
        var request = new StreamRequest(TARGET, null, messages, List.of(), 100, 0.5, Map.of());
        return ResponsesMessageConverter.buildParams(request,
            ResponsesOptions.from(ApiOptions.defaults()), "gpt-5-mini", "openai-responses",
            CompatResolver.forResponses(request.model(), false));
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistantWithTwoIdenticalCalls() {
        return new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent(DUP_ID, "ls", Map.of("path", "docs")),
            new ContentBlock.ToolUseContent(DUP_ID, "ls", Map.of("path", "docs"))),
            "toolUse", null, "openai-responses", TARGET.provider(), TARGET.modelName(),
            null, null, null, null);
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage(DUP_ID, "ls",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    private static List<ResponseInputItem> items(ResponseCreateParams params) {
        return params.input().orElseThrow().asResponse();
    }

    @Test
    void duplicateCallAndItsOutputAreDroppedOnReplay() throws Exception {
        var params = buildParams(List.of(user("hi"),
            assistantWithTwoIdenticalCalls(), toolResult("a"), toolResult("b")));

        var calls = items(params).stream().filter(ResponseInputItem::isFunctionCall).toList();
        var outputs = items(params).stream().filter(ResponseInputItem::isFunctionCallOutput).toList();

        assertThat(calls).as("同 call_id 的第二次调用不许再发").hasSize(1);
        assertThat(outputs).as("它对应的输出同样只发一条").hasSize(1);
        assertThat(calls.get(0).asFunctionCall().callId()).isEqualTo("call_1");
    }

    /** 对照：两个**不同**的调用必须原样保留（去重不许误伤）。 */
    @Test
    void distinctCallsSurviveReplay() throws Exception {
        var assistant = new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent("call_1|fc_1", "ls", Map.of("path", "a")),
            new ContentBlock.ToolUseContent("call_2|fc_2", "ls", Map.of("path", "b"))),
            "toolUse", null, "openai-responses", TARGET.provider(), TARGET.modelName(),
            null, null, null, null);
        var params = buildParams(List.of(user("hi"), assistant,
            new Message.ToolResultMessage("call_1|fc_1", "ls",
                List.of(new ContentBlock.TextContent("a")), false),
            new Message.ToolResultMessage("call_2|fc_2", "ls",
                List.of(new ContentBlock.TextContent("b")), false)));

        assertThat(items(params).stream().filter(ResponseInputItem::isFunctionCall)).hasSize(2);
        assertThat(items(params).stream().filter(ResponseInputItem::isFunctionCallOutput)).hasSize(2);
    }
}
