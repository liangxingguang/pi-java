package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpFetch;

/**
 * The authorization flow itself (pi flow.ts:292-411): discovery, client
 * resolution, code exchange, refresh, and the redirect.
 */
public final class OAuthFlow {

    private OAuthFlow() {
    }

    /** Run the flow, retrying once after invalidating rejected credentials. */
    public static OAuthFlowResult authorizeMcp(OAuthClientProvider provider, OAuthFlowOptions options)
            throws Exception {
        try {
            return runFlow(provider, options);
        } catch (OAuthError error) {
            if (List.of("invalid_client", "unauthorized_client").contains(error.code())) {
                provider.invalidateCredentials("all");
                return runFlow(provider, options);
            }
            if ("invalid_grant".equals(error.code())) {
                provider.invalidateCredentials("tokens");
                return runFlow(provider, options);
            }
            throw error;
        }
    }

    private static OAuthFlowResult runFlow(OAuthClientProvider provider, OAuthFlowOptions options)
            throws Exception {
        var fetch = OAuthTokenRequests.fetchOf(options.fetch());
        // A configured metadata URL is not cached, so changing it applies at once.
        var metadataUrl = options.authorizationServerMetadataUrl() == null
                ? null : OAuthEndpoints.secureEndpoint(options.authorizationServerMetadataUrl());
        var discovered = discover(provider, options, fetch, metadataUrl);
        if (metadataUrl == null) {
            provider.saveDiscoveryState(new OAuthDiscoveryState(
                    discovered.authorizationServerUrl(),
                    discovered.authorizationServerMetadata(),
                    discovered.resourceMetadata(),
                    options.resourceMetadataUrl() == null
                            ? null : options.resourceMetadataUrl().toString()));
        }
        var metadata = discovered.authorizationServerMetadata();
        var resource = OAuthDiscovery.selectResource(options.serverUrl(), discovered.resourceMetadata());
        // `||`, not `??`: an empty scope falls through to the next source.
        var scope = pickScope(provider, options, discovered.resourceMetadata());

        var stored = provider.clientInformation();
        var document = stored != null ? null : provider.clientMetadataDocument(metadata);
        if (document != null) {
            checkClientMetadataUrl(document.url());
        }
        var client = stored != null ? stored
                : document != null ? clientDocumentInformation(document) : null;
        if (client == null) {
            if (options.authorizationCode() != null) {
                throw new IllegalStateException(
                        "OAuth client information is missing during code exchange");
            }
            if (!provider.canSaveClientInformation()) {
                throw new IllegalStateException("OAuth client information cannot be persisted");
            }
            client = OAuthTokenRequests.registerClient(
                    URI.create(discovered.authorizationServerUrl()),
                    new OAuthTokenRequests.Options(metadata, provider.clientMetadata(), scope, fetch));
            provider.saveClientInformation(client);
        }
        var redirectUrl = document != null ? document.redirectUrl() : provider.redirectUrl();
        var tokenOptions = new OAuthTokenRequests.TokenRequestOptions(
                metadata, client, resource, provider.addClientAuthentication(), fetch);

        if (options.authorizationCode() != null) {
            checkIssuer(metadata, options.iss());
            var tokens = OAuthTokenRequests.exchangeAuthorizationCode(
                    URI.create(discovered.authorizationServerUrl()),
                    new OAuthTokenRequests.ExchangeOptions(tokenOptions,
                            options.authorizationCode(), provider.codeVerifier(), redirectUrl));
            provider.saveTokens(OAuthEndpoints.withScope(tokens, scope));
            return OAuthFlowResult.AUTHORIZED;
        }

        var existing = options.skipRefresh() ? null : provider.tokens();
        if (existing != null && existing.refreshToken() != null) {
            try {
                var tokens = OAuthTokenRequests.refreshAuthorization(
                        URI.create(discovered.authorizationServerUrl()),
                        new OAuthTokenRequests.RefreshOptions(tokenOptions, existing.refreshToken()));
                provider.saveTokens(OAuthEndpoints.withScope(tokens, existing.scope()));
                return OAuthFlowResult.AUTHORIZED;
            } catch (OAuthInsecureEndpointError error) {
                throw error;
            } catch (OAuthError error) {
                if (!"server_error".equals(error.code())) {
                    throw error;
                }
            } catch (IOException ignored) {
                // A network failure is worth a redirect attempt.
            }
        }

        var authorization = OAuthEndpoints.startAuthorization(
                URI.create(discovered.authorizationServerUrl()), metadata, client, redirectUrl,
                scope, provider.state(), resource);
        provider.saveCodeVerifier(authorization.codeVerifier());
        provider.redirectToAuthorization(authorization.authorizationUrl());
        return OAuthFlowResult.REDIRECT;
    }

    private static OAuthServerInfo discover(OAuthClientProvider provider, OAuthFlowOptions options,
                                            McpFetch fetch, @Nullable URI metadataUrl) throws IOException {
        var cached = metadataUrl != null ? null : provider.discoveryState();
        if (cached != null && cached.authorizationServerUrl() != null) {
            var metadata = cached.authorizationServerMetadata();
            if (metadata == null) {
                metadata = OAuthDiscovery.discoverAuthorizationServerMetadata(
                        URI.create(cached.authorizationServerUrl()), fetch, null,
                        options.skipIssuerValidation());
            }
            return new OAuthServerInfo(cached.authorizationServerUrl(), metadata,
                    cached.resourceMetadata());
        }
        return OAuthDiscovery.discoverOAuthServerInfo(options.serverUrl(), fetch,
                new OAuthDiscovery.Options(options.resourceMetadataUrl(), metadataUrl,
                        options.skipIssuerValidation()));
    }

    private static @Nullable String pickScope(OAuthClientProvider provider, OAuthFlowOptions options,
                                               @Nullable OAuthProtectedResourceMetadata resourceMetadata) {
        if (notEmpty(options.scope())) {
            return options.scope();
        }
        if (resourceMetadata != null && resourceMetadata.scopesSupported() != null
                && !resourceMetadata.scopesSupported().isEmpty()) {
            return String.join(" ", resourceMetadata.scopesSupported());
        }
        return provider.clientMetadata().scope();
    }

    private static boolean notEmpty(@Nullable String value) {
        return value != null && !value.isEmpty();
    }

    private static void checkClientMetadataUrl(String value) {
        var url = URI.create(value);
        var path = url.getPath();
        if (!"https".equalsIgnoreCase(url.getScheme())
                || path == null || path.isEmpty() || path.equals("/")) {
            throw new IllegalStateException("Invalid OAuth client metadata URL");
        }
    }

    private static OAuthClientInformation clientDocumentInformation(
            OAuthClientProvider.ClientMetadataDocument document) {
        return new OAuthClientInformation(document.url(), null, null, null, null, List.of(), null);
    }

    /** RFC 9207: never send a code from another authorization server to this one. */
    private static void checkIssuer(@Nullable AuthorizationServerMetadata metadata,
                                    @Nullable String iss) {
        if (metadata == null) {
            return;
        }
        if (iss != null || Boolean.TRUE.equals(metadata.authorizationResponseIssParameterSupported())) {
            if (!Objects.equals(iss, metadata.issuer())) {
                throw new OAuthIssuerMismatchError(metadata.issuer(), iss);
            }
        }
    }
}
