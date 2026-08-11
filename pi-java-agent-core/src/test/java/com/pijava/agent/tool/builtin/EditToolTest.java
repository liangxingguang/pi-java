package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class EditToolTest {

    private static ToolContext contextFor(Path cwd) {
        return new ToolContext(cwd.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    @Test
    void replacesText(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("edit.txt");
        Files.writeString(file, "Hello World");
        var tool = EditTool.create();
        tool.execute("id1", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("World", "Java"))),
            null, null, contextFor(tmp));
        assertThat(Files.readString(file)).isEqualTo("Hello Java");
    }

    @Test
    void createsBackupFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("edit.txt");
        Files.writeString(file, "original");
        var tool = EditTool.create();
        tool.execute("id1", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("original", "modified"))),
            null, null, contextFor(tmp));
        assertThat(Files.exists(Path.of(file.toString() + ".bak"))).isTrue();
        assertThat(Files.readString(Path.of(file.toString() + ".bak"))).isEqualTo("original");
    }
}
