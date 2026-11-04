package com.pijava.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.mcp.McpJson;

/**
 * Loading and editing of the MCP configuration ({@code config.ts:91-242}).
 *
 * <p>Servers are read from {@code mcp.json} in the agent directory and, for trusted
 * projects, from {@code <cwd>/.pi-java/mcp.json}. Project entries replace global entries
 * with the same name; an entry without {@code command}, {@code url}, or {@code type}
 * overrides only {@code enabled}, {@code exposure}, and {@code toolExposure}.</p>
 */
public final class McpConfig {

    /**
     * The project configuration directory ({@code config.ts:542} reads it from
     * {@code package.json}; pi-java uses {@code .pi-java} everywhere).
     */
    public static final String PROJECT_DIR_NAME = ".pi-java";

    /** The only keys a project entry may override ({@code config.ts:74}). */
    private static final List<String> OVERRIDE_KEYS = List.of("enabled", "exposure", "toolExposure");

    /**
     * UTF-8 byte order mark. Written as an escape so no invisible character sits in
     * the source (the same reason {@code SettingsManager} escapes it).
     */
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private McpConfig() {
    }

    /**
     * Load the global and, when the project is trusted, the project configuration
     * ({@code config.ts:145-156}). Disabled servers are included with
     * {@code enabled: false}, so they can be enabled again.
     *
     * @param agentDir       directory holding the global {@code mcp.json}
     * @param cwd            session working directory
     * @param projectTrusted whether {@code <cwd>/.pi-java/mcp.json} is read
     */
    public static LoadedMcpConfig load(Path agentDir, Path cwd, boolean projectTrusted) {
        var state = new LoaderState();
        readConfigFile(agentDir.resolve("mcp.json"), McpScope.GLOBAL, state);
        Path projectConfig = projectTrusted ? cwd.resolve(PROJECT_DIR_NAME).resolve("mcp.json") : null;
        if (projectConfig != null) {
            readConfigFile(projectConfig, McpScope.PROJECT, state);
        }
        return new LoadedMcpConfig(List.copyOf(state.servers.values()), state.autoEnableCodemode,
                List.copyOf(state.errors), projectConfig);
    }

    /**
     * Change one server's settings in the {@code mcp.json} that defines or overrides it
     * ({@code config.ts:169-193}). {@code enabled: true} and {@code exposure: "codemode"}
     * are the defaults and remove the key, unless the entry is an override, which keeps
     * default values since it replaces the global server's.
     *
     * @param path  the configuration file to edit
     * @param name  server name
     * @param patch the settings to change
     */
    public static void update(Path path, String name, McpConfigPatch patch) {
        update(path, name, patch, false);
    }

    /**
     * Change one server's settings; with {@code override}, a missing entry is added as an
     * override entry.
     *
     * @param path     the configuration file to edit
     * @param name     server name
     * @param patch    the settings to change
     * @param override add a missing entry as an override
     */
    public static void update(Path path, String name, McpConfigPatch patch, boolean override) {
        editMcpServers(path, (servers, parsed) -> {
            var server = servers == null ? null : asObject(servers.get(name));
            if (server == null && override) {
                server = McpJson.mapper().createObjectNode();
                parsed.withObject("/mcpServers").set(name, server);
            }
            if (server == null) {
                throw new McpConfigError(path + " does not define MCP server \"" + name + "\"");
            }
            var keepDefaults = isOverride(server);
            if (patch.enabled() != null) {
                if (patch.enabled() && !keepDefaults) {
                    server.remove("enabled");
                } else {
                    server.put("enabled", patch.enabled());
                }
            }
            if (patch.exposure() != null) {
                if (patch.exposure() == McpExposure.CODEMODE && !keepDefaults) {
                    server.remove("exposure");
                } else {
                    server.put("exposure", patch.exposure().wire());
                }
            }
            return true;
        });
    }

    /**
     * Add a server to a configuration file, creating the file when missing
     * ({@code config.ts:199-209}).
     *
     * @param path   the configuration file to edit
     * @param name   server name
     * @param config the validated configuration to write
     * @return whether an entry with that name was replaced
     */
    public static boolean add(Path path, String name, McpServerConfig config) {
        var replaced = new AtomicBoolean();
        editMcpServers(path, (servers, parsed) -> {
            var target = servers == null ? McpJson.mapper().createObjectNode() : servers;
            replaced.set(target.has(name));
            target.set(name, writeServerConfig(config));
            parsed.set("mcpServers", target);
            return true;
        });
        return replaced.get();
    }

