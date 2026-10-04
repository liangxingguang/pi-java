package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.ContextOverflow;

/**
 * B2（{@code 原 docs/64}）：z.ai 400「Prompt too long」在真 HTTP 线上被终局消息
 * 判为上下文溢出 —— 钉生产可达（旧首正则写死 {@code "is"} ⇒ 漏检）。
 */
class ZaiContextOverflowWireTest {

    /** z.ai 超限体：无 error 外层、无 is（原 docs/64 §2.4）。 */
    private static final String ZAI_OVERFLOW =
            "{\"code\":\"1261\",\"message\":\"Prompt too long\"}";

    @Test
    void zaiPromptTooLong400SettlesAsContextOverflow() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    400, Map.of("Content-Type", "application/json"),
                    ZAI_OVERFLOW.getBytes(StandardCharsets.UTF_8)));

            var options = new ApiOptions(server.baseUrl(), "test-key",
                    Duration.ofSeconds(5), 0, Map.of());
            var api = new OpenAICompletionsApi(options, "ZAI_API_KEY");
            var model = ModelInfo.minimal(ModelId.of("zai", "glm-4.6"));
            Message user = new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("hi")));
            var request = new StreamRequest(model, "", List.of(user), List.of(),
                    -1, -1, Map.of());

            Message result = api.send(request, options);

            assertThat(result).isInstanceOf(Message.AssistantMessage.class);
            var msg = (Message.AssistantMessage) result;
            assertThat(msg.provider()).isEqualTo("zai");
            assertThat(msg.stopReason()).isEqualTo("error");
            // SDK 把状态码前缀与整节点体拼进 errorMessage（UnexpectedStatusCodeException）。
            assertThat(msg.errorMessage()).contains("Prompt too long");
            assertThat(ContextOverflow.isContextOverflow(msg, null)).isTrue();
            // 400 不触发重试；凭证保持普通 Bearer 形状。
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(server.headers().get("authorization")).isEqualTo("Bearer test-key");
        }
    }
}
