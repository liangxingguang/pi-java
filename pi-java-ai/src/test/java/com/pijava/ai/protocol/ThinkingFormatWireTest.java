package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ChatTemplateKwargValue;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ThinkingFormat;
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
 * 包 A-09：思考开关的十一种线格形状（pi {@code openai-completions.ts:873-970}）。
 *
 * <p>本包之前 completions 车道**一个思考字段都不发** —— 连缺省的 {@code openai} 形状
 * （{@code reasoning_effort}）都没有生产者（{@code docs/58} 篇首）。验收口径来自 pi 的
 * 行为夹具（{@code docs/58 §2.6}）：pi 的 {@code capture()} 用 {@code onPayload} 抓请求体，
 * java 的对应物是 {@link RecordingHttpServer}。</p>
 *
 * <p>⚠️ 断言一律**按键取值**、不钉键序：SDK 的类型化路径 JSON 键序与 pi 不同
 * （{@code docs/56 §12} 的教训）。「同名键只许出现一次」用 {@link #occurrences} 钉。</p>
 *
 * <p>探测背景：夹具的 baseUrl 是 localhost、provider 是 {@code test} ⇒ 探测给
 * {@code thinkingFormat=openai}、{@code supportsReasoningEffort=true}
 * （七谓词一个都不命中）。要测其它形状或关门，用显式 compat（≙ 用户写了 models.json）。</p>
 */
class ThinkingFormatWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── openai 形状（缺省；pi :962-970）───────────────────────────────

    /**
     * ★ 本包最重的一条今天可达修复：探测的缺省形状把级别送上
     * {@code reasoning_effort}（此前一个字段都不发）。
     */
    @Test
    void theDetectedDefaultSendsTheReasoningEffort() throws Exception {
        var body = body(model(ModelCompat.NONE, ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("reasoning_effort").asText()).isEqualTo("medium");
    }

    /** {@code map[level] ?? level}：映射值赢过级别名。 */
    @Test
    void theMappedEffortWinsOverTheLevelName() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("HIGH")));
        var body = body(model(ModelCompat.NONE, map, Map.of()),
            Optional.of(new ThinkingLevel.High()));

        assertThat(body.path("reasoning_effort").asText()).isEqualTo("HIGH");
    }

    /**
     * ★ 设计期预测被实测推翻的一格（{@code docs/58 §2.4} R4 的前提修正）：
     * 显式 {@code map{medium:null}} **不会**让线上出现「null 回落级别名」——
     * 夹取先把显式 null 的级别踢出可用集（pi {@code getSupportedThinkingLevels} 的
     * {@code mapped === null ⇒ false}），medium 被向上夹到 high ⇒ 发的是 {@code "high"}。
     *
     * <p>⇒ 两派 null 语义（{@code ??} vs {@code === undefined}）在**夹取后**的级别上
     * 不可分辨（显式 null 到不了写点）；本用例钉的是「夹取先行」本身 —— 若写点
     * 直接读 {@code request.reasoning()}（跳过夹取），这里会发 {@code "medium"} 而红。</p>
     */
    @Test
    void anExplicitNullClampsTheLevelAway() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.Medium()), Optional.empty()));
        var body = body(model(ModelCompat.NONE, map, Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
    }

    /** A-10 的归属前移回执（{@code docs/57 §12.3}）：线上的是**夹取后**的级别。 */
    @Test
    void theLevelIsClampedBeforeItReachesTheWire() throws Exception {
        // 空表 ⇒ xhigh/max 是 opt-in 级、不在可用集 ⇒ 向下夹到 high（pi :741-742）。
        var body = body(model(ModelCompat.NONE, ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.XHigh()));

        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
    }

    /**
     * 无级别时的关闭支（pi :965-969）：{@code map.off} 三态 ——
     * 字符串 ⇒ 发；缺席／显式 null ⇒ 什么都不发（{@code typeof offValue === "string"} 门）。
     */
    @Test
    void theOffValueIsSentOnlyWhenItIsAString() throws Exception {
        var offString = body(model(ModelCompat.NONE, ThinkingLevelMap.of(Map.of(
                ModelThinkingLevel.off(), Optional.of("none"))), Map.of()),
            Optional.empty());
        assertThat(offString.path("reasoning_effort").asText()).isEqualTo("none");

        var offAbsent = body(model(ModelCompat.NONE, ThinkingLevelMap.empty(), Map.of()),
            Optional.empty());
        assertThat(offAbsent.has("reasoning_effort")).isFalse();

        var offNull = body(model(ModelCompat.NONE, ThinkingLevelMap.of(Map.of(
                ModelThinkingLevel.off(), Optional.empty())), Map.of()),
            Optional.empty());
        assertThat(offNull.has("reasoning_effort")).isFalse();
    }

    /** pi :962/:965 的第三个合取项：门关上 ⇒ 两个支都不写。 */
    @Test
    void theSupportsReasoningEffortGateBinds() throws Exception {
        var model = model(compat(ThinkingFormat.OPENAI, Boolean.FALSE, Map.of(), Map.of()),
            ThinkingLevelMap.empty(), Map.of());

        assertThat(body(model, Optional.of(new ThinkingLevel.Medium()))
            .has("reasoning_effort")).isFalse();
        // 关闭支同样被门挡住 —— 即使 map.off 是字符串。
        var offModel = model(compat(ThinkingFormat.OPENAI, Boolean.FALSE, Map.of(), Map.of()),
            ThinkingLevelMap.of(Map.of(ModelThinkingLevel.off(), Optional.of("none"))),
            Map.of());
        assertThat(body(offModel, Optional.empty()).has("reasoning_effort")).isFalse();
    }

    /** §2.2 事实 1：十二个臂全部合取 {@code model.reasoning} ⇒ 非推理模型恒不写。 */
    @Test
    void aNonReasoningModelSendsNothing() throws Exception {
        var model = new ModelInfo(ModelId.of("test", "chat-only"), "chat-only",
            Set.of(ModelCapability.TEXT), 200_000, 100_000, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.of(Map.of(ModelThinkingLevel.off(), Optional.of("none"))),
            Map.of(), Map.of(), ModelCompat.NONE);

        var body = body(model, Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("enable_thinking")).isFalse();
    }

    /**
     * ★ R5（{@code docs/58 §4.8}）：{@code samplingParams.reasoning_effort} 与链条写的
     * 同名键**覆盖而不并列** —— pi 的 {@code Object.assign} 是最后赢（:996-999 的注释
     * {@code Last so custom keys override the named request fields}），而 SDK 的非类型化
     * 通道会把两份都写出去（{@code docs/57 §10} 的实测病理）。
     */
    @Test
    void samplingParamsOverrideTheChainsEffortWithoutDuplicating() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.Medium()), Optional.of("MED")));
        var model = model(ModelCompat.NONE, map, Map.of("reasoning_effort", "high"));

        var raw = raw(model, Optional.of(new ThinkingLevel.Medium()));

        assertThat(MAPPER.readTree(raw).path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(occurrences(raw, "\"reasoning_effort\""))
            .as("覆盖 = 一个键；写成两份就不是 pi 的 Object.assign 语义了：%s", raw)
            .isEqualTo(1);
    }

    // ── zai 形状（pi :873-885）─────────────────────────────────────────

    /** 有级别 ⇒ {@code thinking:{type:"enabled", clear_thinking:false}} ＋ 映射后的 effort。 */
    @Test
    void zaiSendsTheEnabledThinkingShape() throws Exception {
        var body = body(model(compat(ThinkingFormat.ZAI, Boolean.TRUE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("thinking").path("clear_thinking").asBoolean()).isFalse();
        assertThat(body.path("thinking").has("clear_thinking")).isTrue();
        // 键缺席 ⇒ 回落级别名（pi :880-884 的 `=== undefined` 派）。
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("medium");
    }

    /**
     * 无级别 ⇒ {@code thinking:{type:"disabled"}} —— ⚠️ **没有** {@code clear_thinking} 键
     * （pi :878 的两个对象字面量形状不同），也没有 reasoning_effort。
     */
    @Test
    void zaiSendsTheDisabledThinkingShapeWithoutClearThinking() throws Exception {
        var body = body(model(compat(ThinkingFormat.ZAI, Boolean.TRUE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.empty());

        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("thinking").has("clear_thinking")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    /** 映射值赢过级别名（pi :880-884）。 */
    @Test
    void zaiMapsTheEffortThroughTheLevelMap() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.Medium()), Optional.of("MED")));
        var body = body(model(compat(ThinkingFormat.ZAI, Boolean.TRUE, Map.of(), Map.of()),
                map, Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("reasoning_effort").asText()).isEqualTo("MED");
    }

    /** pi :879 的门：{@code supportsReasoningEffort} 关 ⇒ thinking 照发、effort 不发。 */
    @Test
    void zaiOmitsTheEffortWhenTheGateIsClosed() throws Exception {
        var body = body(model(compat(ThinkingFormat.ZAI, Boolean.FALSE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    // ── qwen 形状（pi :886-893）────────────────────────────────────────

    /**
     * 顶层 {@code enable_thinking} 布尔**无门恒发**（pi :887 在 supportsReasoningEffort
     * 之外）：有级别 true、无级别 false —— 键都在。
     */
    @Test
    void qwenSendsTheTopLevelEnableThinkingFlag() throws Exception {
        var on = body(model(compat(ThinkingFormat.QWEN, Boolean.TRUE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.Medium()));
        assertThat(on.path("enable_thinking").asBoolean()).isTrue();
        assertThat(on.has("enable_thinking")).isTrue();

        var off = body(model(compat(ThinkingFormat.QWEN, Boolean.TRUE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.empty());
        assertThat(off.has("enable_thinking")).isTrue();
        assertThat(off.path("enable_thinking").asBoolean()).isFalse();
        assertThat(off.has("reasoning_effort")).isFalse();
    }

    /** effort 是 `??` 派（pi :889）：映射值 ?? 级别名；门关 ⇒ 不发。 */
    @Test
    void qwenSendsTheMappedOrFallbackEffort() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.Medium()), Optional.of("MED")));
        assertThat(body(model(compat(ThinkingFormat.QWEN, Boolean.TRUE, Map.of(), Map.of()),
                map, Map.of()), Optional.of(new ThinkingLevel.Medium()))
            .path("reasoning_effort").asText()).isEqualTo("MED");
        assertThat(body(model(compat(ThinkingFormat.QWEN, Boolean.TRUE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()), Optional.of(new ThinkingLevel.Low()))
            .path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(body(model(compat(ThinkingFormat.QWEN, Boolean.FALSE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()), Optional.of(new ThinkingLevel.Low()))
            .has("reasoning_effort")).isFalse();
    }

    // ── deepseek 形状（pi :921-930）────────────────────────────────────

    /** 有级别 ⇒ {@code thinking:{type:"enabled"}} —— ⚠️ 没有 clear_thinking（与 zai 的差别）。 */
    @Test
    void deepseekSendsEnabledThinkingWithoutClearThinking() throws Exception {
        var body = body(model(compat(ThinkingFormat.DEEPSEEK, Boolean.TRUE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("thinking").has("clear_thinking")).isFalse();
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("medium");
    }

    /**
     * 关闭支的 off 三态（pi :924 的 `map?.off !== null` 门）：缺席／字符串 ⇒ 发
     * {@code {type:"disabled"}}（⚠️ off 的字符串值**不上线**，只当门用 —— 与
     * openrouter/string-thinking 的 `?? "none"` 派不同）；显式 null ⇒ 整个键不发。
     */
    @Test
    void deepseekDisabledThinkingFollowsTheOffTriState() throws Exception {
        var offAbsent = body(model(compat(ThinkingFormat.DEEPSEEK, Boolean.TRUE,
                Map.of(), Map.of()), ThinkingLevelMap.empty(), Map.of()),
            Optional.empty());
        assertThat(offAbsent.path("thinking").path("type").asText()).isEqualTo("disabled");

        var offString = body(model(compat(ThinkingFormat.DEEPSEEK, Boolean.TRUE,
                Map.of(), Map.of()),
            ThinkingLevelMap.of(Map.of(ModelThinkingLevel.off(), Optional.of("none"))),
            Map.of()), Optional.empty());
        assertThat(offString.path("thinking").path("type").asText()).isEqualTo("disabled");

        var offNull = body(model(compat(ThinkingFormat.DEEPSEEK, Boolean.TRUE,
                Map.of(), Map.of()),
            ThinkingLevelMap.of(Map.of(ModelThinkingLevel.off(), Optional.empty())),
            Map.of()), Optional.empty());
        assertThat(offNull.has("thinking")).isFalse();
    }

    /** effort 是 `??` 派（pi :928，无 typeof 检查 —— {@code ?? 级别名} 保证恒为字符串）。 */
    @Test
    void deepseekSendsTheEffortWhenSupported() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("HIGH")));
        assertThat(body(model(compat(ThinkingFormat.DEEPSEEK, Boolean.TRUE, Map.of(), Map.of()),
                map, Map.of()), Optional.of(new ThinkingLevel.High()))
            .path("reasoning_effort").asText()).isEqualTo("HIGH");
        assertThat(body(model(compat(ThinkingFormat.DEEPSEEK, Boolean.FALSE, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()), Optional.of(new ThinkingLevel.Medium()))
            .has("reasoning_effort")).isFalse();
    }

    // ── ant-ling 形状（pi :941-945）────────────────────────────────────

    /**
     * ★ §2.4 第三行 —— 唯一**不回落级别名**的形状：只认 {@code map[level]} 的字符串值。
     * 且本臂不读 supportsReasoningEffort、也**从不**写 reasoning_effort（命中即终止，
     * §2.2 事实 2 —— 变异「ant-ling 臂顺带写 openai 的 effort」会红在这里）。
     */
    @Test
    void antLingSendsTheMappedEffortOnly() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.Medium()), Optional.of("MED")));
        var body = body(model(compat(ThinkingFormat.ANT_LING, null, Map.of(), Map.of()),
                map, Map.of()),
            Optional.of(new ThinkingLevel.Medium()));

        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("MED");
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    /** 键缺席／显式 null ⇒ **什么都不写**（openai 派在同样输入下会发级别名 —— 对照）。 */
    @Test
    void antLingWritesNothingWithoutAMappedString() throws Exception {
        var absent = body(model(compat(ThinkingFormat.ANT_LING, null, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.Medium()));
        assertThat(absent.has("reasoning")).isFalse();
        assertThat(absent.has("reasoning_effort")).isFalse();

        // 显式 null 的双重效果：夹取把 medium 踢到 high，而 map[high] 又缺席 ⇒ 仍不写。
        var explicitNull = body(model(compat(ThinkingFormat.ANT_LING, null, Map.of(), Map.of()),
                ThinkingLevelMap.of(Map.of(
                    ModelThinkingLevel.of(new ThinkingLevel.Medium()), Optional.empty())),
                Map.of()),
            Optional.of(new ThinkingLevel.Medium()));
        assertThat(explicitNull.has("reasoning")).isFalse();
    }

    /** 无级别 ⇒ 臂不点火（空表下与 pi 的链条等价；off 字符串的穿透角见 docs/32 B131）。 */
    @Test
    void antLingWithoutALevelWritesNothing() throws Exception {
        var body = body(model(compat(ThinkingFormat.ANT_LING, null, Map.of(), Map.of()),
                ThinkingLevelMap.empty(), Map.of()),
            Optional.empty());

        assertThat(body.has("reasoning")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 显式 compat（≙ 用户在 models.json 里写 compat）—— 其余位与 NONE 同值。 */
    private static ModelCompat compat(ThinkingFormat format, Boolean supportsEffort,
                                      Map<String, ChatTemplateKwargValue> kwargs,
                                      Map<String, ChatTemplateKwargValue> args) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, null, null, null, null, null,
            format, kwargs, args, supportsEffort);
    }

    private static ModelInfo model(ModelCompat compat, ThinkingLevelMap map,
                                   Map<String, Object> samplingParams) {
        return new ModelInfo(ModelId.of("test", "reasoner"), "reasoner",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING), 200_000, 100_000,
            false, PricingInfo.UNKNOWN, map, Map.of(), samplingParams, compat);
    }

    private static JsonNode body(ModelInfo model, Optional<ThinkingLevel> reasoning)
            throws Exception {
        return MAPPER.readTree(raw(model, reasoning));
    }

    private static String raw(ModelInfo model, Optional<ThinkingLevel> reasoning)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, "be brief",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of(), reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return server.body();
        }
    }

    private static int occurrences(String raw, String needle) {
        return raw.split(needle, -1).length - 1;
    }
}
