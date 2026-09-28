package com.pijava.ai.catalog;

import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.builtin.ModelData;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CatalogCompatRules} — pi 生成期目录规则的 Java 抄本（包 A7b，{@code docs/53}）。
 *
 * <p>这里的用例大多是**辨伪**用的：pi 的三条谓词在匹配方式上并不统一（正则 vs
 * {@code includes}、是否小写化），本类逐条照抄 ⇒ 夹具必须能抓住「顺手统一写法」这个变异。
 * 期望值同时经 §{@code docs/53 §7.1} 的 oracle（跑生成器 ＋ {@code getBuiltinModel}）与
 * {@code docs/53 §7.1b}（pi 自己的 {@code providers.test.ts}）核对过。</p>
 */
class CatalogCompatRulesTest {

    // ── anthropic 规则 ────────────────────────────────────────

    @Test
    void opus48AndFable5GetBothMidConvoFlagsAndAdaptiveThinking() {
        for (var id : new String[] {"claude-opus-4-8", "claude-fable-5"}) {
            var compat = CatalogCompatRules.anthropic("anthropic", id);

            assertThat(compat.supportsMidConvoSystemMessages()).as(id).isTrue();
            assertThat(compat.supportsMidConvoToolChanges()).as(id).isTrue();
            assertThat(compat.forceAdaptiveThinking()).as(id).isTrue();
        }
    }

