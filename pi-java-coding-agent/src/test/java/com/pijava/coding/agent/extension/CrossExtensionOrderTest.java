package com.pijava.coding.agent.extension;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.session.memory.MemorySessionRepository;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 §7.7：跨扩展触发序＝加载序（pi {@code snapshotEventHandlers} 的
 * 「扩展加载序 × 扩展内注册序」，runner.ts:269-271）——两个扩展经 ServiceLoader
 * 装配、各自 {@code on()} 订阅同名事件，触发序必须是装配序。
 */
class CrossExtensionOrderTest {

    /** 两个扩展共享的触发记录（ServiceLoader 无参构造 ⇒ 静态汇）。 */
    static final List<String> ORDER = new CopyOnWriteArrayList<>();

    /** 先加载的扩展。 */
    public static final class ExtA implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public ExtA() {}
        @Override public String name() { return "ext-a"; }
        @Override public void register(ExtensionContext ctx) {
            ctx.on(ExtensionEventSpec.AGENT_START, (event, c) -> {
                ORDER.add("a");
                return null;
            });
        }
    }

    /** 后加载的扩展。 */
    public static final class ExtB implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public ExtB() {}
        @Override public String name() { return "ext-b"; }
        @Override public void register(ExtensionContext ctx) {
            ctx.on(ExtensionEventSpec.AGENT_START, (event, c) -> {
                ORDER.add("b");
                return null;
            });
        }
    }

    @TempDir
    Path tmp;

    @Test
    void crossExtensionFireOrderIsLoadOrder() throws Exception {
        ORDER.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            CrossExtensionOrderTest.ExtA.class.getName() + "\n"
                + CrossExtensionOrderTest.ExtB.class.getName() + "\n");
        Path testClasses = Path.of(CrossExtensionOrderTest.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());

        var bus = new ExtensionEventBus();
        var context = context(bus);
        var manager = new ExtensionManager(context);
        try (var loader = new URLClassLoader(new URL[] {
                tmp.toUri().toURL(), testClasses.toUri().toURL()},
                ExtensionManager.class.getClassLoader())) {
            manager.loadAll(loader);
        }
        assertThat(manager.loadedNames()).containsExactlyInAnyOrder("ext-a", "ext-b");

        for (var h : bus.snapshot(ExtensionEventSpec.AGENT_START)) {
            try {
                h.handle(new AgentStartEvent(), context);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        assertThat(ORDER).containsExactly("a", "b");
    }

    private static DefaultExtensionContext context(ExtensionEventBus bus) {
        var providers = ProviderRegistry.create();
        var services = new SessionServices(
            SettingsManager.load(null),
            new TrustManager("none"),
            providers,
            new DefaultModelResolver(ProviderCatalog.allModels()),
            new ToolRegistry(null), CommandRegistry.withBuiltins(),
            new MemorySessionRepository(),
            new PromptTemplateRegistry());
        return new DefaultExtensionContext(services, new SkillManager(), null, bus);
    }
}
