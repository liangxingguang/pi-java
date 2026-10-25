package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.theme.ThemeToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 21（T8/T9，docs/32）：主题装配链——{@code --theme} CLI 车道进发现、
 * 装载结果在 {@code session.themes()}、诊断进 startupDiagnostics。
 */
class SessionThemeAssemblyTest {

    @TempDir
    Path tmp;

    private AgentSession session(String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    private Path writeTheme(String fileName, String name) throws Exception {
        var colors = new StringBuilder();
        for (var token : ThemeToken.values()) {
            if (token.fallback() == null) {
                if (colors.length() > 0) {
                    colors.append(',');
                }
                colors.append('"').append(token.piName()).append("\":\"#102030\"");
            }
        }
        var path = tmp.resolve(fileName);
        Files.writeString(path, "{\"name\":\"" + name + "\",\"colors\":{" + colors + "}}");
        return path;
    }

    @Test
    void cliThemePathIsDiscoveredAndLoaded() throws Exception {
        var themeFile = writeTheme("nord.json", "nord");
        try (var s = session("--theme", themeFile.toString())) {
            assertThat(s.themes()).hasSize(1);
            assertThat(s.themes().get(0).name()).isEqualTo("nord");
            assertThat(s.startupDiagnostics()).isEmpty();
        }
    }

    @Test
    void relativeCliThemePathResolvesAgainstCwd() throws Exception {
        // pi resolveCliPaths：本地路径对 cwd 解析（main.ts:555-556）。
        // @TempDir 在 C: 盘、cwd 在 D: 盘——跨盘不能 relativize，改用 target/ 下临时目录。
        var localDir = Files.createTempDirectory(
            Path.of(System.getProperty("user.dir"), "target"), "theme-rel-test");
        var colors = new StringBuilder();
        for (var token : ThemeToken.values()) {
            if (token.fallback() == null) {
                if (colors.length() > 0) {
                    colors.append(',');
                }
                colors.append('"').append(token.piName()).append("\":\"#102030\"");
            }
        }
        Files.writeString(localDir.resolve("rel.json"),
            "{\"name\":\"rel\",\"colors\":{" + colors + "}}");
        var relative = Path.of(System.getProperty("user.dir"))
            .relativize(localDir.resolve("rel.json"));
        try (var s = session("--theme", relative.toString())) {
            assertThat(s.themes()).hasSize(1);
            assertThat(s.themes().get(0).name()).isEqualTo("rel");
        }
    }

    @Test
    void missingCliThemePathYieldsWarningDiagnostic() {
        try (var s = session("--theme", tmp.resolve("ghost.json").toString())) {
            assertThat(s.themes()).isEmpty();
            assertThat(s.startupDiagnostics()).anySatisfy(d -> {
                assertThat(d.type()).isEqualTo("warning");
                assertThat(d.message()).contains("theme path does not exist");
            });
        }
    }

    @Test
    void themeLoadingIsNotGatedByNoExtensions() throws Exception {
        // pi：noThemes 只裁 resolve 臂；主题独立于扩展开关（resource-loader:633-635）。
        var themeFile = writeTheme("nord.json", "nord");
        try (var s = session("--no-extensions", "--theme", themeFile.toString())) {
            assertThat(s.themes()).hasSize(1);
        }
    }
}
