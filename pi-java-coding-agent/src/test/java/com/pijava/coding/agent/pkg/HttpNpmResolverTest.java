package com.pijava.coding.agent.pkg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * 批 18 T3（docs/29 §3/§7）：HTTP registry resolver——loopback {@link HttpServer}
 * 夹具（packument JSON＋自造 .tgz），pinned 直拉/range maxSatisfying/SRI 校验/
 * 镜像键优先级/安装目录 package.json 回读。
 */
class HttpNpmResolverTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    private HttpServer server;
    private final ConcurrentLinkedQueue<String> requestedPaths = new ConcurrentLinkedQueue<>();
    private byte[] tgz100;
    private byte[] tgz120;
    private byte[] tgz200;

    @BeforeEach
    void startRegistry() throws Exception {
        tgz100 = tgz(Map.of(
            "package/package.json", "{\"name\":\"pkg\",\"version\":\"1.0.0\"}",
            "package/skills/s.md", "# v1.0.0"));
        tgz120 = tgz(Map.of(
            "package/package.json", "{\"name\":\"pkg\",\"version\":\"1.2.0\"}",
            "package/skills/s.md", "# v1.2.0"));
        tgz200 = tgz(Map.of(
            "package/package.json", "{\"name\":\"pkg\",\"version\":\"2.0.0\"}",
            "package/skills/s.md", "# v2.0.0"));

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            var path = exchange.getRequestURI().getPath();
            byte[] body = null;
            if (path.equals("/pkg")) {
                body = packument(true).getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/pkg-bad-integrity")) {
                body = packument(false).getBytes(StandardCharsets.UTF_8);
            } else {
                body = switch (path) {
                    case "/pkg/-/pkg-1.0.0.tgz" -> tgz100;
                    case "/pkg/-/pkg-1.2.0.tgz" -> tgz120;
                    case "/pkg/-/pkg-2.0.0.tgz" -> tgz200;
                    default -> null;
                };
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopRegistry() {
        server.stop(0);
    }

    private String registry() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** dist.integrity 正确/全错的 packument。 */
    private String packument(boolean goodIntegrity) {
        var base = registry();
        var bad = "sha512-" + Base64.getEncoder().encodeToString(new byte[64]);
        var i100 = goodIntegrity ? sri(tgz100) : bad;
        var i120 = goodIntegrity ? sri(tgz120) : bad;
        var i200 = goodIntegrity ? sri(tgz200) : bad;
        return """
            {"name":"pkg","versions":{
              "1.0.0":{"dist":{"tarball":"%s/pkg/-/pkg-1.0.0.tgz","integrity":"%s"}},
              "1.2.0":{"dist":{"tarball":"%s/pkg/-/pkg-1.2.0.tgz","integrity":"%s"}},
              "2.0.0":{"dist":{"tarball":"%s/pkg/-/pkg-2.0.0.tgz","integrity":"%s"}}
            }}
            """.formatted(base, i100, base, i120, base, i200);
    }

    private NpmResolver resolver() {
        return new HttpNpmResolver(HttpClient.newHttpClient(), registry(), layout());
    }

    private InstallLayout layout() {
        return new InstallLayout(new SourceResolver(cwd, agentDir, () -> true));
    }

    @Test
    void versionsReturnsAllPublished() throws Exception {
        assertThat(resolver().versions("pkg"))
            .containsExactlyInAnyOrder("1.0.0", "1.2.0", "2.0.0");
    }

    @Test
    void latestSatisfyingPicksMaxInRangeOrOverall() throws Exception {
        assertThat(resolver().latestSatisfying("pkg", "^1.0.0")).isEqualTo("1.2.0");
        // 无 range ⇒ rcompare 取首（pi :1560 对位）。
        assertThat(resolver().latestSatisfying("pkg", null)).isEqualTo("2.0.0");
    }

    @Test
    void pinnedInstallSkipsMetadataAndExtracts() throws Exception {
        var installRoot = cwd.resolve("npmroot");
        layout().ensureNpmProject(installRoot);
        var pinned = new NpmSource("pkg@1.0.0", "pkg", "1.0.0", "1.0.0", true);

        var target = resolver().install(pinned, installRoot);

        // pinned 不查 packument（直拉约定 URL）；package/ 前缀剥一层。
        assertThat(requestedPaths).containsExactly("/pkg/-/pkg-1.0.0.tgz");
        assertThat(target).isEqualTo(installRoot.resolve("node_modules").resolve("pkg"));
        assertThat(Files.readString(target.resolve("skills/s.md"))).isEqualTo("# v1.0.0");
        assertThat(layout().installedNpmVersion(target)).isEqualTo("1.0.0");
    }

    @Test
    void rangeInstallVerifiesIntegrityAndReadsBackVersion() throws Exception {
        var installRoot = cwd.resolve("npmroot2");
        layout().ensureNpmProject(installRoot);
        var ranged = new NpmSource("pkg@^1.0.0", "pkg", "^1.0.0", "^1.0.0", false);

        var target = resolver().install(ranged, installRoot);

        assertThat(requestedPaths).contains("/pkg", "/pkg/-/pkg-1.2.0.tgz");
        assertThat(Files.readString(target.resolve("skills/s.md"))).isEqualTo("# v1.2.0");
        assertThat(layout().installedNpmVersion(target)).isEqualTo("1.2.0");
    }

    @Test
    void integrityMismatchFailsInstall() {
        var installRoot = cwd.resolve("npmroot3");
        var ranged = new NpmSource("pkg-bad-integrity", "pkg-bad-integrity", "^1.0.0",
            "^1.0.0", false);

        assertThatThrownBy(() -> resolver().install(ranged, installRoot))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Integrity check failed");
    }

    @Test
    void registryResolutionPrecedence() throws Exception {
        var project = Files.createDirectories(cwd.resolve("proj"));
        Files.writeString(project.resolve(".npmrc"), "registry=https://project.example\n");
        var home = Files.createDirectories(cwd.resolve("home"));
        Files.writeString(home.resolve(".npmrc"), "# comment\nregistry=https://user.example\n");

        // env > 项目 > 用户 > 默认（裁决④）。
        assertThat(HttpNpmResolver.resolveRegistry(project, "https://env.example", home))
            .isEqualTo("https://env.example");
        assertThat(HttpNpmResolver.resolveRegistry(project, null, home))
            .isEqualTo("https://project.example");
        assertThat(HttpNpmResolver.resolveRegistry(cwd, " ", home))
            .isEqualTo("https://user.example");
        assertThat(HttpNpmResolver.resolveRegistry(cwd, null, cwd))
            .isEqualTo(HttpNpmResolver.DEFAULT_REGISTRY);
    }

    @Test
    void scopedNameTarballUrlConvention() throws Exception {
        // scoped 名末段为 tarball 基名（npm 约定形）。
        server.createContext("/@org/pkg/-/pkg-3.1.0.tgz", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            var body = tgz(Map.of("package/package.json",
                "{\"name\":\"@org/pkg\",\"version\":\"3.1.0\"}"));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var installRoot = cwd.resolve("npmroot4");
        var pinned = new NpmSource("@org/pkg@3.1.0", "@org/pkg", "3.1.0", "3.1.0", true);

        var target = resolver().install(pinned, installRoot);

        assertThat(requestedPaths).containsExactly("/@org/pkg/-/pkg-3.1.0.tgz");
        assertThat(layout().installedNpmVersion(target)).isEqualTo("3.1.0");
    }

    // ── 夹具工具 ─────────────────────────────────────────────────

    private static String sri(byte[] payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-512").digest(payload);
            return "sha512-" + Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] tgz(java.util.Map<String, String> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
            for (var entry : entries.entrySet()) {
                var content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                var tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(content.length);
                tar.putArchiveEntry(tarEntry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
