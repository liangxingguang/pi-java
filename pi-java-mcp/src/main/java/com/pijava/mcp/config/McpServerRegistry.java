package com.pijava.mcp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpJson;

/**
 * Servers registered by the extensions of one runtime
 * (pi {@code McpServerRegistry}, {@code mcp-servers.ts:288-319}).
 *
 * <p>A registration carries the path of the extension that made it, and only that extension can
 * unregister the server: a name another extension owns is left alone, silently.</p>
 */
public final class McpServerRegistry {

    /** Registration order, which is the order {@link #list()} hands back. */
    private final Map<String, RegisteredMcpServer> servers = new LinkedHashMap<>();
    private @Nullable Runnable changeListener;

    /** Register or replace a server ({@code mcp-servers.ts:293-296}). */
    public void register(RegisteredMcpServer server) {
        servers.put(server.name(), server);
        changed();
    }

    /**
     * Remove a server registered by {@code extensionPath}
     * ({@code mcp-servers.ts:298-303}). Servers of other extensions are left alone.
     *
     * @param name the server name
     * @param extensionPath the path of the extension asking to remove it
     */
    public void unregister(String name, String extensionPath) {
        var existing = servers.get(name);
        if (existing == null || !existing.extensionPath().equals(extensionPath)) {
            return;
        }
        servers.remove(name);
        changed();
    }

    /**
     * One server by name.
     *
     * @param name the server name
     * @return the registration, or {@code null}
     */
    public @Nullable RegisteredMcpServer get(String name) {
        return servers.get(name);
    }

    /**
     * Copies of the registered servers, in registration order
     * ({@code mcp-servers.ts:309-311}).
     *
     * @return the registrations, with their configurations copied
     */
    public List<RegisteredMcpServer> list() {
        var out = new ArrayList<RegisteredMcpServer>(servers.size());
        for (var server : servers.values()) {
            out.add(new RegisteredMcpServer(server.name(), copyOf(server.config()),
                    server.extensionPath()));
        }
        return List.copyOf(out);
    }

    /**
     * A copy of a configuration. The switch is over the two variants because
     * {@link McpServerConfig} is a sealed interface with no type discriminator: its {@code type}
     * component is a configuration field of its own, so Jackson cannot pick a variant to build.
     */
    private static McpServerConfig copyOf(McpServerConfig config) {
        return switch (config) {
            case McpServerConfig.Stdio stdio ->
                    McpJson.mapper().convertValue(stdio, McpServerConfig.Stdio.class);
            case McpServerConfig.Http http ->
                    McpJson.mapper().convertValue(http, McpServerConfig.Http.class);
        };
    }

    /**
     * Set the listener called after every change.
     *
     * @param listener the listener, or {@code null} to detach it
     */
    public void setChangeListener(@Nullable Runnable listener) {
        this.changeListener = listener;
    }

    private void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }
}
