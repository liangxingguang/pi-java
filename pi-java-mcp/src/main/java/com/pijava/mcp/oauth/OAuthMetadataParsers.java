package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Structural parsers for OAuth metadata documents (pi types.ts:134-176).
 *
 * <p>Dependency-free, like pi: documents are parsed into generic maps and
 * validated field by field. Note that {@code safeUrl} does <b>not</b> enforce
 * https; it rejects only unparseable values and {@code javascript:},
 * {@code data:}, {@code vbscript:} schemes.</p>
 */
public final class OAuthMetadataParsers {

    private OAuthMetadataParsers() {
    }

    /** Parse RFC 9728 protected-resource metadata. */
    public static OAuthProtectedResourceMetadata parseProtectedResourceMetadata(Object value) {
        var input = object(value, "OAuth protected resource metadata");
        var servers = optionalStrings(input.get("authorization_servers"), "authorization_servers");
        if (servers != null) {
            servers = servers.stream()
                    .map(url -> safeUrl(url, "authorization server URL"))
                    .toList();
        }
        var extension = without(input,
                "resource", "authorization_servers", "scopes_supported");
        return new OAuthProtectedResourceMetadata(
                safeUrl(input.get("resource"), "OAuth protected resource metadata resource"),
                servers,
                optionalStrings(input.get("scopes_supported"), "scopes_supported"),
                extension);
    }

    /** Parse RFC 8414 authorization-server metadata. */
    public static AuthorizationServerMetadata parseAuthorizationServerMetadata(Object value) {
        var input = object(value, "authorization server metadata");
        var responseTypes = optionalStrings(input.get("response_types_supported"), "response_types_supported");
        if (responseTypes == null) {
            throw new IllegalArgumentException("Invalid response_types_supported");
        }
        var extension = without(input,
                "issuer", "authorization_endpoint", "token_endpoint", "registration_endpoint",
                "scopes_supported", "response_types_supported", "grant_types_supported",
                "token_endpoint_auth_methods_supported", "code_challenge_methods_supported",
                "client_id_metadata_document_supported",
                "authorization_response_iss_parameter_supported");
        return new AuthorizationServerMetadata(
                safeUrl(input.get("issuer"), "authorization server issuer"),
                safeUrl(input.get("authorization_endpoint"), "authorization endpoint"),
                safeUrl(input.get("token_endpoint"), "token endpoint"),
                optionalUrl(input.get("registration_endpoint"), "registration endpoint"),
                optionalStrings(input.get("scopes_supported"), "scopes_supported"),
                responseTypes,
                optionalStrings(input.get("grant_types_supported"), "grant_types_supported"),
                optionalStrings(input.get("token_endpoint_auth_methods_supported"),
                        "token_endpoint_auth_methods_supported"),
                optionalStrings(input.get("code_challenge_methods_supported"),
                        "code_challenge_methods_supported"),
                optionalBoolean(input.get("client_id_metadata_document_supported")),
                optionalBoolean(input.get("authorization_response_iss_parameter_supported")),
                extension);
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        // isObject() has already proven keys and values are Object; the wildcard
        // capture cannot be expressed to the compiler, so the cast is unchecked.
        @SuppressWarnings("unchecked")
        var typed = (Map<String, Object>) map;
        return typed;
    }

    /** Null or empty-string means a server had no value for the field. */
    private static boolean absent(@Nullable Object value) {
        return value == null || value.equals("");
    }

    private static @Nullable List<String> optionalStrings(@Nullable Object value, String name) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        var copy = new ArrayList<String>();
        for (var item : list) {
            copy.add((String) item);
        }
        return List.copyOf(copy);
    }

    private static String safeUrl(Object value, String name) {
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("javascript") || scheme.equals("data") || scheme.equals("vbscript")) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return text;
    }

    private static @Nullable String optionalUrl(@Nullable Object value, String name) {
        return absent(value) ? null : safeUrl(value, name);
    }

    private static @Nullable Boolean optionalBoolean(@Nullable Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static Map<String, Object> without(Map<String, Object> input, String... keys) {
        var extension = new LinkedHashMap<String, Object>();
        outer: for (var entry : input.entrySet()) {
            for (var key : keys) {
                if (key.equals(entry.getKey())) {
                    continue outer;
                }
            }
            extension.put(entry.getKey(), entry.getValue());
        }
        // Not Map.copyOf: documents may carry explicit null-valued unknown keys,
        // which pi retains through the object spread.
        return Collections.unmodifiableMap(extension);
    }
}
