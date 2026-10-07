package com.pijava.mcp.oauth;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.oauth.OAuthClientProvider.ClientMetadataDocument;

/**
 * Default stateful provider for one exact MCP server URL
 * (pi provider.ts:52-168). Applications inject durable storage when needed.
 */
public final class McpOAuthProvider implements OAuthClientProvider {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String serverUrl;
    private final String redirectUrl;
    private final OAuthClientMetadata clientMetadata;
    private final @Nullable Function<@Nullable AuthorizationServerMetadata,
            @Nullable ClientMetadataDocument> documentLookup;
    private final @Nullable OAuthClientInformation configuredClient;
    private final OAuthStateStore store;
    private final McpOAuthProviderOptions.OnRedirect onRedirect;
    private final Object lock = new Object();

    /** Create a provider from its options. */
    public McpOAuthProvider(McpOAuthProviderOptions options) {
        this.serverUrl = URI.create(options.serverUrl().toString()).toString();
        this.redirectUrl = options.redirectUrl().toString();
        var metadata = options.clientMetadata();
        this.clientMetadata = new OAuthClientMetadata(
                metadata.redirectUris() == null || metadata.redirectUris().isEmpty()
                        ? List.of(redirectUrl) : metadata.redirectUris(),
                metadata.tokenEndpointAuthMethod() != null ? metadata.tokenEndpointAuthMethod()
                        : options.clientSecret() != null ? "client_secret_post" : "none",
                metadata.grantTypes() != null ? metadata.grantTypes()
                        : List.of("authorization_code", "refresh_token"),
                metadata.responseTypes() != null ? metadata.responseTypes() : List.of("code"),
                metadata.clientName(), metadata.clientUri(), metadata.logoUri(), metadata.scope(),
                metadata.contacts(), metadata.tosUri(), metadata.policyUri(), metadata.jwksUri(),
                metadata.jwks(), metadata.softwareId(), metadata.softwareVersion(),
                metadata.softwareStatement());
        this.documentLookup = options.clientMetadataDocument();
        this.configuredClient = options.clientId() == null ? null
                : new OAuthClientInformation(options.clientId(), options.clientSecret(),
                        null, null, null, List.of(), null);
        this.store = options.store() == null ? new MemoryOAuthStateStore() : options.store();
        this.onRedirect = options.onRedirect();
    }

    @Override
    public String redirectUrl() {
        return redirectUrl;
    }

    @Override
    public OAuthClientMetadata clientMetadata() {
        return clientMetadata;
    }

    @Override
    public @Nullable ClientMetadataDocument clientMetadataDocument(
            @Nullable AuthorizationServerMetadata metadata) {
        return documentLookup == null ? null : documentLookup.apply(metadata);
    }

    @Override
    public @Nullable String state() {
        var existing = load().oauthState();
        if (existing != null) {
            return existing;
        }
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var state = HexFormat.of().formatHex(bytes);
        updateWithOauthState(state);
        return state;
    }

    @Override
    public @Nullable OAuthClientInformation clientInformation() {
        return configuredClient != null ? configuredClient : load().clientInformation();
    }

    @Override
    public boolean canSaveClientInformation() {
        return configuredClient == null;
    }

    @Override
    public void saveClientInformation(OAuthClientInformation information) {
        if (configuredClient != null) {
            return;
        }
        update(value -> new McpOAuthState(value.serverUrl(), information, value.tokens(),
                value.tokensExpireAt(), value.codeVerifier(), value.oauthState(), value.discovery()));
    }

    @Override
    public @Nullable OAuthTokens tokens() {
        return load().tokens();
    }

    @Override
    public void saveTokens(OAuthTokens tokens) {
        var expiresAt = tokens.expiresIn() == null
                ? null : System.currentTimeMillis() + tokens.expiresIn() * 1000L;
        update(value -> new McpOAuthState(value.serverUrl(), value.clientInformation(), tokens,
                expiresAt, value.codeVerifier(), value.oauthState(), value.discovery()));
    }

    @Override
    public void redirectToAuthorization(URI url) throws Exception {
        onRedirect.redirect(url);
    }

    @Override
    public void saveCodeVerifier(String verifier) {
        update(value -> new McpOAuthState(value.serverUrl(), value.clientInformation(),
                value.tokens(), value.tokensExpireAt(), verifier, value.oauthState(),
                value.discovery()));
    }

    @Override
    public String codeVerifier() {
        var verifier = load().codeVerifier();
        if (verifier == null) {
            throw new IllegalStateException("No OAuth PKCE code verifier is stored");
        }
        return verifier;
    }

    @Override
    public void invalidateCredentials(String kind) {
        var all = "all".equals(kind);
        var dropClient = all || "client".equals(kind);
        var dropTokens = all || "tokens".equals(kind);
        var dropVerifier = all || "verifier".equals(kind);
        var dropDiscovery = all || "discovery".equals(kind);
        update(value -> new McpOAuthState(value.serverUrl(),
                dropClient ? null : value.clientInformation(),
                dropTokens ? null : value.tokens(),
                dropTokens ? null : value.tokensExpireAt(),
                dropVerifier ? null : value.codeVerifier(),
                all ? null : value.oauthState(),
                dropDiscovery ? null : value.discovery()));
    }

    @Override
    public void saveDiscoveryState(OAuthDiscoveryState discovery) {
        update(value -> new McpOAuthState(value.serverUrl(), value.clientInformation(),
                value.tokens(), value.tokensExpireAt(), value.codeVerifier(), value.oauthState(),
                discovery));
    }

    @Override
    public @Nullable OAuthDiscoveryState discoveryState() {
        return load().discovery();
    }

    private void updateWithOauthState(String state) {
        update(value -> new McpOAuthState(value.serverUrl(), value.clientInformation(),
                value.tokens(), value.tokensExpireAt(), value.codeVerifier(), state,
                value.discovery()));
    }

    private McpOAuthState load() {
        synchronized (lock) {
            return own(store.load());
        }
    }

    private void update(UnaryOperator<McpOAuthState> updater) {
        synchronized (lock) {
            store.save(updater.apply(own(store.load())));
        }
    }

    /** Stored state for another server URL is ignored, so credentials never leak across servers. */
    private McpOAuthState own(@Nullable McpOAuthState state) {
        return state != null && serverUrl.equals(state.serverUrl())
                ? state : new McpOAuthState(serverUrl, null, null, null, null, null, null);
    }
}
