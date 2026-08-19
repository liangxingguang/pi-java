package com.pijava.coding.agent.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 测试工具：构建含 TestExtension 的扩展 JAR（services 标记 + pi-extension.json 清单）。
 */
public final class TestExtensionJar {

    private TestExtensionJar() {}

    /** 构建标准测试扩展 JAR。 */
    public static Path build(Path target) throws Exception {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (var jos = new JarOutputStream(Files.newOutputStream(target))) {
            jos.putNextEntry(new JarEntry(
                "META-INF/services/com.pijava.coding.agent.extension.PiExtension"));
            jos.write("com.pijava.coding.agent.extension.TestExtension".getBytes());
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/pi-extension.json"));
            jos.write(("{\"name\":\"test-ext\",\"version\":\"1.0.0\","
                + "\"description\":\"test extension\",\"commands\":[\"sample\"]}")
                .getBytes());
            jos.closeEntry();

            Path classFile = Path.of(TestExtension.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI())
                .resolve("com/pijava/coding/agent/extension/TestExtension.class");
            jos.putNextEntry(new JarEntry("com/pijava/coding/agent/extension/TestExtension.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }
        return target;
    }

    /** 构建一个无扩展标记的普通 JAR（校验应拒绝）。 */
    public static Path buildPlain(Path target) throws Exception {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (var jos = new JarOutputStream(Files.newOutputStream(target))) {
            jos.putNextEntry(new JarEntry("README.txt"));
            jos.write("not an extension".getBytes());
            jos.closeEntry();
        }
        return target;
    }
}
