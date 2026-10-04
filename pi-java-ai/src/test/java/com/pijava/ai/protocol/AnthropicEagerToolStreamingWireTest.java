package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>包 09 / B90</b>：Anthropic 车道的**工具输入流式**（{@code eager_input_streaming} ＋
 * {@code fine-grained-tool-streaming-2025-05-14} beta 头）。
 *
 * <p>pi 的三处逐字码：{@code anthropic-messages.ts:209} 的缺省 {@code ?? true}、
 * {@code :1486} 的工具字段、{@code :1449-1454}＋{@code :1016-1018} 的 beta 头。
 * 观测面是**真出站请求体**（{@link RecordingHttpServer}）。</p>
 */
class AnthropicEagerToolStreamingWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BETA = "fine-grained-tool-streaming-2025-05-14";

    private static final ToolDefinition TOOL = new ToolDefinition(
        "base_tool", "base tool", Map.of("type", "object"));

    private static List<Message> transcript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(TOOL), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
    }

    /** 没有工具 ⇒ 即便模型不支持 eager，也不该挂 beta 头（pi 的前置条件是工具表非空）。 */
    private static List<Message> toolLessTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(), List.of(), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
    }

    /** 缺省（compat 未标）⇒ pi 的 {@code ?? true} ⇒ 每个工具都带 {@code eager_input_streaming: true}。 */
    @Test
    void eagerInputStreamingDefaultsToTrue() throws Exception {
        var body = capture(model(null), transcript());

        assertThat(body.path("tools").size()).isEqualTo(1);
        assertThat(body.path("tools").get(0).path("eager_input_streaming").asBoolean())
            .as("pi anthropic-messages.ts:209 的缺省是 true")
            .isTrue();
        assertThat(betas(body)).doesNotContain(BETA);
    }

    /** 显式 false ⇒ 不发该字段，改挂细粒度工具流式 beta 头。 */
    @Test
    void eagerFalseOmitsTheFieldAndSendsTheBetaHeader() throws Exception {
        var body = capture(model(Boolean.FALSE), transcript());

        assertThat(body.path("tools").get(0).path("eager_input_streaming").isMissingNode())
            .as("pi 的 `...(cond ? {eager_input_streaming:true} : {})` 是**缺席**而非 false")
            .isTrue();
        assertThat(betas(body)).contains(BETA);
    }

    /** 没有工具时 beta 头不挂（pi `:1450-1452` 的第一个合取项）。 */
    @Test
    void noToolsMeansNoFineGrainedBetaHeader() throws Exception {
        var body = capture(model(Boolean.FALSE), toolLessTranscript());

        assertThat(body.path("tools").isMissingNode() || body.path("tools").isEmpty()).isTrue();
        assertThat(betas(body)).doesNotContain(BETA);
    }

    private static JsonNode capture(ModelInfo model, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static List<String> betas(JsonNode body) {
        var out = new ArrayList<String>();
        for (var b : body.path("betas")) {
            out.add(b.asText());
        }
        return out;
    }

    private static ModelInfo model(Boolean eagerToolInputStreaming) {
        var compat = new ModelCompat(false, null, true, false, null, null, null, null, null, true,
            null, null, null, null, null, null, null, null, null, null,
            Map.of(), Map.of(), null, null, null, null, null, true, null,
            eagerToolInputStreaming, null);
        return new ModelInfo(ModelId.of("anthropic", "claude-opus-5"), "Claude Opus 5", Set.of(),
            100_000, 1000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }
}