    /**
     * Write one entry in the key order pi's CLI produces ({@code cli.ts:340-394}): the transport
     * keys, {@code exposure}, {@code description}, then the remaining known keys in a fixed order
     * ({@code mcp-servers.ts:24-113}). The CLI itself sets no remaining keys; writing them keeps
     * the other callers (Radius) deterministic.
     */
    private static ObjectNode writeServerConfig(McpServerConfig config) {
        var out = McpJson.mapper().createObjectNode();
        var type = switch (config) {
            case McpServerConfig.Http http -> http.type();
            case McpServerConfig.Stdio stdio -> stdio.type();
        };
        if (config instanceof McpServerConfig.Http http) {
            out.put("url", http.url());
            if (http.headers() != null && !http.headers().isEmpty()) {
                out.set("headers", McpJson.mapper().valueToTree(http.headers()));
            }
            if (http.oauth() != null && !isEmpty(http.oauth())) {
                out.set("oauth", writeOAuth(http.oauth()));
            }
        } else if (config instanceof McpServerConfig.Stdio stdio) {
            out.put("command", stdio.command());
            if (stdio.args() != null && !stdio.args().isEmpty()) {
                out.set("args", McpJson.mapper().valueToTree(stdio.args()));
            }
            if (stdio.env() != null && !stdio.env().isEmpty()) {
                out.set("env", McpJson.mapper().valueToTree(stdio.env()));
            }
            if (stdio.cwd() != null) {
                out.put("cwd", stdio.cwd());
            }
        }
        if (config.exposure() != null) {
            out.put("exposure", config.exposure().wire());
        }
        if (config.description() != null) {
            out.put("description", config.description());
        }
        if (type != null) {
            out.put("type", type);
        }
        if (config.toolExposure() != null) {
            out.set("toolExposure", McpJson.mapper().valueToTree(config.toolExposure()));
        }
        if (config.enabled() != null) {
            out.put("enabled", config.enabled());
        }
        if (config.timeout() != null) {
            out.set("timeout", McpJson.mapper().valueToTree(config.timeout()));
        }
        if (config instanceof McpServerConfig.Http http && http.auth() != null) {
            out.set("auth", McpJson.mapper().valueToTree(http.auth()));
        }
        return out;
    }

    /** The OAuth settings in the schema's field order ({@code mcp-servers.ts:80-100}). */
    private static ObjectNode writeOAuth(McpServerConfig.OAuth oauth) {
        var out = McpJson.mapper().createObjectNode();
        if (oauth.clientId() != null) {
            out.put("clientId", oauth.clientId());
        }
        if (oauth.clientSecret() != null) {
            out.put("clientSecret", oauth.clientSecret());
        }
        if (oauth.callbackPort() != null) {
            out.put("callbackPort", oauth.callbackPort());
        }
        if (oauth.callbackUrl() != null) {
            out.put("callbackUrl", oauth.callbackUrl());
        }
        if (oauth.scope() != null) {
            out.put("scope", oauth.scope());
        }
        if (oauth.clientName() != null) {
            out.put("clientName", oauth.clientName());
        }
        if (oauth.clientRegistration() != null) {
            out.put("clientRegistration", oauth.clientRegistration());
        }
        if (oauth.authServerMetadataUrl() != null) {
            out.put("authServerMetadataUrl", oauth.authServerMetadataUrl());
        }
        return out;
    }

    /** Whether every OAuth field is unset, so the CLI writes no {@code oauth} object at all. */
    private static boolean isEmpty(McpServerConfig.OAuth oauth) {
        return oauth.clientId() == null && oauth.clientSecret() == null && oauth.callbackPort() == null
                && oauth.callbackUrl() == null && oauth.scope() == null && oauth.clientName() == null
                && oauth.clientRegistration() == null && oauth.authServerMetadataUrl() == null;
    }

    /**
     * Remove a server from a configuration file ({@code config.ts:212-222}).
     *
     * @param path the configuration file to edit
     * @param name server name
     * @return whether the file defined the server
     */
    public static boolean remove(Path path, String name) {
        if (!Files.exists(path)) {
            return false;
        }
        var removed = new AtomicBoolean();
        editMcpServers(path, (servers, parsed) -> {
            if (servers == null || !servers.has(name)) {
                return false;
            }
            servers.remove(name);
            removed.set(true);
            return true;
        });
        return removed.get();
    }

    /** Read one {@code mcp.json}; a missing file is not an error ({@code config.ts:91-139}). */
    private static void readConfigFile(Path path, McpScope scope, LoaderState state) {
        if (!Files.exists(path)) {
            return;
        }
        JsonNode parsed;
        try {
            parsed = McpJson.mapper().readTree(stripBom(Files.readString(path)));
        } catch (IOException | RuntimeException error) {
            state.errors.add(path + ": " + error.getMessage());
            return;
        }
        if (!parsed.isObject() || (parsed.has("mcpServers") && !parsed.get("mcpServers").isObject())) {
            state.errors.add(path + ": expected an object with an \"mcpServers\" object");
            return;
        }
        var autoEnable = parsed.get("autoEnableCodemode");
        if (autoEnable != null && autoEnable.isBoolean()) {
            state.autoEnableCodemode = autoEnable.booleanValue();
        } else if (autoEnable != null) {
            state.errors.add(path + ": autoEnableCodemode must be a boolean");
        }
        var servers = parsed.get("mcpServers");
        if (servers == null || !servers.isObject()) {
            return;
        }
        for (var field : servers.properties()) {
            readServer(path, scope, field.getKey(), field.getValue(), state);
        }
    }

