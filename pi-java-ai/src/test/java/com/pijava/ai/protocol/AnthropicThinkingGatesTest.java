package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.anthropic.models.messages.MessageCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步6：思考开关的<b>两处连带线格后果</b>（pi {@code anthropic-messages.ts:1104-1112}
 * 与 {@code :1018-1025}）。
 *
 * <ol>
 *   <li><b>温度与思考互斥</b>：{@code thinkingEnabled} 为真 ⇒ <b>不发</b> {@code temperature}
 *       （pi 注释原文：{@code // Temperature is incompatible with extended thinking}）。</li>
 *   <li><b>interleaved-thinking beta 头</b>：非 adaptive 的 reasoning 模型在开了思考时
 *       带上 {@code interleaved-thinking-2025-05-14}。</li>
 * </ol>
 *
 * <p>⚠️ <b>两条都只在「思考打开」时可观察</b> ⇒ 夹具必须先把思考打开，否则恒绿。</p>
 *
 * <p>⚠️ <b>可达性登记</b>：生产路径上 {@code StreamOptions.temperature} 恒空
 * （{@code PiLoopRunner} 不设它）⇒ 第 1 条在今天的内置路径上<b>不可观察</b>；
 * 它修的是<b>直接调 API</b> 的那条路。第 2 条可达（{@code --thinking} 就够）。</p>
 */
class AnthropicThinkingGatesTest {

    private static final ModelId<?> ID = ModelId.of("anthropic", "claude-sonnet-5");

    private MessageCreateParams buildParams(StreamRequest request) throws Exception {
        var options = new ApiOptions(
            "https://api.teamorouter.cn", "sk-test",
            Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ID, "Claude",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            200_000, 64_000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }

    private static StreamRequest request(ModelInfo m, Optional<ThinkingLevel> reasoning,
                                         double temperature) {
        return new StreamRequest(
            m, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), -1, temperature, Map.of(), reasoning);
    }

    // ── 温度抑制（pi :1104-1112）────────────────────────────────────────

    /** 没开思考 ⇒ 温度**照发**（这是对照组，没有它下一条就没有牙）。 */
    @Test
    void temperatureIsSentWhenThinkingIsOff() throws Exception {
        var params = buildParams(request(model(ModelCompat.NONE), Optional.empty(), 0.5));

        assertThat(params.temperature()).contains(0.5);
    }

    /** ⚠️ 开了思考 ⇒ 温度**不发**（pi 的 {@code !options?.thinkingEnabled} 门）。 */
    @Test
    void temperatureIsSuppressedWhenThinkingIsOn() throws Exception {
        var params = buildParams(request(
            model(ModelCompat.NONE), Optional.of(new ThinkingLevel.High()), 0.5));

        // 前提：思考确实开了 —— 否则本断言可能因为别的原因恒真
        assertThat(params.thinking()).isPresent();
        assertThat(params.temperature()).isEmpty();
    }

    /** adaptive 模型同样抑制（pi 的门只看 {@code thinkingEnabled}，不看形态）。 */
    @Test
    void temperatureIsSuppressedForAdaptiveModelsToo() throws Exception {
        var params = buildParams(request(
            model(new ModelCompat(false, null, true, true)),
            Optional.of(new ThinkingLevel.High()), 0.5));

        assertThat(params.temperature()).isEmpty();
    }

    // ── interleaved beta（pi :1018-1025）───────────────────────────────

    /** 非 adaptive 的 reasoning 模型 ＋ 开了思考 ⇒ 带 interleaved beta。 */
    @Test
    void interleavedBetaIsSentForBudgetThinking() throws Exception {
        var params = buildParams(request(
            model(ModelCompat.NONE), Optional.of(new ThinkingLevel.High()), -1));

        assertThat(params._additionalBodyProperties()).containsKey("betas");
        assertThat(params._additionalBodyProperties().get("betas").toString())
            .contains("interleaved-thinking-2025-05-14");
    }

    /** adaptive 模型**不带**该 beta（pi 的 {@code forceAdaptiveThinking !== true}）。 */
    @Test
    void interleavedBetaIsNotSentForAdaptiveModels() throws Exception {
        var params = buildParams(request(
            model(new ModelCompat(false, null, true, true)),
            Optional.of(new ThinkingLevel.High()), -1));

        assertThat(params._additionalBodyProperties()).doesNotContainKey("betas");
    }

    /** 没开思考 ⇒ 不带该 beta（pi 的 {@code thinkingEnabled === true} 门）。 */
    @Test
    void interleavedBetaIsNotSentWithoutThinking() throws Exception {
        var params = buildParams(request(model(ModelCompat.NONE), Optional.empty(), -1));

        assertThat(params._additionalBodyProperties()).doesNotContainKey("betas");
    }

    /** 模型**没有**思考能力 ⇒ 不带该 beta（pi 的 {@code model.reasoning} 门）。 */
    @Test
    void interleavedBetaIsNotSentForNonThinkingModels() throws Exception {
        var plain = new ModelInfo(ID, "Claude", Set.of(ModelCapability.TEXT),
            200_000, 64_000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty());

        var params = buildParams(request(plain, Optional.of(new ThinkingLevel.High()), -1));

        assertThat(params._additionalBodyProperties()).doesNotContainKey("betas");
    }
}
