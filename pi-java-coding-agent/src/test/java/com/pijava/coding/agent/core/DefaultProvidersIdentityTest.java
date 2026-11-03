package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.identity.AttributionSetting;
import com.pijava.ai.identity.ClientIdentity;
import com.pijava.ai.identity.UserAgentMode;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.cli.Version;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.SpanOptions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 B266：宿主请求组装点的 identity 两步（pi {@code sdk.ts:340-353} 对位）。
 *
 * <ol>
 *   <li>宿主每请求以 {@code ClientIdentityProvider.get(settings)} <b>替换</b>
 *       agent 循环的默认 identity（pi 注释：replaces the agent loop's default
 *       identity；逐请求求值 ⇒ 遥测开关即时生效）；</li>
 *   <li>OpenCode 目标追加 {@code x-opencode-session}（先 session、后既有头）。</li>
 * </ol>
 *
 * <p>归因开／关取决于进程真实 {@code PI_TELEMETRY}，本夹具不断言它——那条门在
 * {@link ClientIdentityProviderTest} 用注入缝逐态钉住；这里只钉「宿主值确实到达
 * 车道」与「请求级值被替换」。</p>
 */
class DefaultProvidersIdentityTest {

    private static StreamFn streamFn(ProviderRegistry registry, String sessionId) {
        var args = ArgsParser.parse(new String[] {"--provider", "alpha"});
        return DefaultProviders.streamFnFor(args, "alpha", registry, new Settings(), sessionId);
    }

    @Test
    void hostIdentityReplacesTheRequestLevelAgentDefault() {
        var alpha = new RecordingProvider();
        var registry = ProviderRegistry.create();
        registry.register(alpha);

        var agentLevel = new ClientIdentity(
            UserAgentMode.SHORT, AttributionSetting.OFF, "agent-version");
        streamFn(registry, null).stream(ModelId.of("alpha", "m"),
            Context.of(List.of()), StreamOptions.defaults().withIdentity(agentLevel));

        var seen = alpha.optionsSeen.get(0).identity();
        assertThat(seen).isNotNull();
        assertThat(seen.userAgent()).isEqualTo(UserAgentMode.DETAILED);
        assertThat(seen.version()).isEqualTo(Version.VERSION);
    }

    @Test
    void missingRequestIdentityStillGetsTheHostIdentity() {
        var alpha = new RecordingProvider();
        var registry = ProviderRegistry.create();
        registry.register(alpha);

        streamFn(registry, null).stream(ModelId.of("alpha", "m"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(alpha.optionsSeen.get(0).identity()).isNotNull();
        assertThat(alpha.optionsSeen.get(0).identity().userAgent())
            .isEqualTo(UserAgentMode.DETAILED);
    }

    @Test
    void openCodeModelGetsTheSessionHeader() {
        var model = new ModelInfo(ModelId.of("opencode", "opus"), "Opus", Set.of(),
            128_000, 16_384, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), ModelCompat.NONE, null,
            "https://opencode.ai/zen/v1");
        var alpha = new RecordingProvider(BuiltinCatalog.of(List.of(model)));
        var registry = ProviderRegistry.create();
        registry.register(alpha);

        streamFn(registry, "session-1").stream(ModelId.of("opencode", "opus"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(headersOf(alpha.optionsSeen.get(0)))
            .containsEntry("x-opencode-session", "session-1");
    }

    @Test
    void existingConfiguredHeadersOverrideTheSessionHeader() {
        // pi models.ts:856-898：session 头先合并、配置头后合并 ⇒ 配置头赢。
        var model = new ModelInfo(ModelId.of("opencode", "opus"), "Opus", Set.of(),
            128_000, 16_384, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of("x-opencode-session", "caller"), Map.of(), ModelCompat.NONE, null,
            "https://opencode.ai/zen/v1");
        var alpha = new RecordingProvider(BuiltinCatalog.of(List.of(model)));
        var registry = ProviderRegistry.create();
        registry.register(alpha);

        streamFn(registry, "session-1").stream(ModelId.of("opencode", "opus"),
            Context.of(List.of()), StreamOptions.defaults());

        assertThat(headersOf(alpha.optionsSeen.get(0)))
            .containsEntry("x-opencode-session", "caller");
    }

    /** 8 参重建点必须把 identity 与 telemetry 原样带到下一层（docs/14 §3.7）。 */
    @Test
    void optionRebuildsPreserveIdentityAndTelemetry() {
        var span = new InMemoryTelemetryContext().openSpan(new SpanOptions("sentinel"));
        var identity = ClientIdentityProvider.get(new Settings());
        var base = new ApiOptions("", "sk", java.time.Duration.ofSeconds(1), 0,
            Map.of(), AuthKind.API_KEY, span, identity);
        var model = new ModelInfo(ModelId.of("alpha", "m"), "M", Set.of(),
            128_000, 16_384, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), ModelCompat.NONE, "anthropic-messages");

        assertThat(DefaultProviders.withPerModelOverrides(base, model).identity())
            .isSameAs(identity);
        assertThat(DefaultProviders.withPerModelOverrides(base, model).telemetry())
            .isSameAs(span);
        assertThat(DefaultProviders.withModelProtocol(base, model).identity()).isSameAs(identity);
        assertThat(DefaultProviders.withMaxRetries(base, 0).identity()).isSameAs(identity);
    }

    private static Map<String, Object> headersOf(ApiOptions options) {
        Object raw = options.extra().get("headers");
        var out = new java.util.LinkedHashMap<String, Object>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        return out;
    }

    /** 桩 provider：记录到达 {@code createApi} 的选项，不触网、事件流为空。 */
    private static final class RecordingProvider implements Provider {

        private final List<ApiOptions> optionsSeen = new ArrayList<>();
        private final ModelCatalog catalog;

        RecordingProvider() {
            this(ModelCatalog.empty());
        }

        RecordingProvider(ModelCatalog catalog) {
            this.catalog = catalog;
        }

        @Override public String name() { return "alpha"; }

        @Override public String displayName() { return "alpha"; }

        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            optionsSeen.add(options);
            return apiType.cast(new EmptyChatApi());
        }

        @Override public ModelCatalog builtinModels() { return catalog; }
    }

    /** 空事件流的桩 ChatApi。 */
    private static final class EmptyChatApi implements ChatApi {

        @Override public Flow.Publisher<StreamEvent> stream(StreamRequest request,
                                                            ApiOptions options) {
            throw new UnsupportedOperationException("只观测请求选项");
        }

        @Override public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
            return StreamIterator.from(List.of());
        }

        @Override public com.pijava.ai.message.Message send(StreamRequest request,
                                                             ApiOptions options) {
            throw new UnsupportedOperationException("只观测请求选项");
        }
    }
}
