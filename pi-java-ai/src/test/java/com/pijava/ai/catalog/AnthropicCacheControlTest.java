package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-01 步 A1a 的夹具：{@link CompatResolver#anthropicCacheControl} 与
 * {@link CacheRetention#parse}。
 *
 * <p><b>oracle</b>：{@code 原 docs/54 §7.1} 的 pi 探针 P1–P6/P18 —— 那是<b>跑</b>
 * {@code streamSimple} ＋ {@code onPayload} 抓到的真实出站体，不是读源码推的。每条用例的
 * 注释标出对应的 P 编号。</p>
 *
 * <p>⚠️ 本夹具钉的是**解析结果**；「结果怎么落线」由 {@code AnthropicCacheControlWireTest}
 * （A1b）钉。分两份的理由与 A7a/A7c 同：解析层的红点是值，wire 层的红点是位置。</p>
 */
class AnthropicCacheControlTest {

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ModelId.of("test-anthropic", "claude-opus-4-8"), "Claude Opus 4.8",
            Set.of(ModelCapability.TEXT), 200_000, 32_000, true, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    /** 内置 anthropic 目录里的真模型（经 {@code forAnthropic} 解析出两个 {@code ?? true}）。 */
    private static ModelInfo builtIn(String id) {
        return BuiltinCatalog.anthropicModels()
            .find(ModelId.of("anthropic", id)).orElseThrow();
    }

    /** 跑一次解析：模型 ＋ 选项 ＋ 环境变量原文。 */
    private static Optional<CacheBreakpointSpec> resolve(ModelInfo model,
                                                         CacheRetention option,
                                                         String env) {
        return CompatResolver.anthropicCacheControl(model, Optional.ofNullable(option), env);
    }

    // ── 三态（P1/P2/P3）──────────────────────────────────────────

    @Test
    void defaultShortSendsABreakpointWithoutTtl() {
        // P1：选项与环境变量都缺席 ⇒ retention 落 "short" ⇒ 有断点、无 ttl。
        var spec = resolve(model(ModelCompat.NONE), null, null).orElseThrow();

        assertThat(spec.oneHourTtl()).isFalse();
    }

    @Test
    void noneSendsNoBreakpoint() {
        // P2：`cacheRetention:"none"` ⇒ Optional.empty（三处落点全部消失）。
        assertThat(resolve(model(ModelCompat.NONE), CacheRetention.NONE, null)).isEmpty();
    }

    @Test
    void noneBeatsTheEnvVarAsWell() {
        // 选项在场就压过环境变量 —— pi 的 `if (cacheRetention) return cacheRetention`。
        assertThat(resolve(model(ModelCompat.NONE), CacheRetention.NONE, "long")).isEmpty();
    }

    @Test
    void longAddsTheOneHourTtl() {
        // P3：long ⇒ ttl:"1h"。
        assertThat(resolve(model(ModelCompat.NONE), CacheRetention.LONG, null).orElseThrow()
            .oneHourTtl()).isTrue();
    }

    @Test
    void shortBeatsALongEnvVar() {
        // 反向配对：选项显式 short 时，环境变量的 "long" 不生效（解析顺序，不是取值大小）。
        assertThat(resolve(model(ModelCompat.NONE), CacheRetention.SHORT, "long").orElseThrow()
            .oneHourTtl()).isFalse();
    }

    // ── 环境变量只认字面 "long"（P4/P4b）────────────────────────

    @Test
    void theEnvVarMatchesTheExactLiteral() {
        // P4：PI_CACHE_RETENTION=long ⇒ ttl。
        assertThat(resolve(model(ModelCompat.NONE), null, "long").orElseThrow()
            .oneHourTtl()).isTrue();
    }

    @Test
    void theEnvVarIsNotTrimmedNotCaseFoldedAndNotInterpreted() {
        // P4b ＋ pi 的 `=== "long"` 是**严格相等**：不 trim、不小写化、不把 "1h" 当同义。
        // 七个变体逐个钉，任一处放宽都会红（断点是「无 ttl」而不是「缺席」—— 两者都断，
        // 免得把「断点也消失」的缺陷放过去）。
        for (var env : new String[] {"long ", " long", "Long", "LONG", "1h", "lt", "long\n"}) {
            var spec = resolve(model(ModelCompat.NONE), null, env);

            assertThat(spec).as("env=%s", env.replace("\n", "\\n")).isPresent();
            assertThat(spec.orElseThrow().oneHourTtl())
                .as("env=%s", env.replace("\n", "\\n")).isFalse();
        }
    }

    @Test
    void aBlankEnvVarFallsBackToShort() {
        assertThat(resolve(model(ModelCompat.NONE), null, "").orElseThrow()
            .oneHourTtl()).isFalse();
    }

    // ── supportsLongCacheRetention 只控 ttl（P6）─────────────────

    @Test
    void supportsLongCacheRetentionFalseDropsOnlyTheTtl() {
        // P6：门为假时断点**仍在**（不是消失），只是没有 ttl。这条与下一条构成配对 ——
        // 只断「ttl 缺席」而不断「断点在场」的话，把门写成「断点也消失」也会绿。
        var compat = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, Boolean.FALSE, null);

        var spec = resolve(model(compat), CacheRetention.LONG, null).orElseThrow();

        assertThat(spec.oneHourTtl()).isFalse();
    }

    @Test
    void supportsLongCacheRetentionTrueIsTheDefaultUnderLong() {
        // 配对：同一个 long 请求，门缺席（≙ pi 的 ?? true）时 ttl 在场。
        assertThat(resolve(model(ModelCompat.NONE), CacheRetention.LONG, null).orElseThrow()
            .oneHourTtl()).isTrue();
    }

    // ── 缺省方向 ──────────────────────────────────────────────

    @Test
    void aModelWithoutCompatStillGetsABreakpoint() {
        // 安全方向：未声明 compat 的模型（models.json 没写、或 ModelInfo.minimal）
        // **照发**断点 —— 与 supportsTemperature 的 `?? true` 同向。写成假会让所有
        // 自定义模型静默失去缓存。
        assertThat(resolve(model(ModelCompat.NONE), null, null)).isPresent();
    }

    @Test
    void aNullModelStillResolvesTheDefault() {
        // StreamRequest.model 允许为空（原 docs/53 §4.1 的 base(...) 容忍 null）。
        assertThat(CompatResolver.anthropicCacheControl(
            null, Optional.empty(), null).orElseThrow().oneHourTtl()).isFalse();
    }

    // ── 内置目录上的可达性（真模型，不是手搓 compat）─────────────

    @Test
    void builtInAnthropicModelsResolveBothCacheGatesToTrue() {
        // 包 A-01 的 forAnthropic 补了两个 `?? true`。用**真**模型钉，避免手搓 compat
        // 把「解析层到底有没有填」这件事遮住（A7c 的教训，原 docs/53 §4.5）。
        for (var id : new String[] {"claude-opus-4-8", "claude-sonnet-4-6",
                                    "claude-haiku-4-5-20251001"}) {
            var compat = CompatResolver.forAnthropic(builtIn(id));

            assertThat(compat.supportsLongCacheRetention()).as(id).isTrue();
            assertThat(compat.supportsCacheControlOnTools()).as(id).isTrue();
        }
    }

    @Test
    void anExplicitOverrideBeatsTheAnthropicCacheDefaults() {
        // 覆盖源优先（pi 的 `explicit ?? detected`）：models.json 写了 false 就是 false。
        var compat = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, Boolean.FALSE, Boolean.FALSE);

        var resolved = CompatResolver.forAnthropic(model(compat));

        assertThat(resolved.supportsLongCacheRetention()).isFalse();
        assertThat(resolved.supportsCacheControlOnTools()).isFalse();
    }

    @Test
    void longOnABuiltInModelCarriesTheTtl() {
        // 端到端：真模型 ＋ long ⇒ ttl 在场（证明两个门在可达路径上都通向"允许"）。
        var spec = CompatResolver.anthropicCacheControl(
            builtIn("claude-opus-4-8"), Optional.of(CacheRetention.LONG), null).orElseThrow();

        assertThat(spec.oneHourTtl()).isTrue();
    }

    // ── CacheRetention.parse ────────────────────────────────────

    @Test
    void parseAcceptsTheThreeWireValues() {
        assertThat(CacheRetention.parse("none")).contains(CacheRetention.NONE);
        assertThat(CacheRetention.parse("short")).contains(CacheRetention.SHORT);
        assertThat(CacheRetention.parse("long")).contains(CacheRetention.LONG);
    }

    @Test
    void parseIsCaseInsensitiveForTheOptionSideOnly() {
        // 选项侧的宽松是 java 的方言（pi 的 TS 类型让它不可达）；**环境变量侧**没有这层
        // 宽松 —— 那条走字符串严格比较，见 theEnvVarIsNotTrimmedNotCaseFoldedAndNotInterpreted。
        assertThat(CacheRetention.parse("LONG")).contains(CacheRetention.LONG);
        assertThat(CacheRetention.parse("Long")).contains(CacheRetention.LONG);
    }

    @Test
    void parseRejectsUnknownValuesInsteadOfCollapsingToShort() {
        // ⚠️ 这是与 ResponsesOptions.CacheRetention.parse 的**刻意分歧**：非法值当作缺席，
        // 好让 PI_CACHE_RETENTION 仍能生效。若塌成 SHORT，"Long" 会把环境变量静默屏蔽。
        for (var raw : new String[] {null, "", "   ", "medium", "1h", "longer"}) {
            assertThat(CacheRetention.parse(raw)).as("raw=%s", raw).isEmpty();
        }
    }

    @Test
    void wireNamesRoundTripThroughParse() {
        for (var value : CacheRetention.values()) {
            assertThat(CacheRetention.parse(value.wireName())).contains(value);
        }
    }
}
