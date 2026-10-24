package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pijava.coding.agent.core.Json;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * npm 源的 HTTP registry 实现（批 18 裁决③）：不依赖 node 生态，直拉 registry
 * 元数据（精简 packument）＋ tarball 下载（SRI 校验）＋ tar.gz 解包。
 *
 * <p>registry 来源（裁决④）：{@code NPM_CONFIG_REGISTRY} 环境变量 &gt; 项目
 * {@code .npmrc} &gt; 用户 {@code ~/.npmrc} 的 {@code registry=} 键 &gt;
 * 默认 {@code https://registry.npmjs.org}。scope 化 registry 与认证不支持
 * （D104 注记）。</p>
 *
 * <p>与 pi（npm CLI 臂）的偏差：无遗留全局回落（D102）；pinned 源不查元数据，
 * tarball URL 按 npm 约定形 {@code <registry>/<name>/-/<base>-<version>.tgz}
 * 构造，integrity 缺席时跳过校验并记诊断。</p>
 */
public final class HttpNpmResolver implements NpmResolver {

    private static final Logger LOG = LoggerFactory.getLogger(HttpNpmResolver.class);

    public static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";

    /** pi {@code NETWORK_TIMEOUT_MS}（package-manager.ts:50）。 */
    private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(10);

    /** pi 逐字（:1549 经 runCommandCapture 的 Accept 行为对位——npm CLI 内部同头）。 */
    private static final String PACKUMENT_ACCEPT = "application/vnd.npm.install-v1+json";

    private final HttpClient client;
    private final String registry;
    private final InstallLayout layout;

    public HttpNpmResolver(Path cwd, InstallLayout layout) {
        this(defaultClient(), resolveRegistry(cwd), layout);
    }

    /** 可测形（注册表/客户端注入）。 */
    public HttpNpmResolver(HttpClient client, String registry, InstallLayout layout) {
        this.client = client;
        this.registry = registry.endsWith("/")
            ? registry.substring(0, registry.length() - 1) : registry;
        this.layout = layout;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
            .connectTimeout(NETWORK_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    // ── registry 解析（裁决④）────────────────────────────────────

    /** 生产入口：env &gt; 项目 .npmrc &gt; 用户 .npmrc &gt; 默认。 */
    public static String resolveRegistry(Path cwd) {
        return resolveRegistry(cwd, System.getenv("NPM_CONFIG_REGISTRY"),
            Path.of(System.getProperty("user.home")));
    }

    /** 可测形（全注入）。 */
    public static String resolveRegistry(Path cwd, String envRegistry, Path userHome) {
        if (envRegistry != null && !envRegistry.isBlank()) {
            return envRegistry.trim();
        }
        var project = readNpmrcRegistry(cwd.resolve(".npmrc"));
        if (project != null) {
            return project;
        }
        var user = readNpmrcRegistry(userHome.resolve(".npmrc"));
        if (user != null) {
            return user;
        }
        return DEFAULT_REGISTRY;
    }

    /** 简单 {@code key=value} 解析：{@code registry=} 键（裁决④；scope 化键不支持）。 */
    private static String readNpmrcRegistry(Path npmrc) {
        if (!Files.isRegularFile(npmrc)) {
            return null;
        }
        try {
            for (var line : Files.readAllLines(npmrc)) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                var eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).trim().equals("registry")) {
                    var value = trimmed.substring(eq + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (IOException e) {
            // 读不动 ⇒ 当作没有（pi 由 npm CLI 代读，失败同样静默回落默认）
        }
        return null;
    }

    // ── NpmResolver 实现 ─────────────────────────────────────────

    @Override
    public List<String> versions(String name) throws IOException {
        return versionsOf(fetchPackument(name));
    }

    @Override
    public String latestSatisfying(String name, String range) throws IOException {
        var versions = versions(name).stream()
            .filter(v -> v != null && !v.isEmpty() && Semvers.valid(v) != null)
            .toList();
        // pi :1560-1562：range ⇒ maxSatisfying；无 ⇒ rcompare 取首。
        var latest = range != null
            ? Semvers.maxSatisfying(versions, range)
            : versions.stream().max(versionOrder()).orElse(null);
        if (latest == null) {
            throw new IOException("Unexpected response from npm registry for " + name);
        }
        return latest;
    }

    @Override
    public Path install(NpmSource source, Path installRoot) throws IOException {
        String version;
        JsonNode dist = null;
        if (source.pinned()) {
            // pinned（精确版本）不查元数据直接下载（docs/29 §3 T3）。
            version = source.version();
        } else {
            var packument = fetchPackument(source.name());
            version = pickVersion(versionsOf(packument), source);
            var distNode = packument.path("versions").path(version).path("dist");
            dist = distNode.isMissingNode() ? null : distNode;
        }

        var tarballUrl = dist != null && dist.hasNonNull("tarball")
            ? dist.get("tarball").asText()
            : conventionalTarballUrl(source.name(), version);
        var tarball = download(tarballUrl);

        var integrity = dist != null && dist.hasNonNull("integrity")
            ? dist.get("integrity").asText() : null;
        if (integrity != null && !integrity.isBlank()) {
            verifySriIntegrity(integrity, tarball, tarballUrl);
        } else {
            LOG.debug("npm tarball integrity absent; skipping verification: {}", tarballUrl);
        }

        var target = installRoot.resolve("node_modules").resolve(source.name());
        extractTarGz(tarball, target);
        ensureInstalledPackageJson(target, source.name(), version);
        return target;
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private static List<String> versionsOf(JsonNode packument) {
        var versionsNode = packument.get("versions");
        var versions = new ArrayList<String>();
        if (versionsNode != null && versionsNode.isObject()) {
            versionsNode.fieldNames().forEachRemaining(versions::add);
        }
        return versions;
    }

    /** pi :1560-1562 对位：range ⇒ maxSatisfying；无 ⇒ rcompare 取首；
     *  无满足 ⇒ 抛。 */
    private static String pickVersion(List<String> versions, NpmSource source)
            throws IOException {
        var valid = versions.stream()
            .filter(v -> v != null && !v.isEmpty() && Semvers.valid(v) != null)
            .toList();
        var latest = source.range() != null
            ? Semvers.maxSatisfying(valid, source.range())
            : valid.stream().max(versionOrder()).orElse(null);
        if (latest == null) {
            throw new IOException("Unexpected response from npm registry for " + source.name());
        }
        return latest;
    }


    /** {@code GET <registry>/<name>}（scoped 名 {@code @} 后斜杠转 {@code %2f}）。 */
    private JsonNode fetchPackument(String name) throws IOException {
        var encoded = name.replace("/", "%2f");
        var request = HttpRequest.newBuilder(URI.create(registry + "/" + encoded))
            .timeout(NETWORK_TIMEOUT)
            .header("Accept", PACKUMENT_ACCEPT)
            .GET()
            .build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException(
                "npm registry metadata request failed with status " + response.statusCode()
                    + " for " + name);
        }
        return Json.mapper().readTree(response.body());
    }

    private byte[] download(String url) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url))
            .timeout(NETWORK_TIMEOUT)
            .GET()
            .build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException(
                "npm tarball download failed with status " + response.statusCode()
                    + " for " + url);
        }
        return response.body();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("npm registry request interrupted", e);
        }
    }

    /** npm 约定 tarball 形：{@code <registry>/<name>/-/<base>-<version>.tgz}
     *  （base＝scoped 名末段）。 */
    private String conventionalTarballUrl(String name, String version) {
        var base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        return registry + "/" + name + "/-/" + base + "-" + version + ".tgz";
    }

    /** SRI 校验（{@code sha512-<base64>}，多枚空格分隔取首个可算算法）。 */
    private static void verifySriIntegrity(String integrity, byte[] payload, String url)
            throws IOException {
        for (var token : integrity.trim().split("\\s+")) {
            var dash = token.indexOf('-');
            if (dash < 0) {
                continue;
            }
            var algo = token.substring(0, dash).toLowerCase(Locale.ROOT);
            var jca = switch (algo) {
                case "sha512" -> "SHA-512";
                case "sha384" -> "SHA-384";
                case "sha1" -> "SHA-1";
                default -> null;
            };
            if (jca == null) {
                continue;
            }
            try {
                var digest = MessageDigest.getInstance(jca).digest(payload);
                var actual = Base64.getEncoder().encodeToString(digest);
                if (actual.equals(token.substring(dash + 1))) {
                    return;
                }
                throw new IOException("Integrity check failed for npm tarball: " + url);
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SRI algorithm unavailable: " + algo, e);
            }
        }
        LOG.debug("no supported SRI algorithm in integrity field; skipped: {}", url);
    }

    /**
     * 解 tar.gz 到 {@code target}：tar 内统一 {@code package/} 前缀剥一层；
     * 越目录（tar slip）条目拒绝。
     */
    private static void extractTarGz(byte[] tarball, Path target) throws IOException {
        deleteRecursively(target);
        Files.createDirectories(target);
        try (var tar = new TarArchiveInputStream(new GZIPInputStream(
                new java.io.ByteArrayInputStream(tarball)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                var name = entry.getName();
                if (name.startsWith("package/")) {
                    name = name.substring("package/".length());
                }
                if (name.isEmpty()) {
                    continue;
                }
                var destination = target.resolve(name).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Refusing tar entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                var destinationParent = destination.getParent();
                if (destinationParent != null) {
                    Files.createDirectories(destinationParent);
                }
                // tar 流跨 entry 推进，Files.copy 不许关它 ⇒ 不关包装。
                Files.copy(NonClosingInputStream.of(tar), destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 读取面对齐 pi {@code getInstalledNpmVersion}：解出的 package.json 缺
     *  version 时写最小形兜底。 */
    private void ensureInstalledPackageJson(Path target, String name, String version)
            throws IOException {
        if (layout.installedNpmVersion(target) != null) {
            return;
        }
        Files.writeString(target.resolve("package.json"),
            "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"private\":true}");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Comparator<String> versionOrder() {
        return Semvers::compare;
    }

    /** commons-compress 的流在 entry 间推进，{@code Files.copy} 不许关它。 */
    private static final class NonClosingInputStream extends InputStream {
        private final InputStream delegate;

        private NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        static NonClosingInputStream of(InputStream delegate) {
            return new NonClosingInputStream(delegate);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() {
            // 不关：tar 流要继续读下一 entry。
        }
    }

}
