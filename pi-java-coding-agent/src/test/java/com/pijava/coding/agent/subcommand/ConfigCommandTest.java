package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.pijava.coding.agent.core.FileSettingsStorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-14: ConfigCommand — 资源开关 enable/disable/list（注入临时 storage）。
 */
class ConfigCommandTest {

    @TempDir
    Path tmp;

    @Test
    void enableAddsResourceEntry() {
        var storage = storage();

        var out = capture(() -> ConfigCommand.run(
            new String[] {"enable", "skills", "my-skill"}, storage));

        assertThat(out).contains("Enabled my-skill for skills (global)");
        assertThat(storage.readGlobal().skills).contains("my-skill");
    }

    @Test
    void disableRemovesResourceEntry() {
        var storage = storage();
        ConfigCommand.run(new String[] {"enable", "extensions", "my-ext"}, storage);

        var out = capture(() -> ConfigCommand.run(
            new String[] {"disable", "extensions", "my-ext"}, storage));

        assertThat(out).contains("Disabled my-ext for extensions (global)");
        assertThat(storage.readGlobal().extensions).doesNotContain("my-ext");
    }

    @Test
    void localScopeWritesProjectSettings() {
        var storage = storage();

        ConfigCommand.run(new String[] {"enable", "prompts", "review", "-l"}, storage);

        assertThat(storage.readProject().prompts).contains("review");
        // 全局文件未被触碰（不存在 → prompts 为 null）
        assertThat(storage.readGlobal().prompts).isNull();
    }

    @Test
    void listPrintsSwitches() {
        var storage = storage();
        ConfigCommand.run(new String[] {"enable", "themes", "solarized"}, storage);

        var out = capture(() -> ConfigCommand.run(new String[] {}, storage));

        assertThat(out).contains("Resource switches (effective)")
            .contains("themes: [solarized]");
    }

    @Test
    void unknownResourceRejected() {
        var out = capture(() -> ConfigCommand.run(
            new String[] {"enable", "bogus", "x"}, storage()));

        assertThat(out).contains("Unknown resource");
    }

    private FileSettingsStorage storage() {
        return new FileSettingsStorage(
            tmp.resolve("agent"), tmp.resolve("proj"));
    }

    private static String capture(Runnable action) {
        var buffer = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
