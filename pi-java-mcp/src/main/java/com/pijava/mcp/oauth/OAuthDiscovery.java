package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpFetch;
import com.pijava.mcp.McpJson;
import com.pijava.mcp.protocol.McpVersion;

/**
 * Three-level OAuth discovery (pi discovery.ts:64-185): protected-resource
 * metadata, then authorization-server metadata, combined into an
 * {@link OAuthServerInfo}; plus resource selection for a concrete server URL.
 */
public final class OAuthDiscovery {

    private OAuthDiscovery() {
    }

    /** One discovery candidate: URL and which metadata family it serves. */
    public record Candidate(URI url, String type) {
    }

    /** Options for {@link #discoverOAuthServerInfo}. */
    public record Options(
            @Nullable URI resourceMetadataUrl,
            @Nullable URI authorizationServerMetadataUrl,
            boolean skipIssuerValidation) {
    }

    /** 4xx and 502 mean "not here", so the next candidate URL is tried. */
    static boolean isDiscoveryMiss(int status) {
        return (status >= 400 && status < 500) || status == 502;
    }

    /** Strip one trailing slash; a root path becomes empty. */
    static String pathSuffix(String pathname) {
        return pathname.endsWith("/") ? pathname.substring(0, pathname.length() - 1) : pathname;
    }

    private static McpFetch.Fetched fetchMetadata(URI url, McpFetch fetch, String protocolVersion)
            throws IOException {
        return fetch.fetch(McpFetch.Request.get(url, Map.of(
                "Accept", "application/json",
                "MCP-Protocol-Version", protocolVersion)));
    }

    private static String version(@Nullable String protocolVersion) {
        return protocolVersion == null ? McpVersion.LATEST : protocolVersion;
    }

    private static Object readJson(byte[] body) throws IOException {
        return McpJson.mapper().readValue(body, Object.class);
    }

    /** Discover a server's protected-resource metadata. */
    public static OAuthProtectedResourceMetadata discoverProtectedResourceMetadata(
            URI serverUrl,
            @Nullable URI resourceMetadataUrl,
            @Nullable String protocolVersion,
            McpFetch fetch) throws IOException {
        var path = serverUrl.getPath() == null ? "" : serverUrl.getPath();
        var version = version(protocolVersion);
        var url = resourceMetadataUrl != null
                ? resourceMetadataUrl
                : serverUrl.resolve("/.well-known/oauth-protected-resource" + pathSuffix(path));
        var response = fetchMetadata(url, fetch, version);
        if (resourceMetadataUrl == null && canFallback(path)
                && isDiscoveryMiss(response.status())) {
            response = fetchMetadata(
                    serverUrl.resolve("/.well-known/oauth-protected-resource"), fetch, version);
        }
        if (!response.ok()) {
            throw new RuntimeException(
                    "HTTP " + response.status() + " loading OAuth protected resource metadata");
        }
        return OAuthMetadataParsers.parseProtectedResourceMetadata(readJson(response.body()));
    }

    /** Build the ordered candidate URLs for authorization-server metadata. */
    public static List<Candidate> buildAuthorizationServerDiscoveryUrls(URI authorizationServerUrl) {
        var path = pathSuffix(
                authorizationServerUrl.getPath() == null ? "" : authorizationServerUrl.getPath());
        var urls = new ArrayList<Candidate>();
        urls.add(new Candidate(
                authorizationServerUrl.resolve("/.well-known/oauth-authorization-server" + path),
                "oauth"));
        urls.add(new Candidate(
                authorizationServerUrl.resolve("/.well-known/openid-configuration" + path),
                "oidc"));
        if (!path.isEmpty()) {
            urls.add(new Candidate(
                    authorizationServerUrl.resolve(path + "/.well-known/openid-configuration"),
                    "oidc"));
        }
        return List.copyOf(urls);
    }

