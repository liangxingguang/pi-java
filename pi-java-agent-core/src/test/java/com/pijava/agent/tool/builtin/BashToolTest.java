package com.pijava.agent.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BashToolTest {

    @Test
    void descriptionNamesTheShellForTheCurrentPlatform() {
        var tool = BashTool.create();
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertThat(tool.description())
                .as("Windows hosts run the command in cmd.exe")
                .contains("cmd.exe")
                .contains("`dir`")
                .contains("`pwd`");
        } else {
            assertThat(tool.description())
                .as("POSIX hosts run the command in sh")
                .contains("sh (POSIX shell)");
        }
    }

    @Test
    void schemaRequiresCommand() {
        var schema = BashTool.create().inputSchema();
        assertThat(schema.get("required")).isNotNull();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).contains("command");
    }
}
