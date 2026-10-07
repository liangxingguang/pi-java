package com.pijava.mcp.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Validation of one server entry and the exposure rules built on it
 * ({@code mcp-servers.ts:96-278}).
 */
public final class McpServerConfigs {

    /** Server names: letters, digits, {@code _} and {@code -} ({@code :117}). */
    private static final Pattern SERVER_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");

    /**
     * The host names that count as loopback ({@code :93}).
     *
     * <p>Not the same list as the oauth package's four-value {@code OAuthEndpoints.loopback}: this
     * one is about where a credential may go, that one about RFC 8252 redirect URIs.</p>
     */
    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");

    /** Characters a tool-name pattern escapes ({@code :199}); {@code *} stays the wildcard. */
    private static final String REGEX_SPECIALS = "\\^$.|?*+()[]{}";

    /** Older exposure names, accepted in configs and replaced by their current name ({@code :22}). */
    private static final Map<String, McpExposure> EXPOSURE_ALIASES = Map.of("codemode-deferred", McpExposure.CODEMODE);

    /** The exposure values as pi lists them in error messages ({@code :226}). */
    private static final String EXPOSURES = Arrays.stream(McpExposure.values())
            .map(exposure -> "\"" + exposure.wire() + "\"")
            .collect(Collectors.joining(", "));

    private McpServerConfigs() {
    }

