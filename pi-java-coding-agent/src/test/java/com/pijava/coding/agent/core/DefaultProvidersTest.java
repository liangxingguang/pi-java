package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 review fix: settings {@code defaultProvider} now drives the provider
 * when {@code --provider} is absent.
 *
 * <p>Phase 6: {@code defaultProviders()} loads 16 built-ins and runs
 * ServiceLoader discovery.</p>
 *
 * <p>P1（docs/31 §8.31）：{@code defaultProvider} 自此只决定**起手模型**与
 * **未注册 provider 的回退**，不再决定每个请求的适配器 —— 适配器跟
 * {@code model.provider()} 走。</p>
 */
class DefaultProvidersTest {

    @Test
    void resolveProviderNamePrefersCliThenSettingsThenDefault() {
        var cli = ArgsParser.parse(new String[] {"--provider", "openai"});
        assertThat(DefaultProviders.resolveProviderName(cli, "deepseek"))
            .isEqualTo("openai");

        var noCli = ArgsParser.parse(new String[] {});
        assertThat(DefaultProviders.resolveProviderName(noCli, "deepseek"))
            .isEqualTo("deepseek");

        assertThat(DefaultProviders.resolveProviderName(noCli, null))
            .isEqualTo("google");
    }

    @Test
    void defaultProvidersIncludesSixteenBuiltins() {
        var names = DefaultProviders.defaultProviders().listAll().stream()
            .map(Provider::name)
            .toList();
        assertThat(names).contains(
            "anthropic", "openai", "google", "deepseek", "mistral",
            "moonshotai-cn", "moonshotai", "zai-coding-cn", "zai",
            "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
            "minimax-cn", "minimax", "ant-ling", "ollama");
        assertThat(names).hasSizeGreaterThanOrEqualTo(16);
    }

    @Test
    void apiOptionsCarriesBaseUrlFromSettings() {
        var settings = new Settings();
        settings.defaultBaseUrl = "https://relay.example.com/v1";
        var args = ArgsParser.parse(new String[] {});
        var opts = DefaultProviders.apiOptions(args, "openai", settings, null);
        assertThat(opts.baseUrl()).isEqualTo("https://relay.example.com/v1");
    }

    @Test
    void cliBaseUrlOverridesSettings() {
        var settings = new Settings();
        settings.defaultBaseUrl = "https://relay.example.com/v1";
        var args = ArgsParser.parse(new String[] {"--base-url", "https://cli.example.com/v1"});
        var opts = DefaultProviders.apiOptions(args, "openai", settings, null);
        assertThat(opts.baseUrl()).isEqualTo("https://cli.example.com/v1");
    }

    @Test
    void apiOptionsDefaultsToBlankBaseUrl() {
        var settings = new Settings();
        var args = ArgsParser.parse(new String[] {});
        var opts = DefaultProviders.apiOptions(args, "openai", settings, null);
        assertThat(opts.baseUrl()).isEqualTo("");
    }

    @Test
    void settingsApiKeyUsedWhenNoCliKey() {
        var settings = new Settings();
        settings.defaultApiKey = "sk-settings";
        var args = ArgsParser.parse(new String[] {});
        var opts = DefaultProviders.apiOptions(args, "openai", settings, null);
        assertThat(opts.apiKey()).isEqualTo("sk-settings");
    }

    @Test
    void cliApiKeyOverridesSettingsKey() {
        var settings = new Settings();
        settings.defaultApiKey = "sk-settings";
        var args = ArgsParser.parse(new String[] {"--api-key", "sk-cli"});
        var opts = DefaultProviders.apiOptions(args, "openai", settings, null);
        assertThat(opts.apiKey()).isEqualTo("sk-cli");
    }

    // ── P1（docs/31 §8.31）：适配器跟着**模型**走 ─────────────────────────

