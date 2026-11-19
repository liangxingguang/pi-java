package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/54 §2.6：tool-override 验收——7 条阻断正则、拒绝/截断/切片文案、日志/命令。 */
class ToolOverrideExtensionTest {

    @TempDir Path tmp;
    private String oldAgentDir;

    @BeforeEach
    void setUp() {
        oldAgentDir = System.getProperty("pi.java.agentDir");
        System.setProperty("pi.java.agentDir", tmp.toString());
    }

    @AfterEach
    void tearDown() {
        if (oldAgentDir == null) {
            System.clearProperty("pi.java.agentDir");
        } else {
            System.setProperty("pi.java.agentDir", oldAgentDir);
        }
    }

    @Test
    void blockedPatterns() {
        assertThat(ToolOverrideExtension.isBlockedPath(".env")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("config/.env.production")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("secrets.json")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("CREDENTIALS.YML")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("a/.ssh/id_rsa")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("a/.aws/creds")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("a/.gnupg/key")).isTrue();
        assertThat(ToolOverrideExtension.isBlockedPath("src/Main.java")).isFalse();
    }

    @Test
    void blockedReadDeniesVerbatim() throws Exception {
        var w = new W3Context();
        var file = tmp.resolve("secrets.json");
        Files.writeString(file, "token");

        var result = ToolOverrideExtension.read(w.ctx, file, null, null, true);

        assertThat(ExampleFixtures.textOf(result.content()))
            .contains("matches a blocked pattern (sensitive file)")
            .contains("SSH/AWS/GPG directories");
        assertThat(result.details()).containsEntry("blocked", true);
        assertThat(result.isError()).isTrue();
    }

    @Test
    void offsetLimitSliceAndLog() throws Exception {
        var w = new W3Context();
        var file = tmp.resolve("data.txt");
        Files.writeString(file, "l1\nl2\nl3\nl4\nl5");

        var result = ToolOverrideExtension.read(w.ctx, file, 2, 2, false);

        assertThat(ExampleFixtures.textOf(result.content())).isEqualTo("l2\nl3");
        var log = Files.readString(ToolOverrideExtension.logFile(w.ctx));
        assertThat(log).contains("ALLOWED: " + file);
    }

    @Test
    void truncationAt50Kb() throws Exception {
        var w = new W3Context();
        var file = tmp.resolve("big.txt");
        Files.writeString(file, "x".repeat(ToolOverrideExtension.MAX_BYTES + 100));

        var result = ToolOverrideExtension.read(w.ctx, file, null, null, false);

        assertThat(ExampleFixtures.textOf(result.content())).contains("[Output truncated at 50KB]");
    }

    @Test
    void registrationAndLogCommand() throws Exception {
        var w = new W3Context();
        var ext = new ToolOverrideExtension();
        ext.register(w.ctx);

        assertThat(w.tools.toolNames()).contains("read");
        assertThat(w.tools.get("read").label()).isEqualTo("read (audited)");
        w.command("read-log", "");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No access log found");
    }
}