    /** One entry of an {@code mcp.json} ({@code config.ts:107-138}). */
    private static void readServer(Path path, McpScope scope, String name, JsonNode value, LoaderState state) {
        if (scope == McpScope.PROJECT && value.isObject() && isOverride((ObjectNode) value)) {
            applyOverride(path, name, (ObjectNode) value, state);
            return;
        }
        var validation = McpServerConfigs.validate(name, value);
        if (validation instanceof McpConfigValidation.Invalid invalid) {
            state.errors.add(path + ": " + invalid.message());
            return;
        }
        var config = ((McpConfigValidation.Valid) validation).config();
        // Names that differ only in `-` and `_` would share a namespace.
        var clash = state.servers.keySet().stream()
                .filter(other -> !other.equals(name))
                .filter(other -> McpServerConfigs.namespace(other).equals(McpServerConfigs.namespace(name)))
                .findFirst().orElse(null);
        if (clash != null) {
            state.errors.add(path + ": server \"" + name + "\" conflicts with \"" + clash + "\"");
            return;
        }
        // A project file must not point a provider credential at its own URL.
        if (scope == McpScope.PROJECT && config instanceof McpServerConfig.Http http && http.auth() != null) {
            state.errors.add(path + ": server \"" + name + "\": auth is only allowed in the global mcp.json");
            return;
        }
        state.servers.put(name, new McpServerEntry(name, config, path, scope, null));
    }

    /**
     * A project entry that overrides {@code enabled}, {@code exposure}, or {@code toolExposure}
     * ({@code config.ts:108-121}).
     */
    private static void applyOverride(Path path, String name, ObjectNode value, LoaderState state) {
        var base = state.servers.get(name);
        var extra = new ArrayList<String>();
        for (var field : value.properties()) {
            if (!OVERRIDE_KEYS.contains(field.getKey())) {
                extra.add(field.getKey());
            }
        }
        if (base == null) {
            state.errors.add(path + ": server \"" + name + "\" needs \"command\" or \"url\","
                    + " or a global server to override");
            return;
        }
        if (!extra.isEmpty()) {
            state.errors.add(path + ": server \"" + name + "\": an override can only set"
                    + " enabled, exposure, toolExposure");
            return;
        }
        var merged = McpJson.mapper().valueToTree(base.config());
        ((ObjectNode) merged).setAll(value);
        var validation = McpServerConfigs.validate(name, merged);
        if (validation instanceof McpConfigValidation.Invalid invalid) {
            state.errors.add(path + ": " + invalid.message());
            return;
        }
        // The override keeps the entry's source and scope, and remembers the file that overrode it.
        state.servers.put(name, new McpServerEntry(name, ((McpConfigValidation.Valid) validation).config(),
                base.source(), base.scope(), path));
    }

    /** Whether an entry overrides a server defined elsewhere instead of defining one ({@code config.ts:77-79}). */
    private static boolean isOverride(ObjectNode value) {
        return !value.has("command") && !value.has("url") && !value.has("type");
    }

    /**
     * Read a configuration file, let {@code edit} change its {@code mcpServers}, and write it back
     * ({@code config.ts:228-242}).
     */
    private static void editMcpServers(Path path, Edit edit) {
        String text = null;
        if (Files.exists(path)) {
            try {
                text = stripBom(Files.readString(path));
            } catch (IOException error) {
                throw new McpConfigError(path + ": " + error.getMessage(), error);
            }
        }
        JsonNode parsed;
        try {
            parsed = text == null ? McpJson.mapper().createObjectNode() : McpJson.mapper().readTree(text);
        } catch (IOException error) {
            throw new McpConfigError(path + ": " + error.getMessage(), error);
        }
        if (!parsed.isObject() || (parsed.has("mcpServers") && !parsed.get("mcpServers").isObject())) {
            throw new McpConfigError(path + ": expected an object with an \"mcpServers\" object");
        }
        var root = (ObjectNode) parsed;
        var servers = root.get("mcpServers") instanceof ObjectNode object ? object : null;
        if (!edit.apply(servers, root)) {
            return;
        }
        try {
            var parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, JsJson.stringify(root, JsJson.detectIndent(text)) + "\n");
        } catch (IOException error) {
            throw new McpConfigError(path + ": " + error.getMessage(), error);
        }
    }

    /** Drop a leading UTF-8 BOM, which {@code JSON.parse} rejects ({@code config.ts:92-100,229-232}). */
    private static String stripBom(String text) {
        return text.startsWith(BOM) ? text.substring(BOM.length()) : text;
    }

    private static @Nullable ObjectNode asObject(@Nullable JsonNode node) {
        return node instanceof ObjectNode object ? object : null;
    }

    /** The change one {@link #editMcpServers} call applies; {@code false} leaves the file untouched. */
    @FunctionalInterface
    private interface Edit {

        /** @param servers the {@code mcpServers} object, or {@code null} when the file has none */
        boolean apply(@Nullable ObjectNode servers, ObjectNode parsed);
    }

    /** The state one {@link #load} call accumulates. */
    private static final class LoaderState {
        /** Insertion-ordered, so a replaced entry keeps its position. */
        private final Map<String, McpServerEntry> servers = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private @Nullable Boolean autoEnableCodemode;
    }
}
