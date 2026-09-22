package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步2：{@link ModelThinkingLevels} 的两个枢轴函数
 * （pi {@code models.ts:924-955}）。
 *
 * <p><b>夹具骨架镜像 pi 自己的测试</b>（{@code packages/ai/test/max-thinking.test.ts}）
 * —— 同一份输入、两套期望，是<b>跨实现 oracle</b>，比自造夹具强一档
 * （包B84 撞出的口径，{@code docs/45 §10}）。三个用例逐个对应 pi 的三条：</p>
 *
 * <ol>
 *   <li>pi「is opt-in for ordinary reasoning models」—— 普通 reasoning 模型
 *       {@code off..high}，{@code max} 夹到 {@code high}</li>
 *   <li>pi「supports a hole between high and max」—— {@code {xhigh:null, max:"max"}}
 *       造出<b>空洞</b>，且 {@code xhigh} 夹<b>向上</b>到 {@code max}</li>
 *   <li>pi 的 {@code reasoning:false} ⇒ 只有 {@code off}</li>
 * </ol>
 *
 * <p>⚠️ pi 那三条里还有一条（{@code gpt-5.6-*} 的 {@code xhigh}/{@code max}）依赖
 * <b>生成目录数据</b>，而 pi 的数据不在仓库里（{@code providers/data/} 被 gitignore）
 * ⇒ 本夹具用<b>手写 map</b> 表达同一形状。</p>
 */
class ModelThinkingLevelsTest {

    /** 带 {@code THINKING} 能力的模型（≙ pi 的 {@code reasoning: true}）。 */
    private static ModelInfo model(ThinkingLevelMap map) {
        return new ModelInfo(
            ModelId.of("test", "m"), "M",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            128_000, 4096, false, PricingInfo.UNKNOWN, map);
    }

    /** 不带 {@code THINKING} 能力（≙ pi 的 {@code reasoning: false}）。 */
    private static ModelInfo nonReasoningModel() {
        return new ModelInfo(
            ModelId.of("test", "m"), "M",
            Set.of(ModelCapability.TEXT),
            128_000, 4096, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty());
    }

    private static ModelThinkingLevel lvl(ThinkingLevel level) {
        return ModelThinkingLevel.of(level);
    }

    // ── pi max-thinking.test.ts 用例 1 ──────────────────────────────────

    /** 普通 reasoning 模型：{@code off..high} 默认支持，{@code xhigh}/{@code max} **opt-in**。 */
    @Test
    void ordinaryReasoningModelSupportsOffThroughHighOnly() {
        var levels = ModelThinkingLevels.supported(model(ThinkingLevelMap.empty()));

        assertThat(levels).extracting(ModelThinkingLevel::label)
            .containsExactly("off", "minimal", "low", "medium", "high");
    }

    /** 未支持的级别**向上**夹 —— {@code max} ⇒ {@code high}（pi 的 clamp 先向上）。 */
    @Test
    void unsupportedMaxClampsDownToHigh() {
        assertThat(ModelThinkingLevels.clamp(model(ThinkingLevelMap.empty()), lvl(new ThinkingLevel.Max())))
            .isEqualTo(lvl(new ThinkingLevel.High()));
    }

    // ── pi max-thinking.test.ts 用例 2 ──────────────────────────────────

    /** {@code {xhigh:null, max:"max"}}：{@code xhigh} 被排除 ⇒ **空洞**，{@code max} 进来。 */
    @Test
    void explicitNullMakesAHoleAndMaxOptsIn() {
        var map = ThinkingLevelMap.of(Map.of(
            lvl(new ThinkingLevel.XHigh()), Optional.empty(),
            lvl(new ThinkingLevel.Max()), Optional.of("max")));

        var levels = ModelThinkingLevels.supported(model(map));

        assertThat(levels).extracting(ModelThinkingLevel::label)
            .containsExactly("off", "minimal", "low", "medium", "high", "max");
    }

    /** 空洞之上仍有可用级 ⇒ 夹**向上**跨过空洞（{@code xhigh} ⇒ {@code max}）。 */
    @Test
    void clampSkipsTheHoleUpward() {
        var map = ThinkingLevelMap.of(Map.of(
            lvl(new ThinkingLevel.XHigh()), Optional.empty(),
            lvl(new ThinkingLevel.Max()), Optional.of("max")));

        assertThat(ModelThinkingLevels.clamp(model(map), lvl(new ThinkingLevel.XHigh())))
            .isEqualTo(lvl(new ThinkingLevel.Max()));
    }

    // ── reasoning:false ─────────────────────────────────────────────────

    /** 非 reasoning 模型：**只有** {@code off}（pi 的 `if (!model.reasoning) return ["off"]`）。 */
    @Test
    void nonReasoningModelSupportsOnlyOff() {
        assertThat(ModelThinkingLevels.supported(nonReasoningModel()))
            .extracting(ModelThinkingLevel::label)
            .containsExactly("off");
    }

    /** 非 reasoning 模型上请求任何级别都夹回 {@code off}。 */
    @Test
    void nonReasoningModelClampsEverythingToOff() {
        assertThat(ModelThinkingLevels.clamp(nonReasoningModel(), lvl(new ThinkingLevel.High())))
            .isEqualTo(ModelThinkingLevel.off());
        assertThat(ModelThinkingLevels.clamp(nonReasoningModel(), ModelThinkingLevel.off()))
            .isEqualTo(ModelThinkingLevel.off());
    }

    // ── 边界 ────────────────────────────────────────────────────────────

    /** 已支持的级别**原样返回**（不夹）。 */
    @Test
    void supportedLevelPassesThroughUnchanged() {
        assertThat(ModelThinkingLevels.clamp(model(ThinkingLevelMap.empty()), lvl(new ThinkingLevel.Medium())))
            .isEqualTo(lvl(new ThinkingLevel.Medium()));
    }

    /**
     * {@code off} 被**显式**标为不支持时，它在可用集里消失；其余级别仍在。
     *
     * <p>对齐 pi 的 {@code mapped === null ⇒ false} 分支 —— 它<b>没有</b>对 {@code off}
     * 的例外（Kimi K2.7 Code 那类「只能省略该参数」的模型）。</p>
     */
    @Test
    void explicitlyUnsupportedOffLeavesTheLadder() {
        var map = ThinkingLevelMap.of(Map.of(ModelThinkingLevel.off(), Optional.empty()));

        assertThat(ModelThinkingLevels.supported(model(map)))
            .extracting(ModelThinkingLevel::label)
            .containsExactly("minimal", "low", "medium", "high");
    }

    /** 整条梯子都被显式标空 ⇒ 可用集为空，{@code clamp} 回落 {@code off}（pi 的 `?? "off"`）。 */
    @Test
    void fullyUnsupportedLadderFallsBackToOff() {
        var entries = new java.util.HashMap<ModelThinkingLevel, Optional<String>>();
        for (var level : ModelThinkingLevel.extended()) {
            entries.put(level, Optional.empty());
        }
        var m = model(ThinkingLevelMap.of(entries));

        assertThat(ModelThinkingLevels.supported(m)).isEmpty();
        assertThat(ModelThinkingLevels.clamp(m, lvl(new ThinkingLevel.High())))
            .isEqualTo(ModelThinkingLevel.off());
    }
}
