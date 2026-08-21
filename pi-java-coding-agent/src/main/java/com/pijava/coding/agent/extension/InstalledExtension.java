package com.pijava.coding.agent.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/**
 * 已安装扩展的展示记录，供 {@code pi-java package list} 输出。
 *
 * <p>优先从 JAR 内 {@code META-INF/pi-extension.json} 读取清单；无清单时
 * {@code name} 回落为去掉 {@code .jar} 后缀的文件名，其余字段为空。</p>
 */
public record InstalledExtension(
    Path path,
    String fileName,
    String name,
    String version,
    String description,
    List<String> tools,
    List<String> commands,
    List<String> providers,
    List<String> skills
) {

    /** 从扩展目录中的 JAR 构建展示记录。 */
    public static InstalledExtension of(Path jar) {
        Path fileNamePath = jar.getFileName();
        String fileName = fileNamePath == null ? jar.toString() : fileNamePath.toString();
        var manifest = ExtensionManifest.from(jar);
        var name = manifest.map(ExtensionManifest::name)
            .filter(v -> v != null && !v.isBlank())
            .orElseGet(() -> stripJarSuffix(fileName));
        return new InstalledExtension(
            jar,
            fileName,
            name,
            manifest.map(ExtensionManifest::version).orElse(""),
            manifest.map(ExtensionManifest::description).orElse(""),
            manifest.map(ExtensionManifest::tools).orElse(List.of()),
            manifest.map(ExtensionManifest::commands).orElse(List.of()),
            manifest.map(ExtensionManifest::providers).orElse(List.of()),
            manifest.map(ExtensionManifest::skills).orElse(List.of()));
    }

    private static String stripJarSuffix(String fileName) {
        return fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;
    }

    /**
     * 校验 JAR 是否是可安装扩展：含 {@code META-INF/pi-extension.json} 或
     * {@code META-INF/services/...PiExtension} 之一。
     */
    public static boolean isExtensionJar(Path jar) {
        try (var jf = new JarFile(jar.toFile())) {
            if (jf.getJarEntry("META-INF/pi-extension.json") != null) {
                return true;
            }
            return jf.getJarEntry(
                "META-INF/services/com.pijava.coding.agent.extension.PiExtension") != null;
        } catch (IOException e) {
            return false;
        }
    }
}
