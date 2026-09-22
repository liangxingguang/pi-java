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
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步5：Anthropic 车道的<b>思考请求三分支</b>（pi
 * {@code anthropic-messages.ts:1152-1180} 的落线 ＋ {@code :858-904} 的 streamSimple 分流）。
 *
 * <p>⚠️ <b>本文件是包H5 唯一的「用户今天撞得到」证据</b>：改动前
 * {@code --thinking high} 在请求体里<b>什么也不产生</b>（那条 extra 通道从未被写入）。
 * 三条分支逐个钉：</p>
 *
 * <ol>
 *   <li><b>budget 型</b>（默认）：{@code {type:"enabled", budget_tokens, display}}</li>
 *   <li><b>adaptive 型</b>（{@code compat.forceAdaptiveThinking}）：{@code {type:"adaptive",
 *       display}} ＋ {@code output_config:{effort}}</li>
 *   <li><b>关闭</b>（无 reasoning ＋ 模型有思考能力 ＋ 目录未把 {@code off} 标为不支持）：
 *       {@code {type:"disabled"}}</li>
 * </ol>
 *
 * <p>{@code display} 恒 {@code "summarized"} —— pi 的默认（{@code :1167}）；pi-java 没有
 * 用户面能改它（{@code docs/46 §9} 登记）。</p>
 */
class AnthropicThinkingWireTest {

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

    /** 带思考能力的模型（pi 的 {@code reasoning: true}）。 */
    private static ModelInfo thinkingModel(ThinkingLevelMap map, ModelCompat compat, int maxOut) {
        return new ModelInfo(ID, "Claude",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            200_000, maxOut, false, PricingInfo.UNKNOWN, map, Map.of(), Map.of(), compat);
    }

    /**
     * 输出上限**足够大**（64000）的思考模型 —— 用于隔离「级别 → 预算」这条映射，
     * 不让 {@code adjust} 的收缩分支或线格的 {@code min} 咬到。
     */
    private static ModelInfo roomyModel(ThinkingLevelMap map, ModelCompat compat) {
        return thinkingModel(map, compat, 64_000);
    }