    /**
     * Validate one server entry ({@code mcp-servers.ts:221-278}), returning a copy of the
     * configuration with exposure names resolved, or the error message.
     *
     * @param name server name as written in {@code mcp.json}
     * @param raw  the entry as parsed from the file or rebuilt from a configuration
     */
    public static McpConfigValidation validate(String name, JsonNode raw) {
        if (!SERVER_NAME.matcher(name).matches()) {
            return invalid("invalid server name \"" + name + "\" (use letters, digits, \"_\" and \"-\")");
        }
        if (!raw.isObject()) {
            return invalid("server \"" + name + "\" must be an object");
        }
        var value = resolveExposureAliases((ObjectNode) raw);

        McpExposure exposure = null;
        var exposureNode = value.get("exposure");
        if (exposureNode != null) {
            exposure = exposureNode.isTextual() ? McpExposure.fromWire(exposureNode.textValue()) : null;
            if (exposure == null) {
                return invalid("server \"" + name + "\": exposure must be one of " + EXPOSURES);
            }
        }
        Map<String, McpExposure> toolExposure = null;
        var toolExposureNode = value.get("toolExposure");
        if (toolExposureNode != null) {
            if (!toolExposureNode.isObject()) {
                return invalid("server \"" + name + "\": toolExposure must map tool names to exposures");
            }
            toolExposure = new LinkedHashMap<>();
            for (var field : toolExposureNode.properties()) {
                var tool = field.getValue().isTextual() ? McpExposure.fromWire(field.getValue().textValue()) : null;
                if (tool == null) {
                    return invalid("server \"" + name + "\": toolExposure \"" + field.getKey()
                            + "\" must be one of " + EXPOSURES);
                }
                toolExposure.put(field.getKey(), tool);
            }
        }
        Boolean enabled = null;
        var enabledNode = value.get("enabled");
        if (enabledNode != null) {
            if (!enabledNode.isBoolean()) {
                return invalid("server \"" + name + "\": enabled must be a boolean");
            }
            enabled = enabledNode.booleanValue();
        }
        String description = null;
        var descriptionNode = value.get("description");
        if (descriptionNode != null) {
            if (!descriptionNode.isTextual()) {
                return invalid("server \"" + name + "\": description must be a string");
            }
            description = descriptionNode.textValue();
        }
        Number timeout = null;
        var timeoutNode = value.get("timeout");
        if (timeoutNode != null) {
            var seconds = timeoutNode.isNumber() ? timeoutNode.doubleValue() : Double.NaN;
            if (!(seconds > 0)) {
                return invalid("server \"" + name + "\": timeout must be a positive number of seconds");
            }
            timeout = timeoutNode.numberValue();
        }

        // The raw `type`, so that a non-string value is not mistaken for "sse" (:243).
        var typeNode = value.get("type");
        var type = typeNode != null && typeNode.isTextual() ? typeNode.textValue() : null;
        if ("sse".equals(type)) {
            return invalid("server \"" + name + "\": legacy SSE transport is not supported;"
                    + " use the streamable HTTP URL");
        }

        var urlNode = value.get("url");
        if (urlNode != null && urlNode.isTextual()
                && (type == null || type.equals("http") || type.equals("streamable-http"))) {
            var url = parseHttpUrl(urlNode.textValue());
            if (url == null) {
                return invalid("server \"" + name + "\": url must be an http or https URL");
            }
            Map<String, String> headers = null;
            var headersNode = value.get("headers");
            if (headersNode != null) {
                if (!isStringRecord(headersNode)) {
                    return invalid("server \"" + name + "\": headers must map names to strings");
                }
                headers = stringRecord(headersNode);
            }
            var oauthNode = value.get("oauth");
            var oauthError = validateOAuth(oauthNode);
            if (oauthError != null) {
                return invalid("server \"" + name + "\": " + oauthError);
            }
            McpServerConfig.Auth auth = null;
            var authNode = value.get("auth");
            if (authNode != null) {
                if (!authNode.isObject() || !authNode.has("provider")
                        || !authNode.get("provider").isTextual()
                        || authNode.get("provider").textValue().isEmpty()) {
                    return invalid("server \"" + name + "\": auth.provider must be a provider name");
                }
                if (!"https".equalsIgnoreCase(url.getScheme()) && !isLoopbackHost(url.getHost())) {
                    return invalid("server \"" + name + "\": auth requires an https URL,"
                            + " or http on localhost, 127.0.0.1, or [::1]");
                }
                auth = new McpServerConfig.Auth(authNode.get("provider").textValue());
            }
            return new McpConfigValidation.Valid(new McpServerConfig.Http(type, exposure, description,
                    toolExposure, enabled, timeout, urlNode.textValue(), headers,
                    oauthNode == null ? null : oauth(oauthNode), auth));
        }

        var commandNode = value.get("command");
        if (commandNode != null && commandNode.isTextual() && (type == null || type.equals("stdio"))) {
            List<String> args = null;
            var argsNode = value.get("args");
            if (argsNode != null) {
                if (!argsNode.isArray() || !allTextual(argsNode)) {
                    return invalid("server \"" + name + "\": args must be an array of strings");
                }
                args = new ArrayList<>();
                for (var arg : argsNode) {
                    args.add(arg.textValue());
                }
            }
            Map<String, String> env = null;
            var envNode = value.get("env");
            if (envNode != null) {
                if (!isStringRecord(envNode)) {
                    return invalid("server \"" + name + "\": env must map names to strings");
                }
                env = stringRecord(envNode);
            }
            String cwd = null;
            var cwdNode = value.get("cwd");
            if (cwdNode != null) {
                if (!cwdNode.isTextual()) {
                    return invalid("server \"" + name + "\": cwd must be a string");
                }
                cwd = cwdNode.textValue();
            }
            return new McpConfigValidation.Valid(new McpServerConfig.Stdio(type, exposure, description,
                    toolExposure, enabled, timeout, commandNode.textValue(), args, env, cwd));
        }
        return invalid("server \"" + name + "\" needs either \"command\" (stdio)"
                + " or \"url\" (streamable HTTP)");
    }

    /** Namespace of a server's tools: {@code mcp__<server>} with {@code -} replaced by {@code _} ({@code :120-122}). */
    public static String namespace(String server) {
        return "mcp__" + server.replace('-', '_');
    }

