package com.pijava.coding.agent.extension;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExtensionManager.sessionStartResources — 会话开始资源贡献收集 + 异常隔离。
 */
class ExtensionManagerSessionStartResourcesTest {

    @TempDir
    Path tmp;

    /** 贡献资源路径的测试扩展。 */
    public static final class TestResourceExtension implements PiExtension {
        @Override
        public String name() {
            return "test-resources";
        }

        @Override
        public void register(ExtensionContext ctx) {
            // no-op
        }

        @Override
        public ResourcePaths sessionStartResources() {
            return new ResourcePaths(
                List.of(Path.of("skills"), Path.of("skills2")),
                List.of(Path.of("prompts")),
                List.of(Path.of("themes")));
        }
    }

    /** 第二个资源扩展（不同名，路径有交集，验证跨扩展合并去重）。 */
    public static final class TestResourceExtensionB implements PiExtension {
        @Override
        public String name() {
            return "test-resources-b";
        }

        @Override
        public void register(ExtensionContext ctx) {
            // no-op
        }

        @Override
        public ResourcePaths sessionStartResources() {
            return new ResourcePaths(
                List.of(Path.of("skills2"), Path.of("skills3")),
                List.of(Path.of("prompts2")),
                List.of());
        }
    }

    /** 抛异常的测试扩展（应被隔离，贡献视为空）。 */
    public static final class ThrowingResourceExtension implements PiExtension {
        @Override
        public String name() {
            return "test-throwing";
        }

        @Override
        public void register(ExtensionContext ctx) {
            // no-op
        }

        @Override
        public ResourcePaths sessionStartResources() {
            throw new IllegalStateException("boom");
        }
    }

    /** 默认实现（none）的测试扩展。 */
    public static final class NoneResourceExtension implements PiExtension {
        @Override
        public String name() {
            return "test-none";
        }

        @Override
        public void register(ExtensionContext ctx) {
            // no-op
        }
    }

    private ExtensionContext context() {
        var services = new SessionServices(
            SettingsManager.load(null),
            new TrustManager("none"),
            ProviderRegistry.create(),
            new DefaultModelResolver(BuiltinCatalog.all()),
            new ToolRegistry(null),
            CommandRegistry.withBuiltins(),
            new MemorySessionRepository(),
            new PromptTemplateRegistry());
        return new DefaultExtensionContext(services, new SkillManager());
    }

    private ExtensionManager managerFor(Class<?>... extensionClasses) throws Exception {
        var servicesDir = Files.createDirectories(tmp.resolve("services"));
        var metaInf = Files.createDirectories(servicesDir.resolve("META-INF/services"));
        var svc = Files.writeString(
            metaInf.resolve("com.pijava.coding.agent.extension.PiExtension"), "");
        var sb = new StringBuilder();
        for (var cls : extensionClasses) {
            sb.append(cls.getName()).append('\n');
        }
        Files.writeString(svc, sb.toString());
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        var loader = new URLClassLoader(new URL[] {
            servicesDir.toUri().toURL(), testClasses.toUri().toURL()},
            ExtensionManager.class.getClassLoader());
        var manager = new ExtensionManager(context());
        manager.loadAll(loader);
        return manager;
    }

    @Test
    void collectsMergedResourcesFromExtensions() throws Exception {
        var manager = managerFor(TestResourceExtension.class);
        var paths = manager.sessionStartResources();
        assertThat(paths.skillPaths()).containsExactly(
            Path.of("skills"), Path.of("skills2"));
        assertThat(paths.promptPaths()).containsExactly(Path.of("prompts"));
        assertThat(paths.themePaths()).containsExactly(Path.of("themes"));
    }

    @Test
    void throwingExtensionIsIsolated() throws Exception {
        var manager = managerFor(ThrowingResourceExtension.class);
        var paths = manager.sessionStartResources();
        // 抛异常扩展贡献视为空，不阻断收集。
        assertThat(paths.isEmpty()).isTrue();
    }

    @Test
    void defaultImplementationContributesNone() throws Exception {
        var manager = managerFor(NoneResourceExtension.class);
        assertThat(manager.sessionStartResources().isEmpty()).isTrue();
    }

    @Test
    void mergesMultipleExtensionsInOrder() throws Exception {
        var manager = managerFor(TestResourceExtension.class, TestResourceExtensionB.class);
        var paths = manager.sessionStartResources();
        // A 在前 B 在后，保序去重：skills skills2 | skills3。
        assertThat(paths.skillPaths()).containsExactly(
            Path.of("skills"), Path.of("skills2"), Path.of("skills3"));
        assertThat(paths.promptPaths()).containsExactly(
            Path.of("prompts"), Path.of("prompts2"));
        assertThat(paths.themePaths()).containsExactly(Path.of("themes"));
    }
}
