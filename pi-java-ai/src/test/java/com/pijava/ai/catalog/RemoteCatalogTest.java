package com.pijava.ai.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-8: RemoteCatalog — 200/304 分支、ETag 含引号原样回填、离线缓存回退。
 */
class RemoteCatalogTest {

    private static final String CATALOG = """
        [
          {"provider":"remote","model":"m1","displayName":"M1",
           "capabilities":["text","thinking"],"maxInputTokens":100,
           "maxOutputTokens":50,"deprecated":false,
           "inputPrice":1.0,"outputPrice":2.0},
          {"provider":"remote","model":"m2","displayName":"M2",
           "capabilities":["text"],"maxInputTokens":200,
           "maxOutputTokens":100,"deprecated":true,
           "inputPrice":3.0,"outputPrice":4.0}
        ]
        """;

    private HttpServer server;
    private final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();
    private final AtomicInteger statusToReturn = new AtomicInteger(200);

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void initialRefreshFetchesAndCachesModels() throws Exception {
        var url = startServer();
        var store = new InMemoryModelsStore();
        var catalog = new RemoteCatalog("remote", url, store);

        var result = catalog.refresh();

        assertThat(result.changed()).isTrue();
        assertThat(result.modelCount()).isEqualTo(2);
        assertThat(result.etag()).isEqualTo("\"v1\"");
        assertThat(catalog.listModels()).hasSize(2);
        assertThat(store.read("remote")).isPresent();
        assertThat(store.read("remote").get().etag()).isEqualTo("\"v1\"");
    }

    @Test
    void conditionalRefreshSendsStoredEtagAndUses304() throws Exception {
        var url = startServer();
        var store = new InMemoryModelsStore();
        var catalog = new RemoteCatalog("remote", url, store);

        catalog.refresh(); // 200，缓存 etag="v1"
        statusToReturn.set(304);
        var result = catalog.refresh(); // If-None-Match="v1" → 304

        assertThat(lastIfNoneMatch.get()).isEqualTo("\"v1\""); // 原样回填（含引号）
        assertThat(result.changed()).isFalse();
        assertThat(result.modelCount()).isEqualTo(2);
    }

    @Test
    void forceRefreshIgnoresEtag() throws Exception {
        var url = startServer();
        var store = new InMemoryModelsStore();
        var catalog = new RemoteCatalog("remote", url, store);

        catalog.refresh(); // 缓存 etag
        statusToReturn.set(304);
        var forced = catalog.forceRefresh();

        assertThat(forced.changed()).isFalse(); // 服务器 304，强制刷新不改变
        assertThat(forced.modelCount()).isEqualTo(2);
    }

    @Test
    void offlineFallsBackToCache() throws Exception {
        var url = startServer();
        var store = new InMemoryModelsStore();
        var catalog = new RemoteCatalog("remote", url, store);
        catalog.refresh();
        server.stop(0);
        server = null;

        var result = catalog.refresh(); // 网络失败 → 回退缓存
        assertThat(result.changed()).isFalse();
        assertThat(catalog.listModels()).hasSize(2);
    }

    @Test
    void offlineWithoutCacheThrows() throws Exception {
        var url = startServer();
        var store = new InMemoryModelsStore();
        server.stop(0);
        server = null;
        var catalog = new RemoteCatalog("remote", url, store);

        assertThatThrownBy(catalog::refresh)
            .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void searchMatchesProviderAndModel() throws Exception {
        var url = startServer();
        var catalog = new RemoteCatalog("remote", url, new InMemoryModelsStore());
        catalog.refresh();

        assertThat(catalog.search("m1")).hasSize(1);
        assertThat(catalog.search("REMOTE")).hasSize(2);
        assertThat(catalog.search("zzz")).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private URL startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/models.json", exchange -> {
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            lastIfNoneMatch.set(inm);
            int status = statusToReturn.get();
            byte[] body = CATALOG.getBytes(StandardCharsets.UTF_8);
            if (status == 304) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort()
            + "/models.json").toURL();
    }
}
