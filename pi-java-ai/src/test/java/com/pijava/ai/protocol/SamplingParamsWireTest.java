package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-10：模型级 {@code samplingParams} 落到 OpenAI 兼容车道
 * （pi {@code openai-completions.ts:996-999}、{@code openai-responses.ts:362-365}、
 * {@code azure-openai-responses.ts:345-347} —— 三处同形）。
 *
 * <p>它是 body 的**最后一个**变更，pi 的注释写明理由：{@code Last so custom keys override
 * the named request fields}。⚠️ **那句话说出的语义是「同名键压过具名字段」，不是「排在最后」**
 * ⇒ 本文件钉的是**覆盖**与**在场**两件事，不钉键序（A-01 的教训：跨实现断言键序是自找的）。</p>
 *
 * <p>{@code ModelInfo.samplingParams} 在包 A7 就有了字段、但**零消费者**（{@code 原 docs/53 §4.4}
 * 列在「零消费者》那张清单里）⇒ 本包第一次给它装上消费者。per-request 那一半
 * （pi 的 {@code options.samplingParams}）在 java **没有生产者** ⇒ 登记 B125。</p>
 */
class SamplingParamsWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── completions ─────────────────────────────────────────────────────

    /** pi 的用例：{@code top_p} 这类 pi **不建模**的键经原样通道落到 body。 */
    @Test
    void completionsSendsUnknownSamplingKeys() throws Exception {
        var body = completionsWire(Map.of("top_p", 0.9));

        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
    }

    /**
     * ★ 覆盖语义：同名键**压过**具名字段，且**不是**写出两份。
     *
     * <p>这条是变异探针 M6 的靶子 —— 若把采样参数一律塞进 SDK 的非类型化通道
     * （{@code putAdditionalBodyProperty}），SDK 会把它与类型化字段**并列**写出
     * ⇒ body 里出现两个 {@code max_completion_tokens}（最后一个赢，但字节与 pi 不同）。</p>
     */
    @Test
    void completionsSamplingKeysOverrideNamedFieldsWithoutDuplicating() throws Exception {
        var request = request(model(Map.of("max_completion_tokens", 7)), -1);

        var raw = completionsBody(request);

        assertThat(MAPPER.readTree(raw).path("max_completion_tokens").asInt()).isEqualTo(7);
        assertThat(occurrences(raw, "\"max_completion_tokens\""))
            .as("覆盖 = 一个键；写成两份就不是 pi 的 Object.assign 语义了：%s", raw)
            .isEqualTo(1);
    }

    /** 嵌套值原样往返（{@code JsonValue.from(Map)} 得一个真对象，不是字符串）。 */
    @Test
    void completionsSendsNestedSamplingValues() throws Exception {
        var body = completionsWire(Map.of("extra_body", Map.of("top_k", 5)));

        assertThat(body.path("extra_body").path("top_k").asInt()).isEqualTo(5);
    }

    /** 对照组：没有 {@code samplingParams} 时，body 里不该多出任何采样键。 */
    @Test
    void completionsWithoutSamplingParamsAddsNothing() throws Exception {
        var body = completionsWire(null);

        assertThat(body.path("top_p").isMissingNode()).isTrue();
        assertThat(body.has("extra_body")).isFalse();
    }

    // ── responses（同一份 converter 服务 openai-responses 与 azure）──────

    /** ★ RED-6：Responses 车道同一条语义（pi 的两处是副本，java 是一处共用的实现）。 */
    @Test
    void responsesSendsUnknownSamplingKeys() throws Exception {
        var body = responsesWire(Map.of("top_p", 0.9));

        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
    }

    /** 配对：Responses 的具名字段是 {@code max_output_tokens}，覆盖语义同 completions。 */
    @Test
    void responsesSamplingKeysOverrideNamedFieldsWithoutDuplicating() throws Exception {
        var raw = responsesRaw(Map.of("max_output_tokens", 7));

        assertThat(MAPPER.readTree(raw).path("max_output_tokens").asInt()).isEqualTo(7);
        assertThat(occurrences(raw, "\"max_output_tokens\"")).isEqualTo(1);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static JsonNode completionsWire(Map<String, Object> sampling) throws Exception {
        return MAPPER.readTree(completionsBody(request(model(sampling), -1)));
    }

    private static JsonNode responsesWire(Map<String, Object> sampling) throws Exception {
        return MAPPER.readTree(responsesRaw(sampling));
    }

    private static String completionsBody(StreamRequest request) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drain(api, request);
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return server.body();
        }
    }

    private static String responsesRaw(Map<String, Object> sampling) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drain(api, request(model(sampling), -1));
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return server.body();
        }
    }

    private static void drain(AbstractChatApi api, StreamRequest request) {
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                iter.next();
            }
        } catch (Exception ignored) {
            // 桩恒回 400：请求体已录到
        }
    }

    private static int occurrences(String raw, String needle) {
        return raw.split(needle, -1).length - 1;
    }

    private static StreamRequest request(ModelInfo model, int maxTokens) {
        return new StreamRequest(model, "be brief",
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), maxTokens, -1, Map.of(), Optional.empty());
    }

    /** {@code sampling == null} ⇒ 空的采样表（≙ pi 的 {@code samplingParams: undefined}）。 */
    private static ModelInfo model(Map<String, Object> sampling) {
        Map<String, Object> params = sampling == null ? Map.of() : new LinkedHashMap<>(sampling);
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "GPT-5",
            Set.of(), 200_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), params, ModelCompat.NONE);
    }
}
