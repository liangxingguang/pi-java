package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openai.models.responses.ResponseCreateParams;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelCompatFactories;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>批 8 / B240</b>：Sign in with ChatGPT 订阅 token 的请求时特判
 * （pi {@code api/openai-responses.ts:40-46}、{@code :329-345}）。
 *
 * <p>两层夹具：① 分类器 {@code isChatGptSignIn} 真值表（直调包可见静态，桩 HTTP 的
 * baseUrl 必然不是官方 URL，端到端 wire 无法构造真分支）；② {@code buildParams} 的
 * omit 门——直接断言出参 SDK 对象的 Optional 访问器（缺席即 {@code JsonMissing}，
 * SDK 序列化时整个键不落线，与 ResponsesMaxOutputTokensWireTest 同一事实链）。</p>
 *
 * <p>锚点事实（原 docs/17 §2）：裁剪集合恰好四个键，{@code prompt_cache_key} <b>不裁</b>；
 * 门只在 openai 车道（azure 副本零命中）；严格相等不归一。</p>
 */
class ResponsesChatGptSignInWireTest {

    private static final String OFFICIAL = "https://api.openai.com/v1";
    private static final String SID = "0192ab63-chatgpt-sign-in-sid";

    // ── ① 分类器真值表（pi openai-responses.ts:40-46） ─────────────────

    @Test
    void nonSkKeyOnOfficialOpenAiUrlIsChatGptSignIn() {
        assertThat(OpenAIResponsesApi.isChatGptSignIn(
            "openai", OFFICIAL, "chatgpt-access-token-abc")).isTrue();
    }

    @Test
    void skPrefixedKeyIsARegularApiKey() {
        assertThat(OpenAIResponsesApi.isChatGptSignIn(
            "openai", OFFICIAL, "sk-abcdef123456")).isFalse();
    }

    @Test
    void missingOrBlankKeyIsNotChatGptSignIn() {
        assertThat(OpenAIResponsesApi.isChatGptSignIn("openai", OFFICIAL, null)).isFalse();
        assertThat(OpenAIResponsesApi.isChatGptSignIn("openai", OFFICIAL, "  ")).isFalse();
    }

    @Test
    void anotherProviderNeverClassifiesEvenWithANonSkKey() {
        // xai 车道复用同一个适配器类（XaiProvider → OpenAIResponsesApi）。
        assertThat(OpenAIResponsesApi.isChatGptSignIn(
            "xai", OFFICIAL, "chatgpt-access-token-abc")).isFalse();
    }

    @Test
    void aCustomBaseUrlNeverClassifies() {
        assertThat(OpenAIResponsesApi.isChatGptSignIn(
            "openai", "https://my-gateway.example.com/v1", "chatgpt-access-token-abc"))
            .isFalse();
    }

    @Test
    void theOfficialUrlMatchIsExactNoNormalization() {
        // pi 是 === 严格相等：尾斜杠/大小写都不命中，钉死，别「顺手」归一。
        assertThat(OpenAIResponsesApi.isChatGptSignIn(
            "openai", OFFICIAL + "/", "chatgpt-access-token-abc")).isFalse();
    }

    // ── ② buildParams 的 omit 门（pi :334-345） ────────────────────────

    /** omit=true（订阅 token）＋ long 保留期：key 在，retention/options／上限／温度全裁。 */
    @Test
    void omitStripsTheFourRejectedFieldsButKeepsTheCacheKey() {
        var params = params(true, ModelCompat.NONE);

        assertThat(params.promptCacheKey())
            .as("pi :334 —— prompt_cache_key 无 omit 门，订阅 token 照发")
            .contains(SID);
        assertThat(params.promptCacheRetention())
            .as("pi :335").isEmpty();
        assertThat(params.promptCacheOptions())
            .as("pi :336").isEmpty();
        assertThat(params.maxOutputTokens())
            .as("pi :340").isEmpty();
        assertThat(params.temperature())
            .as("pi :344").isEmpty();
        // 其余请求骨架不受影响。
        assertThat(params.model().orElseThrow().asString()).isEqualTo("gpt-5");
        assertThat(params.store()).contains(false);
        assertThat(params.input()).isPresent();
    }

    /** omit=false 对照组：四个键原样在。 */
    @Test
    void controlLaneSendsAllFourFields() {
        var params = params(false, ModelCompat.NONE);

        assertThat(params.promptCacheKey()).contains(SID);
        assertThat(params.promptCacheRetention().orElseThrow().asString()).isEqualTo("24h");
        assertThat(params.maxOutputTokens()).contains(3_000L);
        assertThat(params.temperature()).contains(0.7);
        assertThat(params.promptCacheOptions()).isEmpty();
    }

    /** 显式 cache 模式 compat：整个 options 支也在门后（pi :336 罩住两支）。 */
    @Test
    void explicitCacheOptionsAreStrippedToo() {
        var params = params(true, explicitLongCacheCompat());

        assertThat(params.promptCacheKey()).contains(SID);
        assertThat(params.promptCacheOptions())
            .as("explicit 支同被 omit 门裁掉，不落 {ttl:30m}").isEmpty();
        assertThat(params.promptCacheRetention()).isEmpty();
    }

    // ── 夹具 ───────────────────────────────────────────────────────────

    private static ResponseCreateParams params(boolean omit, ModelCompat compat) {
        var model = new ModelInfo(ModelId.of("openai", "gpt-5"), "gpt-5",
            Set.of(ModelCapability.TEXT), 200_000, 32_000, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
        var request = new StreamRequest(model, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 3_000, 0.7);
        var ropts = new ResponsesOptions(null, null, null,
            ResponsesOptions.CacheRetention.LONG, SID, Map.of());
        return ResponsesMessageConverter.buildParams(request, ropts,
            "gpt-5", "openai-responses", CompatResolver.forResponses(model, false),
            Map.of(), omit, Map.of());
    }

    /**
     * supportsLongCacheRetention=true（第 15 位）＋ supportsExplicitPromptCacheMode=true
     * （第 31 位），其余位缺席——形参表照 ResponsesPromptCacheWireTest 的同形夹具。
     */
    private static ModelCompat explicitLongCacheCompat() {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, Boolean.TRUE, null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, null, null, false, null,
            null, Boolean.TRUE,
            null, null, null, null, null, null, null, null,
            null);
    }
}
