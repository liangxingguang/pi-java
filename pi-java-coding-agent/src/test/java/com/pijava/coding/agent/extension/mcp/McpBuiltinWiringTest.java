package com.pijava.coding.agent.extension.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.extension.ExtensionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 T1/T2）：内置扩展装配——ServiceLoader 发现 {@code builtin:mcp}
 * 身份、{@code --no-mcp} 过滤、{@code -e builtin:mcp} 显式臂（pi
 * extensions/index.ts＋main.ts:787＋resource-loader.ts:582 对位）。
 */
class McpBuiltinWiringTest {

    @Test
    void serviceLoaderDiscoversMcpExtension() {
        assertThat(ExtensionManager.builtinExtensionNames()).contains("mcp");
    }

    @Test
    void loadAllFiltersDisabledBuiltin() {
        var rig = McpExtensionTestRig.create(tempDir(), tempDir(), false, null);
        var manager = new ExtensionManager(rig.base);
        manager.loadAll(Set.of("mcp"));
        assertThat(manager.loadedNames()).doesNotContain("mcp");
    }

    @Test
    void loadBuiltinLoadsByNameOnce() {
        var rig = McpExtensionTestRig.create(tempDir(), tempDir(), false, null);
        var manager = new ExtensionManager(rig.base);
        assertThat(manager.loadBuiltin("mcp")).isTrue();
        // 重复显式加载 ⇒ 跳过（pi mergePaths 去重净效果）
        assertThat(manager.loadBuiltin("mcp")).isFalse();
        assertThat(manager.loadBuiltin("no-such-builtin")).isFalse();
        assertThat(manager.loadedNames()).contains("mcp");
    }

    @Test
    void noMcpFlagParses() {
        assertThat(ArgsParser.parse(new String[] {"--no-mcp"}).noMcp()).isTrue();
        assertThat(ArgsParser.parse(new String[] {}).noMcp()).isFalse();
    }

    private static java.nio.file.Path tempDir() {
        try {
            return Files.createTempDirectory("mcp-wiring");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
