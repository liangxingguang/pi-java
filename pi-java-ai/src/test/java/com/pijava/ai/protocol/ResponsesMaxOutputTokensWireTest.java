package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-10 第 6 步：Responses 车道的 {@code max_output_tokens} 与
 * {@code compat.supportsMaxOutputTokens} 门。
 *
 * <p>pi 的两条副本在这一点上**不对称**（P24）：{@code openai-responses.ts:321} 写
 * {@code if (options?.maxTokens && compat.supportsMaxOutputTokens)}，而
 * {@code azure-openai-responses.ts:309} 的对应行**光秃秃**（{@code if (options?.maxTokens)}）
 * —— azure 那份 compat 副本里连这个键都不存在（{@code supportsMaxOutputTokens} 在 azure
 * 文件里零命中）。本仓两条车道共用 {@link ResponsesMessageConverter} ⇒ 那份不对称落在
 * **车道名**上（该类的 {@code MAX_OUTPUT_TOKENS_GATED_LANE}）。</p>
 *
 * <p>⚠️ 而在包 A-10 之前，这两条车道是**一个上限都不发**的（{@code request.maxTokens()}
 * 恒 {@code -1} ⇒ {@code if (> 0)} 恒假）。第 3 步的漏斗把请求上限解析成模型上限之后，
 * 这两条车道才开始发 —— 本文件的第 1／5 条因此同时钉住「发」与「发多少」，
 * 第 2／3 条钉住门的**车道不对称**。</p>
 */
class ResponsesMaxOutputTokensWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 车道缺省：没写 compat ⇒ 发（pi {@code openai-responses.ts:79} 的 {@code ?? true}）。 */
    @Test
    void theOpenAiLaneSendsTheResolvedCapByDefault() throws Exception {
        var body = body(openAi(), model("openai", "gpt-5", 3_000, ModelCompat.NONE));

        assertThat(body.path("max_output_tokens").asInt())
            .as("模型上限 3000 经漏斗回落＋夹取后原样落线")
            .isEqualTo(3_000);
    }

    /**
     * ★ 本步的核心：显式 {@code supportsMaxOutputTokens: false} ⇒ **整个键不发**
     * （pi {@code openai-responses.ts:321} 的门；{@code types.ts:773-774} 的注释说明它存在的
     * 理由 ——「某些 Codex 协议网关会拒绝这个参数」）。
     */
    @Test
    void theGateSuppressesTheFieldOnTheOpenAiLane() throws Exception {
        var body = body(openAi(), model("openai", "gpt-5", 3_000, gated(false)));

        assertThat(body.path("max_output_tokens").isMissingNode())
            .as("门关 ⇒ 键整个不发，而不是发一个 0")
            .isTrue();
    }

    /**
     * ★ 对照：**同一个**显式 {@code false} 在 azure 车道上**无效** —— pi 的 azure 副本没有这道
     * 门，字段照发。这条是探针 M5 的对照组：M5 把门后的缺省从 {@code true} 翻成
     * {@code false} 时，它必须**保持绿**（否则说明门泄漏到了 azure）。
     */
    @Test
    void theAzureLaneIgnoresTheGate() throws Exception {
        var body = body(azure(),
            model("azure-openai-responses", "gpt-4o", 3_000, gated(false)));

        assertThat(body.path("max_output_tokens").asInt())
            .as("azure 侧不读 supportsMaxOutputTokens ⇒ 显式 false 也照发")
            .isEqualTo(3_000);
    }

    /** 显式 {@code true} 与缺席同效（反例：不是「写了 compat 就不发」）。 */
    @Test
    void anExplicitTrueIsTheSameAsAbsent() throws Exception {
        var body = body(openAi(), model("openai", "gpt-5", 3_000, gated(true)));

        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(3_000);
    }

    /** pi {@code OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16}：这是**下限**，与夹取（上限）方向相反。 */
    @Test
    void theSixteenTokenFloorAppliesAfterTheCap() throws Exception {
        var body = body(openAi(), model("openai", "gpt-5", 7, ModelCompat.NONE));

        assertThat(body.path("max_output_tokens").asInt())
            .as("模型上限 7 < 16 ⇒ 抬到 16（pi :322 的 Math.max）")
            .isEqualTo(16);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 一条车道（两条的差别只在构造器与 api 名）。 */
    private interface LaneSut {
        AbstractChatApi api(ApiOptions options);
    }

    private static LaneSut openAi() {
        return options -> new OpenAIResponsesApi(options, "OPENAI_API_KEY");
    }

    private static LaneSut azure() {
        return options -> new AzureOpenAIResponsesApi(options, "AZURE_OPENAI_API_KEY");
    }

    /** 送出一次请求，回读**真出站字节**（桩恒回 400，这里只关心请求体）。 */
    private static JsonNode body(LaneSut lane, ModelInfo model) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var request = new StreamRequest(model, null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of());
            try (var iter = lane.api(new ApiOptions(server.baseUrl(), "test-key",
                    Duration.ofSeconds(5), 0, Map.of()))
                    .streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到，流怎么结束与断言无关
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    /**
     * 只改 {@code supportsMaxOutputTokens} 一位的 compat —— 其余位与
     * {@code ModelCompat.NONE} 同值，避免夹具顺带变更别的行为（例如 strict 门）。
     */
    private static ModelCompat gated(Boolean flag) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, null, null, null, null, flag);
    }

    private static ModelInfo model(String provider, String name, int maxOutput,
                                   ModelCompat compat) {
        var id = ModelId.of(provider, name);
        return new ModelInfo(id, name, Set.of(ModelCapability.TEXT),
            200_000, maxOutput, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }
}
