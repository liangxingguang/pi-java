package com.pijava.coding.agent.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CatalogPublisherPort;
import com.pijava.ai.catalog.FileModelsStore;
import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #16：{@code withPersonalizedCatalog} 的分支 —— 离线恢复、pi.dev 合并、
 * 新鲜度守卫、按 Key 收窄、持久化个性化列表。
 */
class PersonalizedCatalogProviderTest {

    private static final Instant LOCAL_GENERATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tmp;

    private final AtomicInteger userCalls = new AtomicInteger();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile int catalogStatus = 200;
    private volatile String catalogBody = "[]";
    private volatile String catalogLastModified = "Mon, 01 Jun 2026 00:00:00 GMT";
    private volatile String userBody = "{\"data\":[]}";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ModelInfo chat(String id) {
        return new ModelInfo(ModelId.of("openrouter", id), id,
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE), 1_000, 100, false,
            new PricingInfo(1, 2, 0, 0, List.of()));
    }

    private static Provider delegate(List<ModelInfo> builtin, String baseUrl) {
        var catalog = BuiltinCatalog.of(builtin);
        return new Provider() {
            @Override public String name() { return "openrouter"; }
            @Override public String displayName() { return "OpenRouter"; }
            @Override public Set<Class<? extends ProviderApi>> supportedApis() { return Set.of(); }
            @Override public <T extends ProviderApi> T createApi(
                    Class<T> apiType, ApiOptions options) {
                throw new UnsupportedOperationException();
            }
            @Override public ModelCatalog builtinModels() { return catalog; }
            @Override public String baseUrl() { return baseUrl; }
            @Override public List<ModelInfo> getModels() { return builtin; }
        };
    }

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/models/providers/openrouter", exchange -> {
            if (catalogLastModified != null) {
                exchange.getResponseHeaders().set("last-modified", catalogLastModified);
            }
            reply(exchange, catalogStatus, catalogBody);
        });
        server.createContext("/api/v1/models/user", exchange -> {
            userCalls.incrementAndGet();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if ("authorization".equalsIgnoreCase(name)) {
                    authorizations.add(String.join(",", values));
                }
            });
            reply(exchange, 200, userBody);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status,
                              String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private Provider wrap(String catalogBaseUrl, List<ModelInfo> builtin) {
        return PersonalizedCatalogProvider.wrap(
            delegate(builtin, catalogBaseUrl + "/api/v1"),
            catalogBaseUrl,
            Optional.of(LOCAL_GENERATED_AT),
            HttpClient.newHttpClient());
    }

    private static RefreshModelsContext online(Provider provider, Optional<ModelsStoreEntry> stored,
                                               CatalogPublisherPort port) {
        return RefreshModelsContext.online(stored, port, false,
            Optional.of(new RecordedCredential(AuthKind.API_KEY, "sk-or", "env:T")),
            provider.baseUrl(), AbortSignal.create());
    }

    @Test
    void offlineRestoresStoredModelsWithoutNetwork() throws Exception {
        startServer();
        var provider = wrap("http://127.0.0.1:" + server.getAddress().getPort(),
            List.of(chat("builtin")));
        var port = new TestPort();

        provider.refreshModels(RefreshModelsContext.offline(
            Optional.of(ModelsStoreEntry.of(List.of(chat("cached")))), port));

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("cached");
        assertThat(userCalls).hasValue(0);
    }

    @Test
    void withoutStoredModelsTheBuiltinCatalogStaysVisible() throws Exception {
        startServer();
        var provider = wrap("http://127.0.0.1:" + server.getAddress().getPort(),
            List.of(chat("builtin")));

        provider.refreshModels(RefreshModelsContext.offline(Optional.empty(), new TestPort()));

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("builtin");
    }

    @Test
    void onlineMergesRemoteThenPersistsThePersonalizedList() throws Exception {
        catalogBody = "[{\"id\":\"remote\",\"name\":\"Remote\","
            + "\"contextWindow\":1000,\"maxTokens\":100}]";
        userBody = "{\"data\":[{\"id\":\"remote\","
            + "\"pricing\":{\"prompt\":\"0.000009\",\"completion\":\"0.000018\"}}]}";
        var base = startServer();
        var provider = wrap(base, List.of(chat("builtin")));
        var port = new TestPort();

        provider.refreshModels(online(provider, Optional.empty(), port));

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("remote");
        assertThat(provider.getModels().getFirst().pricing().inputPrice()).isEqualTo(9);
        assertThat(port.persisted).extracting(m -> m.id().modelName())
            .containsExactly("remote");
        assertThat(authorizations).containsExactly("Bearer sk-or");
    }

    @Test
    void offlineRestoreRebuildsMetadataLostByTheStoreWire() throws Exception {
        // 远端目录为空 ⇒ merge 保留内置条目的完整元数据；根因是 store wire 子集。
        catalogBody = "[]";
        userBody = "{\"data\":[{\"id\":\"claude-lane\"}]}";
        var base = startServer();
        var builtin = new ModelInfo(
            ModelId.of("openrouter", "claude-lane"), "Claude Lane",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE),
            1_000, 100, false,
            new PricingInfo(1, 2, 0.1, 0.2,
                List.of(new PricingInfo.CostTier(1_000, 3, 4, 5, 6))),
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            Map.of("x-test", "1"), Map.of(),
            com.pijava.ai.catalog.ModelCompat.NONE,
            "anthropic-messages", "https://openrouter.ai/api",
            null, new InputLimits(123, null), Map.of());
        var provider = wrap(base, List.of(builtin));
        var port = new TestPort();
        provider.refreshModels(online(provider, Optional.empty(), port));

        // 真实 store 往返：旧存储只保留了 wire 子集。
        var store = new FileModelsStore(tmp);
        store.write("openrouter", ModelsStoreEntry.of(port.persisted));
        var stored = store.read("openrouter").orElseThrow();

        var restarted = wrap(base, List.of(builtin));
        restarted.refreshModels(RefreshModelsContext.offline(Optional.of(stored), new TestPort()));

        var restored = restarted.getModels().getFirst();
        assertThat(restored.api()).isEqualTo("anthropic-messages");
        assertThat(restored.baseUrl()).isEqualTo("https://openrouter.ai/api");
        assertThat(restored.headers()).containsEntry("x-test", "1");
        assertThat(restored.inputLimits().maxRequestBytes()).isEqualTo(123);
        assertThat(restored.pricing().tiers()).hasSize(1);
    }

    @Test
    void staleRemoteCatalogIsIgnored() throws Exception {
        catalogLastModified = "Wed, 01 Jan 2025 00:00:00 GMT";
        catalogBody = "[{\"id\":\"remote\",\"name\":\"Remote\","
            + "\"contextWindow\":1000,\"maxTokens\":100}]";
        userBody = "{\"data\":[{\"id\":\"builtin\"}]}";
        var base = startServer();
        var provider = wrap(base, List.of(chat("builtin")));

        provider.refreshModels(online(provider, Optional.empty(), new TestPort()));

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("builtin");
    }

    @Test
    void a404CatalogStillPersonalizesTheBuiltinModels() throws Exception {
        catalogStatus = 404;
        userBody = "{\"data\":[{\"id\":\"builtin\"}]}";
        var base = startServer();
        var provider = wrap(base, List.of(chat("builtin")));
        var port = new TestPort();

        provider.refreshModels(online(provider, Optional.empty(), port));

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("builtin");
        assertThat(port.persisted).isNotNull();
    }

    @Test
    void aFailingCatalogFailsLoudly() throws Exception {
        catalogStatus = 500;
        var base = startServer();
        var provider = wrap(base, List.of(chat("builtin")));

        assertThatThrownBy(() -> provider.refreshModels(
            online(provider, Optional.empty(), new TestPort())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Model catalog request failed for openrouter: 500");
        assertThat(userCalls).hasValue(0);
    }

    @Test
    void anAbortedSignalRestoresButNeverGoesOnline() throws Exception {
        var base = startServer();
        var provider = wrap(base, List.of(chat("builtin")));
        var port = new TestPort();
        var signal = AbortSignal.create();
        signal.abort();

        provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false,
            Optional.of(new RecordedCredential(AuthKind.API_KEY, "sk-or", "env:T")),
            provider.baseUrl(), signal));

        assertThat(userCalls).hasValue(0);
        assertThat(port.persisted).isNull();
        assertThat(port.publications).hasSize(1);
    }

    /** pi {@code publishProviderModels} 的等价物。 */
    private static final class TestPort implements CatalogPublisherPort {
        final List<ModelsPublication> publications = new ArrayList<>();
        List<ModelInfo> persisted;

        @Override
        public boolean publish(ModelsPublication publication) {
            publications.add(publication);
            publication.persist().ifPresent(entry -> persisted = entry.models());
            publication.update().run();
            return true;
        }
    }
}
