package com.pijava.coding.agent.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CatalogPublisherPort;
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

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 原 docs/70 §1.3／§9 step 3+4：{@code RemoteCatalogProvider} 的全分支 ——
 * 条件 GET、三形态解析、generatedAt 守卫、TTL、304／404／501／transient／200。
 */
class RemoteCatalogProviderTest {

    private static final Instant LOCAL_GENERATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REMOTE_NEWER = Instant.parse("2026-06-01T00:00:00Z");

    private static final ModelInfo STATIC_MODEL = model("static-model", "Static Model");
    private static final ModelInfo CACHED_MODEL = model("cached-model", "Cached Model");

    // ── 夹具 ──────────────────────────────────────────────────

    private static ModelInfo model(String name, String display) {
        return new ModelInfo(
            ModelId.of("static-test", name), display,
            Set.of(ModelCapability.TEXT), 1_000, 100, false,
            new PricingInfo(1.0, 2.0));
    }

    private static Provider staticProvider(List<ModelInfo> models) {
        var catalog = BuiltinCatalog.of(models);
        return new Provider() {
            @Override public String name() { return "static-test"; }
            @Override public String displayName() { return "Static Test"; }
            @Override public Set<Class<? extends ProviderApi>> supportedApis() {
                return Set.of();
            }
            @Override public <T extends ProviderApi> T createApi(
                    Class<T> apiType, ApiOptions options) {
                throw new UnsupportedOperationException();
            }
            @Override public ModelCatalog builtinModels() { return catalog; }
        };
    }

    /** pi {@code publishProviderModels} 的等价物：写 store、世代检查、跑 update。 */
    private static final class TestPort implements CatalogPublisherPort {
        final List<ModelsPublication> publications = new ArrayList<>();
        ModelsStoreEntry entry;
        boolean accept = true;
        boolean allowDelete = true;

        @Override
        public boolean publish(ModelsPublication publication) {
            publications.add(publication);
            if (!accept) {
                return false;
            }
            if (publication.delete() && allowDelete) {
                entry = null;
            } else {
                publication.persist().ifPresent(e -> entry = e);
            }
            publication.update().run();
            return true;
        }
    }

    /** 录制请求的目录端点。 */
    private static final class CatalogServer implements AutoCloseable {
        private final HttpServer server;
        final List<Recorded> requests = new CopyOnWriteArrayList<>();
        private volatile Reply reply;

