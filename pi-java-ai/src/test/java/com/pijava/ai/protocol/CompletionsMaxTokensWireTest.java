package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-10：OpenAI 兼容车道的输出上限。
 *
 * <p>⚠️ 修复前这一格**一个字段都不发**：{@code OpenAICompletionsMessageConverter:203} 写着
 * {@code if (request.maxTokens() > 0)}，而 {@code request.maxTokens()} 在生产上恒为 {@code -1}
 * —— 三个 {@code StreamOptions} 构造点全部传 {@code OptionalInt.empty()}。pi 在
 * {@code buildBaseOptions} 里把 {@code maxTokens} 填成 {@code clamp(model.maxTokens)} 之后
 * 才进车道，故它的 {@code if (options?.maxTokens)}（{@code openai-completions.ts:836}）
 * 恒真。</p>
 *
 * <p>观测面必须是**经漏斗**的真出站体：本车道的 {@code buildParams} 与 pi 一样**没有**回落
 * （回落是 {@code streamSimple}/{@code buildBaseOptions} 那一层的事）⇒ 直接调它观察不到
 * 本包的修复。这与 {@code CompletionsCompatWireTest}（显式给 512、直调 converter、
 * 钉**字段名**）是**互补**的两面，两处都要留着。</p>
 */
class CompletionsMaxTokensWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ★ RED-3：缺席 ⇒ 发**模型自己的上限**（{@code gpt-5} 内置目录 16384）。 */
    @Test
    void absentCapSendsTheModelCap() throws Exception {
        var wire = outbound(builtIn("gpt-5"), -1);

        assertThat(wire.path("max_completion_tokens").asInt()).isEqualTo(16_384);
        assertThat(wire.path("max_tokens").isMissingNode())
            .as("标准端点走 max_completion_tokens，不发 max_tokens")
            .isTrue();
    }

    /** 配对：调用方显式给了上限 ⇒ 它就是上限（漏斗不覆盖调用方）。 */
    @Test
    void explicitCapIsHonored() throws Exception {
        assertThat(outbound(builtIn("gpt-5"), 512).path("max_completion_tokens").asInt())
            .isEqualTo(512);
    }

    /** 配对：同一条漏斗下，另一个模型给的是**它自己的**上限（证明值不是常量漏下来的）。 */
    @Test
    void anotherModelSendsItsOwnCap() throws Exception {
        assertThat(outbound(builtIn("gpt-5-nano"), -1).path("max_completion_tokens").asInt())
            .isEqualTo(4096);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static JsonNode outbound(ModelInfo model, int maxTokens) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, "be brief",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), maxTokens, -1, Map.of(), Optional.empty());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static ModelInfo builtIn(String modelName) {
        return BuiltinCatalog.openaiModels()
            .find(ModelId.of("openai", modelName)).orElseThrow();
    }
}
