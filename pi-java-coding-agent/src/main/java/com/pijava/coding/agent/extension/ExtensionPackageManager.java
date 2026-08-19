package com.pijava.coding.agent.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.pijava.coding.agent.core.FileSettingsStorage;

/**
 * 扩展包管理 —— {@code pi-java package install/remove/update/list} 的文件系统操作。
 *
 * <p>扩展按 JAR 存储在扩展目录：全局 {@code ~/.pi-java/agent/extensions/}（或
 * {@code PI_JAVA_CODING_AGENT_DIR} 覆盖），项目级 {@code <cwd>/.pi-java/extensions/}
 * 由 {@code -l} 选择。安装来源为本地 JAR 路径或 http(s) URL；下载用 JDK
 * {@link HttpClient}，无第三方依赖。</p>
 */
public final class ExtensionPackageManager {

    private static final String SERVICE_MARKER =
        "META-INF/services/com.pijava.coding.agent.extension.PiExtension";

    private final Path dir;

    /** @param dir 扩展 JAR 存放目录（不存在时由 install 创建） */
    public ExtensionPackageManager(Path dir) {
        this.dir = dir;
    }

    /** 全局扩展目录（对齐 skills/settings 的 agent 配置根）。 */
    public static ExtensionPackageManager global() {
        return new ExtensionPackageManager(
            FileSettingsStorage.defaultAgentDir().resolve("extensions"));
    }

    /** 项目级扩展目录（{@code -l}）。 */
    public static ExtensionPackageManager project() {
        return new ExtensionPackageManager(
            Path.of(System.getProperty("user.dir")).resolve(".pi-java").resolve("extensions"));
    }

    /** 扩展目录。 */
    public Path dir() {
        return dir;
    }

    /** 已安装的 {@code .jar} 路径列表（目录不存在时为空，不创建）。 */
    public List<Path> installedJars() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 已安装扩展的展示记录（按文件名排序）。 */
    public List<InstalledExtension> list() {
        return installedJars().stream().map(InstalledExtension::of).toList();
    }

    /** 安装本地 JAR：复制进扩展目录并返回展示记录。 */
    public InstalledExtension install(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("No such file: " + jar);
        }
        if (!InstalledExtension.isExtensionJar(jar)) {
            throw new IllegalArgumentException(
                jar.getFileName() + " is not an extension JAR (missing "
                    + "META-INF/pi-extension.json or META-INF/services/...PiExtension)");
        }
        return copy(jar, jar.getFileName().toString());
    }

    /** 安装远程扩展：下载到扩展目录并返回展示记录。 */
    public InstalledExtension install(URI source) {
        if ("file".equals(source.getScheme())) {
            return install(Path.of(source));
        }
        if (!"http".equals(source.getScheme()) && !"https".equals(source.getScheme())) {
            throw new IllegalArgumentException("Unsupported source scheme: " + source.getScheme());
        }
        var fileName = Path.of(source.getPath()).getFileName().toString();
        if (fileName.isBlank() || !fileName.endsWith(".jar")) {
            throw new IllegalArgumentException(
                "Extension URL must point to a .jar file: " + source);
        }
        var tmp = dir.resolve(fileName + ".download");
        try {
            Files.createDirectories(dir);
            var client = HttpClient.newBuilder().followRedirects(
                HttpClient.Redirect.NORMAL).build();
            var response = client.send(
                HttpRequest.newBuilder(source).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException(
                    "Download failed with HTTP " + response.statusCode() + ": " + source);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!InstalledExtension.isExtensionJar(tmp)) {
                Files.deleteIfExists(tmp);
                throw new IllegalArgumentException(
                    fileName + " is not an extension JAR (missing "
                        + "META-INF/pi-extension.json or META-INF/services/...PiExtension)");
            }
            return moveIntoPlace(tmp, fileName);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download interrupted: " + source, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 按 manifest name 或文件名移除；返回是否移除成功。 */
    public boolean remove(String nameOrFile) {
        var target = resolve(nameOrFile);
        if (target.isEmpty()) {
            return false;
        }
        try {
            Files.deleteIfExists(target.get());
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 重新安装来源（本地路径或 URL），覆盖同名文件。 */
    public Optional<InstalledExtension> update(String source) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return Optional.of(install(URI.create(source)));
        }
        var path = Path.of(source);
        if (Files.isRegularFile(path)) {
            return Optional.of(install(path));
        }
        return Optional.empty();
    }

    private InstalledExtension copy(Path jar, String fileName) {
        try {
            Files.createDirectories(dir);
            var target = dir.resolve(fileName);
            Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
            return InstalledExtension.of(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InstalledExtension moveIntoPlace(Path tmp, String fileName) {
        try {
            var target = dir.resolve(fileName);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return InstalledExtension.of(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 按 manifest name 或文件名（带或不带 {@code .jar} 后缀）解析已安装 JAR。 */
    private Optional<Path> resolve(String nameOrFile) {
        var name = nameOrFile.endsWith(".jar") ? nameOrFile : nameOrFile + ".jar";
        for (var installed : list()) {
            if (installed.fileName().equals(name) || installed.fileName().equals(nameOrFile)
                    || installed.name().equals(nameOrFile)) {
                return Optional.of(installed.path());
            }
        }
        return Optional.empty();
    }
}
