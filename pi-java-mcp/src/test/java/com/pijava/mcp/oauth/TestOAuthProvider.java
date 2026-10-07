package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * In-memory provider mirroring pi's {@code TestOAuthProvider}
 * (oauth.test.ts:22-89).
 */
final class TestOAuthProvider implements OAuthClientProvider {

    final String redirectUrl;
    final OAuthClientMetadata metadata;
    @Nullable OAuthClientInformation client;
    @Nullable OAuthTokens tokenSet;
    @Nullable String verifier;
    @Nullable OAuthDiscoveryState discovery;
    @Nullable URI authorizationUrl;
    final List<String> invalidations = new ArrayList<>();

    TestOAuthProvider(String redirectUrl) {
        this.redirectUrl = redirectUrl;
        this.metadata = new OAuthClientMetadata(List.of(redirectUrl), "none",
                List.of("authorization_code", "refresh_token"), List.of("code"),
                "pi-mcp-test", null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String redirectUrl() {
        return redirectUrl;
    }

    @Override
    public OAuthClientMetadata clientMetadata() {
        return metadata;
    }

    @Override
    public String state() {
        return "expected-state";
    }

    @Override
    public @Nullable OAuthClientInformation clientInformation() {
        return client;
    }

    @Override
    public boolean canSaveClientInformation() {
        return true;
    }

    @Override
    public void saveClientInformation(OAuthClientInformation information) {
        this.client = information;
    }

    @Override
    public @Nullable OAuthTokens tokens() {
        return tokenSet;
    }

    @Override
    public void saveTokens(OAuthTokens tokens) {
        this.tokenSet = tokens;
    }

    @Override
    public void redirectToAuthorization(URI url) {
        this.authorizationUrl = url;
    }

    @Override
    public void saveCodeVerifier(String verifier) {
        this.verifier = verifier;
    }

    @Override
    public String codeVerifier() {
        if (verifier == null) {
            throw new IllegalStateException("Missing code verifier");
        }
        return verifier;
    }

    @Override
    public void invalidateCredentials(String kind) {
        invalidations.add(kind);
        if ("all".equals(kind) || "client".equals(kind)) {
            client = null;
        }
        if ("all".equals(kind) || "tokens".equals(kind)) {
            tokenSet = null;
        }
        if ("all".equals(kind) || "verifier".equals(kind)) {
            verifier = null;
        }
        if ("all".equals(kind) || "discovery".equals(kind)) {
            discovery = null;
        }
    }

    @Override
    public void saveDiscoveryState(OAuthDiscoveryState state) {
        this.discovery = state;
    }

    @Override
    public @Nullable OAuthDiscoveryState discoveryState() {
        return discovery;
    }
}
