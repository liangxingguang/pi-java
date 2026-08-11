package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class WriteToolTest {

    private static ToolContext contextFor(Path cwd) {
        return new ToolContext(cwd.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    @Test
    void writesFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("output.txt");
        var tool = WriteTool.create();
        tool.execute("id1", new WriteTool.WriteInput(file.toString(), "created content"),
            null, null, contextFor(tmp));
        assertThat(Files.readString(file)).isEqualTo("created content");
    }

    @Test
    void createsParentDirectories(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("a").resolve("b").resolve("c.txt");
        var tool = WriteTool.create();
        tool.execute("id1", new WriteTool.WriteInput(file.toString(), "nested"),
            null, null, contextFor(tmp));
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file)).isEqualTo("nested");
    }

    @Test
    void overwritesExistingFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("overwrite.txt");
        Files.writeString(file, "old");
        var tool = WriteTool.create();
        tool.execute("id1", new WriteTool.WriteInput(file.toString(), "new"),
            null, null, contextFor(tmp));
        assertThat(Files.readString(file)).isEqualTo("new");
    }
}
