package com.pijava.coding.agent.extension;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.pijava.agent.session.memory.MemorySessionRepository;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-7: ExtensionManager — ServiceLoader 发现、注册、unload、loadJar。
 */
class ExtensionManagerTest {

    @TempDir
    Path tmp;

    @Test
    void discoversAndLoadsFromServiceLoader() throws Exception {
        var context = context();
        var manager = new ExtensionManager(context);

        // 作用域 classloader：临时目录含 META-INF/services + test-classes（TestExtension）
        var servicesDir = Files.createDirectories(tmp.resolve("services"));
        Files.createDirectories(servicesDir.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("META-INF/services/com.pijava.coding.agent.extension.PiExtension"),
            "com.pijava.coding.agent.extension.TestExtension");
        Path testClasses = Path.of(TestExtension.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        try (var loader = new URLClassLoader(new URL[] {
                servicesDir.toUri().toURL(), testClasses.toUri().toURL()},
                ExtensionManager.class.getClassLoader())) {
            var names = manager.loadAll(loader);
            assertThat(names).contains("test-ext");
        }

        assertThat(context.slashCommands().get("sample")).isNotNull();
        assertThat(context.providers().get("faux")).isNotNull();
        assertThat(manager.loadedNames()).contains("test-ext");

        manager.unload("test-ext");
        assertThat(manager.loadedNames()).isEmpty();
    }

    @Test
    void loadJarFromExternalJar() throws Exception {
        var jar = buildTestJar();
        var context = context();
        var manager = new ExtensionManager(context);

        var names = manager.loadJar(jar);
        assertThat(names).contains("test-ext");
        assertThat(context.slashCommands().get("sample")).isNotNull();
    }

    @Test
    void manifestFromJar() throws Exception {
        var jar = buildTestJar();
        var manifest = ExtensionManifest.from(jar).orElseThrow();
        assertThat(manifest.name()).isEqualTo("test-ext");
        assertThat(manifest.commands()).contains("sample");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private DefaultExtensionContext context() {
        var providers = ProviderRegistry.create();
        var tools = new ToolRegistry(null);
        var commands = CommandRegistry.withBuiltins();
        var services = new SessionServices(
            SettingsManager.load(null),
            new TrustManager("none"),
            providers,
            new DefaultModelResolver(BuiltinCatalog.all()),
            tools, commands, new MemorySessionRepository());
        return new DefaultExtensionContext(services, new SkillManager());
    }

    /** 构建一个含 TestExtension.class + services + 清单的 jar。 */
    private Path buildTestJar() throws Exception {
        var jar = tmp.resolve("test-ext.jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry(
                "META-INF/services/com.pijava.coding.agent.extension.PiExtension"));
            jos.write("com.pijava.coding.agent.extension.TestExtension".getBytes());
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/pi-extension.json"));
            jos.write(("{\"name\":\"test-ext\",\"version\":\"1.0.0\","
                + "\"description\":\"test\",\"commands\":[\"sample\"]}")
                .getBytes());
            jos.closeEntry();

            // 把编译好的 TestExtension.class 打进去
            Path classFile = Path.of(TestExtension.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI())
                .resolve("com/pijava/coding/agent/extension/TestExtension.class");
            jos.putNextEntry(new JarEntry("com/pijava/coding/agent/extension/TestExtension.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }
        return jar;
    }
}