    /**
     * Exposure of one tool of a server ({@code :207-215}): its exact {@code toolExposure}
     * entry, else the first matching pattern in the file's order, else the server's exposure.
     *
     * @param config validated server configuration
     * @param toolName tool name as the server offers it
     */
    public static McpExposure getMcpToolExposure(McpServerConfig config, String toolName) {
        var overrides = config.toolExposure();
        if (overrides != null) {
            var exact = overrides.get(toolName);
            if (exact != null) {
                return exact;
            }
            for (var entry : overrides.entrySet()) {
                if (entry.getKey().indexOf('*') >= 0 && toolPattern(entry.getKey()).matcher(toolName).matches()) {
                    return entry.getValue();
                }
            }
        }
        return config.exposure() == null ? McpExposure.CODEMODE : config.exposure();
    }

    /** Whether a redirect URI can be served by the loopback callback server ({@code :96-100}). */
    public static boolean isLoopbackRedirectUri(String value) {
        var url = parseUrl(value);
        return url != null && "http".equalsIgnoreCase(url.getScheme()) && isLoopbackHost(url.getHost())
                && url.getRawQuery() == null && url.getRawFragment() == null;
    }

    /** Whether a host name is one of the loopback hosts; JS lowercases {@code URL.hostname} ({@code :93}). */
    private static boolean isLoopbackHost(@Nullable String host) {
        return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** The OAuth client settings, validated by {@link #validateOAuth} already. */
    private static McpServerConfig.OAuth oauth(JsonNode node) {
        return new McpServerConfig.OAuth(textOrNull(node, "clientId"), textOrNull(node, "clientSecret"),
                integerOrNull(node, "callbackPort"), textOrNull(node, "callbackUrl"),
                textOrNull(node, "scope"), textOrNull(node, "clientName"),
                textOrNull(node, "clientRegistration"), textOrNull(node, "authServerMetadataUrl"));
    }

    /** One error message of {@code mcp-servers.ts:132-174}, without the {@code server "x": } prefix. */
    private static @Nullable String validateOAuth(@Nullable JsonNode oauth) {
        if (oauth == null) {
            return null;
        }
        if (!oauth.isObject()) {
            return "oauth must be an object";
        }
        if (oauth.has("clientId") && !oauth.get("clientId").isTextual()) {
            return "oauth.clientId must be a string";
        }
        if (oauth.has("clientSecret") && !oauth.get("clientSecret").isTextual()) {
            return "oauth.clientSecret must be a string";
        }
        var portNode = oauth.get("callbackPort");
        if (portNode != null) {
            var port = portNode.isNumber() ? portNode.doubleValue() : Double.NaN;
            if (!(port == Math.floor(port) && !Double.isInfinite(port) && port >= 1 && port <= 65535)) {
                return "oauth.callbackPort must be a port number";
            }
        }
        var callbackNode = oauth.get("callbackUrl");
        if (callbackNode != null) {
            if (!callbackNode.isTextual() || !isLoopbackRedirectUri(callbackNode.textValue())) {
                return "oauth.callbackUrl must be an http URI on localhost, 127.0.0.1, or [::1]"
                        + " without query or fragment";
            }
            var callback = URI.create(callbackNode.textValue());
            if (callback.getPort() >= 0 && portNode != null && callback.getPort() != portNode.doubleValue()) {
                return "oauth.callbackUrl and oauth.callbackPort name different ports";
            }
        }
        if (oauth.has("scope") && !oauth.get("scope").isTextual()) {
            return "oauth.scope must be a string";
        }
        var nameNode = oauth.get("clientName");
        if (nameNode != null && (!nameNode.isTextual() || nameNode.textValue().trim().isEmpty())) {
            return "oauth.clientName must be a non-empty string";
        }
        var registrationNode = oauth.get("clientRegistration");
        if (registrationNode != null) {
            var registration = registrationNode.isTextual() ? registrationNode.textValue() : null;
            if (!"dcr".equals(registration)) {
                if (!"cimd".equals(registration)) {
                    return "oauth.clientRegistration must be \"dcr\" or \"cimd\"";
                }
                if (oauth.has("clientId") || oauth.has("clientName")) {
                    return "oauth.clientRegistration \"cimd\" cannot be combined"
                            + " with oauth.clientId or oauth.clientName";
                }
                if (callbackNode != null) {
                    var callback = URI.create(callbackNode.textValue());
                    if ("[::1]".equals(callback.getHost()) || !"/callback".equals(callback.getPath())) {
                        return "oauth.clientRegistration \"cimd\" requires oauth.callbackUrl"
                                + " on localhost or 127.0.0.1 with path /callback";
                    }
                }
            }
        }
        var metadataNode = oauth.get("authServerMetadataUrl");
        if (metadataNode != null) {
            var metadata = metadataNode.isTextual() ? parseHttpUrl(metadataNode.textValue()) : null;
            if (metadata == null
                    || (!"https".equalsIgnoreCase(metadata.getScheme()) && !isLoopbackHost(metadata.getHost()))) {
                return "oauth.authServerMetadataUrl must be an https URL,"
                        + " or http on localhost, 127.0.0.1, or [::1]";
            }
        }
        return null;
    }

    /** A copy of the entry with exposure aliases replaced by their current names ({@code :186-196}). */
    private static ObjectNode resolveExposureAliases(ObjectNode value) {
        var resolved = value.deepCopy();
        var exposure = resolved.get("exposure");
        if (exposure != null) {
            resolved.set("exposure", resolveExposureAlias(exposure));
        }
        var toolExposure = resolved.get("toolExposure");
        if (toolExposure != null && toolExposure.isObject()) {
            var tools = (ObjectNode) toolExposure;
            var names = new ArrayList<String>();
            tools.fieldNames().forEachRemaining(names::add);
            for (var tool : names) {
                tools.set(tool, resolveExposureAlias(tools.get(tool)));
            }
        }
        return resolved;
    }

    /** The current name of an alias; other values are returned unchanged ({@code :180-183}). */
    private static JsonNode resolveExposureAlias(JsonNode value) {
        if (!value.isTextual()) {
            return value;
        }
        var exposure = EXPOSURE_ALIASES.get(value.textValue());
        return exposure == null ? value : TextNode.valueOf(exposure.wire());
    }

    /** {@code ^pattern$} with {@code *} as the only wildcard ({@code :198-204}). */
    private static Pattern toolPattern(String pattern) {
        var source = Arrays.stream(pattern.split("\\*", -1))
                .map(McpServerConfigs::escapePatternPart)
                .collect(Collectors.joining(".*"));
        return Pattern.compile("^" + source + "$");
    }

    /** Escape the regex metacharacters of one literal part of a tool-name pattern. */
    private static String escapePatternPart(String part) {
        var escaped = new StringBuilder();
        for (var index = 0; index < part.length(); index++) {
            var character = part.charAt(index);
            if (REGEX_SPECIALS.indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /**
     * Parse an absolute URI; malformed and relative values give {@code null}
     * (JS {@code URL.canParse}).
     */
    private static @Nullable URI parseUrl(String value) {
        try {
            var uri = URI.create(value);
            return uri.isAbsolute() ? uri : null;
        } catch (IllegalArgumentException expected) {
            return null;
        }
    }

    /** Parse an absolute {@code http}/{@code https} URI, else {@code null}. */
    private static @Nullable URI parseHttpUrl(String value) {
        var uri = parseUrl(value);
        if (uri == null) {
            return null;
        }
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()) ? uri : null;
    }

    private static boolean isStringRecord(JsonNode node) {
        if (!node.isObject()) {
            return false;
        }
        for (var field : node.properties()) {
            if (!field.getValue().isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> stringRecord(JsonNode node) {
        var record = new LinkedHashMap<String, String>();
        for (var field : node.properties()) {
            record.put(field.getKey(), field.getValue().textValue());
        }
        return record;
    }

    private static boolean allTextual(JsonNode arrayNode) {
        for (var element : arrayNode) {
            if (!element.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String textOrNull(JsonNode node, String key) {
        var value = node.get(key);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static @Nullable Integer integerOrNull(JsonNode node, String key) {
        var value = node.get(key);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    private static McpConfigValidation invalid(String message) {
        return new McpConfigValidation.Invalid(message);
    }
}
