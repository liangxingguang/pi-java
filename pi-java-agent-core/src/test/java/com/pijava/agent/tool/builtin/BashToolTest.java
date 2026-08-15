package com.pijava.agent.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BashToolTest {

    @Test
    void descriptionAlwaysPromisesRealBash() {
        var tool = BashTool.create();
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertThat(tool.description())
                .as("Windows hosts run the command in Git Bash")
                .contains("Git Bash")
                .contains("ls -la")
                .contains("No bash shell found");
        } else {
            assertThat(tool.description())
                .as("POSIX hosts run the command in bash")
                .contains("bash");
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