    /**
     * RE-P1：模型的 provider 决定适配器，而不是会话的
     * {@code defaultProvider}。两个桩 provider 只记录「谁被 createApi」，
     * 不打任何网络。
     *
     * <p>修复前 {@code streamFnFor} 闭包了会话 provider ⇒ 这里会打进
     * {@code alpha}，故本用例**应红**（pi 的参照：
     * {@code compat.ts:262} {@code resolveApiProvider(model.api)}）。</p>
     */
    @Test
    void streamFnRoutesByModelProviderNotSessionProvider() {
        var calls = new ArrayList<String>();
        var registry = ProviderRegistry.create();
        registry.register(new RecordingProvider("alpha", calls));
        registry.register(new RecordingProvider("beta", calls));

        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var streamFn = DefaultProviders.streamFnFor(args, "alpha", registry, new Settings());

        streamFn.stream(ModelId.of("beta", "beta-model"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(calls).containsExactly("beta");
    }

    /**
     * 裁决 ①（§8.31.7）：模型的 provider 未注册 ⇒ 回退会话 provider
     * （不新增硬失败，只在 stderr 留一行警告）。本用例今天就该绿 —— 它钉的是
     * 「回退规则被保留」，不是新行为。
     */
    @Test
    void unregisteredModelProviderFallsBackToSessionProvider() {
        var calls = new ArrayList<String>();
        var registry = ProviderRegistry.create();
        registry.register(new RecordingProvider("alpha", calls));

        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var streamFn = DefaultProviders.streamFnFor(args, "alpha", registry, new Settings());

        streamFn.stream(ModelId.of("ghost", "ghost-model"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(calls).containsExactly("alpha");
    }

    // ── 决策 5（docs/31 §8.34.4）：请求带整个 ModelInfo，不是只有 id ───────

    /**
     * <b>RE-决策 5</b>：适配器收到的是**目录里的整个 {@link ModelInfo}**。
     *
     * <p>要证的正是这条**通道**本身。包② 开工前 {@code streamBlocking} 只投
     * {@link ModelId}（走 7 参便捷构造器 ⇒ {@code ModelInfo.minimal}），于是 models.json
     * 里的 per-model 标志到不了适配器 —— {@code compat.allowEmptySignature} 与
     * extended thinking 两条都因此不可达（docs/32 B15）。</p>
     *
     * <p>⚠️ 与 {@code AnthropicThinkingReplayTest} 的 B8-1 **分工必须说清**：B8-1 自己造
     * {@code StreamRequest}，所以它只证明「落线读了 compat」；把本方法所在的投送链改回只投
     * id，B8-1 **照样绿**。缺了本夹具，决策 5 就没有任何一条夹具守着。</p>
     */
    @Test
    void streamFnCarriesCatalogModelMetadata() {
        var info = new ModelInfo(ModelId.of("alpha", "alpha-model"), "Alpha Model",
            Set.of(), 128_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), ModelCompat.of(true));
        var alpha = new RecordingProvider("alpha", new ArrayList<>(),
            BuiltinCatalog.of(List.of(info)));
        var registry = ProviderRegistry.create();
        registry.register(alpha);

        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var streamFn = DefaultProviders.streamFnFor(args, "alpha", registry, new Settings());

        streamFn.stream(ModelId.of("alpha", "alpha-model"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(alpha.requests).hasSize(1);
        assertThat(alpha.requests.get(0).model().compat().allowEmptySignature()).isTrue();
    }

    /**
     * <b>回归门</b>：目录里**查不到**该模型 ⇒ 退化为 {@code ModelInfo.minimal}，但 id 仍在。
     *
     * <p>钉的是「容忍未知模型」这条既有行为不被本次改动破坏：id 是所有适配器都要读的
     * （线格上的 model 名、消息身份三元组），不能因为目录未命中就丢；而合成的元数据
     * compat 为 false ≡ pi 的 {@code ?? false}，是安全方向（§8.34.4 决策 5）。</p>
     */
    @Test
    void unknownModelStillCarriesItsId() {
        var alpha = new RecordingProvider("alpha", new ArrayList<>());
        var registry = ProviderRegistry.create();
        registry.register(alpha);

        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        var streamFn = DefaultProviders.streamFnFor(args, "alpha", registry, new Settings());

        streamFn.stream(ModelId.of("alpha", "ghost-model"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(alpha.requests).hasSize(1);
        assertThat(alpha.requests.get(0).modelId())
            .isEqualTo(ModelId.of("alpha", "ghost-model"));
        assertThat(alpha.requests.get(0).model().compat().allowEmptySignature()).isFalse();
    }

    /** 桩 provider：只把自己被调用的名字记进共享日志，不触网。 */
    private static final class RecordingProvider implements Provider {

        private final String name;
        private final List<String> calls;
        private final ModelCatalog catalog;
        /** 适配器**实际收到**的请求 —— 决策 5 的观测面。 */
        private final List<StreamRequest> requests = new ArrayList<>();

        RecordingProvider(String name, List<String> calls) {
            this(name, calls, ModelCatalog.empty());
        }

        RecordingProvider(String name, List<String> calls, ModelCatalog catalog) {
            this.name = name;
            this.calls = calls;
            this.catalog = catalog;
        }

        @Override public String name() { return name; }

        @Override public String displayName() { return name; }

        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            calls.add(name);
            return apiType.cast(new RecordingChatApi(requests));
        }

        @Override public ModelCatalog builtinModels() { return catalog; }
    }

    /** 桩 ChatApi：满足接口即可，事件流为空（本夹具只观测适配器选择/请求载荷）。 */
    private static final class RecordingChatApi implements ChatApi {

        private final List<StreamRequest> requests;

        RecordingChatApi(List<StreamRequest> requests) {
            this.requests = requests;
        }

        @Override public Flow.Publisher<StreamEvent> stream(StreamRequest request,
                                                            ApiOptions options) {
            throw new UnsupportedOperationException("RE-P1 只观测适配器选择");
        }

        @Override public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
            requests.add(request);
            return StreamIterator.from(List.of());
        }

        @Override public Message send(StreamRequest request, ApiOptions options) {
            throw new UnsupportedOperationException("RE-P1 只观测适配器选择");
        }
    }
}
