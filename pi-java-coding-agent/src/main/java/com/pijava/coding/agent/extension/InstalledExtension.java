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

    /**
     * 批 19（裁决④善后）：从 resolve 装配的扩展资源路径枚举展示记录——
     * {@code builtin:} 前缀跳过；目录经 {@code resolveExtensionEntries} 取入口
     * JAR；其余按 JAR 处理。非法路径跳过（诊断面不崩）。
     */
    public static List<InstalledExtension> fromResolvedPaths(List<String> paths) {
        var out = new java.util.ArrayList<InstalledExtension>();
        for (var path : paths) {
            if (path.startsWith(com.pijava.coding.agent.pkg.PackageManager.BUILTIN_PATH_PREFIX)) {
                continue;
            }
            final Path p;
            try {
                p = Path.of(path);
            } catch (java.nio.file.InvalidPathException e) {
                continue;
            }
            if (java.nio.file.Files.isDirectory(p)) {
                var entries = com.pijava.coding.agent.pkg.PackageResourceScanner
                    .resolveExtensionEntries(p);
                if (entries != null) {
                    for (var entry : entries) {
                        out.add(of(Path.of(entry)));
                    }
                }
                continue;
            }
            out.add(of(p));
        }
        return out;
    }

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