    /** Discover an authorization server's metadata; null when no candidate hosts it. */
    public static @Nullable AuthorizationServerMetadata discoverAuthorizationServerMetadata(
            URI authorizationServerUrl,
            McpFetch fetch,
            @Nullable String protocolVersion,
            boolean skipIssuerValidation) throws IOException {
        var version = version(protocolVersion);
        for (var candidate : buildAuthorizationServerDiscoveryUrls(authorizationServerUrl)) {
            var response = fetchMetadata(candidate.url(), fetch, version);
            if (!response.ok()) {
                if (isDiscoveryMiss(response.status())) {
                    continue;
                }
                throw new RuntimeException("HTTP " + response.status()
                        + " loading authorization server metadata from " + candidate.url());
            }
            var metadata = OAuthMetadataParsers
                    .parseAuthorizationServerMetadata(readJson(response.body()));
            if (!skipIssuerValidation) {
                var expected = authorizationServerUrl.toString();
                if (!trimTrailingSlash(metadata.issuer()).equals(trimTrailingSlash(expected))) {
                    throw new OAuthIssuerMismatchError(expected, metadata.issuer());
                }
            }
            return metadata;
        }
        return null;
    }

    /** Discover the authorization server behind an MCP server. */
    public static OAuthServerInfo discoverOAuthServerInfo(
            URI serverUrl, McpFetch fetch, Options options) throws IOException {
        @Nullable OAuthProtectedResourceMetadata resourceMetadata;
        try {
            resourceMetadata = discoverProtectedResourceMetadata(
                    serverUrl, options.resourceMetadataUrl(), null, fetch);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            resourceMetadata = null;
        }
        if (options.authorizationServerMetadataUrl() != null) {
            var url = options.authorizationServerMetadataUrl();
            var response = fetchMetadata(url, fetch, McpVersion.LATEST);
            if (!response.ok()) {
                throw new RuntimeException("HTTP " + response.status()
                        + " loading authorization server metadata from " + url);
            }
            var metadata = OAuthMetadataParsers
                    .parseAuthorizationServerMetadata(readJson(response.body()));
            return new OAuthServerInfo(metadata.issuer(), metadata, resourceMetadata);
        }
        var authorizationServerUrl = firstAuthorizationServer(resourceMetadata, serverUrl);
        return new OAuthServerInfo(
                authorizationServerUrl,
                discoverAuthorizationServerMetadata(
                        URI.create(authorizationServerUrl), fetch, null,
                        options.skipIssuerValidation()),
                resourceMetadata);
    }

    private static String firstAuthorizationServer(
            @Nullable OAuthProtectedResourceMetadata resourceMetadata, URI serverUrl) {
        if (resourceMetadata != null && resourceMetadata.authorizationServers() != null
                && !resourceMetadata.authorizationServers().isEmpty()) {
            return resourceMetadata.authorizationServers().get(0);
        }
        return serverUrl.resolve("/").toString();
    }

    /** Strip a URL's fragment; query and path are preserved. */
    public static URI resourceUrlFromServerUrl(URI value) {
        try {
            return new URI(value.getScheme(), value.getSchemeSpecificPart(), null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verify the metadata describes the given server; returns its resource identifier. */
    public static @Nullable String selectResource(
            URI serverUrl, @Nullable OAuthProtectedResourceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        var requested = resourceUrlFromServerUrl(serverUrl);
        var configured = URI.create(metadata.resource());
        if (!origin(requested).equals(origin(configured))) {
            throw mismatch(metadata.resource(), requested);
        }
        var requestedPath = withTrailingSlash(pathOrRoot(requested));
        var configuredPath = withTrailingSlash(pathOrRoot(configured));
        if (!requestedPath.startsWith(configuredPath)) {
            throw mismatch(metadata.resource(), requested);
        }
        return metadata.resource();
    }

    private static RuntimeException mismatch(String resource, URI requested) {
        return new RuntimeException(
                "Protected resource " + resource + " does not match MCP server " + requested);
    }

    private static boolean canFallback(String path) {
        // Java gives a bare origin an empty path; WHATWG calls it "/".
        return !path.isEmpty() && !path.equals("/");
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String origin(URI uri) {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        var port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) {
            port = -1;
        }
        return scheme + "://" + host + (port < 0 ? "" : ":" + port);
    }

    private static String pathOrRoot(URI uri) {
        var path = uri.getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static String withTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
