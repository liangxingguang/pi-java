package com.pijava.mcp.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.mcp.McpJson;
import com.pijava.mcp.config.JsJson;
import com.pijava.mcp.config.McpServerConfigs;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthTokens;

/**
 * Per-server OAuth state — client registration, tokens, the pending PKCE verifier — kept in
 * {@code <agent-dir>/mcp-auth.json}, keyed by server name and URL
 * (pi {@code McpOAuthCredentialStore}, {@code oauth.ts:137-221}).
 *
 * <p>A refresh lock file per server keeps two processes from refreshing with the same rotating
 * token ({@code oauth.ts:168-193}).</p>
 */
public final class McpOAuthCredentialStore {

    /** pi uses {@code JSON.stringify(states, null, 2)} plus a trailing newline ({@code oauth.ts:120}). */
    private static final String INDENT = "  ";

    /** Three states that are absent are omitted, the way JS omits {@code undefined}. */
    private static final ObjectMapper STATES = McpJson.mapper().copy()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final long REFRESH_LOCK_WAIT_MS = 25_000;
    private static final long REFRESH_LOCK_RETRY_MS = 100;

    private final AuthJsonBackend backend;
    /** Directory for the refresh lock files; without one, refreshes are only serialized in this process. */
    private final @Nullable Path lockDir;

    /**
     * Create the store pi uses by default.
     *
     * @param agentDir the agent directory holding {@code mcp-auth.json} and the lock files
     */
    public McpOAuthCredentialStore(Path agentDir) {
        this(new FileAuthJsonBackend(agentDir.resolve("mcp-auth.json")), agentDir);
    }

    /**
     * Create a store over an injected backend.
     *
     * @param backend where the state is kept
     * @param lockDir directory for the refresh lock files, or {@code null} for none
     */
    public McpOAuthCredentialStore(AuthJsonBackend backend, @Nullable Path lockDir) {
        this.backend = backend;
        this.lockDir = lockDir;
    }

    /**
     * One server's view of the store.
     *
     * @param name the MCP server name
     * @param serverUrl the MCP server URL
     * @return the per-server store
     */
    public McpOAuthServerStore forServer(String name, String serverUrl) {
        var keys = storeKeys(name, serverUrl);
        return new ServerStore(keys.key(), keys.legacyKey());
    }

    /**
     * The stored tokens of a server, for noticing sign-ins done by another process.
     * Does not take over legacy state ({@code oauth.ts:196-200}).
     *
     * @param name the MCP server name
     * @param serverUrl the MCP server URL
     * @return the tokens, or {@code null}
     */
    public @Nullable OAuthTokens tokens(String name, String serverUrl) {
        var keys = storeKeys(name, serverUrl);
        var state = read((states) -> states.containsKey(keys.key()) ? states.get(keys.key())
                : states.get(keys.legacyKey()));
        return state == null ? null : state.tokens();
    }

    /**
     * Remove a server's credentials. Removes legacy state the server would take over
     * ({@code oauth.ts:202-212}).
     *
     * @param name the MCP server name
     * @param serverUrl the MCP server URL
     * @return whether credentials were stored
     */
    public boolean remove(String name, String serverUrl) {
        var keys = storeKeys(name, serverUrl);
        return backend.withLock(current -> {
            var states = parseStates(current);
            var stored = states.containsKey(keys.key()) ? keys.key()
                    : states.containsKey(keys.legacyKey()) ? keys.legacyKey() : null;
            if (stored == null) {
                return AuthJsonBackend.LockResult.of(false);
            }
            states.remove(stored);
            return AuthJsonBackend.LockResult.writing(true, serializeStates(states));
        });
    }

    /** Keys of a server's state ({@code oauth.ts:123-130}). */
    private static Keys storeKeys(String name, String serverUrl) {
        var legacyKey = WebUrls.normalize(serverUrl);
        return new Keys(McpServerConfigs.namespace(name) + "|" + legacyKey, legacyKey);
    }

    private <T> T read(Function<Map<String, McpOAuthState>, T> pick) {
        return backend.withLock(current -> AuthJsonBackend.LockResult.of(pick.apply(parseStates(current))));
    }

    private void write(Consumer<Map<String, McpOAuthState>> update) {
        backend.withLock(current -> {
            var states = parseStates(current);
            update.accept(states);
            return AuthJsonBackend.LockResult.writing(null, serializeStates(states));
        });
    }

    private static Map<String, McpOAuthState> parseStates(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return new LinkedHashMap<>();
        }
        JsonNode parsed;
        try {
            parsed = McpJson.mapper().readTree(content);
        } catch (JsonProcessingException error) {
            return new LinkedHashMap<>();
        }
        if (parsed == null || !parsed.isObject()) {
            return new LinkedHashMap<>();
        }
        return McpJson.mapper().convertValue(parsed,
                McpJson.mapper().getTypeFactory().constructMapType(LinkedHashMap.class,
                        String.class, McpOAuthState.class));
    }

    private static String serializeStates(Map<String, McpOAuthState> states) {
        var node = STATES.createObjectNode();
        for (var entry : states.entrySet()) {
            node.set(entry.getKey(), STATES.valueToTree(entry.getValue()));
        }
        return JsJson.stringify(node, INDENT) + "\n";
    }

    /** A lock file per server, held from reading the tokens to saving new ones. */
    private <T> T withRefreshLock(String key, Callable<T> action) throws Exception {
        if (lockDir == null) {
            return action.call();
        }
        Files.createDirectories(lockDir);
        var lockPath = lockDir.resolve("mcp-auth-refresh-" + shortHash(key));
        try (var channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = acquireRefreshLock(channel)) {
            return action.call();
        }
    }

    private static String shortHash(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    /**
     * Wait for the refresh lock. pi's lockfile goes stale after 20 s without a renewal; an OS
     * file lock is released when the holder dies, so it needs no staleness rule.
     */
    private static FileLock acquireRefreshLock(FileChannel channel) throws IOException {
        var attempts = REFRESH_LOCK_WAIT_MS / REFRESH_LOCK_RETRY_MS;
        for (var attempt = 0; ; attempt++) {
            try {
                var lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException expected) {
                // Contention inside this JVM; the caller's single-flight refresh handles it.
            }
            if (attempt + 1 >= attempts) {
                throw new IOException("Timed out waiting for the MCP OAuth refresh lock");
            }
            try {
                Thread.sleep(REFRESH_LOCK_RETRY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the MCP OAuth refresh lock",
                        interrupted);
            }
        }
    }

    /** A server's two keys ({@code oauth.ts:127-130}). */
    private record Keys(String key, String legacyKey) {
    }

    /** One server's view; the legacy key is taken over by the first server to load it. */
    private final class ServerStore implements McpOAuthServerStore {

        private final String key;
        private final String legacyKey;

        private ServerStore(String key, String legacyKey) {
            this.key = key;
            this.legacyKey = legacyKey;
        }

        @Override
        public @Nullable McpOAuthState load() {
            return backend.withLock(current -> {
                var states = parseStates(current);
                if (states.containsKey(key) || !states.containsKey(legacyKey)) {
                    return AuthJsonBackend.LockResult.of(states.get(key));
                }
                states.put(key, states.remove(legacyKey));
                return AuthJsonBackend.LockResult.writing(states.get(key), serializeStates(states));
            });
        }

        @Override
        public void save(McpOAuthState state) {
            write(states -> states.put(key, state));
        }

        @Override
        public <T> T withRefreshLock(Callable<T> action) throws Exception {
            return McpOAuthCredentialStore.this.withRefreshLock(key, action);
        }
    }

}
