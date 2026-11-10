package com.pijava.examples;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * Ports pi's {@code custom-provider-anthropic} example.
 *
 * <p>Registers a configurable provider that speaks the Anthropic Messages protocol
 * against a fixed base URL and static Claude catalog. OAuth is reduced to pi's
 * callback contract and credential shape; no network exchange is performed.</p>
 */
public final class CustomAnthropicProviderExtension implements PiExtension {

    /** Extension name shown by the host registry. */
    public static final String NAME = "custom-provider-anthropic";

    /** Provider id registered in the provider registry. */
    public static final String PROVIDER_NAME = "custom-anthropic";

    /** Fixed endpoint of the example provider. */
    public static final String BASE_URL = "https://api.anthropic.com";

    /** Environment variable holding the example provider's API key. */
    public static final String API_KEY_ENV = "CUSTOM_ANTHROPIC_API_KEY";

    private static final ModelCatalog MODELS = BuiltinCatalog.of(List.of(
            new ModelInfo(ModelId.of(PROVIDER_NAME, "claude-opus-4-5"),
                    "Claude Opus 4.5 (Custom)",
                    ModelCapability.frontierDefaults(), 200000, 64000, false,
                    new PricingInfo(5, 25, 0.5, 6.25, List.of()), Map.of(), Map.of()),
            new ModelInfo(ModelId.of(PROVIDER_NAME, "claude-sonnet-4-5"),
                    "Claude Sonnet 4.5 (Custom)",
                    ModelCapability.frontierDefaults(), 200000, 64000, false,
                    new PricingInfo(3, 15, 0.3, 3.75, List.of()), Map.of(), Map.of())));

    private static final ProviderConfig CONFIG = ProviderConfig.single(
            PROVIDER_NAME, "Custom Anthropic", BASE_URL, API_KEY_ENV,
            Protocol.ANTHROPIC_MESSAGES, MODELS);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.providers().register(new CustomAnthropicProvider());
    }

    /** The provider instance registered by this extension. */
    static final class CustomAnthropicProvider extends ConfigurableProvider {

        @Override
        protected ProviderConfig config() {
            return CONFIG;
        }

        @Override
        protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
            if (protocol == Protocol.ANTHROPIC_MESSAGES) {
                return new AnthropicMessagesApi(options, API_KEY_ENV);
            }
            throw new UnsupportedOperationException("Unsupported protocol: " + protocol);
        }
    }

    /**
     * pi OAuth callback contract: announce the authorization URL, then ask the user
     * to paste the returned code. The example never performs a token exchange.
     */
    interface OAuthCallbacks {

        void onAuth(String url);

        String onPrompt(String message);
    }

    /** Static OAuth facts transplanted from pi's custom Anthropic flow. */
    static final class OAuth {

        static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
        static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
        static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
        static final String REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback";
        static final String SCOPES = "org:create_api_key user:profile user:inference";

        private OAuth() {
        }

        static String authorizeUrl(String challenge, String state) {
            return AUTHORIZE_URL
                    + "?code=true"
                    + "&client_id=" + encode(CLIENT_ID)
                    + "&response_type=code"
                    + "&redirect_uri=" + encode(REDIRECT_URI)
                    + "&scope=" + encode(SCOPES)
                    + "&code_challenge=" + encode(challenge)
                    + "&state=" + encode(state);
        }

        static String requestAuthorizationCode(
                OAuthCallbacks callbacks, String challenge, String state) {
            callbacks.onAuth(authorizeUrl(challenge, state));
            return callbacks.onPrompt("Paste the authorization code:");
        }

        static OAuthCredential credentials(
                String access, String refresh, long expiresInSeconds, long nowMillis) {
            long expiresAt = nowMillis + expiresInSeconds * 1000L - 5 * 60 * 1000L;
            return new OAuthCredential(access, refresh, expiresAt / 1000L, BASE_URL);
        }

        static String apiKey(OAuthCredential credential) {
            return credential.accessToken();
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
