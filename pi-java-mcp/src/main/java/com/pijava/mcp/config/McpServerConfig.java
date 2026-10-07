package com.pijava.mcp.config;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One server of the {@code mcpServers} shape ({@code mcp-servers.ts:115}); the shared
 * fields are {@code mcp-servers.ts:24-43}.
 *
 * <p>Unlike {@code McpContentBlock} this union carries <b>no</b> {@code @JsonTypeInfo}:
 * its {@code type} component is a configuration field of its own, and a type
 * discriminator would emit a second key.</p>
 */
public sealed interface McpServerConfig permits McpServerConfig.Stdio, McpServerConfig.Http {

    /** Default {@code codemode} ({@code :26}). */
    @Nullable McpExposure exposure();

    /** What the server offers, in a sentence ({@code :31}). */
    @Nullable String description();

    /**
     * Exposure of single tools, overriding {@link #exposure()} ({@code :38}).
     * Keys are tool names as the server offers them, or patterns where {@code *}
     * matches any characters; the order is the file's order and decides which
     * pattern matches first ({@code :211}).
     */
    @Nullable Map<String, McpExposure> toolExposure();

    /** {@code false} keeps the entry without connecting; default {@code true} ({@code :40}). */
    @Nullable Boolean enabled();

    /** Per-request timeout in seconds; default 60 ({@code :41}). */
    @Nullable Number timeout();

    /** A server started as a child process ({@code :45-53}). */
    record Stdio(
            @Nullable String type,
            @Nullable McpExposure exposure,
            @Nullable String description,
            @Nullable Map<String, McpExposure> toolExposure,
            @Nullable Boolean enabled,
            @Nullable Number timeout,
            String command,
            @Nullable List<String> args,
            @Nullable Map<String, String> env,
            @Nullable String cwd) implements McpServerConfig {
    }

    /** A streamable HTTP server ({@code :102-113}). */
    record Http(
            @Nullable String type,
            @Nullable McpExposure exposure,
            @Nullable String description,
            @Nullable Map<String, McpExposure> toolExposure,
            @Nullable Boolean enabled,
            @Nullable Number timeout,
            String url,
            @Nullable Map<String, String> headers,
            @Nullable OAuth oauth,
            @Nullable Auth auth) implements McpServerConfig {
    }

    /** OAuth client settings for servers without dynamic client registration ({@code :56-91}). */
    record OAuth(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @Nullable Integer callbackPort,
            @Nullable String callbackUrl,
            @Nullable String scope,
            @Nullable String clientName,
            @Nullable String clientRegistration,
            @Nullable String authServerMetadataUrl) {
    }

    /** {@code auth?: { provider }} ({@code :112}). */
    record Auth(String provider) {
    }
}
