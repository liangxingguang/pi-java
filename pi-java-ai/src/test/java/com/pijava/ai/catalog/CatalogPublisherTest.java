package com.pijava.ai.catalog;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-10: CatalogPublisher — 校验 / 合并 / ETag / HTTP PUT 上传。
 */
class CatalogPublisherTest {

    private static CatalogModel model(String provider, String id) {
        return new CatalogModel(provider, id, id, List.of("text"),
            100, 50, false, 1.0, 2.0);
    }

    @Test
    void validateAcceptsValidCatalog() {
        assertThat(CatalogPublisher.validate(List.of(model("a", "m1")))).isEmpty();
    }

    @Test
    void validateFlagsBlankAndDuplicate() {
        var errors = CatalogPublisher.validate(List.of(
            model("", "m1"),
            model("a", "m1"),
            model("a", "m1")));
        assertThat(errors).hasSize(2);
        assertThat(errors).anyMatch(e -> e.contains("blank provider"));
        assertThat(errors).anyMatch(e -> e.contains("duplicate"));
    }

    @Test
    void mergeOverlayOverridesBaseByKey() {
        var base = List.of(model("a", "m1"), model("a", "m2"));
        var overlay = List.of(model("a", "m1"), model("b", "m3"));
        var merged = CatalogPublisher.merge(base, overlay);
        // LinkedHashMap 保序：m1 覆盖后仍在原位置；m3 追加在尾部
        assertThat(merged).extracting(m -> m.model()).containsExactly("m1", "m2", "m3");
        // overlay 的 m1 覆盖 base 的 m1（同 key）
        assertThat(merged).filteredOn(m -> m.model().equals("m1")).hasSize(1);
    }

    @Test
    void etagIsQuotedSha256AndDeterministic() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String etag = CatalogPublisher.generateEtag(content);
        assertThat(etag).startsWith("\"").endsWith("\"").hasSize(66);
        assertThat(CatalogPublisher.generateEtag(content)).isEqualTo(etag);
    }

    @Test
    void publishPutsContentAndEtag() throws Exception {
        var receivedEtag = new AtomicReference<String>();
        var receivedBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/upload", exchange -> {
            receivedEtag.set(exchange.getRequestHeaders().getFirst("ETag"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            byte[] content = "[]".getBytes(StandardCharsets.UTF_8);
            String etag = CatalogPublisher.generateEtag(content);
            CatalogPublisher.publish(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/upload"),
                content, etag);
            assertThat(receivedEtag.get()).isEqualTo(etag);
            assertThat(receivedBody.get()).isEqualTo("[]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishFailsOnNon2xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/upload", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> CatalogPublisher.publish(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/upload"),
                "[]".getBytes(StandardCharsets.UTF_8), "\"etag\""))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("500");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jsonRoundTrip() {
        var models = List.of(model("a", "m1"), model("b", "m2"));
        byte[] bytes = CatalogPublisher.toJsonBytes(models);
        assertThat(CatalogPublisher.parse(bytes)).isEqualTo(models);
    }
}