    private static StreamRequest request(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                         int maxTokens) {
        return new StreamRequest(
            model, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), maxTokens, -1, Map.of(), reasoning);
    }

    // ── 分支 1：budget 型 ───────────────────────────────────────────────

    /** 默认形态：{@code enabled} ＋ 预算 ＋ {@code display:"summarized"}。 */
    @Test
    void budgetModelEmitsEnabledThinkingWithABudget() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(), ModelCompat.NONE);

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.Medium()), -1));

        var thinking = params.thinking().orElseThrow();
        assertThat(thinking.isEnabled()).isTrue();
        assertThat(thinking.asEnabled().budgetTokens()).isEqualTo(8192L);
        assertThat(thinking.asEnabled().display()).contains(
            com.anthropic.models.messages.ThinkingConfigEnabled.Display.SUMMARIZED);
    }

    /** {@code high} 级 ⇒ 预算 16384（pi 的 {@code DEFAULT_THINKING_BUDGETS.high}）。 */
    @Test
    void highLevelUsesTheHighBudget() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(), ModelCompat.NONE);

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1));

        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(16384L);
    }

    /**
     * ⚠️ {@code xhigh}/{@code max} <b>夹到 high</b> 的预算（pi 的 {@code clampReasoning}）——
     * 它们没有自己的预算槽。
     */
    @Test
    void xhighAndMaxUseTheHighBudgetToo() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(), ModelCompat.NONE);

        var xhigh = buildParams(request(model, Optional.of(new ThinkingLevel.XHigh()), -1));
        var max = buildParams(request(model, Optional.of(new ThinkingLevel.Max()), -1));

        assertThat(xhigh.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(16384L);
        assertThat(max.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(16384L);
    }

    /**
     * ⚠️ 线格对预算<b>再夹一次</b>：{@code min(budget, max(0, max_tokens - 1024))}
     * （pi 的 {@code streamSimple:899}）—— 与 {@code adjust} 内部那次收缩<b>是两道</b>。
     */
    @Test
    void budgetIsCappedToLeaveAnswerRoom() throws Exception {
        // 模型上限 5000 ⇒ max_tokens = 5000（base 缺席），5000 <= 16384 ⇒ adjust 内收缩到 3976
        var model = thinkingModel(ThinkingLevelMap.empty(), ModelCompat.NONE, 5000);

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1));

        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(5000 - 1024L);
        assertThat(params.maxTokens()).isEqualTo(5000L);
    }

    /** 天花板低于 {@code MIN_ANSWER_TOKENS} ⇒ 预算算成 0 ⇒ 线格的 {@code || 1024} 兜底。 */
    @Test
    void zeroBudgetFallsBackToTheLiteral1024() throws Exception {
        var model = thinkingModel(ThinkingLevelMap.empty(), ModelCompat.NONE, 500);

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1));

        // pi :1173 `options.thinkingBudgetTokens || 1024` —— 0 会落到 1024
        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(1024L);
    }

    /** 调用方给了上限 ⇒ {@code max_tokens} 涨到 {@code min(base+budget, 模型上限)}。 */
    @Test
    void explicitCapGrowsToFitTheThinkingBudget() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(), ModelCompat.NONE);

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.Low()), 10_000));

        // min(10000 + 2048, 64000) = 12048，且 12048 > 2048 ⇒ 预算原样
        assertThat(params.maxTokens()).isEqualTo(12_048L);
        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(2048L);
    }

    /**
     * ⚠️ <b>线格那道 min 与 {@code adjust} 内那道是两道</b>，且存在只有后者不咬的区间：
     * {@code max_tokens - 1024 < budget <= max_tokens}。
     *
     * <p>本用例刻意落在这个区间（budget 2048、max_tokens 3048 ⇒ 2024 &lt; 2048 ≤ 3048）——
     * {@code adjust} 不收缩（{@code 3048 > 2048}），而线格的
     * {@code min(2048, 3048-1024)} 把它压到 <b>2024</b>。</p>
     */
    @Test
    void wireRoomCapBitesWhereAdjustDidNot() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(), ModelCompat.NONE);

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.Low()), 1000));

        assertThat(params.maxTokens()).isEqualTo(3048L);
        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(3048 - 1024L);
    }

    // ── 分支 2：adaptive 型 ─────────────────────────────────────────────

    /** {@code forceAdaptiveThinking} ⇒ adaptive ＋ {@code output_config.effort}。 */
    @Test
    void adaptiveModelEmitsAdaptiveThinkingWithEffort() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(),
            new ModelCompat(false, null, true, true));

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1));

        var thinking = params.thinking().orElseThrow();
        assertThat(thinking.isAdaptive()).isTrue();
        assertThat(thinking.asAdaptive().display()).contains(
            com.anthropic.models.messages.ThinkingConfigAdaptive.Display.SUMMARIZED);
        assertThat(params.outputConfig().orElseThrow().effort())
            .get().extracting(Object::toString).isEqualTo("high");
    }

    /** adaptive 下 {@code xhigh} 的 effort 是 {@code high}（pi 的 {@code default:} 分支）。 */
    @Test
    void adaptiveXhighFallsBackToHighEffort() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(),
            new ModelCompat(false, null, true, true));

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.XHigh()), -1));

        assertThat(params.outputConfig().orElseThrow().effort())
            .get().extracting(Object::toString).isEqualTo("high");
    }

    /** 目录映射能覆盖 adaptive 的 effort（{@code mapLevelToEffort} 的映射优先）。 */
    @Test
    void catalogMappingOverridesTheAdaptiveEffort() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("max")));
        var model = roomyModel(map, new ModelCompat(false, null, true, true));

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1));

        assertThat(params.outputConfig().orElseThrow().effort())
            .get().extracting(Object::toString).isEqualTo("max");
    }

    /** adaptive 分支**不动** {@code max_tokens}（pi 的 {@code {...base}}）。 */
    @Test
    void adaptiveLeavesMaxTokensAlone() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(),
            new ModelCompat(false, null, true, true));

        var params = buildParams(request(model, Optional.of(new ThinkingLevel.High()), 1234));

        assertThat(params.maxTokens()).isEqualTo(1234L);
    }

    // ── 分支 3：显式关闭 ────────────────────────────────────────────────

    /**
     * ⚠️ <b>无 reasoning 但有思考能力 ＋ 目录未把 {@code off} 标为不支持</b> ⇒
     * {@code {type:"disabled"}}（pi {@code :1179}）—— <b>不是「省略」</b>。
     */
    @Test
    void absentReasoningEmitsExplicitDisabled() throws Exception {
        var model = roomyModel(ThinkingLevelMap.empty(), ModelCompat.NONE);

        var params = buildParams(request(model, Optional.empty(), -1));

        assertThat(params.thinking().orElseThrow().isDisabled()).isTrue();
    }

    /** 目录把 {@code off} 标为**显式不支持** ⇒ 整条 thinking 参数都不发。 */
    @Test
    void explicitNullOffSuppressesTheDisabledParam() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(ModelThinkingLevel.off(), Optional.empty()));
        var model = roomyModel(map, ModelCompat.NONE);

        // ⚠️ 前提：**同一个模型**在开了 reasoning 时是**会发** thinking 的 ——
        // 否则下面那条「缺席」断言在缺陷态恒真（包B84 的教训：凡断言某键缺席，
        // 先钉承载它的那个对象在场）。
        assertThat(buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1)).thinking())
            .as("precondition: the model is thinking-capable, so absence below is caused by the map")
            .isNotEmpty();

        var params = buildParams(request(model, Optional.empty(), -1));

        assertThat(params.thinking()).isEmpty();
    }

    /** 模型**没有**思考能力 ⇒ 整条 thinking 参数都不发（pi 的 {@code else if (model.reasoning)}）。 */
    @Test
    void nonThinkingModelEmitsNothing() throws Exception {
        var model = new ModelInfo(ID, "Claude", Set.of(ModelCapability.TEXT),
            200_000, 8192, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty());

        var withReasoning = buildParams(request(model, Optional.of(new ThinkingLevel.High()), -1));
        var without = buildParams(request(model, Optional.empty(), -1));

        assertThat(withReasoning.thinking()).isEmpty();
        assertThat(without.thinking()).isEmpty();
    }
}
