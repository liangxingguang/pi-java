package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAICompletionsApiTest {

    @Test
    void buildParamsPassesToolsAndRequestsUsage() throws Exception {
        var api = new OpenAICompletionsApi(
            new ApiOptions("", "test-key", Duration.ofSeconds(10), 1, Map.of()),
            "OPENAI_API_KEY");
        var request = new StreamRequest(
            ModelId.of("deepseek", "deepseek-v4-flash"),
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(new ToolDefinition(
                "write", "Write a file",
                Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"))))),
            100, 0.5, Map.of());

        var method = OpenAICompletionsApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        var params = (ChatCompletionCreateParams) method.invoke(api, request);

        var tools = params.tools().orElseThrow();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).isFunction()).isTrue();
        assertThat(params.streamOptions().orElseThrow()
            .includeUsage().orElse(false)).isTrue();
    }
}
