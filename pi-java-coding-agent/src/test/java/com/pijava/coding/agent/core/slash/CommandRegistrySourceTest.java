package com.pijava.coding.agent.core.slash;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 20（B237，T1a）：{@link CommandRegistry} 来源标签——pi 的扩展命令表与
 * TUI 内置件是两个注册面（agent-session.ts:1978-1984／interactive-mode.ts:
 * 4771-4779），java 同池 ⇒ 标签区分执行面。
 */
class CommandRegistrySourceTest {

    private static SlashCommand cmd(String name) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " desc"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext context) {
                return CompletableFuture.completedFuture("ran:" + name + ":" + args);
            }
        };
    }

    @Test
    void defaultRegistrationIsBuiltinAndDispatches() {
        var registry = new CommandRegistry();
        registry.register(cmd("model"));
        assertThat(registry.extensionCommand("/model")).isNull();
        assertThat(registry.dispatch("/model x", null))
            .isNotNull()
            .succeedsWithin(java.time.Duration.ofSeconds(1))
            .isEqualTo("ran:model:x");
    }

    @Test
    void extensionViewRegistrationIsTaggedAndSkippedByDispatch() {
        var registry = new CommandRegistry();
        registry.extensionView().register(cmd("hello"));
        // pi：扩展命令不入 TUI 分发池——dispatch 返回 null 落 processPrompt 臂。
        assertThat(registry.dispatch("/hello world", null)).isNull();
        // 补全/列举面不受影响（同池共享存储）。
        assertThat(registry.names()).contains("hello");
        assertThat(registry.get("hello")).isNotNull();
    }

    @Test
    void extensionCommandParsesNameWithLiteralSpaceTwoCut() {
        var registry = new CommandRegistry();
        registry.extensionView().register(cmd("hello"));
        // pi agent-session.ts:2118-2120 逐字：indexOf(" ") 两刀——
        // "/hello a b" ⇒ name "hello"；"/hello" ⇒ "hello"。
        assertThat(registry.extensionCommand("/hello a b")).isNotNull();
        assertThat(registry.extensionCommand("/hello")).isNotNull();
        assertThat(registry.extensionCommand("/hello\ttab"))
            .as("制表符不是 pi 的 indexOf(\" \") 切分符").isNull();
        assertThat(registry.extensionCommand("hello")).isNull();
        assertThat(registry.extensionCommand("/other")).isNull();
        assertThat(registry.extensionCommand(null)).isNull();
    }

    @Test
    void builtinShadowedByExtensionKeepsBothNamespaces() {
        var registry = new CommandRegistry();
        registry.register(cmd("model"));
        var extension = cmd("model");
        registry.extensionView().register(extension);
        // pi 双命名空间：TUI 分发内置赢……
        assertThat(registry.dispatch("/model", null))
            .succeedsWithin(java.time.Duration.ofSeconds(1))
            .isEqualTo("ran:model:");
        // ……prompt 臂扩展赢（撞名旁表）。
        assertThat(registry.extensionCommand("/model")).isSameAs(extension);
    }

    @Test
    void builtinReRegistrationOverridesShadow() {
        var registry = new CommandRegistry();
        registry.register(cmd("model"));
        registry.extensionView().register(cmd("model"));
        registry.register(cmd("model"));
        assertThat(registry.extensionCommand("/model")).isNull();
    }

    @Test
    void unregisterRemovesBothNamespaces() {
        var registry = new CommandRegistry();
        registry.register(cmd("model"));
        registry.extensionView().register(cmd("model"));
        registry.unregister("model");
        assertThat(registry.extensionCommand("/model")).isNull();
        assertThat(registry.get("model")).isNull();
    }

    @Test
    void extensionViewSharesStorageWithMainRegistry() {
        var registry = new CommandRegistry();
        registry.extensionView().register(cmd("shared"));
        // 视图查询与主表一致（dispatch 的 EXTENSION 跳过臂经同一存储）。
        assertThat(registry.names()).containsExactly("shared");
        registry.unregister("shared");
        assertThat(registry.extensionView().names()).isEmpty();
    }
}
