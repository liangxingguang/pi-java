package com.pijava.coding.agent.core.slash;

import java.util.concurrent.CompletableFuture;

import com.pijava.coding.agent.core.source.SourceInfo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 23（docs/34 T4）：扩展命令撞名 {@code :N} 编号（pi runner.ts:805-840 逐字）＋
 * 注册盖章（loader.ts:303-319）＋分发语义（编号名可调、撞名裸名不可调）。
 */
class ExtensionCommandNumberingTest {

    private static final SourceInfo STAMP_A = new SourceInfo(
        "/ext/a.jar", "local", SourceInfo.Scope.TEMPORARY,
        SourceInfo.Origin.TOP_LEVEL, null);
    private static final SourceInfo STAMP_B = new SourceInfo(
        "/ext/b.jar", "local", SourceInfo.Scope.TEMPORARY,
        SourceInfo.Origin.TOP_LEVEL, null);

    private static SlashCommand cmd(String name) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "desc-" + name;
            }

            @Override
            public String argumentHint() {
                return "";
            }

            @Override
            public CompletableFuture<String> execute(String args, SlashContext context) {
                return CompletableFuture.completedFuture("ran:" + name);
            }
        };
    }

    @Test
    void singleRegistrationKeepsBareName() {
        var registry = new CommandRegistry();
        registry.extensionView("/ext/a.jar", STAMP_A).register(cmd("hello"));
        var resolved = registry.resolvedExtensionCommands();
        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().invocationName()).isEqualTo("hello");
        assertThat(resolved.getFirst().sourceInfo()).isSameAs(STAMP_A);
        assertThat(registry.extensionCommand("/hello")).isNotNull();
    }

    @Test
    void collidingNamesGetNumberedAndBareNameIsNotInvocable() {
        var registry = new CommandRegistry();
        registry.extensionView("/ext/a.jar", STAMP_A).register(cmd("dup"));
        registry.extensionView("/ext/b.jar", STAMP_B).register(cmd("dup"));
        var resolved = registry.resolvedExtensionCommands();
        assertThat(resolved.stream().map(r -> r.invocationName()))
            .containsExactly("dup:1", "dup:2");
        // pi getCommand 只按 invocationName 命中——裸名撞名时不可调。
        assertThat(registry.extensionCommand("/dup")).isNull();
        assertThat(registry.extensionCommand("/dup:2")).isNotNull();
        assertThat(registry.extensionCommand("/dup:2 extra args")).isNotNull();
    }

    @Test
    void sameExtensionReRegistrationReplacesWithoutNumbering() {
        var registry = new CommandRegistry();
        var view = registry.extensionView("/ext/a.jar", STAMP_A);
        view.register(cmd("mine"));
        view.register(cmd("mine"));
        assertThat(registry.resolvedExtensionCommands())
            .extracting(r -> r.invocationName())
            .containsExactly("mine");
    }

    @Test
    void suffixSkipsTakenNumberedNames() {
        // pi runner.ts:828-836：name:2 亦被占 ⇒ 自增到空闲。
        var registry = new CommandRegistry();
        registry.extensionView("/ext/a.jar", STAMP_A).register(cmd("dup"));
        registry.extensionView("/ext/b.jar", STAMP_B).register(cmd("dup"));
        // 第三个扩展注册了字面名 "dup:2"：解析按出现序——a⇒dup:1、b⇒dup:2，
        // c 的裸名 "dup:2" 唯一（无编号）但 taken 已被 b 占 ⇒ 从 occurrence=1
        // 自增 ⇒ "dup:2:2"（pi runner.ts:828-836 逐字）。
        registry.extensionView("/ext/c.jar", null).register(cmd("dup:2"));
        var names = registry.resolvedExtensionCommands().stream()
            .map(r -> r.invocationName()).toList();
        assertThat(names).containsExactly("dup:1", "dup:2", "dup:2:2");
    }

    @Test
    void unregisterDropsCatalogEntries() {
        var registry = new CommandRegistry();
        registry.extensionView("/ext/a.jar", STAMP_A).register(cmd("gone"));
        registry.unregister("gone");
        assertThat(registry.resolvedExtensionCommands()).isEmpty();
    }

    @Test
    void builtinCollisionKeepsExtensionReachableByBareName() {
        var registry = CommandRegistry.withBuiltins();
        registry.extensionView("/ext/a.jar", STAMP_A).register(cmd("model"));
        // 单注册 ⇒ invocationName 即裸名，prompt 臂扩展赢（批 20 语义不变）。
        assertThat(registry.extensionCommand("/model")).isNotNull();
        assertThat(registry.extensionCommand("/model").description())
            .isEqualTo("desc-model");
        // TUI 分发内置赢（旁表机制不动）。
        assertThat(registry.get("model").description()).isNotEqualTo("desc-model");
    }
}
