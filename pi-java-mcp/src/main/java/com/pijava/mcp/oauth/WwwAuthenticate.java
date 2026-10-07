package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Parsing of {@code WWW-Authenticate} response headers into an
 * {@link OAuthChallenge} (pi discovery.ts:33-56). Only Bearer and DPoP
 * schemes are recognized.
 */
public final class WwwAuthenticate {

    private WwwAuthenticate() {
    }

    /** Parse a challenge header; unknown schemes yield an empty challenge. */
    public static OAuthChallenge parse(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return OAuthChallenge.empty();
        }
        var trimmed = header.stripLeading();
        var schemeEnd = firstWhitespace(trimmed);
        var scheme = (schemeEnd < 0 ? trimmed : trimmed.substring(0, schemeEnd))
                .toLowerCase(Locale.ROOT);
        if (!scheme.equals("bearer") && !scheme.equals("dpop")) {
            return OAuthChallenge.empty();
        }
        @Nullable URI resourceMetadataUrl = null;
        var resourceMetadata = field(header, "resource_metadata");
        if (resourceMetadata != null) {
            try {
                var uri = URI.create(resourceMetadata);
                if (uri.isAbsolute()) {
                    resourceMetadataUrl = uri;
                }
            } catch (IllegalArgumentException ignored) {
                // Unparseable hint: the other fields are still usable.
            }
        }
        return new OAuthChallenge(
                resourceMetadataUrl,
                field(header, "scope"),
                field(header, "error"),
                field(header, "error_description"));
    }

    /** Extract one field; an empty quoted value counts as absent. */
    private static @Nullable String field(String header, String name) {
        var pattern = Pattern.compile(
                "(?:^|[\\s,])" + Pattern.quote(name)
                        + "=(?:\"([^\"]*)\"|([^\\s,]+))",
                Pattern.CASE_INSENSITIVE);
        var matcher = pattern.matcher(header);
        if (!matcher.find()) {
            return null;
        }
        var quoted = matcher.group(1);
        if (quoted != null && !quoted.isEmpty()) {
            return quoted;
        }
        var bare = matcher.group(2);
        return bare == null || bare.isEmpty() ? null : bare;
    }

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s");

    private static int firstWhitespace(String value) {
        var matcher = WHITESPACE.matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }
}