    @Test
    void onlyTheOpusGenerationSuppressesTemperature() {
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-opus-4-8")
            .supportsTemperature()).isFalse();
        // fable-5 / sonnet-4-6 / haiku 都不在 isAnthropicTemperatureUnsupportedModel 的名单里。
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-fable-5")
            .supportsTemperature()).isTrue();
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-sonnet-4-6")
            .supportsTemperature()).isTrue();
    }

    @Test
    void sonnet46IsAdaptiveOnly() {
        var compat = CatalogCompatRules.anthropic("anthropic", "claude-sonnet-4-6");

        assertThat(compat.forceAdaptiveThinking()).isTrue();
        // ⚠️ sonnet-4-6 **不**在 mid-convo 的正则里 ⇒ 这一支是真正独立的判据。
        assertThat(compat.supportsMidConvoSystemMessages()).isNull();
        assertThat(compat.supportsMidConvoToolChanges()).isNull();
    }

    @Test
    void haikuCarriesNothingThisRepoConsults() {
        // pi 只给它 supportsStrictTools（java 不携带）⇒ 标注结果与「什么都没写」等价。
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-haiku-4-5-20251001"))
            .isEqualTo(ModelCompat.NONE);
    }

    @Test
    void theMidConvoRegexIsAnchored() {
        // ⚠️ 抓「把正则简化成 includes」的变异：`claude-opus-4-80` 不是 `claude-opus-4-8`。
        var compat = CatalogCompatRules.anthropic("anthropic", "claude-opus-4-80");

        assertThat(compat.supportsMidConvoSystemMessages()).isNull();
        // 而 adaptive 那条**是** includes ⇒ 它命中（pi 的两条谓词本来就不同宽）。
        assertThat(compat.forceAdaptiveThinking()).isTrue();
    }

    @Test
    void theThreePredicatesDisagreeOnCase() {
        // ⚠️ 这是三条谓词「匹配方式不统一」的**最尖**的一条：同一个大写 id 上
        // mid-convo（正则，不小写化）与 adaptive（includes，不小写化）都不命中，
        // 而 temperature（includes，**先**小写化）命中。
        var compat = CatalogCompatRules.anthropic("anthropic", "CLAUDE-OPUS-4-8");

        assertThat(compat.supportsMidConvoSystemMessages()).isNull();
        assertThat(compat.forceAdaptiveThinking()).isFalse();
        assertThat(compat.supportsTemperature()).isFalse();
    }

    @Test
    void theMidConvoFlagsAreGatedOnTheAnthropicProvider() {
        // pi generate-models.ts:1151 —— `provider === "anthropic"` 是那个分支的一部分。
        var compat = CatalogCompatRules.anthropic("openrouter", "claude-opus-4-8");

        assertThat(compat.supportsMidConvoSystemMessages()).isNull();
        assertThat(compat.supportsMidConvoToolChanges()).isNull();
        // 但 adaptive 与 temperature 两条**没有** provider 门 ⇒ 仍然命中。
        assertThat(compat.forceAdaptiveThinking()).isTrue();
        assertThat(compat.supportsTemperature()).isFalse();
    }

    @Test
    void theOptionalDotOneSuffixIsAccepted() {
        // pi 的正则里 `(?:[.-]1)?` 是可选的 ⇒ `claude-fable-5-1` 也命中。
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-fable-5-1")
            .supportsMidConvoSystemMessages()).isTrue();
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-mythos-5")
            .supportsMidConvoSystemMessages()).isTrue();
        assertThat(CatalogCompatRules.anthropic("anthropic", "claude-sonnet-5")
            .supportsMidConvoSystemMessages()).isNull();
    }

    // ── completions 规则 ──────────────────────────────────────

    @Test
    void onlyTheNativeDeepSeekProGetsMidConvoSystemMessages() {
        assertThat(CatalogCompatRules.completions("deepseek", "deepseek-v4-pro")
            .supportsMidConvoSystemMessages()).isTrue();
        // ⚠️ 逐 id 写死：同族的 flash 拿不到（pi 自己的 providers.test.ts 把这点钉成断言）。
        // 包 A-09 后 flash 会拿到 provider 级的 thinkingFormat（deepseek ⇒ DEEPSEEK），
        // 但 mid-convo 标志仍然缺席 —— 本用例钉的就是后者。
        assertThat(CatalogCompatRules.completions("deepseek", "deepseek-v4-flash")
            .supportsMidConvoSystemMessages()).isNull();
        assertThat(CatalogCompatRules.completions("deepseek", "deepseek-flash")
            .supportsMidConvoSystemMessages()).isNull();
        // 别的 provider 上的同名 id 也不算（`provider === "deepseek"` 是判据的一半）——
        // opencode 也不在 A-09 的格式常量表里（java 不携带该 provider）⇒ 仍是 NONE。
        assertThat(CatalogCompatRules.completions("opencode", "deepseek-v4-pro"))
            .isEqualTo(ModelCompat.NONE);
    }

    @Test
    void kimiK3IsNotCoveredHere() {
        // pi 对 Kimi K3 会给 supportsMidConvoToolAdditions —— 那是 moonshot/fireworks/opencode
        // 的规则，本仓的内置目录里没有这些模型 ⇒ 本抄本**不实现**它（docs/53 §4.4）。
        // 包 A-09：moonshotai 的 provider 级常量给的是 thinkingFormat（docs/58 §2.5(b)），
        // 与那个标志无关。
        assertThat(CatalogCompatRules.completions("moonshotai", "kimi-k3")
            .supportsMidConvoToolAdditions()).isNull();
        assertThat(CatalogCompatRules.completions("moonshotai", "kimi-k3")
            .thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
    }

    // ── 内置目录确实用上了这些规则 ─────────────────────────────

    @Test
    void theBuiltInCatalogCarriesTheAnthropicAnnotations() {
        var catalog = BuiltinCatalog.anthropicModels();
        var opus = catalog.find(ModelId.of("anthropic", "claude-opus-4-8")).orElseThrow();

        assertThat(opus.compat().supportsMidConvoSystemMessages()).isTrue();
        assertThat(opus.compat().supportsMidConvoToolChanges()).isTrue();
        assertThat(opus.compat().forceAdaptiveThinking()).isTrue();
        assertThat(opus.compat().supportsTemperature()).isFalse();
    }

    @Test
    void theBuiltInCatalogCarriesTheDeepSeekAnnotation() {
        var catalog = BuiltinCatalog.deepseekModels();
        var pro = catalog.find(ModelId.of("deepseek", "deepseek-v4-pro")).orElseThrow();
        var flash = catalog.find(ModelId.of("deepseek", "deepseek-v4-flash")).orElseThrow();

        assertThat(pro.compat().supportsMidConvoSystemMessages()).isTrue();
        // 包 A-09：两条现在都携带 provider 级的 thinkingFormat（pi deepseekCompat :2784）；
        // flash 仍然没有 mid-convo 标志。
        assertThat(pro.compat().thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
        assertThat(flash.compat().supportsMidConvoSystemMessages()).isNull();
        assertThat(flash.compat().thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
    }

    @Test
    void theOtherBuiltInProvidersCarryNoCompatAtAll() {
        // pi 的目录对 google 条目根本没有 compat 键；openai 的 gpt-5* 在 pi 走 responses 车道
        // 而本仓默认走 completions（A-04）⇒ 本包**不**标注它们（docs/53 §4.2）。
        assertThat(BuiltinCatalog.googleModels().listModels())
            .allMatch(m -> m.compat().equals(ModelCompat.NONE));
        assertThat(BuiltinCatalog.openaiModels().listModels())
            .allMatch(m -> m.compat().equals(ModelCompat.NONE));
        assertThat(BuiltinCatalog.mistralModels().listModels())
            .allMatch(m -> m.compat().equals(ModelCompat.NONE));
    }

    @Test
    void anAnnotatedModelSurvivesTheResolverUnchanged() {
        // 端到端的一格：目录标注 ＋ 解析层（A7a）⇒ 仍是那三个真值。
        var catalog = BuiltinCatalog.anthropicModels();
        var fable = catalog.find(ModelId.of("anthropic", "claude-fable-5")).orElseThrow();
        var compat = CompatResolver.forAnthropic(fable);

        assertThat(compat.supportsMidConvoSystemMessages()).isTrue();
        assertThat(compat.supportsMidConvoToolChanges()).isTrue();
        assertThat(compat.forceAdaptiveThinking()).isTrue();
    }

    @Test
    void aCatalogueAnnotatedModelIsStillDetectableByTheResolver() {
        var catalog = BuiltinCatalog.deepseekModels();
        var pro = catalog.find(ModelId.of("deepseek", "deepseek-v4-pro")).orElseThrow();
        var compat = CompatResolver.forCompletions(pro, "https://api.deepseek.com");

        assertThat(compat.supportsMidConvoSystemMessages()).isTrue();
        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isTrue();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_TOKENS);
        // 包 A-09：目录标注的形状活过解析层（探测同值 ⇒ 冗余但一致）。
        assertThat(compat.thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
    }

    // ── 包 A-09：per-provider 的 thinkingFormat 目录常量（docs/58 §2.5(b)）──

    @Test
    void theCatalogueAnnotatesTheFormatsDetectionCannotGive() {
        // 探测给不出这三条（探测会把它们误判成 openai）⇒ pi 的目录常量是唯一来源。
        assertThat(CatalogCompatRules.completions("moonshotai", "kimi-k2.5")
            .thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
        assertThat(CatalogCompatRules.completions("moonshotai-cn", "kimi-k2.5")
            .thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
        assertThat(CatalogCompatRules.completions("xiaomi", "mimo-v2-omni")
            .thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
        assertThat(CatalogCompatRules.completions("xiaomi-token-plan-cn", "mimo-v2-omni")
            .thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
        assertThat(CatalogCompatRules.completions("qwen-token-plan-cn", "qwen3-max")
            .thinkingFormat()).isEqualTo(ThinkingFormat.QWEN);
        // pi generate-models.ts:2537 —— qwen-token-plan 的 supportsReasoningEffort
        // 是明文常量 true（探测本来也给 true ⇒ 冗余，照抄 pi 写出来）。
        assertThat(CatalogCompatRules.completions("qwen-token-plan-cn", "qwen3-max")
            .supportsReasoningEffort()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void theCatalogueMirrorsPisRedundantFormatConstants() {
        // 这四条探测已给同值（冗余），但 pi 的目录照写 ⇒ 照抄（docs/58 §2.5(b)）。
        assertThat(CatalogCompatRules.completions("zai", "glm-5.2").thinkingFormat())
            .isEqualTo(ThinkingFormat.ZAI);
        assertThat(CatalogCompatRules.completions("zai-coding-cn", "glm-5.2").thinkingFormat())
            .isEqualTo(ThinkingFormat.ZAI);
        assertThat(CatalogCompatRules.completions("ant-ling", "ling-1t").thinkingFormat())
            .isEqualTo(ThinkingFormat.ANT_LING);
        assertThat(CatalogCompatRules.completions("deepseek", "deepseek-v4-pro").thinkingFormat())
            .isEqualTo(ThinkingFormat.DEEPSEEK);
        // ⚠️ zai 的 supportsReasoningEffort 在 pi 是**数据驱动**的
        // （generate-models.ts:1396：`thinkingLevelMap !== undefined`）—— java 的内置
        // 条目今天没有级别表 ⇒ 不标（null），由探测（isZai ⇒ false）兜底 ⇒ 与 pi 的
        // 有效值 false 等价（数据漂移登记见 docs/58 §9）。
        assertThat(CatalogCompatRules.completions("zai", "glm-5.2").supportsReasoningEffort())
            .isNull();
        // 常量表之外的 provider 仍是 NONE（minimax 是 anthropic 车道、ollama 探测给 openai）。
        assertThat(CatalogCompatRules.completions("ollama", "llama3.2"))
            .isEqualTo(ModelCompat.NONE);
    }

    @Test
    void theBuiltInModelDataCarriesTheCatalogueFormats() {
        // ModelData.model(...) 现在走 CatalogCompatRules.completions（与
        // BuiltinCatalog.deepseekModel 的先例同形）⇒ 内置条目真的携带标注。
        var kimi = ModelData.moonshotAiModels()
            .find(ModelId.of("moonshotai", "kimi-k2.5")).orElseThrow();
        assertThat(kimi.compat().thinkingFormat()).isEqualTo(ThinkingFormat.DEEPSEEK);
        var glm = ModelData.zaiModels().find(ModelId.of("zai", "glm-4.7")).orElseThrow();
        assertThat(glm.compat().thinkingFormat()).isEqualTo(ThinkingFormat.ZAI);
        var qwen = ModelData.qwenTokenPlanCnModels()
            .find(ModelId.of("qwen-token-plan-cn", "qwen3-max")).orElseThrow();
        assertThat(qwen.compat().thinkingFormat()).isEqualTo(ThinkingFormat.QWEN);
        assertThat(qwen.compat().supportsReasoningEffort()).isTrue();
        var ling = ModelData.antLingModels()
            .find(ModelId.of("ant-ling", "ling-1t")).orElseThrow();
        assertThat(ling.compat().thinkingFormat()).isEqualTo(ThinkingFormat.ANT_LING);
        // 表外 provider 不受影响。
        assertThat(ModelData.ollamaModels().find(ModelId.of("ollama", "llama3.2"))
            .orElseThrow().compat()).isEqualTo(ModelCompat.NONE);
        assertThat(ModelData.miniMaxModels().find(ModelId.of("minimax", "MiniMax-M2.5"))
            .orElseThrow().compat()).isEqualTo(ModelCompat.NONE);
    }
}
