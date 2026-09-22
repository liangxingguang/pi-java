package com.pijava.ai.thinking;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步1：<b>形状重塑</b>的语义夹具。
 *
 * <p>这一批断言钉的是 {@link ThinkingLevelMap} 的 <b>三态</b>（pi 的
 * {@code Partial<Record<ModelThinkingLevel, string | null>>}，{@code types.ts:86}）
 * 与 {@link ModelThinkingLevel#extended()} 的刻度 —— 它们是
 * {@code getSupportedThinkingLevels} / {@code clampThinkingLevel}（步2）的<b>判据基础</b>。</p>
 *
 * <p>⚠️ <b>为什么这些断言在改动前必然红</b>：旧 {@code ThinkingLevelMap} 是
 * {@code Map<ThinkingLevel, ThinkingConfig>} ＋ 一个 {@code forLevel} ——
 * <b>没有</b> {@code hasEntry}／{@code explicitlyUnsupported}／{@code supportsExplicitOff}
 * 这些读点（pi 的三态在旧形状里<b>结构上不可表达</b>），{@code ThinkingLevel} 也<b>没有</b>
 * {@code Max}。所以本夹具在旧代码上是<b>编译错误</b>，即红。这与包B84 §10-5 记的
 * 「探针红有两种形态」同族：clean build 上是编译错误。</p>
 *
 * <p>正因先红只能以编译错误呈现，本步的**变异探针**（§5）才是真正的牙齿证明。</p>
 */
class ThinkingLevelMapTest {

    private static final ModelThinkingLevel OFF = ModelThinkingLevel.off();
    private static final ModelThinkingLevel LOW = ModelThinkingLevel.of(new ThinkingLevel.Low());
    private static final ModelThinkingLevel XHIGH = ModelThinkingLevel.of(new ThinkingLevel.XHigh());
    private static final ModelThinkingLevel MAX = ModelThinkingLevel.of(new ThinkingLevel.Max());

    // ── 三态 ────────────────────────────────────────────────────────────

    /** pi 的 `undefined`（键缺席）：既不是「不支持」，也不是「有映射」。 */
    @Test
    void absentKeyIsNeitherUnsupportedNorMapped() {
        var map = ThinkingLevelMap.empty();

        assertThat(map.hasEntry(LOW)).isFalse();
        assertThat(map.explicitlyUnsupported(LOW)).isFalse();
        assertThat(map.mapped(LOW)).isEmpty();
    }

    /** pi 的 `null`（键在场、值为空）：**显式不支持**。 */
    @Test
    void presentButEmptyValueIsExplicitlyUnsupported() {
        var map = ThinkingLevelMap.of(Map.of(LOW, Optional.empty()));

        assertThat(map.hasEntry(LOW)).isTrue();
        assertThat(map.explicitlyUnsupported(LOW)).isTrue();
        assertThat(map.mapped(LOW)).isEmpty();
    }

    /** pi 的字符串值：该级别的 provider effort 名。 */
    @Test
    void presentStringValueCarriesTheProviderEffortName() {
        var map = ThinkingLevelMap.of(Map.of(LOW, Optional.of("MINIMAL")));

        assertThat(map.hasEntry(LOW)).isTrue();
        assertThat(map.explicitlyUnsupported(LOW)).isFalse();
        assertThat(map.mapped(LOW)).contains("MINIMAL");
    }

    /** 三个状态**互不塌缩** —— 缺席与显式 null 在 `mapped` 上同值，靠 `explicitlyUnsupported` 分开。 */
    @Test
    void absentAndExplicitNullDifferOnlyByExplicitlyUnsupported() {
        var absent = ThinkingLevelMap.empty();
        var explicitNull = ThinkingLevelMap.of(Map.of(LOW, Optional.empty()));

        assertThat(absent.mapped(LOW)).isEqualTo(explicitNull.mapped(LOW));
        assertThat(absent.explicitlyUnsupported(LOW)).isFalse();
        assertThat(explicitNull.explicitlyUnsupported(LOW)).isTrue();
    }

    // ── supportsExplicitOff（pi anthropic-messages.ts:1179）───────────────

    /** ⚠️ **键缺席也算「支持 off」** —— pi 的 `undefined !== null` 为真。 */
    @Test
    void absentOffKeyStillSupportsExplicitOff() {
        assertThat(ThinkingLevelMap.empty().supportsExplicitOff()).isTrue();
    }

    /** 只有**显式** `off: null` 才是不支持（Kimi K2.7 Code 那类）。 */
    @Test
    void explicitNullOffMeansExplicitOffIsUnsupported() {
        var map = ThinkingLevelMap.of(Map.of(OFF, Optional.empty()));

        assertThat(map.supportsExplicitOff()).isFalse();
    }

    /** `off` 有**字符串**映射（如 {@code "none"}）⇒ 仍然支持显式关闭。 */
    @Test
    void stringMappedOffStillSupportsExplicitOff() {
        var map = ThinkingLevelMap.of(Map.of(OFF, Optional.of("none")));

        assertThat(map.supportsExplicitOff()).isTrue();
        assertThat(map.mapped(OFF)).contains("none");
    }

    // ── 刻度（pi models.ts:922）─────────────────────────────────────────

    /** pi 的 `EXTENDED_THINKING_LEVELS` —— **含 off**，共 7 项，顺序逐字。 */
    @Test
    void extendedLadderIsOffThenSixLevelsInPiOrder() {
        assertThat(ModelThinkingLevel.extended())
            .extracting(ModelThinkingLevel::label)
            .containsExactly("off", "minimal", "low", "medium", "high", "xhigh", "max");
    }

    /** `ThinkingLevel.ordered()` 是 6 级（**不含 off**），且 `"max"` 不再并进 `xhigh`。 */
    @Test
    void thinkingLevelLadderHasSixDistinctLevelsIncludingMax() {
        assertThat(ThinkingLevel.ordered())
            .extracting(ThinkingLevel::label)
            .containsExactly("minimal", "low", "medium", "high", "xhigh", "max");
    }

    // ── 字面量 ──────────────────────────────────────────────────────────

    /** pi 字面量往返：`"max"` ⇒ {@code Max}（**不是** {@code XHigh}）。 */
    @Test
    void maxLiteralParsesToMaxNotXHigh() {
        assertThat(ThinkingLevel.parse("max")).contains(new ThinkingLevel.Max());
        assertThat(ThinkingLevel.parse("xhigh")).contains(new ThinkingLevel.XHigh());
    }

    /** 未知值与 `"off"` 都不属于 `ThinkingLevel`（`off` 是 `ModelThinkingLevel` 的事）。 */
    @Test
    void unknownAndOffAreNotThinkingLevels() {
        assertThat(ThinkingLevel.parse("off")).isEmpty();
        assertThat(ThinkingLevel.parse("bogus")).isEmpty();
        assertThat(ThinkingLevel.parse(null)).isEmpty();
    }

    /** `ModelThinkingLevel.parse` 认 `"off"`，并**忽略大小写**（pi 的 CLI 输入形态）。 */
    @Test
    void modelThinkingLevelParsesOffAndIsCaseInsensitive() {
        assertThat(ModelThinkingLevel.parse("off")).contains(OFF);
        assertThat(ModelThinkingLevel.parse("HIGH")).contains(ModelThinkingLevel.of(new ThinkingLevel.High()));
        assertThat(ModelThinkingLevel.parse("bogus")).isEmpty();
    }

    /** `label()` 与 pi 的字面量逐字一致（`off` / `xhigh` / `max` 三个最易错）。 */
    @Test
    void labelsMatchPiLiteralsVerbatim() {
        assertThat(OFF.label()).isEqualTo("off");
        assertThat(XHIGH.label()).isEqualTo("xhigh");
        assertThat(MAX.label()).isEqualTo("max");
        assertThat(ModelThinkingLevel.of(new ThinkingLevel.Minimal()).label()).isEqualTo("minimal");
    }

    /** 构造器防御性拷贝：外部 map 的后续改动不影响本记录。 */
    @Test
    void entriesAreDefensivelyCopied() {
        var source = new java.util.HashMap<ModelThinkingLevel, Optional<String>>();
        source.put(LOW, Optional.of("low"));
        var map = ThinkingLevelMap.of(source);

        source.clear();

        assertThat(map.mapped(LOW)).contains("low");
    }
}
