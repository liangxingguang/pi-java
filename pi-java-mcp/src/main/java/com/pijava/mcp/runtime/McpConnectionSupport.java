package com.pijava.mcp.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpClient;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.protocol.jsonrpc.JsonRpcErrorCode;
import com.pijava.mcp.protocol.jsonrpc.McpError;
import com.pijava.mcp.transport.http.McpHttpError;

/**
 * The pieces of {@link McpServerConnection} that do not need its state
 * (pi {@code runtime.ts} free functions).
 */
final class McpConnectionSupport {

    private McpConnectionSupport() {
    }

    /** Something that may fail. */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Something that produces a value and may fail. */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Run a blocking piece of work on its own virtual thread. */
    static CompletableFuture<Void> onWorker(ThrowingRunnable action) {
        var future = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    /** Produce a value on its own virtual thread. */
    static <T> CompletableFuture<T> onWorker(ThrowingSupplier<T> action) {
        var future = new CompletableFuture<T>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    /** Wait for a future, ignoring how it ended. */
    static void awaitQuietly(@Nullable Future<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException ignored) {
            // The caller wants a fresh connection either way.
        }
    }

    /** Peel the wrappers a {@link CompletableFuture} adds. */
    static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Rethrow a failure as a checked exception. */
    static Exception asException(Throwable failure) {
        return failure instanceof Exception checked ? checked : new IllegalStateException(failure);
    }

    /** A failure's message, or its string form when it has none. */
    static String errorMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    /**
     * Network failures and overloaded or restarting servers, worth another attempt
     * ({@code runtime.ts:68-74}).
     */
    static boolean isTransientError(Throwable failure) {
        if (failure instanceof McpHttpError http) {
            return http.status() == 408 || http.status() == 429
                    || (http.status() >= 500 && http.status() != 501);
        }
        // pi tests for a TypeError, which is what a failed fetch throws. The JDK throws an
        // IOException family instead, so the set is wider (TLS, interruption, redirect policy).
        return failure instanceof IOException;
    }

    /** HTTP servers authenticate with OAuth unless the config supplies an Authorization header. */
    static boolean usesOAuth(McpServerEntry entry) {
        if (!(entry.config() instanceof McpServerConfig.Http http) || http.auth() != null) {
            return false;
        }
        var headers = http.headers();
        if (headers == null) {
            return true;
        }
        return headers.keySet().stream()
                .noneMatch(header -> "authorization".equals(header.toLowerCase(Locale.ROOT)));
    }

    /** {@code runtime.ts:76-79}. */
    static String signInRequiredMessage(McpServerEntry entry) {
        var provider = entry.config() instanceof McpServerConfig.Http http && http.auth() != null
                ? http.auth().provider() : null;
        return "MCP server \"" + entry.name() + "\" requires sign-in. Run "
                + (provider == null ? "/mcp" : "/login " + provider) + " to sign in.";
    }

    /** {@code pathToFileURL(cwd).href}, which adds no trailing slash the way {@link Path#toUri} does. */
    static String fileUrl(Path path) {
        var uri = path.toAbsolutePath().toUri().toString();
        return uri.endsWith("/") && uri.length() > "file:///".length()
                ? uri.substring(0, uri.length() - 1) : uri;
    }

    /** {@code basename(cwd)}. */
    static String baseName(Path path) {
        var name = path.toAbsolutePath().normalize().getFileName();
        return name == null ? "" : name.toString();
    }

    /** {@code null} for a missing or empty string. */
    static @Nullable String nullIfEmpty(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Servers that do not implement {@code resources/templates/list} have no templates. */
    static <T> CompletableFuture<T> withoutTemplates(
            Supplier<CompletableFuture<T>> list, T empty) {
        return list.get().handle((value, failure) -> {
            if (failure == null) {
                return CompletableFuture.completedFuture(value);
            }
            var cause = unwrap(failure);
            if (cause instanceof McpError mcp && mcp.code() == JsonRpcErrorCode.METHOD_NOT_FOUND.code()) {
                return CompletableFuture.completedFuture(empty);
            }
            return CompletableFuture.<T>failedFuture(cause);
        }).thenCompose(result -> result);
    }

    /** {@code listTemplates} ({@code runtime.ts:133-135}). */
    static List<ResourceTemplate> listTemplates(McpClient client, McpRequestOptions requestOptions)
            throws Exception {
        try {
            return client.listResourceTemplates(requestOptions).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            var cause = unwrap(failure);
            if (cause instanceof McpError mcp && mcp.code() == JsonRpcErrorCode.METHOD_NOT_FOUND.code()) {
                return List.of();
            }
            throw asException(cause);
        }
    }

    /**
     * Resources and templates at connect time, for the counts in {@code /mcp} and
     * {@code pi mcp list} ({@code runtime.ts:137-152}).
     *
     * <p>A server whose lists fail still connects: the resource tools list and read its
     * resources on demand.</p>
     */
    static ResourceLists fetchResources(McpClient client) {
        var resources = new ArrayList<Resource>();
        try {
            resources.addAll(client.listResources(McpRequestOptions.none()).get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // The list is best effort.
        }
        var templates = new ArrayList<ResourceTemplate>();
        try {
            templates.addAll(listTemplates(client, McpRequestOptions.none()));
        } catch (Exception ignored) {
            // The list is best effort.
        }
        return new ResourceLists(
                resources.stream().filter(resource -> !McpAppResources.isMcpAppResource(resource)).toList(),
                templates.stream().filter(template -> !McpAppResources.isMcpAppResource(template)).toList());
    }

    /** What one {@code fetchResources} found ({@code runtime.ts:141-143}). */
    record ResourceLists(List<Resource> resources, List<ResourceTemplate> templates) {
    }

    /** A provider that reads a pi provider's token on every request ({@code runtime.ts:213-216}). */
    record ReadThroughProvider(String provider, @Nullable McpProviderToken source)
            implements McpAuthProvider {

        @Override
        public @Nullable String token() throws Exception {
            return source == null ? null : source.token(provider);
        }

        @Override
        public void settled() {
            // Nothing is stored, so nothing has to be flushed.
        }
    }
}
