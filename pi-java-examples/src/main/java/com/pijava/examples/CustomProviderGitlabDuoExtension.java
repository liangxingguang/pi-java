package com.pijava.examples;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.auth.OAuthCredential;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.protocol.AnthropicMessagesApi;
import com.pijava.ai.protocol.OpenAIResponsesApi;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * Ports pi's {@code custom-provider-gitlab-duo} example.
 *
 * <p>The provider registers one catalog whose models carry their own wire protocol
 * ({@code anthropic-messages} or {@code openai-responses}); requests are delegated to
 * the built-in protocol adapters, never to a copied implementation. OAuth is limited
 * to pi's authorization-URL and credential shape.</p>
 */
public final class CustomProviderGitlabDuoExtension implements PiExtension {

    /** Extension name shown by the host registry. */
    public static final String NAME = "custom-provider-gitlab-duo";

    /** Provider id registered in the provider registry. */
    public static final String PROVIDER_NAME = "gitlab-duo";

    /** Default GitLab AI gateway endpoint. */
    public static final String AI_GATEWAY_URL = "https://cloud.gitlab.com";

    static final String ANTHROPIC_PROXY_URL = AI_GATEWAY_URL + "/ai/v1/proxy/anthropic/";
    static final String OPENAI_PROXY_URL = AI_GATEWAY_URL + "/ai/v1/proxy/openai/v1";

    /** Environment variable holding the GitLab access token. */
    public static final String API_KEY_ENV = "GITLAB_TOKEN";

    private static final String GITLAB_COM_URL = "https://gitlab.com";
    private static final String BUNDLED_CLIENT_ID =
            "da4edff2e6ebd2bc3208611e2768bc1c1dd7be791dc5ff26ca34ca9ee44f7d4b";
    private static final String OAUTH_SCOPES = "api";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/callback";

    private static final List<ModelInfo> MODELS = List.of(
            model("claude-opus-4-8", "Claude Opus 4.8", Protocol.ANTHROPIC_MESSAGES,
                    ANTHROPIC_PROXY_URL, 5, 25, 0.5, 6.25, 1000000, 128000),
            model("claude-sonnet-4-6", "Claude Sonnet 4.6", Protocol.ANTHROPIC_MESSAGES,
                    ANTHROPIC_PROXY_URL, 3, 15, 0.3, 3.75, 1000000, 64000),
            model("claude-opus-4-5-20251101", "Claude Opus 4.5", Protocol.ANTHROPIC_MESSAGES,
                    ANTHROPIC_PROXY_URL, 15, 75, 1.5, 18.75, 200000, 32000),
            model("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", Protocol.ANTHROPIC_MESSAGES,
                    ANTHROPIC_PROXY_URL, 3, 15, 0.3, 3.75, 200000, 16384),
            model("claude-haiku-4-5-20251001", "Claude Haiku 4.5", Protocol.ANTHROPIC_MESSAGES,
                    ANTHROPIC_PROXY_URL, 1, 5, 0.1, 1.25, 200000, 8192),
            model("gpt-5.5-2026-04-23", "GPT-5.5", Protocol.OPENAI_RESPONSES,
                    OPENAI_PROXY_URL, 5, 30, 0.5, 0, 272000, 128000),
            model("gpt-5.1-2025-11-13", "GPT-5.1", Protocol.OPENAI_RESPONSES,
                    OPENAI_PROXY_URL, 2.5, 10, 0, 0, 128000, 16384),
            model("gpt-5-mini-2025-08-07", "GPT-5 Mini", Protocol.OPENAI_RESPONSES,
                    OPENAI_PROXY_URL, 0.15, 0.6, 0, 0, 128000, 16384),
            model("gpt-5-codex", "GPT-5 Codex", Protocol.OPENAI_RESPONSES,
                    OPENAI_PROXY_URL, 2.5, 10, 0, 0, 128000, 16384));

    private static final ModelCatalog CATALOG = BuiltinCatalog.of(MODELS);

    private static final ProviderConfig CONFIG = new ProviderConfig(
            PROVIDER_NAME, "GitLab Duo", AI_GATEWAY_URL, API_KEY_ENV,
            Protocol.ANTHROPIC_MESSAGES,
            Set.of(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES), CATALOG);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.providers().register(new GitLabDuoProvider());
    }

    /** Wire protocol selected by the model id, mirroring pi's per-model {@code api}. */
    static Protocol protocolFor(String modelId) {
        return CATALOG.find(ModelId.of(PROVIDER_NAME, modelId))
                .map(model -> Protocol.fromWire(model.api()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));
    }

    /** Proxy base URL selected by the model id. */
    static Optional<String> baseUrlFor(String modelId) {
        return CATALOG.find(ModelId.of(PROVIDER_NAME, modelId)).map(ModelInfo::baseUrl);
    }

    private static ModelInfo model(String id, String name, Protocol protocol, String baseUrl,
            double input, double output, double cacheRead, double cacheWrite,
            int contextWindow, int maxTokens) {
        return new ModelInfo(ModelId.of(PROVIDER_NAME, id), name, ModelCapability.frontierDefaults(),
                contextWindow, maxTokens, false,
                new PricingInfo(input, output, cacheRead, cacheWrite, List.of()),
                ThinkingLevelMap.empty(), Map.of(), Map.of(),
                com.pijava.ai.catalog.ModelCompat.NONE, protocol.wireName(), baseUrl);
    }

    /** The provider instance registered by this extension. */
    static final class GitLabDuoProvider extends ConfigurableProvider {

        @Override
        protected ProviderConfig config() {
            return CONFIG;
        }

        @Override
        protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
            return switch (protocol) {
                case ANTHROPIC_MESSAGES -> new AnthropicMessagesApi(options, API_KEY_ENV);
                case OPENAI_RESPONSES -> new OpenAIResponsesApi(options);
                default -> throw new UnsupportedOperationException(
                        "Unsupported protocol: " + protocol);
            };
        }
    }

    /** pi GitLab OAuth facts, kept as data only. */
    static final class OAuth {

        private OAuth() {
        }

        static String authorizeUrl(String challenge, String state) {
            return GITLAB_COM_URL + "/oauth/authorize"
                    + "?client_id=" + encode(BUNDLED_CLIENT_ID)
                    + "&redirect_uri=" + encode(REDIRECT_URI)
                    + "&response_type=code"
                    + "&scope=" + encode(OAUTH_SCOPES)
                    + "&code_challenge=" + encode(challenge)
                    + "&code_challenge_method=S256"
                    + "&state=" + encode(state);
        }

        static OAuthCredential credentials(String access, String refresh,
                long createdAtEpochSec, long expiresInSeconds) {
            long expiresAt = createdAtEpochSec + expiresInSeconds - 5 * 60;
            return new OAuthCredential(access, refresh, expiresAt, GITLAB_COM_URL);
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