        CatalogServer(Reply reply) throws IOException {
            this.reply = reply;
            server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                var headers = new LinkedHashMap<String, String>();
                exchange.getRequestHeaders().forEach(
                    (name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
                requests.add(new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), headers));
                var current = this.reply;
                current.headers().forEach(
                    (name, value) -> exchange.getResponseHeaders().set(name, value));
                var body = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(current.status(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reply(Reply next) {
            this.reply = next;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record Recorded(String method, String path, Map<String, String> headers) {}

    private record Reply(int status, String body, Map<String, String> headers) {
        /** 200 + JSON，并带一个**晚于** {@link #LOCAL_GENERATED_AT} 的 Last-Modified。 */
        static Reply json(String body) {
            return new Reply(200, body, Map.of(
                "content-type", "application/json",
                "last-modified", "Mon, 01 Jun 2026 00:00:00 GMT"));
        }

        static Reply status(int status) {
            return new Reply(status, "", Map.of());
        }
    }

    private static ModelsStoreEntry cached(List<ModelInfo> models, Instant lastModified,
                                           Instant checkedAt, String etag) {
        return new ModelsStoreEntry(models, lastModified, checkedAt, etag);
    }

    private static Provider wrapped(CatalogServer server) {
        return RemoteCatalogProvider.wrap(
            staticProvider(List.of(STATIC_MODEL)), server.baseUrl(),
            Optional.of(LOCAL_GENERATED_AT));
    }

    // ── §7.1-1：GET 形状 ─────────────────────────────────────

    @Test
    void sendsGetWithPathAcceptAndUserAgentAndNoValidatorWhenCacheEmpty() throws Exception {
        try (var server = new CatalogServer(Reply.json("[]"))) {
            var port = new TestPort();
            wrapped(server).refreshModels(RefreshModelsContext.online(
                Optional.empty(), port, false));

            assertThat(server.requests).hasSize(1);
            var request = server.requests.get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/api/models/providers/static-test");
            assertThat(request.headers()).containsEntry("accept", "application/json");
            assertThat(request.headers().get("user-agent")).startsWith("pi-java/");
            // 无缓存 body ⇒ 不带 validator（否则 304 会把 overlay 清空）
            assertThat(request.headers()).doesNotContainKey("if-none-match");
        }
    }

    @Test
    void sendsValidatorOnlyWhenCachedBodyHasModels() throws Exception {
        try (var server = new CatalogServer(Reply.status(304))) {
            var withModels = new TestPort();
            withModels.entry = cached(List.of(CACHED_MODEL), REMOTE_NEWER, null, "\"v1\"");
            wrapped(server).refreshModels(RefreshModelsContext.online(
                Optional.of(withModels.entry), withModels, false));

            assertThat(server.requests.get(0).headers())
                .containsEntry("if-none-match", "\"v1\"");

            var withoutModels = new TestPort();
            withoutModels.entry = cached(List.of(), REMOTE_NEWER, null, "\"v1\"");
            wrapped(server).refreshModels(RefreshModelsContext.online(
                Optional.of(withoutModels.entry), withoutModels, false));

            assertThat(server.requests.get(1).headers())
                .doesNotContainKey("if-none-match");
        }
    }

    // ── §7.1-2：200 三形态 + merge ───────────────────────────

    @Test
    void mergesArrayShapeReplacingSameIdAndAppendingNewId() throws Exception {
        try (var server = new CatalogServer(Reply.json("""
            [{"id":"static-model","name":"Remote Static","contextWindow":4096,"maxTokens":512},
             {"id":"new-model","name":"New","contextWindow":8192,"maxTokens":1024}]
            """))) {
            var port = new TestPort();
            var provider = wrapped(server);
            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            var models = provider.getModels();
            assertThat(models).hasSize(2);
            assertThat(models.get(0).id().modelName()).isEqualTo("static-model");
            assertThat(models.get(0).displayName()).isEqualTo("Remote Static");
            assertThat(models.get(0).maxInputTokens()).isEqualTo(4096);
            assertThat(models.get(1).id().modelName()).isEqualTo("new-model");

            // 持久化还是 wire 形状
            assertThat(port.entry.lastModified()).isEqualTo(REMOTE_NEWER);
            assertThat(port.entry.models()).hasSize(2);
        }
    }

    @Test
    void parsesModelsEnvelopeShape() throws Exception {
        try (var server = new CatalogServer(Reply.json("""
            {"models":[{"id":"envelope","name":"Envelope","contextWindow":10,"maxTokens":20}]}
            """))) {
            var port = new TestPort();
            var provider = wrapped(server);
            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model", "envelope");
        }
    }

    @Test
    void parsesKeyedObjectShape() throws Exception {
        try (var server = new CatalogServer(Reply.json("""
            {"dynamic":{"id":"dynamic","name":"Dynamic","contextWindow":10,"maxTokens":20},
             "notAModel":"ignored"}
            """))) {
            var port = new TestPort();
            var provider = wrapped(server);
            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model", "dynamic");
        }
    }

    @Test
    void parsesLastModifiedAndEtagIntoTheStoredEntry() throws Exception {
        try (var server = new CatalogServer(new Reply(200, "[]", Map.of(
                "content-type", "application/json",
                "etag", "\"abc\"",
                "last-modified", "Mon, 01 Jun 2026 00:00:00 GMT")))) {
            var port = new TestPort();
            wrapped(server).refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            assertThat(port.entry.lastModified()).isEqualTo(REMOTE_NEWER);
            assertThat(port.entry.etag()).isEqualTo("\"abc\"");
            assertThat(port.entry.checkedAt()).isNotNull();
        }
    }

    @Test
    void absentLastModifiedIsStoredAsEpochAndGuardedOut() throws Exception {
        try (var server = new CatalogServer(new Reply(200, "[{\"id\":\"x\",\"name\":\"X\"}]",
                Map.of("content-type", "application/json")))) {
            var port = new TestPort();
            var provider = wrapped(server);
            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            assertThat(port.entry.lastModified()).isEqualTo(Instant.EPOCH);
            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model");
        }
    }

    @Test
    void invalidCatalogShapeThrows() throws Exception {
        try (var server = new CatalogServer(Reply.json("\"just a string\""))) {
            var port = new TestPort();
            var thrown = catchThrowable(() -> wrapped(server).refreshModels(
                RefreshModelsContext.online(Optional.empty(), port, false)));

            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid model catalog for provider \"static-test\"");
        }
    }

    // ── §7.1-7：generatedAt 守卫 ─────────────────────────────

    @Test
    void generatedAtGuardDropsRemoteNotNewerThanLocal() throws Exception {
        try (var server = new CatalogServer(new Reply(200, "[{\"id\":\"x\",\"name\":\"X\"}]",
                Map.of("last-modified", "Thu, 01 Jan 2026 00:00:00 GMT")))) {
            var port = new TestPort();
            var provider = wrapped(server);
            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            // 远端 == 本地生成时间 ⇒ overlay 不生效，只有静态目录
            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model");
        }
    }

    @Test
    void generatedAtGuardAcceptsRemoteNewerThanLocal() throws Exception {
        try (var server = new CatalogServer(new Reply(200, "[{\"id\":\"x\",\"name\":\"X\"}]",
                Map.of("last-modified", "Mon, 01 Jun 2026 00:00:00 GMT")))) {
            var port = new TestPort();
            var provider = wrapped(server);
            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model", "x");
        }
    }

    // ── §7.1-9：离线相 ───────────────────────────────────────

    @Test
    void offlinePhaseRestoresCacheWithZeroRequests() throws Exception {
        try (var server = new CatalogServer(Reply.json("[]"))) {
            var port = new TestPort();
            port.entry = cached(List.of(CACHED_MODEL), REMOTE_NEWER, null, null);
            var provider = wrapped(server);

            provider.refreshModels(RefreshModelsContext.offline(
                Optional.of(port.entry), port));

            assertThat(server.requests).isEmpty();
            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model", "cached-model");
        }
    }

    // ── 世代：publish 拒收则整轮停手 ─────────────────────────

    @Test
    void rejectedPublicationStopsRefreshBeforeNetwork() throws Exception {
        try (var server = new CatalogServer(Reply.json("[]"))) {
            var port = new TestPort();
            port.accept = false;
            var provider = wrapped(server);

            provider.refreshModels(RefreshModelsContext.online(Optional.empty(), port, false));

            assertThat(server.requests).isEmpty();
            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model");
        }
    }

    // ── 304 / 404 / 501 / transient ──────────────────────────

    @Test
    void notModifiedKeepsOverlayAndOnlyUpdatesCheckedAt() throws Exception {
        try (var server = new CatalogServer(Reply.status(304))) {
            var port = new TestPort();
            port.entry = cached(List.of(CACHED_MODEL), REMOTE_NEWER, null, "\"v1\"");
            var provider = wrapped(server);

            provider.refreshModels(RefreshModelsContext.online(
                Optional.of(port.entry), port, false));

            assertThat(port.entry.etag()).isEqualTo("\"v1\"");
            assertThat(port.entry.lastModified()).isEqualTo(REMOTE_NEWER);
            assertThat(port.entry.checkedAt()).isNotNull();
            assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                .containsExactly("static-model", "cached-model");
        }
    }

    @Test
    void notFoundZeroesLastModifiedAndClearsEtag() throws Exception {
        for (int status : new int[] {404, 501}) {
            try (var server = new CatalogServer(Reply.status(status))) {
                var port = new TestPort();
                var stored = cached(List.of(CACHED_MODEL), REMOTE_NEWER, null, "\"v1\"");
                port.entry = stored;
                var provider = wrapped(server);

                provider.refreshModels(RefreshModelsContext.online(
                    Optional.of(stored), port, false));

                assertThat(port.entry.lastModified()).isEqualTo(Instant.EPOCH);
                assertThat(port.entry.etag()).isNull();
                assertThat(port.entry.models()).hasSize(1);
                // pi 的 404 分支只 publish(persist)，**不带 update** ⇒ 本次调用内
                // dynamic 仍是离线相恢复的那份（判定见 原 docs/70 §1.3 的守卫注）
                assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                    .containsExactly("static-model", "cached-model");

                // 下次刷新读到的 stored.lastModified == EPOCH ⇒ 守卫让 overlay 归空
                provider.refreshModels(RefreshModelsContext.online(
                    Optional.of(port.entry), port, false));
                assertThat(provider.getModels()).extracting(m -> m.id().modelName())
                    .containsExactly("static-model");
            }
        }
    }

    @Test
    void transientFailureKeepsEtagAndThrows() throws Exception {
        try (var server = new CatalogServer(Reply.status(500))) {
            var port = new TestPort();
            port.entry = cached(List.of(CACHED_MODEL), REMOTE_NEWER, null, "\"v1\"");
            var provider = wrapped(server);

            var thrown = catchThrowable(() -> provider.refreshModels(
                RefreshModelsContext.online(Optional.of(port.entry), port, false)));

            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("Model catalog request failed for static-test: 500");
            assertThat(port.entry.etag()).isEqualTo("\"v1\"");
            assertThat(port.entry.checkedAt()).isNotNull();
        }
    }

    // ── §7.1-6：TTL ──────────────────────────────────────────

    @Test
    void freshEntrySkipsRequestButForceBypasses() throws Exception {
        try (var server = new CatalogServer(Reply.json("[]"))) {
            var port = new TestPort();
            port.entry = cached(List.of(CACHED_MODEL), REMOTE_NEWER, Instant.now(), "\"v1\"");
            var provider = wrapped(server);

            provider.refreshModels(RefreshModelsContext.online(
                Optional.of(port.entry), port, false));
            assertThat(server.requests).isEmpty();

            provider.refreshModels(RefreshModelsContext.online(
                Optional.of(port.entry), port, true));
            assertThat(server.requests).hasSize(1);
        }
    }

    @Test
    void staleCheckedAtRefetches() throws Exception {
        try (var server = new CatalogServer(Reply.json("[]"))) {
            var port = new TestPort();
            port.entry = cached(List.of(CACHED_MODEL), REMOTE_NEWER,
                Instant.now().minus(RemoteCatalogProvider.REFRESH_INTERVAL).minusSeconds(60),
                "\"v1\"");
            var provider = wrapped(server);

            provider.refreshModels(RefreshModelsContext.online(
                Optional.of(port.entry), port, false));

            assertThat(server.requests).hasSize(1);
        }
    }
}
